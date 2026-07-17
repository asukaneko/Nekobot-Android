package com.nekobot.app.ui.screens.extensions

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.data.model.Hook
import com.nekobot.app.data.model.HookExecutionLog
import com.nekobot.app.data.model.HookRequest
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
 * Hook 管理 ViewModel：管理对话 Hook 的加载、创建、更新、删除、切换启停、测试与日志查看。
 */
class HooksViewModel : BaseViewModel() {

    private val _list = MutableStateFlow<List<Hook>>(emptyList())
    val list: StateFlow<List<Hook>> = _list.asStateFlow()

    private val _logs = MutableStateFlow<List<HookExecutionLog>>(emptyList())
    val logs: StateFlow<List<HookExecutionLog>> = _logs.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init {
        load()
    }

    /** 加载 Hook 列表 */
    fun load() = launchResult(
        block = { unified.listHooks() },
        onSuccess = { _list.value = it ?: emptyList() }
    )

    /** 创建 Hook */
    fun create(req: HookRequest) = launchResult(
        block = { unified.createHook(req) },
        onSuccess = { load() }
    )

    /** 更新 Hook */
    fun update(id: String, req: HookRequest) = launchResult(
        block = { unified.updateHook(id, req) },
        onSuccess = { load() }
    )

    /** 删除 Hook */
    fun delete(id: String) = launchResult(
        block = { unified.deleteHook(id) },
        onSuccess = { load() }
    )

    /** 切换 Hook 启停状态 */
    fun toggle(id: String) = launchResult(
        block = { unified.toggleHook(id) },
        onSuccess = { load() }
    )

    /** 测试 Hook */
    fun testHook(body: JsonElement) = launchResult(
        block = { unified.testHook(body) },
        onSuccess = { _testResult.value = it?.toString() ?: "无返回结果" }
    )

    /** 加载 Hook 执行日志 */
    fun loadLogs(hookId: String) = launchResult(
        block = { unified.listHookLogs(hookId) },
        onSuccess = { _logs.value = it ?: emptyList() }
    )

    /** 清除测试结果 */
    fun clearTestResult() {
        _testResult.value = null
    }

    /** 清除日志 */
    fun clearLogs() {
        _logs.value = emptyList()
    }
}

