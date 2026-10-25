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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.ai.GeminiNativeProtocol
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.screens.aiconfig.AiModelEditorScreen
import com.nekobot.app.ui.screens.aiconfig.LocalAiModelsViewModel
import com.nekobot.app.ui.screens.aiconfig.ProtocolOption
import com.nekobot.app.ui.screens.aiconfig.toEditorState
import com.nekobot.app.ui.screens.aiconfig.toLocalEntity
import kotlinx.coroutines.launch

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

/**
 * 新用户的会话模型配置：直接复用「模型编辑」的分步全屏编辑器（基础信息 → 连接 → 选项），
 * 只固定用途为会话模型，保存后写库并设为激活模型。
 */
@Composable
private fun QuickSetupChatModelScreen(
    onBack: () -> Unit,
    onComplete: (AppMode) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val vm: LocalAiModelsViewModel = viewModel()
    val savedApiKeys by vm.apiKeys.collectAsStateWithLifecycle()
    val availableModels by vm.availableModels.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val geminiNativeLabel = stringResource(R.string.aimodel_editor_protocol_gemini_native)
    val protocols = remember(geminiNativeLabel) {
        LocalProtocols.names().map {
            ProtocolOption(it, if (it == GeminiNativeProtocol.name) geminiNativeLabel else it)
        }
    }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearEditorModels() }

    AiModelEditorScreen(
        initial = (null as LocalAiModelEntity?).toEditorState(protocols),
        isEditing = false,
        protocols = protocols,
        purposes = listOf("chat"),
        availableModels = availableModels,
        savedApiKeys = savedApiKeys,
        showProxyConfig = true,
        fixedPurpose = true,
        busy = loading || saving,
        actionError = saveError ?: error,
        onResolveApiKey = vm::resolveApiKey,
        onFetchModels = { baseUrl, apiKey, protocol, appendBaseUrlPath, proxyUrl ->
            vm.fetchModels(baseUrl, apiKey, protocol, appendBaseUrlPath, proxyUrl)
        },
        onConfirm = { editorState ->
            saveError = null
            saving = true
            val chatModel = editorState.copy(purpose = "chat").toLocalEntity(null)
            coroutineScope.launch {
                runCatching {
                    ServiceContainer.unified.upsertLocalAiModel(chatModel)
                    ServiceContainer.unified.setActiveLocalModel(chatModel.id)
                }.onSuccess {
                    onComplete(AppMode.LOCAL)
                }.onFailure { failure ->
                    saveError = failure.message ?: failure.javaClass.simpleName
                    saving = false
                }
            }
        },
        onDismiss = onBack
    )
}
