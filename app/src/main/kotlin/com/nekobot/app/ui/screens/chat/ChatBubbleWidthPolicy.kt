package com.nekobot.app.ui.screens.chat

/** 整宽 Agent 正文填满气泡可用空间，用户消息与独立表情包维持原有宽度。 */
internal fun shouldFillAvailableMessageBodyWidth(
    fillAiWidth: Boolean,
    isUser: Boolean,
    standaloneSticker: Boolean
): Boolean = fillAiWidth && !isUser && !standaloneSticker

/**
 * 正文是否含围栏代码块或表格。
 *
 * 这类内容天然比正文宽（目录树、JSON、日志行），沿用正文的气泡宽度上限只会被横向裁掉大半，
 * 因此含代码块/表格的消息改用多媒体内容那一档更宽的上限。
 */
internal fun markdownUsesWideBlocks(text: String): Boolean {
    if (!text.contains('`') && !text.contains('|')) return false
    return text.lineSequence().any { line ->
        val trimmed = line.trim()
        trimmed.startsWith("```") || isTableSeparatorLine(trimmed)
    }
}

/** 表格分隔行：只由 `|`、`-`、`:` 和空白组成，且至少含一个 `-`。 */
private fun isTableSeparatorLine(line: String): Boolean =
    line.contains('-') && TABLE_SEPARATOR_LINE.matches(line)
