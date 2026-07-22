package com.nekobot.app.data.local.ai

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

private fun estimateLocalMessagesTokens(messages: List<Map<String, Any>>): Int {
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
