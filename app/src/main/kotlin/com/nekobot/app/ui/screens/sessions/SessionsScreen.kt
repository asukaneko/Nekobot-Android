package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import coil.compose.AsyncImage
import androidx.lifecycle.viewModelScope
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 会话列表筛选类型。 */
enum class SessionFilter(val label: String) {
    ALL("全部会话"),
    UNARCHIVED("未归档"),
    ARCHIVED("已归档"),
    FAVORITE("收藏"),
    PINNED("置顶"),
    PUBLIC("已公开"),
    BY_CHARACTER("按角色")
}

/** 频道筛选项：value 为 null 表示全部频道。 */
data class ChannelOption(val label: String, val value: String?)

/**
 * 会话列表页：展示所有会话，支持新建、重命名、删除、收藏 / 置顶切换、筛选、搜索。
 * 点击会话项调用 [onOpenChat]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenChat: (String) -> Unit,
    onOpenDetail: (String) -> Unit = onOpenChat,
    onOpenStoryGraph: (String) -> Unit = onOpenChat
) {
    val viewModel: SessionsViewModel = viewModel()
    val sessions by viewModel.displayedSessions.collectAsState()
    val overview by viewModel.overview.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val toast by viewModel.toast.collectAsState()

    val filter by viewModel.filter.collectAsState()
    val channelFilterValue by viewModel.channelFilterValue.collectAsState()
    val availableChannels by viewModel.availableChannels.collectAsState()
    val characterFilterId by viewModel.characterFilterId.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // 模式切换时自动刷新会话列表
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    LaunchedEffect(appMode) { viewModel.loadAll() }

    // 弹窗状态
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var showSearchPanel by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("会话", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.size(8.dp))
                        SessionCountBadge(sessions.size)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建会话", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SessionOverviewCard(overview)

            // 搜索栏（点击展开半屏搜索面板）
            SearchEntryBar(
                searchQuery = searchQuery,
                filter = filter,
                channelFilterValue = channelFilterValue,
                availableChannels = availableChannels,
                onClick = { showSearchPanel = true }
            )

            QuickSessionFilters(
                selected = filter,
                onSelect = viewModel::setFilter
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading && sessions.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    sessions.isEmpty() -> {
                        val emptyTitle = when {
                            searchQuery.isNotBlank() -> "未找到匹配会话"
                            filter != SessionFilter.ALL -> "当前筛选下无会话"
                            else -> "暂无会话"
                        }
                        val emptyHint = when {
                            searchQuery.isNotBlank() -> "尝试更换关键词或切换筛选"
                            filter != SessionFilter.ALL -> "切换其他筛选或点击右上角 + 创建"
                            else -> "点击右上角 + 创建新会话"
                        }
                        EmptyState(
                            title = emptyTitle,
                            hint = emptyHint,
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "session-section-header") {
                                SessionSectionHeader(sessions.size)
                            }
                            items(sessions, key = { it.id ?: it.name ?: it.hashCode().toString() }) { session ->
                                // 当 session 自身没有立绘时，回退到已加载角色列表里同 characterId 的立绘
                                val fallbackCharacter = characters
                                    .firstOrNull { it.id == session.characterId }
                                val relatedCharacterIds = buildList {
                                    session.characterId?.takeIf { it.isNotBlank() }?.let(::add)
                                    session.characterIds.orEmpty()
                                        .filter { it.isNotBlank() }
                                        .forEach(::add)
                                }.distinct()
                                val fallbackCharacterName = relatedCharacterIds
                                    .map { characterId ->
                                        characters.firstOrNull { it.id == characterId }
                                            ?.displayName
                                            ?: characterId
                                    }
                                    .joinToString("、")
                                    .takeIf { it.isNotBlank() }
                                SessionItem(
                                    session = session,
                                    fallbackPortraitUrl = fallbackCharacter?.avatarUrl,
                                    fallbackCharacterName = fallbackCharacterName,
                                    onClick = { session.id?.let(onOpenChat) },
                                    onOpenDetail = { session.id?.let(onOpenDetail) },
                                    onRename = { renaming = session },
                                    onDelete = { deleting = session },
                                    onToggleFavorite = { viewModel.toggleFavorite(session) },
                                    onTogglePinned = { viewModel.togglePinned(session) },
                                    onToggleArchived = { viewModel.toggleArchived(session) },
                                    onOpenStoryGraph = { session.id?.let(onOpenStoryGraph) }
                                )
                            }
                        }
                    }
                }

                // 错误提示
                val errorMsg = error
                if (!errorMsg.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        ErrorBanner(
                            message = errorMsg,
                            onRetry = { viewModel.clearError() }
                        )
                    }
                }
            }
        }
    }

    // 新建会话弹窗
    if (showCreate) {
        CreateSessionDialog(
            characters = characters,
            onDismiss = { showCreate = false },
            onCreate = { req ->
                viewModel.createSession(req) { showCreate = false }
            },
            onLoadCharacters = { viewModel.loadCharacters() }
        )
    }

    // 重命名弹窗
    renaming?.let { session ->
        var name by remember(session.id) { mutableStateOf(session.name.orEmpty()) }
        NekoDialog(
            onDismiss = { renaming = null },
            title = "重命名会话",
            confirmText = "保存",
            onConfirm = {
                viewModel.renameSession(session.id.orEmpty(), name) { renaming = null }
            },
            content = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("会话名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    // 删除确认弹窗
    deleting?.let { session ->
        NekoDialog(
            onDismiss = { deleting = null },
            title = "删除会话",
            message = "确定删除「${session.displayName}」吗？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteSession(session.id.orEmpty()) { deleting = null }
            }
        )
    }

    // 操作结果 Toast 自动清除
    LaunchedEffect(toast) {
        if (!toast.isNullOrBlank()) {
            viewModel.clearToast()
        }
    }

    // 半屏搜索面板
    if (showSearchPanel) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPanel = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SearchPanelContent(
                searchQuery = searchQuery,
                onSearchChange = viewModel::setSearchQuery,
                filter = filter,
                onFilterChange = viewModel::setFilter,
                channelFilterValue = channelFilterValue,
                availableChannels = availableChannels,
                onChannelFilterChange = viewModel::setChannelFilter,
                characters = characters,
                characterFilterId = characterFilterId,
                onCharacterFilterChange = viewModel::setCharacterFilter
            )
        }
    }
}

@Composable
private fun SessionCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SessionOverviewCard(overview: SessionOverview) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        cornerRadius = 18,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OverviewStat("会话", overview.total, Icons.AutoMirrored.Outlined.Chat)
            OverviewStat("置顶", overview.pinned, Icons.Filled.PushPin)
            OverviewStat("收藏", overview.favorite, Icons.Filled.Favorite)
            OverviewStat("归档", overview.archived, Icons.Filled.Archive)
        }
    }
}

@Composable
private fun RowScope.OverviewStat(label: String, value: Int, icon: ImageVector) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(5.dp))
        Column {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickSessionFilters(
    selected: SessionFilter,
    onSelect: (SessionFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QUICK_SESSION_FILTERS.forEach { filter ->
            QuickFilterPill(
                label = if (filter == SessionFilter.ALL) "全部" else filter.label,
                selected = selected == filter,
                onClick = { onSelect(filter) }
            )
        }
    }
}

@Composable
private fun QuickFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SessionSectionHeader(resultCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "最近会话",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$resultCount 个结果",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 搜索入口栏：点击展开半屏搜索面板。展示当前搜索词和活跃筛选标签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEntryBar(
    searchQuery: String,
    filter: SessionFilter,
    channelFilterValue: String?,
    availableChannels: List<ChannelOption>,
    onClick: () -> Unit
) {
    val channelLabel = availableChannels.firstOrNull { it.value == channelFilterValue }?.label
    val hasActiveFilter = filter != SessionFilter.ALL || channelFilterValue != null || searchQuery.isNotBlank()

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(48.dp)
            .clickable { onClick() },
        cornerRadius = 16,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (searchQuery.isNotBlank()) {
                Text(
                    searchQuery,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    "搜索会话、筛选...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            // 活跃筛选标签
            if (channelLabel != null) {
                FilterChip(label = channelLabel, active = true)
            }
            if (filter != SessionFilter.ALL) {
                FilterChip(label = filter.label, active = true)
            }
        }
    }
}

/** 小型筛选标签。直接用Box渲染避免GlassCard内部padding。 */
@Composable
private fun FilterChip(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 半屏搜索面板内容：搜索框 + 频道筛选 + 会话筛选 + 角色筛选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanelContent(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    channelFilterValue: String?,
    availableChannels: List<ChannelOption>,
    onChannelFilterChange: (String?) -> Unit,
    characters: List<CharacterPreset>,
    characterFilterId: String?,
    onCharacterFilterChange: (String?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索会话、角色名...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp)
        )

        // 频道筛选
        Text("频道", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChannelChips(
            availableChannels = availableChannels,
            selectedValue = channelFilterValue,
            onSelect = onChannelFilterChange
        )

        // 会话筛选
        Text("筛选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SessionFilterChips(
            selected = filter,
            onSelect = { newFilter ->
                onFilterChange(newFilter)
                if (newFilter != SessionFilter.BY_CHARACTER) {
                    onCharacterFilterChange(null)
                }
            }
        )

        // 角色筛选（仅按角色时显示）
        if (filter == SessionFilter.BY_CHARACTER && characters.isNotEmpty()) {
            Text("角色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CharacterChips(
                characters = characters,
                selectedId = characterFilterId,
                onSelect = onCharacterFilterChange
            )
        }
    }
}

