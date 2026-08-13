package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.model.TaskItem
import com.nekobot.app.data.model.TaskRequest
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
 * 任务中心 ViewModel：管理聚合任务的加载、创建、更新、删除、切换启停、手动执行。
 */
class TaskCenterViewModel : BaseViewModel() {

    private val _list = MutableStateFlow<List<TaskItem>>(emptyList())
    val list: StateFlow<List<TaskItem>> = _list.asStateFlow()

    init {
        load()
    }

    /** 加载任务列表 */
    fun load() = launchResult(
        block = { unified.listTasks() },
        onSuccess = { _list.value = it ?: emptyList() }
    )

    /** 创建任务 */
    fun create(req: TaskRequest) = launchResult(
        block = { unified.createTask(req) },
        onSuccess = { load() }
    )

    /** 更新任务 */
    fun update(id: String, req: TaskRequest) = launchResult(
        block = { unified.updateTask(id, req) },
        onSuccess = { load() }
    )

    /** 删除任务 */
    fun delete(id: String) = launchResult(
        block = { unified.deleteTask(id) },
        onSuccess = { load() }
    )

    /** 切换任务启停状态 */
    fun toggle(id: String) = launchResult(
        block = { unified.toggleTask(id) },
        onSuccess = { load() }
    )

    /** 手动执行任务 */
    fun run(id: String) = launchResult(
        block = { unified.runTask(id) },
        onSuccess = { showToast(string(R.string.tasks_executed)); load() }
    )
}

/**
 * 任务中心页：展示所有聚合任务，支持新建、编辑、删除、切换启停、手动执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCenterScreen(onBack: () -> Unit, viewModel: TaskCenterViewModel = viewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var deleteTarget by remember { mutableStateOf<TaskItem?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.tasks_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editing = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tasks_new), tint = MaterialTheme.colorScheme.primary)
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
                    title = stringResource(R.string.tasks_empty_title),
                    hint = stringResource(R.string.tasks_empty_hint),
                    icon = {
                        Icon(
                            Icons.Filled.Schedule,
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
                    items(list, key = { it.id.ifBlank { it.name + it.hashCode().toString() } }) { task ->
                        TaskCard(
                            task = task,
                            onEdit = {
                                editing = task
                                showForm = true
                            },
                            onDelete = { deleteTarget = task },
                            onToggle = { viewModel.toggle(task.id) },
                            onRun = { viewModel.run(task.id) }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        TaskFormDialog(
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
            title = stringResource(R.string.tasks_confirm_delete),
            message = stringResource(R.string.tasks_confirm_delete_msg, target.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            }
        )
    }
}

/** 任务 kind 徽章 */
@Composable
private fun KindBadge(kind: String) {
    val (badgeColor, badgeLabel) = when (kind) {
        "heartbeat" -> MaterialTheme.colorScheme.tertiary to stringResource(R.string.tasks_kind_heartbeat)
        "workflow" -> MaterialTheme.colorScheme.secondary to stringResource(R.string.tasks_kind_workflow)
        else -> MaterialTheme.colorScheme.primary to stringResource(R.string.tasks_kind_custom)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(badgeLabel, style = MaterialTheme.typography.labelSmall, color = badgeColor)
    }
}

