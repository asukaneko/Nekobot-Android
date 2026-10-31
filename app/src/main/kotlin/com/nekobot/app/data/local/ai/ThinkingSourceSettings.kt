package com.nekobot.app.data.local.ai

/**
 * 「思考内容来源」全局配置。
 *
 * 不同模型/服务商把原生思考放在不同字段里，有的干脆不返回思考字段，而是在回复正文里
 * 用一对标记包住思考。这里允许玩家自定义字段名候选和兜底标记，而不是写死。
 *
 * @param fields 思考字段名候选，按顺序尝试
 * @param markerStart 正文里思考的开头标记；与 [markerEnd] 同时非空才启用
 * @param markerEnd 正文里思考的结尾标记
 */
data class ThinkingSourceConfig(
    val fields: List<String> = DEFAULT_FIELDS,
    val markerStart: String = "",
    val markerEnd: String = ""
) {
    /** 两个标记都填了才启用正文标记提取；留空表示不启用。 */
    val markerEnabled: Boolean get() = markerStart.isNotBlank() && markerEnd.isNotBlank()

    companion object {
        val DEFAULT_FIELDS = listOf(
            "reasoning_content",
            "thinking_content",
            "reasoning",
            "thinking"
        )
        val DEFAULT = ThinkingSourceConfig()
    }
}

/**
 * 供协议解析层读取当前生效配置的安装点。
 *
 * 刻意不引用 ServiceContainer / Android Context：JVM 单测里没有初始化应用时，
 * [current] 必须稳定返回 [ThinkingSourceConfig.DEFAULT]，不影响既有协议测试。
 */
internal object ThinkingSourceSettings {
    @Volatile
    private var resolver: (() -> ThinkingSourceConfig)? = null

    fun install(resolver: (() -> ThinkingSourceConfig)?) {
        this.resolver = resolver
    }

    fun current(): ThinkingSourceConfig = resolver?.invoke() ?: ThinkingSourceConfig.DEFAULT
}

/**
 * 解析用户填写的字段候选：英文逗号分隔，trim、去空、去重；
 * 全部为空时回退到 [ThinkingSourceConfig.DEFAULT_FIELDS]。
 */
internal fun parseThinkingSourceFields(raw: String): List<String> {
    val parsed = raw.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    return parsed.ifEmpty { ThinkingSourceConfig.DEFAULT_FIELDS }
}

/**
 * 非流式 / 终态整段提取：仅在「字段没取到思考」且配置了标记时启用。
 *
 * @return 清洗后的正文与思考；没发现完整标记时原样返回，不猜不剪。
 */
internal fun extractThinkingFromMarkedContent(
    config: ThinkingSourceConfig,
    content: String,
    fieldThinking: String
): Pair<String, String> {
    if (!config.markerEnabled || fieldThinking.isNotBlank() || content.isEmpty()) {
        return content to fieldThinking
    }
    val extractor = ThinkingMarkerExtractor(config)
    val events = extractor.feed(content) + extractor.finish()
    if (!extractor.didExtract) return content to fieldThinking
    val cleanedContent = events.filterIsInstance<ExtractEvent.Content>()
        .joinToString("") { it.text }
    val markedThinking = events.filterIsInstance<ExtractEvent.Thinking>()
        .joinToString("") { it.text }
    return cleanedContent to markedThinking
}

/** 已确认本回复用标记提取过思考时，只把正文里的标记段剪掉（思考结果另行保留）。 */
internal fun stripThinkingMarkers(
    config: ThinkingSourceConfig,
    content: String
): String {
    if (!config.markerEnabled || content.isEmpty()) return content
    val extractor = ThinkingMarkerExtractor(config)
    val events = extractor.feed(content) + extractor.finish()
    if (!extractor.didExtract) return content
    return events.filterIsInstance<ExtractEvent.Content>().joinToString("") { it.text }
}
