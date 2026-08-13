package com.nekobot.app.ui.screens.sessions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.repeatCount
import androidx.lifecycle.viewModelScope
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.RandomCharacterIdea
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INHERIT
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.RelationshipStateSourceSelector
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.adaptive.listItemSemantics
import com.nekobot.app.ui.adaptive.rememberShouldUseTwoPane
import com.nekobot.app.ui.screens.chat.ModernChatScreen
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FileUpload
import com.google.gson.JsonParser
import com.nekobot.app.data.repository.SessionImportResult

/** 会话列表筛选类型。 */
enum class SessionFilter(val labelResId: Int) {
    ALL(R.string.sessions_recent),
    UNARCHIVED(R.string.sessions_filter_unarchived),
    ARCHIVED(R.string.sessions_filter_archived),
    FAVORITE(R.string.sessions_filter_favorite),
    PINNED(R.string.sessions_filter_pinned),
    PUBLIC(R.string.sessions_filter_public),
    BY_CHARACTER(R.string.sessions_filter_by_character)
}

/** 频道筛选项：value 为 null 表示全部频道。 */
data class ChannelOption(val label: String, val value: String?)

private data class DashboardMetricsLoadResults(
    val stats: Resource<TokenStats>,
    val rankings: Resource<TokenRankings>,
    val highAffectionCount: Resource<Int>,
    val worldBooks: Resource<List<WorldBook>>
)

/**
 * 会话-聊天双栏布局包装器。
 *
 * - Compact 模式：直接渲染 [content]，保持现有单栏行为
 * - Medium/Expanded 模式：Row 布局，左列显示会话列表 [content]，
 *   右列显示选中会话的聊天（ModernChatScreen）或占位文本
 *
 * @param useTwoPane 是否使用双栏
 * @param selectedSessionId 当前选中的会话 ID（null 表示未选中）
 * @param onSessionSelected 选中会话回调
 * @param onClearSelection 清除选中回调
 * @param onOpenDetail 打开会话详情回调
 * @param onOpenStoryGraph 打开剧情图回调
 * @param onOpenWenku8Login 打开 wenku8 登录页回调
 * @param content 会话列表内容（Scaffold）
 */
