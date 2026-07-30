package com.nekobot.app.ui.screens.aiconfig

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.ai.ModelPricingEntry
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.ai.parseModelProxyUrl
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
    val name: String = "新对话模型配置",
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
    val outputPrice: String
)

private val providerPresets = listOf(
    ProviderPreset("OpenAI", "openai", "gpt-5.5", "", "1050000", "128000", "", ""),
    ProviderPreset("Claude", "anthropic", "claude-opus-4.8", "", "1000000", "128000", "", ""),
    ProviderPreset("Gemini", "google", "gemini-3.5-flash", "", "1048576", "65536", "", ""),
    ProviderPreset("DeepSeek", "deepseek", "deepseek-v4-flash", "https://api.deepseek.com", "1000000", "131072", "", ""),
    ProviderPreset("GLM", "zhipu", "glm-5.1", "https://open.bigmodel.cn/api/paas/v4", "200000", "128000", "", ""),
    ProviderPreset("MiniMax", "minimax", "minimax-m2.7", "https://api.minimax.chat/v1", "204800", "131072", "", ""),
    ProviderPreset("Grok", "grok", "grok-4.20", "https://api.x.ai/v1", "1000000", "131072", "", ""),
    ProviderPreset("Qwen", "qwen", "qwen3.7-max", "https://dashscope.aliyuncs.com/compatible-mode/v1", "1000000", "65536", "", ""),
    ProviderPreset("Mimo", "xiaomi", "mimo-v2.5", "https://api.xiaomimimo.com/v1", "1048576", "131072", "", "")
)

private val purposeLabels = linkedMapOf(
    "chat" to "对话模型",
    "vision" to "图片理解",
    "video" to "视频理解",
    "tts" to "TTS 语音合成",
    "stt" to "STT 语音识别",
    "embedding" to "向量嵌入",
    "image_generation" to "图片生成"
)

