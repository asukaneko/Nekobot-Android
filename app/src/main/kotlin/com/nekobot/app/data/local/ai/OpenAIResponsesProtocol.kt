package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonElement
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

    override fun resolveUrl(
        baseUrl: String,
        model: String,
        appendBaseUrlPath: Boolean,
        stream: Boolean,
        apiKey: String
    ): String {
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
        when (event.string("type")) {
            "response.output_text.delta",
            "response.text.delta" -> event.text("delta").ifBlank { null }
            else -> null
        }
    }.getOrNull()

    override fun parseStreamThinkingChunk(chunkJson: String): String? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        when (event.string("type")) {
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta" -> event.text("delta").ifBlank { null }
            else -> null
        }
    }.getOrNull()

    override fun parseStreamToolCallDeltas(chunkJson: String): List<LocalToolCallDelta> = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        val index = event.intOrNull("output_index") ?: event.intOrNull("index") ?: 0
        when (event.string("type")) {
            "response.output_item.added" -> {
                val item = event.getAsJsonObject("item") ?: return emptyList()
                if (item.string("type") != "function_call") return emptyList()
                listOf(
                    LocalToolCallDelta(
                        index = index,
                        idChunk = item.string("call_id").ifBlank { item.string("id") },
                        nameChunk = item.string("name"),
                        initialArgumentsJson = item.text("arguments")
                    )
                )
            }
            "response.function_call_arguments.delta" -> listOf(
                LocalToolCallDelta(
                    index = index,
                    argumentsChunk = event.text("delta")
                )
            )
            "response.function_call_arguments.done" -> listOf(
                LocalToolCallDelta(
                    index = index,
                    initialArgumentsJson = event.text("arguments")
                )
            )
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    override fun parseStreamFinishReason(chunkJson: String): String? =
        parseStreamFinalResponse(chunkJson)?.finishReason?.ifBlank { null }

    override fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        val response = event.getAsJsonObject("response") ?: event
        val usage = response.getAsJsonObject("usage")
            ?: event.getAsJsonObject("usage")
            ?: return null
        val input = usage.intOrNull("input_tokens") ?: usage.int("prompt_tokens")
        val output = usage.intOrNull("output_tokens") ?: usage.int("completion_tokens")
        Triple(input, output, usage.intOrNull("total_tokens") ?: input + output)
    }.getOrNull()

    override fun parseStreamFinalResponse(chunkJson: String): LocalModelResponse? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        val type = event.string("type")
        val response = when {
            type == "response.completed" || type == "response.done" || type == "response.incomplete" ->
                event.getAsJsonObject("response") ?: event.getAsJsonObject("data") ?: event
            type == "response.output_item.done" -> {
                val item = event.getAsJsonObject("item") ?: return null
                JsonObject().apply {
                    addProperty("status", "completed")
                    add("output", gson.toJsonTree(listOf(item)))
                }
            }
            type == "response.content_part.done" -> {
                val part = event.getAsJsonObject("part") ?: return null
                JsonObject().apply {
                    addProperty("status", "completed")
                    add(
                        "output",
                        gson.toJsonTree(
                            listOf(
                                mapOf(
                                    "type" to "message",
                                    "content" to listOf(part)
                                )
                            )
                        )
                    )
                }
            }
            type == "response.output_text.done" || type == "response.text.done" -> {
                val text = event.text("text").ifBlank { event.text("output_text") }
                if (text.isBlank()) return null
                JsonObject().apply {
                    addProperty("status", "completed")
                    addProperty("output_text", text)
                }
            }
            type == "response.message.done" ->
                event.getAsJsonObject("message")?.let { message ->
                    JsonObject().apply {
                        addProperty("status", "completed")
                        add("output", gson.toJsonTree(listOf(message)))
                    }
                } ?: return null
            event.has("output") || event.has("output_text") || event.has("choices") -> event
            event.getAsJsonObject("response")?.let {
                it.has("output") || it.has("output_text") || it.has("choices")
            } == true -> event.getAsJsonObject("response")
            else -> return null
        }
        @Suppress("UNCHECKED_CAST")
        val data = gson.fromJson(response, Map::class.java) as Map<String, Any>
        parseNonStreamResponse(data)
    }.getOrNull()

    override fun parseStreamError(chunkJson: String): String? = runCatching {
        val event = JsonParser.parseString(chunkJson).asJsonObject
        val type = event.string("type")
        if (type != "error" && type != "response.failed" && type != "response.error") return null
        val error = event.getAsJsonObject("error")
            ?: event.getAsJsonObject("response")?.getAsJsonObject("error")
        error?.string("message")
            ?.ifBlank { error.string("code") }
            ?.ifBlank { "Responses API 请求失败" }
            ?: event.text("message").ifBlank { "Responses API 请求失败" }
    }.getOrNull()

    override fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse {
        @Suppress("UNCHECKED_CAST")
        val response = (data["response"] as? Map<String, Any>) ?: data
        if (response["output"] == null && response["output_text"] == null && response["choices"] is List<*>) {
            return OpenAIChatProtocol.parseNonStreamResponse(response)
        }

        val output = when (val raw = response["output"]) {
            is List<*> -> raw
            null -> emptyList<Any>()
            else -> listOf(raw)
        }
        val contentFromItems = buildString {
            output.forEach { item -> append(extractOutputItemText(item)) }
        }
        val content = contentFromItems.ifBlank {
            extractText(response["output_text"] ?: response["text"] ?: response["content"]).orEmpty()
        }
        val thinking = buildString {
            output.forEach { item ->
                val objectItem = item as? Map<*, *> ?: return@forEach
                if (objectItem["type"] == "reasoning") {
                    val summaryText = extractText(objectItem["summary"]).orEmpty()
                    append(summaryText.ifBlank { extractText(objectItem["content"]).orEmpty() })
                }
            }
        }
        val toolCalls = output.mapNotNull { item ->
            val value = item as? Map<*, *> ?: return@mapNotNull null
            if (value["type"] != "function_call" && value["type"] != "tool_call") return@mapNotNull null
            val function = value["function"] as? Map<*, *>
            val name = (value["name"] ?: function?.get("name")) as? String ?: return@mapNotNull null
            val arguments = parseArguments(value["arguments"] ?: function?.get("arguments"))
            mapOf(
                "id" to ((value["call_id"] ?: value["id"]) as? String).orEmpty(),
                "name" to name,
                "arguments" to arguments
            )
        }
        val usageMap = response["usage"] as? Map<*, *>
        val inputTokens = ((usageMap?.get("input_tokens") ?: usageMap?.get("prompt_tokens")) as? Number)?.toInt() ?: 0
        val outputTokens = ((usageMap?.get("output_tokens") ?: usageMap?.get("completion_tokens")) as? Number)?.toInt() ?: 0
        val responseStatus = (response["status"] as? String).orEmpty()
        val explicitFinishReason = (response["finish_reason"] as? String).orEmpty()
        val incompleteReason = ((response["incomplete_details"] as? Map<*, *>)?.get("reason") as? String).orEmpty()
        val finishReason = when {
            toolCalls.isNotEmpty() -> "tool_calls"
            explicitFinishReason.isNotBlank() -> explicitFinishReason
            responseStatus == "completed" -> "stop"
            responseStatus == "incomplete" -> if (incompleteReason.contains("max", ignoreCase = true)) "length" else "incomplete"
            content.isNotBlank() -> "stop"
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

    private fun extractOutputItemText(item: Any?): String = when (item) {
        is String -> item
        is Map<*, *> -> when (item["type"] as? String) {
            "message" -> extractText(item["content"]).orEmpty()
            "output_text", "text" -> extractText(item["text"] ?: item["content"] ?: item["value"]).orEmpty()
            else -> if (item.containsKey("content") && item["type"] != "reasoning") {
                extractText(item["content"]).orEmpty()
            } else {
                ""
            }
        }
        else -> ""
    }

    private fun parseArguments(raw: Any?): Map<String, Any> = when (raw) {
        is Map<*, *> -> raw.entries.associate { it.key.toString() to (it.value ?: "") }
        is String -> runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(raw, Map::class.java) as Map<String, Any>
        }.getOrDefault(emptyMap())
        else -> emptyMap()
    }

    private fun extractText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is List<*> -> value.mapNotNull { block ->
            when (block) {
                is String -> block
                is Map<*, *> -> extractText(
                    block["text"] ?: block["output_text"] ?: block["content"] ?:
                    block["value"] ?: block["delta"] ?: block["refusal"]
                )
                else -> null
            }
        }.joinToString("")
        is Map<*, *> -> extractText(
            value["text"] ?: value["output_text"] ?: value["content"] ?:
            value["value"] ?: value["delta"] ?: value["refusal"]
        )
        else -> value.toString()
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.text(key: String): String = extractJsonText(get(key))

    private fun extractJsonText(value: JsonElement?): String = when {
        value == null || value.isJsonNull -> ""
        value.isJsonPrimitive -> runCatching { value.asString }.getOrDefault("")
        value.isJsonArray -> value.asJsonArray.joinToString("") { extractJsonText(it) }
        value.isJsonObject -> {
            val obj = value.asJsonObject
            extractJsonText(
                obj.get("text") ?: obj.get("output_text") ?: obj.get("content") ?:
                obj.get("value") ?: obj.get("delta") ?: obj.get("refusal")
            )
        }
        else -> ""
    }

    private fun JsonObject.int(key: String): Int = intOrNull(key) ?: 0

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.let {
            runCatching { it.asInt }.getOrNull()
        }
}
