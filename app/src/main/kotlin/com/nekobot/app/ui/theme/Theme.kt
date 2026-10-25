package com.nekobot.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.nekobot.app.ServiceContainer

/**
 * 深色配色。
 *
 * 只保留一个强调色（陶土粉）+ 一个状态绿，其余全部是灰阶：
 * 深色下强调色提亮、降低饱和，避免在近黑背景上发光刺眼。
 */
internal val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = NekoAccentDeepDark,
    secondary = Secondary,
    onSecondary = Color(0xFF15202A),
    secondaryContainer = NekoBlueSoftDark,
    onSecondaryContainer = NekoBlueDark,
    tertiary = Tertiary,
    onTertiary = Color(0xFF2A1D15),
    tertiaryContainer = NekoAccentSoftDark,
    onTertiaryContainer = NekoClayDark,
    background = BgDark,
    onBackground = OnSurface,
    surface = BgSurface,
    onSurface = OnSurface,
    surfaceVariant = BgSurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = BgDark,
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = BgSurface,
    surfaceContainerHigh = BgSurfaceVariant,
    surfaceContainerHighest = Color(0xFF333333),
    outline = Outline,
    outlineVariant = Color(0xFF272727),
    inverseSurface = OnSurface,
    inverseOnSurface = BgDark,
    inversePrimary = PrimaryLight,
    error = ErrorRed,
    onError = Color(0xFF2A1512),
    errorContainer = Color(0xFF4A2521),
    onErrorContainer = Color(0xFFF3B7AD),
)

/**
 * 浅色配色。
 *
 * 暖白底（#F7F7F4）+ 纯白表面 + 细线（#E7E9E3）：层级只靠 1dp 描边与留白表达，
 * 不用阴影堆叠。
 */
internal val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = NekoAccentDeepLight,
    secondary = SecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = NekoBlueSoftLight,
    onSecondaryContainer = NekoBlueLight,
    tertiary = TertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = NekoGreenSoftLight,
    onTertiaryContainer = NekoClayLight,
    background = BgLight,
    onBackground = OnSurfaceLight,
    surface = BgSurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = BgSurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFF0F0F0),
    outline = OutlineLight,
    outlineVariant = Color(0xFFEDEDED),
    inverseSurface = OnSurfaceLight,
    inverseOnSurface = Color(0xFFF7F7F4),
    inversePrimary = Primary,
    error = ErrorRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFAE7E5),
    onErrorContainer = ErrorRedLight,
)

/**
 * 新壳层圆角：卡片 18dp、输入 14dp、段落控件 12dp、抽屉 24dp。
 * 内部页面沿用 MaterialTheme.shapes 时会自动收敛到这套数值。
 */
private val NekoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * 应用内字号缩放系数（已叠加系统字号）。
 *
 * 壳层组件用固定 sp 数值精确还原设计稿的层级，但必须跟着用户的字号设置一起放大，
 * 因此统一乘上这个系数，而不是散落读取 prefs。
 */
val LocalNekoTypeScale = compositionLocalOf { 1f }

@Composable
fun NekobotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val prefs = ServiceContainer.prefs
    val baseScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 主题色覆盖：null 表示使用默认陶土红
    val themedScheme = prefs.themeColorOverride?.let { hex ->
        parseHexColor(hex)?.let { override ->
            val container = if (darkTheme) derivePrimaryContainer(override) else derivePrimaryContainerLight(override)
            baseScheme.copy(
                primary = override,
                primaryContainer = container
            )
        }
    } ?: baseScheme

    // 字体颜色覆盖：null 表示跟随主题
    val finalColorScheme = prefs.fontColorOverride?.let { hex ->
        parseHexColor(hex)?.let { overrideColor ->
            themedScheme.copy(
                onSurface = overrideColor,
                onSurfaceVariant = overrideColor,
                onBackground = overrideColor
            )
        }
    } ?: themedScheme

    // 读取系统字号缩放系数（LocalDensity.current.fontScale）
    val systemFontScale = LocalDensity.current.fontScale
    val followSystemFont = prefs.followSystemFontScale

    val typography = buildTypography(
        fontFamily = prefs.fontFamily,
        fontScale = prefs.fontScale,
        followSystemFont = followSystemFont,
        systemFontScale = systemFontScale
    )
    // 与 buildTypography 内部一致的最终缩放系数，供壳层固定字号使用
    val typeScale = (if (followSystemFont) prefs.fontScale * systemFontScale else prefs.fontScale)
        .coerceIn(0.7f, 2f)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            // 浅色模式下状态栏 / 导航栏图标使用深色外观
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalNekoTypeScale provides typeScale) {
        MaterialTheme(
            colorScheme = finalColorScheme,
            typography = typography,
            shapes = NekoShapes,
            content = content
        )
    }
}
