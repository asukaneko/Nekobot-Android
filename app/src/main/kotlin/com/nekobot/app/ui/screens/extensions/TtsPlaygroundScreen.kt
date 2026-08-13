package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalAudioResult
import com.nekobot.app.data.model.TtsPreviewRequest
import com.nekobot.app.data.model.TtsVoice
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.screens.chat.AudioRenderer
import com.nekobot.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TTS 试验场 ViewModel：管理音色列表加载与语音预览生成。
 */
class TtsPlaygroundViewModel : BaseViewModel() {

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    /** 远程模式：预览音频 URL；本地模式不使用 */
    private val _previewUrl = MutableStateFlow<String?>(null)
    val previewUrl: StateFlow<String?> = _previewUrl.asStateFlow()

    /** 本地模式：合成的音频缓存结果（含 cacheUri + 实际使用模型） */
    private val _localAudio = MutableStateFlow<LocalAudioResult?>(null)
    val localAudio: StateFlow<LocalAudioResult?> = _localAudio.asStateFlow()

    init {
        load()
    }

    /** 加载音色列表 */
    fun load() {
        launchResult(
            block = { unified.listTtsVoices() },
            onSuccess = { _voices.value = it ?: emptyList() }
        )
    }

    /** 生成语音预览：本地模式走 synthesizeAudio 播放缓存 URI，远程模式走 ttsPreview 返回 URL */
    fun preview(text: String, voice: String, speed: Float, pitch: Float, volume: Float) {
        // 清空上一次结果
        _previewUrl.value = null
        _localAudio.value = null
        if (isLocalMode) {
            launchResult(
                block = {
                    unified.synthesizeAudio(
                        text = text,
                        voice = voice,
                        speed = speed,
                        pitch = pitch,
                        volume = volume
                    )
                },
                onSuccess = { res -> _localAudio.value = res },
                onError = { msg -> showToast(msg) }
            )
        } else {
            val req = TtsPreviewRequest(
                text = text,
                voice = voice,
                speed = speed,
                pitch = pitch,
                volume = volume
            )
            launchResult(
                block = { unified.ttsPreview(req) },
                onSuccess = { res ->
                    _previewUrl.value = res.audioUrl
                    if (res.audioUrl.isNullOrBlank()) {
                        showToast(res.message ?: string(R.string.tts_no_audio_url))
                    }
                }
            )
        }
    }
}

/**
 * TTS 试验场页：顶部试验区生成预览音频地址，下方列出所有音色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsPlaygroundScreen(onBack: () -> Unit) {
    val vm: TtsPlaygroundViewModel = viewModel()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val previewUrl by vm.previewUrl.collectAsStateWithLifecycle()
    val localAudio by vm.localAudio.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 试验区表单状态
    val defaultText = stringResource(R.string.tts_default_test_text)
    var text by remember { mutableStateOf(defaultText) }
    var selectedVoice by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(1.0f) }
    var pitch by remember { mutableStateOf(1.0f) }
    var volume by remember { mutableStateOf(1.0f) }

    // 音色列表加载后默认选中第一个
    LaunchedEffect(voices) {
        if (selectedVoice.isBlank() && voices.isNotEmpty()) {
            selectedVoice = voices.first().id
        }
    }

    // 模式切换时自动刷新
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(appMode) { vm.load() }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.tts_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 错误提示
                error?.let {
                    ErrorBanner(message = it, onRetry = {
                        vm.clearError()
                        vm.load()
                    })
                }

                // 试验区
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = stringResource(R.string.tts_voice_preview), subtitle = stringResource(R.string.tts_voice_preview_subtitle))
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.tts_text_content)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(8.dp))

                    // 音色选择下拉
                    TtsVoiceDropdown(
                        voices = voices,
                        selected = selectedVoice,
                        onSelect = { selectedVoice = it }
                    )
                    Spacer(Modifier.height(8.dp))

                    // 语速 / 音调 / 音量滑块
                    SliderField(label = stringResource(R.string.tts_speed), value = speed, onValueChange = { speed = it })
                    Spacer(Modifier.height(8.dp))
                    SliderField(label = stringResource(R.string.tts_pitch), value = pitch, onValueChange = { pitch = it })
                    Spacer(Modifier.height(8.dp))
                    SliderField(label = stringResource(R.string.tts_volume), value = volume, onValueChange = { volume = it })

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val voice = selectedVoice.ifBlank { voices.firstOrNull()?.id ?: "" }
                            when {
                                text.isBlank() -> vm.showToast(context.getString(R.string.tts_prompt_input_text))
                                voice.isBlank() -> vm.showToast(context.getString(R.string.tts_prompt_select_voice))
                                else -> vm.preview(text, voice, speed, pitch, volume)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.tts_generate_preview), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                // 预览结果：本地模式播放缓存音频，远程模式显示 URL
                localAudio?.let { audio ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.tts_preview_result))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tts_model_label, audio.usedModelName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        AudioRenderer(url = audio.cacheUri, modifier = Modifier.fillMaxWidth())
                    }
                } ?: previewUrl?.let { url ->
                    if (url.isNotBlank()) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = stringResource(R.string.tts_preview_result))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(url))
                                    Toast.makeText(context, context.getString(R.string.tts_audio_url_copied), Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.tts_copy_url), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                // 音色列表
                SectionHeader(title = stringResource(R.string.tts_voice_list), subtitle = stringResource(R.string.tts_voice_count, voices.size))
                if (voices.isEmpty() && !loading) {
                    EmptyState(title = stringResource(R.string.tts_no_voices))
                } else {
                    voices.forEach { voice ->
                        TtsVoiceItem(voice = voice)
                    }
                }
            }

            LoadingOverlay(visible = loading && voices.isEmpty())
        }
    }
}

/** 单个音色卡片：名称 + 自定义标记 + 提供商 + 描述 */
@Composable
private fun TtsVoiceItem(voice: TtsVoice) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = voice.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!voice.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = voice.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!voice.provider.isNullOrBlank()) {
                    Text(stringResource(R.string.tts_provider_label, voice.provider), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 自定义音色标记
            if (voice.custom) {
                Box(
                    modifier = Modifier
                        .background(SuccessGreen.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.tts_custom), style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                }
            }
        }
    }
}

/** 音色选择下拉框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsVoiceDropdown(
    voices: List<TtsVoice>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedVoice = voices.firstOrNull { it.id == selected }
    val displayValue = selectedVoice?.displayName ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.tts_voice)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (voices.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.tts_no_voices)) }, onClick = { expanded = false })
            } else {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice.displayName) },
                        onClick = {
                            onSelect(voice.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** 带数值显示的滑块字段 */
@Composable
private fun SliderField(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.0f..2.0f
        )
    }
}
