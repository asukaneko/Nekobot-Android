package com.nekobot.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val NekobotTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/** 自定义字体缓存，避免反复解析文件 */
private var cachedCustomFontPath: String? = null
private var cachedCustomFamily: FontFamily? = null

/** 解析自定义字体路径为 FontFamily，路径未变时复用缓存 */
private fun resolveCustomFont(path: String?): FontFamily? {
    if (path.isNullOrBlank()) return null
    if (path == cachedCustomFontPath && cachedCustomFamily != null) return cachedCustomFamily
    return runCatching {
        // 兼容 file:// 前缀，转为 File 后传给 Font()
        val rawPath = if (path.startsWith("file:")) {
            android.net.Uri.parse(path).path ?: path.removePrefix("file://")
        } else path
        val file = java.io.File(rawPath)
        if (!file.exists()) return null
        val family = FontFamily(Font(file))
        cachedCustomFontPath = path
        cachedCustomFamily = family
        family
    }.getOrNull()
}

/**
 * 根据 fontFamily 选择 FontFamily，根据 fontScale 缩放所有 fontSize 和 lineHeight。
 * 基于现有 NekobotTypography 的字号生成动态 Typography。
 */
fun buildTypography(fontFamily: String, fontScale: Float): Typography {
    val resolvedFamily = when (fontFamily) {
        PrefsManager.FONT_FAMILY_SERIF -> FontFamily.Serif
        PrefsManager.FONT_FAMILY_MONOSPACE -> FontFamily.Monospace
        PrefsManager.FONT_FAMILY_ROUNDED -> FontFamily.SansSerif
        PrefsManager.FONT_FAMILY_CUSTOM -> {
            // 从 Prefs 读取自定义字体路径
            val path = ServiceContainer.prefs.customFontPath
            resolveCustomFont(path) ?: FontFamily.Default
        }
        else -> FontFamily.Default
    }

    fun scale(sp: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.TextUnit =
        (sp.value * fontScale).sp

    fun TextStyle.scaled(): TextStyle = copy(
        fontFamily = resolvedFamily,
        fontSize = scale(fontSize),
        lineHeight = scale(lineHeight)
    )

    return NekobotTypography.copy(
        headlineLarge = NekobotTypography.headlineLarge.scaled(),
        headlineMedium = NekobotTypography.headlineMedium.scaled(),
        titleLarge = NekobotTypography.titleLarge.scaled(),
        titleMedium = NekobotTypography.titleMedium.scaled(),
        bodyLarge = NekobotTypography.bodyLarge.scaled(),
        bodyMedium = NekobotTypography.bodyMedium.scaled(),
        bodySmall = NekobotTypography.bodySmall.scaled(),
        labelLarge = NekobotTypography.labelLarge.scaled(),
        labelMedium = NekobotTypography.labelMedium.scaled(),
    )
}