/** 单个任务卡片 */
@Composable
private fun TaskCard(
    task: TaskItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onRun: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + kind 徽章 + 启停状态 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = task.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            KindBadge(task.kind)
            Spacer(Modifier.width(8.dp))
            // 启停状态标记
            val statusColor = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val statusText = if (task.enabled) stringResource(R.string.tasks_enabled) else stringResource(R.string.tasks_disabled)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Spacer(Modifier.width(8.dp))
            // 操作菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tasks_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text(if (task.enabled) stringResource(R.string.tasks_disabled) else stringResource(R.string.tasks_enabled)) }, onClick = { menuExpanded = false; onToggle() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tasks_manual_run)) }, onClick = { menuExpanded = false; onRun() })
                    // 仅 custom 类型可删除
                    if (task.kind == "custom") {
                        DropdownMenuItem(text = {
                            Text(stringResource(R.string.common_delete), color = ErrorRed)
                        }, onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 信息行
        val triggerLabel = when (task.trigger) {
            "interval" -> stringResource(R.string.tasks_trigger_interval)
            "cron" -> stringResource(R.string.tasks_trigger_cron)
            "run_at" -> stringResource(R.string.tasks_trigger_run_at)
            else -> task.trigger
        }
        Text(stringResource(R.string.tasks_trigger_method, triggerLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!task.description.isNullOrBlank()) {
            Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!task.lastRun.isNullOrBlank()) {
            Text(stringResource(R.string.tasks_last_run, task.lastRun), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!task.nextRun.isNullOrBlank()) {
            Text(stringResource(R.string.tasks_next_run, task.nextRun), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task.status != "idle") {
            val executionLabel = when (task.status) {
                "running" -> "执行中"
                "success" -> "上次执行成功"
                "failed" -> "上次执行失败"
                else -> task.status
            }
            Text(
                executionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (task.status == "failed") ErrorRed else MaterialTheme.colorScheme.primary
            )
        }
        task.lastError?.takeIf(String::isNotBlank)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 任务新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskFormDialog(
    initial: TaskItem?,
    onConfirm: (TaskRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var trigger by remember { mutableStateOf(initial?.trigger ?: "interval") }
    var targetSessionId by remember { mutableStateOf(initial?.targetSessionId ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    // trigger 对应的配置值
    var intervalMinutes by remember {
        mutableStateOf(
            initial?.config?.takeIf { it.isJsonObject }?.asJsonObject?.get("interval_minutes")?.asInt?.toString() ?: ""
        )
    }
    var cronExpr by remember {
        mutableStateOf(
            initial?.config?.takeIf { it.isJsonObject }?.asJsonObject?.get("cron")?.asString ?: ""
        )
    }
    var runAt by remember {
        mutableStateOf(
            initial?.config?.takeIf { it.isJsonObject }?.asJsonObject?.get("run_at")?.asString ?: ""
        )
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.tasks_new) else stringResource(R.string.tasks_edit),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            val config = when (trigger) {
                "interval" -> JsonObject().apply {
                    addProperty("interval_minutes", intervalMinutes.toIntOrNull() ?: 5)
                }
                "cron" -> JsonObject().apply { addProperty("cron", cronExpr.trim()) }
                "run_at" -> JsonObject().apply { addProperty("run_at", runAt.trim()) }
                else -> null
            }
            val req = TaskRequest(
                name = name.trim(),
                description = description.trim().takeIf { it.isNotBlank() },
                enabled = initial?.enabled ?: true,
                trigger = trigger,
                config = config,
                targetSessionId = targetSessionId.trim().takeIf { it.isNotBlank() },
                prompt = prompt.trim().takeIf { it.isNotBlank() }
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
            LabeledField(stringResource(R.string.tasks_name_required))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 描述
            LabeledField(stringResource(R.string.tasks_description))
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

            // 触发方式下拉
            LabeledField(stringResource(R.string.tasks_trigger_method_label))
            DropdownField(
                value = trigger,
                options = listOf("interval", "cron", "run_at"),
                onSelect = { trigger = it }
            )
            Spacer(Modifier.height(8.dp))

            // 根据触发方式显示不同配置字段
            when (trigger) {
                "interval" -> {
                    LabeledField(stringResource(R.string.tasks_interval_minutes))
                    OutlinedTextField(
                        value = intervalMinutes,
                        onValueChange = { intervalMinutes = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text(stringResource(R.string.tasks_interval_placeholder)) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                "cron" -> {
                    LabeledField(stringResource(R.string.tasks_cron_expr))
                    OutlinedTextField(
                        value = cronExpr,
                        onValueChange = { cronExpr = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        placeholder = { Text(stringResource(R.string.tasks_cron_placeholder)) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                "run_at" -> {
                    LabeledField(stringResource(R.string.tasks_run_at_time))
                    OutlinedTextField(
                        value = runAt,
                        onValueChange = { runAt = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        placeholder = { Text(stringResource(R.string.tasks_run_at_placeholder)) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // 目标会话 ID
            LabeledField(stringResource(R.string.tasks_target_session))
            OutlinedTextField(
                value = targetSessionId,
                onValueChange = { targetSessionId = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 提示词（多行）
            LabeledField(stringResource(R.string.tasks_prompt))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = false,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
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
