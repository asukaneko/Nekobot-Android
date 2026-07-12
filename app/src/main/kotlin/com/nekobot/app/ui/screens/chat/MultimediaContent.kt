package com.nekobot.app.ui.screens.chat

import android.media.MediaPlayer
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

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
    var fullscreen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        AsyncImage(
            model = url,
            contentDescription = "图片",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
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
                    model = url,
                    contentDescription = "图片",
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
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White
            )
        }
    }
}

/** 音频渲染器：自定义播放器 UI（播放/暂停 + 进度条 + 时长）。 */
@Composable
fun AudioRenderer(url: String, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var prepared by remember { mutableStateOf(false) }

    val mediaPlayer = remember(url) {
        MediaPlayer().apply {
            try {
                setDataSource(url)
                setOnPreparedListener { mp ->
                    duration = mp.duration
                    prepared = true
                }
                setOnCompletionListener {
                    isPlaying = false
                    currentPosition = 0
                }
                prepareAsync()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(url) {
        onDispose { mediaPlayer.release() }
    }

    // 播放中每秒更新进度
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(1000)
            try {
                currentPosition = mediaPlayer.currentPosition
            } catch (_: Exception) {
            }
        }
    }

    GlassCard(modifier = modifier, cornerRadius = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                try {
                    if (isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        if (!prepared) {
                            mediaPlayer.prepareAsync()
                        }
                        mediaPlayer.start()
                        isPlaying = true
                    }
                } catch (_: Exception) {
                }
            }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(8.dp))
            // 进度条：用 0f..1f 的比例
            val progress = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = progress,
                onValueChange = { v ->
                    try {
                        if (duration > 0) {
                            val newPos = (v * duration).toInt()
                            mediaPlayer.seekTo(newPos)
                            currentPosition = newPos
                        }
                    } catch (_: Exception) {
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** txt 文件渲染器：通过 OkHttp 下载文本内容并以等宽字体可滚动展示。 */
@Composable
fun TxtRenderer(url: String, modifier: Modifier = Modifier) {
    var content by remember(url) { mutableStateOf<String?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }

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
            error = e.message ?: "下载失败"
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
                text = "加载失败: $error",
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
                downloadError = e.message ?: "下载失败"
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
                    contentDescription = "全屏查看",
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
            text = "HTML 加载失败: $downloadError",
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
    Dialog(onDismissRequest = onDismiss) {
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
                    contentDescription = "退出全屏",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "退出全屏",
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
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
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
            if (idx != segments.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
}

/** 获取文件扩展名（小写，不含点） */
private fun fileExt(name: String): String = name.substringAfterLast('.', "").lowercase()

/** 构建工作区文件下载 URL */
private fun buildWorkspaceFileUrl(sessionId: String, fileName: String): String? {
    if (sessionId.isBlank() || fileName.isBlank()) return null
    val base = ServiceContainer.prefs.serverUrl.trimEnd('/')
    if (base.isBlank()) return null
    return "$base/api/sessions/$sessionId/workspace/files/${java.net.URLEncoder.encode(fileName, "UTF-8")}"
}

/** 判断文件类型是否可直接预览 */
private enum class FilePreviewType { IMAGE, TEXT, HTML, UNSUPPORTED }
private fun classifyFilePreview(fileName: String): FilePreviewType {
    val ext = fileExt(fileName)
    return when (ext) {
        in IMAGE_EXTS -> FilePreviewType.IMAGE
        in TXT_EXTS, "md", "json", "csv", "log", "yaml", "yml", "xml", "py", "js", "ts", "kt", "java", "c", "cpp", "go", "rs", "sh" -> FilePreviewType.TEXT
        in HTML_EXTS -> FilePreviewType.HTML
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
    val fileUrl = remember(sessionId, fileName) { buildWorkspaceFileUrl(sessionId, fileName) }
    val previewType = remember(fileName) { classifyFilePreview(fileName) }

    when (previewType) {
        FilePreviewType.IMAGE -> {
            if (fileUrl != null) {
                ImageRenderer(url = fileUrl, modifier = modifier)
            } else {
                UnsupportedFileCard(fileName, fileUrl, modifier)
            }
        }
        FilePreviewType.TEXT -> {
            if (fileUrl != null) {
                TxtRenderer(url = fileUrl, modifier = modifier)
                // 下载按钮
                if (fileUrl != null) {
                    Spacer(Modifier.height(4.dp))
                    DownloadButton(fileName, fileUrl)
                }
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
        FilePreviewType.UNSUPPORTED -> {
            UnsupportedFileCard(fileName, fileUrl, modifier)
        }
    }
}

/** 不支持直接预览的文件卡片：显示文件名 + 下载按钮 */
@Composable
private fun UnsupportedFileCard(
    fileName: String,
    fileUrl: String?,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadius = 12) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            // 文件图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fileExt(fileName).take(3).ifBlank { "?" }.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
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
                    text = "点击下载文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
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

    IconButton(onClick = {
        if (downloading) return@IconButton
        downloading = true
        scope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val client = ServiceContainer.network.client
                    val request = Request.Builder().url(fileUrl).build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body ?: throw IllegalStateException("空响应")
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
                contentDescription = "下载",
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
