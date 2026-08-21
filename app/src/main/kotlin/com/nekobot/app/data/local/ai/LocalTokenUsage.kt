package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** 本地模型单次调用的标准化 Token 用量。 */
internal data class ResolvedLocalTokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val estimated: Boolean
)

/**
 * 统一不同兼容接口的 usage 字段；接口未返回用量时才按实际请求与回复文本估算。
 */
internal fun resolveLocalTokenUsage(
    usage: Map<String, *>,
    messages: List<Map<String, Any>>,
    outputText: String
): ResolvedLocalTokenUsage {
    val reportedInput = usage.tokenNumber("prompt_tokens", "input_tokens", "prompt", "input")
    val reportedOutput = usage.tokenNumber("completion_tokens", "output_tokens", "completion", "output")
    val reportedTotal = usage.tokenNumber("total_tokens", "total")

    if (reportedInput != null || reportedOutput != null || reportedTotal != null) {
        val input = reportedInput
            ?: reportedTotal?.minus(reportedOutput ?: 0)?.coerceAtLeast(0)
            ?: 0
        val output = reportedOutput
            ?: reportedTotal?.minus(input)?.coerceAtLeast(0)
            ?: 0
        if (input > 0 || output > 0) {
            return ResolvedLocalTokenUsage(input, output, estimated = false)
        }
    }

    return ResolvedLocalTokenUsage(
        inputTokens = estimateLocalMessagesTokens(messages),
        outputTokens = estimateLocalTextTokens(outputText),
        estimated = true
    )
}

/**
 * 估算当前仍在上下文中的 Token，而不是把每一轮完整 prompt 的计费用量重复相加。
 * 最近一条带服务商 usage 的助手消息可视为当时完整上下文；只需再加其后的消息。
 */
internal fun currentLocalContextTokens(messages: List<LocalContextTokenMessage>): Long {
    if (messages.isEmpty()) return 0L
    val measuredIndex = messages.indexOfLast {
        it.inputTokens != null && it.outputTokens != null &&
            (it.inputTokens > 0 || it.outputTokens > 0)
    }
    if (measuredIndex >= 0) {
        val measured = messages[measuredIndex]
        val trailing = messages.drop(measuredIndex + 1).sumOf {
            (estimateLocalTextTokens(it.content) + MESSAGE_OVERHEAD_TOKENS).toLong()
        }
        return (measured.inputTokens!!.toLong() + measured.outputTokens!!.toLong() + trailing)
            .coerceAtLeast(0L)
    }

    return messages.sumOf {
        (estimateLocalTextTokens(it.content) + MESSAGE_OVERHEAD_TOKENS).toLong()
    } + CHAT_PRIMING_TOKENS
}

internal data class LocalContextTokenMessage(
    val content: String,
    val inputTokens: Int?,
    val outputTokens: Int?
)

/** Room 中可用于补回 token 明细的 assistant 消息快照。 */
internal data class LocalPersistedTokenMessage(
    val id: String,
    val sessionId: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val timestamp: String,
    val createdAt: String,
    val sessionCreatedAt: String,
    val content: String
)

internal data class LocalTokenUsageReconciliation(
    val records: List<JsonObject>,
    val recoveredCount: Int,
    val changed: Boolean
)

/**
 * 用 Room 内已成功落库的 assistant 消息修复缺失的主对话 token 记录。
 *
 * 新记录用 message_id 精确去重；旧版本记录没有 message_id 时，按会话和 token 数量逐条匹配，
 * 并回填关联，避免升级后把已有用量重复计算。
 */
