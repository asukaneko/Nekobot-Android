package app.lianxu.independent

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun voiceDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") {
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (duration != null && duration > 0) return duration
        }
    } catch (_: Exception) {
        // Some playable containers have no duration available through the retriever.
    } finally {
        retriever.release()
    }
    val probe = MediaPlayer()
    try {
        probe.setDataSource(file.absolutePath)
        probe.prepare()
        require(probe.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO })
        return probe.duration.toLong().also { require(it > 0) }
    } finally {
        probe.release()
    }
}

internal enum class VoicePlaybackState(val description: String) {
    Idle("待播放"), Preparing("正在准备"), Playing("正在播放"), Paused("已暂停")
}

private fun wholeSecondsLeft(milliseconds: Long) = (milliseconds + 999) / 1000

internal fun voiceBubbleWidth(durationMs: Long?, available: Dp, label: Dp = 0.dp): Dp {
    val seconds = (durationMs ?: 0L).coerceAtLeast(0L) / 1000f
    val desired = if (seconds <= 8f) {
        84.dp + 7.dp * (seconds - 2f).coerceAtLeast(0f)
    } else {
        126.dp + (74.dp / 12f) * (seconds - 8f)
    }
    // Includes both insets, the speaker, and a little separation at larger font scales.
    val minimum = maxOf(84.dp, 58.dp + label)
    val limit = (available - 12.dp).coerceAtLeast(minimum).coerceAtMost(available)
    return desired.coerceAtMost(224.dp).coerceAtLeast(minimum).coerceAtMost(limit)
}

private fun Modifier.voiceWidth(durationMs: Long?, label: Dp = 0.dp) = layout { child, constraints ->
    val width = voiceBubbleWidth(durationMs, constraints.maxWidth.toDp(), label).roundToPx()
        .coerceIn(constraints.minWidth, constraints.maxWidth)
    val measured = child.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(measured.width, measured.height) { measured.placeRelative(0, 0) }
}

internal class ChatVoicePlayback(private val scope: CoroutineScope) {
    private var file by mutableStateOf<File?>(null)
    private var state by mutableStateOf(VoicePlaybackState.Idle)
    private var player: MediaPlayer? = null

    private var progressJob: Job? = null
    private var remainingSeconds by mutableStateOf<Long?>(null)

    fun remainingSecondsFor(source: File) = if (file == source) remainingSeconds else null

    private fun samplePosition(current: MediaPlayer, durationMs: Long): Long {
        val left = (durationMs - current.currentPosition.coerceAtLeast(0)).coerceAtLeast(0)
        remainingSeconds = wholeSecondsLeft(left)
        return left
    }

    fun stateFor(source: File) = if (file == source) state else VoicePlaybackState.Idle

    fun release(source: File? = null) {
        if (source != null && file != source) return
        progressJob?.cancel()
        progressJob = null
        remainingSeconds = null
        val current = player
        player = null
        file = null
        state = VoicePlaybackState.Idle
        current?.release()
    }

    fun toggle(source: File, durationMs: Long, onFailure: () -> Unit) {
        fun fail() {
            release()
            onFailure()
        }
        fun start(current: MediaPlayer) {
            current.start()
            state = VoicePlaybackState.Playing
            progressJob = scope.launch(Dispatchers.Main.immediate) {
                while (player === current && state == VoicePlaybackState.Playing) {
                    val left = try {
                        samplePosition(current, durationMs)
                    } catch (_: IllegalStateException) {
                        fail()
                        return@launch
                    }
                    if (left == 0L) break
                    // Re-read the player at the next visible second boundary.
                    delay(((left - 1) % 1000 + 1).coerceAtLeast(50))
                }
            }
        }
        try {
            val current = player
            if (file == source && current != null) {
                when (state) {
                    VoicePlaybackState.Playing -> {
                        current.pause()
                        progressJob?.cancel()
                        samplePosition(current, durationMs)
                        state = VoicePlaybackState.Paused
                    }
                    VoicePlaybackState.Paused -> start(current)
                    else -> Unit
                }
                return
            }
            release()
            val next = MediaPlayer()
            player = next
            file = source
            state = VoicePlaybackState.Preparing
            next.setOnPreparedListener {
                if (player === it) {
                    try {
                        start(it)
                    } catch (_: IllegalStateException) {
                        fail()
                    }
                }
            }
            next.setOnCompletionListener { if (player === it) release() }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) fail()
                true
            }
            next.setDataSource(source.absolutePath)
            next.prepareAsync()
        } catch (_: Exception) {
            fail()
        }
    }
}

@Composable
internal fun rememberChatVoicePlayback(): ChatVoicePlayback {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val playback = remember(lifecycle, scope) { ChatVoicePlayback(scope) }
    DisposableEffect(lifecycle, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) playback.release()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            playback.release()
        }
    }
    return playback
}

