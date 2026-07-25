package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIResponsesProtocolTest {
    @Test
    fun `build payload separates instructions and flattens tools`() {
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-test",
            messages = listOf(
                mapOf("role" to "system", "content" to "Follow the rules"),
                mapOf("role" to "user", "content" to "Hello")
            ),
            stream = false,
            extra = mapOf(
                "max_tokens" to 2048,
                "tools" to listOf(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "lookup",
                            "description" to "Look up a value",
                            "parameters" to mapOf("type" to "object")
                        )
                    )
                )
            )
        )

        assertEquals("Follow the rules", payload["instructions"])
        assertEquals(true, payload["stream"])
        assertEquals(false, payload["store"])
        assertEquals(2048, payload["max_output_tokens"])
        val tools = payload["tools"] as List<*>
        val tool = tools.first() as Map<*, *>
        assertEquals("function", tool["type"])
        assertEquals("lookup", tool["name"])
        assertTrue("function" !in tool)
    }

    @Test
    fun `parse response completed returns text tools and usage`() {
        val event = """
            {
              "type": "response.completed",
              "response": {
                "status": "completed",
                "output": [
                  {
                    "type": "message",
                    "content": [{"type": "output_text", "text": "hello"}]
                  },
                  {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "lookup",
                    "arguments": "{\"key\":\"value\"}"
                  }
                ],
                "usage": {
                  "input_tokens": 10,
                  "output_tokens": 4,
                  "total_tokens": 14
                }
              }
            }
        """.trimIndent()

        val parsed = requireNotNull(OpenAIResponsesProtocol.parseStreamFinalResponse(event))

        assertEquals("hello", parsed.content)
        assertEquals(14, parsed.usage["total"])
        assertEquals("lookup", parsed.toolCalls.single()["name"])
        assertEquals("tool_calls", parsed.finishReason)
    }

    @Test
    fun `completed response maps to stop so tool loop does not repeat`() {
        var modelCalls = 0
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "look it up")),
            modelCall = { messages, _ ->
                modelCalls += 1
                val parsed = if (modelCalls == 1) {
                    OpenAIResponsesProtocol.parseNonStreamResponse(
                        mapOf(
                            "status" to "completed",
                            "output" to listOf(
                                mapOf(
                                    "type" to "function_call",
                                    "call_id" to "call_1",
                                    "name" to "lookup",
                                    "arguments" to """{"key":"value"}"""
                                )
                            )
                        )
                    )
                } else {
                    assertTrue(messages.any { it["role"] == "tool" })
                    OpenAIResponsesProtocol.parseNonStreamResponse(
                        mapOf(
                            "status" to "completed",
                            "output" to listOf(
                                mapOf(
                                    "type" to "message",
                                    "content" to listOf(
                                        mapOf("type" to "output_text", "text" to "done")
                                    )
                                )
                            )
                        )
                    )
                }
                buildMap {
                    put("content", parsed.content)
                    put("finish_reason", parsed.finishReason)
                    if (parsed.toolCalls.isNotEmpty()) put("tool_calls", parsed.toolCalls)
                }
            },
            toolExecutor = { _, _, _, _ -> mapOf("result" to "value") }
        )

        assertEquals(2, modelCalls)
        assertEquals("done", result.finalContent)
    }

    @Test
    fun `parse stream delta returns only output text`() {
        assertEquals(
            "hi",
            OpenAIResponsesProtocol.parseStreamChunk(
                """{"type":"response.output_text.delta","delta":"hi"}"""
            )
        )
        assertEquals(
            null,
            OpenAIResponsesProtocol.parseStreamChunk(
                """{"type":"response.created","response":{}}"""
            )
        )
        assertEquals(
            "subscription required",
            OpenAIResponsesProtocol.parseStreamError(
                """{"type":"response.failed","response":{"error":{"message":"subscription required"}}}"""
            )
        )
    }
}
