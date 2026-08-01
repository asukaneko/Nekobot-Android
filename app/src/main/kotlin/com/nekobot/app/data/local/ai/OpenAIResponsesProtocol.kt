package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * OpenAI Responses API 协议。
 *
 * Codex 订阅端点只接受流式请求，因此非流式调用也会由 LocalAiClient
 * 以 SSE 方式执行并聚合成一个 LocalModelResponse。
 */
object OpenAIResponsesProtocol : LocalProtocol {
    override val name: String = "openai_responses"
    override val requiresStreaming: Boolean = true

    private val gson = Gson()

    override fun resolveUrl(baseUrl: String, model: String, appendBaseUrlPath: Boolean): String {
        val base = baseUrl.trimEnd('/')
        if (base.endsWith("/responses")) return base
        return if (appendBaseUrlPath) "$base/responses" else base
    }

    override fun buildHeaders(apiKey: String, stream: Boolean): Map<String, String> = linkedMapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "Accept" to "text/event-stream",
        "Cache-Control" to "no-cache"
    )

    override fun buildPayload(
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?>
    ): Map<String, Any> {
        val instructions = messages
            .filter { it["role"] == "system" || it["role"] == "developer" }
            .mapNotNull { extractText(it["content"]) }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .ifBlank { "You are a helpful assistant." }

        val input = messages
            .filterNot { it["role"] == "system" || it["role"] == "developer" }
            .flatMap(::toResponseInput)

        return buildMap {
            put("model", model)
            put("input", input)
            put("stream", true)
            put("store", false)
            if (instructions.isNotBlank()) put("instructions", instructions)
            extra["temperature"]?.let { put("temperature", it) }
            extra["max_tokens"]?.let { put("max_output_tokens", it) }
            extra["top_p"]?.let { put("top_p", it) }
            extra["reasoning_effort"]?.let { effort ->
                put(
                    "reasoning",
                    buildMap<String, Any> {
                        put("effort", effort)
                        if (effort != "none") put("summary", "auto")
                    }
                )
            }
            @Suppress("UNCHECKED_CAST")
            (extra["tools"] as? List<Map<String, Any>>)
                ?.mapNotNull(::flattenTool)
                ?.takeIf(List<*>::isNotEmpty)
                ?.let {
                    put("tools", it)
                    put("tool_choice", "auto")
                }
        }
    }

    override fun parseStreamChunk(chunkJson: String): String? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        if (event.string("type") != "response.output_text.delta") return null
        event.string("delta").ifBlank { null }
    }.getOrNull()

    override fun parseStreamThinkingChunk(chunkJson: String): String? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        when (event.string("type")) {
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta" -> event.string("delta").ifBlank { null }
            else -> null
        }
    }.getOrNull()

    override fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        val response = event.getAsJsonObject("response") ?: return null
        val usage = response.getAsJsonObject("usage") ?: return null
        val input = usage.int("input_tokens")
        val output = usage.int("output_tokens")
        Triple(input, output, usage.intOrNull("total_tokens") ?: input + output)
    }.getOrNull()

    override fun parseStreamFinalResponse(chunkJson: String): LocalModelResponse? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        if (event.string("type") != "response.completed") return null
        val response = event.getAsJsonObject("response") ?: return null
        @Suppress("UNCHECKED_CAST")
        val data = gson.fromJson(response, Map::class.java) as Map<String, Any>
        parseNonStreamResponse(data)
    }.getOrNull()

    override fun parseStreamError(chunkJson: String): String? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        val type = event.string("type")
        if (type != "error" && type != "response.failed") return null
        val error = event.getAsJsonObject("error")
            ?: event.getAsJsonObject("response")?.getAsJsonObject("error")
        error?.string("message")
            ?.ifBlank { error.string("code") }
            ?.ifBlank { "Responses API 请求失败" }
            ?: "Responses API 请求失败"
    }.getOrNull()

    override fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse {
        val output = data["output"] as? List<*> ?: emptyList<Any>()
        val content = buildString {
            output.forEach { item ->
                val objectItem = item as? Map<*, *> ?: return@forEach
                if (objectItem["type"] == "message") {
                    val blocks = objectItem["content"] as? List<*> ?: emptyList<Any>()
                    blocks.forEach { block ->
                        val value = block as? Map<*, *> ?: return@forEach
                        if (value["type"] == "output_text") {
                            append(value["text"] as? String ?: "")
                        }
                    }
                }
            }
        }
        val thinking = buildString {
            output.forEach { item ->
                val objectItem = item as? Map<*, *> ?: return@forEach
                if (objectItem["type"] == "reasoning") {
                    val summary = objectItem["summary"] as? List<*> ?: emptyList<Any>()
                    summary.forEach { block ->
                        val value = block as? Map<*, *> ?: return@forEach
                        append(value["text"] as? String ?: "")
                    }
                }
            }
        }
        val toolCalls = output.mapNotNull { item ->
            val value = item as? Map<*, *> ?: return@mapNotNull null
            if (value["type"] != "function_call") return@mapNotNull null
            val name = value["name"] as? String ?: return@mapNotNull null
            val rawArguments = value["arguments"] as? String ?: "{}"
            val arguments: Any = runCatching {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(rawArguments, Map::class.java) as Map<String, Any>
            }.getOrDefault(emptyMap())
            mapOf(
                "id" to ((value["call_id"] ?: value["id"]) as? String).orEmpty(),
                "name" to name,
                "arguments" to arguments
            )
        }
        val usageMap = data["usage"] as? Map<*, *>
        val inputTokens = (usageMap?.get("input_tokens") as? Number)?.toInt() ?: 0
        val outputTokens = (usageMap?.get("output_tokens") as? Number)?.toInt() ?: 0
        val responseStatus = (data["status"] as? String).orEmpty()
        val finishReason = when {
            toolCalls.isNotEmpty() -> "tool_calls"
            responseStatus == "completed" -> "stop"
            responseStatus == "incomplete" -> "length"
            else -> responseStatus
        }
        return LocalModelResponse(
            content = content,
            usage = if (usageMap == null) emptyMap() else mapOf(
                "prompt" to inputTokens,
                "completion" to outputTokens,
                "total" to ((usageMap["total_tokens"] as? Number)?.toInt()
                    ?: inputTokens + outputTokens)
            ),
            toolCalls = toolCalls,
            finishReason = finishReason,
            thinkingContent = thinking
        )
    }

    private fun toResponseInput(message: Map<String, Any>): List<Map<String, Any>> {
        val role = message["role"] as? String ?: "user"
        if (role == "tool") {
            val callId = (message["tool_call_id"] as? String).orEmpty()
            return listOf(
                mapOf(
                    "type" to "function_call_output",
                    "call_id" to callId,
                    "output" to (extractText(message["content"]) ?: "")
                )
            )
        }
        val result = mutableListOf<Map<String, Any>>()
        @Suppress("UNCHECKED_CAST")
        val toolCalls = message["tool_calls"] as? List<Map<String, Any>>
        toolCalls.orEmpty().forEach { call ->
            @Suppress("UNCHECKED_CAST")
            val function = call["function"] as? Map<String, Any> ?: return@forEach
            result += mapOf(
                "type" to "function_call",
                "call_id" to ((call["id"] as? String).orEmpty()),
                "name" to ((function["name"] as? String).orEmpty()),
                "arguments" to when (val args = function["arguments"]) {
                    is String -> args
                    else -> gson.toJson(args ?: emptyMap<String, Any>())
                }
            )
        }
        val text = extractText(message["content"])
        if (!text.isNullOrBlank()) {
            result += mapOf(
                "role" to if (role == "assistant") "assistant" else "user",
                "content" to text
            )
        }
        return result
    }

    private fun flattenTool(tool: Map<String, Any>): Map<String, Any>? {
        @Suppress("UNCHECKED_CAST")
        val function = tool["function"] as? Map<String, Any>
        if (function != null) {
            val name = function["name"] as? String ?: return null
            return buildMap {
                put("type", "function")
                put("name", name)
                function["description"]?.let { put("description", it) }
                put(
                    "parameters",
                    sanitizeSchema(function["parameters"] ?: emptyMap<String, Any>())
                )
            }
        }
        return tool.takeIf { it["type"] == "function" && it["name"] is String }
    }

    private fun sanitizeSchema(value: Any?): Any = when (value) {
        is Map<*, *> -> value.entries
            .filterNot { (key, _) -> key == "pattern" || key == "format" }
            .associate { (key, child) -> key.toString() to sanitizeSchema(child) }
        is List<*> -> value.map(::sanitizeSchema)
        else -> value ?: ""
    }

    private fun extractText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is List<*> -> value.mapNotNull { block ->
            when (block) {
                is String -> block
                is Map<*, *> -> extractText(block["text"] ?: block["content"])
                else -> null
            }
        }.joinToString("")
        is Map<*, *> -> extractText(value["text"] ?: value["content"] ?: value["value"])
        else -> value.toString()
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.int(key: String): Int = intOrNull(key) ?: 0

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.let {
            runCatching { it.asInt }.getOrNull()
        }
}
