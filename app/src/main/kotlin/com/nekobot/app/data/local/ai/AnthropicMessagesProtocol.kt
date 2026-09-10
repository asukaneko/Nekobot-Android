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

    override fun resolveUrl(
        baseUrl: String,
        model: String,
        appendBaseUrlPath: Boolean,
        stream: Boolean,
        apiKey: String
    ): String {
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
                        val thinkingBlock = thinkingBlockOf(msg)
                        if (toolCalls.isEmpty() && thinkingBlock == null) {
                            mapOf("role" to "assistant", "content" to (msg["content"] ?: ""))
                        } else {
                            val blocks = mutableListOf<Map<String, Any>>()
                            // 扩展思考要求 thinking 块位于 assistant 内容块的最前面
                            thinkingBlock?.let { blocks.add(it) }
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
                                "content" to toolResultContent(msg["content"])
                            )
                        )
                    )
                    else -> mapOf("role" to "user", "content" to userContent(msg["content"]))
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
                // 工具定义在整轮 Agent 循环里是稳定的（前缀不变），把最后一个工具标记为缓存断点，
                // 让后续每次工具调用都能命中 system + tools 前缀的缓存。
                payload["tools"] = converted.mapIndexed { index, tool ->
                    if (index == converted.lastIndex) {
                        tool + mapOf("cache_control" to EPHEMERAL_CACHE_CONTROL)
                    } else {
                        tool
                    }
                }
            }
        return payload
    }

    /**
     * system 提示词转为块数组并打上缓存断点。
     *
     * Anthropic 的 prompt caching 以"前缀命中"计费：Agent 模式下 system 提示词与工具定义
     * 每轮都完全相同，标记断点后重复调用的输入成本大幅下降。短提示词（低于最小缓存长度）
     * 不使用断点，避免无意义的分块。
     */
    private fun systemBlocksOf(systemMessage: String): List<Map<String, Any>> {
        val block = mapOf("type" to "text", "text" to systemMessage)
        return if (systemMessage.length >= MIN_CACHEABLE_SYSTEM_CHARS) {
            listOf(block + mapOf("cache_control" to EPHEMERAL_CACHE_CONTROL))
        } else {
            listOf(block)
        }
    }

    /**
     * 还原上一轮 assistant 的 thinking 块。
     *
     * Anthropic 的扩展思考 + 工具调用要求把 thinking 块（含 signature）原样带回，
     * 缺失会被服务端判为非法消息序列；没有签名时宁可不回传——带 thinking 无 signature
     * 同样非法，反而会把可用的请求变成失败请求。
     */
    private fun thinkingBlockOf(message: Map<String, Any>): Map<String, Any>? {
        val text = (message["reasoning_content"] as? String)
            ?: (message["thinking_content"] as? String)
        val signature = (message["reasoning_signature"] as? String)
            ?: (message["thinking_signature"] as? String)
        if (text.isNullOrBlank() || signature.isNullOrBlank()) return null
        return mapOf("type" to "thinking", "thinking" to text, "signature" to signature)
    }

    private fun userContent(content: Any?): Any {
        if (content !is List<*>) return content ?: ""
        val blocks = content.mapNotNull { raw ->
            val block = raw as? Map<*, *> ?: return@mapNotNull null
            when (block["type"] as? String) {
                "text" -> (block["text"] as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { mapOf("type" to "text", "text" to it) }
                "image_url" -> imageBlock(block)
                else -> null
            }
        }
        return blocks.takeIf { it.isNotEmpty() } ?: ""
    }

    /** 工具结果 content：多模态数组（text + image_url parts）转 Anthropic tool_result blocks。 */
    private fun toolResultContent(content: Any?): Any = when (content) {
        is List<*> -> {
            val blocks = content.mapNotNull { raw ->
                val block = raw as? Map<*, *> ?: return@mapNotNull null
                when (block["type"] as? String) {
                    "text" -> (block["text"] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { mapOf("type" to "text", "text" to it) }
                    "image_url" -> imageBlock(block)
                    else -> null
                }
            }
            blocks.takeIf { it.isNotEmpty() } ?: ""
        }
        else -> content ?: ""
    }

    private fun imageBlock(block: Map<*, *>): Map<String, Any>? {
        val imageUrl = when (val image = block["image_url"]) {
            is Map<*, *> -> image["url"] as? String
            is String -> image
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        val source = if (imageUrl.startsWith("data:")) {
            val mediaType = imageUrl.substringAfter("data:", "").substringBefore(';')
            val data = imageUrl.substringAfter("base64,", "")
            if (mediaType.isBlank() || data.isBlank()) return null
            mapOf("type" to "base64", "media_type" to mediaType, "data" to data)
        } else {
            mapOf("type" to "url", "url" to imageUrl)
        }
        return mapOf("type" to "image", "source" to source)
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

    /**
     * 思考块签名：流式响应里由 content_block_delta(signature_delta) 下发，
     * 少数网关会在 content_block_start 里直接给全量签名。
     *
     * 该签名必须与 thinking 文本一起在下一轮请求中回传，否则扩展思考 + 工具调用会被拒。
     */
    override fun parseStreamThinkingSignature(chunkJson: String): String? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            when (obj.get("type")?.asString) {
                "content_block_delta" -> {
                    val delta = obj.getAsJsonObject("delta") ?: return null
                    if (delta.get("type")?.asString != "signature_delta") return null
                    delta.get("signature")?.takeIf { !it.isJsonNull }?.asString?.ifEmpty { null }
                }
                "content_block_start" -> {
                    val block = obj.getAsJsonObject("content_block") ?: return null
                    if (block.get("type")?.asString != "thinking") return null
                    block.get("signature")?.takeIf { !it.isJsonNull }?.asString?.ifEmpty { null }
                }
                else -> null
            }
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

    override fun parseStreamToolCallDeltas(chunkJson: String): List<LocalToolCallDelta> {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            val index = obj.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            when (obj.get("type")?.asString) {
            "content_block_start" -> {
                val block = obj.getAsJsonObject("content_block") ?: return emptyList()
                if (block.get("type")?.asString != "tool_use") return emptyList()
                listOf(
                    LocalToolCallDelta(
                        index = index,
                        idChunk = block.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        nameChunk = block.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        initialArgumentsJson = block.get("input")
                            ?.takeIf { !it.isJsonNull }
                            ?.toString()
                            .orEmpty()
                    )
                )
            }
            "content_block_delta" -> {
                val delta = obj.getAsJsonObject("delta") ?: return emptyList()
                if (delta.get("type")?.asString != "input_json_delta") return emptyList()
                listOf(
                    LocalToolCallDelta(
                        index = index,
                        argumentsChunk = delta.get("partial_json")
                            ?.takeIf { !it.isJsonNull }
                            ?.asString
                            .orEmpty()
                    )
                )
            }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun parseStreamFinishReason(chunkJson: String): String? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            if (obj.get("type")?.asString != "message_delta") return null
            obj.getAsJsonObject("delta")
                ?.get("stop_reason")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.ifBlank { null }
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
        val thinkingSignature = contentBlocks?.mapNotNull { block ->
            val b = block as? Map<*, *> ?: return@mapNotNull null
            if (b["type"] != "thinking") return@mapNotNull null
            (b["signature"] as? String)?.takeIf { it.isNotBlank() }
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
            thinkingContent = thinking,
            thinkingSignature = thinkingSignature
        )
    }
}
