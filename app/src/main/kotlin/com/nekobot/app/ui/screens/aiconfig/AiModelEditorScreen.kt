package com.nekobot.app.ui.screens.aiconfig

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekobot.app.ui.BaseViewModel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import com.nekobot.app.ui.components.BorderlessAssistChip as AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.ai.ModelPricingEntry
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.ai.EMBEDDING_DIMENSION_PRESETS
import com.nekobot.app.data.local.ai.usesFixedEmbeddingDimensions
import com.nekobot.app.data.local.ai.validEmbeddingDimensionsInput
import com.nekobot.app.data.local.ai.REALTIME_OPENAI_VOICES
import com.nekobot.app.data.local.ai.REALTIME_GLM_DEFAULT_BASE_URL
import com.nekobot.app.data.local.ai.REALTIME_GLM_DEFAULT_MODEL
import com.nekobot.app.data.local.ai.REALTIME_GLM_DEFAULT_TRANSCRIPTION_MODEL
import com.nekobot.app.data.local.ai.REALTIME_GLM_DEFAULT_VOICE
import com.nekobot.app.data.local.ai.REALTIME_GLM_VOICES
import com.nekobot.app.data.local.ai.REALTIME_QWEN_VOICES
import com.nekobot.app.data.local.ai.REALTIME_QWEN_DEFAULT_MODEL
import com.nekobot.app.data.local.ai.REALTIME_QWEN_DEFAULT_BASE_URL
import com.nekobot.app.data.local.ai.REALTIME_QWEN_DEFAULT_VOICE
import com.nekobot.app.data.local.ai.REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL
import com.nekobot.app.data.local.ai.REALTIME_SEED_DEFAULT_BASE_URL
import com.nekobot.app.data.local.ai.REALTIME_SEED_DEFAULT_MODEL
import com.nekobot.app.data.local.ai.REALTIME_SEED_DEFAULT_TRANSCRIPTION_MODEL
import com.nekobot.app.data.local.ai.REALTIME_SEED_DEFAULT_VOICE
import com.nekobot.app.data.local.ai.REALTIME_SEED_VOICES
import com.nekobot.app.data.local.ai.REALTIME_STEPFUN_DEFAULT_BASE_URL
import com.nekobot.app.data.local.ai.REALTIME_STEPFUN_DEFAULT_MODEL
import com.nekobot.app.data.local.ai.REALTIME_STEPFUN_DEFAULT_VOICE
import com.nekobot.app.data.local.ai.REALTIME_STEPFUN_VOICES
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.AiModel
import com.nekobot.app.data.model.AiModelRequest
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.ui.components.GlassExposedDropdownMenu
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 远程和本地模型共用的编辑状态。字段与原仓库网页端 modelForm 保持一致，
 * 两个模式只在协议列表和最终持久化方式上有差异。
 */
data class AiModelEditorState(
    val name: String = "",
    val purpose: String = "chat",
    val provider: String = "openai",
    val protocol: String = "openai_compatible",
    val apiKey: String = "",
    val proxyUrl: String = "",
    val baseUrl: String = "",
    val appendBaseUrlPath: Boolean = true,
    val model: String = "gpt-5.5",
    val enabled: Boolean = true,
    val priority: String = "0",
    val temperature: String = "0.7",
    val maxTokens: String = "2000",
    val maxContextLength: String = "1050000",
    val topP: String = "",
    /** 停止字符串（换行分隔，也兼容 JSON 数组文本）。 */
    val stopSequences: String = "",
    val inputPrice: String = "",
    val outputPrice: String = "",
    /** 缓存命中价（每 1M tokens）；留空时按价格目录或输入价比例折算。 */
    val cacheReadPrice: String = "",
    /** 缓存写入价（每 1M tokens）；留空时按价格目录或输入价折算。 */
    val cacheWritePrice: String = "",
    val supportsTools: Boolean = true,
    val supportsReasoning: Boolean = true,
    val supportsStream: Boolean = true,
    val supportsVision: Boolean = false,
    val ttsProvider: String = "openai",
    val ttsUrl: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "alloy",
    val ttsSpeed: String = "1.0",
    val ttsPitch: String = "1.0",
    val ttsVolume: String = "1.0",
    val ttsFormat: String = "mp3",
    val ttsUploadUrl: String = "",
    val ttsHeaders: String = "",
    val ttsBodyTemplate: String = "",
    val ttsResourceId: String = "",
    val ttsRefAudio: String = "",
    val ttsUser: String = "",
    val language: String = "zh",
    val sttProvider: String = "",
    val sttUrl: String = "",
    val sttModel: String = "",
    val sttHeaders: String = "",
    val dimensions: String = "",
    val size: String = "1024x1024",
    val promptTemplate: String = ""
)

private data class ProviderPreset(
    val label: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val maxContextLength: String,
    val maxTokens: String,
    val inputPrice: String,
    val outputPrice: String,
    val labelRes: Int? = null,
    val purpose: String? = null,
    val voice: String? = null,
    val format: String? = null
)

private val LIVE_SEED_PROVIDERS = setOf("doubao", "seed", "volcengine", "bytedance", "ark", "volces")
private val LIVE_GLM_PROVIDERS = setOf("glm", "zhipu", "bigmodel")
private val LIVE_STEPFUN_PROVIDERS = setOf("stepfun", "stepfun-ai", "step")

private data class LiveRealtimeDefaults(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val voice: String,
    val transcriptionModel: String
)

private fun liveRealtimeDefaults(provider: String): LiveRealtimeDefaults = when (provider.lowercase()) {
    "gemini", "google", "google_gemini" -> LiveRealtimeDefaults(
        provider, com.nekobot.app.data.local.ai.REALTIME_GEMINI_DEFAULT_MODEL,
        "https://generativelanguage.googleapis.com/v1beta", "Kore", ""
    )
    "grok", "xai" -> LiveRealtimeDefaults(
        provider, com.nekobot.app.data.local.ai.REALTIME_GROK_DEFAULT_MODEL,
        "https://api.x.ai/v1", "eve", ""
    )
    "qwen", "dashscope", "tongyi" -> LiveRealtimeDefaults(
        provider = provider,
        model = REALTIME_QWEN_DEFAULT_MODEL,
        baseUrl = REALTIME_QWEN_DEFAULT_BASE_URL,
        voice = REALTIME_QWEN_DEFAULT_VOICE,
        transcriptionModel = REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL
    )
    in LIVE_SEED_PROVIDERS -> LiveRealtimeDefaults(
        provider = provider,
        model = REALTIME_SEED_DEFAULT_MODEL,
        baseUrl = REALTIME_SEED_DEFAULT_BASE_URL,
        voice = REALTIME_SEED_DEFAULT_VOICE,
        transcriptionModel = REALTIME_SEED_DEFAULT_TRANSCRIPTION_MODEL
    )
    in LIVE_GLM_PROVIDERS -> LiveRealtimeDefaults(
        provider = provider,
        model = REALTIME_GLM_DEFAULT_MODEL,
        baseUrl = REALTIME_GLM_DEFAULT_BASE_URL,
        voice = REALTIME_GLM_DEFAULT_VOICE,
        transcriptionModel = REALTIME_GLM_DEFAULT_TRANSCRIPTION_MODEL
    )
    in LIVE_STEPFUN_PROVIDERS -> LiveRealtimeDefaults(
        provider = provider,
        model = REALTIME_STEPFUN_DEFAULT_MODEL,
        baseUrl = REALTIME_STEPFUN_DEFAULT_BASE_URL,
        voice = REALTIME_STEPFUN_DEFAULT_VOICE,
        transcriptionModel = ""
    )
    else -> LiveRealtimeDefaults(
        provider = "openai",
        model = "gpt-realtime",
        baseUrl = "https://api.openai.com/v1",
        voice = "marin",
        transcriptionModel = "gpt-4o-mini-transcribe"
    )
}

