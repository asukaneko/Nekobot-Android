package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser

/**
 * Anthropic Messages 协议 (/v1/messages)。
 *
 * 对应后端 `nbot/core/protocols/anthropic_messages.py:AnthropicMessagesProtocol`。
 * 关键差异：system 字段独立、消息 content 是字符串而非数组（本地模式不涉及工具调用）、
 * 流式 chunk 通过 type 区分（content_block_delta / message_stop）。
 */
object AnthropicMessagesProtocol : LocalProtocol {

    override val name: String = "anthropic_messages"

    override fun resolveUrl(baseUrl: String, model: String, appendBaseUrlPath: Boolean): String {
        val base = baseUrl.trimEnd('/')
        if (base.contains("/v1/messages")) return base
        return "$base/v1/messages"
    }

    override fun buildHeaders(apiKey: String, stream: Boolean): Map<String, String> {
        val headers = linkedMapOf(
            "x-api-key" to apiKey,
            "Content-Type" to "application/json",
            "anthropic-version" to "2023-06-01"
        )
        if (stream) {
            headers["Accept"] = "text/event-stream"
        }
        return headers
    }

    override fun buildPayload(
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?>
    ): Map<String, Any> {
        // 分离 system 消息
        val systemMessage = messages.firstOrNull { it["role"] == "system" }?.get("content") as? String
        val mappedMessages = messages
            .filter { it["role"] != "system" }
            .map { msg ->
                when (msg["role"]) {
                    "assistant" -> {
                        @Suppress("UNCHECKED_CAST")
                        val toolCalls = msg["tool_calls"] as? List<Map<String, Any>> ?: emptyList()
                        if (toolCalls.isEmpty()) {
                            mapOf("role" to "assistant", "content" to (msg["content"] ?: ""))
                        } else {
                            val blocks = mutableListOf<Map<String, Any>>()
                            (msg["content"] as? String)?.takeIf { it.isNotBlank() }?.let {
                                blocks.add(mapOf("type" to "text", "text" to it))
                            }
                            toolCalls.forEach { call ->
                                @Suppress("UNCHECKED_CAST")
                                val function = call["function"] as? Map<String, Any> ?: emptyMap()
                                val rawArguments = function["arguments"]
                                val input = when (rawArguments) {
                                    is String -> runCatching {
                                        @Suppress("UNCHECKED_CAST")
                                        JsonParser.parseString(rawArguments).asJsonObject.entrySet()
                                            .associate { it.key to it.value.asString }
                                    }.getOrDefault(emptyMap())
                                    is Map<*, *> -> rawArguments.entries.associate {
                                        it.key.toString() to (it.value ?: "")
                                    }
                                    else -> emptyMap<String, Any>()
                                }
                                blocks.add(
                                    mapOf(
                                        "type" to "tool_use",
                                        "id" to (call["id"] ?: ""),
                                        "name" to (function["name"] ?: ""),
                                        "input" to input
                                    )
                                )
                            }
                            mapOf("role" to "assistant", "content" to blocks)
                        }
                    }
                    "tool" -> mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "tool_result",
                                "tool_use_id" to (msg["tool_call_id"] ?: ""),
                                "content" to (msg["content"] ?: "")
                            )
                        )
                    )
                    else -> mapOf("role" to "user", "content" to (msg["content"] ?: ""))
                }
            }
        // Anthropic 要求 user/assistant 角色交替；一次返回多个工具调用时，
        // ToolLoop 会产生连续 tool 消息，需要合并为同一个 user 的 tool_result blocks。
        val anthropicMessages = mutableListOf<Map<String, Any>>()
        mappedMessages.forEach { message ->
            val previous = anthropicMessages.lastOrNull()
            if (previous?.get("role") != message["role"]) {
                anthropicMessages.add(message)
                return@forEach
            }
            val previousMessage = previous ?: return@forEach

            val previousContent = previousMessage["content"]
            val currentContent = message["content"]
            val mergedContent: Any = if (previousContent is String && currentContent is String) {
                listOf(previousContent, currentContent)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            } else {
                fun blocks(value: Any?): List<Any> = when (value) {
                    is List<*> -> value.filterNotNull()
                    is String -> value.takeIf { it.isNotBlank() }
                        ?.let { listOf(mapOf("type" to "text", "text" to it)) }
                        .orEmpty()
                    else -> emptyList()
                }
                blocks(previousContent) + blocks(currentContent)
            }
            anthropicMessages[anthropicMessages.lastIndex] =
                previousMessage.toMutableMap().apply { put("content", mergedContent) }
        }

        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to anthropicMessages,
            "max_tokens" to ((extra["max_tokens"] as? Number)?.toInt() ?: 4096)
        )
        systemMessage?.let { payload["system"] = it }
        if (stream) payload["stream"] = true
        val reasoningEffort = extra["reasoning_effort"] as? String
        val thinkingEnabled = reasoningEffort != null && reasoningEffort != "none"
        if (reasoningEffort == "none") {
            payload["thinking"] = mapOf("type" to "disabled")
        } else if (thinkingEnabled) {
            payload["thinking"] = mapOf("type" to "adaptive")
            payload["output_config"] = mapOf("effort" to reasoningEffort)
        }
        // Anthropic 思考模式不接受 temperature/top_p 调优参数。
        if (!thinkingEnabled) {
            (extra["temperature"] as? Number)?.let { payload["temperature"] = it.toDouble() }
            (extra["top_p"] as? Number)?.let { payload["top_p"] = it.toDouble() }
        }
        @Suppress("UNCHECKED_CAST")
        (extra["tools"] as? List<Map<String, Any>>)
            ?.takeIf { it.isNotEmpty() }
            ?.let { tools ->
                payload["tools"] = tools.mapNotNull { tool ->
                    @Suppress("UNCHECKED_CAST")
                    val function = tool["function"] as? Map<String, Any> ?: return@mapNotNull null
                    buildMap<String, Any> {
                        put("name", function["name"] ?: return@mapNotNull null)
                        function["description"]?.let { put("description", it) }
                        put("input_schema", function["parameters"] ?: emptyMap<String, Any>())
                    }
                }
            }
        return payload
    }

    override fun parseStreamThinkingChunk(chunkJson: String): String? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            if (obj.get("type")?.asString != "content_block_delta") return null
            val delta = obj.getAsJsonObject("delta") ?: return null
            if (delta.get("type")?.asString != "thinking_delta") return null
            delta.get("thinking")?.takeIf { !it.isJsonNull }?.asString?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    override fun parseStreamChunk(chunkJson: String): String? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            val type = obj.get("type")?.asString ?: return null
            if (type != "content_block_delta") return null
            val delta = obj.getAsJsonObject("delta") ?: return null
            val deltaType = delta.get("type")?.asString ?: return null
            if (deltaType != "text_delta") return null
            delta.get("text")?.takeIf { !it.isJsonNull }?.asString?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    override fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            // Anthropic 在 message_delta / message_start 事件里携带 usage
            val type = obj.get("type")?.asString ?: return null
            if (type != "message_delta" && type != "message_start") return null
            val usage = obj.getAsJsonObject("usage") ?: return null
            val input = usage.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val output = usage.get("output_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            Triple(input, output, input + output)
        } catch (_: Exception) {
            null
        }
    }

    override fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse {
        val contentBlocks = data["content"] as? List<*>
        val content = contentBlocks?.mapNotNull { block ->
            val b = block as? Map<*, *> ?: return@mapNotNull null
            if (b["type"] == "text") b["text"] as? String else null
        }?.joinToString("") ?: ""
        val thinking = contentBlocks?.mapNotNull { block ->
            val b = block as? Map<*, *> ?: return@mapNotNull null
            if (b["type"] == "thinking") b["thinking"] as? String else null
        }?.joinToString("") ?: ""

        val usage = (data["usage"] as? Map<*, *>)?.let { u ->
            val input = (u["input_tokens"] as? Number)?.toInt() ?: 0
            val output = (u["output_tokens"] as? Number)?.toInt() ?: 0
            mapOf(
                "prompt" to input,
                "completion" to output,
                "total" to (input + output)
            )
        } ?: emptyMap()
        val toolCalls = contentBlocks?.mapNotNull { block ->
            val item = block as? Map<*, *> ?: return@mapNotNull null
            if (item["type"] != "tool_use") return@mapNotNull null
            val name = item["name"] as? String ?: return@mapNotNull null
            val input = (item["input"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to (it.value ?: "") }
                .orEmpty()
            mapOf(
                "id" to (item["id"] as? String ?: ""),
                "name" to name,
                "arguments" to input
            )
        }.orEmpty()

        return LocalModelResponse(
            content = content,
            usage = usage,
            toolCalls = toolCalls,
            finishReason = (data["stop_reason"] as? String).orEmpty(),
            thinkingContent = thinking
        )
    }
}
