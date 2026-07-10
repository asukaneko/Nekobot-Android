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
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 多媒体内容段类型 */
enum class SegmentType { TEXT, IMAGE, VIDEO, AUDIO, TXT, HTML }

/** 内容段：文本或多媒体 URL。HTML 内容（整段为 HTML 时）存于 [text]。 */
data class ContentSegment(
    val type: SegmentType,
    val text: String = "",      // TEXT 类型的文本内容；HTML 整段内容也存这里
    val url: String = "",       // 多媒体 URL
    val caption: String = ""    // URL 前后的说明文字（可选）
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

/** HTML 渲染器：URL 用 WebView 加载，HTML 内容用 loadDataWithBaseURL 显示。 */
@Composable
fun HtmlRenderer(html: String, url: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                if (url.isNotBlank()) {
                    loadUrl(url)
                } else if (html.isNotBlank()) {
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp, max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/**
 * 渲染内容段列表：文本段用 Text，多媒体段用对应渲染器。
 */
@Composable
fun RenderContentSegments(
    segments: List<ContentSegment>,
    textColor: Color,
    modifier: Modifier = Modifier
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
            }
            if (idx != segments.lastIndex) Spacer(Modifier.height(4.dp))
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
