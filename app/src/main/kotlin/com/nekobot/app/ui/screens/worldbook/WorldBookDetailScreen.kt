package com.nekobot.app.ui.screens.worldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.model.WorldBookEntryRequest
import com.nekobot.app.data.model.WorldBookRequest
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 条目位置可选项
private val POSITION_OPTIONS = listOf("before_char", "after_char", "before_an", "after_an")

/**
 * 世界书详情/条目管理页 ViewModel：管理世界书信息与条目列表的增删改。
 */
class WorldBookViewModel(bookId: String) : com.nekobot.app.ui.BaseViewModel() {

    private val currentBookId = bookId

    private val _book = MutableStateFlow<WorldBook?>(null)
    val book: StateFlow<WorldBook?> = _book.asStateFlow()

    private val _entries = MutableStateFlow<List<WorldBookEntry>>(emptyList())
    val entries: StateFlow<List<WorldBookEntry>> = _entries.asStateFlow()

    // 条目编辑对话框状态
    private val _editingEntry = MutableStateFlow<WorldBookEntry?>(null)
    val editingEntry: StateFlow<WorldBookEntry?> = _editingEntry.asStateFlow()

    private val _showEntryDialog = MutableStateFlow(false)
    val showEntryDialog: StateFlow<Boolean> = _showEntryDialog.asStateFlow()

    // 条目编辑字段
    val entryKeys = MutableStateFlow("")
    val entryContent = MutableStateFlow("")
    val entryComment = MutableStateFlow("")
    val entryEnabled = MutableStateFlow(true)
    val entryConstant = MutableStateFlow(false)
    val entrySelective = MutableStateFlow(true)
    val entryPosition = MutableStateFlow(POSITION_OPTIONS.first())
    val entryPriority = MutableStateFlow(10)
    val entryCaseSensitive = MutableStateFlow(false)

    init { load(bookId) }

    /** 加载世界书信息与条目列表 */
    fun load(bookId: String) {
        launchResult(
            block = { unified.getWorldBook(bookId) },
            onSuccess = { _book.value = it }
        )
        launchResult(
            block = { unified.listEntries(bookId) },
            onSuccess = { _entries.value = it ?: emptyList() }
        )
    }

    /** 切换启用状态：用当前书信息 + 新 enabled 调更新接口 */
    fun toggleEnabled(enabled: Boolean) {
        val b = _book.value ?: return
        val name = b.name ?: return
        launchResult(
            block = {
                unified.updateWorldBook(
                    currentBookId,
                    WorldBookRequest(
                        name = name,
                        description = b.description,
                        characterId = b.characterId,
                        enabled = enabled
                    )
                )
            },
            onSuccess = { updated -> _book.value = updated }
        )
    }

    /** 更新世界书信息（名称、描述） */
    fun updateBook(name: String, description: String?) {
        val b = _book.value
        launchResult(
            block = {
                unified.updateWorldBook(
                    currentBookId,
                    WorldBookRequest(
                        name = name,
                        description = description,
                        characterId = b?.characterId,
                        enabled = b?.enabled
                    )
                )
            },
            onSuccess = { updated ->
                _book.value = updated
                showToast("已更新世界书")
            }
        )
    }

    /** 删除世界书 */
    fun deleteBook(onSuccess: () -> Unit) {
        launchResult(
            block = { unified.deleteWorldBook(currentBookId) },
            onSuccess = { onSuccess() }
        )
    }

    /** 开始新建条目：重置字段并打开对话框 */
    fun startNewEntry() {
        _editingEntry.value = null
        entryKeys.value = ""
        entryContent.value = ""
        entryComment.value = ""
        entryEnabled.value = true
        entryConstant.value = false
        entrySelective.value = true
        entryPosition.value = POSITION_OPTIONS.first()
        entryPriority.value = 10
        entryCaseSensitive.value = false
        _showEntryDialog.value = true
    }

    /** 开始编辑条目：填充字段并打开对话框 */
    fun startEditEntry(entry: WorldBookEntry) {
        _editingEntry.value = entry
        entryKeys.value = entry.keys?.joinToString(", ").orEmpty()
        entryContent.value = entry.content.orEmpty()
        entryComment.value = entry.comment.orEmpty()
        entryEnabled.value = entry.enabled ?: true
        entryConstant.value = entry.constant ?: false
        entrySelective.value = entry.selective ?: true
        entryPosition.value = entry.position ?: POSITION_OPTIONS.first()
        entryPriority.value = entry.priority ?: 10
        entryCaseSensitive.value = entry.caseSensitive ?: false
        _showEntryDialog.value = true
    }