@Composable
private fun TwoPaneSessionsWrapper(
    useTwoPane: Boolean,
    selectedSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenStoryGraph: (String) -> Unit,
    onOpenWenku8Login: () -> Unit,
    content: @Composable () -> Unit
) {
    if (useTwoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左列：会话列表
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
            ) {
                content()
            }
            // 垂直分隔线
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
            )
            // 右列：聊天或占位
            Box(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
            ) {
                val sid = selectedSessionId
                if (sid != null) {
                    ModernChatScreen(
                        sessionId = sid,
                        onBack = onClearSelection,
                        onOpenChat = onSessionSelected,
                        onOpenSessionDetail = onOpenDetail,
                        onOpenWorkspace = {},
                        onOpenStoryGraph = onOpenStoryGraph,
                        onOpenWenku8Login = onOpenWenku8Login,
                        onJumpToLatest = {}
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "选择一个会话开始聊天",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else {
        content()
    }
}

/**
 * 会话列表页：展示所有会话，支持新建、重命名、删除、收藏 / 置顶切换、筛选、搜索。
 * 点击会话项调用 [onOpenChat]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onOpenChat: (String) -> Unit,
    onOpenDetail: (String) -> Unit = onOpenChat,
    onOpenStoryGraph: (String) -> Unit = onOpenChat,
    onNavigate: (String) -> Unit = {}
) {
    val viewModel: SessionsViewModel = viewModel()
    val sessionRows by viewModel.displayedSessionRows.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val dashboardData by viewModel.dashboardData.collectAsStateWithLifecycle()
    val dashboardLoading by viewModel.dashboardLoading.collectAsStateWithLifecycle()

    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val channelFilterValue by viewModel.channelFilterValue.collectAsStateWithLifecycle()
    val availableChannels by viewModel.availableChannels.collectAsStateWithLifecycle()
    val characterFilterId by viewModel.characterFilterId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recentSessionsIncludeArchived by ServiceContainer.prefs.recentSessionsIncludeArchivedFlow.collectAsStateWithLifecycle()

    // 提取本地化字符串
    val titleText = stringResource(R.string.sessions_title)
    val refreshDesc = stringResource(R.string.sessions_refresh)
    val newSessionDesc = stringResource(R.string.sessions_new)
    val noMatchTitle = stringResource(R.string.sessions_no_match)
    val filterEmptyTitle = stringResource(R.string.sessions_filter_empty)
    val emptyTitleText = stringResource(R.string.sessions_empty)
    val noMatchHint = stringResource(R.string.sessions_no_match_hint)
    val filterEmptyHint = stringResource(R.string.sessions_filter_empty_hint)
    val emptyHintText = stringResource(R.string.sessions_empty_hint)
    val renameTitle = stringResource(R.string.sessions_rename_title)
    val saveText = stringResource(R.string.common_save)
    val sessionNameLabel = stringResource(R.string.sessions_name_label)
    val deleteTitle = stringResource(R.string.sessions_delete_title)
    val deleteConfirmFmt = stringResource(R.string.sessions_delete_confirm)
    val deleteText = stringResource(R.string.common_delete)
    val dashboardTitle = stringResource(R.string.stats_dashboard_title)
    val dashboardRefreshDesc = stringResource(R.string.stats_dashboard_refresh)
    val importDesc = stringResource(R.string.sessions_import)

    val context = LocalContext.current
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importSessions(uri, context)
        }
    }

    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 2 })
    val dashboardVisible = pagerState.currentPage == 0

    // 模式切换时自动刷新会话列表
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    LaunchedEffect(appMode, dataSourceRevision) {
        viewModel.reloadForDataSource()
    }

    // 角色卡立绘/头像变更后自动刷新会话列表，使会话头像跟随角色卡更新
    LaunchedEffect(Unit) {
        ServiceContainer.characterChanged.collect {
            viewModel.loadAll()
        }
    }

    // 弹窗状态
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionListRow?>(null) }
    var deleting by remember { mutableStateOf<SessionListRow?>(null) }
    var showSearchPanel by rememberSaveable { mutableStateOf(false) }
    var showDashboardLayout by rememberSaveable { mutableStateOf(false) }
    var showGeneratingCharacter by remember { mutableStateOf(false) }
    var randomCharacterIdeas by remember { mutableStateOf(emptyList<RandomCharacterIdea>()) }
    var dashboardWidgetOrder by remember {
        mutableStateOf(ServiceContainer.prefs.statsDashboardWidgetOrder)
    }
    var dashboardHiddenWidgets by remember {
        mutableStateOf(ServiceContainer.prefs.statsDashboardHiddenWidgets)
    }
    var characterRankingMode by remember {
        mutableStateOf(CharacterRankingMode.fromStorage(ServiceContainer.prefs.statsCharacterRankingMode))
    }

    // 双栏布局状态：大屏模式下选中的会话 ID
    val useTwoPane = rememberShouldUseTwoPane()
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    // 双栏模式下点击会话在右侧打开聊天，单栏模式保持原有导航行为
    val handleOpenChat: (String) -> Unit = if (useTwoPane) {
        { id -> selectedSessionId = id }
    } else {
        onOpenChat
    }

    TwoPaneSessionsWrapper(
        useTwoPane = useTwoPane,
        selectedSessionId = selectedSessionId,
        onSessionSelected = { id -> selectedSessionId = id },
        onClearSelection = { selectedSessionId = null },
        onOpenDetail = onOpenDetail,
        onOpenStoryGraph = onOpenStoryGraph,
        onOpenWenku8Login = { onNavigate(Routes.WENKU_LOGIN) }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (dashboardVisible) dashboardTitle else titleText,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.size(8.dp))
                        if (dashboardVisible) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    stringResource(R.string.stats_dashboard_badge),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            SessionCountBadge(sessionRows.size)
                        }
                    }
                },
                actions = {
                    if (dashboardVisible) {
                        IconButton(
                            onClick = {
                                viewModel.loadAll()
                                viewModel.loadDashboardMetrics()
                            }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = dashboardRefreshDesc, tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showDashboardLayout = true }) {
                            Icon(Icons.Filled.DashboardCustomize, contentDescription = stringResource(R.string.stats_customize_layout), tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(onClick = { viewModel.loadAll() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = refreshDesc, tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { importFileLauncher.launch("*/*") }) {
                            Icon(Icons.Filled.FileUpload, contentDescription = importDesc, tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showCreate = true }) {
                            Icon(Icons.Filled.Add, contentDescription = newSessionDesc, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            key = { it }
        ) { page ->
            if (page == 0) {
                SessionStatsDashboard(
                    data = dashboardData,
                    loading = dashboardLoading,
                    widgetOrder = dashboardWidgetOrder,
                    hiddenWidgets = dashboardHiddenWidgets,
                    characterRankingMode = characterRankingMode,
                    onCharacterRankingModeChange = { mode ->
                        characterRankingMode = mode
                        ServiceContainer.prefs.statsCharacterRankingMode = mode.name
                    },
                    onCustomize = { showDashboardLayout = true },
                    onOpenChat = handleOpenChat,
                    onQuickAction = { action ->
                        when (action) {
                            DashboardQuickAction.NEW_SESSION -> showCreate = true
                            DashboardQuickAction.AI_CONFIG -> onNavigate(Routes.AI_CONFIG_CENTER)
                            DashboardQuickAction.WORLD_BOOKS -> onNavigate(Routes.WORLD_BOOKS)
                            DashboardQuickAction.SETTINGS -> onNavigate(Routes.SETTINGS)
                        }
                    },
                    onCharacterSelected = { character ->
                        viewModel.createSessionForCharacter(
                            characterId = character.id,
                            characterName = character.name,
                            onSuccess = handleOpenChat
                        )
                    },
                    randomCharacterIdeas = randomCharacterIdeas,
                    onGenerateIdeas = {
                        viewModel.generateRandomCharacterIdeas { ideas ->
                            randomCharacterIdeas = ideas
                        }
                    },
                    onGenerateCharacter = { idea ->
                        showGeneratingCharacter = true
                        viewModel.aiGenerateCharacterAndCreateSession(
                            description = idea.description,
                            onSuccess = { sessionId ->
                                showGeneratingCharacter = false
                                handleOpenChat(sessionId)
                            },
                            onError = { showGeneratingCharacter = false }
                        )
                    }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
            // 概览统计 + 快速筛选合并：每张卡显示数量并可点击筛选
            SessionStatFilters(
                overview = overview,
                includeArchived = recentSessionsIncludeArchived,
                selected = filter,
                onSelect = viewModel::setFilter
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading && sessionRows.isEmpty() -> {
                        // 初次加载时搜索框仍可见（固定在顶部），下方显示加载指示。
                        // 外层 padding 与有数据时 LazyColumn 的 contentPadding 保持一致，
                        // 保证搜索框在所有状态下样式统一。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                        ) {
                            SearchEntryBar(
                                searchQuery = searchQuery,
                                filter = filter,
                                channelFilterValue = channelFilterValue,
                                availableChannels = availableChannels,
                                onClick = { showSearchPanel = true }
                            )
                        }
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    sessionRows.isEmpty() -> {
                        // 空状态下搜索框仍可见（固定在顶部），下方显示空状态。
                        // 外层 padding 与有数据时 LazyColumn 的 contentPadding 保持一致，
                        // 保证搜索框在所有状态下样式统一。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                        ) {
                            SearchEntryBar(
                                searchQuery = searchQuery,
                                filter = filter,
                                channelFilterValue = channelFilterValue,
                                availableChannels = availableChannels,
                                onClick = { showSearchPanel = true }
                            )
                        }
                        val emptyTitle = when {
                            searchQuery.isNotBlank() -> noMatchTitle
                            filter != SessionFilter.ALL -> filterEmptyTitle
                            else -> emptyTitleText
                        }
                        val emptyHint = when {
                            searchQuery.isNotBlank() -> noMatchHint
                            filter != SessionFilter.ALL -> filterEmptyHint
                            else -> emptyHintText
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
                        // 列表不为空时，搜索框作为 LazyColumn 的第一个 item，跟随列表滚动
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 110.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 搜索栏：跟随列表滚动的首项
                            item(
                                key = "search-entry-bar",
                                contentType = "search-entry-bar"
                            ) {
                                SearchEntryBar(
                                    searchQuery = searchQuery,
                                    filter = filter,
                                    channelFilterValue = channelFilterValue,
                                    availableChannels = availableChannels,
                                    onClick = { showSearchPanel = true }
                                )
                            }
                            item(
                                key = "session-section-header",
                                contentType = "session-section-header"
                            ) {
                                SessionSectionHeader(sessionRows.size)
                            }
                            items(
                                items = sessionRows,
                                key = SessionListRow::key,
                                contentType = { "session-row" }
                            ) { row ->
                                SessionItem(
                                    row = row,
                                    onClick = { row.id?.let(handleOpenChat) },
                                    onOpenDetail = { row.id?.let(onOpenDetail) },
                                    onRename = { if (row.id != null) renaming = row },
                                    onDelete = { if (row.id != null) deleting = row },
                                    onToggleFavorite = {
                                        row.id?.let { viewModel.toggleFavorite(it, row.favorite) }
                                    },
                                    onTogglePinned = {
                                        row.id?.let { viewModel.togglePinned(it, row.pinned) }
                                    },
                                    onToggleArchived = {
                                        row.id?.let { viewModel.toggleArchived(it, row.archived) }
                                    },
                                    onOpenStoryGraph = { row.id?.let(onOpenStoryGraph) }
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
        }
    }
    } // 结束 TwoPaneSessionsWrapper

    if (showDashboardLayout) {
        DashboardLayoutDialog(
            order = dashboardWidgetOrder,
            hidden = dashboardHiddenWidgets,
            onDismiss = { showDashboardLayout = false },
            onSave = { order, hidden ->
                dashboardWidgetOrder = order
                dashboardHiddenWidgets = hidden
                ServiceContainer.prefs.statsDashboardWidgetOrder = order
                ServiceContainer.prefs.statsDashboardHiddenWidgets = hidden
                showDashboardLayout = false
            }
        )
    }

    // AI 生成角色并创建会话中提示
    if (showGeneratingCharacter) {
        NekoDialog(
            onDismiss = { /* 生成中不可关闭 */ },
            title = stringResource(R.string.character_discovery_generating_title),
            message = stringResource(R.string.character_discovery_generating_msg),
            confirmText = stringResource(R.string.character_got_it),
            onConfirm = { showGeneratingCharacter = false },
            cancelText = null
        )
    }

    // 新建会话弹窗
    if (showCreate) {
        CreateSessionDialog(
            characters = characters,
            isLocalMode = appMode == AppMode.LOCAL,
            onDismiss = { showCreate = false },
            onCreate = { req ->
                viewModel.createSession(req) { showCreate = false }
            },
            onLoadCharacters = { viewModel.loadCharacters() }
        )
    }

    // 重命名弹窗
    renaming?.let { row ->
        var name by rememberSaveable(row.key) { mutableStateOf(row.name) }
        NekoDialog(
            onDismiss = { renaming = null },
            title = renameTitle,
            confirmText = saveText,
            onConfirm = {
                viewModel.renameSession(row.id.orEmpty(), name) { renaming = null }
            },
            content = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(sessionNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    // 删除确认弹窗
    deleting?.let { row ->
        NekoDialog(
            onDismiss = { deleting = null },
            title = deleteTitle,
            message = deleteConfirmFmt.format(row.displayName),
            confirmText = deleteText,
            onConfirm = {
                viewModel.deleteSession(row.id.orEmpty()) { deleting = null }
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

/**
 * 概览统计与快速筛选合并成一排可点击的统计卡：每张卡展示数量并作为筛选入口，
 * 选中态用主题色高亮。原先「统计卡 + 筛选 pill」两段内容重复，合并后更紧凑清爽。
 */
@Composable
private fun SessionStatFilters(
    overview: SessionOverview,
    includeArchived: Boolean,
    selected: SessionFilter,
    onSelect: (SessionFilter) -> Unit
) {
    val recentLabel = stringResource(R.string.sessions_recent)
    val pinLabel = stringResource(R.string.sessions_pin)
    val favoriteLabel = stringResource(R.string.sessions_favorite)
    val archiveLabel = stringResource(R.string.sessions_archive)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatFilterCard(
            label = recentLabel,
            value = if (includeArchived) overview.total else (overview.total - overview.archived).coerceAtLeast(0),
            icon = Icons.AutoMirrored.Outlined.Chat,
            selected = selected == SessionFilter.ALL,
            onClick = { onSelect(SessionFilter.ALL) }
        )
        StatFilterCard(
            label = pinLabel,
            value = overview.pinned,
            icon = Icons.Filled.PushPin,
            selected = selected == SessionFilter.PINNED,
            onClick = { onSelect(SessionFilter.PINNED) }
        )
        StatFilterCard(
            label = favoriteLabel,
            value = overview.favorite,
            icon = Icons.Filled.Favorite,
            selected = selected == SessionFilter.FAVORITE,
            onClick = { onSelect(SessionFilter.FAVORITE) }
        )
        StatFilterCard(
            label = archiveLabel,
            value = overview.archived,
            icon = Icons.Filled.Archive,
            selected = selected == SessionFilter.ARCHIVED,
            onClick = { onSelect(SessionFilter.ARCHIVED) }
        )
    }
}

@Composable
private fun RowScope.StatFilterCard(
    label: String,
    value: Int,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val contentColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val containerColor =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SessionSectionHeader(resultCount: Int) {
    val recentText = stringResource(R.string.sessions_recent)
    val resultsCountFmt = stringResource(R.string.sessions_results_count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = recentText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = resultsCountFmt.format(resultCount),
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
    val searchHint = stringResource(R.string.sessions_search_hint)
    val channelLabel = availableChannels.firstOrNull { it.value == channelFilterValue }?.label
    val hasActiveFilter = filter != SessionFilter.ALL || channelFilterValue != null || searchQuery.isNotBlank()

    val accent = MaterialTheme.colorScheme.primary
    val borderColor =
        if (hasActiveFilter) accent.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp)
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (hasActiveFilter) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                searchHint,
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
            FilterChip(label = stringResource(filter.labelResId), active = true)
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
        val searchPlaceholder = stringResource(R.string.sessions_search_placeholder)
        val clearDesc = stringResource(R.string.sessions_clear_input)
        val channelText = stringResource(R.string.sessions_channel)
        val filterLabelText = stringResource(R.string.sessions_filter_label)
        val characterLabelText = stringResource(R.string.sessions_character_label)
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(searchPlaceholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = clearDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp)
        )

        // 频道筛选
        Text(channelText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChannelChips(
            availableChannels = availableChannels,
            selectedValue = channelFilterValue,
            onSelect = onChannelFilterChange
        )

        // 会话筛选
        Text(filterLabelText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(characterLabelText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val allLabel = stringResource(R.string.sessions_all)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectableChip(
            label = allLabel,
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
                label = stringResource(f.labelResId),
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
    val allCharactersLabel = stringResource(R.string.sessions_all_characters)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectableChip(
            label = allCharactersLabel,
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
    isLocalMode: Boolean,
    onDismiss: () -> Unit,
    onCreate: (CreateSessionRequest) -> Unit,
    onLoadCharacters: () -> Unit
) {
    // 提取本地化字符串
    val newSessionDefault = stringResource(R.string.sessions_new_default)
    val newSessionTitle = stringResource(R.string.sessions_new)
    val createText = stringResource(R.string.common_create)
    val agentChatName = stringResource(R.string.sessions_agent_chat)
    val groupChatName = stringResource(R.string.sessions_group_chat)
    val modeRole = stringResource(R.string.sessions_mode_role)
    val modeAgent = stringResource(R.string.sessions_mode_agent)
    val modeGroup = stringResource(R.string.sessions_mode_group)
    val nameOptional = stringResource(R.string.sessions_name_optional)
    val agentNoInherit = stringResource(R.string.sessions_agent_no_inherit)
    val strategyLabel = stringResource(R.string.sessions_strategy)
    val strategyRoundRobin = stringResource(R.string.sessions_strategy_round_robin)
    val strategyMention = stringResource(R.string.sessions_strategy_mention)
    val strategyRelevance = stringResource(R.string.sessions_strategy_relevance)
    val strategyRandom = stringResource(R.string.sessions_strategy_random)
    val strategyWorldEngine = stringResource(R.string.sessions_strategy_world_engine)
    val selectCharactersLabel = stringResource(R.string.sessions_select_characters)
    val noCharactersText = stringResource(R.string.sessions_no_characters)
    val selectedCharacterFmt = stringResource(R.string.sessions_selected_character)
    val characterNameLabel = stringResource(R.string.sessions_character_name)
    val characterInputHint = stringResource(R.string.sessions_character_input_hint)
    val selectButtonText = stringResource(R.string.sessions_select_button)
    val noneSelectText = stringResource(R.string.sessions_none_select)
    val firstMessageOptional = stringResource(R.string.sessions_first_message_optional)

    var sessionMode by rememberSaveable { mutableStateOf("character") }
    var name by rememberSaveable { mutableStateOf(newSessionDefault) }
    var characterName by rememberSaveable { mutableStateOf("") }
    var firstMessage by rememberSaveable { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    /** 选中的角色对象（来自下拉或 ID 输入匹配） */
    var selectedCharacter by remember { mutableStateOf<CharacterPreset?>(null) }
    var relationshipStateSource by rememberSaveable { mutableStateOf(RELATIONSHIP_STATE_SOURCE_INHERIT) }
    /** 标记用户是否手动编辑过会话名 / 首条消息，避免被角色切换覆盖。 */
    var nameEditedByUser by rememberSaveable { mutableStateOf(false) }
    var firstMessageEditedByUser by rememberSaveable { mutableStateOf(false) }
    val firstMessageScrollState = rememberScrollState()

    // 群聊模式：选中的角色 ID 列表 + 发言策略
    var selectedGroupCharacterIds by remember { mutableStateOf(emptyList<String>()) }
    var speechStrategy by rememberSaveable { mutableStateOf("round_robin") }
    var speechStrategyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (characters.isEmpty()) onLoadCharacters()
    }

    /** 切换到指定角色时同步首条消息（仅覆盖未被手动编辑过的字段）。会话名保持默认不覆盖。 */
    fun applyCharacter(c: CharacterPreset?) {
        selectedCharacter = c
        if (c == null) return
        if (!firstMessageEditedByUser) firstMessage = c.firstMessage.orEmpty()
    }

    // 当角色名输入框变化时，尝试在已加载角色列表里按名称匹配；命中则自动填充对应字段
    LaunchedEffect(characterName, characters) {
        val input = characterName.trim()
        if (input.isEmpty()) {
            // 名字清空时也清空已选角色（保留手动输入的 firstMessage）
            selectedCharacter = null
            return@LaunchedEffect
        }
        val match = characters.firstOrNull { it.displayName == input }
        if (match != null && match != selectedCharacter) {
            applyCharacter(match)
        } else if (match == null) {
            // 输入了不在列表中的名字，视为未匹配
            selectedCharacter = null
        }
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = newSessionTitle,
        confirmText = createText,
        onConfirm = {
            val char = selectedCharacter
            val req = when (sessionMode) {
                "agent" -> CreateSessionRequest(
                    name = name.ifBlank { agentChatName },
                    sessionMode = "agent",
                    systemPrompt = "",
                    firstMessage = "",
                    senderName = "Agent",
                    characterId = "",
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                "group" -> CreateSessionRequest(
                    name = name.ifBlank { groupChatName },
                    sessionMode = "group",
                    characterIds = selectedGroupCharacterIds,
                    senderName = groupChatName,
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
                    groupConfig = com.google.gson.JsonObject().apply {
                        addProperty("speaker_strategy", speechStrategy)
                    }
                )
                else -> CreateSessionRequest(
                    name = name.ifBlank { newSessionDefault },
                    sessionMode = "character",
                    characterId = char?.id,
                    systemPrompt = char?.systemPrompt?.takeIf { it.isNotBlank() },
                    firstMessage = firstMessage.ifBlank { char?.firstMessage },
                    scenario = char?.scenario?.takeIf { it.isNotBlank() },
                    senderName = char?.displayName,
                    senderAvatar = char?.avatar,
                    senderPortrait = char?.portrait,
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
                    relationshipStateSource = relationshipStateSource
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
                    label = modeRole,
                    selected = sessionMode == "character",
                    onClick = { sessionMode = "character" }
                )
                SelectableChip(
                    label = modeAgent,
                    selected = sessionMode == "agent",
                    onClick = { sessionMode = "agent" }
                )
                SelectableChip(
                    label = modeGroup,
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
                        label = { Text(nameOptional) },
                        placeholder = { Text(agentChatName, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        agentNoInherit,
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
                        label = { Text(nameOptional) },
                        placeholder = { Text(groupChatName, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))

                    // 发言策略下拉选择
                    val strategies = listOf(
                        "round_robin" to strategyRoundRobin,
                        "mention" to strategyMention,
                        "relevance" to strategyRelevance,
                        "random" to strategyRandom,
                        "world_engine" to strategyWorldEngine
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
                            label = { Text(strategyLabel) },
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
                    Text(selectCharactersLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (characters.isEmpty()) {
                        Text(noCharactersText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                selectedCharacterFmt.format(previewChar.displayName),
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
                        label = { Text(nameOptional) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))

                    // 角色名输入 + 选择按钮（按钮高度对齐 OutlinedTextField）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = characterName,
                            onValueChange = { characterName = it },
                            label = { Text(characterNameLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(characterInputHint, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                        Spacer(Modifier.size(8.dp))
                        Box(
                            modifier = Modifier.height(56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            GlassCard(
                                modifier = Modifier
                                    .height(56.dp)
                                    .clickable { dropdownExpanded = !dropdownExpanded },
                                cornerRadius = 12,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Text(selectButtonText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.size(2.dp))
                                    Icon(
                                        if (dropdownExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    // 内联角色选择列表：点击「选择」按钮展开/收起，可滚动，不受下拉菜单高度限制
                    if (dropdownExpanded) {
                        Spacer(Modifier.height(8.dp))
                        if (characters.isEmpty()) {
                            Text(
                                noCharactersText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dropdownExpanded = false
                                            characterName = ""
                                            selectedCharacter = null
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        noneSelectText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                                characters.forEach { c ->
                                    val selected = c.id == selectedCharacter?.id
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                                            .clickable {
                                                dropdownExpanded = false
                                                // 切换角色时同步角色名 + 首条消息（除非用户已手动编辑）
                                                characterName = c.displayName
                                                applyCharacter(c)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
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

                    if (isLocalMode && selectedCharacter != null) {
                        Spacer(Modifier.height(12.dp))
                        RelationshipStateSourceSelector(
                            selectedSource = relationshipStateSource,
                            onSourceSelected = { relationshipStateSource = it },
                            initialState = selectedCharacter?.state
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    // 首条消息：固定高度的滚动窗口，避免长内容撑爆弹窗
                    OutlinedTextField(
                        value = firstMessage,
                        onValueChange = {
                            firstMessage = it
                            firstMessageEditedByUser = it.isNotBlank()
                        },
                        label = { Text(firstMessageOptional) },
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

private val SessionItemShape = RoundedCornerShape(16.dp)
private val SessionPortraitShape = RoundedCornerShape(12.dp)

/** 单个会话项卡片。 */
@Composable
private fun SessionItem(
    row: SessionListRow,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onOpenDetail: () -> Unit = {},
    onToggleArchived: () -> Unit = {},
    onOpenStoryGraph: () -> Unit = {}
) {
    var menuExpanded by remember(row.key) { mutableStateOf(false) }
    // 提取本地化字符串
    val msgCountFmt = stringResource(R.string.sessions_msg_count)
    val pinnedBadge = stringResource(R.string.sessions_pinned_badge)
    val favoritedBadge = stringResource(R.string.sessions_favorited_badge)
    val archivedBadge = stringResource(R.string.sessions_archived_badge)
    val moreDesc = stringResource(R.string.sessions_more)
    val detailMenuText = stringResource(R.string.sessions_detail_menu)
    val renameMenuText = stringResource(R.string.sessions_rename_menu)
    val unpinText = stringResource(R.string.sessions_unpin)
    val pinText = stringResource(R.string.sessions_pin)
    val unfavoriteText = stringResource(R.string.sessions_unfavorite)
    val favoriteText = stringResource(R.string.sessions_favorite)
    val unarchiveText = stringResource(R.string.sessions_unarchive)
    val archiveText = stringResource(R.string.sessions_archive)
    val storyGraphText = stringResource(R.string.sessions_story_graph)
    val deleteText = stringResource(R.string.common_delete)
    val context = LocalContext.current
    val portraitRequest = remember(context, row.portraitUrl) {
        row.portraitUrl?.let { portraitUrl ->
            ImageRequest.Builder(context)
                .data(portraitUrl)
                // 列表快速滑动时避免多张新头像同时执行淡入动画。
                .crossfade(false)
                // GIF 头像在列表中只播放一次，避免多个可见行持续逐帧刷新。
                .repeatCount(0)
                .build()
        }
    }
    // 列表背景是纯色，预合成后视觉等价于半透明 GlassCard，同时省去逐帧透明混合与渐变描边。
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
        .copy(alpha = 0.45f)
        .compositeOver(MaterialTheme.colorScheme.background)
    val borderColor = MaterialTheme.colorScheme.onSurface
        .copy(alpha = 0.08f)
        .compositeOver(containerColor)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .listItemSemantics("会话：${row.displayName}，${row.lastMessage ?: "无消息"}，${row.updatedAt ?: ""}"),
        shape = SessionItemShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 60.dp)
                    .clip(SessionPortraitShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (portraitRequest != null) {
                    AsyncImage(
                        model = portraitRequest,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        if (row.isGroupSession) Icons.Outlined.Group
                        else Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.size(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                if (row.lastMessage != null || row.updatedAt != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        row.lastMessage?.let { preview ->
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } ?: Spacer(Modifier.weight(1f))
                        row.updatedAt?.let { time ->
                            if (row.lastMessage != null) Spacer(Modifier.size(8.dp))
                            Text(
                                text = time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.characterLabel?.let {
                        SessionMetaLabel(text = it, emphasized = true)
                    }
                    row.messageCount?.let { SessionMetaLabel(text = msgCountFmt.format(it)) }
                    if (row.pinned) SessionStatusIcon(Icons.Filled.PushPin, pinnedBadge)
                    if (row.favorite) SessionStatusIcon(Icons.Filled.Favorite, favoritedBadge)
                    if (row.archived) SessionStatusIcon(Icons.Filled.Archive, archivedBadge)
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = moreDesc,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(detailMenuText) },
                        onClick = {
                            menuExpanded = false
                            onOpenDetail()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(renameMenuText) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (row.pinned) unpinText else pinText) },
                        onClick = {
                            menuExpanded = false
                            onTogglePinned()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (row.favorite) unfavoriteText else favoriteText) },
                        onClick = {
                            menuExpanded = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (row.archived) unarchiveText else archiveText) },
                        onClick = {
                            menuExpanded = false
                            onToggleArchived()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(storyGraphText) },
                        onClick = {
                            menuExpanded = false
                            onOpenStoryGraph()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(deleteText, color = Color(0xFFFF6B6B)) },
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
 * 对外暴露 [displayedSessionRows]（已筛选 + 已搜索 + 已排序 + 已解析角色回退）。
 */
class SessionsViewModel : BaseViewModel() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    private val _dashboardTokenStats = MutableStateFlow<TokenStats?>(null)
    private val _dashboardRankings = MutableStateFlow<TokenRankings?>(null)
    private val _dashboardHighAffectionCharacterCount = MutableStateFlow(0)
    private val _dashboardWorldBookCount = MutableStateFlow(0)
    private val _dashboardAchievementScopeId = MutableStateFlow(
        com.nekobot.app.data.local.AchievementManager.activeScopeId()
    )
    private val _dashboardLoading = MutableStateFlow(false)
    private var dataSourceVersion = 0L
    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    private val _webDavStatus = MutableStateFlow<DashboardWebDavStatus?>(null)
    private val _localLogPreview = MutableStateFlow<List<DashboardLogEntry>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()
    val dashboardLoading: StateFlow<Boolean> = _dashboardLoading.asStateFlow()

    val dashboardData: StateFlow<SessionStatsDashboardData> = combine(
        _sessions,
        _dashboardTokenStats,
        _dashboardRankings,
        _characters,
        _webDavStatus,
        _localLogPreview,
        _dashboardHighAffectionCharacterCount,
        _dashboardWorldBookCount,
        _dashboardAchievementScopeId
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        buildSessionStatsDashboardData(
            sessions = values[0] as List<Session>,
            stats = values[1] as TokenStats?,
            rankings = values[2] as TokenRankings?,
            characters = values[3] as List<CharacterPreset>,
            webDavStatus = values[4] as DashboardWebDavStatus?,
            localLogPreview = values[5] as List<DashboardLogEntry>,
            highAffectionCharacterCount = values[6] as Int,
            worldBookCount = values[7] as Int,
            achievementScopeId = values[8] as String
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SessionStatsDashboardData()
        )

    val overview: StateFlow<SessionOverview> = _sessions
        .map(::buildSessionOverview)
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SessionOverview()
        )

    private val _filter = MutableStateFlow(SessionFilter.ALL)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    /** 当前选中的频道值（null = 全部频道）。默认 null。 */
    private val _channelFilterValue = MutableStateFlow<String?>(null)
    val channelFilterValue: StateFlow<String?> = _channelFilterValue.asStateFlow()

    /** 动态频道列表：从已加载会话的 type 字段派生。 */
    val availableChannels: StateFlow<List<ChannelOption>> = _sessions
        .map { sessions ->
            val types = sessions
                .mapNotNull { it.type?.lowercase()?.trim()?.takeIf { t -> t.isNotBlank() } }
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
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _characterFilterId = MutableStateFlow<String?>(null)
    val characterFilterId: StateFlow<String?> = _characterFilterId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 对外展示的最近会话列表：频道筛选 + 置顶优先 + 应用筛选 + 应用搜索 + 按时间倒序。 */
    private val displayedSessions: StateFlow<List<Session>> = combine(
        _sessions,
        _filter,
        _channelFilterValue,
        _characterFilterId,
        _searchQuery,
        ServiceContainer.prefs.recentSessionsIncludeArchivedFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<Session>
        val f = values[1] as SessionFilter
        val chVal = values[2] as String?
        val charId = values[3] as String?
        val query = values[4] as String
        val includeArchived = values[5] as Boolean
        // 先排除压缩上下文自动创建的归档会话（is_archive == true）；
        // 手动归档的会话（archived == true 但 is_archive != true）仍可展示
        val visible = all
            .filter { it.isArchive != true }
            .let { sessions ->
                if (f == SessionFilter.ALL && !includeArchived) {
                    sessions.filter { it.archived != true }
                } else {
                    sessions
                }
            }
        // 先按频道筛选
        val byChannel = applyChannelFilter(visible, chVal)
        val filtered = applyFilter(byChannel, f, charId)
        val searched = applySearch(filtered, query)
        // 置顶强制置顶；归档会话沉到底部；同组内按时间倒序（updatedAt 回退到 createdAt）
        searched.sortedWith(
            compareByDescending<Session> { it.archived != true }
                .thenByDescending { it.pinned == true }
                .thenByDescending { it.updatedAt ?: it.createdAt ?: "" }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * 列表行在数据变化时于后台线程一次性生成；滚动过程中不再扫描角色列表、
     * 拼接群聊角色名或计算完整 Session 的 hashCode。
     */
    val displayedSessionRows: StateFlow<List<SessionListRow>> = combine(
        displayedSessions,
        _characters
    ) { sessions, characters ->
        buildSessionListRows(
            sessions = sessions,
            characters = characters,
            portraitUrlResolver = { resolveAvatarUrl(it) }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
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
    fun loadAll(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        loadSessions(version)
        loadCharacters(version)
    }

    /** 数据库 Profile / 运行模式切换时先清空旧快照，再从新数据源完整加载。 */
    fun reloadForDataSource() {
        val version = ++dataSourceVersion
        _sessions.value = emptyList()
        _characters.value = emptyList()
        _dashboardTokenStats.value = null
        _dashboardRankings.value = null
        _dashboardHighAffectionCharacterCount.value = 0
        _dashboardWorldBookCount.value = 0
        _webDavStatus.value = null
        _localLogPreview.value = emptyList()
        _dashboardAchievementScopeId.value =
            com.nekobot.app.data.local.AchievementManager.activeScopeId()
        loadAll(version)
        loadDashboardMetrics(version)
    }

    /** 独立加载负一屏 Token 总览与排行榜；失败时仍展示会话派生统计。 */
    fun loadDashboardMetrics(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        viewModelScope.launch {
            _dashboardLoading.value = true
            try {
                val results = coroutineScope {
                    val statsRequest = async { unified.tokenStats(dateRange = "total") }
                    val rankingsRequest = async { unified.tokenRankings() }
                    val highAffectionCountRequest = async {
                        unified.countHighAffectionCharacters(threshold = 90)
                    }
                    val worldBooksRequest = async { unified.listWorldBooks() }
                    DashboardMetricsLoadResults(
                        stats = statsRequest.await(),
                        rankings = rankingsRequest.await(),
                        highAffectionCount = highAffectionCountRequest.await(),
                        worldBooks = worldBooksRequest.await()
                    )
                }
                if (version != dataSourceVersion) return@launch
                if (results.stats is Resource.Success) {
                    _dashboardTokenStats.value = results.stats.data
                }
                if (results.rankings is Resource.Success) {
                    _dashboardRankings.value = results.rankings.data
                }
                if (results.highAffectionCount is Resource.Success) {
                    _dashboardHighAffectionCharacterCount.value = results.highAffectionCount.data
                }
                if (results.worldBooks is Resource.Success) {
                    _dashboardWorldBookCount.value = results.worldBooks.data.size
                }
                loadWebDavStatus()
                loadLocalLogPreview()
            } catch (_: Exception) {
                // 会话统计可独立工作，Token 接口失败不阻断负一屏。
            } finally {
                if (version == dataSourceVersion) {
                    _dashboardLoading.value = false
                }
            }
        }
    }

    /** 加载 WebDAV 备份状态；本地与服务器模式共用同一张状态卡。 */
    private suspend fun loadWebDavStatus() {
        try {
            val configResult = unified.getWebDavConfig()
            val infoResult = unified.webDavInfo()
            val config = (configResult as? Resource.Success)?.data
            val info = (infoResult as? Resource.Success)?.data?.asJsonObject
            val remoteFileSize = info?.get("file_size")
                ?.takeUnless { it.isJsonNull }
                ?.asLong
                ?: info?.get("size")
                    ?.takeUnless { it.isJsonNull }
                    ?.asLong
                ?: config?.lastFileSize
            _webDavStatus.value = DashboardWebDavStatus(
                enabled = config?.enabled == true,
                configured = !config?.url.isNullOrBlank(),
                url = config?.url,
                lastBackupAt = config?.lastBackupAt?.takeIf { it.isNotBlank() },
                lastSyncAt = config?.lastSyncAt?.takeIf { it.isNotBlank() },
                lastError = config?.lastError?.takeIf { it.isNotBlank() },
                remoteFileSize = remoteFileSize,
                remoteModifiedAt = info?.get("last_modified")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: config?.lastModified?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            _webDavStatus.value = null
        }
    }

    /** 加载本地日志速览（最近 5 条）。 */
    private suspend fun loadLocalLogPreview() {
        try {
            val records = withContext(Dispatchers.IO) {
                com.nekobot.app.data.local.LocalLogger.listLogs()
            }
            _localLogPreview.value = records.take(5).map { record ->
                DashboardLogEntry(
                    time = record.time,
                    level = record.level,
                    tag = record.tag,
                    message = record.message
                )
            }
        } catch (_: Exception) {
            _localLogPreview.value = emptyList()
        }
    }

    /** 加载会话列表。 */
    fun loadSessions(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        launchResult(
            block = { unified.listSessions() },
            onSuccess = {
                if (version == dataSourceVersion) {
                    _sessions.value = it ?: emptyList()
                }
            }
        )
    }

    /**
     * 导入会话：支持本地模式导出格式、nekobot 远程导出格式，远程模式也兼容本地导出。
     * 在 IO 线程读取 Uri 字节并解析，统一调用 [unified.importSessions] 分发到本地或远程实现。
     */
    fun importSessions(uri: Uri, context: Context) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    showToast(string(R.string.sessions_import_empty))
                    return@launch
                }
                val payload = withContext(Dispatchers.Default) {
                    JsonParser.parseString(String(bytes, Charsets.UTF_8))
                }
                if (payload.isJsonNull || (!payload.isJsonObject && !payload.isJsonArray)) {
                    showToast(string(R.string.sessions_import_invalid_format))
                    return@launch
                }
                showToast(string(R.string.sessions_import_progress))
                when (val res = unified.importSessions(payload)) {
                    is Resource.Success -> {
                        val r = res.data
                        when {
                            r.imported > 0 && r.failed == 0 -> {
                                showToast(string(R.string.sessions_import_success, r.imported))
                            }
                            r.imported > 0 && r.failed > 0 -> {
                                showToast(string(R.string.sessions_import_partial, r.imported, r.failed))
                            }
                            r.imported == 0 && r.failed > 0 -> {
                                val detail = r.errors.firstOrNull() ?: ""
                                showToast(
                                    if (detail.isBlank()) string(R.string.sessions_import_failed, "")
                                    else string(R.string.sessions_import_failed, detail)
                                )
                            }
                            else -> showToast(string(R.string.sessions_import_empty))
                        }
                        if (r.imported > 0) loadSessions()
                    }
                    is Resource.Error -> showToast(string(R.string.sessions_import_failed, res.message ?: ""))
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showToast(string(R.string.sessions_import_failed, e.message ?: ""))
            } finally {
                setLoading(false)
            }
        }
    }

    /** 加载角色列表（供新建会话下拉菜单使用）。 */
    fun loadCharacters(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { list ->
                if (version == dataSourceVersion) {
                    _characters.value = list ?: emptyList()
                }
            }
        )
    }

    /** 新建会话，成功后刷新并回调 [onSuccess]。 */
    fun createSession(req: CreateSessionRequest, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.createSession(req) },
            onSuccess = {
                showToast(string(R.string.sessions_created_toast))
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 为指定角色一键创建会话，成功后回调 sessionId。 */
    fun createSessionForCharacter(characterId: String, characterName: String, onSuccess: (String) -> Unit) {
        launchResult(
            block = {
                val req = CreateSessionRequest(
                    name = characterName,
                    sessionMode = "character",
                    characterId = characterId,
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                unified.createSession(req)
            },
            onSuccess = { session ->
                session?.id?.let { id ->
                    showToast(string(R.string.sessions_created_toast))
                    loadSessions()
                    onSuccess(id)
                }
            }
        )
    }

    /**
     * AI 根据描述生成角色卡并持久化，然后自动为其创建会话，成功后回调 sessionId。
     */
    fun aiGenerateCharacterAndCreateSession(
        description: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        launchResult(
            block = { unified.aiGenerateCharacter(description) },
            onSuccess = { preset ->
                preset ?: run {
                    showToast(string(R.string.character_ai_generate_failed))
                    onError(string(R.string.character_ai_generate_failed))
                    return@launchResult
                }
                // 保存生成的角色卡
                launchResult(
                    block = {
                        val json = ServiceContainer.gson.toJsonTree(preset)
                        unified.createCharacter(json)
                    },
                    onSuccess = { saved ->
                        saved?.id?.let { characterId ->
                            showToast(string(R.string.character_ai_generated, saved.displayName))
                            // 自动创建会话
                            createSessionForCharacter(
                                characterId = characterId,
                                characterName = saved.displayName,
                                onSuccess = onSuccess
                            )
                        } ?: run {
                            showToast(string(R.string.character_ai_generate_failed))
                            onError(string(R.string.character_ai_generate_failed))
                        }
                    },
                    onError = { msg ->
                        showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
                        onError(msg ?: string(R.string.character_ai_generate_failed))
                    }
                )
            },
            onError = { msg ->
                showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
                onError(msg ?: string(R.string.character_ai_generate_failed))
            }
        )
    }

    /**
     * 使用 AI 随机生成 3 个角色灵感条目。本地模式直接调用 LLM；远程模式回退到内置种子。
     */
    fun generateRandomCharacterIdeas(onResult: (List<RandomCharacterIdea>) -> Unit) {
        launchResult(
            block = { unified.generateRandomCharacterIdeas() },
            onSuccess = { ideas ->
                if (!ideas.isNullOrEmpty()) {
                    onResult(ideas)
                } else {
                    showToast(string(R.string.character_ai_generate_failed, "无可用模型"))
                }
            },
            onError = { msg ->
                showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
            }
        )
    }

    /** 删除会话，成功后刷新并回调 [onSuccess]。 */
    fun deleteSession(id: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.deleteSession(id) },
            onSuccess = {
                showToast(string(R.string.sessions_deleted_toast))
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 重命名会话，成功后刷新并回调 [onSuccess]。 */
    fun renameSession(id: String, name: String, onSuccess: () -> Unit = {}) {
        if (name.isBlank()) {
            showError(string(R.string.sessions_name_empty_error))
            return
        }
        launchResult(
            block = { unified.updateSession(id, UpdateSessionRequest(name = name)) },
            onSuccess = {
                showToast(string(R.string.sessions_renamed_toast))
                loadSessions()
                onSuccess()
            }
        )
    }

    /** 切换收藏状态。 */
    fun toggleFavorite(sessionId: String, currentlyFavorite: Boolean) {
        val newFav = !currentlyFavorite
        launchResult(
            block = { unified.updateSession(sessionId, UpdateSessionRequest(favorite = newFav)) },
            onSuccess = {
                loadSessions()
            }
        )
    }

    /** 切换置顶状态。 */
    fun togglePinned(sessionId: String, currentlyPinned: Boolean) {
        val newPinned = !currentlyPinned
        launchResult(
            block = { unified.updateSession(sessionId, UpdateSessionRequest(pinned = newPinned)) },
            onSuccess = {
                showToast(if (newPinned) string(R.string.sessions_pinned_toast) else string(R.string.sessions_unpinned_toast))
                loadSessions()
            }
        )
    }

    /** 切换归档状态。 */
    fun toggleArchived(sessionId: String, currentlyArchived: Boolean) {
        val newArchived = !currentlyArchived
        launchResult(
            block = {
                if (newArchived) unified.archiveSession(sessionId)
                else unified.restoreSession(sessionId)
            },
            onSuccess = {
                showToast(if (newArchived) string(R.string.sessions_archived_toast) else string(R.string.sessions_unarchived_toast))
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
