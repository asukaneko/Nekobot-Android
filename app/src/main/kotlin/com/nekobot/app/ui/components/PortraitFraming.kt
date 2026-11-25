package com.nekobot.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import kotlin.math.roundToInt

/**
 * 立绘取景：以「铺满」为基准的缩放倍数 + 取景中心。
 *
 * - [zoom]：1 = 正好铺满显示区域，越大画面越近（最多 [MaxZoom] 倍）。
 * - [centerX]/[centerY]：取景中心在**整张图片**里的位置（0..1）。
 *
 * 只存比例，不存像素：同一个角色在首页 banner（方形）与模式卡（竖版）里
 * 会围绕同一个中心按各自比例取景，所以两处看到的画面始终是同一块。
 */
data class PortraitFraming(
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f
) {
    /** 夹到合法范围，避免外部数据把画面推出图片外。 */
    fun normalised(): PortraitFraming = PortraitFraming(
        zoom = zoom.coerceIn(MinZoom, MaxZoom),
        centerX = centerX.coerceIn(0f, 1f),
        centerY = centerY.coerceIn(0f, 1f)
    )

    fun serialise(): String {
        val safe = normalised()
        return "${safe.zoom}|${safe.centerX}|${safe.centerY}"
    }

    companion object {
        const val MinZoom = 1f
        const val MaxZoom = 4f
        val Default = PortraitFraming()

        /**
         * 解析取景参数。
         *
         * 支持当前格式 `zoom|centerX|centerY`，也兼容旧版首页方形取景
         * `角色ID|方形边长占图片宽度比例|centerX|centerY`：
         * 人像立绘里"方框边长占比"约等于 1/zoom，换算后首页画面与旧版一致。
         */
        fun parse(raw: String?): PortraitFraming? {
            val parts = raw?.split('|') ?: return null
            val values = when (parts.size) {
                3 -> parts
                4 -> {
                    val frame = parts[1].toFloatOrNull() ?: return null
                    val zoom = if (frame <= 0f) "1" else (1f / frame).toString()
                    listOf(zoom, parts[2], parts[3])
                }
                else -> return null
            }
            val zoom = values[0].toFloatOrNull() ?: return null
            val centerX = values[1].toFloatOrNull() ?: return null
            val centerY = values[2].toFloatOrNull() ?: return null
            return PortraitFraming(zoom, centerX, centerY).normalised()
        }
    }
}

/** 取景后真正要绘制的源图区域（像素）。 */
internal data class PortraitSourceRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/** 图片"铺满"显示区域时，可见部分占整图宽/高的比例。 */
internal fun coverFractionX(imageAspect: Float, boxAspect: Float): Float =
    if (imageAspect >= boxAspect) boxAspect / imageAspect else 1f

internal fun coverFractionY(imageAspect: Float, boxAspect: Float): Float =
    if (imageAspect >= boxAspect) 1f else imageAspect / boxAspect

/** 把取景中心夹在可见范围内：中心移动不能让画面露白。 */
internal fun clampPortraitCenter(center: Float, visibleFraction: Float): Float {
    val half = (visibleFraction / 2f).coerceIn(0f, 0.5f)
    return center.coerceIn(half, 1f - half)
}

/**
 * 按取景参数算出源图区域。
 *
 * 直接取源矩形再铺满显示区域，不依赖 ContentScale 的裁切规则，
 * 因此任何宽高比都不会露白边，也不会多裁或少裁。
 */