    /** 关闭条目对话框 */
    fun dismissEntryDialog() {
        _showEntryDialog.value = false
        _editingEntry.value = null
    }

    /** 保存条目：editingEntry 为空表示新建，否则更新 */
    fun saveEntry() {
        val keys = entryKeys.value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val req = WorldBookEntryRequest(
            keys = keys.takeIf { it.isNotEmpty() },
            content = entryContent.value.trim().takeIf { it.isNotBlank() },
            comment = entryComment.value.trim().takeIf { it.isNotBlank() },
            enabled = entryEnabled.value,
            constant = entryConstant.value,
            selective = entrySelective.value,
            position = entryPosition.value,
            priority = entryPriority.value,
            caseSensitive = entryCaseSensitive.value
        )
        val editing = _editingEntry.value
        if (editing == null) {
            // 新建
            launchResult(
                block = { unified.createEntry(currentBookId, req) },
                onSuccess = {
                    _entries.value = _entries.value + it
                    _showEntryDialog.value = false
                    showToast("已创建条目")
                }
            )
        } else {
            val entryId = editing.id ?: return
            launchResult(
                block = { unified.updateEntry(currentBookId, entryId, req) },
                onSuccess = { updated ->
                    _entries.value = _entries.value.map { if (it.id == entryId) updated else it }
                    _showEntryDialog.value = false
                    _editingEntry.value = null
                    showToast("已更新条目")
                }
            )
        }
    }

    /** 删除条目 */
    fun deleteEntry(id: String) {
        launchResult(
            block = { unified.deleteEntry(currentBookId, id) },
            onSuccess = {
                _entries.value = _entries.value.filterNot { it.id == id }
                showToast("已删除条目")
            }
        )
    }
}

