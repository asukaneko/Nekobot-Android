package com.nekobot.app.ui.screens.chat

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * 工具调用参数的一行展示数据：参数名 + 已格式化的参数值。
 *
 * [name] 为空表示这条预览没有参数名（模型直接给了非 JSON 的裸文本，如整段命令）。
 */
internal data class ToolArgumentRow(val name: String, val value: String)

/** 同行展示的参数值长度上限：超过（或多行文本）时改为「参数名 + 换行正文」的堆叠排版。 */
private const val INLINE_VALUE_MAX_CHARS = 60

/** 参数预览统一收在 [PREVIEW_KEY] 这一个键下，见 LocalAgentProgressReporter / AgentProgressPersistence。 */
private const val PREVIEW_KEY = "preview"

/** 短值同行展示，长值/多行值换行展示。 */
internal fun ToolArgumentRow.fitsInline(): Boolean =
    name.isNotBlank() && value.length <= INLINE_VALUE_MAX_CHARS && !value.contains('\n')

/**
 * 把进度卡步骤里的工具参数整理成人类可读的参数列表，替代原先只能看一段 JSON 的展示。
 *
 * 进度卡的 `arguments` 有三种来源，这里统一收敛成同一份列表：
 * 1. 本地 Agent / 子代理：`{"preview": "<扁平化预览文本>"}`（体积受 AgentToolLimits 限制）；
 * 2. 远程服务端：真实参数字典，如 `{"command": "ls -la"}`；
 * 3. 旧版本落库的历史进度卡：与 1 相同，但预览文本可能是模型原始 JSON 字符串。
 *
 * 整理不出任何条目时返回空列表，由调用方回退原始 JSON 展示。
 */
internal fun toolArgumentRows(arguments: Map<String, Any>?): List<ToolArgumentRow> {
    if (arguments.isNullOrEmpty()) return emptyList()
    val preview = arguments.entries
        .singleOrNull()
        ?.takeIf { it.key == PREVIEW_KEY }
        ?.value as? String
    if (preview != null) return previewTextArgumentRows(preview)
    return arguments.entries.map { (name, value) -> ToolArgumentRow(name, formatArgumentValue(value)) }
}

/**
 * 解析预览文本：先按 JSON 解析（模型原始参数、远程参数都能直接命中），
 * 失败再按扁平化的 `key=value` 预览格式解析，最后回退整段文本。
 */
internal fun previewTextArgumentRows(text: String): List<ToolArgumentRow> {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return emptyList()
    if (looksLikeJsonObject(trimmed)) {
        jsonArgumentRows(trimmed)?.let { return it }
    }
    if (trimmed.startsWith("{")) {
        parseFlattenedArgumentPreview(trimmed).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return listOf(ToolArgumentRow("", trimmed))
}

/**
 * 是否像严格的 JSON 对象（`{"name": ...}`）。
 *
 * Gson 的 JsonReader 即使关掉宽松模式也保留「无引号名称 / `=` 作分隔符」等旧行为，
 * 会把 `{path=/a.txt}` 这种扁平化预览解析成错误结构，因此这里先用结构判断把两种格式分开。
 */
private fun looksLikeJsonObject(text: String): Boolean {
    if (!text.startsWith("{")) return false
    var index = 1
    while (index < text.length && text[index].isWhitespace()) index++
    return index < text.length && text[index] == '"'
}

/** JSON 对象预览：逐个字段转成一行；不是严格 JSON 对象（数组/标量/扁平化预览）时返回 null 交给下一种解析。 */
private fun jsonArgumentRows(text: String): List<ToolArgumentRow>? {
    val obj = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return null
    if (obj.size() == 0) return null
    return obj.entrySet().map { (name, value) -> ToolArgumentRow(name, formatJsonArgumentValue(value)) }
}

private fun formatJsonArgumentValue(value: JsonElement): String = when {
    value.isJsonNull -> "null"
    value is JsonPrimitive -> value.asString
    else -> value.toString()
}

/**
 * 解析 [com.nekobot.app.data.local.ai.boundedAgentValuePreview] 产出的扁平化预览文本
 * （形如 `{path=/a.txt, limit=10}`）。
 *
 * 该格式里字符串值没有引号，无法做到完全精确；这里按「顶层 `, 名称=` 才算分隔符」的
 * 规则容错切分：值里出现的逗号只要后面不是新的 `名称=`，就保留在值内。
 */
internal fun parseFlattenedArgumentPreview(text: String): List<ToolArgumentRow> {
    val trimmed = text.trim()
    val body = if (trimmed.length >= 2 && trimmed.startsWith("{") && trimmed.endsWith("}")) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
    val rows = mutableListOf<ToolArgumentRow>()
    var index = 0
    while (index < body.length) {
        while (index < body.length && (body[index] == ',' || body[index] == ' ')) index++
        if (index >= body.length) break
        val nameStart = index
        var equalsAt = -1
        var scan = index
        while (scan < body.length) {
            val ch = body[scan]
            if (ch == '=') {
                equalsAt = scan
                break
            }
            if (ch == ',') break
            scan++
        }
        if (equalsAt < 0) {
            // 没有 `=`：剩余部分按整段裸值展示（例如只给了一个命令字符串）
            rows.add(ToolArgumentRow("", body.substring(nameStart).trim()))
            break
        }
        val valueStart = equalsAt + 1
        val valueEnd = findFlattenedValueEnd(body, valueStart)
        rows.add(
            ToolArgumentRow(
                name = body.substring(nameStart, equalsAt).trim(),
                value = body.substring(valueStart, valueEnd).trim()
            )
        )
        index = valueEnd
    }
    return rows.filter { it.name.isNotEmpty() || it.value.isNotEmpty() }
}

/** 从 [start] 起找当前值的结束位置：嵌套括号按配对跳到闭合处，标量停在下一个顶层 `, 名称=` 之前。 */
private fun findFlattenedValueEnd(body: String, start: Int): Int {
    if (start >= body.length) return body.length
    if (body[start] == '{' || body[start] == '[') {
        var depth = 0
        var index = start
        while (index < body.length) {
            when (body[index]) {
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return body.length
    }
    var index = start
    while (index < body.length) {
        if (body[index] == ',' && looksLikeNextFlattenedEntry(body, index + 1)) return index
        index++
    }
    return body.length
}

/** 逗号之后是否紧跟新的 `名称=`，用于区分「值里的逗号」与「参数之间的逗号」。 */
private fun looksLikeNextFlattenedEntry(body: String, from: Int): Boolean {
    var index = from
    while (index < body.length && body[index] == ' ') index++
    val nameStart = index
    while (index < body.length) {
        val ch = body[index]
        if (ch == '=') return index > nameStart
        if (!(ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.')) return false
        index++
    }
    return false
}

/** 真实参数字典里的值：字符串原样展示，嵌套结构压成一行 JSON。 */
private fun formatArgumentValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Number, is Boolean, is Char -> value.toString()
    is Map<*, *>, is Iterable<*>, is Array<*> ->
        runCatching { Gson().toJson(value) }.getOrElse { value.toString() }
    else -> value.toString()
}
