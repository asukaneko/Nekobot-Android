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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.AutoAwesome
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
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.model.Workflow
import com.nekobot.app.data.model.WorkflowRequest
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
 * 工作流 ViewModel：管理工作流的加载、创建、更新、删除、切换启停、手动执行。
 */
class WorkflowsViewModel : BaseViewModel() {

    private val _list = MutableStateFlow<List<Workflow>>(emptyList())
    val list: StateFlow<List<Workflow>> = _list.asStateFlow()

    private val _aiGenerated = MutableStateFlow<WorkflowRequest?>(null)
    val aiGenerated: StateFlow<WorkflowRequest?> = _aiGenerated.asStateFlow()

    init {
        load()
    }

    /** 加载工作流列表 */
    fun load() = launchResult(
        block = { unified.listWorkflows() },
        onSuccess = { _list.value = it ?: emptyList() }
    )

    /** 创建工作流 */
    fun create(req: WorkflowRequest) = launchResult(
        block = { unified.createWorkflow(req) },
        onSuccess = { load() }
    )

    /** 更新工作流 */
    fun update(id: String, req: WorkflowRequest) = launchResult(
        block = { unified.updateWorkflow(id, req) },
        onSuccess = { load() }
    )

    /** 删除工作流 */
    fun delete(id: String) = launchResult(
        block = { unified.deleteWorkflow(id) },
        onSuccess = { load() }
    )

    /** 切换工作流启停状态 */
    fun toggle(id: String) = launchResult(
        block = { unified.toggleWorkflow(id) },
        onSuccess = { load() }
    )

    /** 手动执行工作流 */
    fun execute(id: String) = launchResult(
        block = { unified.executeWorkflow(id) },
        onSuccess = { showToast(string(R.string.workflows_executed)); load() }
    )

    /** 调用 AI 生成工作流配置（不直接持久化，由 UI 决定是否创建） */
    fun aiGenerate(description: String) = launchResult(
        block = { unified.aiGenerateWorkflow(description) },
        onSuccess = { _aiGenerated.value = it },
        onError = { showToast(string(R.string.workflows_ai_generate_failed)) }
    )

    /** 清除 AI 生成预览 */
    fun clearAiGenerated() {
        _aiGenerated.value = null
    }
}

