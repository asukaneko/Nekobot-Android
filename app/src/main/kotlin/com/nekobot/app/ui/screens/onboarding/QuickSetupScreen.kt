package com.nekobot.app.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassExposedDropdownMenu
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class QuickSetupProviderPreset(
    val label: String,
    val provider: String,
    val protocol: String,
    val baseUrl: String,
    val model: String
)

private val quickSetupProviderPresets = listOf(
    QuickSetupProviderPreset("OpenAI", "openai", "openai_chat", "https://api.openai.com/v1", "gpt-5.5"),
    QuickSetupProviderPreset("Claude", "anthropic", "anthropic_messages", "https://api.anthropic.com/v1", "claude-opus-4.8"),
    QuickSetupProviderPreset("Gemini", "google", "gemini_native", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.5-flash"),
    QuickSetupProviderPreset("DeepSeek", "deepseek", "openai_chat", "https://api.deepseek.com", "deepseek-v4-flash"),
    QuickSetupProviderPreset("GLM", "zhipu", "openai_chat", "https://open.bigmodel.cn/api/paas/v4", "glm-5.1"),
    QuickSetupProviderPreset("MiniMax", "minimax", "openai_chat", "https://api.minimaxi.com/v1", "minimax-m2.7"),
    QuickSetupProviderPreset("Grok", "grok", "openai_chat", "https://api.x.ai/v1", "grok-4.20"),
    QuickSetupProviderPreset("Qwen", "qwen", "openai_chat", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.7-max"),
    QuickSetupProviderPreset("Mimo", "xiaomi", "openai_chat", "https://api.xiaomimimo.com/v1", "mimo-v2.5"),
    QuickSetupProviderPreset("Doubao Seed", "doubao", "openai_chat", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seedream-4-0-250828"),
    QuickSetupProviderPreset("custom", "custom", "openai_chat", "", "")
)

/** 首次启动时选择本地或服务器运行方式的快速配置页。 */
@Composable
fun QuickSetupScreen(onComplete: (AppMode) -> Unit) {
    var selectedModeName by rememberSaveable { mutableStateOf(AppMode.LOCAL.name) }
    var configuringChatModel by rememberSaveable { mutableStateOf(false) }
    val selectedMode = AppMode.valueOf(selectedModeName)
    val isLocalMode = selectedMode == AppMode.LOCAL

    if (configuringChatModel) {
        QuickSetupChatModelScreen(
            onBack = { configuringChatModel = false },
            onSkip = { onComplete(AppMode.LOCAL) },
            onComplete = onComplete
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onComplete(AppMode.LOCAL) }) {
                Text(stringResource(R.string.quick_setup_skip))
            }
        }

        Spacer(Modifier.height(28.dp))
        Image(
            painter = painterResource(R.drawable.neko),
            contentDescription = null,
            modifier = Modifier.size(88.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.quick_setup_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.quick_setup_description),
            modifier = Modifier.widthIn(max = 400.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        Column(modifier = Modifier.widthIn(max = 480.dp)) {
            Text(
                text = stringResource(R.string.quick_setup_mode_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = isLocalMode,
                    onClick = { selectedModeName = AppMode.LOCAL.name },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text(stringResource(R.string.quick_setup_local_mode)) }
                )
                SegmentedButton(
                    selected = !isLocalMode,
                    onClick = { selectedModeName = AppMode.SERVER.name },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text(stringResource(R.string.quick_setup_server_mode)) }
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(
                    if (isLocalMode) {
                        R.string.quick_setup_local_description
                    } else {
                        R.string.quick_setup_server_description
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    if (isLocalMode) configuringChatModel = true else onComplete(AppMode.SERVER)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    stringResource(
                        if (isLocalMode) {
                            R.string.quick_setup_configure_chat_model
                        } else {
                            R.string.quick_setup_continue_server
                        }
                    )
                )
            }
            if (isLocalMode) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onComplete(AppMode.LOCAL) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.quick_setup_continue_without_model))
                }
            }
        }
    }
}

