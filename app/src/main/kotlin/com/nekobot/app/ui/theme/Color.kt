package com.nekobot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// ==================== 深色玻璃拟态主题配色 ====================
// 深色蓝灰主题：背景 #1F2732 / 表面 #242D38 / 变体 #2B3440
val BgDark = Color(0xFF1F2732)
val BgSurface = Color(0xFF242D38)
val BgSurfaceVariant = Color(0xFF2B3440)
val GlassWhite = Color(0xFFFFFFFF)
val GlassAlpha = Color(0x14FFFFFF)

val Primary = Color(0xFFFF8FB1)
val PrimaryContainer = Color(0xFFFF6B97)
val Secondary = Color(0xFF6EC1E4)
val Tertiary = Color(0xFF8B6CFF)

val OnPrimary = Color(0xFFFFFFFF)
val OnSurface = Color(0xFFE8E8F0)
val OnSurfaceVariant = Color(0xFFA8A8BE)
val Outline = Color(0xFF3A3A4E)

val ErrorRed = Color(0xFFFF6B6B)
val SuccessGreen = Color(0xFF6BCF7F)
val WarningAmber = Color(0xFFFFC56B)

// 消息气泡
val BubbleUser = Color(0xFFFF8FB1)
val BubbleAssistant = Color(0xFF242436)

// ==================== 浅色主题配色 ====================
val BgLight = Color(0xFFF6F6FB)
val BgSurfaceLight = Color(0xFFFFFFFF)
val BgSurfaceVariantLight = Color(0xFFECECF3)
val GlassAlphaLight = Color(0x33000000)

val PrimaryLight = Color(0xFFFF6B97)
val PrimaryContainerLight = Color(0xFFFFE5EE)
val SecondaryLight = Color(0xFF3E9EC4)
val TertiaryLight = Color(0xFF7A5CFF)

val OnPrimaryLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1C1C28)
val OnSurfaceVariantLight = Color(0xFF5C5C70)
val OutlineLight = Color(0xFFD2D2DE)

val ErrorRedLight = Color(0xFFE5484D)
val SuccessGreenLight = Color(0xFF2E9E5A)
val WarningAmberLight = Color(0xFFC77700)

// 消息气泡（浅色）
val BubbleUserLight = Color(0xFFFF6B97)
val BubbleAssistantLight = Color(0xFFEDEDF6)

/**
 * 根据主色派生 primaryContainer（深色主题用，亮度降低到 ~38%）。
 */
fun derivePrimaryContainer(primary: Color): Color {
    val argb = primary.toArgb()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        hsv
    )
    hsv[1] = (hsv[1] * 0.85f).coerceAtMost(1f)  // 略降饱和度
    hsv[2] = (hsv[2] * 0.38f).coerceAtLeast(0.12f)  // 大幅降亮度
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * 根据主色派生浅色主题的 primaryContainer（亮度提高到 ~92%）。
 */
fun derivePrimaryContainerLight(primary: Color): Color {
    val argb = primary.toArgb()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        hsv
    )
    hsv[1] = (hsv[1] * 0.4f).coerceAtMost(0.5f)  // 大幅降饱和度
    hsv[2] = 0.96f  // 提亮
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 解析 hex 颜色字符串，失败返回 null。 */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/**
 * 获取"默认粉色"主题色：与主题色覆盖为 null 时一致，
 * 不受当前已选主题色影响，供样式设置中的"粉色"选项展示用。
 */
@Composable
fun defaultPrimaryColor(): Color =
    if (isSystemInDarkTheme()) Primary else PrimaryLight

// ==================== 深浅色自适应语义色 ====================
// 用于图标着色、日志等级、剧情事件等"点缀色"场景，
// 避免硬编码 hex 导致浅色主题下对比度错误。

/** 蓝（深色 Secondary / 浅色 SecondaryLight） */
@Composable
fun accentSecondary(): Color =
    if (isSystemInDarkTheme()) Secondary else SecondaryLight

/** 紫（深色 Tertiary / 浅色 TertiaryLight） */
@Composable
fun accentTertiary(): Color =
    if (isSystemInDarkTheme()) Tertiary else TertiaryLight

/** 琥珀（深色 WarningAmber / 浅色 WarningAmberLight） */
@Composable
fun accentWarning(): Color =
    if (isSystemInDarkTheme()) WarningAmber else WarningAmberLight

/** 绿（深色 SuccessGreen / 浅色 SuccessGreenLight） */
@Composable
fun accentSuccess(): Color =
    if (isSystemInDarkTheme()) SuccessGreen else SuccessGreenLight
