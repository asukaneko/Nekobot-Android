package com.nekobot.app.ui.screens.chat

import com.google.gson.GsonBuilder

/** 将工具参数和结果格式化为适合用户阅读的 JSON，保留原始符号而不是 HTML 转义。 */
internal fun formatJsonForDisplay(value: Any): String {
    return runCatching {
        GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
            .toJson(value)
    }.getOrElse { value.toString() }
}

/**
 * 将 token 数量格式化为易读的紧凑写法：
 * - 小于 1k：原样数字（如 512、999）
 * - 千位：一位小数并去掉多余的 0；k 值 ≥ 100 时直接用整数（如 1.2k、9.8k、10k、123k、999k）
 * - 百万位：一位小数并去掉多余的 0；值 ≥ 100 时直接用整数（如 1.5M、12M、350M）
 * - k 值 ≥ 999.95（四舍五入后为 1000k）时进位到 M（如 999950 → 1M）
 */
internal fun formatTokenCount(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    var value = tokens / 1000.0
    var suffix = "k"
    if (value >= 999.95) {
        value = tokens / 1_000_000.0
        suffix = "M"
    }
    val text = if (value >= 100) {
        value.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
            .trimEnd('0')
            .trimEnd('.')
    }
    return "$text$suffix"
}
