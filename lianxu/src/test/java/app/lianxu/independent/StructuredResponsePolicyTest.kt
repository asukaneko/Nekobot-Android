package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class StructuredResponsePolicyTest {
    @Test fun tokenLimitFieldUsesTheUpstreamModelOnCustomRelays() {
        val custom = ModelConfig("id", "relay", "custom", "https://relay.test/v1", "ordinary")
        for (model in listOf("o1", "o3-mini", "O4-mini", "gpt-5-mini", "openai/gpt-5", "gpt-5.1")) {
            assertEquals(model, "max_completion_tokens", ImageWire.tokenLimitField(custom.copy(modelId = model)))
        }
        assertEquals("max_tokens", ImageWire.tokenLimitField(custom))
        for (provider in listOf("openai", "mimo")) {
            assertEquals("max_completion_tokens", ImageWire.tokenLimitField(custom.copy(providerId = provider)))
        }
    }

    @Test fun reasoningContinuationIsEndpointScopedAndIndependentOfJsonMode() {
        val request = TextChatRequest(listOf(WireMessage("user", "hello")), jsonObject = true)
        val base = ModelConfig("id", "model", "custom", "https://api.deepseek.com/v1", "deepseek-flash")
        for (endpoint in listOf("https://api.deepseek.com", "https://api.deepseek.com/v1/")) {
            for (model in listOf("deepseek-reasoner", "deepseek-chat", "deepseek-flash", "deepseek-v4-pro")) {
                val config = base.copy(baseUrl = endpoint, modelId = model)
                assertTrue(StructuredResponsePolicy.supportsReasoningContinuation(config))
                assertFalse(StructuredResponsePolicy.jsonMode(config, request))
            }
        }
        assertFalse(StructuredResponsePolicy.supportsReasoningContinuation(base.copy(modelId = "unknown")))
        assertFalse(StructuredResponsePolicy.supportsReasoningContinuation(base.copy(baseUrl = "https://example.test/v1")))
        val mimo = base.copy(baseUrl = "https://api.xiaomimimo.com/v1", modelId = "mimo-v2.6-pro")
        assertTrue(StructuredResponsePolicy.supportsReasoningContinuation(mimo))
        assertTrue(StructuredResponsePolicy.jsonMode(mimo, request))
    }
}