private val providerPresets = listOf(
    ProviderPreset("OpenAI", "openai", "gpt-5.5", "https://api.openai.com/v1", "1050000", "128000", "", ""),
    ProviderPreset("Claude", "anthropic", "claude-opus-4.8", "https://api.anthropic.com", "1000000", "128000", "", ""),
    ProviderPreset("Gemini", "google", "gemini-3.5-flash", "https://generativelanguage.googleapis.com/v1beta", "1048576", "65536", "", ""),
    ProviderPreset("DeepSeek", "deepseek", "deepseek-v4-flash", "https://api.deepseek.com", "1000000", "131072", "", ""),
    ProviderPreset("GLM", "zhipu", "glm-5.1", "https://open.bigmodel.cn/api/paas/v4", "200000", "128000", "", ""),
    ProviderPreset("MiniMax", "minimax", "minimax-m2.7", "https://api.minimaxi.com/v1", "204800", "131072", "", ""),
    ProviderPreset("Grok", "grok", "grok-4.20", "https://api.x.ai/v1", "1000000", "131072", "", ""),
    ProviderPreset("Qwen", "qwen", "qwen3.7-max", "https://dashscope.aliyuncs.com/compatible-mode/v1", "1000000", "65536", "", ""),
    ProviderPreset(
        label = "Kimi",
        provider = "kimi",
        model = "kimi-k3",
        baseUrl = "https://api.moonshot.cn/v1",
        maxContextLength = "1048576",
        maxTokens = "131072",
        inputPrice = "",
        outputPrice = "",
        labelRes = R.string.aimodel_editor_provider_kimi
    ),
    ProviderPreset(
        label = "Qwen Image 3.0",
        provider = "qwen",
        model = "qwen-image-3.0",
        baseUrl = "https://dashscope.aliyuncs.com/api/v1",
        maxContextLength = "",
        maxTokens = "",
        inputPrice = "",
        outputPrice = "",
        labelRes = R.string.aimodel_editor_preset_qwen_image_3,
        purpose = "image_generation"
    ),
    ProviderPreset("Mimo", "xiaomi", "mimo-v2.5", "https://api.xiaomimimo.com/v1", "1048576", "131072", "", ""),
    ProviderPreset(
        label = "StepFun",
        provider = "stepfun",
        model = "step-5-preview",
        baseUrl = "https://api.stepfun.com/v1",
        maxContextLength = "1000000",
        maxTokens = "",
        inputPrice = "",
        outputPrice = "",
        labelRes = R.string.aimodel_editor_provider_stepfun
    ),
    ProviderPreset("豆包 Seed", "doubao", "doubao-seedream-4-0-250828", "https://ark.cn-beijing.volces.com/api/v3", "", "", "", "")
)

@Composable
private fun rememberPurposeLabels(): LinkedHashMap<String, String> = linkedMapOf(
    "chat" to stringResource(R.string.aimodel_editor_purpose_chat),
    "live" to stringResource(R.string.aimodel_editor_purpose_live),
    "vision" to stringResource(R.string.aimodel_editor_purpose_vision),
    "video" to stringResource(R.string.aimodel_editor_purpose_video),
    "tts" to stringResource(R.string.aimodel_editor_purpose_tts),
    "stt" to stringResource(R.string.aimodel_editor_purpose_stt),
    "embedding" to stringResource(R.string.aimodel_editor_purpose_embedding),
    "image_generation" to stringResource(R.string.aimodel_editor_purpose_image_generation)
)