internal fun portraitSourceRect(
    imageWidth: Int,
    imageHeight: Int,
    boxWidth: Int,
    boxHeight: Int,
    framing: PortraitFraming
): PortraitSourceRect? {
    if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0 || boxHeight <= 0) return null
    val crop = framing.normalised()
    val boxAspect = boxWidth.toFloat() / boxHeight.toFloat()
    val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
    val visibleX = (coverFractionX(imageAspect, boxAspect) / crop.zoom).coerceIn(0.01f, 1f)
    val visibleY = (coverFractionY(imageAspect, boxAspect) / crop.zoom).coerceIn(0.01f, 1f)
    val width = (visibleX * imageWidth).roundToInt().coerceIn(1, imageWidth)
    val height = (visibleY * imageHeight).roundToInt().coerceIn(1, imageHeight)
    val centerX = clampPortraitCenter(crop.centerX, visibleX)
    val centerY = clampPortraitCenter(crop.centerY, visibleY)
    val left = (centerX * imageWidth - width / 2f).roundToInt().coerceIn(0, imageWidth - width)
    val top = (centerY * imageHeight - height / 2f).roundToInt().coerceIn(0, imageHeight - height)
    return PortraitSourceRect(left, top, width, height)
}

/**
 * 拖动手势换算成取景中心位移：显示面积越小（zoom 越大），同样一段手指位移
 * 在整图里对应的比例越小，画面才是"跟手"的。
 */
internal fun panPortraitFraming(
    framing: PortraitFraming,
    panX: Float,
    panY: Float,
    boxWidth: Float,
    boxHeight: Float,
    imageAspect: Float
): PortraitFraming {
    if (boxWidth <= 0f || boxHeight <= 0f || imageAspect <= 0f) return framing.normalised()
    val current = framing.normalised()
    val boxAspect = boxWidth / boxHeight
    val visibleX = coverFractionX(imageAspect, boxAspect) / current.zoom
    val visibleY = coverFractionY(imageAspect, boxAspect) / current.zoom
    return current.copy(
        centerX = clampPortraitCenter(current.centerX - panX * visibleX / boxWidth, visibleX),
        centerY = clampPortraitCenter(current.centerY - panY * visibleY / boxHeight, visibleY)
    )
}

/**
 * 读取某个角色的立绘取景。
 *
 * 首页 banner 与模式卡共用同一份数据（按角色 ID 存），改一处两处同步。
 */
@Composable
fun rememberPortraitFraming(characterId: String?): PortraitFraming {
    val prefs = ServiceContainer.prefs
    val stored by prefs.characterPortraitFramingsFlow.collectAsStateWithLifecycle()
    return remember(characterId, stored) {
        PortraitFraming.parse(prefs.portraitFramingValue(characterId.orEmpty()))
            ?: PortraitFraming.Default
    }
}

/**
 * 按取景参数显示立绘：任意宽高比都取同一块画面，铺满且不露白边。
 *
 * 位图还没解码好时先按 Crop 铺一张，拿到位图后立刻切成精确的源矩形。
 */
@Composable
fun FramedPortraitImage(
    url: String?,
    framing: PortraitFraming,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    /** 拿到图片后回调宽高比（宽/高），供取景预览换算拖拽距离。 */
    onAspect: ((Float) -> Unit)? = null
) {
    if (url.isNullOrBlank()) return
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val loaded = bitmap
    if (loaded == null) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onSuccess = { state -> bitmap = state.result.drawable.toPortraitBitmap() },
            modifier = modifier.onSizeChanged { boxSize = it }
        )
        return
    }
    val aspect = loaded.width.toFloat() / loaded.height.toFloat()
    LaunchedEffect(aspect) { onAspect?.invoke(aspect) }
    val src = portraitSourceRect(loaded.width, loaded.height, boxSize.width, boxSize.height, framing)
    if (src == null) {
        Image(
            painter = BitmapPainter(loaded),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.onSizeChanged { boxSize = it }
        )
        return
    }
    val painter = remember(loaded, src) {
        BitmapPainter(
            image = loaded,
            srcOffset = IntOffset(src.left, src.top),
            srcSize = IntSize(src.width, src.height)
        )
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.onSizeChanged { boxSize = it }
    )
}

/**
 * 立绘取景对话框：直接拖动/双指缩放图片本身，预览框与卡片同比例，
 * 确定后写回该角色的取景，首页 banner 同步生效。
 */
