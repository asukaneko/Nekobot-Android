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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
    val message: String? = null
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
            _uiState.update { it.copy(draft = content, message = null) }
        }
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
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
                    _uiState.update { it.copy(loading = false, message = error.message ?: "读取失败") }
                }
        }
    }

    fun save() {
        val content = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, message = null) }
            runCatching { withContext(Dispatchers.IO) { store.replace(content) } }
                .onSuccess { snapshot ->
                    _uiState.update {
                        it.copy(
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            updatedAt = snapshot.updatedAt,
                            saving = false,
                            message = "已保存，将从下一轮 Agent 请求开始生效"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(saving = false, message = error.message ?: "保存失败") }
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
                title = { Text("全局 Agent 记忆", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                "这里的内容跨 Agent 会话和本地数据库 Profile 保存。每轮 Agent 请求都会自动注入；AI 可读取，修改时仍需你的授权。",
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
                label = { Text("长期偏好、背景与持续事项") },
                placeholder = { Text("例如：我的常用语言、项目约定、长期目标……") },
                supportingText = {
                    Text("${uiState.draft.length} / ${GlobalAgentMemoryStore.MAX_CONTENT_CHARS}")
                }
            )
            uiState.updatedAt?.let {
                Text(
                    "最近保存：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            uiState.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.contains("失败")) MaterialTheme.colorScheme.error
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
                    Text("放弃未保存修改")
                }
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.hasUnsavedChanges && !uiState.loading && !uiState.saving
                ) {
                    Text(if (uiState.saving) "保存中…" else "保存")
                }
            }
        }
    }
}
