package com.nekobot.app.ui.screens.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LivePipelineMode
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.nekobot.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class LiveConversationPhase {
    Connecting,
    Listening,
    Transcribing,
    Thinking,
    PreparingAudio,
    Speaking,
    Error
}

internal data class LiveRealtimeTurnCallbacks(
    val onConnected: () -> Unit,
    val onUserTranscript: (String, Boolean) -> Unit,
    val onAssistantTranscript: (String, Boolean) -> Unit,
    val onAudioDelta: (ByteArray) -> Unit,
    val onCompleted: () -> Unit,
    val onError: (String) -> Unit
)

internal fun isLiveConversationSession(sessionMode: String?): Boolean =
    sessionMode.isNullOrBlank() ||
        sessionMode.equals("character", ignoreCase = true) ||
        sessionMode.equals("agent", ignoreCase = true)

internal fun liveMessageFingerprint(message: Message): String {
    val stableId = message.id?.takeIf {
        it.isNotBlank() &&
            it != ChatViewModel.STREAMING_ID &&
            !it.startsWith(ChatViewModel.STREAM_FALLBACK_PREFIX)
    }
    return stableId ?: "${message.timestamp.orEmpty()}|${message.displayContent.trim()}"
}

internal fun findLiveAssistantReply(
    messages: List<Message>,
    baseline: Set<String>
): Message? = messages.asReversed().firstOrNull { message ->
    !message.isUser &&
        !message.isThinkingCard &&
        message.id != ChatViewModel.STREAMING_ID &&
        message.displayContent.isNotBlank() &&
        liveMessageFingerprint(message) !in baseline
}

