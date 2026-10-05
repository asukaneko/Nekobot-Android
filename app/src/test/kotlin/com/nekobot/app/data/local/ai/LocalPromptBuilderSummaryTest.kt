package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_PREFIX
import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_SOURCE
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptBuilderSummaryTest {
    private fun session(mode: String = "agent") = LocalSessionEntity(
        id = "s", name = "Test", sessionMode = mode, createdAt = "now", updatedAt = "now"
    )
    private fun row(content: String, source: String? = null) = LocalMessageEntity(
        id = "summary", sessionId = "s", role = "system", content = content,
        source = source, timestamp = "now", createdAt = "now"
    )

    @Test
    fun preservesMarkedAgentSummaryAlongsideSessionRules() {
        val summary = row("SUMMARY_FACT", "$AGENT_CONTEXT_SUMMARY_SOURCE:boundary")
        val messages = LocalPromptBuilder.build(session().copy(systemPrompt = "SESSION_RULE"), null,
            listOf(summary), "Current question")
        assertEquals(listOf("system", "system", "user"), messages.map { it["role"] })
        assertEquals("SUMMARY_FACT", messages[1]["content"])
        assertTrue(messages.first()["content"] == "SESSION_RULE")
    }

    @Test
    fun preservesBothLegacyAndCurrentSummaryMarkersWithoutSource() {
        for (prefix in listOf(AGENT_CONTEXT_SUMMARY_PREFIX, "【历史对话摘要】")) {
            val content = "$prefix\nSUMMARY_FACT"
            val messages = LocalPromptBuilder.build(session(), null, listOf(row(content)), "Current question")
            assertEquals(content, messages.first()["content"])
            assertEquals("system", messages.first()["role"])
        }
    }

    @Test
    fun doesNotPromoteOrdinaryStoredSystemMessagesToAgentInstructions() {
        val messages = LocalPromptBuilder.build(session(), null,
            listOf(row("UNRELATED_SYSTEM_MESSAGE")), "Current question")
        assertEquals(listOf("user"), messages.map { it["role"] })
    }

    @Test
    fun characterChatStillIgnoresStoredSystemMessages() {
        val messages = LocalPromptBuilder.build(session("character"), null,
            listOf(row("$AGENT_CONTEXT_SUMMARY_PREFIX\nAGENT_ONLY_FACT")), "Current question")
        assertFalse(messages.any { (it["content"] as? String)?.contains("AGENT_ONLY_FACT") == true })
    }
}
