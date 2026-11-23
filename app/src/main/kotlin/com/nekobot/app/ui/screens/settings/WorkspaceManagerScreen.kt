package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.SessionWorkspaceEntry
import com.nekobot.app.data.local.SessionWorkspaceMaintenance
import com.nekobot.app.data.local.WORKSPACE_ENTRY_LIMIT
import com.nekobot.app.data.local.WorkspaceItemEntry
import com.nekobot.app.data.local.WorkspaceSortKey
import com.nekobot.app.data.local.WorkspaceSortOrder
import com.nekobot.app.data.local.sortWorkspaceEntries
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.BorderlessFilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedButton as OutlinedButton
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 工作区占用大小的可读格式（与数据维护页的缓存大小格式保持一致）。 */
private fun formatWorkspaceSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 工作区时间（epoch 毫秒 → yyyy-MM-dd HH:mm）；时间无效时返回 null。 */
private fun formatWorkspaceTime(epochMillis: Long): String? =
    if (epochMillis <= 0L) null
    else runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }.getOrNull()

/**
 * 会话工作区管理 ViewModel：列出磁盘上所有含文件的会话工作区，
 * 支持逐个文件删除或整目录删除。
 */
internal class WorkspaceManagerViewModel : BaseViewModel() {

    private val _entries = MutableStateFlow<List<SessionWorkspaceEntry>>(emptyList())
    val entries: StateFlow<List<SessionWorkspaceEntry>> = _entries.asStateFlow()

    private val _items = MutableStateFlow<List<WorkspaceItemEntry>>(emptyList())
    val items: StateFlow<List<WorkspaceItemEntry>> = _items.asStateFlow()

    /** 工作区列表排序（默认按会话时间从新到旧）。 */
    private val _sortOrder = MutableStateFlow(WorkspaceSortOrder())
    val sortOrder: StateFlow<WorkspaceSortOrder> = _sortOrder.asStateFlow()

    private var maintenance: SessionWorkspaceMaintenance? = null

    private fun maintenance(context: Context): SessionWorkspaceMaintenance =
        maintenance ?: SessionWorkspaceMaintenance(context.applicationContext, ServiceContainer.prefs)
            .also { maintenance = it }

    /** 点击排序字段：同一字段切换升降序，另一字段则按其默认方向排序。 */
    fun selectSortKey(key: WorkspaceSortKey) {
        _sortOrder.value = _sortOrder.value.selecting(key)
    }

    /** 重新扫描全部工作区。 */
    fun refresh(context: Context) {
        viewModelScope.launch {
            setLoading(true)
            try {
                _entries.value = maintenance(context).list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                setLoading(false)
            }
        }
    }

