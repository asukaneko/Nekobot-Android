package com.nekobot.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一条助手回复里单个模型累计的 Token 用量。
 *
 * Agent 工具循环一轮任务可能调用多次模型，故障转移还会在中途切换到备用模型，
 * 因此模型用量按「模型」聚合后随消息一起保存，供消息详情展示用了哪些模型。
 */
data class MessageModelUsageEntry(
    /** 用户为该模型配置的名称；缺失时退回实际模型标识。 */
    val model: String = "",
    /** 实际请求的模型标识（如 gpt-4o）。 */
    val actualModel: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    /** 该模型被调用的次数（工具循环轮数）。 */
    val calls: Int = 0,
    /** true 表示该模型是在前序模型失败后才接手的（模型回退）。 */
    val fallback: Boolean = false
)

/** 一次模型回退：先在 [model] 上失败，循环才切换到下一个候选模型。 */
data class MessageModelFailoverEntry(
    val model: String = "",
    val actualModel: String = "",
    /** HTTP 状态码；0 表示非 HTTP 失败（超时、连接错误、拒答占位等）。 */
    val statusCode: Int = 0
)

/** 一条助手回复使用过的模型与回退记录；旧消息或单模型回复为 null。 */
data class MessageModelUsage(
    val models: List<MessageModelUsageEntry> = emptyList(),
    val failovers: List<MessageModelFailoverEntry> = emptyList()
) {
    val isEmpty: Boolean get() = models.isEmpty() && failovers.isEmpty()
}

/**
 * 解析 `local_messages.model_usage` 中保存的 JSON。
 *
 * 解析是防御性的：字段缺失取默认值，整体损坏返回 null，绝不因为历史数据抛异常。
 */
fun parseMessageModelUsage(raw: String?): MessageModelUsage? {
    if (raw.isNullOrBlank()) return null
    val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
    if (!root.isJsonObject) return null
    val obj = root.asJsonObject
    val models = obj.getAsJsonArray("models")?.mapNotNull { element ->
        val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        MessageModelUsageEntry(
            model = entry.stringOrEmpty("model"),
            actualModel = entry.stringOrEmpty("actual_model"),
            inputTokens = entry.intOrZero("input_tokens"),
            outputTokens = entry.intOrZero("output_tokens"),
            cachedInputTokens = entry.intOrZero("cached_input_tokens"),
            cacheWriteTokens = entry.intOrZero("cache_write_tokens"),
            calls = entry.intOrZero("calls"),
            fallback = entry.booleanOrFalse("fallback")
        )
    }.orEmpty()
    val failovers = obj.getAsJsonArray("failovers")?.mapNotNull { element ->
        val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        MessageModelFailoverEntry(
            model = entry.stringOrEmpty("model"),
            actualModel = entry.stringOrEmpty("actual_model"),
            statusCode = entry.intOrZero("status_code")
        )
    }.orEmpty()
    return MessageModelUsage(models = models, failovers = failovers).takeUnless { it.isEmpty }
}

/** 组装 `model_usage` 列保存的 JSON；没有可展示的用量时返回 null。 */
fun encodeMessageModelUsage(usage: MessageModelUsage?): String? {
    if (usage == null || usage.isEmpty) return null
    val root = JsonObject()
    val models = JsonArray()
    usage.models.forEach { entry ->
        models.add(JsonObject().apply {
            addProperty("model", entry.model)
            addProperty("actual_model", entry.actualModel)
            addProperty("input_tokens", entry.inputTokens)
            addProperty("output_tokens", entry.outputTokens)
            addProperty("cached_input_tokens", entry.cachedInputTokens)
            addProperty("cache_write_tokens", entry.cacheWriteTokens)
            addProperty("calls", entry.calls)
            addProperty("fallback", entry.fallback)
        })
    }
    val failovers = JsonArray()
    usage.failovers.forEach { entry ->
        failovers.add(JsonObject().apply {
            addProperty("model", entry.model)
            addProperty("actual_model", entry.actualModel)
            addProperty("status_code", entry.statusCode)
        })
    }
    root.add("models", models)
    root.add("failovers", failovers)
    return root.toString()
}

private fun JsonObject.stringOrEmpty(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

private fun JsonObject.intOrZero(name: String): Int =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0

private fun JsonObject.booleanOrFalse(name: String): Boolean =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
