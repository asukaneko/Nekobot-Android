package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GlobalAgentMemoryUiState(
    val draft: String = "",
    val savedContent: String = "",
    val updatedAt: String? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
}

class GlobalAgentMemoryViewModel : ViewModel() {
    private val store: GlobalAgentMemoryStore
        get() = ServiceContainer.globalAgentMemory

    private val _uiState = MutableStateFlow(GlobalAgentMemoryUiState())
    val uiState: StateFlow<GlobalAgentMemoryUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun updateDraft(content: String) {
        if (content.length <= GlobalAgentMemoryStore.MAX_CONTENT_CHARS) {
            _uiState.update { it.copy(draft = content, message = null, messageIsError = false) }
        }
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null, messageIsError = false) }
            runCatching { withContext(Dispatchers.IO) { store.read() } }
                .onSuccess { snapshot ->
                    _uiState.update {
                        it.copy(
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            updatedAt = snapshot.updatedAt,
                            loading = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            message = error.message ?: ServiceContainer.getString(R.string.global_memory_read_failed),
                            messageIsError = true
                        )
                    }
                }
        }
    }

    fun save() {
        val content = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, message = null, messageIsError = false) }
            runCatching { withContext(Dispatchers.IO) { store.replace(content) } }
                .onSuccess { snapshot ->
                    _uiState.update {
                        it.copy(
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            updatedAt = snapshot.updatedAt,
                            saving = false,
                            message = ServiceContainer.getString(R.string.global_memory_saved),
                            messageIsError = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            saving = false,
                            message = error.message ?: ServiceContainer.getString(R.string.global_memory_save_failed),
                            messageIsError = true
                        )
                    }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalAgentMemoryScreen(
    onBack: () -> Unit,
    viewModel: GlobalAgentMemoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_global_agent_memory), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.global_memory_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = uiState.draft,
                onValueChange = viewModel::updateDraft,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enabled = !uiState.loading && !uiState.saving,
                label = { Text(stringResource(R.string.global_memory_label)) },
                placeholder = { Text(stringResource(R.string.global_memory_placeholder)) },
                supportingText = {
                    Text("${uiState.draft.length} / ${GlobalAgentMemoryStore.MAX_CONTENT_CHARS}")
                }
            )
            uiState.updatedAt?.let {
                Text(
                    stringResource(R.string.global_memory_last_saved, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            uiState.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = viewModel::reload,
                    enabled = !uiState.loading && !uiState.saving
                ) {
                    Text(stringResource(R.string.global_memory_discard))
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.hasUnsavedChanges && !uiState.loading && !uiState.saving
                ) {
                    Text(if (uiState.saving) stringResource(R.string.global_memory_saving) else stringResource(R.string.common_save))
                }
            }
        }
    }
}
