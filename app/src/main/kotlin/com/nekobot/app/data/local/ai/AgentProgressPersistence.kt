package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import java.util.IdentityHashMap
import java.util.Locale

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
    // 中间回复正文同理：长任务每轮都可能留一段，共用一份独立总预算。
    var reasoningBudget = AgentToolLimits.PROGRESS_REASONING_TOTAL_CHARS
    var intermediateBudget = AgentToolLimits.PROGRESS_INTERMEDIATE_TOTAL_CHARS
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
        val keptText = step.text?.takeIf(String::isNotEmpty)?.let { content ->
            when {
                intermediateBudget <= 0 -> null
                content.length <= intermediateBudget -> content.also { intermediateBudget -= content.length }
                else -> content.take(intermediateBudget).also { intermediateBudget = 0 }
            }
        }
        persistedSteps[index] = step.toPersistedProgressStep(keptReasoning, keptText)
    }
    return copy(
        content = content.take(AgentToolLimits.PROGRESS_PERSISTED_CONTENT_CHARS),
        steps = persistedSteps.filterNotNull()
    )
}

private fun ThinkingStep.toPersistedProgressStep(
    keptReasoning: String?,
    keptText: String?
): ThinkingStep = copy(
    name = name?.take(AgentToolLimits.PROGRESS_PERSISTED_NAME_CHARS),
    detail = detail?.take(AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS),
    arguments = arguments?.toPersistedArguments(),
    fullResult = fullResult?.let { value ->
        val sanitized = (value as? Map<String, Any>)?.let(::sanitizeAgentToolResultForDisplay) ?: value
        boundedAgentValuePreview(sanitized, AgentToolLimits.progressPreviewChars())
    },
    thinkingContent = keptReasoning,
    text = keptText
)

/**
 * 工具结果进入进度卡片前的展示净化。
 *
 * `_image_urls` 里是可能长达数 MB 的 base64 data URI（read_image / android_screenshot /
 * android_step 在支持视觉的对话模型下注入），原样预览会把卡片刷满 base64。
 * 模型侧仍通过工具消息看到图片，这里只把每张图替换为「类型 + 大小」的简短说明。
 */
internal fun sanitizeAgentToolResultForDisplay(result: Map<String, Any>): Map<String, Any> {
    val images = (result["_image_urls"] as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    if (images.isEmpty()) return result
    val description = buildString {
        if (images.size > 1) append("×").append(images.size).append(" ")
        append(images.joinToString("; ") { describeAgentImageForDisplay(it) })
    }
    val placeholder = ServiceContainer.localizedContext
        ?.getString(R.string.agent_progress_image_injected, description)
        ?: String.format(Locale.getDefault(), "图片已注入模型上下文（%s，数据已省略）", description)
    return result + ("_image_urls" to placeholder)
}

/** data URI 只保留 MIME 与估算大小；普通 URL 保留地址（截断）。 */
private fun describeAgentImageForDisplay(url: String): String {
    if (!url.startsWith("data:")) return url.take(120)
    val mime = url.substringAfter("data:", "").substringBefore(';').ifBlank { "image" }
    val bytes = url.length * 3L / 4L
    val size = when {
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
    return "$mime $size"
}

/**
 * 参数落库：结构化参数字典按参数名逐项限长保留（见 [boundedAgentArguments]），
 * 详情弹窗才能显示「参数名 → 参数值」；旧版本写入的 `{"preview": "<扁平化预览文本>"}`
 * 原样保留（不能再套一层 preview，否则详情弹窗的参数列表只会剩一行 "preview = {...}"）。
 */
private fun Map<String, Any>.toPersistedArguments(): Map<String, Any> {
    if (isEmpty()) return emptyMap()
    val previewText = if (size == 1) get("preview") as? String else null
    if (previewText != null) {
        return mapOf("preview" to previewText.take(AgentToolLimits.progressPreviewChars()))
    }
    return boundedAgentArguments(this)
}

/** 落库的参数字典最多保留的条目数：避免超长参数表把进度卡 JSON 撑大。 */
private const val MAX_PERSISTED_ARGUMENT_ENTRIES = 24

/**
 * 结构化参数限长：保留参数名，值逐个截断，总长度不超过 [maxTotalChars]。
 *
 * 与 [boundedAgentValuePreview] 的扁平文本不同，这里保留键值结构，让进度卡详情弹窗
 * 能直接列出参数名与参数值（避免整段参数被 "preview" 包裹后无法阅读）。
 */
internal fun boundedAgentArguments(
    arguments: Map<String, Any>,
    maxEntries: Int = MAX_PERSISTED_ARGUMENT_ENTRIES,
    maxTotalChars: Int = AgentToolLimits.progressPreviewChars()
): Map<String, Any> {
    if (arguments.isEmpty() || maxEntries <= 0 || maxTotalChars <= 0) return emptyMap()
    val result = LinkedHashMap<String, Any>(minOf(arguments.size, maxEntries))
    var remaining = maxTotalChars
    for ((key, value) in arguments) {
        if (result.size >= maxEntries) break
        val name = key.take(64)
        if (name.isBlank()) continue
        if (remaining <= 0) {
            result[name] = "…"
            continue
        }
        val text = boundedAgentValuePreview(value, remaining)
        result[name] = text
        remaining -= text.length
    }
    return result
}

/**
 * 工具调用参数规范化：参数字典直接使用，JSON 字符串解析成参数表，解析失败返回空表。
 *
 * 部分协议（Gemini 原生 / Responses 等）下发的 arguments 是 JSON 字符串，
 * 进度卡片与工具执行共用同一份规范化逻辑，避免「能执行但卡片显示不出参数」。
 */
internal fun normalizeAgentToolArguments(raw: Any?): Map<String, Any> {
    if (raw is Map<*, *>) {
        return raw.entries
            .mapNotNull { (key, value) ->
                val name = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                name to (value as Any)
            }
            .toMap()
    }
    if (raw is String && raw.isNotBlank()) {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(raw, Map::class.java) as? Map<String, Any>
        }.getOrNull().orEmpty()
    }
    return emptyMap()
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