@Composable
internal fun LiveConversationDialog(
    sessionId: String,
    sessionName: String,
    portraitUrl: String?,
    messages: List<Message>,
    sending: Boolean,
    streamingSubtitle: String,
    ttsStates: Map<String, MessageTtsUiState>,
    onSendMessage: (String) -> Unit,
    onPrepareTts: (Message) -> Unit,
    onStartRealtimeTurn: (ByteArray, LiveRealtimeTurnCallbacks) -> Unit,
    onStopRealtimeTurn: () -> Unit,
    onStopGeneration: () -> Unit,
    onValidatePipeline: suspend (LivePipelineMode) -> String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var interfaceVisible by remember(sessionId) { mutableStateOf(false) }
    var portraitLanded by remember(sessionId) { mutableStateOf(false) }
    var dismissing by remember(sessionId) { mutableStateOf(false) }
    var phase by remember(sessionId) { mutableStateOf(LiveConversationPhase.Connecting) }
    var subtitle by remember(sessionId) { mutableStateOf("") }
    var subtitleFromUser by remember(sessionId) { mutableStateOf(false) }
    var errorMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    var spectrum by remember(sessionId) { mutableStateOf(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f }) }
    var assistantBaseline by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var awaitingAssistant by remember(sessionId) { mutableStateOf(false) }
    var pendingTtsContent by remember(sessionId) { mutableStateOf<String?>(null) }
    var pendingTtsMessageId by remember(sessionId) { mutableStateOf<String?>(null) }
    var autoStopSignal by remember(sessionId) { mutableIntStateOf(0) }
    var playbackCompleteSignal by remember(sessionId) { mutableIntStateOf(0) }
    var pipeline by remember(sessionId) {
        mutableStateOf(ServiceContainer.prefs.livePipelineMode)
    }
    var realtimeAudioStarted by remember(sessionId) { mutableStateOf(false) }

    val soundEffects = remember(sessionId) { LiveConversationSoundEffects() }

    val audioController = remember(sessionId) {
        LiveAudioController(
            context = context,
            scope = scope,
            onSpectrum = { spectrum = it },
            onAutoStop = { autoStopSignal++ },
            onPlaybackComplete = { playbackCompleteSignal++ },
            onError = {
                errorMessage = it
                phase = LiveConversationPhase.Error
            }
        )
    }

    fun beginListening(targetPipeline: LivePipelineMode = pipeline) {
        if (sending || phase == LiveConversationPhase.Transcribing) return
        errorMessage = null
        pendingTtsContent = null
        pendingTtsMessageId = null
        subtitle = ""
        subtitleFromUser = true
        realtimeAudioStarted = false
        val started = when (targetPipeline) {
            LivePipelineMode.CLASSIC -> audioController.startRecording()
            LivePipelineMode.REALTIME -> audioController.startRealtimeRecording()
        }
        if (started) {
            phase = LiveConversationPhase.Listening
        }
    }

    fun finishRecordingAndSend() {
        if (phase != LiveConversationPhase.Listening) return
        if (pipeline == LivePipelineMode.REALTIME) {
            val pcm16 = audioController.finishRealtimeRecording()
            if (pcm16 == null) {
                errorMessage = context.getString(R.string.live_recording_failed)
                phase = LiveConversationPhase.Error
                return
            }
            subtitle = ""
            subtitleFromUser = true
            phase = LiveConversationPhase.Thinking
            onStartRealtimeTurn(
                pcm16,
                LiveRealtimeTurnCallbacks(
                    onConnected = {
                        if (phase != LiveConversationPhase.Speaking) {
                            phase = LiveConversationPhase.Thinking
                        }
                    },
                    onUserTranscript = { text, _ ->
                        if (!realtimeAudioStarted && text.isNotBlank()) {
                            subtitle = text
                            subtitleFromUser = true
                        }
                    },
                    onAssistantTranscript = { text, _ ->
                        if (text.isNotBlank()) {
                            subtitle = text
                            subtitleFromUser = false
                            if (!realtimeAudioStarted) phase = LiveConversationPhase.Thinking
                        }
                    },
                    onAudioDelta = { bytes ->
                        if (!realtimeAudioStarted) {
                            realtimeAudioStarted = audioController.startRealtimePlayback()
                            if (realtimeAudioStarted) phase = LiveConversationPhase.Speaking
                        }
                        if (realtimeAudioStarted) audioController.writeRealtimeAudio(bytes)
                    },
                    onCompleted = {
                        if (realtimeAudioStarted) {
                            audioController.finishRealtimePlayback()
                        } else {
                            scope.launch {
                                delay(240)
                                beginListening(LivePipelineMode.REALTIME)
                            }
                        }
                    },
                    onError = { message ->
                        audioController.stopPlayback()
                        errorMessage = message
                        phase = LiveConversationPhase.Error
                    }
                )
            )
            return
        }
        val audioFile = audioController.finishRecording()
        if (audioFile == null) {
            errorMessage = context.getString(R.string.live_recording_failed)
            phase = LiveConversationPhase.Error
            return
        }
        phase = LiveConversationPhase.Transcribing
        scope.launch {
            try {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { audioFile.readBytes() }
                when (val result = ServiceContainer.unified.transcribeAudio(bytes, audioFile.name, null)) {
                    is Resource.Success -> {
                        val recognized = result.data?.text.orEmpty().trim()
                        if (recognized.isBlank()) {
                            errorMessage = context.getString(R.string.live_no_speech)
                            phase = LiveConversationPhase.Error
                        } else {
                            subtitle = recognized
                            subtitleFromUser = true
                            assistantBaseline = messages
                                .filter { !it.isUser && !it.isThinkingCard }
                                .mapTo(mutableSetOf(), ::liveMessageFingerprint)
                            awaitingAssistant = true
                            phase = LiveConversationPhase.Thinking
                            onSendMessage(recognized)
                        }
                    }
                    is Resource.Error -> {
                        errorMessage = result.message ?: context.getString(R.string.live_asr_failed)
                        phase = LiveConversationPhase.Error
                    }
                    is Resource.Loading -> Unit
                }
            } catch (error: Exception) {
                errorMessage = error.message ?: context.getString(R.string.live_asr_failed)
                phase = LiveConversationPhase.Error
            } finally {
                audioFile.delete()
            }
        }
    }

    DisposableEffect(audioController) {
        onDispose {
            onStopRealtimeTurn()
            audioController.release()
            soundEffects.release()
        }
    }

    LaunchedEffect(Unit) {
        soundEffects.playEntrance()
        portraitLanded = true
        delay(LIVE_PORTRAIT_TRANSITION_MS)
        interfaceVisible = true
        delay(LIVE_INTERFACE_TRANSITION_MS)
        if (!dismissing) beginListening()
    }

    LaunchedEffect(autoStopSignal) {
        if (autoStopSignal > 0 && phase == LiveConversationPhase.Listening) {
            finishRecordingAndSend()
        }
    }

    LaunchedEffect(playbackCompleteSignal) {
        if (playbackCompleteSignal > 0 && phase == LiveConversationPhase.Speaking) {
            delay(320)
            beginListening()
        }
    }

    LaunchedEffect(awaitingAssistant, sending, streamingSubtitle) {
        if (awaitingAssistant && sending) {
            phase = LiveConversationPhase.Thinking
            if (streamingSubtitle.isNotBlank()) {
                subtitle = liveSubtitleWindow(streamingSubtitle)
                subtitleFromUser = false
            }
        }
    }

    LaunchedEffect(messages, sending, awaitingAssistant) {
        if (awaitingAssistant && !sending) {
            val reply = findLiveAssistantReply(messages, assistantBaseline)
            if (reply != null) {
                awaitingAssistant = false
                subtitle = liveSubtitleWindow(reply.displayContent)
                subtitleFromUser = false
                pendingTtsContent = reply.displayContent.trim()
                pendingTtsMessageId = reply.id
                phase = LiveConversationPhase.PreparingAudio
                onPrepareTts(reply)
            }
        }
    }

    val pendingAudioMessage = remember(messages, pendingTtsContent) {
        val expected = pendingTtsContent
        if (expected.isNullOrBlank()) null else messages.asReversed().firstOrNull {
            !it.isUser && it.displayContent.trim() == expected && !it.audioUrl.isNullOrBlank()
        }
    }
    LaunchedEffect(pendingAudioMessage?.audioUrl) {
        val rawUrl = pendingAudioMessage?.audioUrl ?: return@LaunchedEffect
        val playableUrl = resolveAvatarUrl(rawUrl) ?: rawUrl
        pendingTtsMessageId = pendingAudioMessage.id
        phase = LiveConversationPhase.Speaking
        if (!audioController.play(playableUrl, ServiceContainer.prefs.token)) {
            phase = LiveConversationPhase.Error
        }
    }

    val pendingTtsError = remember(ttsStates, messages, pendingTtsMessageId, pendingTtsContent) {
        val direct = pendingTtsMessageId?.let(ttsStates::get)
        if (direct?.status == MessageTtsStatus.Error) return@remember direct.error
        val expected = pendingTtsContent
        messages.asReversed()
            .firstOrNull { !it.isUser && it.displayContent.trim() == expected }
            ?.id
            ?.let(ttsStates::get)
            ?.takeIf { it.status == MessageTtsStatus.Error }
            ?.error
    }
    LaunchedEffect(pendingTtsError) {
        if (!pendingTtsError.isNullOrBlank() && phase == LiveConversationPhase.PreparingAudio) {
            errorMessage = pendingTtsError
            phase = LiveConversationPhase.Error
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        LiveConversationContent(
            sessionName = sessionName,
            portraitUrl = portraitUrl,
            phase = phase,
            subtitle = subtitle,
            subtitleFromUser = subtitleFromUser,
            errorMessage = errorMessage,
            spectrum = spectrum,
            pipeline = pipeline,
            interfaceVisible = interfaceVisible,
            portraitLanded = portraitLanded,
            pipelineEnabled = interfaceVisible && (
                phase == LiveConversationPhase.Listening || phase == LiveConversationPhase.Error
            ),
            onPipelineChange = { selected ->
                if (selected != pipeline) {
                    scope.launch {
                        val validationError = onValidatePipeline(selected)
                        if (validationError != null) {
                            errorMessage = validationError
                            phase = LiveConversationPhase.Error
                            return@launch
                        }
                        onStopRealtimeTurn()
                        audioController.release()
                        pipeline = selected
                        ServiceContainer.prefs.livePipelineMode = selected
                        phase = LiveConversationPhase.Connecting
                        delay(180)
                        beginListening(selected)
                    }
                }
            },
            onPrimaryAction = {
                when (phase) {
                    LiveConversationPhase.Listening -> {
                        soundEffects.playFinishSpeaking()
                        finishRecordingAndSend()
                    }
                    LiveConversationPhase.Speaking -> {
                        onStopRealtimeTurn()
                        audioController.stopPlayback()
                        beginListening()
                    }
                    LiveConversationPhase.Thinking -> {
                        if (pipeline == LivePipelineMode.REALTIME) {
                            onStopRealtimeTurn()
                            audioController.stopPlayback()
                        } else {
                            onStopGeneration()
                        }
                        awaitingAssistant = false
                        errorMessage = context.getString(R.string.live_response_stopped)
                        phase = LiveConversationPhase.Error
                    }
                    LiveConversationPhase.Error -> beginListening()
                    else -> Unit
                }
            },
            onDismiss = {
                if (dismissing) return@LiveConversationContent
                dismissing = true
                soundEffects.playHangUp()
                onStopRealtimeTurn()
                audioController.release()
                scope.launch {
                    interfaceVisible = false
                    delay(LIVE_INTERFACE_TRANSITION_MS)
                    portraitLanded = false
                    delay(LIVE_PORTRAIT_TRANSITION_MS)
                    onDismiss()
                }
            }
        )
    }
}

