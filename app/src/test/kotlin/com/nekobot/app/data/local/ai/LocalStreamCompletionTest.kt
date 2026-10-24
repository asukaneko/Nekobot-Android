package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class LocalStreamCompletionTest {
    @Test fun responsePartDoneIsNotWholeResponseDone() {
        val tracker = LocalStreamCompletion(OpenAIResponsesProtocol)
        tracker.observe("""{"type":"response.output_text.done","text":"partial"}""")
        try { tracker.requireComplete(); fail("Part end is not response end") } catch (_: IncompleteChatStream) { }
        tracker.observe("""{"type":"response.completed","response":{"status":"completed"}}""")
        tracker.requireComplete()
    }
    @Test fun anthropicStopAndOpenAiSentinelAreExplicitCompletion() {
        val anthropic = LocalStreamCompletion(AnthropicMessagesProtocol)
        anthropic.observe("""{"type":"message_stop"}""")
        anthropic.requireComplete()
        val openai = LocalStreamCompletion(OpenAIChatProtocol)
        openai.observe("[DONE]")
        openai.requireComplete()
    }
}
