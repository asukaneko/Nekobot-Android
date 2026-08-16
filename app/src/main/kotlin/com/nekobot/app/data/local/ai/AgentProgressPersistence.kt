package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import java.util.IdentityHashMap

/** 聊天页恢复历史时允许解码的单条进度卡 JSON 上限。 */
internal const val MAX_AGENT_PROGRESS_HISTORY_JSON_CHARS = 256 * 1024

private const val MAX_PERSISTED_AGENT_STEPS = 64
private const val MAX_PERSISTED_REASONING_CHARS = 16_000
internal const val MAX_AGENT_PROGRESS_ARGUMENT_PREVIEW_CHARS = 1_500
internal const val MAX_AGENT_PROGRESS_RESULT_PREVIEW_CHARS = 3_000

/**
 * 恢复聊天历史时先按原始 JSON 大小挡住旧版本写入的巨型进度卡。
 * 超限卡只保留一个完成态占位，不影响用户和助手消息继续显示。
 */
internal fun decodeThinkingCardsForUi(
    messageId: String,
    raw: String?,
    gson: Gson = Gson()
): List<ThinkingCard>? {
    if (raw.isNullOrBlank()) return null
    if (raw.length > MAX_AGENT_PROGRESS_HISTORY_JSON_CHARS) {
        return listOf(oversizedThinkingCard(messageId))
    }
    return runCatching {
        val type = object : TypeToken<List<ThinkingCard>>() {}.type
        gson.fromJson<List<ThinkingCard>>(raw, type)
            ?.map(ThinkingCard::toPersistedProgressCard)
    }.getOrNull()
}

private fun oversizedThinkingCard(messageId: String): ThinkingCard = ThinkingCard(
    id = "oversized-history-$messageId",
    content = "思考过程已完成（历史详情过大，已安全折叠）",
    isComplete = true,
    isAgent = true,
    parentMessageId = messageId
)

/**
 * Agent 续聊所需的完整工具历史单独保存在 tool_call_history 中；thinking_cards 只负责 UI 展示。
 * 因此进度卡落库前必须裁掉无上限的工具返回，避免重新进入会话时一次性解码数 MB 数据。
 */
internal fun ThinkingCard.toPersistedProgressCard(): ThinkingCard {
    val selectedSteps = if (steps.size <= MAX_PERSISTED_AGENT_STEPS) {
        steps
    } else {
        val thinking = steps.firstOrNull { it.type.equals("thinking", ignoreCase = true) }
        buildList {
            if (thinking != null) add(thinking)
            addAll(steps.takeLast(MAX_PERSISTED_AGENT_STEPS - size))
        }
    }
    return copy(
        content = content.take(500),
        steps = selectedSteps.map(ThinkingStep::toPersistedProgressStep)
    )
}

private fun ThinkingStep.toPersistedProgressStep(): ThinkingStep = copy(
    name = name?.take(200),
    detail = detail?.take(500),
    arguments = arguments?.let { value ->
        mapOf("preview" to boundedAgentValuePreview(value, MAX_AGENT_PROGRESS_ARGUMENT_PREVIEW_CHARS))
    },
    fullResult = fullResult?.let { value ->
        boundedAgentValuePreview(value, MAX_AGENT_PROGRESS_RESULT_PREVIEW_CHARS)
    },
    thinkingContent = thinkingContent?.takeLast(MAX_PERSISTED_REASONING_CHARS)
)

/** 不创建完整 toString/JSON 副本地生成嵌套工具参数或结果预览。 */
internal fun boundedAgentValuePreview(value: Any?, maxChars: Int): String {
    if (maxChars <= 0) return ""
    val output = StringBuilder(minOf(maxChars, 512))
    val visited = IdentityHashMap<Any, Boolean>()

    fun appendText(text: String) {
        val remaining = maxChars - output.length
        if (remaining <= 0) return
        output.append(text, 0, minOf(text.length, remaining))
    }

    fun appendValue(current: Any?, depth: Int) {
        if (output.length >= maxChars) return
        if (depth > 8) {
            appendText("…")
            return
        }
        when (current) {
            null -> appendText("null")
            is String -> appendText(current)
            is Number, is Boolean, is Char -> appendText(current.toString())
            is Map<*, *> -> {
                if (visited.put(current, true) != null) {
                    appendText("<cycle>")
                    return
                }
                appendText("{")
                val iterator = current.entries.iterator()
                var index = 0
                while (iterator.hasNext() && output.length < maxChars) {
                    val entry = iterator.next()
                    if (index > 0) appendText(", ")
                    appendText(entry.key?.toString().orEmpty())
                    appendText("=")
                    appendValue(entry.value, depth + 1)
                    index++
                }
                appendText("}")
                visited.remove(current)
            }
            is Iterable<*> -> {
                if (visited.put(current, true) != null) {
                    appendText("<cycle>")
                    return
                }
                appendText("[")
                val iterator = current.iterator()
                var index = 0
                while (iterator.hasNext() && output.length < maxChars) {
                    val item = iterator.next()
                    if (index > 0) appendText(", ")
                    appendValue(item, depth + 1)
                    index++
                }
                appendText("]")
                visited.remove(current)
            }
            is Array<*> -> appendValue(current.asList(), depth)
            else -> appendText(current.toString())
        }
    }

    appendValue(value, 0)
    if (output.length >= maxChars) {
        val suffix = "…"
        output.replace((maxChars - suffix.length).coerceAtLeast(0), output.length, suffix)
    }
    return output.toString()
}

/** 工具以字符串或数值返回布尔标记时，也统一识别为输出截断。 */
internal fun isAgentToolOutputTruncated(result: Map<String, Any>): Boolean {
    return when (val value = result.entries.firstOrNull {
        it.key.equals("truncated", ignoreCase = true)
    }?.value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() == 1
        else -> false
    }
}
