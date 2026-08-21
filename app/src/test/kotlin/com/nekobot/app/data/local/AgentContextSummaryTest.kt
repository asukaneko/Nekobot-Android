package com.nekobot.app.data.local

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextSummaryTest {

    @Test
    fun identifiesOnlyMarkedAgentSummarySystemMessages() {
        assertTrue(
            Message(
                role = "system",
                content = "$AGENT_CONTEXT_SUMMARY_PREFIX\n已完成文件检查"
            ).isAgentContextSummary()
        )
        assertFalse(Message(role = "system", content = "普通会话提示词").isAgentContextSummary())
        assertFalse(Message(role = "assistant", content = AGENT_CONTEXT_SUMMARY_PREFIX).isAgentContextSummary())
    }

    @Test
    fun exposesSummaryBoundaryForChatTimelineDivider() {
        val summary = Message(
            id = "summary",
            role = "system",
            content = "$AGENT_CONTEXT_SUMMARY_PREFIX\n早期历史摘要",
            source = "$AGENT_CONTEXT_SUMMARY_SOURCE:boundary"
        )

        assertTrue(summary.isAgentContextSummary())
        assertEquals("boundary", summary.agentContextSummaryBoundaryId())
    }

    @Test
    fun contextWindowKeepsSummaryAndMessagesAfterItsBoundary() {
        fun message(id: String, role: String, content: String, source: String? = null) =
            LocalMessageEntity(
                id = id,
                sessionId = "session",
                role = role,
                content = content,
                timestamp = id,
                createdAt = id,
                source = source
            )

        val first = message("first", "user", "早期请求")
        val boundary = message("boundary", "assistant", "早期结果")
        val summary = message(
            "summary",
            "system",
            "$AGENT_CONTEXT_SUMMARY_PREFIX\n早期历史摘要",
            "$AGENT_CONTEXT_SUMMARY_SOURCE:boundary"
        )
        val recent = message("recent", "user", "继续处理")

        val context = listOf(first, boundary, summary, recent).agentContextWindow()

        assertEquals(listOf("summary", "recent"), context.map(LocalMessageEntity::id))
    }
}
