package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser

/**
 * OpenAI Chat Completions 协议 (v1/chat/completions)。
 *
 * 兼容 OpenAI / DeepSeek / 硅基流动 / 通义 / 智谱 / Gemini OpenAI 兼容端点等。
 * 对应后端 `nbot/core/protocols/openai_chat.py:OpenAIChatProtocol`。
 */
object OpenAIChatProtocol : LocalProtocol {

    override val name: String = "openai_chat"

    override fun resolveUrl(baseUrl: String, model: String, appendBaseUrlPath: Boolean): String {
        val base = baseUrl.trimEnd('/')
        if (!appendBaseUrlPath) return base
        // 已经含完整路径则直接返回
        if (base.contains("/chat/completions") || base.contains("/chatcompletion")) return base
        return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/chat/completions"
    }

    override fun buildHeaders(apiKey: String, stream: Boolean): Map<String, String> {
        val headers = linkedMapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        if (stream) {
            headers["Accept"] = "text/event-stream"
            headers["Cache-Control"] = "no-cache"
        }
        return headers
    }

    override fun buildPayload(
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?>
    ): Map<String, Any> {
        val payload = linkedMapOf<String, Any>(
            "model" to model,
            "messages" to messages,
            "stream" to stream
        )
        // 流式请求要求在最后一个 chunk 返回 usage
        if (stream) payload["stream_options"] = mapOf("include_usage" to true)
        extra["temperature"]?.let { payload["temperature"] = it }
        extra["max_tokens"]?.let { payload["max_tokens"] = it }
        extra["top_p"]?.let { payload["top_p"] = it }
        if (extra["deepseek_thinking"] == true) {
            val effort = extra["reasoning_effort"]
            payload["thinking"] = mapOf(
                "type" to if (effort == "none") "disabled" else "enabled"
            )
            if (effort != null && effort != "none") payload["reasoning_effort"] = effort
        } else {
            extra["reasoning_effort"]?.let { payload["reasoning_effort"] = it }
        }
        @Suppress("UNCHECKED_CAST")
        (extra["tools"] as? List<Map<String, Any>>)
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                payload["tools"] = it
                if (extra["deepseek_thinking"] != true) payload["tool_choice"] = "auto"
            }
        return payload
    }

    override fun parseStreamThinkingChunk(chunkJson: String): String? = try {
        val obj = JsonParser.parseString(chunkJson).asJsonObject
        val delta = obj.getAsJsonArray("choices")
            ?.takeIf { it.size() > 0 }
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("delta")
            ?: return null
        val value = delta.get("reasoning_content") ?: delta.get("thinking_content") ?: return null
        value.takeIf { !it.isJsonNull }?.asString?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    override fun parseStreamChunk(chunkJson: String): String? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            val choices = obj.getAsJsonArray("choices") ?: return null
            if (choices.size() == 0) return null
            val choice = choices[0].asJsonObject
            val delta = choice.getAsJsonObject("delta") ?: return null
            // content 可能是字符串或 null
            val contentElem = delta.get("content") ?: return null
            if (contentElem.isJsonNull) return null
            val text = contentElem.asString
            text.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    override fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>? {
        return try {
            val obj = JsonParser.parseString(chunkJson).asJsonObject
            val usage = obj.getAsJsonObject("usage") ?: return null
            val prompt = usage.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val completion = usage.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val total = usage.get("total_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: (prompt + completion)
            Triple(prompt, completion, total)
        } catch (_: Exception) {
            null
        }
    }

    override fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse {
        val choices = data["choices"] as? List<*>
        val choice = choices?.firstOrNull() as? Map<*, *>
        val message = choice?.get("message") as? Map<*, *>
        val content = extractResponseText(message?.get("content"))
        val finishReason = (choice?.get("finish_reason") as? String).orEmpty()
        val thinkingContent = (message?.get("reasoning_content") as? String)
            ?: (message?.get("thinking_content") as? String)
            ?: ""
        val toolCalls = (message?.get("tool_calls") as? List<*>)
            ?.mapNotNull { raw ->
                val call = raw as? Map<*, *> ?: return@mapNotNull null
                val function = call["function"] as? Map<*, *> ?: return@mapNotNull null
                val name = function["name"] as? String ?: return@mapNotNull null
                val rawArguments = function["arguments"]
                val arguments: Any = when (rawArguments) {
                    is String -> runCatching {
                        @Suppress("UNCHECKED_CAST")
                        JsonParser.parseString(rawArguments)
                            .takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.entrySet()
                            ?.associate { (key, value) ->
                                key to when {
                                    value.isJsonNull -> ""
                                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber
                                    value.isJsonPrimitive -> value.asString
                                    else -> value.toString()
                                }
                            }
                            .orEmpty()
                    }.getOrDefault(emptyMap<String, Any>())
                    is Map<*, *> -> rawArguments.entries.associate { it.key.toString() to (it.value ?: "") }
                    else -> emptyMap<String, Any>()
                }
                buildMap<String, Any> {
                    put("id", call["id"] as? String ?: "")
                    put("name", name)
                    put("arguments", arguments)
                }
            }
            .orEmpty()
        val usage = (data["usage"] as? Map<*, *>)?.let { u ->
            val prompt = (u["prompt_tokens"] as? Number)?.toInt() ?: 0
            val completion = (u["completion_tokens"] as? Number)?.toInt() ?: 0
            val total = (u["total_tokens"] as? Number)?.toInt() ?: (prompt + completion)
            mapOf(
                "prompt" to prompt,
                "completion" to completion,
                "total" to total
            )
        } ?: emptyMap()
        return LocalModelResponse(
            content = content,
            usage = usage,
            toolCalls = toolCalls,
            finishReason = finishReason,
            thinkingContent = thinkingContent
        )
    }

    private fun extractResponseText(value: Any?): String = when (value) {
        is String -> value
        is List<*> -> value.mapNotNull { block ->
            when (block) {
                is String -> block
                is Map<*, *> -> {
                    val type = block["type"] as? String
                    when {
                        type == null || type == "text" || type == "output_text" ->
                            extractResponseText(block["text"] ?: block["content"])
                        else -> null
                    }
                }
                else -> null
            }
        }.joinToString("")
        is Map<*, *> -> extractResponseText(value["value"] ?: value["text"] ?: value["content"])
        else -> ""
    }
}