@Composable
internal fun ChatVoiceBubble(file: File, original: String, playback: ChatVoicePlayback, manage: (() -> Unit)? = null,
    observe: @Composable (VoiceStatus, VoiceReason?) -> Unit = { _, _ -> }) {
    key(file) { VoiceBubbleContent(file, original, playback, manage, observe) }
}

@Composable
private fun VoiceBubbleContent(file: File, original: String, playback: ChatVoicePlayback, manage: (() -> Unit)?, observe: @Composable (VoiceStatus, VoiceReason?) -> Unit) {
    val duration by produceState<Long?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try { voiceDurationMs(file) } catch (_: Exception) { 0L }
        }
    }
    var broken by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    DisposableEffect(playback, file) { onDispose { playback.release(file) } }
    val shape = RoundedCornerShape(50)
    val milliseconds = duration
    if (milliseconds == null) {
        Box(
            Modifier.voiceWidth(null).heightIn(min = 40.dp).background(ChatRoleBubble, shape).border(1.dp, Line, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("正在读取语音…", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = Muted)
        }
        return
    }
    if (broken || milliseconds <= 0) {
        observe(VoiceStatus.UNAVAILABLE_RENDERED, if(broken) VoiceReason.PLAYER_ERROR else VoiceReason.DURATION_UNREADABLE)
        VoiceUnavailable(original, manage)
        return
    }
    observe(VoiceStatus.VOICE_RENDERED,null)
    val state = playback.stateFor(file)
    val playing = state == VoicePlaybackState.Playing
    val totalSeconds = wholeSecondsLeft(milliseconds)
    val seconds = playback.remainingSecondsFor(file) ?: totalSeconds
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val labelWidth = with(LocalDensity.current) {
        measurer.measure("${totalSeconds}″", labelStyle).size.width.toDp()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.voiceWidth(milliseconds, labelWidth).heightIn(min = 40.dp).clip(shape).background(ChatRoleBubble).border(1.dp, Line, shape)
                .bubbleActions(original,"voice",expanded,transcript={ expanded=!expanded }, fallback={ expanded=true }, click={ playback.toggle(file, milliseconds) { broken = true } }
                ).semantics {
                    contentDescription = if (playing || state == VoicePlaybackState.Paused) {
                        "语音，剩余${seconds}秒"
                    } else "语音，${totalSeconds}秒"
                    stateDescription = state.description
                    if (expanded) collapse("收起原文") { expanded = false; true }
                    else expand("展开原文") { expanded = true; true }
                }.testTag("chat-voice-bubble").padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            VoiceSpeaker(playing)
            Text("${seconds}″", Modifier.widthIn(min = labelWidth), style = labelStyle, color = Muted, textAlign = TextAlign.End)
        }
        if (expanded) VoiceTranscript(original, manage, close = { expanded = false })
    }
}

@Composable
private fun VoiceSpeaker(playing: Boolean) {
    val reducedMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val phase = if (playing && !reducedMotion) {
        rememberInfiniteTransition(label = "语音播放").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "声弧"
        )
    } else null
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        val stroke = Stroke(1.6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val speaker = Path().apply {
            moveTo(2.5f * unit, 9f * unit)
            lineTo(6f * unit, 9f * unit)
            lineTo(11f * unit, 5f * unit)
            lineTo(11f * unit, 19f * unit)
            lineTo(6f * unit, 15f * unit)
            lineTo(2.5f * unit, 15f * unit)
            close()
        }
        drawPath(speaker, ink, style = stroke)
        fun arc(radius: Float, alpha: Float) {
            val r = radius * unit
            drawArc(ink, -34f, 68f, false, Offset(9f * unit - r, center.y - r),
                Size(2 * r, 2 * r), alpha = alpha, style = stroke)
        }
        if (phase == null) {
            arc(7f, 1f)
            if (playing) arc(12f, 0.8f)
        } else {
            repeat(2) { index ->
                val step = (phase.value + index * 0.5f) % 1f
                arc(5.5f + 8f * step, minOf(step * 8f, 1f) * (1f - step))
            }
        }
    }
}

@Composable
private fun VoiceTranscript(text: String, manage: (() -> Unit)?, close: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(16.dp)).background(ChatRoleBubble)
        .bubbleActions(text,"voice",true,transcript=close,fallback=manage,click=close)
        .padding(UiSpace.bubble), style = MaterialTheme.typography.bodyLarge, color = ChatRoleText)
}

@Composable
private fun VoiceUnavailable(original: String, manage: (() -> Unit)?) {
    val actions = if (LocalBubbleMessage.current != null) Modifier.bubbleActions(original) else if (manage == null) Modifier else Modifier
        .pointerInput(manage) { detectTapGestures(onLongPress = { manage() }) }
        .semantics(mergeDescendants = true) { onLongClick("消息操作") { manage(); true } }
    Column(
        Modifier.background(ChatRoleBubble, RoundedCornerShape(16.dp)).then(actions).padding(UiSpace.bubble),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("语音无法播放", style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(original, style = MaterialTheme.typography.bodyLarge, color = ChatRoleText)
    }
}
