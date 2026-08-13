package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.model.Tool
import com.nekobot.app.data.model.ToolRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tools 配置 ViewModel：负责工具函数的 CRUD 与启停切换。
 */
class ToolsViewModel : BaseViewModel() {
    private val _list = MutableStateFlow<List<Tool>>(emptyList())
    val list: StateFlow<List<Tool>> = _list.asStateFlow()

    init { load() }

    fun load() = launchResult(block = { unified.listTools() }, onSuccess = { _list.value = it ?: emptyList() })

    fun create(req: ToolRequest) =
        launchResult(block = { unified.createTool(req) }, onSuccess = { load() })

    fun update(id: String, req: ToolRequest) =
        launchResult(block = { unified.updateTool(id, req) }, onSuccess = { load() })

    fun delete(id: String) =
        launchResult(block = { unified.deleteTool(id) }, onSuccess = { load() })

    fun toggle(id: String) =
        launchResult(block = { unified.toggleTool(id) }, onSuccess = { load() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val vm: ToolsViewModel = viewModel()
    val list by vm.list.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var showForm by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<Tool?>(null) }
    var deleteTarget by remember { mutableStateOf<Tool?>(null) }
    var viewTarget by remember { mutableStateOf<Tool?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingItem = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tools_new))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (list.isEmpty() && !loading) {
                EmptyState(title = stringResource(R.string.tools_empty_title), hint = stringResource(R.string.tools_empty_hint))
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
                    items(list, key = { it.id ?: it.hashCode().toString() }) { tool ->
                        ToolCard(
                            tool = tool,
                            onEdit = { editingItem = tool; showForm = true },
                            onDelete = { deleteTarget = tool },
                            onToggle = { tool.id?.let { vm.toggle(it) } },
                            onView = { viewTarget = tool }
                        )
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        ToolFormDialog(
            initial = editingItem,
            onConfirm = { req ->
                editingItem?.id?.let { vm.update(it, req) } ?: vm.create(req)
                showForm = false
                editingItem = null
            },
            onDismiss = {
                showForm = false
                editingItem = null
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { target ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.tools_confirm_delete),
            message = stringResource(R.string.tools_delete_message, target.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                target.id?.let { vm.delete(it) }
                deleteTarget = null
            }
        )
    }

    // 内置工具查看弹窗
    viewTarget?.let { target ->
        NekoDialog(
            onDismiss = { viewTarget = null },
            title = stringResource(R.string.tools_detail_title),
            confirmText = stringResource(R.string.common_close),
            onConfirm = { viewTarget = null },
            cancelText = null,
            onCancel = null,
            content = {
                Column {
                    Text(stringResource(R.string.tools_name, target.displayName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tools_description, target.description ?: "—"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tools_status, if (target.enabled) stringResource(R.string.tools_status_enabled) else stringResource(R.string.tools_status_disabled)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tools_builtin, if (target.builtin) stringResource(R.string.common_yes) else stringResource(R.string.common_no)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tools_created_at, target.createdAt ?: "—"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/**
 * 工具卡片：展示名称、启停状态、内置标记与操作菜单。
 * 内置工具（builtin=true）菜单仅显示「查看」，不可删除/切换。
 */
@Composable
private fun ToolCard(
    tool: Tool,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onView: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 状态标记 + 内置标记 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tool.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // 启停状态标记（颜色区分）
            val (statusText, statusColor) = if (tool.enabled) stringResource(R.string.tools_status_enabled) to SuccessGreen else stringResource(R.string.tools_status_disabled) to WarningAmber
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            // 内置标记
            if (tool.builtin) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.tools_builtin_badge), style = MaterialTheme.typography.labelSmall, color = Primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tools_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (tool.builtin) {
                        // 内置工具仅可查看
                        DropdownMenuItem(text = { Text(stringResource(R.string.tools_view)) }, onClick = { menuExpanded = false; onView() })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tools_toggle)) }, onClick = { menuExpanded = false; onToggle() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = ErrorRed) }, onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.tools_description, tool.description ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.tools_created_at, tool.createdAt ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 工具新建/编辑表单弹窗（仅非内置工具使用）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolFormDialog(
    initial: Tool?,
    onConfirm: (ToolRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    val context = LocalContext.current

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.tools_new) else stringResource(R.string.tools_edit),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isBlank()) {
                Toast.makeText(context, context.getString(R.string.tools_name_required), Toast.LENGTH_SHORT).show()
            } else {
                val req = ToolRequest(
                    name = name,
                    description = description.ifBlank { null },
                    enabled = enabled
                )
                onConfirm(req)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tools_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.tools_description_label)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tools_enabled_label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
    }
}
