package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.GitDiffFile
import com.nekobot.app.data.model.GitDiffHunk
import com.nekobot.app.data.model.GitDiffLine
import com.nekobot.app.data.model.GitDiffSummary
import com.nekobot.app.data.model.ThinkingCard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentProgressReporterTest {

    @Test
    fun streamedReasoningIsAccumulatedInThinkingStep() {
        val updates = mutableListOf<ThinkingCard>()
        var now = 0L
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            nowNanos = { now.also { now += 200_000_000L } },
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "测试")
        )

        reporter.onThinkingStart(context)
        reporter.onThinkingContent(context, "先分析问题。")
        reporter.onThinkingContent(context, "再核对答案。")

        val card = updates.last()
        val thinkingStep = card.steps.single { it.type == "thinking" }
        assertEquals("先分析问题。再核对答案。", thinkingStep.thinkingContent)
        assertTrue(thinkingStep.detail.orEmpty().contains("再核对答案"))
        assertEquals("AI 正在思考...", card.content)
    }

    @Test
    fun highFrequencyReasoningIsCoalescedAndOnlyCheckpointedAtStableStates() {
        val updates = mutableListOf<ThinkingCard>()
        val checkpoints = mutableListOf<ThinkingCard>()
        var now = 1L
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            onCheckpoint = checkpoints::add,
            nowNanos = { now.also { now += 1_000_000L } },
            streamIntervalNanos = 120_000_000L,
            streamCharBatch = 96,
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "压力测试")
        )

        reporter.onThinkingStart(context)
        repeat(10_000) { reporter.onThinkingContent(context, "x") }
        reporter.onDone(context)

        val finalThinking = updates.last().steps.single { it.type == "thinking" }.thinkingContent.orEmpty()
        assertEquals(10_000, finalThinking.length)
        assertTrue("UI 更新不应随 token 数线性增长", updates.size < 150)
        assertEquals("流式分片不应逐条写数据库", 2, checkpoints.size)
        assertTrue(checkpoints.last().isComplete)
    }

    @Test
    fun liveProgressCardDoesNotRetainFullToolPayload() {
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "test")
        )

        reporter.onToolStart(
            context,
            toolName = "workspace_read_file",
            arguments = mapOf("path" to "a".repeat(50_000)),
            thinking = ""
        )
        reporter.onToolDone(
            context,
            toolName = "workspace_read_file",
            result = mapOf("content" to "x".repeat(500_000), "truncated" to true),
            thinking = ""
        )

        val toolStep = updates.last().steps.single { it.type == "tool" }
        assertTrue(
            toolStep.arguments?.get("preview").toString().length <=
                AgentToolLimits.progressPreviewChars()
        )
        assertTrue(toolStep.fullResult.toString().length <= AgentToolLimits.progressPreviewChars())
        assertTrue(toolStep.resultTruncated == true)
    }

    @Test
    fun thinkingIsSplitIntoOneStepPerRoundInChronologicalOrder() {
        val updates = mutableListOf<ThinkingCard>()
        var now = 0L
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            nowNanos = { now.also { now += 200_000_000L } },
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "测试")
        )

        // 第 1 轮：思考 → 工具
        reporter.onThinkingStart(context)
        reporter.onToolIteration(context, 0)
        reporter.onThinkingContent(context, "先看目录。")
        reporter.onToolStart(context, toolName = "list_dir", arguments = mapOf("path" to "."), thinking = "")
        reporter.onToolDone(context, toolName = "list_dir", result = mapOf("stdout" to "a.kt"), thinking = "")
        // 第 2 轮：思考 → 工具
        reporter.onToolIteration(context, 1)
        reporter.onThinkingContent(context, "再读文件。")
        reporter.onToolStart(context, toolName = "read_file", arguments = mapOf("path" to "a.kt"), thinking = "")
        reporter.onToolDone(context, toolName = "read_file", result = mapOf("content" to "x"), thinking = "")

        val steps = updates.last().steps
        // 思考按时间顺序夹在工具之间，而不是全部堆在卡片顶部
        assertEquals(listOf("thinking", "tool", "thinking", "tool"), steps.map { it.type })
        assertEquals(
            listOf("先看目录。", "再读文件。"),
            steps.filter { it.type == "thinking" }.map { it.thinkingContent }
        )
        assertTrue(steps.filter { it.type == "thinking" }.all { it.status == "done" })
    }

    @Test
    fun intermediateReplyIsArchivedAsHeaderlessStepInChronologicalOrder() {
        val updates = mutableListOf<ThinkingCard>()
        var now = 0L
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            nowNanos = { now.also { now += 200_000_000L } },
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "测试")
        )

        // 思考 → 中间回复 → 工具：中间回复按时间顺序留档，且不带头部
        reporter.onThinkingStart(context)
        reporter.onThinkingContent(context, "先看目录。")
        reporter.onIntermediateContent(context, "我先看一下目录结构。")
        reporter.onToolStart(context, toolName = "list_dir", arguments = mapOf("path" to "."), thinking = "")
        reporter.onToolDone(context, toolName = "list_dir", result = mapOf("stdout" to "a.kt"), thinking = "")

        val steps = updates.last().steps
        assertEquals(listOf("thinking", "agent_text", "tool"), steps.map { it.type })
        val archived = steps.single { it.type == "agent_text" }
        assertEquals("我先看一下目录结构。", archived.text)
        assertEquals("done", archived.status)
        assertTrue("中间回复不应带名称/头部", archived.name.isNullOrBlank())
    }

    @Test
    fun intermediateReplyLeavesNoEmptyThinkingPlaceholder() {
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "测试")
        )

        // 思考强度关闭：模型直接输出中间回复并调用工具，不应留下空的思考占位
        reporter.onThinkingStart(context)
        reporter.onIntermediateContent(context, "马上执行。")
        reporter.onToolStart(context, toolName = "exec_command", arguments = emptyMap(), thinking = "")
        reporter.onToolDone(context, toolName = "exec_command", result = mapOf("stdout" to "ok"), thinking = "")

        val steps = updates.last().steps
        assertTrue("没有思考正文时不应留下空气泡", steps.none { it.type == "thinking" })
        assertEquals(listOf("agent_text", "tool"), steps.map { it.type })
    }

    @Test
    fun roundsWithoutReasoningContentLeaveNoEmptyThinkingSteps() {        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "测试")
        )

        // 思考强度关闭时模型不产出思考：两轮都不应留下空的思考占位
        reporter.onThinkingStart(context)
        reporter.onToolIteration(context, 0)
        reporter.onToolStart(context, toolName = "exec_command", arguments = emptyMap(), thinking = "")
        reporter.onToolDone(context, toolName = "exec_command", result = mapOf("stdout" to "ok"), thinking = "")
        reporter.onToolIteration(context, 1)
        reporter.onToolStart(context, toolName = "exec_command", arguments = emptyMap(), thinking = "")
        reporter.onToolDone(context, toolName = "exec_command", result = mapOf("stdout" to "ok"), thinking = "")

        val steps = updates.last().steps
        assertTrue("没有思考正文时不应留下空气泡", steps.none { it.type == "thinking" })
        assertEquals(2, steps.count { it.type == "tool" })
    }

    @Test
    fun attachGitDiffInsertsOrUpdatesADedicatedStep() {
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            cardId = "card-1"
        )
        val summary = GitDiffSummary(
            repoName = "repo",
            branch = "main",
            files = listOf(
                GitDiffFile(path = "a.kt", status = GitDiffFile.STATUS_MODIFIED, additions = 1, deletions = 1)
            )
        )

        reporter.attachGitDiff(summary)
        val step = updates.last().steps.single { it.type == "git_diff" }
        assertTrue(step.gitDiff is GitDiffSummary)
        assertEquals("a.kt", step.gitDiff!!.files.single().path)
        assertEquals("done", step.status)

        // 再次 attach 应覆盖既有步骤而非重复
        val summary2 = summary.copy(files = summary.files + GitDiffFile(path = "b.kt"))
        reporter.attachGitDiff(summary2)
        val gitSteps = updates.last().steps.filter { it.type == "git_diff" }
        assertEquals("重复 attach 应只保留一个 git_diff 步骤", 1, gitSteps.size)
        assertEquals(2, gitSteps.single().gitDiff!!.files.size)
    }

    @Test
    fun gitDiffSurvivesPersistedJsonRoundTrip() {
        // 模拟 ChatViewModel.loadMessages 从 Room 恢复卡片的完整链路：
        // attachGitDiff → toPersistedProgressCard → Gson JSON → decodeThinkingCardsForUi → UI 渲染。
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            onCheckpoint = { card ->
                // 与 LocalRepository.updateMessageThinkingCards 相同的序列化方式
                val json = com.google.gson.Gson().toJson(listOf(card.toPersistedProgressCard()))
                decoded = decodeThinkingCardsForUi("user-1", json)
            },
            cardId = "card-1"
        )
        val summary = GitDiffSummary(
            repoName = "repo",
            branch = "feature",
            files = listOf(
                GitDiffFile(
                    path = "src/a.kt",
                    status = GitDiffFile.STATUS_ADDED,
                    additions = 3,
                    deletions = 0,
                    hunks = listOf(
                        GitDiffHunk(
                            header = "@@ -0,0 +1,3 @@",
                            lines = listOf(GitDiffLine(GitDiffLine.KIND_ADD, "fun main() {}"))
                        )
                    )
                )
            )
        )
        reporter.attachGitDiff(summary)

        val restored = decoded ?: throw AssertionError("decodeThinkingCardsForUi 返回 null")
        val gitStep = restored.single().steps.single { it.type == "git_diff" }
        assertNotNull("gitDiff 应通过持久化往返保留", gitStep.gitDiff)
        assertEquals("repo", gitStep.gitDiff!!.repoName)
        assertEquals("feature", gitStep.gitDiff!!.branch)
        assertEquals("src/a.kt", gitStep.gitDiff!!.files.single().path)
        assertEquals(3, gitStep.gitDiff!!.totalAdditions)
        assertEquals("fun main() {}", gitStep.gitDiff!!.files[0].hunks[0].lines[0].text)
    }

    @Test
    fun toolDurationIsMeasuredBetweenStartAndDone() {
        val updates = mutableListOf<ThinkingCard>()
        var now = 0L
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            // 每次 nowNanos() 调用前进 500ms：start 取 0，done 取 500ms → 耗时 500ms
            nowNanos = { now.also { now += 500_000_000L } },
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "test")
        )

        reporter.onToolStart(
            context,
            toolName = "workspace_read_file",
            arguments = mapOf("path" to "a.txt"),
            thinking = ""
        )
        reporter.onToolDone(
            context,
            toolName = "workspace_read_file",
            result = mapOf("content" to "x"),
            thinking = ""
        )

        val toolStep = updates.last().steps.single { it.type == "tool" }
        assertEquals("done", toolStep.status)
        assertEquals(500L, toolStep.durationMs)
    }

    @Test
    fun sameToolCalledTwicePairsEachDurationWithItsOwnStep() {
        val updates = mutableListOf<ThinkingCard>()
        var now = 0L
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            // start1=0, done1=500ms; start2=1000ms, done2=1500ms → 各 500ms
            nowNanos = { now.also { now += 500_000_000L } },
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "test")
        )

        repeat(2) {
            reporter.onToolStart(context, toolName = "exec_command", arguments = emptyMap(), thinking = "")
            reporter.onToolDone(context, toolName = "exec_command", result = mapOf("stdout" to "ok"), thinking = "")
        }

        val toolSteps = updates.last().steps.filter { it.type == "tool" }
        assertEquals(2, toolSteps.size)
        assertEquals(listOf(500L, 500L), toolSteps.map { it.durationMs })
    }

    @Test
    fun interruptedToolWithoutDoneLeavesNoDurationOnFinalCard() {
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "test")
        )

        // onToolStart 后直接结束（模拟用户停止），无 onToolDone
        reporter.onToolStart(context, toolName = "exec_command", arguments = emptyMap(), thinking = "")
        reporter.onDone(context)

        val finalSteps = updates.last().steps
        // onDone 会把 running 步骤标记为 done，但无耗时
        val toolStep = finalSteps.single { it.type == "tool" }
        assertEquals("done", toolStep.status)
        assertEquals(null, toolStep.durationMs)
    }

    private var decoded: List<ThinkingCard>? = null
}
