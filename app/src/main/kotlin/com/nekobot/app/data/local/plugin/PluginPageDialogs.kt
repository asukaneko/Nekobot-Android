package com.nekobot.app.data.local.plugin

import com.google.gson.JsonObject

/**
 * 插件页面发起的原生弹窗请求。
 *
 * 两类来源：页面脚本的 `window.alert/confirm/prompt`（由 [PluginPageHost] 的
 * WebChromeClient 转换）与 `host.ui.alert/confirm/prompt/select`（经 API 分派器进入）。
 * UI 层渲染后通过 `PluginPageHost.respondDialog` 回填结果。
 */
internal data class PluginPageDialog(
    val id: Long = 0,
    val kind: Kind,
    /** 标题；宿主默认填插件名，插件可用 `title` 覆盖。 */
    val title: String = "",
    val message: String = "",
    /** prompt 输入框初始文本。 */
    val defaultValue: String = "",
    /** select 候选列表。 */
    val options: List<Option> = emptyList(),
    /** select 默认选中下标；-1 表示未选中。 */
    val selectedIndex: Int = -1
) {
    enum class Kind { ALERT, CONFIRM, PROMPT, SELECT }

    data class Option(val label: String, val value: String)
}

/** 弹窗结果：`confirmed=false` 表示取消/关闭；文本与下标按弹窗类型取值。 */
internal data class PluginPageDialogResult(
    val confirmed: Boolean,
    val text: String = "",
    val selectedIndex: Int = -1
) {
    companion object {
        val CANCELLED = PluginPageDialogResult(confirmed = false)
    }
}

/** 弹窗能力：由页面宿主实现；命令运行时（无界面）不注入该能力。 */
internal interface PluginPageDialogHost {
    suspend fun showDialog(request: PluginPageDialog): PluginPageDialogResult
}

/** `host.ui.select` 参数解析与边界裁剪（纯逻辑，便于单测）。 */
internal object PluginDialogPayloads {
    const val MAX_MESSAGE_CHARS = 4_000
    const val MAX_TITLE_CHARS = 120
    const val MAX_DEFAULT_CHARS = 4_000
    const val MAX_OPTIONS = 32
    const val MAX_OPTION_CHARS = 200

    /** 解析 options：字符串数组，或 `{label, value}` 对象数组；空数组与超限直接拒绝。 */
    fun parseOptions(payload: JsonObject): List<PluginPageDialog.Option> {
        val raw = payload.get("options")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw PluginApiException("ui.select 需要 options 数组", "invalid_argument")
        if (raw.size() == 0) throw PluginApiException("options 不能为空", "invalid_argument")
        if (raw.size() > MAX_OPTIONS) {
            throw PluginApiException("options 最多 $MAX_OPTIONS 项", "invalid_argument")
        }
        return raw.map { element ->
            when {
                element == null || element.isJsonNull ->
                    throw PluginApiException("options 每一项不能为空", "invalid_argument")
                element.isJsonPrimitive -> {
                    val text = element.asString.trim().take(MAX_OPTION_CHARS)
                    if (text.isBlank()) {
                        throw PluginApiException("options 每一项不能为空字符串", "invalid_argument")
                    }
                    PluginPageDialog.Option(label = text, value = text)
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val value = obj.string("value").trim().take(MAX_OPTION_CHARS)
                    val label = obj.string("label").trim().take(MAX_OPTION_CHARS).ifBlank { value }
                    if (label.isBlank()) {
                        throw PluginApiException("options 每一项需要 label 或 value", "invalid_argument")
                    }
                    PluginPageDialog.Option(label = label, value = value.ifBlank { label })
                }
                else -> throw PluginApiException("options 每一项必须是字符串或对象", "invalid_argument")
            }
        }
    }

    /** 解析 selected：数字按下标、字符串按 value/label 匹配；越界或未命中回退 -1。 */
    fun resolveSelectedIndex(
        payload: JsonObject,
        options: List<PluginPageDialog.Option>
    ): Int {
        val element = payload.get("selected") ?: payload.get("selectedIndex") ?: return -1
        if (!element.isJsonPrimitive) return -1
        val primitive = element.asJsonPrimitive
        if (primitive.isNumber) {
            val index = runCatching { primitive.asInt }.getOrDefault(-1)
            return index.takeIf { it in options.indices } ?: -1
        }
        val needle = runCatching { primitive.asString }.getOrNull() ?: return -1
        val trimmed = needle.trim()
        if (trimmed.isEmpty()) return -1
        return options.indexOfFirst { it.value == trimmed || it.label == trimmed }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
}
