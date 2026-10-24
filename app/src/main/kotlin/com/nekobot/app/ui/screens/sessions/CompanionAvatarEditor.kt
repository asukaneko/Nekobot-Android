package com.nekobot.app.ui.screens.sessions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Selection/crop are memory-only drafts. Only Save invokes the avatar-only persistence path. */
@Composable
internal fun CompanionAvatarEditor(sessionId: String, characterId: String, onSaved: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(sessionId, characterId) { ServiceContainer.localRepository }
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            if (bitmap == null) onDismiss()
        } else {
            bitmap = null
            loading = true
            failed = false
            scope.launch {
                try {
                    bitmap = withContext(Dispatchers.IO) { decodeAvatarImage(context, uri) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    loading = false
                }
            }
        }
    }
    LaunchedEffect(Unit) { picker.launch(arrayOf("image/*")) }
    CompanionAvatarCropDialog(bitmap, loading || saving, failed,
        onChoose = { if (!loading && !saving) picker.launch(arrayOf("image/*")) },
        onDismiss = { if (!saving) onDismiss() },
        onSave = { crop ->
            val source = bitmap
            if (source != null && !loading && !saving) {
                saving = true
                failed = false
                scope.launch {
                    try {
                        check(ServiceContainer.prefs.isLocalMode && ServiceContainer.localRepository === repository)
                        val png = withContext(Dispatchers.Default) { encodeAvatarCrop(source, crop) }
                        check(ServiceContainer.localRepository === repository)
                        val path = repository.saveCompanionAvatar(sessionId, characterId, png)
                        onSaved(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            }
        })
}

@Composable
internal fun CompanionAvatarCropDialog(bitmap: Bitmap?, busy: Boolean, failed: Boolean,
    onChoose: () -> Unit, onDismiss: () -> Unit, onSave: (AvatarCrop) -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var horizontal by remember(bitmap) { mutableFloatStateOf(.5f) }
    var vertical by remember(bitmap) { mutableFloatStateOf(.5f) }
    var viewport by remember { mutableIntStateOf(1) }
    NekoDialog(onDismiss = onDismiss, title = stringResource(R.string.companion_avatar_title),
        message = stringResource(R.string.companion_avatar_hint),
        confirmText = stringResource(R.string.companion_avatar_save), confirmEnabled = bitmap != null && !busy,
        onConfirm = { bitmap?.let { onSave(avatarCrop(it.width, it.height, zoom, horizontal, vertical)) } },
        onCancel = onDismiss, contentScrollable = true) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            val cropLabel = stringResource(R.string.avatar_crop_area)
            val position = stringResource(R.string.avatar_crop_position, (horizontal * 100).toInt(), (vertical * 100).toInt())
            val left = stringResource(R.string.avatar_crop_left)
            val right = stringResource(R.string.avatar_crop_right)
            val up = stringResource(R.string.avatar_crop_up)
            val down = stringResource(R.string.avatar_crop_down)
            fun move(dx: Float, dy: Float): Boolean {
                if (busy) return false
                val crop = avatarCrop(bitmap.width, bitmap.height, zoom, horizontal, vertical)
                val x = if (bitmap.width > crop.side) (horizontal + dx).coerceIn(0f, 1f) else horizontal
                val y = if (bitmap.height > crop.side) (vertical + dy).coerceIn(0f, 1f) else vertical
                val changed = x != horizontal || y != vertical
                horizontal = x; vertical = y
                return changed
            }
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .testTag("companion-avatar-crop")
                .semantics {
                    contentDescription = cropLabel
                    stateDescription = position
                    if (busy) disabled()
                    customActions = if (busy) emptyList() else listOf(
                        CustomAccessibilityAction(left) { move(-.1f, 0f) },
                        CustomAccessibilityAction(right) { move(.1f, 0f) },
                        CustomAccessibilityAction(up) { move(0f, -.1f) },
                        CustomAccessibilityAction(down) { move(0f, .1f) })
                }
                .onSizeChanged { viewport = it.width.coerceAtLeast(1) }
                .pointerInput(bitmap, zoom, busy) {
                    detectDragGestures { change, drag ->
                        if (!busy) {
                            change.consume()
                            val crop = avatarCrop(bitmap.width, bitmap.height, zoom, horizontal, vertical)
                            val pixelScale = crop.side.toFloat() / viewport
                            if (bitmap.width > crop.side) horizontal = (horizontal - drag.x * pixelScale / (bitmap.width - crop.side)).coerceIn(0f, 1f)
                            if (bitmap.height > crop.side) vertical = (vertical - drag.y * pixelScale / (bitmap.height - crop.side)).coerceIn(0f, 1f)
                        }
                    }
                }) {
                val crop = avatarCrop(bitmap.width, bitmap.height, zoom, horizontal, vertical)
                drawImage(image, srcOffset = IntOffset(crop.left, crop.top), srcSize = IntSize(crop.side, crop.side),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            Text(stringResource(R.string.companion_avatar_zoom), style = MaterialTheme.typography.labelMedium)
            Slider(zoom, { zoom = it }, enabled = !busy, valueRange = 1f..4f,
                modifier = Modifier.testTag("companion-avatar-zoom"))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.companion_avatar_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onChoose, enabled = !busy) { Text(stringResource(R.string.companion_avatar_choose)) }
    }
}

internal fun decodeAvatarImage(context: Context, uri: Uri): Bitmap {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 32 * 1024 * 1024) { "Image too large" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Cannot open image")
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Invalid image" }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
        if (it !== decoded) decoded.recycle()
    }
}

internal fun encodeAvatarCrop(source: Bitmap, crop: AvatarCrop): ByteArray {
    val cut = Bitmap.createBitmap(source, crop.left, crop.top, crop.side, crop.side)
    val scaled = Bitmap.createScaledBitmap(cut, 512, 512, true)
    return try {
        ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    } finally {
        if (scaled !== source) scaled.recycle()
        if (cut !== source && cut !== scaled) cut.recycle()
    }
}
