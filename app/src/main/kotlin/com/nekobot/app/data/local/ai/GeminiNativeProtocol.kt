package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.util.UUID

object GeminiNativeProtocol : LocalProtocol {

    override val name: String = "gemini_native"

    override fun resolveUrl(
        baseUrl: String,
        model: String,
        appendBaseUrlPath: Boolean,
        stream: Boolean,
        apiKey: String
    ): String {
        val supplied = baseUrl.trim().trimEnd('/')
        require(supplied.isNotBlank()) { "Gemini base URL is required" }

        val path = supplied.substringBefore('?')
        val originalQuery = supplied.substringAfter('?', "")
        val action = if (stream) "streamGenerateContent" else "generateContent"
        val endpoint = when {
            path.contains(":streamGenerateContent") ->
                path.replace(":streamGenerateContent", ":$action")
            path.contains(":generateContent") ->
                path.replace(":generateContent", ":$action")
            path.contains("/models/") -> "$path:$action"
            appendBaseUrlPath -> "$path/models/${model.removePrefix("models/")}:$action"
            else -> path
        }
        val query = originalQuery.split('&').filter(String::isNotBlank).toMutableList()
        if (stream && endpoint.contains("streamGenerateContent") && query.none { it.startsWith("alt=") }) {
            query += "alt=sse"
        }
        if (apiKey.isNotBlank() && isGoogleEndpoint(endpoint) && query.none { it.startsWith("key=") }) {
            query += "key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"
        }
        return if (query.isEmpty()) endpoint else "$endpoint?${query.joinToString("&")}"
    }

    override fun buildHeaders(apiKey: String, stream: Boolean): Map<String, String> = linkedMapOf<String, String>().apply {
        put("Content-Type", "application/json")
        if (stream) put("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            put("x-goog-api-key", apiKey)
            put("Authorization", "Bearer $apiKey")
        }
    }

    override fun buildPayload(
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?>
    ): Map<String, Any> {
        val systemParts = mutableListOf<Map<String, Any>>()
        val contents = mutableListOf<Map<String, Any>>()
        messages.forEach { message ->
            when (message["role"] as? String) {
                "system" -> systemParts += textParts(message["content"])
                "user" -> textParts(message["content"]).takeIf { it.isNotEmpty() }?.let { parts ->
                    contents += mapOf("role" to "user", "parts" to parts)
                }
                "assistant" -> assistantParts(message).takeIf { it.isNotEmpty() }?.let { parts ->
                    contents += mapOf("role" to "model", "parts" to parts)
                }
                "tool" -> toolResponsePart(message)?.let { part ->
                    contents += mapOf("role" to "user", "parts" to listOf(part))
                }
            }
        }

        val generationConfig = linkedMapOf<String, Any>()
        (extra["max_tokens"] as? Number)?.toInt()?.let { generationConfig["maxOutputTokens"] = it }
        (extra["temperature"] as? Number)?.toDouble()?.let { generationConfig["temperature"] = it }
        (extra["top_p"] as? Number)?.toDouble()?.let { generationConfig["topP"] = it }

        return linkedMapOf<String, Any>().apply {
            put("contents", contents)
            if (systemParts.isNotEmpty()) put("systemInstruction", mapOf("parts" to systemParts))
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
            tools(extra["tools"])?.let { put("tools", it) }
        }
    }

    override fun parseStreamChunk(chunkJson: String): String? = parseChunk(chunkJson).content.ifBlank { null }

    override fun parseStreamThinkingChunk(chunkJson: String): String? = parseChunk(chunkJson).thinking.ifBlank { null }

    override fun parseStreamToolCallDeltas(chunkJson: String): List<LocalToolCallDelta> = parseChunk(chunkJson).toolCalls
        .mapIndexed { index, call ->
            LocalToolCallDelta(
                index = index,
                idChunk = call["id"] as? String ?: "",
                nameChunk = call["name"] as? String ?: "",
                initialArgumentsJson = Gson().toJson(call["arguments"] ?: emptyMap<String, Any>())
            )
        }

    override fun parseStreamFinishReason(chunkJson: String): String? = parseChunk(chunkJson).finishReason

    override fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>? {
        val usage = parseChunk(chunkJson).usage
        return usage?.let { Triple(it["prompt"] ?: 0, it["completion"] ?: 0, it["total"] ?: 0) }
    }

    override fun parseStreamError(chunkJson: String): String? = runCatching {
        val error = JsonParser.parseString(chunkJson).asJsonObject.getAsJsonObject("error") ?: return@runCatching null
        error.get("message")?.takeIf { !it.isJsonNull }?.asString
    }.getOrNull()