/** 频道选择 Chips（含「全部」）。 */
@Composable
private fun ChannelChips(
    availableChannels: List<ChannelOption>,
    selectedValue: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectableChip(
            label = "全部",
            selected = selectedValue == null,
            onClick = { onSelect(null) }
        )
        availableChannels.forEach { ch ->
            SelectableChip(
                label = ch.label,
                selected = selectedValue == ch.value,
                onClick = { onSelect(ch.value) }
            )
        }
    }
}

/** 会话筛选 Chips。 */
@Composable
private fun SessionFilterChips(
    selected: SessionFilter,
    onSelect: (SessionFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SessionFilter.values().forEach { f ->
            SelectableChip(
                label = f.label,
                selected = f == selected,
                onClick = { onSelect(f) }
            )
        }
    }
}

/** 角色选择 Chips（含「全部角色」）。 */
@Composable
private fun CharacterChips(
    characters: List<CharacterPreset>,
    selectedId: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectableChip(
            label = "全部角色",
            selected = selectedId == null,
            onClick = { onSelect(null) }
        )
        characters.forEach { c ->
            SelectableChip(
                label = c.displayName,
                selected = c.id == selectedId,
                onClick = { onSelect(c.id) }
            )
        }
    }
}

/** 可选中 Chip：选中时 MaterialTheme.colorScheme.primary 背景，未选中时透明背景。直接用Box渲染避免GlassCard内部padding。 */
@Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 新建会话弹窗：支持角色 / Agent / 群聊三种模式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSessionDialog(
    characters: List<CharacterPreset>,
    onDismiss: () -> Unit,
    onCreate: (CreateSessionRequest) -> Unit,
    onLoadCharacters: () -> Unit
) {
    var sessionMode by remember { mutableStateOf("character") }
    var name by remember { mutableStateOf("") }
    var characterId by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    /** 选中的角色对象（来自下拉或 ID 输入匹配） */
    var selectedCharacter by remember { mutableStateOf<CharacterPreset?>(null) }
    /** 标记用户是否手动编辑过会话名 / 首条消息，避免被角色切换覆盖。 */
    var nameEditedByUser by remember { mutableStateOf(false) }
    var firstMessageEditedByUser by remember { mutableStateOf(false) }
    val firstMessageScrollState = rememberScrollState()

    // 群聊模式：选中的角色 ID 列表 + 发言策略
    var selectedGroupCharacterIds by remember { mutableStateOf(emptyList<String>()) }
    var speechStrategy by remember { mutableStateOf("round_robin") }
    var speechStrategyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (characters.isEmpty()) onLoadCharacters()
    }

    /** 切换到指定角色时同步会话名 + 首条消息（仅覆盖未被手动编辑过的字段）。 */
    fun applyCharacter(c: CharacterPreset?) {
        selectedCharacter = c
        if (c == null) return
        if (!nameEditedByUser) name = c.displayName
        if (!firstMessageEditedByUser) firstMessage = c.firstMessage.orEmpty()
    }

    // 当 ID 输入框变化时，尝试在已加载角色列表里匹配；命中则自动填充对应字段
    LaunchedEffect(characterId, characters) {
        val id = characterId.trim()
        if (id.isEmpty()) {
            // ID 清空时也清空已选角色（保留手动输入的 firstMessage / name）
            selectedCharacter = null
            return@LaunchedEffect
        }
        val match = characters.firstOrNull { it.id == id }
        if (match != null && match != selectedCharacter) {
            applyCharacter(match)
        } else if (match == null) {
            // 输入了不在列表中的 ID，认为是手动输入的自定义 ID
            selectedCharacter = null
        }
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = "新建会话",
        confirmText = "创建",
        onConfirm = {
            val char = selectedCharacter
            val req = when (sessionMode) {
                "agent" -> CreateSessionRequest(
                    name = name.ifBlank { "Agent 对话" },
                    sessionMode = "agent",
                    systemPrompt = "",
                    firstMessage = "",
                    senderName = "Agent",
                    characterId = "",
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                "group" -> CreateSessionRequest(
                    name = name.ifBlank { "群聊" },
                    sessionMode = "group",
                    characterIds = selectedGroupCharacterIds,
                    senderName = "群聊",
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
                    groupConfig = com.google.gson.JsonObject().apply {
                        addProperty("speaker_strategy", speechStrategy)
                    }
                )
                else -> CreateSessionRequest(
                    name = name.ifBlank { char?.displayName },
                    sessionMode = "character",
                    characterId = characterId.ifBlank { null },
                    systemPrompt = char?.systemPrompt?.takeIf { it.isNotBlank() },
                    firstMessage = firstMessage.ifBlank { char?.firstMessage },
                    scenario = char?.scenario?.takeIf { it.isNotBlank() },
                    senderName = char?.displayName,
                    senderAvatar = char?.avatar,
                    senderPortrait = char?.portrait,
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
            }
            onCreate(req)
        },
        content = {
            // 模式选择 Tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectableChip(
                    label = "角色模式",
                    selected = sessionMode == "character",
                    onClick = { sessionMode = "character" }
                )
                SelectableChip(
                    label = "Agent 模式",
                    selected = sessionMode == "agent",
                    onClick = { sessionMode = "agent" }
                )
                SelectableChip(
                    label = "群聊模式",
                    selected = sessionMode == "group",
                    onClick = { sessionMode = "group" }
                )
            }
            Spacer(Modifier.height(12.dp))

            when (sessionMode) {
                "agent" -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            nameEditedByUser = it.isNotBlank()
                        },
                        label = { Text("会话名称（可选）") },
                        placeholder = { Text("Agent 对话", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Agent 模式不继承角色卡配置，适合纯工具对话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                "group" -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            nameEditedByUser = it.isNotBlank()
                        },
                        label = { Text("会话名称（可选）") },
                        placeholder = { Text("群聊", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))

                    // 发言策略下拉选择
                    val strategies = listOf(
                        "round_robin" to "轮流发言",
                        "mention" to "@提及",
                        "relevance" to "智能推荐",
                        "random" to "随机",
                        "world_engine" to "世界引擎"
                    )
                    val currentStrategyLabel = strategies.firstOrNull { it.first == speechStrategy }?.second ?: speechStrategy
                    androidx.compose.material3.ExposedDropdownMenuBox(
                        expanded = speechStrategyExpanded,
                        onExpandedChange = { speechStrategyExpanded = !speechStrategyExpanded }
                    ) {
                        OutlinedTextField(
                            value = currentStrategyLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("发言策略") },
                            singleLine = true,
                            modifier = Modifier
                                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                            trailingIcon = {
                                androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = speechStrategyExpanded)
                            }
                        )
                        DropdownMenu(
                            expanded = speechStrategyExpanded,
                            onDismissRequest = { speechStrategyExpanded = false },
                            modifier = Modifier.exposedDropdownSize()
                        ) {
                            strategies.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        speechStrategy = value
                                        speechStrategyExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // 角色多选列表
                    Text("选择角色（可多选）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (characters.isEmpty()) {
                        Text("暂无可用角色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            characters.forEach { c ->
                                val id = c.id
                                if (id != null) {
                                    val selected = selectedGroupCharacterIds.contains(id)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                                            .clickable {
                                                selectedGroupCharacterIds = if (selected) {
                                                    selectedGroupCharacterIds - id
                                                } else {
                                                    selectedGroupCharacterIds + id
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selected) {
                                                Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            c.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // 角色模式（原有逻辑）
                    // 当前选中的角色预览
                    val previewChar = selectedCharacter
                    if (previewChar != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        ) {
                            Text(
                                "已选角色：${previewChar.displayName}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            nameEditedByUser = it.isNotBlank()
                        },
                        label = { Text("会话名称（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))

                    // 角色 ID 输入 + 选择按钮（按钮高度对齐 OutlinedTextField）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = characterId,
                            onValueChange = { characterId = it },
                            label = { Text("角色 ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("手动输入或点击右侧选择", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                        Spacer(Modifier.size(8.dp))
                        Box(
                            modifier = Modifier.height(56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            GlassCard(
                                modifier = Modifier
                                    .height(56.dp)
                                    .clickable { dropdownExpanded = true },
                                cornerRadius = 12,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Text("选择", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.size(2.dp))
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                if (characters.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("暂无可用角色", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = { dropdownExpanded = false }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("（不选）", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = {
                                            dropdownExpanded = false
                                            characterId = ""
                                            selectedCharacter = null
                                        }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                                    characters.forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(c.displayName, color = MaterialTheme.colorScheme.onSurface) },
                                            onClick = {
                                                dropdownExpanded = false
                                                // 切换角色时同步会话名 + 首条消息（除非用户已手动编辑）
                                                characterId = c.id.orEmpty()
                                                applyCharacter(c)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    // 首条消息：固定高度的滚动窗口，避免长内容撑爆弹窗
                    OutlinedTextField(
                        value = firstMessage,
                        onValueChange = {
                            firstMessage = it
                            firstMessageEditedByUser = it.isNotBlank()
                        },
                        label = { Text("首条消息（可选）") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 160.dp)
                            .verticalScroll(firstMessageScrollState)
                    )
                }
            }
        }
    )
}

@Composable
private fun SessionMetaLabel(text: String, emphasized: Boolean = false) {
    Box(
        modifier = Modifier
            .widthIn(max = 120.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SessionStatusIcon(icon: ImageVector, description: String) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(14.dp)
    )
}

/** 单个会话项卡片。 */
@Composable
private fun SessionItem(
    session: Session,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    /** 会话自身无立绘时的回退 URL（来自已加载角色列表）。 */
    fallbackPortraitUrl: String? = null,
    /** 会话自身无角色名时的回退名称（来自已加载角色列表）。 */
    fallbackCharacterName: String? = null,
    onOpenDetail: () -> Unit = {},
    onToggleArchived: () -> Unit = {},
    onOpenStoryGraph: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isAgentSession = session.sessionMode == "agent"
    val isGroupSession = session.sessionMode == "group" || !session.characterIds.isNullOrEmpty()
    val senderCharacterName = session.senderName?.takeIf {
        it.isNotBlank() && it !in setOf("AI", "Agent", "群聊")
    }
    val characterLabel = when {
        isAgentSession -> null
        isGroupSession -> fallbackCharacterName
        else -> session.characterName?.takeIf { it.isNotBlank() }
            ?: senderCharacterName
            ?: fallbackCharacterName?.takeIf { it.isNotBlank() && it != "未命名角色" }
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 16,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 角色立绘图片（竖向圆角矩形）：优先 session 自带立绘，回退到角色列表
            val rawPortrait = session.portraitUrl ?: fallbackPortraitUrl
            val portraitUrl = resolveAvatarUrl(rawPortrait)
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (!portraitUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = portraitUrl,
                        contentDescription = characterLabel ?: "角色立绘",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.size(10.dp))

            // 主体信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    session.updatedAt?.takeIf { it.isNotBlank() }?.let { time ->
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp)
                        )
                    }
                }
                session.lastMessage?.takeIf { it.isNotBlank() }?.let { preview ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    characterLabel?.let {
                        SessionMetaLabel(text = it, emphasized = true)
                    }
                    session.messageCount?.let { SessionMetaLabel(text = "$it 条") }
                    if (session.pinned == true) SessionStatusIcon(Icons.Filled.PushPin, "已置顶")
                    if (session.favorite == true) SessionStatusIcon(Icons.Filled.Favorite, "已收藏")
                    if (session.archived == true) SessionStatusIcon(Icons.Filled.Archive, "已归档")
                }
            }

            // 右侧菜单
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("会话详情") },
                        onClick = {
                            menuExpanded = false
                            onOpenDetail()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (session.pinned == true) "取消置顶" else "置顶")
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePinned()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (session.favorite == true) "取消收藏" else "收藏") },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (session.archived == true) "取消归档" else "归档") },
                        onClick = {
                            menuExpanded = false
                            onToggleArchived()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("故事图") },
                        onClick = {
                            menuExpanded = false
                            onOpenStoryGraph()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = Color(0xFFFF6B6B)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * 会话列表 ViewModel。
 *
 * 内部维护原始会话列表 + 筛选/搜索状态，
 * 对外暴露 [displayedSessions]（已筛选 + 已搜索 + 已排序：置顶永远在前）。
 */
class SessionsViewModel : BaseViewModel() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val overview: StateFlow<SessionOverview> = _sessions
        .map(::buildSessionOverview)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SessionOverview()
        )

    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()

    private val _filter = MutableStateFlow(SessionFilter.ALL)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    /** 当前选中的频道值（null = 全部频道）。默认 null。 */
    private val _channelFilterValue = MutableStateFlow<String?>(null)
    val channelFilterValue: StateFlow<String?> = _channelFilterValue.asStateFlow()

    /** 动态频道列表：从已加载会话的 type 字段派生。 */
    val availableChannels: StateFlow<List<ChannelOption>> = _sessions.map { sessions ->
        val types = sessions.mapNotNull { it.type?.lowercase()?.trim()?.takeIf { t -> t.isNotBlank() } }
            .distinct()
            .sorted()
        // 标签映射：web->Web, qq->QQ, 其他首字母大写
        types.map { t ->
            val label = when (t) {
                "web" -> "Web"
                "qq" -> "QQ"
                "cli" -> "CLI"
                else -> t.replaceFirstChar { c -> c.uppercase() }
            }
            ChannelOption(label, t)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val _characterFilterId = MutableStateFlow<String?>(null)
    val characterFilterId: StateFlow<String?> = _characterFilterId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 对外展示的会话列表：频道筛选 + 置顶优先 + 应用筛选 + 应用搜索 + 非置顶按时间倒序。 */
    val displayedSessions: StateFlow<List<Session>> = combine(
        _sessions, _filter, _channelFilterValue, _characterFilterId, _searchQuery
    ) { all, f, chVal, charId, query ->
        // 先排除压缩上下文自动创建的归档会话（is_archive == true）；
        // 手动归档的会话（archived == true 但 is_archive != true）仍可展示
        val visible = all.filter { it.isArchive != true }
        // 先按频道筛选
        val byChannel = applyChannelFilter(visible, chVal)
        val filtered = applyFilter(byChannel, f, charId)
        val searched = applySearch(filtered, query)
        // 置顶强制置顶；非置顶按时间倒序（updatedAt 回退到 createdAt）
        searched.sortedWith(
            compareByDescending<Session> { it.pinned == true }
                .thenByDescending { it.updatedAt ?: it.createdAt ?: "" }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun setFilter(f: SessionFilter) {
        _filter.value = f
    }

    fun setChannelFilter(value: String?) {
        _channelFilterValue.value = value
    }

    fun setCharacterFilter(id: String?) {
        _characterFilterId.value = id
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    /** 加载会话 + 角色列表。 */
    fun loadAll() {
        loadSessions()
        loadCharacters()
    }

    /** 加载会话列表。 */
    fun loadSessions() {
        launchResult(
            block = { unified.listSessions() },
            onSuccess = { _sessions.value = it ?: emptyList() }
        )
    }

    /** 加载角色列表（供新建会话下拉菜单使用）。 */
    fun loadCharacters() {
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { list ->
                _characters.value = list ?: emptyList()
            }
        )
    }

    /** 新建会话，成功后刷新并回调 [onSuccess]。 */
    fun createSession(req: CreateSessionRequest, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.createSession(req) },
            onSuccess = {
                showToast("会话已创建")
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 删除会话，成功后刷新并回调 [onSuccess]。 */
    fun deleteSession(id: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.deleteSession(id) },
            onSuccess = {
                showToast("会话已删除")
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 重命名会话，成功后刷新并回调 [onSuccess]。 */
    fun renameSession(id: String, name: String, onSuccess: () -> Unit = {}) {
        if (name.isBlank()) {
            showError("名称不能为空")
            return
        }
        launchResult(
            block = { unified.updateSession(id, UpdateSessionRequest(name = name)) },
            onSuccess = {
                showToast("已重命名")
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 切换收藏状态。 */
    fun toggleFavorite(session: Session) {
        val newFav = !(session.favorite ?: false)
        launchResult(
            block = { unified.updateSession(session.id.orEmpty(), UpdateSessionRequest(favorite = newFav)) },
            onSuccess = {
                loadSessions()
            }
        )
    }

    /** 切换置顶状态。 */
    fun togglePinned(session: Session) {
        val newPinned = !(session.pinned ?: false)
        launchResult(
            block = { unified.updateSession(session.id.orEmpty(), UpdateSessionRequest(pinned = newPinned)) },
            onSuccess = {
                showToast(if (newPinned) "已置顶" else "已取消置顶")
                loadSessions()
            }
        )
    }

    /** 切换归档状态。 */
    fun toggleArchived(session: Session) {
        val newArchived = !(session.archived ?: false)
        launchResult(
            block = {
                if (newArchived) unified.archiveSession(session.id.orEmpty())
                else unified.restoreSession(session.id.orEmpty())
            },
            onSuccess = {
                showToast(if (newArchived) "已归档" else "已取消归档")
                loadSessions()
            }
        )
    }

    /** 根据频道过滤会话（先于其他筛选）。channelValue 为 null 时返回全部。 */
    private fun applyChannelFilter(all: List<Session>, channelValue: String?): List<Session> {
        if (channelValue == null) return all
        return all.filter { s ->
            val type = s.type?.lowercase()?.trim() ?: "web"
            type == channelValue
        }
    }

    /** 根据筛选类型过滤会话。 */
    private fun applyFilter(all: List<Session>, f: SessionFilter, charId: String?): List<Session> {
        return when (f) {
            SessionFilter.ALL -> all
            SessionFilter.UNARCHIVED -> all.filter { it.archived != true }
            SessionFilter.ARCHIVED -> all.filter { it.archived == true }
            SessionFilter.FAVORITE -> all.filter { it.favorite == true }
            SessionFilter.PINNED -> all.filter { it.pinned == true }
            SessionFilter.PUBLIC -> all.filter { it.isPublic == true }
            SessionFilter.BY_CHARACTER -> {
                if (charId.isNullOrBlank()) all
                else all.filter { s ->
                    s.characterId == charId || s.characterIds?.contains(charId) == true
                }
            }
        }
    }

    /** 根据关键词搜索会话名称 / 角色名 / 最后一条消息。 */
    private fun applySearch(list: List<Session>, query: String): List<Session> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return list.filter { s ->
            s.displayName.contains(q, ignoreCase = true) ||
                (s.characterName?.contains(q, ignoreCase = true) == true) ||
                (s.lastMessage?.contains(q, ignoreCase = true) == true)
        }
    }
}
