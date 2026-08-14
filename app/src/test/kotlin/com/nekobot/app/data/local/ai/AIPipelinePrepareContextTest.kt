package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