@Composable
private fun QuickSetupChatModelScreen(
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onComplete: (AppMode) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val protocols = LocalProtocols.names()
    val geminiNativeLabel = stringResource(R.string.aimodel_editor_protocol_gemini_native)
    var selectedPresetProvider by rememberSaveable { mutableStateOf("openai") }
    var name by rememberSaveable { mutableStateOf("OpenAI") }
    var provider by rememberSaveable { mutableStateOf("openai") }
    var protocol by rememberSaveable { mutableStateOf("openai_chat") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("https://api.openai.com/v1") }
    var model by rememberSaveable { mutableStateOf("gpt-5.5") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by rememberSaveable { mutableStateOf(false) }
    val selectedPreset = quickSetupProviderPresets.firstOrNull {
        it.provider == selectedPresetProvider
    } ?: quickSetupProviderPresets.first()

    fun applyPreset(preset: QuickSetupProviderPreset) {
        selectedPresetProvider = preset.provider
        name = if (preset.provider == "custom") "" else preset.label
        provider = preset.provider
        protocol = preset.protocol
        baseUrl = preset.baseUrl
        model = preset.model
        validationError = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back)
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.quick_setup_continue_without_model))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.quick_setup_chat_model_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.quick_setup_chat_model_description),
            modifier = Modifier.widthIn(max = 440.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))

        Column(modifier = Modifier.widthIn(max = 480.dp)) {
            QuickSetupDropdown(
                label = stringResource(R.string.quick_setup_preset),
                value = if (selectedPreset.provider == "custom") {
                    stringResource(R.string.quick_setup_preset_custom)
                } else {
                    selectedPreset.label
                },
                options = quickSetupProviderPresets.map { preset ->
                    preset to if (preset.provider == "custom") {
                        stringResource(R.string.quick_setup_preset_custom)
                    } else {
                        preset.label
                    }
                },
                onSelect = ::applyPreset
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.aimodel_editor_field_config_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = stringResource(R.string.aimodel_editor_purpose_chat),
                onValueChange = {},
                label = { Text(stringResource(R.string.aimodel_editor_field_purpose)) },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            QuickSetupDropdown(
                label = stringResource(R.string.aimodel_editor_field_provider),
                value = quickSetupProviderPresets.firstOrNull { it.provider == provider }
                    ?.let { if (it.provider == "custom") stringResource(R.string.quick_setup_preset_custom) else it.label }
                    ?: provider,
                options = quickSetupProviderPresets.map { preset ->
                    preset to if (preset.provider == "custom") {
                        stringResource(R.string.quick_setup_preset_custom)
                    } else {
                        preset.label
                    }
                },
                onSelect = ::applyPreset
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.aimodel_editor_field_api_key)) },
                placeholder = { Text(stringResource(R.string.aimodel_editor_field_api_key_placeholder)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            QuickSetupDropdown(
                label = stringResource(R.string.aimodel_editor_field_provider_type),
                value = if (protocol == "gemini_native") geminiNativeLabel else protocol,
                options = protocols.map {
                    it to if (it == "gemini_native") geminiNativeLabel else it
                },
                onSelect = { protocol = it }
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.aimodel_editor_field_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.aimodel_editor_field_model)) },
                placeholder = { Text(stringResource(R.string.aimodel_editor_field_model_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            validationError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    validationError = when {
                        name.isBlank() -> context.getString(R.string.aimodel_editor_validation_name)
                        apiKey.isBlank() -> context.getString(R.string.aimodel_editor_validation_api_key)
                        baseUrl.isBlank() -> context.getString(R.string.aimodel_editor_validation_base_url)
                        model.isBlank() -> context.getString(R.string.aimodel_editor_validation_model)
                        else -> null
                    }
                    if (validationError != null) return@Button

                    val chatModel = LocalAiModelEntity(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(),
                        protocol = protocol,
                        provider = provider.trim().ifBlank { null },
                        apiKey = apiKey.trim(),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        purpose = "chat",
                        active = true,
                        createdAt = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date())
                    )
                    isSaving = true
                    coroutineScope.launch {
                        runCatching {
                            ServiceContainer.unified.upsertLocalAiModel(chatModel)
                            ServiceContainer.unified.setActiveLocalModel(chatModel.id)
                        }.onSuccess {
                            onComplete(AppMode.LOCAL)
                        }.onFailure { error ->
                            validationError = error.message ?: error.javaClass.simpleName
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.quick_setup_save_model_and_start))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> QuickSetupDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        GlassExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (option, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
