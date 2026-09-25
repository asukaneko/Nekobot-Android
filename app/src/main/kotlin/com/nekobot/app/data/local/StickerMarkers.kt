package com.nekobot.app.data.local

/**
 * 聊天正文中的表情包标记工具。
 *
 * 规则：正文中出现 `[名称]` 且该名称能匹配到已导入的表情时，UI 渲染原图；
 * 匹配不到时保持原文，绝不改写消息内容。因此本对象只做"识别"，不做替换。
 */
object StickerMarkers {
    /** 名称长度上限（不含方括号）。 */
    const val MAX_NAME_LENGTH = 64

    /**
     * 单个方括号标记：允许除方括号与换行外的任意字符。
     * 与 `[File: xxx]` 引用共存时由 [isReservedMarker] 排除。
     */
    private val BRACKET_MARKER_REGEX = Regex("""\[([^\[\]\n]{1,$MAX_NAME_LENGTH})]""")

    /** 保留前缀：这些标记属于文件引用等既有语义，不作为表情名匹配。 */
    private fun isReservedMarker(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.startsWith("File:", ignoreCase = true) || trimmed.startsWith("文件:")
    }

    /** 提取正文中所有可能的方括号标记名（含未匹配到表情的候选项，保留去重顺序）。 */
    fun candidateNames(content: String): List<String> {
        if (content.isEmpty() || '[' !in content) return emptyList()
        return BRACKET_MARKER_REGEX.findAll(content)
            .mapNotNull { match ->
                val name = match.groupValues[1].trim()
                name.takeIf { it.isNotEmpty() && !isReservedMarker(it) }
            }
            .distinct()
            .toList()
    }

    /** 正文是否已经包含对某表情名称的引用（用于 AI 工具结果去重）。 */
    fun containsReference(content: String, name: String): Boolean {
        if (content.isEmpty() || name.isBlank()) return false
        return Regex("""\[${Regex.escape(name.trim())}]""").containsMatchIn(content)
    }

    /** 清理用户输入或文件名推导出的名称：去掉方括号/换行并限制长度。 */
    fun sanitizeName(raw: String): String = raw
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace("[", "")
        .replace("]", "")
        .trim()
        .take(MAX_NAME_LENGTH)
        .trim()
}
