package com.nekobot.app.ui.screens.chat

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalWorkspaceStorage
import com.nekobot.app.ui.components.GlassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 多媒体内容段类型 */
enum class SegmentType { TEXT, IMAGE, VIDEO, AUDIO, TXT, HTML, FILE }

/** 内容段：文本或多媒体 URL。HTML 内容（整段为 HTML 时）存于 [text]。 */
data class ContentSegment(
    val type: SegmentType,
    val text: String = "",      // TEXT 类型的文本内容；HTML 整段内容也存这里
    val url: String = "",       // 多媒体 URL
    val caption: String = "",    // URL 前后的说明文字（可选）
    val fileName: String = ""   // FILE 类型的文件名
)

/** 图片扩展名 */
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
/** 视频扩展名 */
private val VIDEO_EXTS = setOf("mp4", "webm", "mov", "avi", "mkv", "3gp")
/** 音频扩展名 */
private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
/** txt 扩展名 */
private val TXT_EXTS = setOf("txt")
/** html 扩展名 */
private val HTML_EXTS = setOf("html", "htm")
/** pdf 扩展名 */
private val PDF_EXTS = setOf("pdf")

/** URL 正则 */
private val URL_REGEX = Regex("""https?://[^\s<>"'\]]+""")

/** HTML 标签检测正则（检测内容是否包含 HTML 标签）*/
private val HTML_TAG_REGEX = Regex("""<(div|span|table|p|br|h[1-6]|ul|ol|li|a|img|b|i|strong|em)\b[^>]*>""", RegexOption.IGNORE_CASE)

/** 文件引用正则：匹配 [File: filename] 或 [文件: filename] */
private val FILE_REF_REGEX = Regex("""\[(?:File|文件):\s*([^\]]+)\]""")

/** 根据 URL 扩展名判断多媒体类型 */
private fun classifyUrl(url: String): SegmentType {
    // 去掉 query string 和 fragment，提取扩展名
    val noQuery = url.substringBefore('?').substringBefore('#')
    val ext = noQuery.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTS -> SegmentType.IMAGE
        in VIDEO_EXTS -> SegmentType.VIDEO
        in AUDIO_EXTS -> SegmentType.AUDIO
        in TXT_EXTS -> SegmentType.TXT
        in HTML_EXTS -> SegmentType.HTML
        else -> SegmentType.TEXT
    }
}

/**
 * 将消息 content 解析为内容段列表。
 * 检测 content 中的 URL，按扩展名分类，剩余文本作为 TEXT 段。
 * 如果 content 本身包含 HTML 标签且没有多媒体 URL，将整个内容作为 HTML 段。
 */
fun parseContentSegments(content: String): List<ContentSegment> {
    if (content.isBlank()) return listOf(ContentSegment(type = SegmentType.TEXT, text = content))

    // 优先检测 [File: filename] 或 [文件: filename] 引用
    val fileMatches = FILE_REF_REGEX.findAll(content).toList()
    if (fileMatches.isNotEmpty()) {
        val result = mutableListOf<ContentSegment>()
        var lastIndex = 0
        for (m in fileMatches) {
            // 文件引用前的文本
            if (m.range.first > lastIndex) {
                val text = content.substring(lastIndex, m.range.first)
                if (text.isNotBlank()) {
                    // 递归解析文本中的 URL 等其他多媒体
                    result.addAll(parseContentSegments(text))
                }
            }
            val fileName = m.groupValues[1].trim()
            result.add(ContentSegment(type = SegmentType.FILE, fileName = fileName))
            lastIndex = m.range.last + 1
        }
        // 末尾文本
        if (lastIndex < content.length) {
            val text = content.substring(lastIndex)
            if (text.isNotBlank()) {
                result.addAll(parseContentSegments(text))
            }
        }
        return result
    }

    val hasHtmlTag = HTML_TAG_REGEX.containsMatchIn(content)

    // 收集所有 URL 并分类
    val urlMatches = URL_REGEX.findAll(content).toList()
    val hasMediaUrl = urlMatches.any { classifyUrl(it.value).let { t -> t == SegmentType.IMAGE || t == SegmentType.VIDEO || t == SegmentType.AUDIO } }

    // 如果包含 HTML 标签且没有图片/视频/音频 URL，整个内容作为 HTML 段
    if (hasHtmlTag && !hasMediaUrl) {
        return listOf(ContentSegment(type = SegmentType.HTML, text = content))
    }

    // 否则按 URL 拆分为交替的文本段和 URL 段
    val result = mutableListOf<ContentSegment>()
    var lastIndex = 0
    for (m in urlMatches) {
        // URL 前的文本
        if (m.range.first > lastIndex) {
            val text = content.substring(lastIndex, m.range.first)
            if (text.isNotBlank()) {
                result.add(ContentSegment(type = SegmentType.TEXT, text = text))
            }
        }
        val url = m.value
        val type = classifyUrl(url)
        when (type) {
            SegmentType.TEXT -> result.add(ContentSegment(type = SegmentType.TEXT, text = url))
            SegmentType.HTML -> result.add(ContentSegment(type = SegmentType.HTML, url = url))
            else -> result.add(ContentSegment(type = type, url = url))
        }
        lastIndex = m.range.last + 1
    }
    // 末尾文本
    if (lastIndex < content.length) {
        val text = content.substring(lastIndex)
        if (text.isNotBlank()) {
            result.add(ContentSegment(type = SegmentType.TEXT, text = text))
        }
    }
    return result
}

