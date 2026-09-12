package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressPersistenceTest {

    @Test
    fun persistedCardBoundsReasoningAndToolPayloads() {
        val card = ThinkingCard(
            id = "card",
            content = "done",
            steps = listOf(
                ThinkingStep(
                    type = "thinking",
                    thinkingContent = "r".repeat(80_000)
                ),
                ThinkingStep(
                    type = "tool",
                    arguments = mapOf("input" to "a".repeat(50_000)),
                    fullResult = mapOf("content" to "x".repeat(500_000))
                )
            ),
            isAgent = true
        )

        val persisted = card.toPersistedProgressCard()

        // 断言统一上限本身，而不是写死旧数字：改设置或调默认值时这里仍应成立。
        assertEquals(
            AgentToolLimits.PROGRESS_REASONING_CHARS,
            persisted.steps.first().thinkingContent?.length
        )
        assertTrue(
            persisted.steps[1].arguments?.get("preview").toString().length <=
                AgentToolLimits.progressPreviewChars()
        )
        assertTrue(
            persisted.steps[1].fullResult.toString().length <= AgentToolLimits.progressPreviewChars()
        )
    }

    @Test
    fun boundedPreviewStopsCyclesAndNeverExceedsBudget() {
        val cyclic = linkedMapOf<String, Any>()
        cyclic["self"] = cyclic

        val preview = boundedAgentValuePreview(cyclic, 128)

        assertTrue(preview.contains("<cycle>"))
        assertTrue(preview.length <= 128)
    }

    @Test
    fun toolOutputTruncationRecognizesCommonBooleanEncodings() {
        assertTrue(isAgentToolOutputTruncated(mapOf("truncated" to true)))
        assertTrue(isAgentToolOutputTruncated(mapOf("TRUNCATED" to "true")))
        assertTrue(isAgentToolOutputTruncated(mapOf("truncated" to 1)))
        assertFalse(isAgentToolOutputTruncated(mapOf("truncated" to false)))
        assertFalse(isAgentToolOutputTruncated(mapOf("content" to "complete")))
    }

    @Test
    fun oversizedLegacyCardSkipsJsonParsingAndReturnsSafePlaceholder() {
        // 故意使用无效 JSON：若先解析再检查大小，本测试会返回 null。
        val raw = "x".repeat(MAX_AGENT_PROGRESS_HISTORY_JSON_CHARS + 1)

        val cards = decodeThinkingCardsForUi("user-1", raw)

        assertEquals(1, cards?.size)
        assertTrue(cards?.single()?.isComplete == true)
        assertTrue(cards?.single()?.isAgent == true)
        assertEquals("user-1", cards?.single()?.parentMessageId)
    }
}
