package com.nekobot.app.ui.screens.chat

import com.nekobot.app.R

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessageModelUsage
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「消息详情」里的一行模型用量：左侧模型名、右侧用量（回退记录同样复用这一行）。 */
internal data class MessageDetailsModelLine(val label: String, val usage: String)

internal data class MessageDetails(
    val sentAt: String,
    val model: String,
    val inputTokens: String,
    val outputTokens: String,
    val totalTokens: String,
    val duration: String,
    val speed: String,
    /**
     * 本条回复用过的模型与回退记录；旧消息或单次调用为空的模型用量为 null。
     *
     * 顺序与首次使用一致：先列实际完成生成的模型，再列被回退掉的模型。
     */
    val modelUsage: List<MessageDetailsModelLine> = emptyList()
)

/** 时区由调用方提供；不读时钟、不猜缺失日期、不修改消息。 */
internal fun formatMessageTimestamp(timestamp: String?, zone: ZoneId, unrecorded: String): String {
    val raw = timestamp?.trim()?.takeIf { it.isNotEmpty() } ?: return unrecorded
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    val numeric = raw.toLongOrNull()
    if (numeric != null) return runCatching {
        val instant = if (numeric >= 100_000_000_000L || numeric <= -100_000_000_000L)
            Instant.ofEpochMilli(numeric) else Instant.ofEpochSecond(numeric)
        formatter.format(instant.atZone(zone))
    }.getOrDefault(unrecorded)
    val normalized = raw.replace(' ', 'T')
    val instant = runCatching { Instant.parse(normalized) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
    if (instant != null) return formatter.format(instant.atZone(zone))
    return runCatching { formatter.format(LocalDateTime.parse(normalized)) }.getOrDefault(unrecorded)
}

internal fun formatMessageDetails(message: Message, zone: ZoneId, text: (Int) -> String): MessageDetails {
    val unrecorded = text(R.string.single_role_not_recorded)
    val input = message.inputTokens?.takeIf { it >= 0 }
    val output = message.outputTokens?.takeIf { it >= 0 }
    val total = if (input != null && output != null) input.toLong() + output.toLong()
        else message.tokens?.takeIf { it >= 0 }?.toLong()
    val duration = message.durationMs?.takeIf { it.isFinite() && it > 0 }
    val speed = if (output != null && duration != null) (output * 1000.0 / duration).takeIf { it.isFinite() } else null
    fun count(value: Long?): String = value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } ?: unrecorded
    return MessageDetails(
        formatMessageTimestamp(message.timestamp, zone, unrecorded), message.model?.trim()?.takeIf { it.isNotEmpty() } ?: unrecorded,
        count(input?.toLong()), count(output?.toLong()), count(total),
        duration?.let { String.format(Locale.US, text(R.string.single_role_1f_s), it / 1000.0) } ?: unrecorded,
        speed?.let { String.format(Locale.US, text(R.string.single_role_1f_tokens_s), it) } ?: unrecorded,
        modelUsage = formatMessageModelUsage(message.modelUsage, text, unrecorded, ::count)
    )
}

/**
 * 按模型列出本条回复的用量，再列出被回退掉的模型。
 *
 * 没有记录时返回空列表，详情页据此整段隐藏；被回退的模型排在最后，
 * 让「实际用了哪些模型」先出现。
 */
private fun formatMessageModelUsage(
    usage: MessageModelUsage?,
    text: (Int) -> String,
    unrecorded: String,
    count: (Long?) -> String
): List<MessageDetailsModelLine> {
    if (usage == null) return emptyList()
    val lines = usage.models.map { entry ->
        val name = entry.model.trim().ifEmpty { entry.actualModel.trim() }.ifEmpty { unrecorded }
        val parts = buildList {
            if (entry.inputTokens > 0 || entry.outputTokens > 0) {
                add(
                    String.format(
                        Locale.ROOT,
                        text(R.string.message_details_model_usage),
                        count(entry.inputTokens.toLong()),
                        count(entry.outputTokens.toLong())
                    )
                )
            }
            if (entry.calls > 1) add(String.format(Locale.ROOT, text(R.string.message_details_model_calls), entry.calls))
            if (entry.fallback) add(text(R.string.message_details_model_fallback))
        }
        MessageDetailsModelLine(name, parts.joinToString(" · ").ifEmpty { unrecorded })
    }
    val failoverLines = usage.failovers.map { entry ->
        val name = entry.model.trim().ifEmpty { entry.actualModel.trim() }.ifEmpty { unrecorded }
        val reason = if (entry.statusCode > 0) {
            String.format(Locale.ROOT, text(R.string.message_details_model_failover_code), entry.statusCode)
        } else {
            text(R.string.message_details_model_failover)
        }
        MessageDetailsModelLine(name, reason)
    }
    return lines + failoverLines
}
