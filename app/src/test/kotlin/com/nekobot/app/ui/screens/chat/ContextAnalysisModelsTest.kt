package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.local.ai.ContextUsagePartTokens
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务端模式回退口径：按消息与提示词估算（本地模式走 agentLiveContextUsage）。 */
class ContextAnalysisModelsTest {

    @Test
    fun `regular session groups user assistant and tool content`() {
        val breakdown = fallbackContextUsageBreakdown(
            session = Session(sessionMode = "character", systemPrompt = "system instructions"),
            messages = listOf(
                Message(role = "system", content = "not part of message context"),
                Message(role = "user", content = "hello"),
                Message(
                    role = "assistant",
                    content = "hi",
                    toolCallHistory = listOf(mapOf("name" to "search", "content" to "result"))
                ),
                Message(role = "tool", content = "tool result")
            )
        )

        assertEquals(1, breakdown.part(ContextUsagePart.SYSTEM_PROMPT)?.itemCount)
        assertEquals(1, breakdown.part(ContextUsagePart.USER_MESSAGES)?.itemCount)
        assertEquals(1, breakdown.part(ContextUsagePart.ASSISTANT_MESSAGES)?.itemCount)
        assertEquals(2, breakdown.part(ContextUsagePart.TOOL_TRAJECTORY)?.itemCount)
        assertTrue(breakdown.totalTokens > 0)
        assertFalse(breakdown.parts.any { it.part == ContextUsagePart.OTHER_MESSAGES })
        // 回退口径没有本地工具定义与落库轨迹
        assertFalse(breakdown.parts.any { it.part == ContextUsagePart.TOOL_DEFINITIONS })
    }

    @Test
    fun `agent session excludes messages before compressed summary boundary`() {
        val breakdown = fallbackContextUsageBreakdown(
            session = Session(sessionMode = "agent"),
            messages = listOf(
                Message(id = "old-user", role = "user", content = "old user message"),
                Message(id = "old-assistant", role = "assistant", content = "old assistant message"),
                Message(id = "boundary", role = "user", content = "boundary message"),
                Message(
                    id = "summary",
                    role = "system",
                    content = "summary of old conversation",
                    source = "agent_context_summary:boundary"
                ),
                Message(id = "recent", role = "assistant", content = "recent answer")
            )
        )

        assertEquals(1, breakdown.part(ContextUsagePart.SUMMARY)?.itemCount)
        assertEquals(1, breakdown.part(ContextUsagePart.ASSISTANT_MESSAGES)?.itemCount)
        assertEquals(null, breakdown.part(ContextUsagePart.USER_MESSAGES))
    }

    private fun ContextUsageBreakdown.part(part: ContextUsagePart): ContextUsagePartTokens? =
        parts.firstOrNull { it.part == part }
}