@Composable
fun PortraitFramingDialog(
    imageUrl: String?,
    characterName: String,
    /** 预览框宽高比：传卡片立绘区域的实际比例，预览即所见。 */
    previewAspect: Float,
    initial: PortraitFraming,
    onDismiss: () -> Unit,
    onConfirm: (PortraitFraming) -> Unit
) {
    val start = initial.normalised()
    var zoom by remember { mutableFloatStateOf(start.zoom) }
    var centerX by remember { mutableFloatStateOf(start.centerX) }
    var centerY by remember { mutableFloatStateOf(start.centerY) }
    var imageAspect by remember { mutableFloatStateOf(0f) }
    val current = PortraitFraming(zoom, centerX, centerY)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                cornerRadius = 20,
                containerColor = MaterialTheme.colorScheme.surface,
                borderWidth = 1
            ) {
                Text(
                    text = stringResource(R.string.home_portrait_framing_title),
                    style = nekoRowTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                // 预览框按卡片立绘区域的比例 contain，长图也不会把按钮顶出屏幕
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val aspect = previewAspect
                        .takeIf { it.isFinite() && it > 0f }
                        ?.coerceIn(0.55f, 1.6f) ?: 1f
                    val previewWidth = if (maxWidth / aspect <= maxHeight) {
                        maxWidth
                    } else {
                        maxHeight * aspect
                    }
                    val previewHeight = previewWidth / aspect
                    val previewShape = RoundedCornerShape(14.dp)
                    Box(
                        modifier = Modifier
                            .size(previewWidth, previewHeight)
                            .clip(previewShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val boxWidth = size.width.toFloat()
                                    val boxHeight = size.height.toFloat()
                                    if (boxWidth <= 0f || boxHeight <= 0f) return@detectTransformGestures
                                    val nextZoom = (zoom * gestureZoom)
                                        .coerceIn(PortraitFraming.MinZoom, PortraitFraming.MaxZoom)
                                    zoom = nextZoom
                                    if (imageAspect <= 0f) return@detectTransformGestures
                                    val moved = panPortraitFraming(
                                        framing = PortraitFraming(nextZoom, centerX, centerY),
                                        panX = pan.x,
                                        panY = pan.y,
                                        boxWidth = boxWidth,
                                        boxHeight = boxHeight,
                                        imageAspect = imageAspect
                                    )
                                    centerX = moved.centerX
                                    centerY = moved.centerY
                                }
                            }
                    ) {
                        if (imageUrl.isNullOrBlank()) {
                            NekoLetterAvatar(
                                text = characterName,
                                size = 96.dp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            FramedPortraitImage(
                                url = imageUrl,
                                framing = current,
                                modifier = Modifier.fillMaxSize(),
                                onAspect = { imageAspect = it }
                            )
                        }
                        // 细边框：提示这块就是卡片里会看到的区域
                        Box(
                            Modifier
                                .fillMaxSize()
                                .border(1.dp, MaterialTheme.colorScheme.outline, previewShape)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.home_portrait_framing_hint),
                    style = nekoCaptionStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        zoom = PortraitFraming.MinZoom
                        centerX = 0.5f
                        centerY = 0.5f
                    }) {
                        Text(
                            stringResource(R.string.shell_portrait_crop_reset),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(current.normalised()) }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            }
        }
    }
}

/** 把 Coil 的 drawable 转成 ImageBitmap（兼容不同 Coil 版本；失败返回 null）。 */
private fun android.graphics.drawable.Drawable?.toPortraitBitmap(): ImageBitmap? {
    if (this == null) return null
    val bitmap = runCatching {
        if (this is android.graphics.drawable.BitmapDrawable) this.bitmap else this.toBitmap()
    }.getOrNull() ?: return null
    return if (bitmap.width > 0 && bitmap.height > 0) bitmap.asImageBitmap() else null
}
