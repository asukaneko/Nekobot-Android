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
        val anthropicMessages = messages
            .filter { it["role"] != "system" }
            .map { msg ->
                mapOf(
                    "role" to (msg["role"] ?: "user"),
                    "content" to (msg["content"] ?: "")
                )
            }

        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to anthropicMessages,
            "max_tokens" to ((extra["max_tokens"] as? Number)?.toInt() ?: 4096)
        )
        systemMessage?.let { payload["system"] = it }
        if (stream) payload["stream"] = true
        (extra["temperature"] as? Number)?.let { payload["temperature"] = it.toDouble() }
        (extra["top_p"] as? Number)?.let { payload["top_p"] = it.toDouble() }
        return payload
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

    override fun parseNonStreamResponse(data: Map<String, Any>): Pair<String, Map<String, Int>> {
        val contentBlocks = data["content"] as? List<*>
        val content = contentBlocks?.mapNotNull { block ->
            val b = block as? Map<*, *> ?: return@mapNotNull null
            if (b["type"] == "text") b["text"] as? String else null
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
        return content to usage
    }
}