/**
 * Hook 管理页：展示所有对话 Hook，支持新建、编辑、删除、切换启停、查看日志、测试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HooksScreen(onBack: () -> Unit, viewModel: HooksViewModel = viewModel()) {
    val list by viewModel.list.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Hook?>(null) }
    var deleteTarget by remember { mutableStateOf<Hook?>(null) }
    var logsTarget by remember { mutableStateOf<Hook?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Hook 管理", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editing = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 Hook", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (list.isEmpty() && !loading) {
                EmptyState(
                    title = "暂无 Hook",
                    hint = "点击右上角 + 添加",
                    icon = {
                        Icon(
                            Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(0.dp)
                        )
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                viewModel.clearError()
                                viewModel.load()
                            })
                        }
                    }
                    items(list, key = { it.id ?: it.name ?: it.hashCode().toString() }) { hook ->
                        HookCard(
                            hook = hook,
                            onEdit = {
                                editing = hook
                                showForm = true
                            },
                            onDelete = { deleteTarget = hook },
                            onToggle = { hook.id?.let { viewModel.toggle(it) } },
                            onLogs = {
                                logsTarget = hook
                                hook.id?.let { viewModel.loadLogs(it) }
                            },
                            onTest = {
                                val body = JsonObject().apply {
                                    addProperty("event", hook.event)
                                    addProperty("scope", hook.scope)
                                }
                                viewModel.testHook(body)
                            }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        HookFormDialog(
            initial = editing,
            onConfirm = { req ->
                editing?.id?.let { id -> viewModel.update(id, req) } ?: viewModel.create(req)
                showForm = false
                editing = null
            },
            onDismiss = {
                showForm = false
                editing = null
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { target ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = "确认删除",
            message = "确定删除 Hook「${target.displayName}」吗？",
            confirmText = "删除",
            onConfirm = {
                target.id?.let { viewModel.delete(it) }
                deleteTarget = null
            }
        )
    }

    // 执行日志弹窗
    logsTarget?.let { target ->
        NekoDialog(
            onDismiss = {
                logsTarget = null
                viewModel.clearLogs()
            },
            title = "执行日志 - ${target.displayName}",
            confirmText = "关闭",
            onConfirm = {
                logsTarget = null
                viewModel.clearLogs()
            },
            cancelText = null,
            onCancel = null
        ) {
            if (logs.isEmpty()) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    logs.forEach { log -> HookLogItem(log) }
                }
            }
        }
    }

    // 测试结果弹窗
    testResult?.let { result ->
        NekoDialog(
            onDismiss = { viewModel.clearTestResult() },
            title = "测试结果",
            message = result,
            confirmText = "确定",
            onConfirm = { viewModel.clearTestResult() },
            cancelText = null,
            onCancel = null
        )
    }
}

/** 单个 Hook 卡片 */
@Composable
private fun HookCard(
    hook: Hook,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onLogs: () -> Unit,
    onTest: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 启停状态 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hook.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 启停状态标记
            val statusColor = if (hook.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val statusText = if (hook.enabled) "启用" else "停用"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(Modifier.width(8.dp))
            // 操作菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text(if (hook.enabled) "停用" else "启用") }, onClick = { menuExpanded = false; onToggle() })
                    DropdownMenuItem(text = { Text("查看日志") }, onClick = { menuExpanded = false; onLogs() })
                    DropdownMenuItem(text = { Text("测试") }, onClick = { menuExpanded = false; onTest() })
                    DropdownMenuItem(text = {
                        Text("删除", color = ErrorRed)
                    }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 信息行
        Text("事件: ${hook.event}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("作用域: ${hook.scope}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("优先级: ${hook.priority}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!hook.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(hook.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Hook 执行日志条目 */
@Composable
private fun HookLogItem(log: HookExecutionLog) {
    val statusColor = when (log.status.lowercase()) {
        "success", "ok" -> MaterialTheme.colorScheme.primary
        "error", "failed" -> ErrorRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = log.status.ifBlank { "未知" },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "耗时: ${log.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!log.createdAt.isNullOrBlank()) {
                    Text(
                        "时间: ${log.createdAt}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!log.error.isNullOrBlank()) {
                    Text(
                        "错误: ${log.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorRed,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Hook 新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HookFormDialog(
    initial: Hook?,
    onConfirm: (HookRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var event by remember { mutableStateOf(initial?.event ?: "") }
    var scope by remember { mutableStateOf(initial?.scope ?: "global") }
    var priority by remember { mutableStateOf(initial?.priority?.toString() ?: "100") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var triggerMode by remember { mutableStateOf(initial?.triggerMode ?: "always") }
    var conditionLogic by remember { mutableStateOf(initial?.conditionLogic ?: "and") }
    var timeoutMs by remember { mutableStateOf(initial?.timeoutMs?.toString() ?: "3000") }
    var maxRetries by remember { mutableStateOf(initial?.maxRetries?.toString() ?: "0") }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) "新建 Hook" else "编辑 Hook",
        confirmText = "保存",
        onConfirm = {
            if (name.isBlank() || event.isBlank()) return@NekoDialog
            val req = HookRequest(
                name = name.trim(),
                event = event.trim(),
                scope = scope,
                priority = priority.toIntOrNull() ?: 100,
                description = description.trim().takeIf { it.isNotBlank() },
                triggerMode = triggerMode,
                conditionLogic = conditionLogic,
                timeoutMs = timeoutMs.toIntOrNull() ?: 3000,
                maxRetries = maxRetries.toIntOrNull() ?: 0
            )
            onConfirm(req)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 名称（必填）
            LabeledField("名称 *")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 事件（必填）
            LabeledField("事件 *")
            OutlinedTextField(
                value = event,
                onValueChange = { event = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                placeholder = { Text("如 character.message") }
            )
            Spacer(Modifier.height(8.dp))

            // 作用域下拉
            LabeledField("作用域")
            DropdownField(
                value = scope,
                options = listOf("global", "character", "conversation", "user"),
                onSelect = { scope = it }
            )
            Spacer(Modifier.height(8.dp))

            // 优先级
            LabeledField("优先级")
            OutlinedTextField(
                value = priority,
                onValueChange = { priority = it.filter { c -> c.isDigit() } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))

            // 描述
            LabeledField("描述")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 触发模式下拉
            LabeledField("触发模式")
            DropdownField(
                value = triggerMode,
                options = listOf("always", "once_per_conversation"),
                onSelect = { triggerMode = it }
            )
            Spacer(Modifier.height(8.dp))

            // 条件逻辑下拉
            LabeledField("条件逻辑")
            DropdownField(
                value = conditionLogic,
                options = listOf("and", "or"),
                onSelect = { conditionLogic = it }
            )
            Spacer(Modifier.height(8.dp))

            // 超时（毫秒）
            LabeledField("超时 (ms)")
            OutlinedTextField(
                value = timeoutMs,
                onValueChange = { timeoutMs = it.filter { c -> c.isDigit() } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))

            // 最大重试次数
            LabeledField("最大重试次数")
            OutlinedTextField(
                value = maxRetries,
                onValueChange = { maxRetries = it.filter { c -> c.isDigit() } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

// ==================== 通用辅助组件 ====================

/** 字段标签 */
@Composable
private fun LabeledField(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 下拉选择字段 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 统一的输入框配色 */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
)