/** 图片渲染器：用 Coil 异步加载，宽度填满、高度自适应，点击全屏查看。 */
@Composable
fun ImageRenderer(url: String, modifier: Modifier = Modifier) {
    ImageRendererModel(model = url, modifier = modifier)
}

@Composable
private fun ImageRendererModel(model: Any, modifier: Modifier = Modifier) {
    var fullscreen by remember { mutableStateOf(false) }
    val imageDesc = stringResource(R.string.chat_media_image)
    Box(modifier = modifier) {
        AsyncImage(
            model = model,
            contentDescription = imageDesc,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { fullscreen = true }
        )
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { fullscreen = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = imageDesc,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** 视频渲染器：用原生 VideoView 播放，附带播放/暂停控制按钮。 */
@Composable
fun VideoRenderer(url: String, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    val pauseDesc = stringResource(R.string.chat_media_pause)
    val playDesc = stringResource(R.string.chat_media_play)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse(url))
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        mp.start()
                        isPlaying = true
                    }
                    setOnCompletionListener { isPlaying = false }
                    videoView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        // 播放/暂停控制按钮（居中半透明）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable {
                    videoView?.let { vv ->
                        try {
                            if (isPlaying) {
                                vv.pause()
                                isPlaying = false
                            } else {
                                vv.start()
                                isPlaying = true
                            }
                        } catch (_: Exception) {
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) pauseDesc else playDesc,
                tint = Color.White
            )
        }
    }
}

