package com.nekobot.app.data.local.ai

/**
 * 网页正文抽取（`web_fetch` 工具的核心）。
 *
 * `http_get` 只把原始 HTML 原样丢给模型：一个普通页面里 80% 以上是脚本、样式与导航标签，
 * 既浪费上下文又干扰判断。这里在本地做一次轻量"可读性"处理——去掉脚本/样式/注释/标签，
 * 保留段落结构——再把纯文本交给模型。
 */
internal object LocalWebFetch {

    /** HTML 标签的移除顺序：先整块丢弃脚本/样式等不可见内容。 */
    private val droppedBlocks = listOf(
        Regex("(?is)<script\\b[^>]*>.*?</script>"),
        Regex("(?is)<style\\b[^>]*>.*?</style>"),
        Regex("(?is)<noscript\\b[^>]*>.*?</noscript>"),
        Regex("(?is)<template\\b[^>]*>.*?</template>"),
        Regex("(?is)<svg\\b[^>]*>.*?</svg>"),
        Regex("(?is)<head\\b[^>]*>.*?</head>"),
        Regex("(?is)<!--.*?-->")
    )

    /** 会被转换为换行的块级标签，保留段落/列表的可读性。 */
    private val blockTags = Regex(
        "(?i)</?(p|div|br|li|ul|ol|tr|table|section|article|header|footer|nav|h[1-6]|pre|blockquote)\\b[^>]*>"
    )

    private val entityMap = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…"
    )

    /** 判断响应体是否看起来是 HTML（含标签）。 */
    internal fun looksLikeHtml(contentType: String?, body: String): Boolean {
        val type = contentType?.lowercase().orEmpty()
        if (type.contains("html")) return true
        if (type.contains("json") || type.contains("plain")) return false
        return Regex("(?i)<(html|body|div|p|a|span)\\b").containsMatchIn(body.take(2_000))
    }

    /**
     * 抽取可读正文。
     *
     * @param raw 原始响应体
     * @param maxChars 返回长度上限；缺省时取统一的工具输出上限（设置 → Agent 设置）
     */
    internal fun extractReadableText(raw: String, maxChars: Int = AgentToolLimits.toolOutputChars()): String {
        if (raw.isBlank()) return ""
        var text = raw
        droppedBlocks.forEach { regex -> text = regex.replace(text, "\n") }
        text = blockTags.replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, "")
        entityMap.forEach { (entity, replacement) -> text = text.replace(entity, replacement) }
        text = Regex("&#(\\d+);").replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.let { code -> code.toChar().toString() } ?: ""
        }
        text = text.replace('\u00A0', ' ')
        // 逐行去空白 + 合并连续空行，保留段落分隔但去掉 HTML 缩进造成的噪声。
        text = text.lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        val budget = maxChars.coerceIn(
            AgentToolLimits.MIN_TOOL_OUTPUT_CHARS,
            AgentToolLimits.MAX_TOOL_OUTPUT_CHARS
        )
        return if (text.length > budget) {
            text.take(budget) + "\n\n…（正文已截断，共 ${text.length} 字符；可用 start_index 参数继续读取后续内容）"
        } else {
            text
        }
    }
}