@Composable
private fun LiveConversationContent(
    sessionName: String,
    portraitUrl: String?,
    phase: LiveConversationPhase,
    subtitle: String,
    subtitleFromUser: Boolean,
    errorMessage: String?,
    spectrum: FloatArray,
    pipeline: LivePipelineMode,
    interfaceVisible: Boolean,
    portraitLanded: Boolean,
    pipelineEnabled: Boolean,
    onPipelineChange: (LivePipelineMode) -> Unit,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background.copy(alpha = 1f)
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val subtitleScrollState = rememberScrollState()
    var pipelineMenuExpanded by remember { mutableStateOf(false) }
    val interfaceAlpha by animateFloatAsState(
        targetValue = if (interfaceVisible) 1f else 0f,
        animationSpec = tween(LIVE_INTERFACE_TRANSITION_MS.toInt(), easing = FastOutSlowInEasing),
        label = "live_interface_alpha"
    )
    val interfaceScale by animateFloatAsState(
        targetValue = if (interfaceVisible) 1f else 0.9f,
        animationSpec = tween(LIVE_INTERFACE_TRANSITION_MS.toInt(), easing = FastOutSlowInEasing),
        label = "live_interface_scale"
    )
    val portraitOffset by animateDpAsState(
        targetValue = if (portraitLanded) 0.dp else (-520).dp,
        animationSpec = tween(LIVE_PORTRAIT_TRANSITION_MS.toInt(), easing = FastOutSlowInEasing),
        label = "live_portrait_drop"
    )
    val interfaceModifier = Modifier.graphicsLayer {
        alpha = interfaceAlpha
        scaleX = interfaceScale
        scaleY = interfaceScale
    }
    LaunchedEffect(subtitleFromUser) {
        subtitleScrollState.scrollTo(0)
    }
    val status = when (phase) {
        LiveConversationPhase.Connecting -> stringResource(R.string.live_connecting)
        LiveConversationPhase.Listening -> stringResource(R.string.live_listening)
        LiveConversationPhase.Transcribing -> stringResource(R.string.live_transcribing)
        LiveConversationPhase.Thinking -> stringResource(R.string.live_thinking)
        LiveConversationPhase.PreparingAudio -> stringResource(R.string.live_preparing_audio)
        LiveConversationPhase.Speaking -> stringResource(R.string.live_speaking)
        LiveConversationPhase.Error -> stringResource(R.string.live_tap_to_continue)
    }
    val busy = phase in setOf(
        LiveConversationPhase.Connecting,
        LiveConversationPhase.Transcribing,
        LiveConversationPhase.PreparingAudio
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(background, primary.copy(alpha = 1f), 0.18f),
                        background,
                        surface
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(interfaceModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.live_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = sessionName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = pipelineEnabled) { pipelineMenuExpanded = true },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (pipeline == LivePipelineMode.CLASSIC) {
                                    stringResource(R.string.live_pipeline_classic_short)
                                } else {
                                    stringResource(R.string.live_pipeline_realtime_short)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (pipelineEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.live_pipeline_label),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = pipelineMenuExpanded,
                        onDismissRequest = { pipelineMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.live_pipeline_classic)) },
                            onClick = {
                                pipelineMenuExpanded = false
                                onPipelineChange(LivePipelineMode.CLASSIC)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.live_pipeline_realtime)) },
                            onClick = {
                                pipelineMenuExpanded = false
                                onPipelineChange(LivePipelineMode.REALTIME)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(0.72f))
            LivePortraitSpectrum(
                portraitUrl = portraitUrl,
                spectrum = spectrum,
                modifier = Modifier
                    .size(286.dp)
                    .offset(y = portraitOffset)
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = interfaceModifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = primary
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (phase == LiveConversationPhase.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            Spacer(Modifier.weight(0.55f))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .then(interfaceModifier),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = if (subtitleFromUser) {
                            stringResource(R.string.live_you)
                        } else {
                            sessionName
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (subtitleFromUser) MaterialTheme.colorScheme.tertiary else primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(5.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(subtitleScrollState)
                    ) {
                        Text(
                            text = errorMessage ?: subtitle.ifBlank {
                                if (phase == LiveConversationPhase.Listening) {
                                    stringResource(R.string.live_listening_hint)
                                } else {
                                    stringResource(R.string.live_waiting_subtitle)
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (errorMessage != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveCallButton(
                    background = MaterialTheme.colorScheme.surfaceContainerHighest,
                    enabled = interfaceVisible && !busy,
                    onClick = onPrimaryAction
                ) {
                    Icon(
                        imageVector = when (phase) {
                            LiveConversationPhase.Listening -> Icons.Filled.Stop
                            LiveConversationPhase.Speaking -> Icons.Filled.Mic
                            else -> Icons.Filled.Mic
                        },
                        contentDescription = stringResource(R.string.live_primary_action),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                LiveCallButton(
                    background = Color(0xFFE84A5F),
                    enabled = interfaceVisible,
                    onClick = onDismiss
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = stringResource(R.string.live_hang_up),
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(34.dp))
        }
    }
}

@Composable
private fun LivePortraitSpectrum(
    portraitUrl: String?,
    spectrum: FloatArray,
    modifier: Modifier = Modifier
) {
    val peak = spectrum.maxOrNull()?.coerceIn(0f, 1f) ?: 0f
    val portraitScale by animateFloatAsState(
        targetValue = 1f + peak * 0.045f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "live_portrait_scale"
    )
    val primary = MaterialTheme.colorScheme.primary

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val resolved = resolveAvatarUrl(portraitUrl)
        Surface(
            modifier = Modifier
                .size(196.dp)
                .graphicsLayer {
                    scaleX = portraitScale
                    scaleY = portraitScale
                }
                .shadow(18.dp, CircleShape)
                .border(3.dp, primary.copy(alpha = 0.82f), CircleShape)
                .clip(CircleShape),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (resolved.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(108.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(resolved)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun LiveCallButton(
    background: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(background)
    ) {
        content()
    }
}

private class LiveConversationSoundEffects {
    private val lock = Any()
    private var activeTrack: AudioTrack? = null
    private var released = false

    fun playEntrance() {
        play(
            durationMs = 390,
            notes = listOf(
                LiveSoundNote(523.25, 0, 220, 0.18),
                LiveSoundNote(659.25, 88, 225, 0.16),
                LiveSoundNote(783.99, 172, 210, 0.14)
            )
        )
    }

    fun playFinishSpeaking() {
        play(
            durationMs = 235,
            notes = listOf(
                LiveSoundNote(587.33, 0, 155, 0.19),
                LiveSoundNote(739.99, 82, 140, 0.17)
            )
        )
    }

    fun playHangUp() {
        play(
            durationMs = 255,
            notes = listOf(
                LiveSoundNote(493.88, 0, 155, 0.17),
                LiveSoundNote(392.00, 82, 155, 0.15)
            )
        )
    }

    fun release() {
        synchronized(lock) {
            released = true
            activeTrack?.let(::stopAndRelease)
            activeTrack = null
        }
    }

    private fun play(durationMs: Int, notes: List<LiveSoundNote>) {
        val samples = createSamples(durationMs, notes)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(LIVE_SOUND_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        synchronized(lock) {
            if (released) {
                stopAndRelease(track)
                return
            }
            activeTrack?.let(::stopAndRelease)
            activeTrack = track
        }

        Thread({
            try {
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                Thread.sleep(durationMs.toLong() + LIVE_SOUND_RELEASE_PADDING_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // 新音效触发时可能会提前释放旧音轨。
            } finally {
                synchronized(lock) {
                    if (activeTrack === track) {
                        stopAndRelease(track)
                        activeTrack = null
                    }
                }
            }
        }, "live-sound-effect").apply {
            isDaemon = true
            start()
        }
    }

    private fun createSamples(durationMs: Int, notes: List<LiveSoundNote>): ShortArray {
        val sampleCount = durationMs * LIVE_SOUND_SAMPLE_RATE / 1_000
        return ShortArray(sampleCount) { index ->
            val timeSeconds = index.toDouble() / LIVE_SOUND_SAMPLE_RATE
            var mix = 0.0
            notes.forEach { note ->
                val elapsedSeconds = timeSeconds - note.startMs / 1_000.0
                val noteDurationSeconds = note.durationMs / 1_000.0
                if (elapsedSeconds in 0.0..noteDurationSeconds) {
                    val attack = (elapsedSeconds / LIVE_SOUND_ATTACK_SECONDS).coerceAtMost(1.0)
                    val release = ((noteDurationSeconds - elapsedSeconds) / LIVE_SOUND_RELEASE_SECONDS)
                        .coerceIn(0.0, 1.0)
                    val phase = 2.0 * Math.PI * note.frequencyHz * elapsedSeconds
                    val tone = sin(phase) * 0.86 + sin(phase * 2.0) * 0.14
                    mix += tone * note.gain * attack * release
                }
            }
            (mix.coerceIn(-0.72, 0.72) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun stopAndRelease(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private data class LiveSoundNote(
        val frequencyHz: Double,
        val startMs: Int,
        val durationMs: Int,
        val gain: Double
    )
}

internal fun liveSubtitleWindow(text: String, limit: Int = Int.MAX_VALUE): String =
    text.trim().let { normalized ->
        if (normalized.length <= limit) normalized else normalized.takeLast(limit)
    }

private class LiveAudioController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSpectrum: (FloatArray) -> Unit,
    private val onAutoStop: () -> Unit,
    private val onPlaybackComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var recorder: MediaRecorder? = null
    private var realtimeRecorder: AudioRecord? = null
    private var realtimeAgc: AutomaticGainControl? = null
    private var realtimeNs: NoiseSuppressor? = null
    private var realtimeAec: AcousticEchoCanceler? = null
    private var realtimeRecordingBuffer: ByteArrayOutputStream? = null
    private val realtimeRecordingLock = Any()
    private var recordingFile: File? = null
    private var amplitudeJob: Job? = null
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var fallbackSpectrumJob: Job? = null
    private var realtimePlayer: AudioTrack? = null
    private var realtimePlaybackChannel: Channel<ByteArray>? = null
    private var realtimePlaybackJob: Job? = null

    fun startRecording(): Boolean {
        stopPlayback()
        cancelRecording()
        return try {
            val file = File(context.cacheDir, "live_${System.currentTimeMillis()}.m4a")
            val nextRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recordingFile = file
            recorder = nextRecorder
            nextRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startAmplitudeCapture(nextRecorder)
            true
        } catch (error: Exception) {
            cancelRecording()
            onError(error.message ?: context.getString(R.string.live_recording_failed))
            false
        }
    }

    fun startRealtimeRecording(): Boolean {
        stopPlayback()
        cancelRecording()
        return try {
            // 录音使用 16000Hz（Qwen Realtime 官方默认输入采样率，更稳定）
            val minBuffer = AudioRecord.getMinBufferSize(
                REALTIME_AUDIO_INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4_096)
            val active = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                REALTIME_AUDIO_INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            check(active.state == AudioRecord.STATE_INITIALIZED) { "无法初始化 Realtime 录音器" }
            // 应用音频增强效果：自动增益、噪声抑制、回声消除
            val audioSessionId = active.audioSessionId
            if (AutomaticGainControl.isAvailable()) {
                realtimeAgc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                realtimeNs = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                realtimeAec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
            val output = ByteArrayOutputStream()
            realtimeRecorder = active
            synchronized(realtimeRecordingLock) { realtimeRecordingBuffer = output }
            active.startRecording()
            amplitudeJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBuffer)
                val startedAt = SystemClock.elapsedRealtime()
                var lastVoiceAt = startedAt
                var voiceDetected = false
                var tick = 0
                while (isActive && realtimeRecorder === active) {
                    val count = active.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    synchronized(realtimeRecordingLock) {
                        if (realtimeRecordingBuffer === output) output.write(buffer, 0, count)
                    }
                    val level = pcm16Level(buffer, count)
                    scope.launch { onSpectrum(spectrumFromLevel(level, tick++)) }
                    val now = SystemClock.elapsedRealtime()
                    if (level >= LIVE_VOICE_THRESHOLD) {
                        voiceDetected = true
                        lastVoiceAt = now
                    }
                    val elapsed = now - startedAt
                    if (
                        elapsed >= LIVE_MAX_RECORDING_MS ||
                        (voiceDetected && elapsed >= LIVE_MIN_RECORDING_MS && now - lastVoiceAt >= LIVE_SILENCE_MS)
                    ) {
                        scope.launch { onAutoStop() }
                        break
                    }
                }
            }
            true
        } catch (error: Exception) {
            cancelRecording()
            onError(error.message ?: context.getString(R.string.live_recording_failed))
            false
        }
    }

    fun finishRecording(): File? {
        amplitudeJob?.cancel()
        amplitudeJob = null
        val currentRecorder = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        val stopped = runCatching { currentRecorder?.stop() }.isSuccess
        runCatching { currentRecorder?.release() }
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
        if (!stopped || file == null || !file.exists() || file.length() < 512L) {
            file?.delete()
            return null
        }
        return file
    }

    fun finishRealtimeRecording(): ByteArray? {
        val active = realtimeRecorder
        realtimeRecorder = null
        runCatching { active?.stop() }
        amplitudeJob?.cancel()
        amplitudeJob = null
        runCatching { active?.release() }
        // 释放音频增强效果
        runCatching { realtimeAgc?.release() }
        runCatching { realtimeNs?.release() }
        runCatching { realtimeAec?.release() }
        realtimeAgc = null
        realtimeNs = null
        realtimeAec = null
        val bytes = synchronized(realtimeRecordingLock) {
            realtimeRecordingBuffer?.toByteArray().also { realtimeRecordingBuffer = null }
        }
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
        // 最小音频长度约 0.25 秒（16000Hz × 0.25s × 2 bytes = 8000 bytes）
        return bytes?.takeIf { it.size >= REALTIME_AUDIO_INPUT_SAMPLE_RATE / 2 }
    }

    fun cancelRecording() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
        realtimeRecorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        realtimeRecorder = null
        // 释放音频增强效果
        runCatching { realtimeAgc?.release() }
        runCatching { realtimeNs?.release() }
        runCatching { realtimeAec?.release() }
        realtimeAgc = null
        realtimeNs = null
        realtimeAec = null
        synchronized(realtimeRecordingLock) { realtimeRecordingBuffer = null }
        recordingFile?.delete()
        recordingFile = null
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
    }

    fun play(url: String, authToken: String?): Boolean {
        stopPlayback()
        return try {
            val nextPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
            }
            player = nextPlayer
            val headers = authToken
                ?.takeIf { it.isNotBlank() && url.startsWith("http", ignoreCase = true) }
                ?.let { mapOf("Authorization" to "Bearer $it") }
                .orEmpty()
            if (headers.isEmpty()) {
                nextPlayer.setDataSource(url)
            } else {
                nextPlayer.setDataSource(context, Uri.parse(url), headers)
            }
            nextPlayer.setOnPreparedListener { prepared ->
                runCatching {
                    prepared.start()
                    attachVisualizer(prepared)
                    startFallbackPlaybackSpectrum(prepared)
                }.onFailure { onError(it.message ?: context.getString(R.string.live_playback_failed)) }
            }
            nextPlayer.setOnCompletionListener {
                stopPlayback(releasePlayer = true)
                onPlaybackComplete()
            }
            nextPlayer.setOnErrorListener { _, _, _ ->
                stopPlayback(releasePlayer = true)
                onError(context.getString(R.string.live_playback_failed))
                true
            }
            nextPlayer.prepareAsync()
            true
        } catch (error: Exception) {
            stopPlayback(releasePlayer = true)
            onError(error.message ?: context.getString(R.string.live_playback_failed))
            false
        }
    }

    fun startRealtimePlayback(): Boolean {
        stopPlayback()
        return try {
            // 播放使用 24000Hz（Qwen Realtime 官方默认输出采样率）
            val minBuffer = AudioTrack.getMinBufferSize(
                REALTIME_AUDIO_OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8_192)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(REALTIME_AUDIO_OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化 Realtime 播放器" }
            val channel = Channel<ByteArray>(Channel.UNLIMITED)
            realtimePlayer = track
            realtimePlaybackChannel = channel
            track.play()
            realtimePlaybackJob = scope.launch(Dispatchers.IO) {
                var totalBytes = 0L
                try {
                    for (chunk in channel) {
                        var offset = 0
                        while (offset < chunk.size && isActive && realtimePlayer === track) {
                            val written = track.write(chunk, offset, chunk.size - offset)
                            if (written <= 0) break
                            offset += written
                            totalBytes += written
                        }
                        scope.launch { onSpectrum(spectrumFromPcm16(chunk)) }
                    }
                    val totalFrames = totalBytes / 2L
                    val timeoutAt = SystemClock.elapsedRealtime() +
                        (totalFrames * 1_000L / REALTIME_AUDIO_OUTPUT_SAMPLE_RATE) + 1_500L
                    while (
                        isActive && realtimePlayer === track &&
                        track.playbackHeadPosition.toLong() < totalFrames &&
                        SystemClock.elapsedRealtime() < timeoutAt
                    ) {
                        delay(24)
                    }
                    if (realtimePlayer === track) {
                        realtimePlayer = null
                        realtimePlaybackChannel = null
                        runCatching { track.stop() }
                        runCatching { track.release() }
                        scope.launch {
                            onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
                            onPlaybackComplete()
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // 主动停止播放。
                }
            }
            true
        } catch (error: Exception) {
            stopPlayback()
            onError(error.message ?: context.getString(R.string.live_playback_failed))
            false
        }
    }

    fun writeRealtimeAudio(pcm16: ByteArray) {
        if (pcm16.isNotEmpty()) realtimePlaybackChannel?.trySend(pcm16.copyOf())
    }

    fun finishRealtimePlayback() {
        realtimePlaybackChannel?.close()
    }

    fun stopPlayback(releasePlayer: Boolean = true) {
        fallbackSpectrumJob?.cancel()
        fallbackSpectrumJob = null
        visualizer?.let { runCatching { it.release() } }
        visualizer = null
        realtimePlaybackChannel?.close()
        realtimePlaybackChannel = null
        realtimePlaybackJob?.cancel()
        realtimePlaybackJob = null
        realtimePlayer?.let { active ->
            runCatching { active.pause() }
            runCatching { active.flush() }
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        realtimePlayer = null
        if (releasePlayer) {
            player?.let { active ->
                runCatching { active.stop() }
                runCatching { active.release() }
            }
            player = null
        }
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }

    private fun startAmplitudeCapture(activeRecorder: MediaRecorder) {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var lastVoiceAt = startedAt
            var voiceDetected = false
            var smoothLevel = 0f
            var tick = 0
            while (isActive && recorder === activeRecorder) {
                delay(80)
                val raw = runCatching { activeRecorder.maxAmplitude }.getOrDefault(0)
                val normalized = sqrt((raw / 32_767f).coerceIn(0f, 1f))
                smoothLevel = smoothLevel * 0.62f + normalized * 0.38f
                onSpectrum(spectrumFromLevel(smoothLevel, tick++))
                val now = SystemClock.elapsedRealtime()
                if (normalized >= LIVE_VOICE_THRESHOLD) {
                    voiceDetected = true
                    lastVoiceAt = now
                }
                val elapsed = now - startedAt
                if (
                    elapsed >= LIVE_MAX_RECORDING_MS ||
                    (voiceDetected && elapsed >= LIVE_MIN_RECORDING_MS && now - lastVoiceAt >= LIVE_SILENCE_MS)
                ) {
                    onAutoStop()
                    break
                }
            }
        }
    }

    private fun attachVisualizer(activePlayer: MediaPlayer) {
        visualizer?.let { runCatching { it.release() } }
        visualizer = runCatching {
            Visualizer(activePlayer.audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (waveform != null && waveform.isNotEmpty()) {
                                scope.launch { onSpectrum(spectrumFromWaveform(waveform)) }
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) = Unit
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )
                enabled = true
            }
        }.getOrNull()
    }

    private fun startFallbackPlaybackSpectrum(activePlayer: MediaPlayer) {
        fallbackSpectrumJob?.cancel()
        fallbackSpectrumJob = scope.launch {
            var tick = 0
            while (isActive && player === activePlayer && runCatching { activePlayer.isPlaying }.getOrDefault(false)) {
                delay(90)
                if (visualizer == null) {
                    val position = runCatching { activePlayer.currentPosition }.getOrDefault(tick * 90)
                    val level = 0.38f + abs(sin(position / 170f)) * 0.46f
                    onSpectrum(spectrumFromLevel(level, tick))
                }
                tick++
            }
        }
    }
}

private fun spectrumFromLevel(level: Float, tick: Int): FloatArray =
    FloatArray(LIVE_SPECTRUM_BARS) { index ->
        val variation = 0.34f + abs(sin(index * 0.73f + tick * 0.21f)) * 0.66f
        (0.06f + level.coerceIn(0f, 1f) * variation).coerceIn(0.06f, 1f)
    }

private fun spectrumFromWaveform(waveform: ByteArray): FloatArray {
    val groupSize = (waveform.size / LIVE_SPECTRUM_BARS).coerceAtLeast(1)
    return FloatArray(LIVE_SPECTRUM_BARS) { index ->
        val start = index * groupSize
        val end = (start + groupSize).coerceAtMost(waveform.size)
        if (start >= end) return@FloatArray 0.06f
        var peak = 0f
        for (sampleIndex in start until end) {
            val centered = abs((waveform[sampleIndex].toInt() and 0xFF) - 128) / 128f
            if (centered > peak) peak = centered
        }
        (0.06f + sqrt(peak.coerceIn(0f, 1f)) * 0.94f).coerceIn(0.06f, 1f)
    }
}

private fun pcm16Level(bytes: ByteArray, count: Int = bytes.size): Float {
    var peak = 0
    var index = 0
    val safeCount = count.coerceAtMost(bytes.size)
    while (index + 1 < safeCount) {
        val sample = (bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)
        peak = maxOf(peak, abs(sample.toShort().toInt()))
        index += 2
    }
    return sqrt((peak / 32_767f).coerceIn(0f, 1f))
}

private fun spectrumFromPcm16(bytes: ByteArray): FloatArray {
    val sampleCount = bytes.size / 2
    val groupSize = (sampleCount / LIVE_SPECTRUM_BARS).coerceAtLeast(1)
    return FloatArray(LIVE_SPECTRUM_BARS) { band ->
        val startSample = band * groupSize
        val endSample = (startSample + groupSize).coerceAtMost(sampleCount)
        var peak = 0
        for (sampleIndex in startSample until endSample) {
            val byteIndex = sampleIndex * 2
            val sample = (bytes[byteIndex].toInt() and 0xFF) or (bytes[byteIndex + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
        }
        (0.06f + sqrt((peak / 32_767f).coerceIn(0f, 1f)) * 0.94f).coerceIn(0.06f, 1f)
    }
}

private const val LIVE_SPECTRUM_BARS = 44
private const val LIVE_PORTRAIT_TRANSITION_MS = 580L
private const val LIVE_INTERFACE_TRANSITION_MS = 240L
private const val LIVE_SOUND_SAMPLE_RATE = 44_100
private const val LIVE_SOUND_RELEASE_PADDING_MS = 50L
private const val LIVE_SOUND_ATTACK_SECONDS = 0.016
private const val LIVE_SOUND_RELEASE_SECONDS = 0.072
private const val LIVE_VOICE_THRESHOLD = 0.075f
private const val LIVE_MIN_RECORDING_MS = 900L
private const val LIVE_SILENCE_MS = 1_250L
private const val LIVE_MAX_RECORDING_MS = 60_000L
/** Realtime 录音采样率（Qwen 官方默认输入 16000Hz，更稳定）。 */
private const val REALTIME_AUDIO_INPUT_SAMPLE_RATE = 16_000
/** Realtime 播放采样率（Qwen 官方默认输出 24000Hz）。 */
private const val REALTIME_AUDIO_OUTPUT_SAMPLE_RATE = 24_000
