package com.nekobot.app.ui.components

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/** 独立窗口包含 WebView 时采样已合成的窗口画面，不改变 AndroidView 的绘制顺序。 */
@Composable
internal fun rememberWindowGlassBackdrop(active: Boolean): GlassBackdrop? {
    val view = LocalView.current
    val window = remember(view) { view.dialogWindow() }
    val backdrop = remember(window) { WindowGlassBackdrop() }
    DisposableEffect(window, active) {
        val capture = if (window != null && active) WindowGlassCapture(window, backdrop) else null
        capture?.start()
        onDispose { capture?.stop() }
    }
    return if (window != null && active) backdrop else null
}

private fun View.dialogWindow(): Window? {
    var ancestor = parent
    while (ancestor != null) {
        if (ancestor is DialogWindowProvider) return ancestor.window
        ancestor = ancestor.parent
    }
    return null
}

private data class WindowGlassFrame(
    val image: ImageBitmap,
    val positionOnScreen: Offset,
    val windowSize: IntSize,
)

private class WindowGlassBackdrop : GlassBackdrop {
    override val isCoordinatesDependent = true
    var frame by mutableStateOf<WindowGlassFrame?>(null)

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val frame = frame ?: return
        if (coordinates == null || !coordinates.isAttached) return
        val offset = frame.positionOnScreen - coordinates.positionOnScreen()
        if (!offset.x.isFinite() || !offset.y.isFinite()) return
        withTransform({
            translate(offset.x, offset.y)
            scale(
                scaleX = frame.windowSize.width.toFloat() / frame.image.width,
                scaleY = frame.windowSize.height.toFloat() / frame.image.height,
                pivot = Offset.Zero,
            )
        }) {
            drawImage(frame.image)
        }
    }
}

/** 菜单显示期间刷新，关闭后停止；每次只允许一个复制请求，限制采样分辨率。 */
private class WindowGlassCapture(
    private val window: Window,
    private val backdrop: WindowGlassBackdrop,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var copying = false
    private var nextCaptureNanos = 0L
    private var lastError: Int? = null

    fun start() {
        running = true
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
        backdrop.frame = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (!copying && frameTimeNanos >= nextCaptureNanos) {
            nextCaptureNanos = frameTimeNanos + if (lastError == null) 50_000_000L else 500_000_000L
            captureFrame()
        }
        choreographer.postFrameCallback(this)
    }

    private fun captureFrame() {
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (!decor.isAttachedToWindow || width <= 0 || height <= 0) return
        val scale = minOf(1f, 1280f / maxOf(width, height))
        val bitmap = Bitmap.createBitmap(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val position = IntArray(2)
        decor.getLocationOnScreen(position)
        copying = true
        try {
            PixelCopy.request(window, bitmap, { result ->
                copying = false
                if (running && result == PixelCopy.SUCCESS) {
                    lastError = null
                    backdrop.frame = WindowGlassFrame(
                        image = bitmap.asImageBitmap(),
                        positionOnScreen = Offset(position[0].toFloat(), position[1].toFloat()),
                        windowSize = IntSize(width, height),
                    )
                } else {
                    bitmap.recycle()
                    if (running) reportError(result)
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            copying = false
            bitmap.recycle()
            reportError(PixelCopy.ERROR_SOURCE_INVALID)
        }
    }

    private fun reportError(result: Int) {
        if (lastError != result) Log.w("NekoGlass", "Window backdrop capture failed: $result")
        lastError = result
    }
}