    override fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse {
        val parsed = parseResponse(data)
        return LocalModelResponse(
            content = parsed.content,
            usage = parsed.usage ?: emptyMap(),
            toolCalls = parsed.toolCalls,
            finishReason = parsed.finishReason.orEmpty(),
            thinkingContent = parsed.thinking
        )
    }

    private fun textParts(content: Any?): List<Map<String, Any>> = when (content) {
        is String -> content.takeIf { it.isNotBlank() }?.let { listOf(mapOf("text" to it)) }.orEmpty()
        is List<*> -> content.flatMap { item ->
            when (item) {
                is String -> textParts(item)
                is Map<*, *> -> when (item["type"] as? String) {
                    "text" -> textParts(item["text"] ?: item["content"])
                    "image_url" -> imagePart(item)?.let(::listOf).orEmpty()
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }
        else -> emptyList()
    }

    private fun imagePart(item: Map<*, *>): Map<String, Any>? {
        val image = item["image_url"]
        val url = when (image) {
            is Map<*, *> -> image["url"] as? String
            is String -> image
            else -> null
        } ?: return null
        if (!url.startsWith("data:")) return mapOf("text" to "Image URL: $url")
        val mimeType = url.substringAfter("data:").substringBefore(';')
        val data = url.substringAfter("base64,", "")
        return if (mimeType.isNotBlank() && data.isNotBlank()) {
            mapOf("inlineData" to mapOf("mimeType" to mimeType, "data" to data))
        } else {
            null
        }
    }

    private fun assistantParts(message: Map<String, Any>): List<Map<String, Any>> = buildList {
        val thinking = (message["_thinking_content"] ?: message["reasoning_content"]) as? String
        thinking?.takeIf { it.isNotBlank() }?.let { add(mapOf("text" to it, "thought" to true)) }
        addAll(textParts(message["content"]))
        @Suppress("UNCHECKED_CAST")
        val calls = message["tool_calls"] as? List<Map<String, Any>> ?: emptyList()
        calls.forEachIndexed { index, call ->
            val function = call["function"] as? Map<*, *> ?: call
            val name = function["name"] as? String ?: return@forEachIndexed
            val arguments = parseArguments(function["arguments"] ?: call["arguments"])
            val functionCall = linkedMapOf<String, Any>(
                "id" to (call["id"] as? String ?: "call_${index}_${UUID.randomUUID().toString().take(8)}"),
                "name" to name,
                "args" to arguments
            )
            val signature = (call["_thought_signature"] ?: function["_thought_signature"]) as? String
            if (!signature.isNullOrBlank()) functionCall["thoughtSignature"] = signature
            add(mapOf("functionCall" to functionCall))
        }
    }

    private fun toolResponsePart(message: Map<String, Any>): Map<String, Any>? {
        val name = message["name"] as? String ?: return null
        val response = when (val content = message["content"]) {
            // 多模态 content 数组（buildToolMessageContent 生成）：
            // 文本部分作为 response JSON，image_url 部分转为 Gemini inlineData 图片
            // （functionResponse 的 "image" 字段，Gemini 支持函数返回图片给模型直接观察）。
            is List<*> -> {
                val text = content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
                    .joinToString("")
                val images = content.mapNotNull { block ->
                    val map = block as? Map<*, *> ?: return@mapNotNull null
                    imagePart(map)
                }
                buildMap<String, Any> {
                    val parsed = runCatching { parseJson(text) as? Map<String, Any> }.getOrNull()
                    if (parsed != null && parsed.isNotEmpty()) putAll(parsed) else put("result", text)
                    images.forEachIndexed { index, inlineData ->
                        put(if (index == 0) "image" else "image_$index", inlineData)
                    }
                }
            }
            is String -> parseJson(content) as? Map<String, Any>
                ?: mapOf("result" to content)
            else -> mapOf("result" to (message["content"]?.toString() ?: ""))
        }
        return mapOf(
            "functionResponse" to mapOf(
                "id" to (message["tool_call_id"] as? String ?: ""),
                "name" to name,
                "response" to response
            )
        )
    }

    private fun tools(value: Any?): List<Map<String, Any>>? {
        @Suppress("UNCHECKED_CAST")
        val tools = value as? List<Map<String, Any>> ?: return null
        val declarations = tools.mapNotNull { tool ->
            val function = tool["function"] as? Map<*, *> ?: return@mapNotNull null
            val name = function["name"] as? String ?: return@mapNotNull null
            linkedMapOf<String, Any>().apply {
                put("name", name)
                (function["description"] as? String)?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                put("parameters", sanitizeSchema(function["parameters"]) ?: emptyMap<String, Any>())
            }
        }
        return declarations.takeIf { it.isNotEmpty() }?.let { listOf(mapOf("functionDeclarations" to it)) }
    }

    private fun sanitizeSchema(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.mapNotNull { (key, nested) ->
            val name = key as? String ?: return@mapNotNull null
            if (name in GEMINI_UNSUPPORTED_SCHEMA_KEYS) return@mapNotNull null
            sanitizeSchema(nested)?.let { name to it }
        }.toMap()
        is List<*> -> value.mapNotNull(::sanitizeSchema)
        null -> null
        else -> value
    }

    private fun parseArguments(value: Any?): Any = when (value) {
        is String -> parseJson(value) ?: emptyMap<String, Any>()
        null -> emptyMap<String, Any>()
        else -> value
    }

    private fun parseJson(raw: String): Any? = runCatching {
        JsonParser.parseString(raw).takeUnless { it.isJsonNull }
    }.getOrNull()

    private fun parseChunk(raw: String): ParsedGeminiResponse = runCatching {
        val root = JsonParser.parseString(raw).toKotlinMap()
        parseResponse(root)
    }.getOrElse { ParsedGeminiResponse() }

    private fun parseResponse(data: Map<*, *>): ParsedGeminiResponse {
        val usage = (data["usageMetadata"] as? Map<*, *>)?.let { metadata ->
            val prompt = metadata.number("promptTokenCount")
            val completion = metadata.number("candidatesTokenCount")
            mapOf("prompt" to prompt, "completion" to completion, "total" to metadata.number("totalTokenCount", prompt + completion))
        }
        val candidate = (data["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: run {
                val blockReason = ((data["promptFeedback"] as? Map<*, *>)?.get("blockReason") as? String).orEmpty()
                return ParsedGeminiResponse(
                    content = blockReason.takeIf { it.isNotBlank() }?.let { "[Gemini blocked: $it]" }.orEmpty(),
                    usage = usage,
                    finishReason = if (blockReason.isBlank()) "stop" else "content_filter"
                )
            }
        val content = StringBuilder()
        val thinking = StringBuilder()
        val toolCalls = mutableListOf<Map<String, Any>>()
        val parts = (candidate["content"] as? Map<*, *>)?.get("parts") as? List<*> ?: emptyList<Any>()
        parts.forEachIndexed { index, rawPart ->
            val part = rawPart as? Map<*, *> ?: return@forEachIndexed
            val text = part["text"] as? String
            if (part["thought"] == true) thinking.append(text.orEmpty())
            else if (part["thought"] is String) thinking.append(part["thought"] as String)
            else content.append(text.orEmpty())
            val call = part["functionCall"] as? Map<*, *> ?: return@forEachIndexed
            val name = call["name"] as? String ?: return@forEachIndexed
            val item = linkedMapOf<String, Any>(
                "id" to (call["id"] as? String ?: "call_${index}_${UUID.randomUUID().toString().take(8)}"),
                "name" to name,
                "arguments" to (call["args"] ?: emptyMap<String, Any>())
            )
            ((call["thoughtSignature"] ?: part["thoughtSignature"]) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { item["_thought_signature"] = it }
            toolCalls += item
        }
        return ParsedGeminiResponse(
            content = content.toString(),
            thinking = thinking.toString(),
            usage = usage,
            toolCalls = toolCalls,
            finishReason = when (candidate["finishReason"] as? String) {
                "MAX_TOKENS" -> "length"
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter"
                else -> if (toolCalls.isNotEmpty()) "tool_calls" else "stop"
            }
        )
    }

    private fun JsonElement.toKotlinValue(): Any? = when {
        isJsonNull -> null
        isJsonObject -> asJsonObject.entrySet().mapNotNull { (key, value) ->
            value.toKotlinValue()?.let { key to it }
        }.toMap()
        isJsonArray -> asJsonArray.mapNotNull { it.toKotlinValue() }
        asJsonPrimitive.isBoolean -> asBoolean
        asJsonPrimitive.isNumber -> asNumber
        else -> asString
    }

    private fun JsonElement.toKotlinMap(): Map<String, Any> =
        (toKotlinValue() as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value!! }.orEmpty()

    private fun Map<*, *>.number(key: String, fallback: Int = 0): Int =
        (this[key] as? Number)?.toInt() ?: fallback

    private fun isGoogleEndpoint(endpoint: String): Boolean =
        endpoint.contains("generativelanguage.googleapis.com", ignoreCase = true)

    private data class ParsedGeminiResponse(
        val content: String = "",
        val thinking: String = "",
        val usage: Map<String, Int>? = null,
        val toolCalls: List<Map<String, Any>> = emptyList(),
        val finishReason: String? = null
    )

    private val GEMINI_UNSUPPORTED_SCHEMA_KEYS = setOf(
        "\$schema", "\$id", "\$ref", "\$defs", "definitions", "additionalProperties"
    )
}