/**
 * 世界书详情/条目管理页：展示书信息、管理条目增删改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookDetailScreen(
    bookId: String,
    onBack: () -> Unit
) {
    val vm: WorldBookViewModel = viewModel(
        key = "wb_$bookId",
        factory = viewModelFactory { initializer { WorldBookViewModel(bookId) } }
    )
    val book by vm.book.collectAsState()
    val entries by vm.entries.collectAsState()
    val loading by vm.loading.collectAsState()
    val showEntryDialog by vm.showEntryDialog.collectAsState()
    val editingEntry by vm.editingEntry.collectAsState()

    var showEditBookDialog by remember { mutableStateOf(false) }
    var showDeleteBookDialog by remember { mutableStateOf(false) }
    var deleteEntryId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        book?.displayName ?: "世界书详情",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditBookDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑书信息", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showDeleteBookDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除书", tint = MaterialTheme.colorScheme.error)
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
                // 书信息卡片
                item {
                    BookInfoCard(
                        book = book,
                        onToggleEnabled = { vm.toggleEnabled(it) }
                    )
                }
                // 条目列表标题 + 新建按钮
                item {
                    SectionHeader(
                        title = "条目列表",
                        subtitle = "共 ${entries.size} 条"
                    ) {
                        TextButton(onClick = { vm.startNewEntry() }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("新建条目", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                // 条目卡片
                items(entries, key = { it.id ?: it.hashCode().toString() }) { entry ->
                    EntryItem(
                        entry = entry,
                        onEdit = { vm.startEditEntry(entry) },
                        onDelete = { deleteEntryId = entry.id }
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "暂无条目，点击右上角新建",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 编辑书信息对话框
    if (showEditBookDialog) {
        EditBookDialog(
            initialName = book?.name.orEmpty(),
            initialDesc = book?.description.orEmpty(),
            onDismiss = { showEditBookDialog = false },
            onConfirm = { name, desc ->
                vm.updateBook(name, desc)
                showEditBookDialog = false
            }
        )
    }

    // 删除书确认
    if (showDeleteBookDialog) {
        NekoDialog(
            onDismiss = { showDeleteBookDialog = false },
            title = "删除世界书",
            message = "确定要删除该世界书及其所有条目吗？此操作不可撤销。",
            confirmText = "删除",
            cancelText = "取消",
            onConfirm = {
                showDeleteBookDialog = false
                vm.deleteBook(onBack)
            },
            onCancel = { showDeleteBookDialog = false }
        )
    }

    // 删除条目确认
    if (deleteEntryId != null) {
        NekoDialog(
            onDismiss = { deleteEntryId = null },
            title = "删除条目",
            message = "确定要删除该条目吗？",
            confirmText = "删除",
            cancelText = "取消",
            onConfirm = {
                deleteEntryId?.let { vm.deleteEntry(it) }
                deleteEntryId = null
            },
            onCancel = { deleteEntryId = null }
        )
    }

    // 条目编辑对话框
    if (showEntryDialog) {
        EntryEditDialog(vm = vm, isEdit = editingEntry != null)
    }
}

/** 书信息卡片 */
@Composable
private fun BookInfoCard(book: WorldBook?, onToggleEnabled: (Boolean) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book?.displayName ?: "未命名世界书",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                if (!book?.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = book!!.description!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "绑定角色：${book?.characterId ?: "无"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = book?.enabled == true,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

/** 单个条目卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryItem(
    entry: WorldBookEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                // 关键词 Chip 行
                val keys = entry.keys
                if (!keys.isNullOrEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        keys.forEach { k ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        k,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 内容预览
                Text(
                    text = entry.content.orEmpty().ifBlank { "（无内容）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.comment.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "备注：${entry.comment}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 开关行：常驻/选择/启用
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SwitchLabel("常驻", entry.constant == true)
                    SwitchLabel("选择", entry.selective == true)
                    SwitchLabel("启用", entry.enabled == true)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 小型只读开关标签 */
@Composable
private fun SwitchLabel(label: String, checked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.scale(0.8f)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 条目编辑对话框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditDialog(vm: WorldBookViewModel, isEdit: Boolean) {
    val keys by vm.entryKeys.collectAsState()
    val content by vm.entryContent.collectAsState()
    val comment by vm.entryComment.collectAsState()
    val enabled by vm.entryEnabled.collectAsState()
    val constant by vm.entryConstant.collectAsState()
    val selective by vm.entrySelective.collectAsState()
    val position by vm.entryPosition.collectAsState()
    val priority by vm.entryPriority.collectAsState()
    val caseSensitive by vm.entryCaseSensitive.collectAsState()

    var positionExpanded by remember { mutableStateOf(false) }

    NekoDialog(
        onDismiss = { vm.dismissEntryDialog() },
        title = if (isEdit) "编辑条目" else "新建条目",
        confirmText = "保存",
        cancelText = "取消",
        onConfirm = { vm.saveEntry() },
        onCancel = { vm.dismissEntryDialog() }
    ) {
        // 关键词（逗号分隔）
        Text("关键词（逗号分隔）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = keys,
            onValueChange = { vm.entryKeys.value = it },
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        // 内容（多行）
        Text("内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = content,
            onValueChange = { vm.entryContent.value = it },
            singleLine = false,
            minLines = 3,
            maxLines = 6
        )
        Spacer(Modifier.height(10.dp))
        // 备注
        Text("备注", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = comment,
            onValueChange = { vm.entryComment.value = it },
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        // 位置下拉
        Text("位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = positionExpanded,
            onExpandedChange = { positionExpanded = it }
        ) {
            OutlinedTextField(
                value = position,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = positionExpanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            ExposedDropdownMenu(
                expanded = positionExpanded,
                onDismissRequest = { positionExpanded = false }
            ) {
                POSITION_OPTIONS.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt, color = MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            vm.entryPosition.value = opt
                            positionExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 优先级（数字）
        Text("优先级", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = priority.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { vm.entryPriority.value = it } },
            singleLine = true,
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(12.dp))
        // 开关组
        SwitchRow("启用", enabled) { vm.entryEnabled.value = it }
        SwitchRow("常驻", constant) { vm.entryConstant.value = it }
        SwitchRow("选择", selective) { vm.entrySelective.value = it }
        SwitchRow("大小写敏感", caseSensitive) { vm.entryCaseSensitive.value = it }
    }
}

/** 开关行 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

/** 编辑书信息对话框 */
@Composable
private fun EditBookDialog(
    initialName: String,
    initialDesc: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }

    NekoDialog(
        onDismiss = onDismiss,
        title = "编辑世界书",
        confirmText = "保存",
        cancelText = "取消",
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            onConfirm(name.trim(), desc.trim().takeIf { it.isNotBlank() })
        },
        onCancel = onDismiss
    ) {
        Text("名称", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Text("描述", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = desc,
            onValueChange = { desc = it },
            singleLine = false,
            minLines = 2,
            maxLines = 5
        )
    }
}

/** 统一样式的输入框 */
@Composable
private fun NekoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}
