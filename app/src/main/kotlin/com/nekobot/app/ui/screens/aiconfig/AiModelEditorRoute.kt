package com.nekobot.app.ui.screens.aiconfig

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.GeminiNativeProtocol
import com.nekobot.app.data.local.ai.LocalProtocols

@Composable
internal fun LocalAiModelEditorRoute(modelId: String?, vm: LocalAiModelsViewModel, onBack: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val loaded by vm.modelsLoaded.collectAsStateWithLifecycle()
    val keys by vm.apiKeys.collectAsStateWithLifecycle()
    val available by vm.availableModels.collectAsStateWithLifecycle()
    val busy by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val model = models.firstOrNull { it.id == modelId }
    val nativeLabel = stringResource(R.string.aimodel_editor_protocol_gemini_native)
    val protocols = remember(nativeLabel) { LocalProtocols.names().map {
        ProtocolOption(it, if (it == GeminiNativeProtocol.name) nativeLabel else it)
    } }
    LaunchedEffect(Unit) { vm.clearEditorModels() }
    if (modelId != null && model == null) {
        ModelEditorLoadingPage(error ?: if (loaded && modelId != null && model == null) stringResource(R.string.model_setup_not_found) else null, onBack)
        return
    }
    AiModelEditorScreen(initial = model.toEditorState(protocols), isEditing = model != null,
        protocols = protocols, purposes = localModelPurposes, availableModels = available,
        savedApiKeys = keys, showProxyConfig = true, busy = busy, actionError = error,
        onResolveApiKey = vm::resolveApiKey, onFetchModels = vm::fetchModels,
        onConfirm = { vm.saveModel(it.toLocalEntity(model), onSaved = onBack) }, onDismiss = onBack)
}

@Composable
internal fun ServerAiModelEditorRoute(modelId: String?, vm: AiModelsViewModel, onBack: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val loaded by vm.modelsLoaded.collectAsStateWithLifecycle()
    val protocols by vm.protocols.collectAsStateWithLifecycle()
    val purposes by vm.purposes.collectAsStateWithLifecycle()
    val keys by vm.apiKeys.collectAsStateWithLifecycle()
    val available by vm.availableModels.collectAsStateWithLifecycle()
    val busy by vm.loading.collectAsStateWithLifecycle()
    val saving by vm.editorSaving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val model = models.firstOrNull { it.id == modelId }
    LaunchedEffect(Unit) { vm.clearEditorModels() }
    if (protocols.isEmpty() || (modelId != null && model == null)) {
        ModelEditorLoadingPage(error ?: if (loaded && modelId != null && model == null) stringResource(R.string.model_setup_not_found) else null, onBack)
        return
    }
    AiModelEditorScreen(initial = model.toEditorState(protocols), isEditing = model != null,
        protocols = protocols, purposes = purposes, availableModels = available,
        savedApiKeys = keys, busy = busy || saving, actionError = error,
        onResolveApiKey = vm::resolveApiKey,
        onFetchModels = { url, key, protocol, append, _ -> vm.fetchModels(url, key, protocol, append) },
        onConfirm = { vm.saveEditor(modelId, it.toRequest(), onBack) }, onDismiss = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelEditorLoadingPage(error: String?, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.aimodel_editor_dialog_title_edit)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator() else Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
