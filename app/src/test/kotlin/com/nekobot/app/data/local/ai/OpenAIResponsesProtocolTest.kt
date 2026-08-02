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

    @Test
    fun `done-only stream events recover final text`() {
        val textDone = requireNotNull(
            OpenAIResponsesProtocol.parseStreamFinalResponse(
                """{"type":"response.output_text.done","text":"final text"}"""
            )
        )
        val itemDone = requireNotNull(
            OpenAIResponsesProtocol.parseStreamFinalResponse(
                """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"text","text":"item text"}]}}"""
            )
        )

        assertEquals("final text", textDone.content)
        assertEquals("item text", itemDone.content)
    }

    @Test
    fun `compatible response shapes recover top-level text and chat choices`() {
        val topLevel = OpenAIResponsesProtocol.parseNonStreamResponse(
            mapOf("output_text" to "top-level text", "status" to "completed")
        )
        val chatCompatible = OpenAIResponsesProtocol.parseNonStreamResponse(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf("content" to "chat-compatible text"),
                        "finish_reason" to "stop"
                    )
                )
            )
        )

        assertEquals("top-level text", topLevel.content)
        assertEquals("chat-compatible text", chatCompatible.content)
    }

    @Test
    fun `function call semantic events are parsed without completed event`() {
        val added = OpenAIResponsesProtocol.parseStreamToolCallDeltas(
            """{"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"call_2","name":"lookup","arguments":""}}"""
        ).single()
        val arguments = OpenAIResponsesProtocol.parseStreamToolCallDeltas(
            """{"type":"response.function_call_arguments.done","output_index":2,"arguments":"{\"key\":\"value\"}"}"""
        ).single()
        val done = requireNotNull(
            OpenAIResponsesProtocol.parseStreamFinalResponse(
                """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_2","name":"lookup","arguments":"{\"key\":\"value\"}"}}"""
            )
        )

        assertEquals(2, added.index)
        assertEquals("lookup", added.nameChunk)
        assertEquals("{\"key\":\"value\"}", arguments.initialArgumentsJson)
        assertEquals("lookup", done.toolCalls.single()["name"])
    }

    @Test
    fun `reasoning effort and summary delta are supported`() {
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-5",
            messages = listOf(mapOf("role" to "user", "content" to "think")),
            stream = true,
            extra = mapOf("reasoning_effort" to "high")
        )

        val reasoning = payload["reasoning"] as Map<*, *>
        assertEquals("high", reasoning["effort"])
        assertEquals("auto", reasoning["summary"])
        assertEquals(
            "step one",
            OpenAIResponsesProtocol.parseStreamThinkingChunk(
                """{"type":"response.reasoning_summary_text.delta","delta":"step one"}"""
            )
        )
    }
}
