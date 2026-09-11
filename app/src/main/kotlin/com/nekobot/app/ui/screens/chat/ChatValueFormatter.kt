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
    val speed = tokenSpeedPerSecond(outputTokens, durationMs) ?: return null
    val text = if (speed >= 100) {
        speed.roundToInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", speed)
    }
    return "$text tok/s"
}

/**
 * 生成速度的原始值（tok/s）：判断条件与 [formatTokenSpeed] 完全一致，
 * 没有可展示的速度时返回 null。分级取色与文本展示共用同一个数据来源，不会出现「有文字没颜色」的错位。
 */
internal fun tokenSpeedPerSecond(outputTokens: Int?, durationMs: Double?): Double? {
    val tokens = outputTokens ?: return null
    val duration = durationMs ?: return null
    if (tokens <= 0 || duration < MIN_TOKEN_SPEED_SAMPLE_MS) return null
    return tokens * 1000.0 / duration
}

/** 生成速度分级：气泡下方的 tok/s 按它取色（快=绿、慢=红、中等保持次要文字色）。 */
internal enum class TokenSpeedLevel {
    /** 快：达到 [FAST_TOKENS_PER_SECOND] 以上 */
    FAST,

    /** 中等：介于快慢阈值之间，不额外着色 */
    NORMAL,

    /** 慢：低于 [SLOW_TOKENS_PER_SECOND] */
    SLOW
}

/**
 * 生成速度分级，供 UI 按快慢取色；没有速度数据时返回 null（与不展示文本保持一致）。
 *
 * 阈值取端上模型与云端模型的常见区间分界：端上低于 8 tok/s 体感明显偏慢，
 * 云端达到 20 tok/s 以上体感流畅，因此中间一段不着色，避免颜色频繁在红绿之间跳。
 */
internal fun tokenSpeedLevel(outputTokens: Int?, durationMs: Double?): TokenSpeedLevel? {
    val speed = tokenSpeedPerSecond(outputTokens, durationMs) ?: return null
    return when {
        speed >= FAST_TOKENS_PER_SECOND -> TokenSpeedLevel.FAST
        speed < SLOW_TOKENS_PER_SECOND -> TokenSpeedLevel.SLOW
        else -> TokenSpeedLevel.NORMAL
    }
}

/** tok/s 的最短采样时长：低于该耗时时四舍五入误差会明显放大速度值。 */
private const val MIN_TOKEN_SPEED_SAMPLE_MS = 100.0

/** 达到该速度（tok/s）视为「快」 */
private const val FAST_TOKENS_PER_SECOND = 20.0

/** 低于该速度（tok/s）视为「慢」 */
private const val SLOW_TOKENS_PER_SECOND = 8.0

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
