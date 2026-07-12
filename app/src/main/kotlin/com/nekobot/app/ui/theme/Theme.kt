package com.nekobot.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nekobot.app.ServiceContainer

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    secondary = Secondary,
    tertiary = Tertiary,
    background = BgDark,
    onBackground = OnSurface,
    surface = BgSurface,
    onSurface = OnSurface,
    surfaceVariant = BgSurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = ErrorRed,
)

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    secondary = SecondaryLight,
    tertiary = TertiaryLight,
    background = BgLight,
    onBackground = OnSurfaceLight,
    surface = BgSurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = BgSurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = ErrorRedLight,
)

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

    // 主题色覆盖：null 表示使用默认紫色
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

    val typography = buildTypography(prefs.fontFamily, prefs.fontScale)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            // 浅色模式下状态栏图标使用深色外观
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = typography,
        content = content
    )
}
