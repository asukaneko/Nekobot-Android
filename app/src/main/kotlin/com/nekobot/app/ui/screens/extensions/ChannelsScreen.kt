package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.material.icons.filled.Campaign
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
import com.nekobot.app.data.model.Channel
import com.nekobot.app.data.model.ChannelPreset
import com.nekobot.app.data.model.ChannelRequest
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
 * 频道管理页 ViewModel：管理频道列表的加载、创建、更新、删除、启停、从预设创建。
 */
class ChannelsViewModel : BaseViewModel() {

    private val _list = MutableStateFlow<List<Channel>>(emptyList())
    val list: StateFlow<List<Channel>> = _list.asStateFlow()

    /** 频道预设列表（从预设创建时使用） */
    private val _presets = MutableStateFlow<List<ChannelPreset>>(emptyList())
    val presets: StateFlow<List<ChannelPreset>> = _presets.asStateFlow()

    init {
        load()
    }

    /** 加载频道列表 */
    fun load() = launchResult(
        block = { unified.listChannels() },
        onSuccess = { _list.value = it ?: emptyList() }
    )

    /** 加载频道预设列表 */
    fun loadPresets() = launchResult(
        block = { unified.channelPresets() },
        onSuccess = { _presets.value = it ?: emptyList() }
    )

    /** 创建频道 */
    fun create(req: ChannelRequest) = launchResult(
        block = { unified.createChannel(req) },
        onSuccess = { load() }
    )

    /** 更新频道 */
    fun update(id: String, req: ChannelRequest) = launchResult(
        block = { unified.updateChannel(id, req) },
        onSuccess = { load() }
    )

    /** 删除频道 */
    fun delete(id: String) = launchResult(
        block = { unified.deleteChannel(id) },
        onSuccess = { load() }
    )

    /** 切换频道启停 */
    fun toggle(id: String) = launchResult(
        block = { unified.toggleChannel(id) },
        onSuccess = { load() }
    )

    /** 从预设创建频道 */
    fun createFromPreset(presetId: String) = launchResult(
        block = { unified.createChannelFromPreset(presetId) },
        onSuccess = {
            showToast(string(R.string.channels_created_from_preset))
            load()
        }
    )
}

/**
 * 频道管理页：展示所有频道，支持新建、编辑、删除、启停、从预设创建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(onBack: () -> Unit, viewModel: ChannelsViewModel = viewModel()) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Channel?>(null) }
    var deleteTarget by remember { mutableStateOf<Channel?>(null) }
    // 顶部 Add 菜单展开状态
    var addMenuExpanded by remember { mutableStateOf(false) }
    // 从预设创建弹窗
    var showPresetDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channels_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.channels_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 顶部右上角 Add 按钮打开菜单
                    Box {
                        IconButton(onClick = { addMenuExpanded = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.channels_new), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = addMenuExpanded,
                            onDismissRequest = { addMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.channels_new_custom)) },
                                onClick = {
                                    addMenuExpanded = false
                                    editing = null
                                    showForm = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.channels_new_from_preset)) },
                                onClick = {
                                    addMenuExpanded = false
                                    viewModel.loadPresets()
                                    showPresetDialog = true
                                }
                            )
                        }
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
                    title = stringResource(R.string.channels_empty_title),
                    hint = stringResource(R.string.channels_empty_hint),
                    icon = {
                        Icon(
                            Icons.Filled.Campaign,
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
                    items(list, key = { it.id ?: it.name ?: it.hashCode().toString() }) { channel ->
                        ChannelCard(
                            channel = channel,
                            onEdit = {
                                editing = channel
                                showForm = true
                            },
                            onDelete = { deleteTarget = channel },
                            onToggle = { channel.id?.let { viewModel.toggle(it) } }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        ChannelFormDialog(
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
    deleteTarget?.let { channel ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.channels_confirm_delete),
            message = stringResource(R.string.channels_delete_message, channel.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                channel.id?.let { viewModel.delete(it) }
                deleteTarget = null
            }
        )
    }

    // 从预设创建弹窗
    if (showPresetDialog) {
        PresetListDialog(
            presets = presets,
            onPick = { preset ->
                viewModel.createFromPreset(preset.id)
                showPresetDialog = false
            },
            onDismiss = { showPresetDialog = false }
        )
    }
}

/** 单个频道卡片 */
@Composable
private fun ChannelCard(
    channel: Channel,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 内置标记 + 启用状态 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 内置标记
            if (channel.builtin) {
                BadgeChip(text = stringResource(R.string.channels_builtin_badge), color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            // 启用状态标记
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (channel.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (channel.enabled) stringResource(R.string.channels_status_enabled) else stringResource(R.string.channels_status_disabled),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (channel.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 操作菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.channels_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(
                        text = { Text(if (channel.enabled) stringResource(R.string.channels_disable) else stringResource(R.string.channels_enable)) },
                        onClick = { menuExpanded = false; onToggle() }
                    )
                    // 内置频道不可删除
                    if (!channel.builtin) {
                        DropdownMenuItem(text = {
                            Text(stringResource(R.string.common_delete), color = ErrorRed)
                        }, onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 信息行
        Text(stringResource(R.string.channels_type, channel.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.channels_transport, channel.transport), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!channel.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(channel.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 频道新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelFormDialog(
    initial: Channel?,
    onConfirm: (ChannelRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "custom") }
    var transport by remember { mutableStateOf(initial?.transport ?: "webhook") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.channels_new) else stringResource(R.string.channels_edit),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            val req = ChannelRequest(
                name = name.trim(),
                type = type,
                transport = transport,
                description = description.trim().takeIf { it.isNotBlank() },
                enabled = enabled
            )
            onConfirm(req)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 名称（必填）
            LabeledField(stringResource(R.string.channels_name_label))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 类型下拉
            LabeledField(stringResource(R.string.channels_type_label))
            DropdownField(
                value = type,
                options = listOf("custom", "telegram", "feishu", "feishu_ws", "qqbot", "web", "qq"),
                onSelect = { type = it }
            )
            Spacer(Modifier.height(8.dp))

            // 传输方式下拉
            LabeledField(stringResource(R.string.channels_transport_label))
            DropdownField(
                value = transport,
                options = listOf("webhook", "websocket", "socketio", "napcat"),
                onSelect = { transport = it }
            )
            Spacer(Modifier.height(8.dp))

            // 描述
            LabeledField(stringResource(R.string.channels_description_label))
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

            // 启用开关
            ToggleRow(label = stringResource(R.string.channels_enabled_label), checked = enabled, onCheckedChange = { enabled = it })
        }
    }
}

/**
 * 从预设创建频道弹窗：展示预设列表供用户选择
 */
@Composable
private fun PresetListDialog(
    presets: List<ChannelPreset>,
    onPick: (ChannelPreset) -> Unit,
    onDismiss: () -> Unit
) {
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.channels_preset_title),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        cancelText = null,
        onCancel = null
    ) {
        if (presets.isEmpty()) {
            Text(stringResource(R.string.channels_no_presets), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(preset) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name.ifBlank { preset.id },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.channels_preset_info, preset.type, preset.transport),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!preset.description.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.common_create),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 通用辅助组件 ====================

/** 标签徽章（内置标记等） */
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
