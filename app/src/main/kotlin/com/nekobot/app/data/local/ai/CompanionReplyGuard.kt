package com.nekobot.app.data.local.ai

/** 单会话正文发布闸门：停止回查后先验整条回复，正常流式仅暂存可能的内部标签前缀。 */
internal class CompanionReplyGuard(
    private val downstream: LocalAiStreamCallbacks? = null,
    private val hold: Boolean = false
) {
    private val pending = StringBuilder()
    private var blocked = false

    val callbacks = LocalAiStreamCallbacks(
        onStart = {
            pending.setLength(0)
            blocked = false
            downstream?.onStart?.invoke()
        },
        onContentChunk = { chunk ->
            if (!hold && !blocked) {
                pending.append(chunk)
                drain()
            }
        },
        onThinkingChunk = { if (!hold && !blocked) downstream?.onThinkingChunk?.invoke(it) },
        onStreamObserved = { downstream?.onStreamObserved?.invoke() }
    )

    private fun drain() {
        if (marker.containsMatchIn(pending)) {
            blocked = true
            pending.setLength(0)
            return
        }
        while (pending.isNotEmpty()) {
            val start = pending.indexOf("<")
            if (start < 0) {
                downstream?.onContentChunk?.invoke(pending.toString())
                pending.setLength(0)
                return
            }
            if (start > 0) {
                downstream?.onContentChunk?.invoke(pending.substring(0, start))
                pending.delete(0, start)
            }
            val prefix = pending.toString().replace('｜', '|').filterNot(Char::isWhitespace).lowercase()
            if (prefixes.any { it.startsWith(prefix) }) return
            downstream?.onContentChunk?.invoke("<")
            pending.deleteCharAt(0)
        }
    }

    /** 失败时不释放暂存内容；不解析或执行正文里的调用，也不自动发起另一次模型请求。 */
    fun finish(result: LocalAiResult) {
        check(!blocked) { FORMAT_ERROR }
        validate(result.content)
        validate(pending.toString())
        if (hold) {
            downstream?.onThinkingChunk?.invoke(result.thinkingContent)
            downstream?.onContentChunk?.invoke(result.content)
        } else if (pending.isNotEmpty()) {
            downstream?.onContentChunk?.invoke(pending.toString())
            pending.setLength(0)
        }
    }

    companion object {
        const val FORMAT_ERROR = "回复格式异常，请重新生成。"
        private val marker = Regex("<\\s*/?\\s*[｜|]{1,2}\\s*DSML\\b", RegexOption.IGNORE_CASE)
        private val prefixes = listOf("<|dsml|", "<||dsml||", "</|dsml|", "</||dsml||")
        fun validate(content: String) {
            val tail = content.substringAfterLast('<', "").let { "<$it" }
                .replace('｜', '|').filterNot(Char::isWhitespace).lowercase()
            val unfinished = tail.contains("ds") && prefixes.any { it.startsWith(tail) }
            check(!marker.containsMatchIn(content) && !unfinished) { FORMAT_ERROR }
        }
    }
}
