package com.nekobot.app.ui.screens.aiconfig

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.ai.ModelPricingEntry
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.ai.parseModelProxyUrl
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
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.AiModel
import com.nekobot.app.data.model.AiModelRequest
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.ui.components.GlassExposedDropdownMenu
import com.nekobot.app.ui.components.NekoDialog
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
    val inputPrice: String = "",
    val outputPrice: String = "",
    val supportsTools: Boolean = true,
    val supportsReasoning: Boolean = true,
    val supportsStream: Boolean = true,
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
    val dimensions: String = "1536",
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
    val purpose: String? = null
)

private val LIVE_SEED_PROVIDERS = setOf("doubao", "seed", "volcengine", "bytedance", "ark", "volces")
private val LIVE_GLM_PROVIDERS = setOf("glm", "zhipu", "bigmodel")

private data class LiveRealtimeDefaults(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val voice: String,
    val transcriptionModel: String
)

private fun liveRealtimeDefaults(provider: String): LiveRealtimeDefaults = when (provider.lowercase()) {
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
    else -> LiveRealtimeDefaults(
        provider = "openai",
        model = "gpt-realtime",
        baseUrl = "https://api.openai.com/v1",
        voice = "marin",
        transcriptionModel = "gpt-4o-mini-transcribe"
    )
}

