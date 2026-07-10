package com.nekobot.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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

/**
 * 根据 fontFamily 选择 FontFamily，根据 fontScale 缩放所有 fontSize 和 lineHeight。
 * 基于现有 NekobotTypography 的字号生成动态 Typography。
 */
fun buildTypography(fontFamily: String, fontScale: Float): Typography {
    val resolvedFamily = when (fontFamily) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "rounded" -> FontFamily.SansSerif
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