    /** 读取某个工作区内的文件列表。 */
    fun loadItems(context: Context, entry: SessionWorkspaceEntry) {
        viewModelScope.launch {
            setLoading(true)
            try {
                _items.value = maintenance(context).listItems(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                setLoading(false)
            }
        }
    }

    fun clearItems() {
        _items.value = emptyList()
    }

    /** 删除整个工作区目录。 */
    fun deleteWorkspace(context: Context, entry: SessionWorkspaceEntry, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            setLoading(true)
            try {
                if (maintenance(context).deleteWorkspace(entry)) {
                    showToast(string(R.string.workspace_manager_deleted))
                    onDone()
                    refresh(context)
                } else {
                    showError(string(R.string.workspace_manager_delete_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.workspace_manager_delete_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * 一键删除全部「会话已删除」的残留工作区目录。
     *
     * 只处理 [SessionWorkspaceEntry.sessionExists] 为 false 的条目，会话仍存在的工作区不受影响；
     * 单个目录删除失败不会中断整体流程，结束后按成功/失败数量分别提示。
     */
    fun deleteOrphanWorkspaces(context: Context, entries: List<SessionWorkspaceEntry>) {
        val orphans = entries.filterNot { it.sessionExists }
        if (orphans.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            var deleted = 0
            var failed = 0
            try {
                val fs = maintenance(context)
                for (entry in orphans) {
                    val ok = try {
                        fs.deleteWorkspace(entry)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) deleted++ else failed++
                }
            } finally {
                setLoading(false)
            }
            if (deleted > 0) showToast(string(R.string.workspace_manager_delete_orphans_done, deleted))
            if (failed > 0) showError(string(R.string.workspace_manager_delete_orphans_failed, failed))
            refresh(context)
        }
    }

    /** 删除工作区内的单个文件或文件夹。 */
    fun deleteItem(context: Context, entry: SessionWorkspaceEntry, relativePath: String) {
        viewModelScope.launch {
            setLoading(true)
            try {
                if (maintenance(context).deleteItem(entry, relativePath)) {
                    showToast(string(R.string.workspace_manager_deleted))
                    _items.value = maintenance(context).listItems(entry)
                    refresh(context)
                } else {
                    showError(string(R.string.workspace_manager_delete_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.workspace_manager_delete_failed))
            } finally {
                setLoading(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceManagerScreen(onBack: () -> Unit) {
    val vm: WorkspaceManagerViewModel = viewModel()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 当前浏览的工作区；null 表示停留在工作区列表
    var browsing by remember { mutableStateOf<SessionWorkspaceEntry?>(null) }
    var currentDir by remember { mutableStateOf("") }
    var pendingWorkspace by remember { mutableStateOf<SessionWorkspaceEntry?>(null) }
    var pendingItem by remember { mutableStateOf<WorkspaceItemEntry?>(null) }
    var pendingOrphans by remember { mutableStateOf(false) }
    // 提到页面层持有，进入文件界面再返回时列表滚动位置不丢失
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.refresh(context) }

    // 从文件界面退回工作区列表（列表滚动位置由 listState 保持）
    val backToList = {
        browsing = null
        currentDir = ""
        vm.clearItems()
    }

    // 文件界面侧滑返回：先回到工作区列表（保持原进入位置），而不是直接退出整个页面
    BackHandler(enabled = browsing != null) { backToList() }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (browsing == null) stringResource(R.string.workspace_manager_title)
                        else stringResource(R.string.workspace_manager_files_title),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (browsing != null) backToList() else onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh(context) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.maintenance_refresh),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                error?.let {
                    ErrorToast(message = it, onShown = { vm.clearError() })
                    Spacer(Modifier.height(12.dp))
                }
                val target = browsing
                if (target == null) {
                    WorkspaceListPane(
                        entries = entries,
                        sortOrder = sortOrder,
                        onSelectSort = vm::selectSortKey,
                        listState = listState,
                        onBrowse = { entry ->
                            browsing = entry
                            currentDir = ""
                            vm.loadItems(context, entry)
                        },
                        onDeleteOrphans = { pendingOrphans = true },
                        onDeleteWorkspace = { entry -> pendingWorkspace = entry }
                    )
                } else {
                    WorkspaceFilesPane(
                        entry = target,
                        workspaceItems = items,
                        currentDir = currentDir,
                        onEnterDir = { path -> currentDir = path },
                        onNavigateUp = { currentDir = currentDir.substringBeforeLast('/', "") },
                        onDeleteItem = { item -> pendingItem = item },
                        onDeleteWorkspace = { pendingWorkspace = target }
                    )
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 一键删除全部「会话已删除」的残留工作区
    if (pendingOrphans) {
        val orphans = entries.filterNot { it.sessionExists }
        NekoDialog(
            onDismiss = { pendingOrphans = false },
            title = stringResource(R.string.workspace_manager_delete_orphans),
            message = stringResource(
                R.string.workspace_manager_delete_orphans_msg,
                orphans.size,
                formatWorkspaceSize(orphans.sumOf { it.sizeBytes })
            ),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                pendingOrphans = false
                vm.deleteOrphanWorkspaces(context, entries)
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { pendingOrphans = false }
        )
    }

    // 删除整个工作区
    pendingWorkspace?.let { entry ->
        NekoDialog(
            onDismiss = { pendingWorkspace = null },
            title = stringResource(R.string.workspace_manager_delete_workspace),
            message = stringResource(R.string.workspace_manager_delete_workspace_msg),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                pendingWorkspace = null
                if (browsing == entry) {
                    browsing = null
                    currentDir = ""
                    vm.clearItems()
                }
                vm.deleteWorkspace(context, entry)
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { pendingWorkspace = null }
        )
    }

    // 删除单个文件 / 文件夹
    pendingItem?.let { item ->
        NekoDialog(
            onDismiss = { pendingItem = null },
            title = stringResource(R.string.workspace_manager_delete_file_title),
            message = stringResource(R.string.workspace_manager_delete_file_msg, item.relativePath),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                pendingItem = null
                browsing?.let { entry -> vm.deleteItem(context, entry, item.relativePath) }
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { pendingItem = null }
        )
    }
}

/** 工作区列表：排序条 + 会话存在的与已删除的分组展示。 */
@Composable
private fun WorkspaceListPane(
    entries: List<SessionWorkspaceEntry>,
    sortOrder: WorkspaceSortOrder,
    onSelectSort: (WorkspaceSortKey) -> Unit,
    listState: LazyListState,
    onBrowse: (SessionWorkspaceEntry) -> Unit,
    onDeleteOrphans: () -> Unit,
    onDeleteWorkspace: (SessionWorkspaceEntry) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyHint(text = stringResource(R.string.workspace_manager_empty))
        return
    }
    val ordered = remember(entries, sortOrder) { sortWorkspaceEntries(entries, sortOrder) }
    val alive = ordered.filter { it.sessionExists }
    val orphans = ordered.filterNot { it.sessionExists }
    val totalBytes = entries.sumOf { it.sizeBytes }

    Column(modifier = Modifier.fillMaxSize()) {
        WorkspaceSortBar(sortOrder = sortOrder, onSelectSort = onSelectSort)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(
                        R.string.workspace_manager_summary,
                        entries.size,
                        formatWorkspaceSize(totalBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (alive.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.workspace_manager_group_alive)) }
                items(alive, key = { "alive:${it.sessionId}:${it.legacy}" }) { entry ->
                    WorkspaceRow(entry = entry, onBrowse = onBrowse, onDelete = onDeleteWorkspace)
                }
            }
            if (orphans.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.workspace_manager_group_orphan),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        // 一键删除全部残留工作区
                        OutlinedButton(onClick = onDeleteOrphans, modifier = Modifier.fillMaxWidth()) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.workspace_manager_delete_orphans),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                items(orphans, key = { "orphan:${it.sessionId}:${it.legacy}" }) { entry ->
                    WorkspaceRow(entry = entry, onBrowse = onBrowse, onDelete = onDeleteWorkspace)
                }
            }
        }
    }
}

/** 排序条：会话时间 / 占用大小，点击已选字段切换升降序。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceSortBar(
    sortOrder: WorkspaceSortOrder,
    onSelectSort: (WorkspaceSortKey) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WorkspaceSortChip(
            label = stringResource(R.string.workspace_manager_sort_time),
            selected = sortOrder.key == WorkspaceSortKey.SESSION_TIME,
            descending = sortOrder.descending,
            onClick = { onSelectSort(WorkspaceSortKey.SESSION_TIME) }
        )
        WorkspaceSortChip(
            label = stringResource(R.string.workspace_manager_sort_size),
            selected = sortOrder.key == WorkspaceSortKey.SIZE,
            descending = sortOrder.descending,
            onClick = { onSelectSort(WorkspaceSortKey.SIZE) }
        )
    }
}

@Composable
private fun WorkspaceSortChip(
    label: String,
    selected: Boolean,
    descending: Boolean,
    onClick: () -> Unit
) {
    BorderlessFilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (!selected) null else {
            {
                Icon(
                    imageVector = if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(
                        if (descending) R.string.workspace_manager_sort_desc
                        else R.string.workspace_manager_sort_asc
                    ),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
}

@Composable
private fun WorkspaceRow(
    entry: SessionWorkspaceEntry,
    onBrowse: (SessionWorkspaceEntry) -> Unit,
    onDelete: (SessionWorkspaceEntry) -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onBrowse(entry) }
            ) {
                Text(
                    text = entry.sessionName ?: entry.sessionId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.workspace_manager_meta,
                        entry.fileCount,
                        formatWorkspaceSize(entry.sizeBytes)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 工作区时间单独一行，避免挤压上面的文件数量与占用大小
                val timeText = remember(entry.lastModified) { formatWorkspaceTime(entry.lastModified) }
                if (timeText != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (entry.sessionExists && !entry.profileLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    WorkspaceTag(
                        text = stringResource(R.string.workspace_manager_profile, entry.profileLabel),
                        emphasized = false
                    )
                } else if (!entry.sessionExists) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WorkspaceTag(
                            text = stringResource(R.string.workspace_manager_tag_orphan),
                            emphasized = true
                        )
                        if (entry.legacy) {
                            WorkspaceTag(
                                text = stringResource(R.string.workspace_manager_tag_legacy),
                                emphasized = false
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { onBrowse(entry) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onDelete(entry) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.workspace_manager_delete_workspace),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 小标签：档案名 / 已删除会话 / 旧版目录 */
@Composable
private fun WorkspaceTag(text: String, emphasized: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 单个工作区内的文件浏览：面包屑 + 目录内条目 + 整目录删除。 */
@Composable
private fun WorkspaceFilesPane(
    entry: SessionWorkspaceEntry,
    workspaceItems: List<WorkspaceItemEntry>,
    currentDir: String,
    onEnterDir: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onDeleteItem: (WorkspaceItemEntry) -> Unit,
    onDeleteWorkspace: () -> Unit
) {
    val prefix = if (currentDir.isEmpty()) "" else "$currentDir/"
    val currentEntries = remember(workspaceItems, currentDir) {
        workspaceItems.filter { it.relativePath.substringBeforeLast('/', "") == currentDir }
            .sortedWith(compareByDescending<WorkspaceItemEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = entry.sessionName ?: entry.sessionId,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (currentDir.isNotEmpty()) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.workspace_manager_root) + if (prefix.isEmpty()) "" else "/$currentDir",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (workspaceItems.size >= WORKSPACE_ENTRY_LIMIT) {
            Text(
                text = stringResource(R.string.workspace_manager_truncated, WORKSPACE_ENTRY_LIMIT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (currentEntries.isEmpty()) {
                item { EmptyHint(text = stringResource(R.string.workspace_manager_empty)) }
            }
            items(currentEntries, key = { it.relativePath }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = item.isDirectory) {
                            if (item.isDirectory) onEnterDir(item.relativePath)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = if (item.isDirectory) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (item.isDirectory) stringResource(R.string.maintenance_folder)
                            else formatWorkspaceSize(item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDeleteItem(item) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onDeleteWorkspace, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.workspace_manager_delete_workspace),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
