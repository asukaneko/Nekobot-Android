package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.model.MessageFilterConfig
import com.nekobot.app.data.model.MessageFilterRule
import com.nekobot.app.data.model.MessageFilterRuleRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 消息过滤页 ViewModel：管理全局过滤开关与全局过滤规则列表的加载、创建、更新、删除。
 *
 * 注意：本界面仅操作 global 规则，删除/更新时 channel 与 sessionId 参数均传 null。
 */
class MessageFilterViewModel : BaseViewModel() {

    /** 消息过滤全局配置 */
    private val _config = MutableStateFlow<MessageFilterConfig?>(null)
    val config: StateFlow<MessageFilterConfig?> = _config.asStateFlow()

    /** 全局过滤开关状态 */
    private val _globalEnabled = MutableStateFlow(false)
    val globalEnabled: MutableStateFlow<Boolean> = _globalEnabled

    init {
        load()
    }

    /** 加载消息过滤配置，并从 config.global 派生规则列表 */
    fun load() = launchResult(
        block = { unified.listMessageFilter() },
        onSuccess = {
            _config.value = it
            _globalEnabled.value = it?.enabled ?: false
        }
    )

    /** 创建全局过滤规则 */
    fun create(req: MessageFilterRuleRequest) = launchResult(
        block = { unified.createMessageFilterRule(req) },
        onSuccess = { load() }
    )

    /** 更新全局过滤规则（channel 与 sessionId 传 null，仅操作 global 规则） */
    fun update(id: String, req: MessageFilterRuleRequest) = launchResult(
        block = { unified.updateMessageFilterRule(id, null, null, req) },
        onSuccess = { load() }
    )

    /** 删除全局过滤规则（channel 与 sessionId 传 null，仅操作 global 规则） */
    fun delete(id: String) = launchResult(
        block = { unified.deleteMessageFilterRule(id, null, null) },
        onSuccess = { load() }
    )

    /** 切换全局过滤开关 */
    fun toggleGlobal(enabled: Boolean) = launchResult(
        block = { unified.toggleMessageFilter(enabled) },
        onSuccess = {
            _globalEnabled.value = enabled
            load()
        }
    )
}

