package com.nekobot.app.ui.components

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow

/** 导航组件共享 Backdrop 的真实采样源，也支持页面与标签的组合采样。 */
typealias GlassBackdrop = Backdrop

/** API 31+ 支持模糊，API 33+ 支持折射；低内存设备使用静态玻璃样式。 */
fun isLiquidGlassAvailable(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return manager?.isLowRamDevice != true
}

@Composable
fun rememberLiquidGlassAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { isLiquidGlassAvailable(context) }
}

@Composable
fun rememberGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/** 页面由库录入图层，导航组件直接采样该图层，无需截图或读回位图。 */
fun Modifier.glassBackdropSource(backdrop: LayerBackdrop): Modifier = layerBackdrop(backdrop)

/** 统一使用 Backdrop 的模糊、折射和高光，供底栏与平板侧栏复用。 */
@Composable
fun GlassPane(
    backdrop: GlassBackdrop,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    blur: Dp = 8.dp,
    saturation: Float = 1.25f,
    refraction: Dp = 12.dp,
    dispersion: Float = 0f,
    tint: Color = Color.Transparent,
    rimColors: List<Color> = emptyList(),
    innerShadowAlpha: Float = 0.06f,
    liquidProgress: () -> Float = { 0f },
    scaleBoost: Float = 0f,
    tintFade: Float = 0.85f,
    rimRestAlpha: Float = 1f,
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(saturation = saturation)
                backdropBlur(blur.toPx())
                val strength = 1f + 0.8f * liquidProgress()
                lens(
                    refraction.toPx() * strength,
                    refraction.toPx() * 1.4f * strength,
                    chromaticAberration = dispersion > 0f,
                )
            },
            highlight = {
                if (rimColors.isEmpty()) null else Highlight.Default.copy(
                    alpha = ((rimColors.first().alpha * rimRestAlpha) +
                        0.35f * liquidProgress()).coerceIn(0f, 1f),
                )
            },
            shadow = null,
            innerShadow = {
                if (innerShadowAlpha <= 0f) null else InnerShadow(
                    radius = 6.dp,
                    color = Color.Black.copy(alpha = innerShadowAlpha),
                )
            },
            layerBlock = {
                val scale = 1f + scaleBoost * liquidProgress()
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = {
                drawRect(tint, alpha = (1f - tintFade * liquidProgress()).coerceIn(0f, 1f))
            },
        )
    )
}
