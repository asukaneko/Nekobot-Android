package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAnalysisModelsTest {

    @Test
    fun `regular session groups user assistant and tool content`() {
        val breakdown = buildContextBreakdown(
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

        assertEquals(1, breakdown.part(ContextPartType.SYSTEM_PROMPT)?.itemCount)
        assertEquals(1, breakdown.part(ContextPartType.USER_MESSAGES)?.itemCount)
        assertEquals(1, breakdown.part(ContextPartType.ASSISTANT_MESSAGES)?.itemCount)
        assertEquals(2, breakdown.part(ContextPartType.TOOL_CALLS)?.itemCount)
        assertTrue(breakdown.estimatedTokens > 0)
        assertFalse(breakdown.parts.any { it.type == ContextPartType.OTHER_MESSAGES })
    }

    @Test
    fun `agent session excludes messages before compressed summary boundary`() {
        val breakdown = buildContextBreakdown(
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

        assertEquals(1, breakdown.part(ContextPartType.COMPRESSED_SUMMARY)?.itemCount)
        assertEquals(1, breakdown.part(ContextPartType.ASSISTANT_MESSAGES)?.itemCount)
        assertEquals(null, breakdown.part(ContextPartType.USER_MESSAGES))
    }

    private fun ContextBreakdown.part(type: ContextPartType): ContextPart? =
        parts.firstOrNull { it.type == type }
}