private val providerLabels = linkedMapOf(
    "openai" to "OpenAI",
    "anthropic" to "Anthropic / Claude",
    "google" to "Google / Gemini",
    "deepseek" to "DeepSeek",
    "zhipu" to "智谱 GLM",
    "minimax" to "MiniMax",
    "grok" to "Grok (xAI)",
    "qwen" to "通义千问",
    "xiaomi" to "小米 MiMo",
    "azure" to "Azure OpenAI",
    "siliconflow" to "SiliconFlow",
    "custom" to "自定义"
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
    val pricingState by pricingViewModel.state.collectAsState()
    val protocolOptions = remember(protocols) {
        protocols.ifEmpty {
            LocalProtocols.names().map { ProtocolOption(it, it) }
                .ifEmpty { listOf(ProtocolOption("openai_compatible", "OpenAI 兼容")) }
        }
    }
    val purposeOptions = remember(purposes) {
        (purposeLabels.keys + purposes).distinct()
    }
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
                ?: error("无法读取音频文件")
            require(bytes.size <= 7.5 * 1024 * 1024) { "参考音频不能超过 7.5 MB" }
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }.onSuccess {
            state = state.copy(ttsRefAudio = it)
            validationError = null
        }.onFailure {
            validationError = it.message ?: "参考音频读取失败"
        }
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (isEditing) "编辑 AI 模型" else "新建 AI 模型",
        confirmText = "保存",
        onConfirm = {
            val proxyError = if (showProxyConfig && state.proxyUrl.isNotBlank()) {
                runCatching { parseModelProxyUrl(state.proxyUrl) }.exceptionOrNull()?.message
            } else {
                null
            }
            validationError = when {
                state.name.isBlank() -> "请填写配置名称"
                state.baseUrl.isBlank() -> "请填写 Base URL"
                state.model.isBlank() -> "请填写模型名称"
                state.apiKey.isBlank() && !(state.purpose == "stt" && state.sttProvider == "local") ->
                    "请填写或选择 API Key"
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
            FormSection("快速预设")
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
                            state = state.applyPreset(preset, protocolOptions)
                        },
                        label = { Text(preset.label) }
                    )
                }
            }

            EditorTextField("配置名称", state.name) { state = state.copy(name = it) }
            EditorDropdownField(
                label = "用途",
                value = state.purpose,
                options = purposeOptions,
                labelFor = { purposeLabels[it] ?: it },
                onSelect = { state = state.applyPurpose(it, isEditing) }
            )
            EditorDropdownField(
                label = "服务商",
                value = state.provider,
                options = providerLabels.keys.toList(),
                labelFor = { providerLabels[it] ?: it },
                onSelect = { provider ->
                    val preset = providerPresets.firstOrNull { it.provider == provider }
                    allowAutomaticPricing = true
                    state = preset?.let { state.applyPreset(it, protocolOptions) }
                        ?: state.copy(provider = provider, protocol = protocolFor(provider, protocolOptions))
                }
            )

            EditorTextField(
                label = "API Key",
                value = state.apiKey,
                password = true,
                placeholder = "输入 API Key"
            ) { state = state.copy(apiKey = it) }
            if (savedApiKeys.isNotEmpty()) {
                EditorDropdownField(
                    label = "选择已保存的 Key",
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
                    label = "代理链接（可选）",
                    value = state.proxyUrl,
                    placeholder = "http://127.0.0.1:7890"
                ) { state = state.copy(proxyUrl = it) }
                Text(
                    text = "留空时该模型直连；支持 HTTP 和 SOCKS5 代理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            EditorDropdownField(
                label = "Provider 类型",
                value = state.protocol,
                options = protocolOptions.map { it.key },
                labelFor = { key -> protocolOptions.firstOrNull { it.key == key }?.displayName ?: key },
                onSelect = { state = state.copy(protocol = it) }
            )
            EditorTextField(
                label = if (state.purpose == "image_generation") "完整 API URL" else "API Base URL",
                value = state.baseUrl,
                placeholder = if (state.purpose == "image_generation") {
                    "https://api.openai.com/v1/images/generations"
                } else {
                    "https://api.example.com/v1"
                }
            ) { state = state.copy(baseUrl = it) }
            EditorSwitch("自动补全 Base URL 后缀", state.appendBaseUrlPath) {
                state = state.copy(appendBaseUrlPath = it)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditorTextField(
                    label = "模型",
                    value = state.model,
                    modifier = Modifier.weight(1f),
                    placeholder = "例如：gpt-4"
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
                    Icon(Icons.Filled.CloudDownload, contentDescription = "获取模型")
                }
            }
            if (availableModels.isNotEmpty()) {
                EditorDropdownField(
                    label = "从已获取列表选择（${availableModels.size}）",
                    value = state.model,
                    options = availableModels,
                    onSelect = {
                        allowAutomaticPricing = true
                        state = state.copy(model = it, inputPrice = "", outputPrice = "")
                    }
                )
            }

            FormSection("生成参数")
            EditorTextField("温度", state.temperature) { state = state.copy(temperature = it) }
            EditorTextField("最大输出 Token", state.maxTokens) { state = state.copy(maxTokens = it) }
            EditorTextField("最大上下文长度", state.maxContextLength) {
                state = state.copy(maxContextLength = it)
            }
            EditorTextField("Top P（可选）", state.topP) { state = state.copy(topP = it) }

            FormSection("价格目录")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前目录 ${pricingState.snapshot.entries.size} 个模型",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (pricingState.snapshot.source == ModelPricingCatalog.SOURCE_OPENROUTER) {
                            "在线数据 · ${pricingState.snapshot.updatedAt.take(10)}"
                        } else {
                            "内置离线数据 · 可在线更新"
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
                    Text(if (pricingState.refreshing) "更新中" else "一键更新")
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
                    text = buildPricingMatchText(matchedPricing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        allowAutomaticPricing = false
                        state = state.applyPricing(matchedPricing, overwrite = true)
                    }
                ) {
                    Text("应用目录价格和上下文")
                }
            } else if (state.model.isNotBlank()) {
                Text(
                    text = "目录中未匹配到“${state.model}”，可保留手动价格或在线更新后重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "目录价格为 OpenRouter 美元参考价；手动填写的服务商价格优先。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            EditorTextField("输入价格 / 百万 Token（美元）", state.inputPrice) {
                allowAutomaticPricing = false
                state = state.copy(inputPrice = it)
            }
            EditorTextField("输出价格 / 百万 Token（美元）", state.outputPrice) {
                allowAutomaticPricing = false
                state = state.copy(outputPrice = it)
            }
            EditorTextField("优先级", state.priority) { state = state.copy(priority = it) }

            FormSection("能力")
            EditorSwitch("启用模型", state.enabled) { state = state.copy(enabled = it) }
            EditorSwitch("支持工具调用", state.supportsTools) { state = state.copy(supportsTools = it) }
            EditorSwitch("支持推理 / 思考字段", state.supportsReasoning) {
                state = state.copy(supportsReasoning = it)
            }
            EditorSwitch("支持流式响应", state.supportsStream) {
                state = state.copy(supportsStream = it)
            }

            when (state.purpose) {
                "tts" -> {
                    FormSection("TTS 语音合成")
                    EditorDropdownField(
                        label = "TTS 提供商",
                        value = state.ttsProvider,
                        options = listOf("openai", "xiaomi", "doubao"),
                        labelFor = {
                            when (it) {
                                "openai" -> "OpenAI 兼容"
                                "xiaomi" -> "小米 MiMo"
                                "doubao" -> "豆包（火山引擎）"
                                else -> it
                            }
                        },
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
                            labelFor = { it.ifBlank { "seed-tts-2.0（默认）" } },
                            onSelect = { state = state.copy(ttsResourceId = it) }
                        )
                    }
                    EditorTextField("TTS 模型名", state.ttsModel, placeholder = "留空则使用上方模型") {
                        state = state.copy(ttsModel = it)
                    }
                    EditorTextField("音色", state.ttsVoice, placeholder = "alloy") {
                        state = state.copy(ttsVoice = it)
                    }
                    EditorTextField("语速", state.ttsSpeed) { state = state.copy(ttsSpeed = it) }
                    EditorTextField("音调", state.ttsPitch) { state = state.copy(ttsPitch = it) }
                    EditorTextField("音量", state.ttsVolume) { state = state.copy(ttsVolume = it) }
                    EditorDropdownField(
                        label = "输出格式",
                        value = state.ttsFormat,
                        options = listOf("mp3", "wav", "opus", "flac"),
                        onSelect = { state = state.copy(ttsFormat = it) }
                    )
                    if (state.ttsProvider == "xiaomi") {
                        EditorTextField(
                            label = "风格控制指令（可选）",
                            value = state.ttsUser,
                            placeholder = "例如：请用欢快的语气说话"
                        ) { state = state.copy(ttsUser = it) }
                        if (state.ttsModel.contains("voiceclone", ignoreCase = true)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (state.ttsRefAudio.isBlank()) "尚未加载参考音频" else "已加载参考音频",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { audioPicker.launch("audio/*") }) {
                                    Text(if (state.ttsRefAudio.isBlank()) "选择音频" else "更换")
                                }
                                if (state.ttsRefAudio.isNotBlank()) {
                                    TextButton(onClick = { state = state.copy(ttsRefAudio = "") }) {
                                        Text("移除")
                                    }
                                }
                            }
                        }
                    }
                }

                "stt" -> {
                    FormSection("STT 语音识别")
                    EditorDropdownField(
                        label = "STT 提供商",
                        value = state.sttProvider,
                        options = listOf("", "openai", "xiaomi", "local"),
                        labelFor = {
                            when (it) {
                                "" -> "跟随 Provider 类型"
                                "openai" -> "OpenAI 兼容"
                                "xiaomi" -> "小米 MiMo"
                                "local" -> "本地 faster-whisper"
                                else -> it
                            }
                        },
                        onSelect = { state = state.copy(sttProvider = it) }
                    )
                    EditorTextField("STT 模型名", state.sttModel, placeholder = "留空则使用上方模型") {
                        state = state.copy(sttModel = it)
                    }
                    EditorDropdownField(
                        label = "识别语言",
                        value = state.language,
                        options = listOf("zh", "en", "auto"),
                        labelFor = { mapOf("zh" to "中文", "en" to "英文", "auto" to "自动检测")[it] ?: it },
                        onSelect = { state = state.copy(language = it) }
                    )
                }

                "embedding" -> {
                    FormSection("Embedding 配置")
                    EditorDropdownField(
                        label = "向量维度",
                        value = state.dimensions,
                        options = listOf("384", "768", "1024", "1536", "3072"),
                        onSelect = { state = state.copy(dimensions = it) }
                    )
                }

                "image_generation" -> {
                    FormSection("图片生成")
                    EditorTextField("图片尺寸", state.size, placeholder = "1024x1024") {
                        state = state.copy(size = it)
                    }
                    EditorTextField(
                        label = "提示词模板（可选）",
                        value = state.promptTemplate,
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

private fun buildPricingMatchText(entry: ModelPricingEntry): String = buildString {
    append("已匹配 ${entry.name}")
    entry.inputPricePerMillion?.let { append(" · 输入 $").append(it.toCatalogPrice()) }
    entry.outputPricePerMillion?.let { append(" · 输出 $").append(it.toCatalogPrice()) }
    append(" / 百万 Token")
    entry.contextLength?.let { append(" · 上下文 ").append(it) }
}

private fun Double.toCatalogPrice(): String {
    val raw = "%.6f".format(Locale.US, this)
    return raw.trimEnd('0').trimEnd('.').ifBlank { "0" }
}

private fun AiModelEditorState.applyPreset(
    preset: ProviderPreset,
    protocols: List<ProtocolOption>
): AiModelEditorState = copy(
    name = "${preset.label} 配置",
    provider = preset.provider,
    protocol = protocolFor(preset.provider, protocols),
    model = preset.model,
    baseUrl = preset.baseUrl,
    maxContextLength = preset.maxContextLength,
    maxTokens = preset.maxTokens,
    inputPrice = preset.inputPrice,
    outputPrice = preset.outputPrice,
    supportsTools = preset.provider != "google",
    supportsReasoning = preset.provider !in setOf("google", "minimax"),
    supportsStream = true
)

private fun AiModelEditorState.applyPurpose(purpose: String, isEditing: Boolean): AiModelEditorState {
    val purposeName = purposeLabels[purpose] ?: purpose
    val renamed = if (isEditing) name else "新${purposeName}配置"
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
    val model = this ?: return AiModelEditorState().ensureProtocol(protocols)
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
    val model = this ?: return AiModelEditorState(protocol = "openai_chat").ensureProtocol(protocols)
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
