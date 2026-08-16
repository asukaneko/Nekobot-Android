package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNativeProtocolTest {

    @Test
    fun `native protocol is registered and resolves official endpoints`() {
        assertSame(GeminiNativeProtocol, LocalProtocols.get("gemini_native"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=test-key",
            GeminiNativeProtocol.resolveUrl(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                model = "models/gemini-2.5-flash",
                appendBaseUrlPath = true,
                stream = true,
                apiKey = "test-key"
            )
        )
        assertEquals(
            "https://proxy.example/v1beta/models/gemini-2.5-flash:generateContent",
            GeminiNativeProtocol.resolveUrl(
                baseUrl = "https://proxy.example/v1beta/models/gemini-2.5-flash:streamGenerateContent",
                model = "gemini-2.5-flash",
                appendBaseUrlPath = false,
                stream = false,
                apiKey = "test-key"
            )
        )
    }

    @Test
    fun `payload maps system messages tools and generation settings`() {
        val payload = GeminiNativeProtocol.buildPayload(
            model = "gemini-2.5-flash",
            messages = listOf(
                mapOf("role" to "system", "content" to "Follow the rules"),
                mapOf("role" to "user", "content" to "Hello")
            ),
            stream = false,
            extra = mapOf(
                "temperature" to 0.7,
                "max_tokens" to 2048,
                "top_p" to 0.9,
                "tools" to listOf(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "lookup",
                            "description" to "Look up a value",
                            "parameters" to mapOf(
                                "type" to "object",
                                "additionalProperties" to false,
                                "properties" to mapOf("key" to mapOf("type" to "string"))
                            )
                        )
                    )
                )
            )
        )

        val instruction = payload["systemInstruction"] as Map<*, *>
        val instructionParts = instruction["parts"] as List<*>
        assertEquals("Follow the rules", (instructionParts.single() as Map<*, *>)["text"])
        val contents = payload["contents"] as List<*>
        assertEquals("user", (contents.single() as Map<*, *>)["role"])
        val generation = payload["generationConfig"] as Map<*, *>
        assertEquals(2048, generation["maxOutputTokens"])
        assertEquals(0.7, generation["temperature"])
        assertEquals(0.9, generation["topP"])
        val declaration = (((payload["tools"] as List<*>).single() as Map<*, *>)["functionDeclarations"] as List<*>)
            .single() as Map<*, *>
        val schema = declaration["parameters"] as Map<*, *>
        assertFalse(schema.containsKey("additionalProperties"))
    }

    @Test
    fun `response and stream chunks expose content tools thinking and usage`() {
        val response = GeminiNativeProtocol.parseNonStreamResponse(
            mapOf(
                "candidates" to listOf(
                    mapOf(
                        "finishReason" to "STOP",
                        "content" to mapOf(
                            "parts" to listOf(
                                mapOf("text" to "reasoning", "thought" to true),
                                mapOf("text" to "answer"),
                                mapOf(
                                    "functionCall" to mapOf(
                                        "id" to "call_1",
                                        "name" to "lookup",
                                        "args" to mapOf("key" to "value")
                                    )
                                )
                            )
                        )
                    )
                ),
                "usageMetadata" to mapOf(
                    "promptTokenCount" to 8,
                    "candidatesTokenCount" to 3,
                    "totalTokenCount" to 11
                )
            )
        )

        assertEquals("answer", response.content)
        assertEquals("reasoning", response.thinkingContent)
        assertEquals("tool_calls", response.finishReason)
        assertEquals("lookup", response.toolCalls.single()["name"])
        assertEquals(11, response.usage["total"])

        val streamChunk = """
            {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"hello"}]}}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1,"totalTokenCount":3}}
        """.trimIndent()
        assertEquals("hello", GeminiNativeProtocol.parseStreamChunk(streamChunk))
        assertEquals(Triple(2, 1, 3), GeminiNativeProtocol.parseStreamUsage(streamChunk))
        assertEquals("stop", GeminiNativeProtocol.parseStreamFinishReason(streamChunk))
        assertTrue(GeminiNativeProtocol.parseStreamToolCallDeltas(streamChunk).isEmpty())
    }
}