internal fun reconcileLocalTokenUsageRecords(
    records: List<JsonObject>,
    messages: List<LocalPersistedTokenMessage>
): LocalTokenUsageReconciliation {
    val normalized = records.map { it.deepCopy() }.toMutableList()
    val linkedRecordByMessageId = normalized.mapIndexedNotNull { index, record ->
        record.stringValue("message_id")?.let { it to index }
    }.toMap().toMutableMap()
    val legacyByUsage = mutableMapOf<LegacyChatUsageKey, ArrayDeque<Int>>()

    normalized.forEachIndexed { index, record ->
        if (record.stringValue("message_id").isNullOrBlank() && record.isPrimaryChatUsage()) {
            record.legacyChatUsageKey()?.let { key ->
                legacyByUsage.getOrPut(key) { ArrayDeque() }.addLast(index)
            }
        }
    }

    // fork 会完整复制历史消息（仅更换 message/session id）。同一指纹跨会话重复时，
    // 保留一条实际调用记录，其余标记为继承用量，供会话统计使用但不重复计入全局消耗。
    val inheritedMessageIds = messages
        .groupBy { it.usageFingerprint() }
        .values
        .filter { group -> group.map { it.sessionId }.distinct().size > 1 }
        .flatMap { group ->
            val canonical = group.firstOrNull { message ->
                linkedRecordByMessageId[message.id]?.let { index ->
                    normalized[index].booleanValue("inherited") != true &&
                        normalized[index].booleanValue("recovered") != true
                } == true
            } ?: group.firstOrNull { message ->
                legacyByUsage[message.legacyChatUsageKey()]?.isNotEmpty() == true
            } ?: group.firstOrNull { message ->
                linkedRecordByMessageId[message.id]?.let { index ->
                    normalized[index].booleanValue("inherited") != true
                } == true
            } ?: group.minWith(compareBy(LocalPersistedTokenMessage::sessionCreatedAt, LocalPersistedTokenMessage::id))
            group.filterNot { it.id == canonical.id }.map { it.id }
        }
        .toSet()

    var recoveredCount = 0
    var changed = false
    messages.forEach { message ->
        if (message.inputTokens <= 0 && message.outputTokens <= 0) return@forEach

        val linkedIndex = linkedRecordByMessageId[message.id]
        if (message.id in inheritedMessageIds) {
            if (linkedIndex != null) {
                val record = normalized[linkedIndex]
                if (record.booleanValue("inherited") != true || record.stringValue("source") != "fork") {
                    record.addProperty("inherited", true)
                    record.addProperty("source", "fork")
                    record.addProperty("purpose", TokenStatsManager.PURPOSE_CHAT)
                    changed = true
                }
            } else {
                normalized += message.toUsageRecord(inherited = true)
                linkedRecordByMessageId[message.id] = normalized.lastIndex
                changed = true
            }
            return@forEach
        }

        if (linkedIndex != null) return@forEach

        val key = message.legacyChatUsageKey()
        val legacyIndex = legacyByUsage[key]?.removeFirstOrNull()
        if (legacyIndex != null) {
            normalized[legacyIndex].addProperty("message_id", message.id)
            linkedRecordByMessageId[message.id] = legacyIndex
            changed = true
            return@forEach
        }

        normalized += message.toUsageRecord(inherited = false)
        linkedRecordByMessageId[message.id] = normalized.lastIndex
        recoveredCount++
        changed = true
    }

    return LocalTokenUsageReconciliation(normalized, recoveredCount, changed)
}

private data class LegacyChatUsageKey(
    val sessionId: String,
    val inputTokens: Int,
    val outputTokens: Int
)

private data class PersistedMessageUsageFingerprint(
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val timestamp: String,
    val createdAt: String,
    val content: String
)

private fun LocalPersistedTokenMessage.usageFingerprint() = PersistedMessageUsageFingerprint(
    model = model,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    timestamp = timestamp,
    createdAt = createdAt,
    content = content
)

private fun LocalPersistedTokenMessage.legacyChatUsageKey() = LegacyChatUsageKey(
    sessionId = sessionId,
    inputTokens = inputTokens,
    outputTokens = outputTokens
)

private fun LocalPersistedTokenMessage.toUsageRecord(inherited: Boolean) = JsonObject().apply {
    addProperty("id", "message:$id")
    addProperty("message_id", id)
    addProperty("session_id", sessionId)
    addProperty("model", model)
    addProperty("input_tokens", inputTokens)
    addProperty("output_tokens", outputTokens)
    addProperty("total_tokens", inputTokens + outputTokens)
    addProperty("timestamp", timestamp)
    addProperty("source", if (inherited) "fork" else "chat")
    addProperty("purpose", TokenStatsManager.PURPOSE_CHAT)
    // Room 仅保存 token 数量，无法判断原始接口是否返回 usage，恢复项保守标为估算。
    addProperty("estimated", true)
    addProperty("recovered", true)
    addProperty("inherited", inherited)
    addProperty("date", localTokenRecordDate(timestamp))
}