/**
 * 消息过滤页：顶部全局开关 + 全局过滤规则列表，支持新建、编辑、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageFilterScreen(onBack: () -> Unit, viewModel: MessageFilterViewModel = viewModel()) {
    val config by viewModel.config.collectAsState()
    val globalEnabled by viewModel.globalEnabled.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    // 规则列表从 config.global 派生
    val list = config?.global ?: emptyList()

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MessageFilterRule?>(null) }
    var deleteTarget by remember { mutableStateOf<MessageFilterRule?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msgfilter_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.msgfilter_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editing = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.msgfilter_new), tint = MaterialTheme.colorScheme.primary)
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部全局开关
                item {
                    ToggleChipRow(
                        label = if (globalEnabled) stringResource(R.string.msgfilter_global_on) else stringResource(R.string.msgfilter_global_off),
                        selected = globalEnabled,
                        onClick = { viewModel.toggleGlobal(!globalEnabled) }
                    )
                }

                if (error != null) {
                    item {
                        ErrorBanner(message = error!!, onRetry = {
                            viewModel.clearError()
                            viewModel.load()
                        })
                    }
                }

                if (list.isEmpty() && !loading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.FilterAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(0.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.msgfilter_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.msgfilter_empty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                items(list, key = { it.id ?: it.pattern ?: it.hashCode().toString() }) { rule ->
                    FilterRuleCard(
                        rule = rule,
                        onEdit = {
                            editing = rule
                            showForm = true
                        },
                        onDelete = { deleteTarget = rule }
                    )
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        FilterRuleFormDialog(
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
    deleteTarget?.let { rule ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.msgfilter_confirm_delete),
            message = stringResource(R.string.msgfilter_delete_message, rule.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                rule.id?.let { viewModel.delete(it) }
                deleteTarget = null
            }
        )
    }
}

/** 单条过滤规则卡片 */
@Composable
private fun FilterRuleCard(
    rule: MessageFilterRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：pattern + 启用状态 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rule.pattern.ifBlank { stringResource(R.string.msgfilter_empty_rule) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 启用状态标记
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (rule.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (rule.enabled) stringResource(R.string.msgfilter_status_enabled) else stringResource(R.string.msgfilter_status_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 操作菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.msgfilter_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = {
                        Text(stringResource(R.string.common_delete), color = ErrorRed)
                    }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 属性标签行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BadgeChip(text = stringResource(R.string.msgfilter_type_prop, rule.type))
            BadgeChip(text = stringResource(R.string.msgfilter_action_prop, rule.action))
            BadgeChip(text = stringResource(R.string.msgfilter_target_prop, rule.filterTarget))
            BadgeChip(text = stringResource(R.string.msgfilter_scope_prop, rule.sessionScope))
        }
        if (rule.sessionScope == "specific" && !rule.sessionId.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.msgfilter_session_id, rule.sessionId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 过滤规则新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRuleFormDialog(
    initial: MessageFilterRule?,
    onConfirm: (MessageFilterRuleRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "keyword") }
    var action by remember { mutableStateOf(initial?.action ?: "strip") }
    var filterTarget by remember { mutableStateOf(initial?.filterTarget ?: "both") }
    var sessionScope by remember { mutableStateOf(initial?.sessionScope ?: "all") }
    var sessionId by remember { mutableStateOf(initial?.sessionId ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.msgfilter_new_rule) else stringResource(R.string.msgfilter_edit),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (pattern.isBlank()) return@NekoDialog
            val req = MessageFilterRuleRequest(
                pattern = pattern.trim(),
                type = type,
                action = action,
                filterTarget = filterTarget,
                sessionScope = sessionScope,
                sessionId = if (sessionScope == "specific") sessionId.trim().takeIf { it.isNotBlank() } else null,
                enabled = enabled
            )
            onConfirm(req)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 模式（必填）
            LabeledField(stringResource(R.string.msgfilter_pattern_label))
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
                placeholder = { Text(if (type == "regex") stringResource(R.string.msgfilter_regex_placeholder) else stringResource(R.string.msgfilter_keyword_placeholder)) }
            )
            Spacer(Modifier.height(8.dp))

            // 类型下拉
            LabeledField(stringResource(R.string.msgfilter_type_label))
            DropdownField(
                value = type,
                options = listOf("keyword", "regex"),
                onSelect = { type = it }
            )
            Spacer(Modifier.height(8.dp))

            // 动作下拉
            LabeledField(stringResource(R.string.msgfilter_action_label))
            DropdownField(
                value = action,
                options = listOf("strip", "recall"),
                onSelect = { action = it }
            )
            Spacer(Modifier.height(8.dp))

            // 过滤目标下拉
            LabeledField(stringResource(R.string.msgfilter_target_label))
            DropdownField(
                value = filterTarget,
                options = listOf("user", "ai", "both"),
                onSelect = { filterTarget = it }
            )
            Spacer(Modifier.height(8.dp))

            // 会话范围下拉
            LabeledField(stringResource(R.string.msgfilter_scope_label))
            DropdownField(
                value = sessionScope,
                options = listOf("all", "specific"),
                onSelect = { sessionScope = it }
            )
            Spacer(Modifier.height(8.dp))

            // 当 sessionScope=specific 时显示 sessionId 输入框
            if (sessionScope == "specific") {
                LabeledField(stringResource(R.string.msgfilter_session_id_label))
                OutlinedTextField(
                    value = sessionId,
                    onValueChange = { sessionId = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text(stringResource(R.string.msgfilter_session_id_hint)) }
                )
                Spacer(Modifier.height(8.dp))
            }

            // 启用开关
            ToggleRow(label = stringResource(R.string.msgfilter_enabled_label), checked = enabled, onCheckedChange = { enabled = it })
        }
    }
}

// ==================== 通用辅助组件 ====================

/** 标签徽章 */
@Composable
private fun BadgeChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}

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

/**
 * 切换芯片行：点击切换 selected 状态，选中时高亮。
 * 用于顶部全局开关。
 */
@Composable
private fun ToggleChipRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
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
