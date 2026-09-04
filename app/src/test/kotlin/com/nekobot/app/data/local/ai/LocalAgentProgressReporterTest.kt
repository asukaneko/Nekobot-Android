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
        assertTrue(toolStep.arguments?.get("preview").toString().length <= 1_500)
        assertTrue(toolStep.fullResult.toString().length <= 3_000)
        assertTrue(toolStep.resultTruncated == true)
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

    private var decoded: List<ThinkingCard>? = null
}