/** 紧凑语音气泡：圆形播放键、可点按跳转的波形进度、时长与重新生成入口。 */
@Composable
fun AudioRenderer(
    url: String,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember(url) { mutableStateOf(false) }
    var currentPosition by remember(url) { mutableStateOf(0) }
    var duration by remember(url) { mutableStateOf(0) }
    var prepared by remember(url) { mutableStateOf(false) }
    var loadFailed by remember(url) { mutableStateOf(false) }
    val pauseDesc = stringResource(R.string.chat_media_pause)
    val playDesc = stringResource(R.string.chat_media_play)
    val authToken = ServiceContainer.prefs.token
    val context = LocalContext.current

    val mediaPlayer = remember(url) { MediaPlayer() }

    LaunchedEffect(mediaPlayer, url, authToken) {
        try {
            val headers = authToken
                ?.takeIf { it.isNotBlank() && url.startsWith("http", ignoreCase = true) }
                ?.let { mapOf("Authorization" to "Bearer $it") }
                .orEmpty()
            if (headers.isEmpty()) {
                mediaPlayer.setDataSource(url)
            } else {
                mediaPlayer.setDataSource(context, Uri.parse(url), headers)
            }
            mediaPlayer.setOnPreparedListener { mp ->
                duration = mp.duration.coerceAtLeast(0)
                prepared = true
                loadFailed = false
            }
            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                currentPosition = 0
                runCatching { it.seekTo(0) }
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                isPlaying = false
                prepared = false
                loadFailed = true
                true
            }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            prepared = false
            loadFailed = true
        }
    }

    DisposableEffect(mediaPlayer) {
        onDispose { mediaPlayer.release() }
    }

    // 更细的刷新间隔让波形进度平滑，同时不会触发重型布局。
    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying) {
            delay(250)
            try {
                currentPosition = mediaPlayer.currentPosition
            } catch (_: Exception) {
                isPlaying = false
            }
        }
    }

    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val waveBars = remember(url) {
        List(30) { index ->
            val primary = kotlin.math.abs(kotlin.math.sin(index * 0.83)).toFloat()
            val secondary = kotlin.math.abs(kotlin.math.cos(index * 0.37)).toFloat()
            (0.24f + primary * 0.46f + secondary * 0.24f).coerceAtMost(1f)
        }
    }
    val activeWaveColor = MaterialTheme.colorScheme.primary
    val inactiveWaveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)

    GlassCard(
        modifier = modifier,
        cornerRadius = 18,
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    try {
                        if (isPlaying) {
                            mediaPlayer.pause()
                            isPlaying = false
                        } else if (prepared) {
                            mediaPlayer.start()
                            isPlaying = true
                        }
                    } catch (_: Exception) {
                        isPlaying = false
                        loadFailed = true
                    }
                },
                enabled = prepared && !loadFailed,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (loadFailed) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
            ) {
                if (!prepared && !loadFailed) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) pauseDesc else playDesc,
                        tint = if (loadFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (loadFailed) stringResource(R.string.audio_load_failed)
                        else stringResource(R.string.audio_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (loadFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (duration > 0) {
                            "${formatTime(currentPosition)} / ${formatTime(duration)}"
                        } else {
                            "--:--"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onRegenerate != null) {
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.audio_regenerate),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(duration, prepared) {
                            detectTapGestures { offset ->
                                if (prepared && duration > 0 && size.width > 0) {
                                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                                    val newPosition = (duration * ratio).toInt()
                                    runCatching { mediaPlayer.seekTo(newPosition) }
                                    currentPosition = newPosition
                                }
                            }
                        }
                ) {
                    val gap = 2.dp.toPx()
                    val barWidth = ((size.width - gap * (waveBars.size - 1)) / waveBars.size)
                        .coerceAtLeast(1.dp.toPx())
                    waveBars.forEachIndexed { index, heightRatio ->
                        val barHeight = (size.height * heightRatio).coerceAtLeast(3.dp.toPx())
                        val x = index * (barWidth + gap)
                        val y = (size.height - barHeight) / 2f
                        val played = (index + 1f) / waveBars.size <= progress
                        drawRoundRect(
                            color = if (played) activeWaveColor else inactiveWaveColor,
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }
        }
    }
}

/** txt 文件渲染器：通过 OkHttp 下载文本内容并以等宽字体可滚动展示。 */
@Composable
fun TxtRenderer(url: String, modifier: Modifier = Modifier) {
    var content by remember(url) { mutableStateOf<String?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    val downloadFailed = stringResource(R.string.chat_media_download_failed)
    val loadFailedFmt = stringResource(R.string.chat_media_load_failed)

    LaunchedEffect(url) {
        content = null
        error = null
        try {
            val client = ServiceContainer.network.client
            val text = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    response.body?.string() ?: ""
                }
            }
            content = text
        } catch (e: Exception) {
            error = e.message ?: downloadFailed
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        when {
            content != null -> Text(
                text = content!!,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            error != null -> Text(
                text = loadFailedFmt.format(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            else -> Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** HTML 渲染器：URL 用带认证的 OkHttp 下载后用 loadDataWithBaseURL 显示；HTML 内容直接显示。
 *  支持点击全屏按钮进入全屏预览模式。 */
@Composable
fun HtmlRenderer(html: String, url: String, modifier: Modifier = Modifier) {
    var downloadedHtml by remember(url) { mutableStateOf<String?>(null) }
    var downloadError by remember(url) { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val downloadFailed = stringResource(R.string.chat_media_download_failed)
    val fullscreenDesc = stringResource(R.string.chat_media_fullscreen)
    val htmlLoadFailedFmt = stringResource(R.string.chat_media_html_load_failed)

    // 如果提供了 URL，用带认证的 OkHttp 下载 HTML 内容
    LaunchedEffect(url) {
        if (url.isNotBlank() && html.isBlank()) {
            downloadedHtml = null
            downloadError = null
            try {
                val client = ServiceContainer.network.client
                val text = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        response.body?.string() ?: ""
                    }
                }
                downloadedHtml = text
            } catch (e: Exception) {
                downloadError = e.message ?: downloadFailed
            }
        }
    }

    val resolvedContent = html.ifBlank { downloadedHtml ?: "" }
    val hasContent = resolvedContent.isNotBlank()

    // 内联预览：限制高度 + 右上角全屏按钮覆盖层
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                    if (hasContent) {
                        loadDataWithBaseURL(null, resolvedContent, "text/html", "UTF-8", null)
                    }
                }
            },
            update = { webView ->
                if (hasContent) {
                    webView.loadDataWithBaseURL(null, resolvedContent, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        // 右上角全屏按钮
        if (hasContent) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { fullscreen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = fullscreenDesc,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        // 加载中覆盖层
        if (url.isNotBlank() && html.isBlank() && downloadedHtml == null && downloadError == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    if (downloadError != null) {
        Text(
            text = htmlLoadFailedFmt.format(downloadError),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(4.dp)
        )
    }

    // 全屏预览 Dialog
    if (fullscreen && hasContent) {
        FullscreenHtmlDialog(
            content = resolvedContent,
            onDismiss = { fullscreen = false }
        )
    }
}

/** 全屏 HTML 预览：顶栏标题 + 关闭按钮，WebView 填充剩余空间。 */
@Composable
private fun FullscreenHtmlDialog(content: String, onDismiss: () -> Unit) {
    val exitFullscreenDesc = stringResource(R.string.chat_media_exit_fullscreen)
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        BackHandler {
            webView?.takeIf(WebView::canGoBack)?.goBack() ?: onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF111111))
        ) {
            // 顶部关闭按钮
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.FullscreenExit,
                    contentDescription = exitFullscreenDesc,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = exitFullscreenDesc,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 渲染内容段列表：文本段用 Text，多媒体段用对应渲染器。
 */
@Composable
fun RenderContentSegments(
    segments: List<ContentSegment>,
    textColor: Color,
    modifier: Modifier = Modifier,
    sessionId: String = ""
) {
    Column(modifier = modifier) {
        segments.forEachIndexed { idx, segment ->
            when (segment.type) {
                SegmentType.TEXT -> Text(
                    text = segment.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                SegmentType.IMAGE -> ImageRenderer(url = segment.url)
                SegmentType.VIDEO -> VideoRenderer(url = segment.url)
                SegmentType.AUDIO -> AudioRenderer(url = segment.url)
                SegmentType.TXT -> TxtRenderer(url = segment.url)
                SegmentType.HTML -> HtmlRenderer(html = segment.text, url = segment.url)
                SegmentType.FILE -> FileCardRenderer(fileName = segment.fileName, sessionId = sessionId)
            }
            if (idx != segments.lastIndex) {
                val gap = if (segment.type == SegmentType.FILE || segments[idx + 1].type == SegmentType.FILE) {
                    2.dp
                } else {
                    4.dp
                }
                Spacer(Modifier.height(gap))
            }
        }
    }
}

/** 获取文件扩展名（小写，不含点） */
private fun fileExt(name: String): String = name.substringAfterLast('.', "").lowercase()

private data class FileCardVisual(
    val icon: ImageVector,
    val accent: Color
)

/** 文件类型的视觉标识：颜色只用于文件卡片，不改变全局主题色。 */
private fun fileCardVisual(fileName: String): FileCardVisual {
    return when (fileExt(fileName)) {
        "pdf" -> FileCardVisual(Icons.Filled.Description, Color(0xFFE53935))
        "doc", "docx", "odt", "rtf" -> FileCardVisual(Icons.Filled.Description, Color(0xFF1976D2))
        "xls", "xlsx", "csv", "ods" -> FileCardVisual(Icons.Filled.Description, Color(0xFF2E7D32))
        "ppt", "pptx", "odp" -> FileCardVisual(Icons.Filled.Description, Color(0xFFE65100))
        in IMAGE_EXTS -> FileCardVisual(Icons.Filled.Image, Color(0xFF00897B))
        in VIDEO_EXTS -> FileCardVisual(Icons.Filled.Movie, Color(0xFF7B1FA2))
        "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "sh" ->
            FileCardVisual(Icons.Filled.Description, Color(0xFF1565C0))
        else -> FileCardVisual(Icons.Filled.InsertDriveFile, Color(0xFF607D8B))
    }
}

@Composable
private fun FileCardIcon(fileName: String, modifier: Modifier = Modifier) {
    val visual = remember(fileName) { fileCardVisual(fileName) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(visual.accent.copy(alpha = 0.14f))
            .padding(9.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.accent,
            modifier = Modifier.size(24.dp)
        )
    }
}

internal fun isPdfWorkspaceFile(fileName: String, mimeType: String = ""): Boolean =
    mimeType.equals("application/pdf", ignoreCase = true) ||
        fileExt(fileName) == "pdf"

internal fun isPlainTextWorkspaceFile(fileName: String, mimeType: String = ""): Boolean =
    mimeType.equals("text/plain", ignoreCase = true) ||
        fileExt(fileName) == "txt"

/** 用户图片附件需要脱离文字气泡单独渲染。 */
internal fun ContentSegment.isImageContent(): Boolean =
    type == SegmentType.IMAGE ||
        (type == SegmentType.FILE && fileExt(fileName) in IMAGE_EXTS)

/** 保持原始顺序，将用户图文消息拆成连续的图片组与非图片组。 */
internal fun groupUserMessageContent(
    segments: List<ContentSegment>
): List<List<ContentSegment>> {
    if (segments.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<ContentSegment>>()
    segments.forEach { segment ->
        val current = groups.lastOrNull()
        if (current == null || current.first().isImageContent() != segment.isImageContent()) {
            groups += mutableListOf(segment)
        } else {
            current += segment
        }
    }
    return groups
}

internal fun encodeWorkspaceFileName(fileName: String): String =
    java.net.URLEncoder.encode(fileName, "UTF-8")
        .replace("+", "%20")

/** 构建工作区文件下载 URL */
internal fun buildWorkspaceFileUrl(sessionId: String, fileName: String): String? {
    if (sessionId.isBlank() || fileName.isBlank()) return null
    val base = ServiceContainer.prefs.serverUrl.trimEnd('/')
    if (base.isBlank()) return null
    val encodedName = encodeWorkspaceFileName(fileName)
    return "$base/api/sessions/$sessionId/workspace/files/$encodedName"
}

internal fun resolveLocalWorkspaceFile(
    context: android.content.Context,
    sessionId: String,
    fileName: String
): File? {
    if (!ServiceContainer.prefs.isLocalMode || fileName.isBlank()) return null
    val (root, relativeName) = if (fileName.startsWith("shared://", ignoreCase = true)) {
        LocalWorkspaceStorage.resolveShared(context.filesDir)?.canonicalFile to
            fileName.substringAfter("//").trimStart('/')
    } else {
        LocalWorkspaceStorage.resolve(context.filesDir, sessionId)?.canonicalFile to fileName
    }
    root ?: return null
    if (relativeName.isBlank()) return null
    val target = runCatching { File(root, relativeName).canonicalFile }.getOrNull() ?: return null
    val isInside = target.path == root.path || target.path.startsWith(root.path + File.separator)
    return target.takeIf { isInside && it.isFile }
}

/** 判断文件类型是否可直接预览 */
private enum class FilePreviewType { IMAGE, TEXT, HTML, PDF, UNSUPPORTED }
private fun classifyFilePreview(fileName: String): FilePreviewType {
    val ext = fileExt(fileName)
    return when (ext) {
        in IMAGE_EXTS -> FilePreviewType.IMAGE
        in TXT_EXTS, "md", "json", "csv", "log", "yaml", "yml", "xml", "py", "js", "ts", "kt", "java", "c", "cpp", "go", "rs", "sh" -> FilePreviewType.TEXT
        in HTML_EXTS -> FilePreviewType.HTML
        in PDF_EXTS -> FilePreviewType.PDF
        else -> FilePreviewType.UNSUPPORTED
    }
}

/**
 * 文件卡片渲染器：根据文件类型选择渲染方式。
 * - 图片：直接内联显示
 * - 文本/代码：下载后显示内容
 * - HTML：用 WebView 渲染
 * - 其他：显示文件信息卡片 + 下载按钮
 */
@Composable
fun FileCardRenderer(fileName: String, sessionId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fileUrl = remember(sessionId, fileName) { buildWorkspaceFileUrl(sessionId, fileName) }
    val localFile = remember(sessionId, fileName, ServiceContainer.prefs.isLocalMode) {
        resolveLocalWorkspaceFile(context, sessionId, fileName)
    }
    val previewType = remember(fileName) { classifyFilePreview(fileName) }
    var showLocalPreview by remember(localFile) { mutableStateOf(false) }

    if (localFile != null) {
        if (previewType == FilePreviewType.IMAGE) {
            ImageRendererModel(model = localFile, modifier = modifier)
        } else {
            LocalWorkspaceFileCard(
                fileName = fileName,
                modifier = modifier,
                onClick = {
                    if (
                        previewType == FilePreviewType.UNSUPPORTED ||
                        isPdfWorkspaceFile(fileName) ||
                        isPlainTextWorkspaceFile(fileName)
                    ) {
                        openLocalWorkspaceFile(
                            context = context,
                            file = localFile,
                            forceChooser = isPlainTextWorkspaceFile(fileName)
                        )
                    } else {
                        showLocalPreview = true
                    }
                }
            )
        }
        if (showLocalPreview) {
            FilePreviewDialog(
                fileName = fileName,
                file = localFile,
                onDismiss = { showLocalPreview = false }
            )
        }
        return
    }

    when (previewType) {
        FilePreviewType.IMAGE -> {
            if (fileUrl != null) {
                ImageRendererModel(model = fileUrl, modifier = modifier)
            } else {
                UnsupportedFileCard(fileName, fileUrl, modifier)
            }
        }
        FilePreviewType.TEXT -> {
            if (fileUrl != null) {
                TxtRenderer(url = fileUrl, modifier = modifier)
                Spacer(Modifier.height(4.dp))
                DownloadButton(fileName, fileUrl)
            } else {
                UnsupportedFileCard(fileName, fileUrl, modifier)
            }
        }
        FilePreviewType.HTML -> {
            if (fileUrl != null) {
                HtmlRenderer(html = "", url = fileUrl, modifier = modifier)
            } else {
                UnsupportedFileCard(fileName, fileUrl, modifier)
            }
        }
        FilePreviewType.PDF -> {
            // 聊天消息中的 PDF 卡片：远程模式下显示下载按钮（预览需先下载到本地）
            UnsupportedFileCard(fileName, fileUrl, modifier)
        }
        FilePreviewType.UNSUPPORTED -> {
            UnsupportedFileCard(fileName, fileUrl, modifier)
        }
    }
}

/** 本地工作区文件卡：点击后直接预览，无法内置预览的类型交给系统应用打开。 */
@Composable
private fun LocalWorkspaceFileCard(
    fileName: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val previewText = stringResource(R.string.chat_media_click_to_preview)
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 12,
        // GlassCard 默认还有 16dp 内边距；文件卡片自己的 Row 已负责留白，不能叠加。
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            FileCardIcon(fileName, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = previewText,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

internal fun openLocalWorkspaceFile(
    context: android.content.Context,
    file: File,
    forceChooser: Boolean = false
): Boolean =
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val extension = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        val launchIntent = if (forceChooser) {
            android.content.Intent.createChooser(intent, null).apply {
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
        } else {
            intent
        }
        context.startActivity(launchIntent)
        true
    }.getOrElse {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.chat_media_open_file_failed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        false
    }

/** 不支持直接预览的文件卡片：显示文件名 + 下载按钮 */
@Composable
private fun UnsupportedFileCard(
    fileName: String,
    fileUrl: String?,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 12,
        // GlassCard 默认还有 16dp 内边距；文件卡片自己的 Row 已负责留白，不能叠加。
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            FileCardIcon(fileName, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            // 文件名
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.chat_media_click_to_download),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(6.dp))
            // 下载按钮
            if (fileUrl != null) {
                DownloadButton(fileName, fileUrl)
            }
        }
    }
}

/** 下载按钮：用带认证的 OkHttp 下载到本地缓存目录后打开 */
@Composable
private fun DownloadButton(fileName: String, fileUrl: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    val downloadDesc = stringResource(R.string.chat_media_download)

    IconButton(onClick = {
        if (downloading) return@IconButton
        downloading = true
        scope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val client = ServiceContainer.network.client
                    val request = Request.Builder().url(fileUrl).build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body ?: throw IllegalStateException(ServiceContainer.getString(R.string.chat_media_empty_response))
                        val dir = java.io.File(context.cacheDir, "downloads")
                        if (!dir.exists()) dir.mkdirs()
                        val file = java.io.File(dir, fileName)
                        body.byteStream().use { input ->
                            java.io.FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                        file.absolutePath
                    }
                }
                // 用 FileProvider URI 打开文件
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    java.io.File(savedPath)
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/octet-stream")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) { /* 忽略 */ }
            downloading = false
        }
    }) {
        if (downloading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = downloadDesc,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 将毫秒格式化为 mm:ss。 */
private fun formatTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}

/**
 * PDF 渲染器：使用系统 [PdfRenderer] 单页渲染，并通过上一页/下一页切换。
 * 入参为本地 [File]（已下载到缓存目录）。
 * 任意时刻只保留一张展示 Bitmap，避免漫画 PDF 的多页大图同时占用内存。
 */
@Composable
fun PdfRendererFromFile(file: File, modifier: Modifier = Modifier) {
    var pageCount by remember(file) { mutableStateOf(0) }
    var currentPage by remember(file) { mutableStateOf(0) }
    var loading by remember(file) { mutableStateOf(true) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    val pdfNoContent = stringResource(R.string.chat_media_pdf_no_content)
    val pdfRenderFailed = stringResource(R.string.chat_media_pdf_render_failed)
    val pdfLoadFailedFmt = stringResource(R.string.chat_media_pdf_load_failed)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val renderWidth = remember(configuration.screenWidthDp, density.density) {
        ((configuration.screenWidthDp - 16).coerceAtLeast(240) * density.density)
            .roundToInt()
            .coerceIn(320, MAX_PDF_RENDER_WIDTH)
    }
    val renderSemaphore = remember(file) { Semaphore(1) }

    LaunchedEffect(file) {
        loading = true
        error = null
        pageCount = 0
        currentPage = 0
        try {
            pageCount = withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext 0
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { it.pageCount }
                }
            }
            if (pageCount == 0) error = pdfNoContent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: pdfRenderFailed
        } finally {
            loading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(text = pdfLoadFailedFmt.format(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                PdfPageBitmap(
                    file = file,
                    pageIndex = currentPage,
                    targetWidth = renderWidth,
                    renderSemaphore = renderSemaphore,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = currentPage > 0,
                        onClick = { currentPage = (currentPage - 1).coerceAtLeast(0) }
                    ) {
                        Icon(
                            Icons.Filled.ChevronLeft,
                            contentDescription = stringResource(R.string.chat_media_pdf_previous_page)
                        )
                    }
                    Text(
                        text = "${currentPage + 1} / $pageCount",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    IconButton(
                        enabled = currentPage < pageCount - 1,
                        onClick = {
                            currentPage = (currentPage + 1).coerceAtMost(pageCount - 1)
                        }
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = stringResource(R.string.chat_media_pdf_next_page)
                        )
                    }
                }
            }
        }
    }
}

/** 单页 PDF 渲染：按需加载，加载失败显示该页错误而不影响其他页。 */
@Composable
private fun PdfPageBitmap(
    file: File,
    pageIndex: Int,
    targetWidth: Int,
    renderSemaphore: Semaphore,
    modifier: Modifier = Modifier
) {
    var bmp by remember(file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(file, pageIndex) { mutableStateOf(true) }
    var error by remember(file, pageIndex) { mutableStateOf<String?>(null) }
    val pageRenderFailed = stringResource(R.string.chat_media_pdf_page_render_failed, pageIndex + 1)
    val pageLoadFailedFmt = stringResource(R.string.chat_media_pdf_page_load_failed, pageIndex + 1)
    val pageDesc = stringResource(R.string.chat_media_pdf_page, pageIndex + 1)

    LaunchedEffect(file, pageIndex, targetWidth) {
        loading = true
        error = null
        bmp = null
        var renderedBitmap: Bitmap? = null
        try {
            renderedBitmap = withContext(Dispatchers.IO) {
                renderSemaphore.withPermit {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val page = renderer.openPage(pageIndex)
                            try {
                                val renderSize = calculatePdfRenderSize(
                                    pageWidth = page.width,
                                    pageHeight = page.height,
                                    targetWidth = targetWidth,
                                    maxPixels = MAX_PDF_RENDER_PIXELS
                                )
                                val bitmap = Bitmap.createBitmap(
                                    renderSize.width,
                                    renderSize.height,
                                    Bitmap.Config.ARGB_8888
                                )
                                try {
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                    )
                                    bitmap
                                } catch (error: Throwable) {
                                    bitmap.recycle()
                                    throw error
                                }
                            } finally {
                                page.close()
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            bmp = renderedBitmap
            renderedBitmap = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: pageRenderFailed
        } finally {
            renderedBitmap?.takeUnless { it.isRecycled }?.recycle()
            loading = false
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        when {
            loading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            error != null -> Text(
                text = pageLoadFailedFmt.format(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
            b != null -> {
                val imageBitmap = remember(b) { b.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = pageDesc,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            }
        }
    }
}

private const val MAX_PDF_RENDER_WIDTH = 640
private const val MAX_PDF_RENDER_PIXELS = 1_200_000

internal data class PdfRenderSize(
    val width: Int,
    val height: Int
)

internal fun calculatePdfRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    targetWidth: Int,
    maxPixels: Int
): PdfRenderSize {
    val safePageWidth = pageWidth.coerceAtLeast(1)
    val safePageHeight = pageHeight.coerceAtLeast(1)
    val safeTargetWidth = targetWidth.coerceAtLeast(1)
    val safeMaxPixels = maxPixels.coerceAtLeast(1)
    val widthScale = safeTargetWidth.toDouble() / safePageWidth.toDouble()
    val pixelScale = sqrt(
        safeMaxPixels.toDouble() /
            (safePageWidth.toDouble() * safePageHeight.toDouble())
    )
    val scale = minOf(widthScale, pixelScale)
    return PdfRenderSize(
        width = (safePageWidth * scale).toInt().coerceAtLeast(1),
        height = (safePageHeight * scale).toInt().coerceAtLeast(1)
    )
}

/**
 * 文件预览 Dialog：根据本地 [file] 的扩展名选择渲染方式。
 * 支持图片/文本/HTML/PDF；其他类型显示不支持提示。
 */
@Composable
fun FilePreviewDialog(fileName: String, file: File, onDismiss: () -> Unit) {
    val previewType = remember(fileName) { classifyFilePreview(fileName) }
    val closeDesc = stringResource(R.string.common_close)
    val imagePreviewDesc = stringResource(R.string.chat_media_image_preview)
    val readFailed = stringResource(R.string.chat_media_read_failed)
    val loadFailedFmt = stringResource(R.string.chat_media_load_failed)
    val unsupportedPreview = stringResource(R.string.chat_media_unsupported_preview)
    val fileDownloadedTo = stringResource(R.string.chat_media_file_downloaded_to, file.name)
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        BackHandler {
            webView?.takeIf(WebView::canGoBack)?.goBack() ?: onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            // 顶部标题 + 关闭按钮
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = closeDesc, tint = Color.White)
                }
            }
            // 内容区
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp)
            ) {
                when (previewType) {
                    FilePreviewType.IMAGE -> {
                        AsyncImage(
                            model = file,
                            contentDescription = imagePreviewDesc,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    FilePreviewType.TEXT -> {
                        var content by remember(file) { mutableStateOf<String?>(null) }
                        var loadErr by remember(file) { mutableStateOf<String?>(null) }
                        LaunchedEffect(file) {
                            try {
                                content = withContext(Dispatchers.IO) { file.readText() }
                            } catch (e: Exception) {
                                loadErr = e.message ?: readFailed
                            }
                        }
                        when {
                            content != null -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = content!!,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            loadErr != null -> Text(
                                loadFailedFmt.format(loadErr),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            else -> CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    FilePreviewType.HTML -> {
                        var htmlContent by remember(file) { mutableStateOf<String?>(null) }
                        var htmlErr by remember(file) { mutableStateOf<String?>(null) }
                        LaunchedEffect(file) {
                            try {
                                htmlContent = withContext(Dispatchers.IO) { file.readText() }
                            } catch (e: Exception) {
                                htmlErr = e.message ?: readFailed
                            }
                        }
                        when {
                            htmlContent != null -> {
                                val content = htmlContent!!
                                AndroidView(
                                    factory = { ctx ->
                                        WebView(ctx).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.loadWithOverviewMode = true
                                            settings.useWideViewPort = true
                                            webViewClient = WebViewClient()
                                            loadDataWithBaseURL("about:blank", content, "text/html", "UTF-8", null)
                                            webView = this
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            htmlErr != null -> Text(
                                loadFailedFmt.format(htmlErr),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            else -> CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    FilePreviewType.PDF -> {
                        PdfRendererFromFile(file = file, modifier = Modifier.fillMaxSize())
                    }
                    FilePreviewType.UNSUPPORTED -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                unsupportedPreview,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                fileDownloadedTo,
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
