package com.nekobot.app.data.local.ai

/**
 * 标记提取器的事件。
 *
 * - [Content] 是应显示在回复气泡里的正文
 * - [Thinking] 是应显示在思考卡片里的原生思考
 */
sealed interface ExtractEvent {
    data class Content(val text: String) : ExtractEvent
    data class Thinking(val text: String) : ExtractEvent
}

/**
 * 从回复正文里按「开头标记 / 结尾标记」提取思考的流式提取器。
 *
 * 纯 Kotlin、无 Android 依赖，可直接单测。语义：
 * 1. 未配置标记时原样产出 Content，不剪裁；
 * 2. 回复开头（允许前导空白）必须匹配开头标记，匹配后进入 INSIDE，累积到结尾标记；
 * 3. 见到结尾标记才把中间内容作为 Thinking 产出，之后正文直通；
 * 4. 找不到完整标记对时不猜不剪，已缓存内容全部按 Content 产出。
 */
class ThinkingMarkerExtractor(config: ThinkingSourceConfig) {

    private enum class Mode { DETECTING, INSIDE, PASSTHROUGH }

    private val startMarker: String = config.markerStart
    private val endMarker: String = config.markerEnd
    private var mode: Mode = if (config.markerEnabled) Mode.DETECTING else Mode.PASSTHROUGH

    /** 判定标记前缓存的内容（含前导空白，确认不是标记时原样放行）。 */
    private val detectBuffer = StringBuilder()

    /** INSIDE 阶段累积的思考；必须等到结尾标记才能决定它是思考还是正文。 */
    private val insideBuffer = StringBuilder()

    /** 是否真的找到并剪掉了一对完整标记。 */
    var didExtract: Boolean = false
        private set

    fun feed(chunk: String): List<ExtractEvent> {
        if (chunk.isEmpty()) return emptyList()
        return when (mode) {
            Mode.PASSTHROUGH -> listOf(ExtractEvent.Content(chunk))
            Mode.DETECTING -> detect(chunk)
            Mode.INSIDE -> inside(chunk)
        }
    }

    fun finish(): List<ExtractEvent> {
        val events = when (mode) {
            Mode.PASSTHROUGH -> emptyList()
            Mode.DETECTING -> {
                val text = detectBuffer.toString()
                detectBuffer.clear()
                if (text.isEmpty()) emptyList() else listOf(ExtractEvent.Content(text))
            }
            Mode.INSIDE -> {
                // 只有开头没有结尾：把开头标记和已累积内容整条当普通正文，不丢字。
                val text = startMarker + insideBuffer.toString()
                insideBuffer.clear()
                if (text.isEmpty()) emptyList() else listOf(ExtractEvent.Content(text))
            }
        }
        mode = Mode.PASSTHROUGH
        return events
    }

    /**
     * 思考字段已取到思考时放弃标记提取（字段优先）。
     *
     * 把尚未决定归属的缓存原样还原成正文并直通；已确认提取过标记时保持现状。
     */
    fun abandon(): List<ExtractEvent> {
        if (didExtract || mode == Mode.PASSTHROUGH) return emptyList()
        val events = when (mode) {
            Mode.DETECTING -> {
                val text = detectBuffer.toString()
                detectBuffer.clear()
                if (text.isEmpty()) emptyList() else listOf(ExtractEvent.Content(text))
            }
            Mode.INSIDE -> {
                val text = startMarker + insideBuffer.toString()
                insideBuffer.clear()
                if (text.isEmpty()) emptyList() else listOf(ExtractEvent.Content(text))
            }
            Mode.PASSTHROUGH -> emptyList()
        }
        mode = Mode.PASSTHROUGH
        return events
    }

    private fun detect(chunk: String): List<ExtractEvent> {
        detectBuffer.append(chunk)
        val text = detectBuffer.toString()
        val firstNonWhitespace = text.indexOfFirst { !it.isWhitespace() }
        val trimmed = if (firstNonWhitespace < 0) "" else text.substring(firstNonWhitespace)
        if (trimmed.startsWith(startMarker)) {
            val rest = trimmed.substring(startMarker.length)
            detectBuffer.clear()
            mode = Mode.INSIDE
            insideBuffer.append(rest)
            // 同一分片里可能直接带结尾标记。
            return inside("")
        }
        if (trimmed.isEmpty() || startMarker.startsWith(trimmed)) {
            // 仍可能跨分片拼出开头标记，暂存等待。
            return emptyList()
        }
        detectBuffer.clear()
        mode = Mode.PASSTHROUGH
        return listOf(ExtractEvent.Content(text))
    }

    private fun inside(chunk: String): List<ExtractEvent> {
        if (chunk.isNotEmpty()) insideBuffer.append(chunk)
        val text = insideBuffer.toString()
        val endIndex = text.indexOf(endMarker)
        if (endIndex < 0) return emptyList()
        val thinking = text.substring(0, endIndex)
        val content = text.substring(endIndex + endMarker.length)
        insideBuffer.clear()
        didExtract = true
        mode = Mode.PASSTHROUGH
        return buildList {
            if (thinking.isNotEmpty()) add(ExtractEvent.Thinking(thinking))
            if (content.isNotEmpty()) add(ExtractEvent.Content(content))
        }
    }
}
