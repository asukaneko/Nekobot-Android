package com.nekobot.app.ui.screens.extensions

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.data.model.ApiKeyRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * API Keys ViewModel：管理密钥列表的增删改查。
 */
class ApiKeysViewModel : BaseViewModel() {

    private val _keys = MutableStateFlow<List<ApiKey>>(emptyList())
    val keys: StateFlow<List<ApiKey>> = _keys.asStateFlow()

    /** 查看完整 Key 时缓存的明文，供弹窗展示。 */
    private val _viewedKey = MutableStateFlow<String?>(null)
    val viewedKey: StateFlow<String?> = _viewedKey.asStateFlow()

    init {
        load()
    }

    /** 加载密钥列表 */
    fun load() {
        launchResult(
            block = { unified.listApiKeys() },
            onSuccess = { _keys.value = it ?: emptyList() }
        )
    }

    /** 创建密钥 */
    fun create(name: String, key: String) {
        launchResult(
            block = { unified.createApiKey(ApiKeyRequest(name = name, key = key)) },
            onSuccess = {
                showToast(string(R.string.apikeys_created))
                load()
            }
        )
    }

    /** 更新密钥 */
    fun update(id: String, name: String, key: String) {
        launchResult(
            block = { unified.updateApiKey(id, ApiKeyRequest(name = name, key = key)) },
            onSuccess = {
                showToast(string(R.string.apikeys_updated))
                load()
            }
        )
    }

    /** 删除密钥 */
    fun delete(id: String) {
        launchResult(
            block = { unified.deleteApiKey(id) },
            onSuccess = {
                showToast(string(R.string.apikeys_deleted))
                load()
            }
        )
    }

    /** 查看完整 Key，成功后写入 viewedKey 供弹窗展示 */
    fun viewKey(id: String) {
        launchResult(
            block = { unified.getApiKey(id) },
            onSuccess = { _viewedKey.value = it.key }
        )
    }

    /** 清除已查看的 Key */
    fun clearViewedKey() {
        _viewedKey.value = null
    }
}

/**
 * API Keys 页：列出所有密钥，支持新建、编辑、查看完整 Key 与删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val vm: ApiKeysViewModel = viewModel()
    val keys by vm.keys.collectAsState()
    val viewedKey by vm.viewedKey.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showForm by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKey?>(null) }
    var deleteTarget by remember { mutableStateOf<ApiKey?>(null) }

    // 模式切换时自动刷新
    val appMode by ServiceContainer.appModeFlow.collectAsState()
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
                title = { Text(stringResource(R.string.apikeys_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.apikeys_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editingKey = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.apikeys_new_key), tint = MaterialTheme.colorScheme.primary)
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
            if (keys.isEmpty() && !loading) {
                EmptyState(
                    title = stringResource(R.string.apikeys_empty_title),
                    hint = stringResource(R.string.apikeys_empty_hint)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                vm.clearError()
                                vm.load()
                            })
                        }
                    }
                    items(keys, key = { it.id ?: it.name ?: it.hashCode().toString() }) { key ->
                        ApiKeyItem(
                            apiKey = key,
                            onEdit = {
                                editingKey = key
                                showForm = true
                            },
                            onViewKey = {
                                key.id?.let { vm.viewKey(it) }
                            },
                            onDelete = { deleteTarget = key }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading && keys.isEmpty())
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        ApiKeyFormDialog(
            initial = editingKey,
            onConfirm = { name, key ->
                editingKey?.id?.let { id -> vm.update(id, name, key) } ?: vm.create(name, key)
                showForm = false
                editingKey = null
            },
            onViewCurrentKey = {
                editingKey?.id?.let { vm.viewKey(it) }
            },
            onDismiss = {
                showForm = false
                editingKey = null
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { key ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.apikeys_confirm_delete_title),
            message = stringResource(R.string.apikeys_delete_confirm_msg, key.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                key.id?.let { vm.delete(it) }
                deleteTarget = null
            }
        )
    }

    // 查看完整 Key 弹窗
    viewedKey?.let { fullKey ->
        NekoDialog(
            onDismiss = { vm.clearViewedKey() },
            title = stringResource(R.string.apikeys_full_key_title),
            confirmText = stringResource(R.string.common_close),
            cancelText = null,
            onCancel = null,
            onConfirm = { vm.clearViewedKey() },
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = fullKey,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(fullKey))
                            Toast.makeText(context, context.getString(R.string.apikeys_key_copied), Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.apikeys_copy_key), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        )
    }
}

/** 单个密钥卡片：名称 + 创建时间 + 操作菜单（编辑/查看Key/删除） */
@Composable
private fun ApiKeyItem(
    apiKey: ApiKey,
    onEdit: () -> Unit,
    onViewKey: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = apiKey.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!apiKey.createdAt.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.apikeys_created_label, apiKey.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 操作菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.apikeys_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.apikeys_view_key)) }, onClick = { menuExpanded = false; onViewKey() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = ErrorRed) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

/** 新建/编辑密钥表单弹窗：名称（必填）+ Key（必填，多行） */
@Composable
private fun ApiKeyFormDialog(
    initial: ApiKey?,
    onConfirm: (name: String, key: String) -> Unit,
    onViewCurrentKey: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var key by remember { mutableStateOf(initial?.key ?: "") }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.apikeys_new_key_title) else stringResource(R.string.apikeys_edit_key_title),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            if (key.isBlank()) return@NekoDialog
            onConfirm(name.trim(), key.trim())
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.apikeys_name_required)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.apikeys_key_required)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            // 编辑时可查看当前完整 Key
            if (initial != null && initial.id != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onViewCurrentKey) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.apikeys_view_full_key), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
