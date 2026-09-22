package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import java.util.IdentityHashMap

/**
 * 聊天页恢复历史时允许解码的单条进度卡 JSON 上限。
 *
 * 进度卡本身已有步骤数、思考正文总量、工具预览三重上限，正常长任务（200 轮以内）落库后
 * 只有几百 KB；这里只是兜底挡住旧版本或极端设置写出的巨型卡，避免一次解码数 MB 数据。
 * 因此阈值放得很宽——真正影响性能（解码数 MB）时才折叠。
 */
internal const val MAX_AGENT_PROGRESS_HISTORY_JSON_CHARS = 8 * 1024 * 1024

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
    val stepCap = AgentToolLimits.PROGRESS_PERSISTED_STEPS
    val selectedSteps = if (steps.size <= stepCap) {
        steps
    } else {
        // 保留任务开头那条思考（最初的思路）+ 最近的步骤；仅当它落在尾部窗口之外才单独补，
        // 否则会在窗口里重复出现同一步骤。
        val headThinking = steps.indexOfFirst { it.type.equals("thinking", ignoreCase = true) }
            .takeIf { it in 0 until steps.size - stepCap }
            ?.let { steps[it] }
        val tailStart = steps.size - (stepCap - if (headThinking != null) 1 else 0)
        buildList {
            if (headThinking != null) add(headThinking)
            addAll(steps.subList(tailStart, steps.size))
        }
    }
    // 思考正文按「从最新一轮往前」分配总预算：每轮思考各自成步后，
    // 逐条截断到 20k 会让进度卡 JSON 随轮数线性膨胀，超出历史解码上限时整卡会被折叠。
    var reasoningBudget = AgentToolLimits.PROGRESS_REASONING_TOTAL_CHARS
    val persistedSteps = arrayOfNulls<ThinkingStep>(selectedSteps.size)
    for (index in selectedSteps.indices.reversed()) {
        val step = selectedSteps[index]
        val allowance = minOf(AgentToolLimits.PROGRESS_REASONING_CHARS, reasoningBudget)
        val keptReasoning = step.thinkingContent?.takeIf(String::isNotEmpty)?.let { content ->
            when {
                allowance <= 0 -> null
                content.length <= allowance -> content.also { reasoningBudget -= content.length }
                else -> content.takeLast(allowance).also { reasoningBudget -= allowance }
            }
        }
        persistedSteps[index] = step.toPersistedProgressStep(keptReasoning)
    }
    return copy(
        content = content.take(AgentToolLimits.PROGRESS_PERSISTED_CONTENT_CHARS),
        steps = persistedSteps.filterNotNull()
    )
}

private fun ThinkingStep.toPersistedProgressStep(keptReasoning: String?): ThinkingStep = copy(
    name = name?.take(AgentToolLimits.PROGRESS_PERSISTED_NAME_CHARS),
    detail = detail?.take(AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS),
    arguments = arguments?.toPersistedArguments(),
    fullResult = fullResult?.let { value ->
        boundedAgentValuePreview(value, AgentToolLimits.progressPreviewChars())
    },
    thinkingContent = keptReasoning
)

/**
 * 参数落库：进度报告器写入的已经是 `{"preview": "<扁平化预览文本>"}` 形态，
 * 不能再套一层 preview，否则详情弹窗的参数列表只会剩一行 "preview = {...}"。
 * 只有真实参数字典（远程/历史形态）才需要转成预览文本。
 */
private fun Map<String, Any>.toPersistedArguments(): Map<String, Any> {
    val previewText = if (size == 1) get("preview") as? String else null
    if (previewText != null) {
        return mapOf("preview" to previewText.take(AgentToolLimits.progressPreviewChars()))
    }
    return mapOf("preview" to boundedAgentValuePreview(this, AgentToolLimits.progressPreviewChars()))
}

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