/**
 * 工作流管理页：展示所有工作流，支持新建、编辑、删除、切换启停、手动执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(onBack: () -> Unit, viewModel: WorkflowsViewModel = viewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val aiGenerated by viewModel.aiGenerated.collectAsStateWithLifecycle()

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Workflow?>(null) }
    var deleteTarget by remember { mutableStateOf<Workflow?>(null) }
    var showAiGenerate by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workflows_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.workflows_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showAiGenerate = true }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(R.string.workflows_ai_generate), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editing = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.workflows_new), tint = MaterialTheme.colorScheme.primary)
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
                    title = stringResource(R.string.workflows_empty_title),
                    hint = stringResource(R.string.workflows_empty_hint),
                    icon = {
                        Icon(
                            Icons.Filled.AppRegistration,
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
                    items(list, key = { it.id ?: it.name ?: it.hashCode().toString() }) { workflow ->
                        WorkflowCard(
                            workflow = workflow,
                            onEdit = {
                                editing = workflow
                                showForm = true
                            },
                            onDelete = { deleteTarget = workflow },
                            onToggle = { workflow.id?.let { viewModel.toggle(it) } },
                            onExecute = { workflow.id?.let { viewModel.execute(it) } }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        WorkflowFormDialog(
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
            title = stringResource(R.string.workflows_confirm_delete),
            message = stringResource(R.string.workflows_confirm_delete_msg, target.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                target.id?.let { viewModel.delete(it) }
                deleteTarget = null
            }
        )
    }

    // AI 生成弹窗
    if (showAiGenerate) {
        WorkflowAiGenerateDialog(
            loading = loading,
            generated = aiGenerated,
            onGenerate = { viewModel.aiGenerate(it) },
            onApply = { req ->
                viewModel.create(req)
                viewModel.clearAiGenerated()
                showAiGenerate = false
            },
            onDismiss = {
                viewModel.clearAiGenerated()
                showAiGenerate = false
            }
        )
    }
}

/** 单个工作流卡片 */
@Composable
private fun WorkflowCard(
    workflow: Workflow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onExecute: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 启停状态 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = workflow.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 启停状态标记
            val statusColor = if (workflow.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val statusText = if (workflow.enabled) stringResource(R.string.workflows_enabled) else stringResource(R.string.workflows_disabled)
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
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.workflows_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text(if (workflow.enabled) stringResource(R.string.workflows_disabled) else stringResource(R.string.workflows_enabled)) }, onClick = { menuExpanded = false; onToggle() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.workflows_manual_run)) }, onClick = { menuExpanded = false; onExecute() })
                    DropdownMenuItem(text = {
                        Text(stringResource(R.string.common_delete), color = ErrorRed)
                    }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 信息行
        val triggerLabel = when (workflow.trigger) {
            "manual" -> stringResource(R.string.workflows_trigger_manual)
            "cron" -> stringResource(R.string.workflows_trigger_cron)
            else -> workflow.trigger
        }
        Text(stringResource(R.string.workflows_trigger_method, triggerLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!workflow.description.isNullOrBlank()) {
            Text(workflow.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        workflow.nextRun?.takeIf(String::isNotBlank)?.let {
            Text(stringResource(R.string.schedule_next_run, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        workflow.lastRun?.takeIf(String::isNotBlank)?.let {
            Text(stringResource(R.string.schedule_last_run, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (workflow.status != "idle") {
            val executionLabel = when (workflow.status) {
                "running" -> stringResource(R.string.schedule_running)
                "success" -> stringResource(R.string.schedule_last_success)
                "failed" -> stringResource(R.string.schedule_last_failed)
                else -> workflow.status
            }
            Text(
                executionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (workflow.status == "failed") ErrorRed else MaterialTheme.colorScheme.primary
            )
        }
        workflow.lastError?.takeIf(String::isNotBlank)?.let { message ->
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
 * 工作流新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowFormDialog(
    initial: Workflow?,
    onConfirm: (WorkflowRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var trigger by remember { mutableStateOf(initial?.trigger ?: "manual") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    // cron 触发时的配置值
    var cronConfig by remember {
        mutableStateOf(
            initial?.config?.toCronConfig() ?: ""
        )
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.workflows_new) else stringResource(R.string.workflows_edit),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            val config: JsonElement? = when (trigger) {
                "cron" -> JsonObject().apply { addProperty("cron", cronConfig.trim()) }
                else -> null
            }
            val req = WorkflowRequest(
                name = name.trim(),
                description = description.trim().takeIf { it.isNotBlank() },
                enabled = enabled,
                trigger = trigger,
                config = config
            )
            onConfirm(req)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 名称（必填）
            LabeledField(stringResource(R.string.workflows_name_required))
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
            LabeledField(stringResource(R.string.workflows_description))
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
            LabeledField(stringResource(R.string.workflows_trigger_method_label))
            DropdownField(
                value = trigger,
                options = listOf("manual", "cron"),
                onSelect = { trigger = it }
            )
            Spacer(Modifier.height(8.dp))

            // cron 模式下显示 cron 配置输入框
            if (trigger == "cron") {
                LabeledField(stringResource(R.string.workflows_cron_expr))
                OutlinedTextField(
                    value = cronConfig,
                    onValueChange = { cronConfig = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text(stringResource(R.string.workflows_cron_placeholder)) }
                )
                Spacer(Modifier.height(8.dp))
            }

            // 启用开关
            ToggleRow(label = stringResource(R.string.workflows_enabled), checked = enabled, onCheckedChange = { enabled = it })
        }
    }
}

// ==================== 通用辅助组件与扩展 ====================

/** 字段标签 */
@Composable
private fun LabeledField(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 启用开关行 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
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

/** 从 config JsonElement 中提取 cron 表达式，兼容 "cron" 与 "time" 两种 key。 */
private fun JsonElement?.toCronConfig(): String {
    if (this == null || !this.isJsonObject) return ""
    val obj = this.asJsonObject
    return obj.get("cron")?.asString ?: obj.get("time")?.asString ?: ""
}

// ==================== AI 生成工作流弹窗 ====================

/**
 * AI 生成工作流弹窗：用户输入描述，AI 返回 WorkflowRequest，预览后可一键应用并创建。
 */
@Composable
private fun WorkflowAiGenerateDialog(
    loading: Boolean,
    generated: WorkflowRequest?,
    onGenerate: (String) -> Unit,
    onApply: (WorkflowRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.workflows_ai_generate_title),
        confirmText = if (generated != null) {
            stringResource(R.string.workflows_ai_apply)
        } else {
            stringResource(R.string.common_close)
        },
        onConfirm = {
            if (generated != null) {
                onApply(generated)
            } else {
                onDismiss()
            }
        },
        cancelText = if (generated != null) stringResource(R.string.common_close) else null,
        onCancel = if (generated != null) ({ onDismiss() }) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 描述输入框
            LabeledField(stringResource(R.string.workflows_ai_prompt))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                placeholder = { Text(stringResource(R.string.workflows_ai_prompt_hint)) },
                enabled = generated == null
            )

            // 生成按钮
            if (generated == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (loading) {
                        Text(
                            stringResource(R.string.workflows_ai_generating),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { onGenerate(prompt.trim()) },
                        enabled = prompt.isNotBlank() && !loading
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(R.string.workflows_ai_generate),
                            tint = if (prompt.isNotBlank() && !loading)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 生成预览
            generated?.let { req ->
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.workflows_ai_preview),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PreviewRow(label = stringResource(R.string.workflows_name_required), value = req.name)
                    req.description?.let {
                        PreviewRow(label = stringResource(R.string.workflows_description), value = it)
                    }
                    val triggerLabel = when (req.trigger) {
                        "manual" -> stringResource(R.string.workflows_trigger_manual)
                        "cron" -> stringResource(R.string.workflows_trigger_cron)
                        else -> req.trigger
                    }
                    PreviewRow(
                        label = stringResource(R.string.workflows_trigger_method_label),
                        value = triggerLabel
                    )
                    if (req.trigger == "cron") {
                        req.config?.toCronConfig()?.takeIf { it.isNotBlank() }?.let { cron ->
                            PreviewRow(label = stringResource(R.string.workflows_cron_expr), value = cron)
                        }
                    }
                }
            }

            if (loading && generated == null) {
                LoadingOverlay(visible = true)
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
