package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProtocolToolCallingTest {

    private val toolDefinition = mapOf<String, Any>(
        "type" to "function",
        "function" to mapOf(
            "name" to "get_date_time",
            "description" to "获取时间",
            "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        )
    )

    @Test
    fun openAiPayloadAndResponsePreserveToolCalls() {
        val payload = OpenAIChatProtocol.buildPayload(
            model = "test-model",
            messages = listOf(mapOf("role" to "user", "content" to "现在几点")),
            stream = false,
            extra = mapOf("tools" to listOf(toolDefinition))
        )

        assertEquals(listOf(toolDefinition), payload["tools"])
        assertEquals("auto", payload["tool_choice"])

        val parsed = OpenAIChatProtocol.parseNonStreamResponse(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "finish_reason" to "tool_calls",
                        "message" to mapOf(
                            "content" to "",
                            "tool_calls" to listOf(
                                mapOf(
                                    "id" to "call-1",
                                    "function" to mapOf(
                                        "name" to "get_date_time",
                                        "arguments" to """{"timezone":"Asia/Shanghai"}"""
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        assertEquals("tool_calls", parsed.finishReason)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("get_date_time", parsed.toolCalls.single()["name"])
        @Suppress("UNCHECKED_CAST")
        val arguments = parsed.toolCalls.single()["arguments"] as Map<String, Any>
        assertEquals("Asia/Shanghai", arguments["timezone"])
    }

    @Test
    fun openAiResponseJoinsStructuredTextBlocks() {
        val parsed = OpenAIChatProtocol.parseNonStreamResponse(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf(
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "图片中有"),
                                mapOf("type" to "output_text", "text" to "一只猫")
                            )
                        )
                    )
                )
            )
        )

        assertEquals("图片中有一只猫", parsed.content)
    }

    @Test
    fun openAiReasoningPayloadAndStreamAreSeparatedFromAnswer() {
        val payload = OpenAIChatProtocol.buildPayload(
            model = "gpt-5",
            messages = listOf(mapOf("role" to "user", "content" to "think")),
            stream = true,
            extra = mapOf("reasoning_effort" to "high")
        )
        assertEquals("high", payload["reasoning_effort"])
        assertEquals(
            "reasoning",
            OpenAIChatProtocol.parseStreamThinkingChunk(
                """{"choices":[{"delta":{"reasoning_content":"reasoning","content":null}}]}"""
            )
        )
        assertEquals(
            null,
            OpenAIChatProtocol.parseStreamChunk(
                """{"choices":[{"delta":{"reasoning_content":"reasoning","content":null}}]}"""
            )
        )
    }

    @Test
    fun openAiStreamParsesToolCallDeltasAndFinishReason() {
        val first = OpenAIChatProtocol.parseStreamToolCallDeltas(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"get_date_time","arguments":"{\"time"}}]}}]}"""
        ).single()
        val second = OpenAIChatProtocol.parseStreamToolCallDeltas(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"zone\":\"Asia/Shanghai\"}"}}]},"finish_reason":"tool_calls"}]}"""
        ).single()

        assertEquals("call-1", first.idChunk)
        assertEquals("get_date_time", first.nameChunk)
        assertEquals(
            """{"timezone":"Asia/Shanghai"}""",
            first.argumentsChunk + second.argumentsChunk
        )
        assertEquals(
            "tool_calls",
            OpenAIChatProtocol.parseStreamFinishReason(
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
            )
        )
    }

    @Test
    fun streamedToolCallDeltasAreAggregatedIntoExecutableCall() {
        val accumulator = LocalToolCallStreamAccumulator()
        accumulator.add(
            LocalToolCallDelta(
                index = 0,
                idChunk = "call-1",
                nameChunk = "get_date_time",
                argumentsChunk = "{\"time"
            )
        )
        accumulator.add(
            LocalToolCallDelta(
                index = 0,
                argumentsChunk = "zone\":\"Asia/Shanghai\"}"
            )
        )

        val call = accumulator.build().single()
        @Suppress("UNCHECKED_CAST")
        val arguments = call["arguments"] as Map<String, Any>

        assertEquals("call-1", call["id"])
        assertEquals("get_date_time", call["name"])
        assertEquals("Asia/Shanghai", arguments["timezone"])
    }

    @Test
    fun deepSeekThinkingUsesToggleAndOmitsToolChoice() {
        val payload = OpenAIChatProtocol.buildPayload(
            model = "deepseek-chat",
            messages = listOf(mapOf("role" to "user", "content" to "think")),
            stream = true,
            extra = mapOf(
                "reasoning_effort" to "max",
                "deepseek_thinking" to true,
                "tools" to listOf(toolDefinition)
            )
        )
        assertEquals(mapOf("type" to "enabled"), payload["thinking"])
        assertTrue("tool_choice" !in payload)
    }

    @Test
    fun anthropicPayloadAndResponsePreserveToolCalls() {
        val payload = AnthropicMessagesProtocol.buildPayload(
            model = "claude-test",
            messages = listOf(mapOf("role" to "user", "content" to "现在几点")),
            stream = false,
            extra = mapOf("tools" to listOf(toolDefinition))
        )

        @Suppress("UNCHECKED_CAST")
        val tools = payload["tools"] as List<Map<String, Any>>
        assertEquals("get_date_time", tools.single()["name"])
        assertTrue(tools.single().containsKey("input_schema"))

        val parsed = AnthropicMessagesProtocol.parseNonStreamResponse(
            mapOf(
                "stop_reason" to "tool_use",
                "content" to listOf(
                    mapOf(
                        "type" to "tool_use",
                        "id" to "tool-1",
                        "name" to "get_date_time",
                        "input" to mapOf("timezone" to "Asia/Shanghai")
                    )
                )
            )
        )

        assertEquals("tool_use", parsed.finishReason)
        assertEquals("get_date_time", parsed.toolCalls.single()["name"])
    }

    @Test
    fun anthropicAdaptiveThinkingParsesDeltasAndResponse() {
        val payload = AnthropicMessagesProtocol.buildPayload(
            model = "claude-test",
            messages = listOf(mapOf("role" to "user", "content" to "think")),
            stream = true,
            extra = mapOf("reasoning_effort" to "high", "temperature" to 0.8)
        )
        assertEquals(mapOf("type" to "adaptive"), payload["thinking"])
        assertEquals(mapOf("effort" to "high"), payload["output_config"])
        assertTrue("temperature" !in payload)
        assertEquals(
            "analysis",
            AnthropicMessagesProtocol.parseStreamThinkingChunk(
                """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"analysis"}}"""
            )
        )
        val parsed = AnthropicMessagesProtocol.parseNonStreamResponse(
            mapOf(
                "content" to listOf(
                    mapOf("type" to "thinking", "thinking" to "analysis"),
                    mapOf("type" to "text", "text" to "answer")
                )
            )
        )
        assertEquals("analysis", parsed.thinkingContent)
        assertEquals("answer", parsed.content)
    }

    @Test
    fun anthropicStreamParsesToolUseAndInputJsonDeltas() {
        val start = AnthropicMessagesProtocol.parseStreamToolCallDeltas(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"get_date_time","input":{}}}"""
        ).single()
        val arguments = AnthropicMessagesProtocol.parseStreamToolCallDeltas(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"timezone\":\"Asia/Shanghai\"}"}}"""
        ).single()

        assertEquals(1, start.index)
        assertEquals("tool-1", start.idChunk)
        assertEquals("get_date_time", start.nameChunk)
        assertEquals("{\"timezone\":\"Asia/Shanghai\"}", arguments.argumentsChunk)
        assertEquals(
            "tool_use",
            AnthropicMessagesProtocol.parseStreamFinishReason(
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
            )
        )
    }

    @Test
    fun toolLoopExecutesCallAndReturnsFinalModelContent() {
        var modelCalls = 0
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "现在几点")),
            modelCall = { messages, _ ->
                modelCalls += 1
                if (modelCalls == 1) {
                    mapOf(
                        "content" to "",
                        "finish_reason" to "tool_calls",
                        "tool_calls" to listOf(
                            mapOf(
                                "id" to "call-1",
                                "name" to "get_date_time",
                                "arguments" to emptyMap<String, Any>()
                            )
                        )
                    )
                } else {
                    assertTrue(messages.any { it["role"] == "tool" })
                    mapOf("content" to "现在是 14:30", "finish_reason" to "stop")
                }
            },
            toolExecutor = { call, _, _, _ ->
                assertEquals("get_date_time", call["name"])
                mapOf("success" to true, "time" to "14:30")
            }
        )

        assertEquals(2, modelCalls)
        assertEquals("现在是 14:30", result.finalContent)
    }

    @Test
    fun toolLoopStopsBeforeRunningAnotherTool() {
        var modelCalls = 0
        var executedTools = 0
        var stopRequested = false

        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "执行工具")),
            modelCall = { _, _ ->
                modelCalls += 1
                mapOf(
                    "content" to "",
                    "finish_reason" to "tool_calls",
                    "tool_calls" to listOf(
                        mapOf("id" to "call-1", "name" to "first", "arguments" to emptyMap<String, Any>()),
                        mapOf("id" to "call-2", "name" to "second", "arguments" to emptyMap<String, Any>())
                    )
                )
            },
            toolExecutor = { _, _, _, _ ->
                executedTools += 1
                stopRequested = true
                mapOf("success" to true)
            },
            shouldStop = { stopRequested }
        )

        assertTrue(result.stopped)
        assertEquals(1, modelCalls)
        assertEquals(1, executedTools)
    }
}
