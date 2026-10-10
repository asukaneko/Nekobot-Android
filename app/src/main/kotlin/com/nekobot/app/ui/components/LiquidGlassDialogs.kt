package com.nekobot.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/** 弹窗只更换背景材质，内容和按钮沿用原来的 Material 组件。 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val backdrop = rememberPopupGlassBackdrop(true)
    val tint = glassDialogTint(containerColor)
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.liquidGlassDialogSurface(backdrop, shape, tint),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = if (backdrop == null) containerColor else Color.Transparent,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/** 自定义弹窗保留 Surface 的尺寸、内容颜色和圆角。 */
@Composable
fun GlassDialogSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberPopupGlassBackdrop(true)
    Surface(
        modifier = modifier.liquidGlassDialogSurface(backdrop, shape, glassDialogTint(color)),
        shape = shape,
        color = if (backdrop == null) color else Color.Transparent,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        content = content,
    )
}

@Composable
internal fun glassDialogTint(color: Color): Color =
    color.copy(alpha = color.alpha * if (isSystemInDarkTheme()) 0.78f else 0.68f)

internal fun Modifier.liquidGlassDialogSurface(
    backdrop: GlassBackdrop?,
    shape: Shape,
    tint: Color,
): Modifier {
    if (backdrop == null) return this
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.2f)
            blur(16.dp.toPx())
            lens(12.dp.toPx(), 18.dp.toPx())
        },
        highlight = { Highlight.Default.copy(alpha = 0.4f) },
        shadow = { Shadow(radius = 16.dp, alpha = 0.55f) },
        innerShadow = { InnerShadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.05f)) },
        onDrawSurface = { drawRect(tint) },
    )
}
