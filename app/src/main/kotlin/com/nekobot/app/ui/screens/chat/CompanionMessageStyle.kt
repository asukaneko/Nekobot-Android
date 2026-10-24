package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nekobot.app.data.model.Message
import com.nekobot.app.ui.components.MarkdownBubbleColors

internal data class CompanionAssistantBubbleStyle(
    val background: Color,
    val content: Color,
    val markdown: MarkdownBubbleColors
)

/** 链接跟随各自气泡的字色，保持自定义背景上的对比度。 */
internal fun companionBubbleLinkColor(palette: CompanionChatPalette, isUser: Boolean = false): Color =
    if (isUser) palette.outgoingInk else palette.incomingInk

/** 角色回复使用独立的 AI 气泡背景与字色。 */
internal fun companionAssistantBubbleStyle(palette: CompanionChatPalette): CompanionAssistantBubbleStyle {
    val dark = palette.background.luminance() < 0.5f
    val background = palette.incoming
    val content = palette.incomingInk
    return CompanionAssistantBubbleStyle(
        background, content,
        MarkdownBubbleColors(
            link = companionBubbleLinkColor(palette),
            surface = if (dark) Color(0xFF2A3031) else Color(0xFFEDF0EF),
            onSurface = if (dark) Color(0xFFF0F4F2) else Color(0xFF202927),
            surfaceLink = if (dark) Color(0xFF9CD7FF) else Color(0xFF174C3C),
            parentheses = content
        )
    )
}

/** 时间分隔和头像分组共用边界，避免分隔线后留下无头像的续条。 */
internal fun companionStartsTimeSection(
    first: Boolean,
    streaming: Boolean,
    day: String?,
    previousDay: String?,
    time: String?,
    previousTime: String?
): Boolean {
    if (streaming) return false
    fun minutes(value: String?): Int? {
        val parts = value?.split(":") ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour * 60 + minute
    }
    val gap = minutes(time)?.let { now -> minutes(previousTime)?.let { now - it } }
    return first || day != previousDay || gap == null || gap >= 5 || gap < 0
}

internal fun companionShowsAvatar(message: Message, previous: Message?, startsTimeSection: Boolean): Boolean {
    if (message.isUser || message.isThinkingCard) return false
    fun sender(message: Message): String =
        message.sender?.trim()?.takeIf(String::isNotEmpty)
            ?: message.name?.trim()?.takeIf(String::isNotEmpty) ?: "assistant"
    return previous == null || previous.isUser || previous.isThinkingCard || startsTimeSection ||
        sender(message) != sender(previous)
}

/** 为头像、列表边距和对侧留白留出空间；窄屏不强加最小气泡宽度。 */
internal fun companionBubbleMaxWidthDp(screenWidthDp: Int, isUser: Boolean): Int =
    (screenWidthDp - if (isUser) 64 else 96).coerceIn(1, 360)

/** 与会话列表使用同一头像优先规则；旧图标标记不作为照片。 */
internal fun companionPhotoPath(portrait: String?, avatar: String?): String? =
    com.nekobot.app.data.model.characterAvatarPath(avatar, portrait)