private val providerPresets = listOf(
    ProviderPreset("OpenAI", "openai", "gpt-5.5", "", "1050000", "128000", "", ""),
    ProviderPreset("Claude", "anthropic", "claude-opus-4.8", "", "1000000", "128000", "", ""),
    ProviderPreset("Gemini", "google", "gemini-3.5-flash", "", "1048576", "65536", "", ""),
    ProviderPreset("DeepSeek", "deepseek", "deepseek-v4-flash", "https://api.deepseek.com", "1000000", "131072", "", ""),
    ProviderPreset("GLM", "zhipu", "glm-5.1", "https://open.bigmodel.cn/api/paas/v4", "200000", "128000", "", ""),
    ProviderPreset("MiniMax", "minimax", "minimax-m2.7", "https://api.minimaxi.com/v1", "204800", "131072", "", ""),
    ProviderPreset("Grok", "grok", "grok-4.20", "https://api.x.ai/v1", "1000000", "131072", "", ""),
    ProviderPreset("Qwen", "qwen", "qwen3.7-max", "https://dashscope.aliyuncs.com/compatible-mode/v1", "1000000", "65536", "", ""),
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
    "xiaomi" to stringResource(R.string.aimodel_editor_provider_xiaomi),
    "doubao" to stringResource(R.string.aimodel_editor_provider_doubao),
    "azure" to stringResource(R.string.aimodel_editor_provider_azure),
    "siliconflow" to stringResource(R.string.aimodel_editor_provider_siliconflow),
    "custom" to stringResource(R.string.aimodel_editor_provider_custom)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelEditorDialog(
    initial: AiModelEditorState,
    isEditing: Boolean,
    protocols: List<ProtocolOption>,
    purposes: List<String>,
    availableModels: List<String>,
    savedApiKeys: List<ApiKey>,
    showProxyConfig: Boolean = false,
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
    val purposeOptions = remember(purposes) {
        (purposeLabels.keys + purposes).distinct()
    }
    val presetConfigTemplate = stringResource(R.string.aimodel_editor_preset_config_name)
    val purposeNameTemplate = stringResource(R.string.aimodel_editor_default_name_purpose)
    var state by remember(initial) { mutableStateOf(initial.ensureProtocol(protocolOptions)) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var allowAutomaticPricing by remember(initial, isEditing) {
        mutableStateOf(!isEditing || initial.inputPrice.isBlank() || initial.outputPrice.isBlank())
    }
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

    NekoDialog(
        onDismiss = onDismiss,
        title = if (isEditing) stringResource(R.string.aimodel_editor_dialog_title_edit)
               else stringResource(R.string.aimodel_editor_dialog_title_new),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            val proxyError = if (showProxyConfig && state.proxyUrl.isNotBlank()) {
                runCatching { parseModelProxyUrl(state.proxyUrl) }.exceptionOrNull()?.message
            } else {
                null
            }
            validationError = when {
                state.name.isBlank() -> context.getString(R.string.aimodel_editor_validation_name)
                state.baseUrl.isBlank() -> context.getString(R.string.aimodel_editor_validation_base_url)
                state.model.isBlank() -> context.getString(R.string.aimodel_editor_validation_model)
                state.apiKey.isBlank() && !(state.purpose == "stt" && state.sttProvider == "local") ->
                    context.getString(R.string.aimodel_editor_validation_api_key)
                proxyError != null -> proxyError
                else -> null
            }
            if (validationError == null) onConfirm(state)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FormSection(stringResource(R.string.aimodel_editor_section_quick_preset))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                providerPresets.forEach { preset ->
                    AssistChip(
                        onClick = {
                            allowAutomaticPricing = true
                            val presetLabel = preset.labelRes?.let(context::getString) ?: preset.label
                            state = state.applyPreset(
                                preset,
                                protocolOptions,
                                presetConfigTemplate,
                                presetLabel
                            )
                        },
                        label = {
                            Text(preset.labelRes?.let { stringResource(it) } ?: preset.label)
                        }
                    )
                }
            }

            EditorTextField(stringResource(R.string.aimodel_editor_field_config_name), state.name) { state = state.copy(name = it) }
            EditorDropdownField(
                label = stringResource(R.string.aimodel_editor_field_purpose),
                value = state.purpose,
                options = purposeOptions,
                labelFor = { purposeLabels[it] ?: it },
                onSelect = { state = state.applyPurpose(it, isEditing, purposeLabels, purposeNameTemplate) }
            )
            EditorDropdownField(
                label = stringResource(R.string.aimodel_editor_field_provider),
                value = state.provider,
                options = providerLabels.keys.toList(),
                labelFor = { providerLabels[it] ?: it },
                onSelect = { provider ->
                    val preset = providerPresets.firstOrNull { it.provider == provider }
                    allowAutomaticPricing = true
                    state = preset?.let {
                        state.applyPreset(
                            it,
                            protocolOptions,
                            presetConfigTemplate,
                            it.labelRes?.let(context::getString) ?: it.label
                        )
                    }
                        ?: state.copy(provider = provider, protocol = protocolFor(provider, protocolOptions))
                }
            )

            EditorTextField(
                label = stringResource(R.string.aimodel_editor_field_api_key),
                value = state.apiKey,
                password = true,
                placeholder = stringResource(R.string.aimodel_editor_field_api_key_placeholder)
            ) { state = state.copy(apiKey = it) }
            if (savedApiKeys.isNotEmpty()) {
                EditorDropdownField(
                    label = stringResource(R.string.aimodel_editor_field_saved_key),
                    value = "",
                    options = savedApiKeys.mapNotNull { it.id },
                    labelFor = { id -> savedApiKeys.firstOrNull { it.id == id }?.displayName ?: id },
                    onSelect = { id ->
                        onResolveApiKey(id) { key -> state = state.copy(apiKey = key) }
                    }
                )
            }
            if (showProxyConfig) {
                EditorTextField(
                    label = stringResource(R.string.aimodel_editor_field_proxy_url),
                    value = state.proxyUrl,
                    placeholder = "http://127.0.0.1:7890"
                ) { state = state.copy(proxyUrl = it) }
                Text(
                    text = stringResource(R.string.aimodel_editor_field_proxy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditorTextField(
                    label = stringResource(R.string.aimodel_editor_field_model),
                    value = state.model,
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.aimodel_editor_field_model_placeholder)
                ) {
                    state = state.copy(
                        model = it,
                        inputPrice = if (allowAutomaticPricing) "" else state.inputPrice,
                        outputPrice = if (allowAutomaticPricing) "" else state.outputPrice
                    )
                }
                IconButton(
                    enabled = state.baseUrl.isNotBlank() && state.apiKey.isNotBlank(),
                    onClick = {
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
                        contentDescription = stringResource(R.string.aimodel_editor_field_fetch_models)
                    )
                }
            }
            if (availableModels.isNotEmpty()) {
                EditorDropdownField(
                    label = stringResource(
                        R.string.aimodel_editor_field_select_from_fetched,
                        availableModels.size
                    ),
                    value = state.model,
                    options = availableModels,
                    onSelect = {
                        allowAutomaticPricing = true
                        state = state.copy(model = it, inputPrice = "", outputPrice = "")
                    }
                )
            }

            FormSection(stringResource(R.string.aimodel_editor_section_generation_params))
            EditorTextField(stringResource(R.string.aimodel_editor_field_temperature), state.temperature) { state = state.copy(temperature = it) }
            EditorTextField(stringResource(R.string.aimodel_editor_field_max_tokens), state.maxTokens) { state = state.copy(maxTokens = it) }
            EditorTextField(stringResource(R.string.aimodel_editor_field_max_context), state.maxContextLength) {
                state = state.copy(maxContextLength = it)
            }
            EditorTextField(stringResource(R.string.aimodel_editor_field_top_p), state.topP) { state = state.copy(topP = it) }

            FormSection(stringResource(R.string.aimodel_editor_section_pricing))
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
                TextButton(
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
            EditorTextField(stringResource(R.string.aimodel_editor_pricing_input), state.inputPrice) {
                allowAutomaticPricing = false
                state = state.copy(inputPrice = it)
            }
            EditorTextField(stringResource(R.string.aimodel_editor_pricing_output), state.outputPrice) {
                allowAutomaticPricing = false
                state = state.copy(outputPrice = it)
            }
            EditorTextField(stringResource(R.string.aimodel_editor_pricing_priority), state.priority) { state = state.copy(priority = it) }

            FormSection(stringResource(R.string.aimodel_editor_section_capability))
            EditorSwitch(stringResource(R.string.aimodel_editor_capability_enable), state.enabled) { state = state.copy(enabled = it) }
            EditorSwitch(stringResource(R.string.aimodel_editor_capability_tools), state.supportsTools) { state = state.copy(supportsTools = it) }
            EditorSwitch(stringResource(R.string.aimodel_editor_capability_reasoning), state.supportsReasoning) {
                state = state.copy(supportsReasoning = it)
            }
            EditorSwitch(stringResource(R.string.aimodel_editor_capability_stream), state.supportsStream) {
                state = state.copy(supportsStream = it)
            }

            when (state.purpose) {
                "live" -> {
                    FormSection(stringResource(R.string.aimodel_editor_section_live))
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
                    val liveVoiceOptions = when {
                        isQwenRealtime -> REALTIME_QWEN_VOICES
                        isSeedRealtime -> REALTIME_SEED_VOICES
                        isGlmRealtime -> REALTIME_GLM_VOICES
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
                    EditorTextField(
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
                    }
                }

                "tts" -> {
                    FormSection(stringResource(R.string.aimodel_editor_section_tts))
                    val ttsProviderLabels = mapOf(
                        "openai" to stringResource(R.string.aimodel_editor_tts_provider_openai),
                        "xiaomi" to stringResource(R.string.aimodel_editor_tts_provider_xiaomi),
                        "doubao" to stringResource(R.string.aimodel_editor_tts_provider_doubao),
                        "gemini" to stringResource(R.string.aimodel_editor_tts_provider_gemini),
                        "glm" to stringResource(R.string.aimodel_editor_tts_provider_glm),
                        "minimax" to stringResource(R.string.aimodel_editor_tts_provider_minimax),
                        "qwen" to stringResource(R.string.aimodel_editor_tts_provider_qwen)
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
                        options = listOf("openai", "gemini", "glm", "minimax", "qwen", "doubao", "xiaomi"),
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
                        options = listOf("mp3", "wav", "opus", "flac"),
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
                    FormSection(stringResource(R.string.aimodel_editor_section_stt))
                    val sttProviderLabels = mapOf(
                        "" to stringResource(R.string.aimodel_editor_stt_provider_follow),
                        "openai" to stringResource(R.string.aimodel_editor_stt_provider_openai),
                        "gemini" to stringResource(R.string.aimodel_editor_stt_provider_gemini),
                        "glm" to stringResource(R.string.aimodel_editor_stt_provider_glm),
                        "qwen" to stringResource(R.string.aimodel_editor_stt_provider_qwen),
                        "xiaomi" to stringResource(R.string.aimodel_editor_stt_provider_xiaomi),
                        "local" to stringResource(R.string.aimodel_editor_stt_provider_local)
                    )
                    val sttLanguageLabels = mapOf(
                        "zh" to stringResource(R.string.aimodel_editor_stt_language_zh),
                        "en" to stringResource(R.string.aimodel_editor_stt_language_en),
                        "auto" to stringResource(R.string.aimodel_editor_stt_language_auto)
                    )
                    EditorDropdownField(
                        label = stringResource(R.string.aimodel_editor_stt_provider),
                        value = state.sttProvider,
                        options = listOf("", "openai", "gemini", "glm", "qwen", "xiaomi", "local"),
                        labelFor = { sttProviderLabels[it] ?: it },
                        onSelect = { state = state.copy(sttProvider = it) }
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
                    FormSection(stringResource(R.string.aimodel_editor_section_embedding))
                    EditorDropdownField(
                        label = stringResource(R.string.aimodel_editor_embedding_dimensions),
                        value = state.dimensions,
                        options = listOf("384", "768", "1024", "1536", "3072"),
                        onSelect = { state = state.copy(dimensions = it) }
                    )
                }

                "image_generation" -> {
                    FormSection(stringResource(R.string.aimodel_editor_section_image_gen))
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

            validationError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FormSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun EditorTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder.takeIf { it.isNotBlank() }?.let { text -> ({ Text(text) }) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
        "google" -> listOf("google", "gemini")
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
    maxContextLength = if ((overwrite || maxContextLength.isBlank()) && entry.contextLength != null) {
        entry.contextLength.toString()
    } else {
        maxContextLength
    },
    supportsTools = if (overwrite) entry.supportsTools else supportsTools,
    supportsReasoning = if (overwrite) entry.supportsReasoning else supportsReasoning
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
    val isImageGeneration = targetPurpose == "image_generation"
    return copy(
        name = presetConfigTemplate.format(presetLabel),
        purpose = targetPurpose,
        provider = liveDefaults?.provider ?: preset.provider,
        protocol = protocolFor(preset.provider, protocols),
        model = liveDefaults?.model ?: preset.model,
        baseUrl = liveDefaults?.baseUrl ?: preset.baseUrl,
        maxContextLength = preset.maxContextLength,
        maxTokens = preset.maxTokens,
        inputPrice = preset.inputPrice,
        outputPrice = preset.outputPrice,
        supportsTools = !isImageGeneration && preset.provider != "google",
        supportsReasoning = !isImageGeneration && preset.provider !in setOf("google", "minimax"),
        supportsStream = !isImageGeneration,
        ttsVoice = liveDefaults?.voice ?: ttsVoice,
        sttModel = liveDefaults?.transcriptionModel ?: sttModel
    )
}

private fun AiModelEditorState.applyPurpose(
    purpose: String,
    isEditing: Boolean,
    purposeLabels: Map<String, String>,
    purposeNameTemplate: String
): AiModelEditorState {
    val purposeName = purposeLabels[purpose] ?: purpose
    val renamed = if (isEditing) name else purposeNameTemplate.format(purposeName)
    return when (purpose) {
        "chat" -> copy(
            name = renamed,
            purpose = purpose,
            temperature = "0.7",
            maxTokens = "2000",
            supportsTools = true,
            supportsReasoning = true,
            supportsStream = true
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
            supportsStream = true
        )
        "video" -> copy(
            name = renamed,
            purpose = purpose,
            temperature = "0.5",
            maxTokens = "1500",
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = true
        )
        "tts" -> copy(
            name = renamed,
            purpose = purpose,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            ttsVoice = ttsVoice.ifBlank { "default" }
        )
        "stt" -> copy(
            name = renamed,
            purpose = purpose,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            language = language.ifBlank { "zh" }
        )
        "embedding" -> copy(
            name = renamed,
            purpose = purpose,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            dimensions = dimensions.ifBlank { "1536" }
        )
        "image_generation" -> copy(
            name = renamed,
            purpose = purpose,
            model = if (model.isBlank()) "dall-e-3" else model,
            supportsTools = false,
            supportsReasoning = false,
            supportsStream = false,
            size = size.ifBlank { "1024x1024" }
        )
        else -> copy(name = renamed, purpose = purpose)
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
        supportsTools = model.supportsTools ?: true,
        supportsReasoning = model.supportsReasoning ?: true,
        supportsStream = model.supportsStream ?: true,
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
        dimensions = (model.dimensions ?: 1536).toString(),
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
        inputPrice = model.inputPrice?.toString().orEmpty(),
        outputPrice = model.outputPrice?.toString().orEmpty(),
        supportsTools = model.supportsTools,
        supportsReasoning = model.supportsReasoning,
        supportsStream = model.supportsStream,
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
        dimensions = model.dimensions.toString(),
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
    supportsTools = supportsTools,
    supportsReasoning = supportsReasoning,
    supportsStream = supportsStream,
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
    dimensions = dimensions.toIntOrNull(),
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
        appendBaseUrlPath = appendBaseUrlPath,
        supportsTools = supportsTools,
        supportsReasoning = supportsReasoning,
        supportsStream = supportsStream,
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
        dimensions = dimensions.toIntOrNull() ?: 1536,
        size = size,
        promptTemplate = promptTemplate,
        createdAt = existing?.createdAt ?: now,
        tokenLimitDaily = existing?.tokenLimitDaily ?: 0,
        tokenLimitWeekly = existing?.tokenLimitWeekly ?: 0,
        failoverTimeout = existing?.failoverTimeout ?: 0,
        inputPrice = inputPrice.toDoubleOrNull(),
        outputPrice = outputPrice.toDoubleOrNull(),
        oauthAccountId = existing?.oauthAccountId
    )
}
