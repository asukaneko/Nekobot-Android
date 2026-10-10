package com.nekobot.app.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

private class PopupGlassSource(val backdrop: GlassBackdrop, val windowRoot: View) {
    var consumers by mutableIntStateOf(0)
}

private val LocalPopupGlassSource = staticCompositionLocalOf<PopupGlassSource?> { null }

/** 只在菜单或弹窗显示时录制页面，独立窗口中的玻璃不会采样自身。 */
@Composable
fun LiquidGlassMenuHost(content: @Composable () -> Unit) {
    val available = rememberLiquidGlassAvailable()
    val layer = rememberGlassBackdrop()
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val backdrop = remember(layer) { ScreenAlignedMenuBackdrop(layer, coordinates) }
    val windowRoot = LocalView.current.rootView
    val source = remember(backdrop, windowRoot) { PopupGlassSource(backdrop, windowRoot) }
    CompositionLocalProvider(LocalPopupGlassSource provides if (available) source else null) {
        Box(
            Modifier.fillMaxSize()
                .then(if (available && source.consumers > 0) Modifier.glassBackdropSource(layer) else Modifier)
                .onGloballyPositioned { coordinates.value = it }
        ) {
            content()
        }
    }
}

@Composable
internal fun rememberPopupGlassBackdrop(
    expanded: Boolean,
    sampleCurrentWindow: Boolean = false,
): GlassBackdrop? {
    val source = LocalPopupGlassSource.current
    val windowRoot = LocalView.current.rootView
    // 菜单位于浏览器等独立窗口时，采样该窗口；弹窗自身的玻璃背景仍采样下层页面。
    val useWindow = sampleCurrentWindow && source != null && source.windowRoot !== windowRoot
    val windowBackdrop = if (useWindow) rememberWindowGlassBackdrop(expanded) else null
    DisposableEffect(source, expanded, useWindow) {
        val capturePage = expanded && !useWindow
        if (capturePage) source?.let { it.consumers++ }
        onDispose { if (capturePage) source?.let { it.consumers-- } }
    }
    return if (!expanded) null else if (useWindow) windowBackdrop else source?.backdrop
}

/** 两个窗口使用屏幕原点对齐，避免菜单里的背景与宿主页面错位。 */
private class ScreenAlignedMenuBackdrop(
    private val source: LayerBackdrop,
    private val sourceCoordinates: State<LayoutCoordinates?>,
) : GlassBackdrop {
    override val isCoordinatesDependent = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val from = sourceCoordinates.value ?: return
        val to = coordinates ?: return
        if (!from.isAttached || !to.isAttached) return
        val sourceOrigin = from.positionOnScreen() - from.positionInWindow()
        val targetOrigin = to.positionOnScreen() - to.positionInWindow()
        val correction = sourceOrigin - targetOrigin
        if (!correction.x.isFinite() || !correction.y.isFinite()) return
        withTransform({ translate(correction.x, correction.y) }) {
            with(source) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}

internal fun Modifier.liquidGlassMenuSurface(
    backdrop: GlassBackdrop,
    shape: Shape,
    tint: Color,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        colorControls(saturation = 1.2f)
        blur(14.dp.toPx())
        lens(12.dp.toPx(), 18.dp.toPx())
    },
    highlight = { Highlight.Default.copy(alpha = 0.35f) },
    shadow = { Shadow(radius = 12.dp, alpha = 0.55f) },
    innerShadow = { InnerShadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.05f)) },
    onDrawSurface = { drawRect(tint) },
)