private fun JsonObject.legacyChatUsageKey(): LegacyChatUsageKey? {
    val sessionId = stringValue("session_id") ?: return null
    val inputTokens = intValue("input_tokens") ?: return null
    val outputTokens = intValue("output_tokens") ?: return null
    return LegacyChatUsageKey(sessionId, inputTokens, outputTokens)
}

private fun JsonObject.isPrimaryChatUsage(): Boolean {
    val source = stringValue("source").orEmpty()
    val purpose = stringValue("purpose").orEmpty()
    return (source.isBlank() || source == "chat") &&
        (purpose.isBlank() || purpose == TokenStatsManager.PURPOSE_CHAT)
}

private fun JsonObject.stringValue(name: String): String? = runCatching {
    get(name)?.takeIf { it.isJsonPrimitive }?.asString
}.getOrNull()

private fun JsonObject.intValue(name: String): Int? = runCatching {
    get(name)?.takeIf { it.isJsonPrimitive }?.asInt
}.getOrNull()

private fun JsonObject.booleanValue(name: String): Boolean? = runCatching {
    get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean
}.getOrNull()

private fun localTokenRecordDate(timestamp: String): String {
    val epochMillis = timestamp.toLongOrNull()?.let { value ->
        if (value < 10_000_000_000L) value * 1000L else value
    }
    if (epochMillis != null) {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
    }
    val date = timestamp.substringBefore('T').substringBefore(' ')
    return date.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

/** 轻量估算器仅用于服务商不返回 usage 的降级路径。 */
internal fun estimateLocalTextTokens(text: String): Int {
    if (text.isBlank()) return 0
    var tokens = 0
    var asciiRun = 0

    fun flushAsciiRun() {
        if (asciiRun > 0) {
            tokens += ceil(asciiRun / 4.0).toInt()
            asciiRun = 0
        }
    }

    text.codePoints().forEach { codePoint ->
        when {
            Character.isWhitespace(codePoint) -> flushAsciiRun()
            codePoint < 128 && Character.isLetterOrDigit(codePoint) -> asciiRun++
            else -> {
                flushAsciiRun()
                tokens += when {
                    isCjkLike(codePoint) -> 1
                    Character.isLetterOrDigit(codePoint) -> 1
                    else -> 1
                }
            }
        }
    }
    flushAsciiRun()
    return tokens.coerceAtLeast(1)
}

/** 估算一次请求实际发送的消息上下文，供路由和故障转移容量检查共用。 */
internal fun estimateLocalMessagesTokens(messages: List<Map<String, Any>>): Int {
    if (messages.isEmpty()) return 0
    val total = messages.sumOf { message ->
        MESSAGE_OVERHEAD_TOKENS + estimateMessageValueTokens(message["content"])
    } + CHAT_PRIMING_TOKENS
    return total.coerceAtLeast(1)
}

private fun estimateMessageValueTokens(value: Any?): Int = when (value) {
    null -> 0
    is String -> estimateLocalTextTokens(value)
    is List<*> -> value.sumOf(::estimateMessageValueTokens)
    is Map<*, *> -> {
        val preferred = value["text"] ?: value["content"]
        if (preferred != null) estimateMessageValueTokens(preferred)
        else value.values.sumOf(::estimateMessageValueTokens)
    }
    else -> 0
}

private fun Map<String, *>.tokenNumber(vararg keys: String): Int? {
    for (key in keys) {
        val value = this[key] ?: continue
        val number = when (value) {
            is Number -> value.toLong()
            is String -> value.toDoubleOrNull()?.toLong()
            else -> null
        } ?: continue
        return number.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
    return null
}

private fun isCjkLike(codePoint: Int): Boolean =
    codePoint in 0x3400..0x9FFF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0x3040..0x30FF ||
        codePoint in 0xAC00..0xD7AF

private const val MESSAGE_OVERHEAD_TOKENS = 4
private const val CHAT_PRIMING_TOKENS = 2
