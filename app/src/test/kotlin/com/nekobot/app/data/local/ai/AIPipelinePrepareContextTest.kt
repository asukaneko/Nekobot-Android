package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_PREFIX
import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_SOURCE
import com.nekobot.app.data.local.agentContextWindow
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIPipelinePrepareContextTest {

    @Test
    fun prepareContextComposesPromptStackWithoutGeneratingResponse() = runBlocking {
        val ctx = PipelineContext(
            ChatRequest.forLocal(
                sessionId = "live-session",
                content = ""
            )
        )
        ctx.metadata["custom_prompts"] = listOf(
            mapOf("order" to 1, "title" to "Live", "content" to "Use a calm tone.")
        )
        val callbacks = object : PipelineCallbacks() {
            override fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> = listOf(
                mapOf("role" to "system", "content" to "Base character prompt.")
            )
        }

        AIPipeline().prepareContext(ctx, callbacks)

        val prompt = ctx.metadata["composed_system_prompt"] as String
        assertTrue(prompt.contains("Base character prompt."))
        assertTrue(prompt.contains("Use a calm tone."))
        assertEquals("", ctx.finalContent)
        assertTrue(ctx.messages.isNotEmpty())
    }

    @Test
    fun inheritedAgentKeepsSummaryRoleAndRecentOriginalsTogether() = runBlocking {
        val ctx = context()
        val callbacks = SummaryCallbacks(inherit = true)

        AIPipeline().prepareContext(ctx, callbacks)

        assertContainsOnce(ctx, "EARLY_HISTORY_FACT")
        assertContainsOnce(ctx, "ROLE_IDENTITY")
        assertContainsOnce(ctx, "RECENT_USER_ONE")
        assertContainsOnce(ctx, "RECENT_REPLY_ONE")
        assertFalse(ctx.messages.any { it["content"] == "OLD_USER_RAW" })
        assertTrue(ctx.characterTurn?.promptText?.contains("ROLE_IDENTITY") == true)
    }

    @Test
    fun unboundAgentKeepsSummaryThroughLocalPromptBuilder() = runBlocking {
        val ctx = context()
        AIPipeline().prepareContext(ctx, SummaryCallbacks(inherit = false))

        assertContainsOnce(ctx, "EARLY_HISTORY_FACT")
        assertContainsOnce(ctx, "RECENT_USER_ONE")
        assertContainsOnce(ctx, "SESSION_RULE")
    }

    @Test
    fun migratedCustomPromptDoesNotReplaceNewRollingSummary() = runBlocking {
        val ctx = context()
        ctx.metadata["custom_prompts"] = listOf(
            mapOf("order" to 1, "title" to "Migration", "content" to "MIGRATION_SNAPSHOT")
        )
        ctx.promptStack.add("agent.rules", "AGENT_CAPABILITIES", priority = 10)

        AIPipeline().prepareContext(ctx, SummaryCallbacks(inherit = true))

        for (marker in listOf("EARLY_HISTORY_FACT", "MIGRATION_SNAPSHOT", "ROLE_IDENTITY", "AGENT_CAPABILITIES")) {
            assertContainsOnce(ctx, marker)
        }
    }

    @Test
    fun nextCompressionUsesUpdatedSummaryAndMovesOriginalBoundary() = runBlocking {
        val ctx = context()
        val callbacks = SummaryCallbacks(inherit = true)
        val pipeline = AIPipeline()
        pipeline.prepareContext(ctx, callbacks)
        assertContainsOnce(ctx, "EARLY_HISTORY_FACT")
        assertContainsOnce(ctx, "RECENT_USER_ONE")

        callbacks.summary = summary("UPDATED_HISTORY_FACT", boundary = "a1")
        pipeline.prepareContext(ctx, callbacks)

        assertContainsOnce(ctx, "UPDATED_HISTORY_FACT")
        assertContainsOnce(ctx, "RECENT_USER_TWO")
        assertContainsOnce(ctx, "ROLE_IDENTITY")
        assertFalse(ctx.messages.any { (it["content"] as? String)?.contains("EARLY_HISTORY_FACT") == true })
        assertFalse(ctx.messages.any { it["content"] == "RECENT_USER_ONE" })
    }

    private fun context() = PipelineContext(ChatRequest.forLocal(
        sessionId = "summary-session", content = "Current question.",
        metadata = mapOf("session_mode" to "agent", "inherit_character" to true)
    )).apply { metadata.putAll(chatRequest.metadata) }

    private fun assertContainsOnce(ctx: PipelineContext, marker: String) {
        val content = ctx.messages.joinToString("\n") { it["content"] as? String ?: "" }
        assertEquals(marker, 1, Regex(Regex.escape(marker)).findAll(content).count())
    }

    private fun row(id: String, role: String, content: String) = LocalMessageEntity(
        id = id, sessionId = "summary-session", role = role, content = content,
        timestamp = id, createdAt = id
    )

    private fun summary(content: String, boundary: String = "a0") = row(
        "summary", "system", "$AGENT_CONTEXT_SUMMARY_PREFIX\n$content"
    ).copy(source = "$AGENT_CONTEXT_SUMMARY_SOURCE:$boundary")

    private inner class SummaryCallbacks(private val inherit: Boolean) : PipelineCallbacks() {
        var summary = summary("EARLY_HISTORY_FACT")
        private val rows = listOf(
            row("u0", "user", "OLD_USER_RAW"), row("a0", "assistant", "OLD_REPLY_RAW"),
            row("u1", "user", "RECENT_USER_ONE"), row("a1", "assistant", "RECENT_REPLY_ONE"),
            row("u2", "user", "RECENT_USER_TWO"), row("a2", "assistant", "RECENT_REPLY_TWO")
        )
        private val runtime = CharacterRuntime(profileRepo = object : ProfileRepository {
            override suspend fun getById(id: String) = CharacterProfile(
                id = id, name = "Test character", systemPrompt = "ROLE_IDENTITY"
            )
        })

        override fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> {
            val history = (rows + summary).agentContextWindow()
            return if (inherit) {
                history.map { mapOf("role" to it.role, "content" to it.content) } +
                    mapOf("role" to "user", "content" to ctx.chatRequest.content)
            } else {
                LocalPromptBuilder.build(
                    LocalSessionEntity(id = "summary-session", name = "Test", sessionMode = "agent",
                        systemPrompt = "SESSION_RULE", createdAt = "now", updatedAt = "now"),
                    character = null, history = history, userInput = ctx.chatRequest.content
                )
            }
        }

        override fun getCharacterRuntime(ctx: PipelineContext) = runtime.takeIf { inherit }
        override fun getCharacterContext(ctx: PipelineContext) = if (inherit) {
            CharacterIdentity(characterId = "character", targetId = "user", scopeId = "summary-session")
        } else null
    }
}
