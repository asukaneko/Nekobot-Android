package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ThinkingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentProgressReporterTest {

    @Test
    fun streamedReasoningIsAccumulatedInThinkingStep() {
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-1",
            onUpdate = updates::add,
            cardId = "card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "测试")
        )

        reporter.onThinkingStart(context)
        reporter.onThinkingContent(context, "先分析问题。")
        reporter.onThinkingContent(context, "再核对答案。")
        reporter.onThinkingContent(context, "再核对答案。")

        val card = updates.last()
        val thinkingStep = card.steps.single { it.type == "thinking" }
        assertEquals("先分析问题。再核对答案。", thinkingStep.thinkingContent)
        assertTrue(thinkingStep.detail.orEmpty().contains("再核对答案"))
        assertEquals("AI 正在思考...", card.content)
    }
}
