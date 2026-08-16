package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ThinkingCard
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
}
