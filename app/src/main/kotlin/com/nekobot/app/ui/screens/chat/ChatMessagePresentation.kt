package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.local.ChatInputLayoutMode

/** 列表快照按引用失效，避免 remember 的 key 比较递归遍历工具结果。 */
internal class ChatSnapshotKey(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is ChatSnapshotKey && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

internal fun shouldShowChatInput(
    layoutMode: ChatInputLayoutMode,
    inputExpanded: Boolean,
    hasPlotSurface: Boolean,
    hasDraft: Boolean
): Boolean = layoutMode == ChatInputLayoutMode.SEPARATE ||
    !hasPlotSurface || inputExpanded || hasDraft

/**
 * 流式阶段只向 Compose 暴露一个有界窗口；完整正文仍保留在累加器并最终写入消息。
 * 这样长回复不会让单个 Text/Markdown 布局随 token 无限增长。
 */
internal fun buildStreamingDisplayPreview(
    content: CharSequence,
    maxChars: Int = 16_000,
    omittedPrefix: String = "…\n"
): String {
    if (content.length <= maxChars) return content.toString()
    return omittedPrefix +
        content.subSequence(content.length - maxChars, content.length).toString()
}

/** 流式预览已限制长度，保持 Markdown 渲染；仅普通超长 AI 消息降级为分页纯文本。 */
internal fun shouldUseSafePlainText(
    isUser: Boolean,
    contentLength: Int,
    isStreaming: Boolean
): Boolean = !isStreaming && !isUser && contentLength > 24_000

/**
 * 结束流式消息时整理 UI 列表。
 *
 * 本地模式在 StreamEnd 前已写入 Room，不创建临时正式消息；服务器模式保留一条兜底消息，
 * 等服务端持久化结果刷新后再用真实 ID 替换。
 */
internal fun finalizeStreamEndMessages(
    current: List<Message>,
    streamingId: String,
    finalContent: String,
    finalReasoning: String = "",
    materializeFallback: Boolean,
    fallbackId: String = ChatViewModel.STREAM_FALLBACK_PREFIX + java.util.UUID.randomUUID(),
    fallbackTimestamp: String = System.currentTimeMillis().toString()
): List<Message> {
    val withoutPlaceholder = current.filter { it.id != streamingId }
    if (!materializeFallback || (finalContent.isBlank() && finalReasoning.isBlank())) return withoutPlaceholder
    if (withoutPlaceholder.any {
            !it.isUser && it.content == finalContent && it.reasoningContent == finalReasoning.takeIf(String::isNotBlank)
        }) return withoutPlaceholder
    return withoutPlaceholder + Message(
        id = fallbackId,
        role = "assistant",
        content = finalContent,
        reasoningContent = finalReasoning.takeIf(String::isNotBlank),
        timestamp = fallbackTimestamp
    )
}

/**
 * 合并 Socket `new_message`：
 * - WebUI 发出的用户消息直接追加到安卓当前会话；
 * - 安卓自身发送的消息用服务端正式 id 替换乐观消息；
 * - AI 消息移除流式占位并按 id 去重。
 */
internal fun mergeRealtimeNewMessage(
    current: List<Message>,
    incoming: Message,
    isSending: Boolean,
    streamingId: String = ChatViewModel.STREAMING_ID,
    fallbackPrefix: String = ChatViewModel.STREAM_FALLBACK_PREFIX
): List<Message> {
    val normalizedCurrent = deduplicateMessagesById(current)
    val incomingId = incoming.id
    if (!incomingId.isNullOrBlank()) {
        val existingIndex = normalizedCurrent.indexOfFirst { it.id == incomingId }
        if (existingIndex >= 0) {
            val existing = normalizedCurrent[existingIndex]
            return normalizedCurrent.toMutableList().apply {
                this[existingIndex] = incoming.copy(
                    thinkingCards = incoming.thinkingCards ?: existing.thinkingCards,
                    audioUrl = incoming.audioUrl ?: existing.audioUrl
                )
            }
        }
    }

    if (incoming.isUser) {
        val optimisticIndex = if (isSending) {
            normalizedCurrent.indexOfLast {
                it.isUser &&
                    (it.id.isNullOrBlank() || isUrgentBubbleId(it.id)) &&
                    it.content == incoming.content
            }
        } else {
            -1
        }
        if (optimisticIndex >= 0) {
            val optimistic = normalizedCurrent[optimisticIndex]
            return normalizedCurrent.toMutableList().apply {
                this[optimisticIndex] = incoming.copy(
                    thinkingCards = incoming.thinkingCards ?: optimistic.thinkingCards
                )
            }
        }
        return normalizedCurrent + incoming
    }

    return normalizedCurrent.filter {
        it.id != streamingId &&
            !(it.id?.startsWith(fallbackPrefix) == true && it.content == incoming.content)
    } + incoming
}

/**
 * Room 主键本身唯一，但页面运行时状态与刚落库数据合并时可能短暂包含同一消息两份。
 * 所有进入 LazyColumn 的列表都在边界处按非空 ID 去重；无 ID 的乐观消息必须保留。
 */
internal fun deduplicateMessagesById(messages: List<Message>): List<Message> {
    val seenIds = HashSet<String>()
    var changed = false
    val result = ArrayList<Message>(messages.size)
    messages.forEach { message ->
        val id = message.id?.takeIf(String::isNotBlank)
        if (id != null && !seenIds.add(id)) {
            changed = true
        } else {
            result.add(message)
        }
    }
    return if (changed) result else messages
}

/**
 * 把时间戳/时间字符串精简到「分钟」级，尽量短以节省气泡下方空间。
 * 支持毫秒时间戳、ISO 8601（含时区 Z/+HH:MM）、已格式化字符串三种输入。
 * 自动按手机时区显示：例 2026-07-10T06:30:45.123Z（UTC）在 UTC+8 设备上 → "14:30"
 */
internal fun compactTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    return try {
        when {
            // 纯数字时间戳（秒/毫秒）
            s.matches(Regex("^\\d{10,13}$")) -> {
                val ms = if (s.length == 10) s.toLong() * 1000 else s.toLong()
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(ms))
            }
            // ISO 8601 带时区标识（Z 或 +HH:MM[:SS]）：按手机时区转换
            s.contains('T') && (s.endsWith('Z') || s.contains(Regex("[+\\-]\\d{2}:?\\d{2}"))) -> {
                val instant = parseIsoInstant(s) ?: return null
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(instant.toEpochMilli()))
            }
            // ISO 或带 T 的时间（无时区标识）：直接提取 HH:mm（视为本地时区）
            s.contains('T') -> {
                val timePart = s.substringAfter('T').take(5)
                if (timePart.matches(Regex("\\d{2}:\\d{2}"))) timePart else null
            }
            // 含空格分隔日期时间：取时间部分前 5 位（视为本地时区）
            s.contains(' ') -> {
                val timePart = s.substringAfter(' ').take(5)
                if (timePart.matches(Regex("\\d{2}:\\d{2}"))) timePart else null
            }
            // 仅时间 HH:mm:ss 或 HH:mm
            s.matches(Regex("\\d{2}:\\d{2}(:\\d{2})?")) -> s.take(5)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** 解析 ISO 8601 字符串为 java.time.Instant，支持 Z 后缀与 ±HH:MM[:SS] 偏移。 */
internal fun parseIsoInstant(s: String): java.time.Instant? {
    return try {
        // Instant.parse 仅支持 Z 后缀；偏移格式用 OffsetDateTime 解析
        if (s.endsWith('Z')) {
            java.time.Instant.parse(s)
        } else {
            // 尝试解析带偏移的 ISO 字符串
            java.time.OffsetDateTime.parse(s).toInstant()
        }
    } catch (_: Exception) {
        // 兜底：尝试 LocalDateTime + 系统默认时区（虽然此分支理论上不应触达）
        try {
            java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}