@Composable
private fun rememberProviderLabels(): LinkedHashMap<String, String> = linkedMapOf(
    "openai" to stringResource(R.string.aimodel_editor_provider_openai),
    "anthropic" to stringResource(R.string.aimodel_editor_provider_anthropic),
    "google" to stringResource(R.string.aimodel_editor_provider_google),
    "deepseek" to stringResource(R.string.aimodel_editor_provider_deepseek),
    "zhipu" to stringResource(R.string.aimodel_editor_provider_zhipu),
    "minimax" to stringResource(R.string.aimodel_editor_provider_minimax),
    "grok" to stringResource(R.string.aimodel_editor_provider_grok),
    "qwen" to stringResource(R.string.aimodel_editor_provider_qwen),
    "kimi" to stringResource(R.string.aimodel_editor_provider_kimi),
    "xiaomi" to stringResource(R.string.aimodel_editor_provider_xiaomi),
    "doubao" to stringResource(R.string.aimodel_editor_provider_doubao),
    "stepfun" to stringResource(R.string.aimodel_editor_provider_stepfun),
    "azure" to stringResource(R.string.aimodel_editor_provider_azure),
    "siliconflow" to stringResource(R.string.aimodel_editor_provider_siliconflow),
    "elevenlabs" to stringResource(R.string.aimodel_editor_provider_elevenlabs),
    "deepgram" to stringResource(R.string.aimodel_editor_provider_deepgram),
    "groq" to stringResource(R.string.aimodel_editor_provider_groq),
    "stability" to stringResource(R.string.aimodel_editor_provider_stability),
    "custom" to stringResource(R.string.aimodel_editor_provider_custom)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiModelEditorScreen(
    initial: AiModelEditorState,
    isEditing: Boolean,
    protocols: List<ProtocolOption>,
    purposes: List<String>,
    availableModels: List<String>,
    savedApiKeys: List<ApiKey>,
    showProxyConfig: Boolean = false,
    fixedPurpose: Boolean = false,
    busy: Boolean = false,
    actionError: String? = null,
    onResolveApiKey: (String, (String) -> Unit) -> Unit,
    onFetchModels: (
        baseUrl: String,
        apiKey: String,
        protocol: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String
    ) -> Unit,
    onConfirm: (AiModelEditorState) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pricingViewModel: ModelPricingCatalogViewModel = viewModel()
    val pricingState by pricingViewModel.state.collectAsStateWithLifecycle()
    val purposeLabels = rememberPurposeLabels()
    val providerLabels = rememberProviderLabels()
    val openaiCompatibleLabel = stringResource(R.string.aimodel_editor_protocol_openai_compatible)
    val protocolOptions = remember(protocols) {
        protocols.ifEmpty {
            LocalProtocols.names().map { ProtocolOption(it, it) }
                .ifEmpty { listOf(ProtocolOption("openai_compatible", openaiCompatibleLabel)) }
        }
    }
    val purposeOptions = remember(purposes, fixedPurpose, initial.purpose, purposeLabels) {
        if (fixedPurpose) listOf(initial.purpose) else (purposeLabels.keys + purposes).distinct()
    }
    val presetConfigTemplate = stringResource(R.string.aimodel_editor_preset_config_name)
    val purposeNameTemplate = stringResource(R.string.aimodel_editor_default_name_purpose)
    val draftKey = rememberSaveable { UUID.randomUUID().toString() }
    val draft: AiModelEditorDraft = viewModel(key = draftKey, factory = viewModelFactory {
        initializer { AiModelEditorDraft(initial.ensureProtocol(protocolOptions), isEditing) }
    })
    var state by draft.state
    var customDimensions by draft.customDimensions
    var validationError by remember { mutableStateOf<String?>(null) }
    var selectedSavedKeyId by draft.selectedSavedKeyId
    var allowAutomaticPricing by draft.allowAutomaticPricing
    val matchedPricing = remember(
        state.model,
        state.provider,
        pricingState.snapshot
    ) {
        ModelPricingCatalog.find(
            modelName = state.model,
            provider = state.provider,
            catalog = pricingState.snapshot
        )
    }

    LaunchedEffect(matchedPricing?.id, pricingState.snapshot.updatedAt, allowAutomaticPricing) {
        if (allowAutomaticPricing && matchedPricing != null) {
            state = state.applyPricing(matchedPricing, overwrite = false)
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(context.getString(R.string.aimodel_editor_audio_read_error))
            require(bytes.size <= 7.5 * 1024 * 1024) { context.getString(R.string.aimodel_editor_audio_too_large) }
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }.onSuccess {
            state = state.copy(ttsRefAudio = it)
            validationError = null
        }.onFailure {
            validationError = it.message ?: context.getString(R.string.aimodel_editor_audio_load_failed)
        }
    }

    var step by draft.step
    var attemptedStep by draft.attemptedStep
    var fetchedConnection by draft.fetchedConnection
    val currentConnection = listOf(state.baseUrl, state.apiKey, state.protocol, state.appendBaseUrlPath.toString(), state.proxyUrl)
    val issues = remember(state, showProxyConfig, customDimensions) { state.validationIssues(showProxyConfig, customDimensions) }
    fun fieldError(field: String): String? = issues.firstOrNull { it.field == field && it.step <= attemptedStep }
        ?.let { context.getString(it.message) }
    AiModelEditorLayout(
        isEditing = isEditing, step = step, busy = busy, hasChanges = state != initial.ensureProtocol(protocolOptions),
        error = actionError ?: validationError ?: issues.firstOrNull { it.step == step && it.step <= attemptedStep }?.let { context.getString(it.message) },
        onStep = { step = it; validationError = null },
        onBack = onDismiss,
        onNext = {
            validationError = null
            attemptedStep = step
            val issue = issues.firstOrNull { it.step <= step }
            if (issue != null) step = issue.step
            else if (step < 2) step++
            else onConfirm(state.copy(name = state.name.trim(), baseUrl = state.baseUrl.trim(),
                apiKey = state.apiKey.trim(), model = state.model.trim(), proxyUrl = state.proxyUrl.trim()))
        }
    ) {
        when (step) {
            0 -> {
                ModelEditorSection(stringResource(R.string.aimodel_editor_field_purpose)) {
                    if (purposeOptions.size == 1) Text(purposeLabels[state.purpose] ?: state.purpose)
                    else EditorDropdownField(
                        label = stringResource(R.string.aimodel_editor_field_purpose),
                        value = state.purpose,
                        options = purposeOptions,
                        labelFor = { purposeLabels[it] ?: it },
                        onSelect = { state = state.applyPurpose(it, isEditing, purposeLabels, purposeNameTemplate) }
                    )
                    if (state.purpose == "embedding") {
                        Text(
                            stringResource(R.string.companion_embedding_free_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ModelEditorSection(stringResource(R.string.aimodel_editor_section_quick_preset)) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val mediaPresets = com.nekobot.app.data.local.ai.mediaModelPresets(state.purpose)
                        val presets = if (state.purpose == "embedding") embeddingPresets else if (mediaPresets.isEmpty()) providerPresets.filter {
                            it.purpose == null && if (state.purpose == "live") {
                                it.provider in setOf("openai", "google", "grok", "qwen", "doubao", "zhipu", "stepfun")
                            } else it.provider != "doubao"
                        } else mediaPresets.map {
                            ProviderPreset(providerLabels[it.provider] ?: it.provider, it.provider, it.model,
                                it.baseUrl, "32000", "4096", "", "", purpose = state.purpose, voice = it.voice, format = it.format)
                        }
                        presets.forEach { preset ->
                            AssistChip(
                                onClick = {
                                    allowAutomaticPricing = true
                                    val presetLabel = preset.labelRes?.let(context::getString) ?: preset.label
                                    val previousName = state.name
                                    state = state.applyPreset(
                                        preset,
                                        protocolOptions,
                                        presetConfigTemplate,
                                        presetLabel
                                    ).copy(name = previousName.ifBlank { presetConfigTemplate.format(presetLabel) })
                                },
                                label = {
                                    Text(preset.labelRes?.let { stringResource(it) } ?: preset.label)
                                }
                            )
                        }
                    }

                }
                ModelEditorSection(stringResource(R.string.model_setup_identity)) {
                    EditorTextField(stringResource(R.string.aimodel_editor_field_config_name), state.name,
                        error = fieldError("name")) { state = state.copy(name = it) }
                    EditorDropdownField(
                        label = stringResource(R.string.aimodel_editor_field_provider),
                        value = state.provider,
                        options = providerLabels.keys.toList(),
                        labelFor = { providerLabels[it] ?: it },
                        // 切换服务商仅更新服务商字段，不再套用预设覆盖已填写的配置内容；
                        // 需要一键填入默认参数请使用上方的“快速预设”。
                        onSelect = { provider ->
                            allowAutomaticPricing = true
                            state = state.copy(provider = provider)
                        }
                    )

                }
            }
            1 -> {
                if (state.purpose == "stt") ModelEditorSection(stringResource(R.string.aimodel_editor_section_stt)) {
                    val sttProviderLabels = mapOf(
                        "" to stringResource(R.string.aimodel_editor_stt_provider_follow),
                        "openai" to stringResource(R.string.aimodel_editor_stt_provider_openai),
                        "gemini" to stringResource(R.string.aimodel_editor_stt_provider_gemini),
                        "glm" to stringResource(R.string.aimodel_editor_stt_provider_glm),
                        "qwen" to stringResource(R.string.aimodel_editor_stt_provider_qwen),
                        "siliconflow" to stringResource(R.string.aimodel_editor_provider_siliconflow),
                        "groq" to stringResource(R.string.aimodel_editor_provider_groq),
                        "elevenlabs" to stringResource(R.string.aimodel_editor_provider_elevenlabs),
                        "deepgram" to stringResource(R.string.aimodel_editor_provider_deepgram),
                        "xiaomi" to stringResource(R.string.aimodel_editor_stt_provider_xiaomi),
                        "local" to stringResource(R.string.aimodel_editor_stt_provider_local)
                    )
                    EditorDropdownField(
                        label = stringResource(R.string.aimodel_editor_stt_provider),
                        value = state.sttProvider,
                        options = sttProviderLabels.keys.toList(),
                        labelFor = { sttProviderLabels[it] ?: it },
                        onSelect = { state = state.copy(sttProvider = it) }
                    )
                }
                if (!(state.purpose == "stt" && state.sttProvider == "local")) {
                    ModelEditorSection(stringResource(R.string.model_setup_connection)) {
                        EditorDropdownField(
                            label = stringResource(R.string.aimodel_editor_field_provider_type),
                            value = state.protocol,
                            options = protocolOptions.map { it.key },
                            labelFor = { key -> protocolOptions.firstOrNull { it.key == key }?.displayName ?: key },
                            onSelect = { state = state.copy(protocol = it) }
                        )
                        EditorTextField(
                            label = if (state.purpose == "image_generation") stringResource(R.string.aimodel_editor_field_full_url)
                            else stringResource(R.string.aimodel_editor_field_base_url),
                            value = state.baseUrl,
                            error = fieldError("baseUrl"),
                            placeholder = if (state.purpose == "image_generation") {
                                "https://api.openai.com/v1/images/generations"
                            } else if (state.purpose == "live") {
                                "https://api.openai.com/v1"
                            } else {
                                "https://api.example.com/v1"
                            }
                        ) { state = state.copy(baseUrl = it) }
                        EditorSwitch(stringResource(R.string.aimodel_editor_field_append_base_url), state.appendBaseUrlPath) {
                            state = state.copy(appendBaseUrlPath = it)
                        }

                        EditorTextField(
                            label = stringResource(R.string.aimodel_editor_field_api_key),
                            value = state.apiKey,
                            error = fieldError("apiKey"),
                            password = true,
                            placeholder = stringResource(R.string.aimodel_editor_field_api_key_placeholder)
                        ) {
                            // 手动改动密钥后，已保存 Key 下拉框不再对应任何选中项
                            selectedSavedKeyId = null
                            state = state.copy(apiKey = it)
                        }
                        if (savedApiKeys.isNotEmpty()) {
                            EditorDropdownField(
                                label = stringResource(R.string.aimodel_editor_field_saved_key),
                                value = selectedSavedKeyId ?: "",
                                options = savedApiKeys.mapNotNull { it.id },
                                labelFor = { id -> savedApiKeys.firstOrNull { it.id == id }?.displayName ?: id },
                                onSelect = { id ->
                                    selectedSavedKeyId = id
                                    onResolveApiKey(id) { key -> state = state.copy(apiKey = key) }
                                }
                            )
                        }
                    }
                    ModelEditorSection(stringResource(R.string.aimodel_editor_field_model)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditorTextField(
                                label = stringResource(R.string.aimodel_editor_field_model),
                                value = state.model,
                                error = fieldError("model"),

                                placeholder = stringResource(R.string.aimodel_editor_field_model_placeholder)
                            ) {
                                state = state.copy(
                                    model = it,
                                    inputPrice = if (allowAutomaticPricing) "" else state.inputPrice,
                                    outputPrice = if (allowAutomaticPricing) "" else state.outputPrice,
                                    cacheReadPrice = if (allowAutomaticPricing) "" else state.cacheReadPrice,
                                    cacheWritePrice = if (allowAutomaticPricing) "" else state.cacheWritePrice
                                )
                            }
                            FilledTonalButton(
                                enabled = state.baseUrl.isNotBlank() && state.apiKey.isNotBlank(),
                                onClick = {
                                    fetchedConnection = currentConnection
                                    onFetchModels(
                                        state.baseUrl,
                                        state.apiKey,
                                        state.protocol,
                                        state.appendBaseUrlPath,
                                        state.proxyUrl
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Filled.CloudDownload,
                                    contentDescription = null, modifier = Modifier.size(18.dp)
                                )
                                Text(stringResource(R.string.aimodel_editor_field_fetch_models), Modifier.padding(start = 8.dp))
                            }
                        }
                        if (availableModels.isNotEmpty() && fetchedConnection == currentConnection) {
                            EditorDropdownField(
                                label = stringResource(
                                    R.string.aimodel_editor_field_select_from_fetched,
                                    availableModels.size
                                ),
                                value = state.model,
                                options = availableModels,
                                onSelect = {
                                    allowAutomaticPricing = true
                                    state = state.copy(
                                        model = it,
                                        inputPrice = "",
                                        outputPrice = "",
                                        cacheReadPrice = "",
                                        cacheWritePrice = ""
                                    )
                                }
                            )
                        }

                    }
                    if (showProxyConfig) ModelEditorSection(stringResource(R.string.model_setup_network), expandable = true, forceExpanded = fieldError("proxyUrl") != null) {
                        if (showProxyConfig) {
                            EditorTextField(
                                label = stringResource(R.string.aimodel_editor_field_proxy_url),
                                value = state.proxyUrl,
                                error = fieldError("proxyUrl"),
                                placeholder = "http://127.0.0.1:7890"
                            ) { state = state.copy(proxyUrl = it) }
                            Text(
                                text = stringResource(R.string.aimodel_editor_field_proxy_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                    }
                }
            }
            2 -> {
                ModelEditorSection(stringResource(R.string.model_setup_review)) {
                    ModelEditorSummaryRow(stringResource(R.string.aimodel_editor_field_config_name), state.name)
                    ModelEditorSummaryRow(stringResource(R.string.aimodel_editor_field_purpose), purposeLabels[state.purpose] ?: state.purpose)
                    ModelEditorSummaryRow(stringResource(R.string.aimodel_editor_field_provider), providerLabels[state.provider] ?: state.provider)
                    ModelEditorSummaryRow(stringResource(R.string.aimodel_editor_field_model), state.model)
                    ModelEditorSummaryRow(stringResource(R.string.aimodel_editor_field_base_url), state.baseUrl)
                    EditorSwitch(stringResource(R.string.aimodel_editor_capability_enable), state.enabled) { state = state.copy(enabled = it) }
                }
                if (state.purpose in setOf("live", "tts", "stt", "embedding", "image_generation")) {
                    ModelEditorSection(purposeLabels[state.purpose] ?: state.purpose) {
                        when (state.purpose) {
                            "live" -> {
                                val isQwenRealtime = state.provider.equals("qwen", ignoreCase = true) ||
                                state.provider.equals("dashscope", ignoreCase = true) ||
                                state.model.contains("qwen", ignoreCase = true) &&
                                state.model.contains("realtime", ignoreCase = true)
                                val isSeedRealtime = state.provider.lowercase() in LIVE_SEED_PROVIDERS ||
                                state.model.contains("seed", ignoreCase = true) &&
                                state.model.contains("realtime", ignoreCase = true)
                                val isGlmRealtime = state.provider.lowercase() in LIVE_GLM_PROVIDERS ||
                                state.model.contains("glm", ignoreCase = true) &&
                                state.model.contains("realtime", ignoreCase = true)
                                val isStepfunRealtime = state.provider.lowercase() in LIVE_STEPFUN_PROVIDERS ||
                                state.model.startsWith("stepaudio-", ignoreCase = true) ||
                                state.model.startsWith("step-audio-", ignoreCase = true) ||
                                state.model.startsWith("step-1o-audio", ignoreCase = true)
                                val liveVoiceOptions = when {
                                    state.provider.lowercase() in setOf("google", "gemini", "google_gemini") -> com.nekobot.app.data.local.ai.REALTIME_GEMINI_VOICES
                                    state.provider.lowercase() in setOf("grok", "xai") -> com.nekobot.app.data.local.ai.REALTIME_GROK_VOICES
                                    isQwenRealtime -> REALTIME_QWEN_VOICES
                                    isSeedRealtime -> REALTIME_SEED_VOICES
                                    isGlmRealtime -> REALTIME_GLM_VOICES
                                    isStepfunRealtime -> REALTIME_STEPFUN_VOICES
                                    else -> REALTIME_OPENAI_VOICES
                                }
                                val liveTranscriptionPlaceholder = when {
                                    isQwenRealtime -> REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL
                                    isSeedRealtime -> REALTIME_SEED_DEFAULT_TRANSCRIPTION_MODEL
                                    isGlmRealtime -> REALTIME_GLM_DEFAULT_TRANSCRIPTION_MODEL
                                    else -> "gpt-4o-mini-transcribe"
                                }
                                Text(
                                    text = stringResource(R.string.aimodel_editor_live_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                EditorTextField(
                                    label = stringResource(R.string.aimodel_editor_live_voice),
                                    value = state.ttsVoice
                                ) { state = state.copy(ttsVoice = it) }
                                EditorDropdownField(
                                    label = stringResource(R.string.aimodel_editor_live_voice_preset),
                                    value = state.ttsVoice,
                                    options = liveVoiceOptions,
                                    onSelect = { state = state.copy(ttsVoice = it) }
                                )
                                if (!isStepfunRealtime && state.provider.lowercase() !in setOf("google", "gemini", "google_gemini", "grok", "xai")) EditorTextField(
                                    label = stringResource(R.string.aimodel_editor_live_transcription_model),
                                    value = state.sttModel,
                                    placeholder = liveTranscriptionPlaceholder
                                ) { state = state.copy(sttModel = it) }
                                EditorDropdownField(
                                    label = stringResource(R.string.aimodel_editor_stt_language),
                                    value = state.language,
                                    options = listOf("zh", "en", "auto"),
                                    onSelect = { state = state.copy(language = it) }
                                )
                                if (isQwenRealtime) {
                                    Text(
                                        text = stringResource(R.string.aimodel_editor_live_qwen_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (isSeedRealtime) {
                                    Text(
                                        text = stringResource(R.string.aimodel_editor_live_seed_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (isGlmRealtime) {
                                    Text(
                                        text = stringResource(R.string.aimodel_editor_live_glm_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (isStepfunRealtime) {
                                    Text(
                                        text = stringResource(R.string.aimodel_editor_live_stepfun_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            "tts" -> {
                                val ttsProviderLabels = mapOf(
                                    "openai" to stringResource(R.string.aimodel_editor_tts_provider_openai),
                                    "xiaomi" to stringResource(R.string.aimodel_editor_tts_provider_xiaomi),
                                    "doubao" to stringResource(R.string.aimodel_editor_tts_provider_doubao),
                                    "gemini" to stringResource(R.string.aimodel_editor_tts_provider_gemini),
                                    "glm" to stringResource(R.string.aimodel_editor_tts_provider_glm),
                                    "minimax" to stringResource(R.string.aimodel_editor_tts_provider_minimax),
                                    "qwen" to stringResource(R.string.aimodel_editor_tts_provider_qwen),
                                    "siliconflow" to stringResource(R.string.aimodel_editor_provider_siliconflow),
                                    "elevenlabs" to stringResource(R.string.aimodel_editor_provider_elevenlabs),
                                    "deepgram" to stringResource(R.string.aimodel_editor_provider_deepgram)
                                )
                                val ttsResourceDefault = stringResource(R.string.aimodel_editor_tts_resource_default)
                                val ttsRefNotLoaded = stringResource(R.string.aimodel_editor_tts_ref_audio_not_loaded)
                                val ttsRefLoaded = stringResource(R.string.aimodel_editor_tts_ref_audio_loaded)
                                val ttsSelectAudio = stringResource(R.string.aimodel_editor_tts_select_audio)
                                val ttsChangeAudio = stringResource(R.string.aimodel_editor_tts_change_audio)
                                val ttsRemoveAudio = stringResource(R.string.aimodel_editor_remove_audio)
                                EditorDropdownField(
                                    label = stringResource(R.string.aimodel_editor_tts_provider),
                                    value = state.ttsProvider,
                                    options = ttsProviderLabels.keys.toList(),
                                    labelFor = { ttsProviderLabels[it] ?: it },
                                    onSelect = { state = state.copy(ttsProvider = it) }
                                )
                                if (state.ttsProvider == "doubao") {
                                    EditorDropdownField(
                                        label = "Resource ID",
                                        value = state.ttsResourceId,
                                        options = listOf(
                                            "",
                                            "seed-tts-2.0",
                                            "seed-tts-1.0",
                                            "seed-tts-1.0-concurr",
                                            "seed-icl-2.0",
                                            "seed-icl-1.0"
                                        ),
                                        labelFor = { it.ifBlank { ttsResourceDefault } },
                                        onSelect = { state = state.copy(ttsResourceId = it) }
                                    )
                                }
                                EditorTextField(
                                    stringResource(R.string.aimodel_editor_tts_model),
                                    state.ttsModel,
                                    placeholder = stringResource(R.string.aimodel_editor_tts_model_placeholder)
                                ) { state = state.copy(ttsModel = it) }
                                EditorTextField(
                                    stringResource(R.string.aimodel_editor_tts_voice),
                                    state.ttsVoice,
                                    placeholder = "alloy"
                                ) { state = state.copy(ttsVoice = it) }
                                EditorTextField(stringResource(R.string.aimodel_editor_tts_speed), state.ttsSpeed) { state = state.copy(ttsSpeed = it) }
                                EditorTextField(stringResource(R.string.aimodel_editor_tts_pitch), state.ttsPitch) { state = state.copy(ttsPitch = it) }
                                EditorTextField(stringResource(R.string.aimodel_editor_tts_volume), state.ttsVolume) { state = state.copy(ttsVolume = it) }
                                EditorDropdownField(
                                    label = stringResource(R.string.aimodel_editor_tts_format),
                                    value = state.ttsFormat,
                                    options = if (state.ttsProvider in setOf("elevenlabs", "deepgram", "siliconflow"))
                                    listOf("mp3", "wav", "opus") else listOf("mp3", "wav", "opus", "flac"),
                                    onSelect = { state = state.copy(ttsFormat = it) }
                                )
                                if (state.ttsProvider == "xiaomi") {
                                    EditorTextField(
                                        label = stringResource(R.string.aimodel_editor_tts_style),
                                        value = state.ttsUser,
                                        placeholder = stringResource(R.string.aimodel_editor_tts_style_placeholder)
                                    ) { state = state.copy(ttsUser = it) }
                                    if (state.ttsModel.contains("voiceclone", ignoreCase = true)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (state.ttsRefAudio.isBlank()) ttsRefNotLoaded else ttsRefLoaded,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { audioPicker.launch("audio/*") }) {
                                                Text(if (state.ttsRefAudio.isBlank()) ttsSelectAudio else ttsChangeAudio)
                                            }
                                            if (state.ttsRefAudio.isNotBlank()) {
                                                TextButton(onClick = { state = state.copy(ttsRefAudio = "") }) {
                                                    Text(ttsRemoveAudio)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "stt" -> {
                                val sttLanguageLabels = mapOf(
                                    "zh" to stringResource(R.string.aimodel_editor_stt_language_zh),
                                    "en" to stringResource(R.string.aimodel_editor_stt_language_en),
                                    "auto" to stringResource(R.string.aimodel_editor_stt_language_auto)
                                )
                                EditorTextField(
                                    stringResource(R.string.aimodel_editor_stt_model),
                                    state.sttModel,
                                    placeholder = stringResource(R.string.aimodel_editor_tts_model_placeholder)
                                ) { state = state.copy(sttModel = it) }
                                EditorDropdownField(
                                    label = stringResource(R.string.aimodel_editor_stt_language),
                                    value = state.language,
                                    options = listOf("zh", "en", "auto"),
                                    labelFor = { sttLanguageLabels[it] ?: it },
                                    onSelect = { state = state.copy(language = it) }
                                )
                            }

                            "embedding" -> {
                                EmbeddingDimensionFields(state.model, state.dimensions, customDimensions,
                                    onSelect = {
                                        customDimensions = it == "custom"
                                        if (!customDimensions) state = state.copy(dimensions = it)
                                    },
                                    onValueChange = { state = state.copy(dimensions = it) })
                            }

                            "image_generation" -> {
                                EditorTextField(
                                    stringResource(R.string.aimodel_editor_image_size),
                                    state.size,
                                    placeholder = "1024x1024"
                                ) { state = state.copy(size = it) }
                                EditorTextField(
                                    label = stringResource(R.string.aimodel_editor_image_prompt_template),
                                    value = state.promptTemplate,
                                    placeholder = stringResource(R.string.aimodel_editor_image_additional_prompt_hint),
                                    singleLine = false
                                ) { state = state.copy(promptTemplate = it) }
                            }
                        }
                        fieldError("dimensions")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (state.purpose in setOf("chat", "vision", "video", "live")) {
                    ModelEditorSection(stringResource(R.string.aimodel_editor_section_generation_params), expandable = true,
                        forceExpanded = listOf("temperature", "maxTokens", "maxContextLength", "topP").any { fieldError(it) != null }) {
                        EditorTextField(stringResource(R.string.aimodel_editor_field_temperature), state.temperature, error = fieldError("temperature")) { state = state.copy(temperature = it) }
                        EditorTextField(stringResource(R.string.aimodel_editor_field_max_tokens), state.maxTokens, error = fieldError("maxTokens")) { state = state.copy(maxTokens = it) }
                        EditorTextField(stringResource(R.string.aimodel_editor_field_max_context), state.maxContextLength, error = fieldError("maxContextLength")) {
                            state = state.copy(maxContextLength = it)
                        }
                        EditorTextField(stringResource(R.string.aimodel_editor_field_top_p), state.topP, error = fieldError("topP")) { state = state.copy(topP = it) }
                        EditorTextField(stringResource(R.string.aimodel_editor_field_stop_sequences), state.stopSequences) {
                            state = state.copy(stopSequences = it)
                        }

                    }
                    ModelEditorSection(stringResource(R.string.aimodel_editor_section_capability), expandable = true) {
                        EditorSwitch(stringResource(R.string.aimodel_editor_capability_tools), state.supportsTools) { state = state.copy(supportsTools = it) }
                        EditorSwitch(stringResource(R.string.aimodel_editor_capability_reasoning), state.supportsReasoning) {
                            state = state.copy(supportsReasoning = it)
                        }
                        EditorSwitch(stringResource(R.string.aimodel_editor_capability_stream), state.supportsStream) {
                            state = state.copy(supportsStream = it)
                        }
                        if (state.purpose == "chat") {
                            EditorSwitch(stringResource(R.string.aimodel_editor_capability_vision), state.supportsVision) {
                                state = state.copy(supportsVision = it)
                            }
                        }

                    }
                }
                ModelEditorSection(stringResource(R.string.aimodel_editor_section_pricing), expandable = true,
                    forceExpanded = listOf("inputPrice", "outputPrice", "cacheReadPrice", "cacheWritePrice", "priority").any { fieldError(it) != null }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.aimodel_editor_pricing_catalog_count,
                                    pricingState.snapshot.entries.size
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (pricingState.snapshot.source == ModelPricingCatalog.SOURCE_OPENROUTER) {
                                    stringResource(R.string.aimodel_editor_pricing_online_data, pricingState.snapshot.updatedAt.take(10))
                                } else {
                                    stringResource(R.string.aimodel_editor_pricing_offline_data)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = !pricingState.refreshing,
                            onClick = pricingViewModel::refresh
                        ) {
                            if (pricingState.refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 2.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                            Text(if (pricingState.refreshing) stringResource(R.string.aimodel_editor_pricing_updating)
                                else stringResource(R.string.aimodel_editor_pricing_update_now))
                        }
                    }
                    pricingState.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    pricingState.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (matchedPricing != null) {
                        Text(
                            text = buildPricingMatchText(
                                entry = matchedPricing,
                                matchedLabel = stringResource(R.string.aimodel_editor_pricing_matched),
                                inputLabel = stringResource(R.string.aimodel_editor_pricing_input_short),
                                outputLabel = stringResource(R.string.aimodel_editor_pricing_output_short),
                                perMillionLabel = stringResource(R.string.aimodel_editor_pricing_per_million),
                                contextLabel = stringResource(R.string.aimodel_editor_pricing_context)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = {
                                allowAutomaticPricing = false
                                state = state.applyPricing(matchedPricing, overwrite = true)
                            }
                        ) {
                            Text(stringResource(R.string.aimodel_editor_pricing_apply))
                        }
                    } else if (state.model.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.aimodel_editor_pricing_no_match, state.model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.aimodel_editor_pricing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    EditorTextField(stringResource(R.string.aimodel_editor_pricing_input), state.inputPrice, error = fieldError("inputPrice")) {
                        allowAutomaticPricing = false
                        state = state.copy(inputPrice = it)
                    }
                    EditorTextField(stringResource(R.string.aimodel_editor_pricing_output), state.outputPrice, error = fieldError("outputPrice")) {
                        allowAutomaticPricing = false
                        state = state.copy(outputPrice = it)
                    }
                    EditorTextField(stringResource(R.string.aimodel_editor_pricing_cache_read), state.cacheReadPrice, error = fieldError("cacheReadPrice")) {
                        allowAutomaticPricing = false
                        state = state.copy(cacheReadPrice = it)
                    }
                    EditorTextField(stringResource(R.string.aimodel_editor_pricing_cache_write), state.cacheWritePrice, error = fieldError("cacheWritePrice")) {
                        allowAutomaticPricing = false
                        state = state.copy(cacheWritePrice = it)
                    }
                    Text(
                        text = stringResource(R.string.aimodel_editor_pricing_cache_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    EditorTextField(stringResource(R.string.aimodel_editor_pricing_priority), state.priority, error = fieldError("priority")) { state = state.copy(priority = it) }

                }
            }
        }
    }
}

@Composable
internal fun EmbeddingDimensionFields(
    modelName: String,
    dimensions: String,
    custom: Boolean,
    onSelect: (String) -> Unit,
    onValueChange: (String) -> Unit
) {
    val context = LocalContext.current
    if (usesFixedEmbeddingDimensions(modelName)) {
        Text(stringResource(R.string.aimodel_editor_embedding_dimensions_fixed),
            style = MaterialTheme.typography.bodySmall)
        return
    }
    EditorDropdownField(
        label = stringResource(R.string.aimodel_editor_embedding_dimensions),
        value = if (custom) "custom" else dimensions,
        options = listOf("") + EMBEDDING_DIMENSION_PRESETS + "custom",
        modifier = Modifier.testTag("embedding-dimensions-selector"),
        labelFor = {
            when (it) {
                "" -> context.getString(R.string.aimodel_editor_embedding_dimensions_default)
                "custom" -> context.getString(R.string.aimodel_editor_embedding_dimensions_custom)
                else -> it
            }
        },
        onSelect = onSelect
    )
    if (custom) {
        EditorTextField(
            label = stringResource(R.string.aimodel_editor_embedding_dimensions_custom),
            value = dimensions,
            modifier = Modifier.testTag("embedding-dimensions-custom-input"),
            onValueChange = onValueChange
        )
    }
    Text(stringResource(R.string.aimodel_editor_embedding_dimensions_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EditorTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
    singleLine: Boolean = true,
    error: String? = null,
    onValueChange: (String) -> Unit
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder.takeIf { it.isNotBlank() }?.let { text -> ({ Text(text) }) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        isError = error != null,
        supportingText = error?.let { message -> ({ Text(message) }) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
        ),
        trailingIcon = if (password) ({
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (visible) R.string.model_setup_hide_key else R.string.model_setup_show_key))
            }
        }) else null,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun EditorSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelFor: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = labelFor(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        GlassExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun protocolFor(provider: String, protocols: List<ProtocolOption>): String {
    val desired = when (provider) {
        "anthropic" -> listOf("anthropic_messages", "anthropic")
        "google", "gemini" -> listOf("gemini_native", "google", "gemini")
        else -> listOf("openai_compatible", "openai_chat", "openai")
    }
    return desired.firstNotNullOfOrNull { target ->
        protocols.firstOrNull { it.key.equals(target, ignoreCase = true) }?.key
    } ?: protocols.firstOrNull()?.key.orEmpty()
}

private fun AiModelEditorState.ensureProtocol(protocols: List<ProtocolOption>): AiModelEditorState {
    if (protocols.any { it.key == protocol }) return this
    return copy(protocol = protocolFor(provider, protocols))
}

private fun AiModelEditorState.applyPricing(
    entry: ModelPricingEntry,
    overwrite: Boolean
): AiModelEditorState = copy(
    inputPrice = if (overwrite || inputPrice.isBlank()) {
        entry.inputPricePerMillion?.toCatalogPrice().orEmpty()
    } else {
        inputPrice
    },
    outputPrice = if (overwrite || outputPrice.isBlank()) {
        entry.outputPricePerMillion?.toCatalogPrice().orEmpty()
    } else {
        outputPrice
    },
    // 缓存价同样可一键套用目录值（用户手填优先，除非主动覆盖）
    cacheReadPrice = if (overwrite || cacheReadPrice.isBlank()) {
        entry.cacheReadPricePerMillion?.toCatalogPrice().orEmpty()
    } else {
        cacheReadPrice
    },
    cacheWritePrice = if (overwrite || cacheWritePrice.isBlank()) {
        entry.cacheWritePricePerMillion?.toCatalogPrice().orEmpty()
    } else {
        cacheWritePrice
    },
    maxContextLength = if ((overwrite || maxContextLength.isBlank()) && entry.contextLength != null) {
        entry.contextLength.toString()
    } else {
        maxContextLength
    },
    supportsTools = if (overwrite) entry.supportsTools else supportsTools,
    supportsReasoning = if (overwrite) entry.supportsReasoning else supportsReasoning,
    supportsVision = if (purpose == "chat") supportsVision else false
)

@Composable
private fun buildPricingMatchText(
    entry: ModelPricingEntry,
    matchedLabel: String,
    inputLabel: String,
    outputLabel: String,
    perMillionLabel: String,
    contextLabel: String
): String = buildString {
    append(matchedLabel.format(entry.name))
    entry.inputPricePerMillion?.let { append(" · ").append(inputLabel.format(it.toCatalogPrice())) }
    entry.outputPricePerMillion?.let { append(" · ").append(outputLabel.format(it.toCatalogPrice())) }
    entry.cacheReadPricePerMillion?.let {
        append(" · ").append(ServiceContainer.getString(R.string.aimodel_editor_pricing_cache_read_short).format(it.toCatalogPrice()))
    }
    append(" ").append(perMillionLabel)
    entry.contextLength?.let { append(" · ").append(contextLabel.format(it)) }
}

private fun Double.toCatalogPrice(): String {
    val raw = "%.6f".format(Locale.US, this)
    return raw.trimEnd('0').trimEnd('.').ifBlank { "0" }
}

private fun AiModelEditorState.applyPreset(
    preset: ProviderPreset,
    protocols: List<ProtocolOption>,
    presetConfigTemplate: String,
    presetLabel: String
): AiModelEditorState {
    // Live purpose + Qwen/DashScope 必须用 api-ws/v1 端点与 realtime 模型，不能用预设默认的
    // compatible-mode/v1（文本对话端点）和 qwen3.x-max（非 realtime 模型），否则 Realtime 连接会报
    // "URL does not appear to be valid" 或服务端 fallback 到已下线的旧模型快照。
    val targetPurpose = preset.purpose ?: purpose
    val liveDefaults = liveRealtimeDefaults(preset.provider).takeIf { targetPurpose == "live" }
    val nativeProvider = com.nekobot.app.data.local.ai.MultimodalProviderSupport.normalize(preset.provider)
    return copy(
        name = name.ifBlank { presetConfigTemplate.format(presetLabel) },
        purpose = targetPurpose,
        provider = liveDefaults?.provider ?: preset.provider,
        protocol = protocolFor(preset.provider, protocols),
        model = liveDefaults?.model ?: preset.model,
        baseUrl = liveDefaults?.baseUrl ?: preset.baseUrl,
        appendBaseUrlPath = if (preset.purpose != null) true else appendBaseUrlPath,
        maxContextLength = preset.maxContextLength,
        maxTokens = preset.maxTokens,
        inputPrice = preset.inputPrice,
        outputPrice = preset.outputPrice,
        supportsTools = targetPurpose == "chat" && preset.provider != "google",
        supportsReasoning = targetPurpose == "chat" && preset.provider !in setOf("google", "minimax"),
        supportsStream = targetPurpose in setOf("chat", "vision", "video", "live"),
        supportsVision = if (targetPurpose == "chat") {
            supportsVision || (preset.provider == "stepfun" && preset.model == "step-5-preview")
        } else false,
        ttsProvider = if (targetPurpose == "tts") nativeProvider else ttsProvider,
        ttsModel = if (targetPurpose == "tts") preset.model else ttsModel,
        ttsVoice = liveDefaults?.voice ?: preset.voice ?: ttsVoice,
        ttsFormat = preset.format ?: ttsFormat,
        ttsUrl = if (targetPurpose == "tts") "" else ttsUrl,
        ttsHeaders = if (targetPurpose == "tts") "" else ttsHeaders,
        ttsBodyTemplate = if (targetPurpose == "tts") "" else ttsBodyTemplate,
        ttsResourceId = if (targetPurpose == "tts" && nativeProvider == "doubao") "seed-tts-2.0" else if (targetPurpose == "tts") "" else ttsResourceId,
        ttsRefAudio = if (targetPurpose == "tts") "" else ttsRefAudio,
        sttProvider = if (targetPurpose == "stt") nativeProvider else sttProvider,
        sttUrl = if (targetPurpose == "stt") "" else sttUrl,
        sttHeaders = if (targetPurpose == "stt") "" else sttHeaders,
        sttModel = liveDefaults?.transcriptionModel ?: if (targetPurpose == "stt") preset.model else sttModel
    )
}

private fun AiModelEditorState.applyPurpose(
    purpose: String,
    isEditing: Boolean,
    purposeLabels: Map<String, String>,
    purposeNameTemplate: String
): AiModelEditorState {
    val purposeName = purposeLabels[purpose] ?: purpose
    val renamed = name.ifBlank { purposeNameTemplate.format(purposeName) }
    return when (purpose) {
        "chat" -> copy(
            name = renamed,
            purpose = purpose,
            temperature = "0.7",
            maxTokens = "2000",
            supportsTools = true,
            supportsReasoning = true,
            supportsStream = true,
            supportsVision = supportsVision
        )
        "live" -> {
            // 保留当前 provider：若已是 qwen/dashscope 则沿用 Qwen Realtime 默认配置，
            // 否则回退 OpenAI Realtime 默认配置。避免一刀切写死 openai 导致 Qwen 用户被重置。
            val defaults = liveRealtimeDefaults(provider)
            copy(
                name = renamed,
                purpose = purpose,
                provider = defaults.provider,
                baseUrl = defaults.baseUrl,
                model = defaults.model,
                temperature = "0.8",
                maxTokens = "4096",
                maxContextLength = "32000",
                supportsTools = false,
                supportsReasoning = false,
                supportsStream = true,
                supportsVision = false,
                ttsVoice = defaults.voice,
                sttModel = defaults.transcriptionModel,
                language = language.ifBlank { "zh" }
            )
        }
        "vision" -> copy(
            name = renamed,
            purpose = purpose,
            temperature = "0.5",
            maxTokens = "1000",
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = true,
            supportsVision = false
        )
        "video" -> copy(
            name = renamed,
            purpose = purpose,
            temperature = "0.5",
            maxTokens = "1500",
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = true,
            supportsVision = false
        )
        "tts" -> copy(
            name = renamed,
            purpose = purpose,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            supportsVision = false,
            ttsVoice = ttsVoice.ifBlank { "default" }
        )
        "stt" -> copy(
            name = renamed,
            purpose = purpose,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            supportsVision = false,
            language = language.ifBlank { "zh" }
        )
        "embedding" -> copy(
            name = renamed,
            purpose = purpose,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            supportsVision = false
        )
        "image_generation" -> copy(
            name = renamed,
            purpose = purpose,
            model = if (model.isBlank()) "dall-e-3" else model,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            supportsVision = false,
            size = size.ifBlank { "1024x1024" }
        )
        else -> copy(name = renamed, purpose = purpose, supportsVision = false)
    }
}

fun AiModel?.toEditorState(protocols: List<ProtocolOption>): AiModelEditorState {
    val model = this ?: return AiModelEditorState(
        name = ServiceContainer.getString(R.string.aimodel_editor_default_name_chat)
    ).ensureProtocol(protocols)
    return AiModelEditorState(
        name = model.name.orEmpty(),
        purpose = model.purpose ?: "chat",
        provider = model.provider ?: "openai",
        protocol = model.protocol.orEmpty(),
        apiKey = model.apiKey.orEmpty(),
        baseUrl = model.baseUrl.orEmpty(),
        appendBaseUrlPath = model.appendBaseUrlPath ?: true,
        model = model.model.orEmpty(),
        enabled = model.enabled ?: true,
        priority = (model.priority ?: 0).toString(),
        temperature = model.temperature?.toString().orEmpty(),
        maxTokens = model.maxTokens?.toString().orEmpty(),
        maxContextLength = model.maxContextLength?.toString().orEmpty(),
        topP = model.topP?.toString().orEmpty(),
        inputPrice = model.inputPrice?.toString().orEmpty(),
        outputPrice = model.outputPrice?.toString().orEmpty(),
        cacheReadPrice = model.cacheReadPrice?.toString().orEmpty(),
        cacheWritePrice = model.cacheWritePrice?.toString().orEmpty(),
        supportsTools = model.supportsTools ?: true,
        supportsReasoning = model.supportsReasoning ?: true,
        supportsStream = model.supportsStream ?: true,
        supportsVision = model.supportsVision ?: false,
        ttsProvider = model.ttsProvider ?: "openai",
        ttsUrl = model.ttsUrl.orEmpty(),
        ttsModel = model.ttsModel.orEmpty(),
        ttsVoice = model.ttsVoice ?: "default",
        ttsSpeed = (model.ttsSpeed ?: 1f).toString(),
        ttsPitch = (model.ttsPitch ?: 1f).toString(),
        ttsVolume = (model.ttsVolume ?: 1f).toString(),
        ttsFormat = model.ttsFormat ?: "mp3",
        ttsUploadUrl = model.ttsUploadUrl.orEmpty(),
        ttsHeaders = model.ttsHeaders.orEmpty(),
        ttsBodyTemplate = model.ttsBodyTemplate.orEmpty(),
        ttsResourceId = model.ttsResourceId.orEmpty(),
        ttsRefAudio = model.ttsRefAudio.orEmpty(),
        ttsUser = model.ttsUser.orEmpty(),
        language = model.sttLanguage ?: model.language ?: "zh",
        sttProvider = model.sttProvider.orEmpty(),
        sttUrl = model.sttUrl.orEmpty(),
        sttModel = model.sttModel.orEmpty(),
        sttHeaders = model.sttHeaders.orEmpty(),
        dimensions = model.dimensions?.takeIf { it > 0 }?.toString().orEmpty(),
        size = model.size ?: "1024x1024",
        promptTemplate = model.promptTemplate.orEmpty()
    ).ensureProtocol(protocols)
}

fun LocalAiModelEntity?.toEditorState(protocols: List<ProtocolOption>): AiModelEditorState {
    val model = this ?: return AiModelEditorState(
        protocol = "openai_chat",
        name = ServiceContainer.getString(R.string.aimodel_editor_default_name_chat)
    ).ensureProtocol(protocols)
    return AiModelEditorState(
        name = model.name,
        purpose = model.purpose,
        provider = model.provider ?: "openai",
        protocol = model.protocol,
        apiKey = model.apiKey,
        proxyUrl = model.proxyUrl,
        baseUrl = model.baseUrl,
        appendBaseUrlPath = model.appendBaseUrlPath,
        model = model.model,
        enabled = model.enabled,
        priority = model.priority.toString(),
        temperature = model.temperature?.toString().orEmpty(),
        maxTokens = model.maxTokens?.toString().orEmpty(),
        maxContextLength = model.maxContextLength?.toString().orEmpty(),
        topP = model.topP?.toString().orEmpty(),
        stopSequences = model.stopSequences.orEmpty(),
        inputPrice = model.inputPrice?.toString().orEmpty(),
        outputPrice = model.outputPrice?.toString().orEmpty(),
        cacheReadPrice = model.cacheReadPrice?.toString().orEmpty(),
        cacheWritePrice = model.cacheWritePrice?.toString().orEmpty(),
        supportsTools = model.supportsTools,
        supportsReasoning = model.supportsReasoning,
        supportsStream = model.supportsStream,
        supportsVision = model.supportsVision,
        ttsProvider = model.ttsProvider,
        ttsUrl = model.ttsUrl,
        ttsModel = model.ttsModel,
        ttsVoice = model.ttsVoice,
        ttsSpeed = model.ttsSpeed.toString(),
        ttsPitch = model.ttsPitch.toString(),
        ttsVolume = model.ttsVolume.toString(),
        ttsFormat = model.ttsFormat,
        ttsUploadUrl = model.ttsUploadUrl,
        ttsHeaders = model.ttsHeaders,
        ttsBodyTemplate = model.ttsBodyTemplate,
        ttsResourceId = model.ttsResourceId,
        ttsRefAudio = model.ttsRefAudio,
        ttsUser = model.ttsUser,
        language = model.language,
        sttProvider = model.sttProvider,
        sttUrl = model.sttUrl,
        sttModel = model.sttModel,
        sttHeaders = model.sttHeaders,
        dimensions = model.dimensions.takeIf { it > 0 }?.toString().orEmpty(),
        size = model.size,
        promptTemplate = model.promptTemplate
    ).ensureProtocol(protocols)
}

fun AiModelEditorState.toRequest(): AiModelRequest = AiModelRequest(
    name = name.trim(),
    protocol = protocol,
    provider = provider,
    apiKey = apiKey.trim(),
    baseUrl = baseUrl.trim(),
    appendBaseUrlPath = appendBaseUrlPath,
    model = model.trim(),
    enabled = enabled,
    purpose = purpose,
    priority = priority.toIntOrNull() ?: 0,
    temperature = temperature.toDoubleOrNull(),
    maxTokens = maxTokens.toIntOrNull(),
    maxContextLength = maxContextLength.toIntOrNull(),
    topP = topP.toDoubleOrNull(),
    inputPrice = inputPrice.toDoubleOrNull(),
    outputPrice = outputPrice.toDoubleOrNull(),
    cacheReadPrice = cacheReadPrice.toDoubleOrNull(),
    cacheWritePrice = cacheWritePrice.toDoubleOrNull(),
    supportsTools = supportsTools,
    supportsReasoning = supportsReasoning,
    supportsStream = supportsStream,
    supportsVision = if (purpose == "chat") supportsVision else false,
    ttsProvider = ttsProvider,
    ttsUrl = ttsUrl.ifBlank { null },
    ttsModel = ttsModel.ifBlank { null },
    ttsVoice = ttsVoice.ifBlank { null },
    ttsSpeed = ttsSpeed.toFloatOrNull(),
    ttsPitch = ttsPitch.toFloatOrNull(),
    ttsVolume = ttsVolume.toFloatOrNull(),
    ttsFormat = ttsFormat,
    ttsUploadUrl = ttsUploadUrl.ifBlank { null },
    ttsHeaders = ttsHeaders.ifBlank { null },
    ttsBodyTemplate = ttsBodyTemplate.ifBlank { null },
    ttsResourceId = ttsResourceId.ifBlank { null },
    ttsRefAudio = ttsRefAudio.ifBlank { null },
    ttsUser = ttsUser.ifBlank { null },
    language = language,
    sttProvider = sttProvider.ifBlank { null },
    sttUrl = sttUrl.ifBlank { null },
    sttModel = sttModel.ifBlank { null },
    sttLanguage = language,
    sttHeaders = sttHeaders.ifBlank { null },
        dimensions = dimensions.trim().toIntOrNull(),
    size = size.ifBlank { null },
    promptTemplate = promptTemplate.ifBlank { null }
)

fun AiModelEditorState.toLocalEntity(existing: LocalAiModelEntity?): LocalAiModelEntity {
    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    return LocalAiModelEntity(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.trim(),
        protocol = protocol,
        provider = provider,
        apiKey = apiKey.trim(),
        proxyUrl = proxyUrl.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        enabled = enabled,
        purpose = purpose,
        priority = priority.toIntOrNull() ?: 0,
        active = existing?.active ?: false,
        temperature = temperature.toFloatOrNull(),
        maxTokens = maxTokens.toIntOrNull(),
        maxContextLength = maxContextLength.toIntOrNull(),
        topP = topP.toFloatOrNull(),
        stopSequences = stopSequences.trim().ifBlank { null },
        appendBaseUrlPath = appendBaseUrlPath,
        supportsTools = supportsTools,
        supportsReasoning = supportsReasoning,
        supportsStream = supportsStream,
        supportsVision = purpose == "chat" && supportsVision,
        ttsProvider = ttsProvider,
        ttsUrl = ttsUrl,
        ttsModel = ttsModel,
        ttsVoice = ttsVoice,
        ttsSpeed = ttsSpeed.toFloatOrNull() ?: 1f,
        ttsPitch = ttsPitch.toFloatOrNull() ?: 1f,
        ttsVolume = ttsVolume.toFloatOrNull() ?: 1f,
        ttsFormat = ttsFormat,
        ttsUploadUrl = ttsUploadUrl,
        ttsHeaders = ttsHeaders,
        ttsBodyTemplate = ttsBodyTemplate,
        ttsResourceId = ttsResourceId,
        ttsRefAudio = ttsRefAudio,
        ttsUser = ttsUser,
        language = language,
        sttProvider = sttProvider,
        sttUrl = sttUrl,
        sttModel = sttModel,
        sttHeaders = sttHeaders,
        dimensions = dimensions.trim().toIntOrNull() ?: 0,
        size = size,
        promptTemplate = promptTemplate,
        createdAt = existing?.createdAt ?: now,
        tokenLimitDaily = existing?.tokenLimitDaily ?: 0,
        tokenLimitWeekly = existing?.tokenLimitWeekly ?: 0,
        failoverTimeout = existing?.failoverTimeout ?: 0,
        inputPrice = inputPrice.toDoubleOrNull(),
        outputPrice = outputPrice.toDoubleOrNull(),
        cacheReadPrice = cacheReadPrice.toDoubleOrNull(),
        cacheWritePrice = cacheWritePrice.toDoubleOrNull(),
        oauthAccountId = existing?.oauthAccountId
    )
}

private class AiModelEditorDraft(initial: AiModelEditorState, isEditing: Boolean) : BaseViewModel() {
    val state = mutableStateOf(initial)
    val step = mutableIntStateOf(0)
    val attemptedStep = mutableIntStateOf(-1)
    val customDimensions = mutableStateOf(initial.dimensions.isNotBlank() && initial.dimensions !in EMBEDDING_DIMENSION_PRESETS)
    val fetchedConnection = mutableStateOf<List<String>?>(null)
    val selectedSavedKeyId = mutableStateOf<String?>(null)
    val allowAutomaticPricing = mutableStateOf(!isEditing || initial.inputPrice.isBlank() || initial.outputPrice.isBlank())
}

private val embeddingPresets = listOf(
    ProviderPreset("OpenAI", "openai", "text-embedding-3-small", "https://api.openai.com/v1", "", "", "", "", purpose = "embedding"),
    ProviderPreset("Gemini", "google", "gemini-embedding-001", "https://generativelanguage.googleapis.com/v1beta", "", "", "", "", purpose = "embedding"),
    ProviderPreset("Qwen", "qwen", "text-embedding-v4", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "", "", "", purpose = "embedding")
)

@Composable
private fun ModelEditorSummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}
