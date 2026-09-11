package com.nekobot.app.ui.screens.chat

import com.google.gson.GsonBuilder
import kotlin.math.roundToInt

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
 * 生成速度（tok/s）：输出 token ÷ 生成耗时，用于「N tok」旁展示本条回复的生成速度。
 *
 * 与 token 数使用同一行、同一字号，因此这里连单位一起返回（如 `34.5 tok/s`）。
 * 以下情况返回 null（不展示）：
 * - 缺少输出 token 或耗时（服务端消息、导入的历史消息、估算失败的回复）
 * - 耗时样本过短（< [MIN_TOKEN_SPEED_SAMPLE_MS] 毫秒）：换算出来的速度没有参考价值
 */
internal fun formatTokenSpeed(outputTokens: Int?, durationMs: Double?): String? {
    val tokens = outputTokens ?: return null
    val duration = durationMs ?: return null
    if (tokens <= 0 || duration < MIN_TOKEN_SPEED_SAMPLE_MS) return null
    val speed = tokens * 1000.0 / duration
    val text = if (speed >= 100) {
        speed.roundToInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", speed)
    }
    return "$text tok/s"
}

/** tok/s 的最短采样时长：低于该耗时时四舍五入误差会明显放大速度值。 */
private const val MIN_TOKEN_SPEED_SAMPLE_MS = 100.0

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
