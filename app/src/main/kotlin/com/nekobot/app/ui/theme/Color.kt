package com.nekobot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// ==================== 内容优先的极简配色 ====================
// 设计基线：暖白底 + 细线 + 单一陶土红强调色。所有"彩色"只用于区分信息类别，
// 不再出现玻璃渐变、粉紫霓虹或多重阴影——颜色越少，内容越清楚。
//
// 浅色：背景 #F7F7F4 / 表面 #FFFFFF / 正文 #222522 / 次要 #777C75 / 细线 #E7E9E3
// 深色：背景 #171A18 / 表面 #222622 / 正文 #F0F2EC / 次要 #A6ADA2 / 细线 #363D35

// ---- 浅色 ----
val NekoBgLight = Color(0xFFFAFAFA)
val NekoSurfaceLight = Color(0xFFFFFFFF)
val NekoSurfaceTintLight = Color(0xFFF2F2F2)
val NekoLineLight = Color(0xFFE5E5E5)
val NekoTextLight = Color(0xFF1D1E1F)
val NekoMutedLight = Color(0xFF6F7377)
val NekoAccentLight = Color(0xFFFF6B97)
val NekoAccentSoftLight = Color(0xFFFFE5EE)
val NekoAccentDeepLight = Color(0xFFD8386A)
val NekoGreenLight = Color(0xFF3F7A5E)
val NekoGreenSoftLight = Color(0xFFE9F0E9)
val NekoBlueSoftLight = Color(0xFFEEF2F6)
val NekoBlueLight = Color(0xFF5C7488)
val NekoClayLight = Color(0xFF7C6A60)

// ---- 深色 ----
val NekoBgDark = Color(0xFF141414)
val NekoSurfaceDark = Color(0xFF1D1D1D)
val NekoSurfaceTintDark = Color(0xFF272727)
val NekoLineDark = Color(0xFF303030)
val NekoTextDark = Color(0xFFF0F0F0)
val NekoMutedDark = Color(0xFFA0A3A5)
val NekoAccentDark = Color(0xFFFF8FB1)
val NekoAccentSoftDark = Color(0xFF3E1B2A)
val NekoAccentDeepDark = Color(0xFFFFC2D2)
val NekoGreenDark = Color(0xFF9FC4AC)
val NekoGreenSoftDark = Color(0xFF26312A)
val NekoBlueSoftDark = Color(0xFF232B31)
val NekoBlueDark = Color(0xFF9DB2BF)
val NekoClayDark = Color(0xFFB9A79D)

// ==================== 兼容旧命名的主题令牌 ====================
// 旧代码按这些名字取色；这里统一指向新配色，避免全量替换调用点。
val GlassWhite = Color(0xFFFFFFFF)
val GlassAlpha = Color(0x0FFFFFFF)

val BgDark = NekoBgDark
val BgSurface = NekoSurfaceDark
val BgSurfaceVariant = NekoSurfaceTintDark

val Primary = NekoAccentDark
val PrimaryContainer = NekoAccentSoftDark
val Secondary = NekoBlueDark
val Tertiary = NekoClayDark

val OnPrimary = Color(0xFF3B0F22)
val OnSurface = NekoTextDark
val OnSurfaceVariant = NekoMutedDark
val Outline = NekoLineDark

val ErrorRed = Color(0xFFE5645C)
val SuccessGreen = NekoGreenDark
val WarningAmber = Color(0xFFD2AC6B)

// 消息气泡
val BubbleUser = NekoAccentDark
val BubbleAssistant = NekoSurfaceDark

// ==================== 浅色主题配色 ====================
val BgLight = NekoBgLight
val BgSurfaceLight = NekoSurfaceLight
val BgSurfaceVariantLight = NekoSurfaceTintLight
val GlassAlphaLight = Color(0x1A000000)

val PrimaryLight = NekoAccentLight
val PrimaryContainerLight = NekoAccentSoftLight
val SecondaryLight = NekoBlueLight
val TertiaryLight = NekoClayLight

val OnPrimaryLight = Color(0xFFFFFFFF)
val OnSurfaceLight = NekoTextLight
val OnSurfaceVariantLight = NekoMutedLight
val OutlineLight = NekoLineLight

val ErrorRedLight = Color(0xFFC24D43)
val SuccessGreenLight = NekoGreenLight
val WarningAmberLight = Color(0xFF8A6A2F)

// 消息气泡（浅色）
val BubbleUserLight = NekoAccentLight
val BubbleAssistantLight = NekoSurfaceLight

/** 线条色（分隔线 / 卡片描边）：浅色 #E7E9E3，深色 #363D35。 */
@Composable
fun nekoLine(): Color = if (isSystemInDarkTheme()) NekoLineDark else NekoLineLight

/** 更淡的一级文字（时间戳、脚注）。 */
@Composable
fun nekoFaint(): Color = if (isSystemInDarkTheme()) NekoMutedDark.copy(alpha = 0.82f) else NekoMutedLight.copy(alpha = 0.72f)

/** 强调色底（标签、选中态）。 */
@Composable
fun nekoAccentSoft(): Color = if (isSystemInDarkTheme()) NekoAccentSoftDark else NekoAccentSoftLight

/** 绿（正常/成功）：仅用于状态点与提示，不作大块填充。 */
@Composable
fun nekoGreen(): Color = if (isSystemInDarkTheme()) NekoGreenDark else NekoGreenLight

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
 * 获取"默认强调色"主题色：与主题色覆盖为 null 时一致，
 * 不受当前已选主题色影响，供样式设置中的默认选项展示用。
 */
@Composable
fun defaultPrimaryColor(): Color =
    if (isSystemInDarkTheme()) Primary else PrimaryLight

// ==================== 深浅色自适应语义色 ====================
// 用于图标着色、日志等级、剧情事件等"点缀色"场景，
// 避免硬编码 hex 导致浅色主题下对比度错误。

/** 蓝灰（深色 Secondary / 浅色 SecondaryLight） */
@Composable
fun accentSecondary(): Color =
    if (isSystemInDarkTheme()) Secondary else SecondaryLight

/** 陶土棕（深色 Tertiary / 浅色 TertiaryLight） */
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

// ==================== 链接色 ====================
// 聊天气泡内的链接必须与气泡底色、正文色都能区分：
// 主题主色（粉色）与用户气泡底色完全相同，直接用它做链接色会“看不见”。

/** 浅色主题的链接蓝：比 SecondaryLight 更深，保证白底气泡上的对比度。 */
val LinkBlueLight = Color(0xFF1B6D8F)

/** AI 气泡内的链接色：深色主题用亮蓝，浅色主题用深蓝。 */
@Composable
fun accentLink(): Color =
    if (isSystemInDarkTheme()) Secondary else LinkBlueLight

/**
 * 用户气泡内的链接色。
 *
 * 气泡偏亮（如默认粉色）时取同色相的深色版本 —— 与气泡底色、白色正文同时区分；
 * 气泡本身很深时改用白色，靠胶囊底色与下划线区分。
 */
fun linkColorOnBubble(bubble: Color): Color {
    val argb = bubble.toArgb()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        hsv
    )
    if (hsv[2] <= 0.6f) return Color.White
    hsv[2] = 0.38f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
