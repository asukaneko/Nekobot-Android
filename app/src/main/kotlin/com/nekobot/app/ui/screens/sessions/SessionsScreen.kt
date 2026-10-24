package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.platform.LocalResources

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.nekobot.app.ui.screens.chat.ChatSessionManager
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.navigation.rememberLiquidGlassBottomBarClearance
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.adaptive.listItemSemantics
import com.nekobot.app.ui.adaptive.rememberShouldUseTwoPane
import com.nekobot.app.ui.screens.chat.ModernChatScreen
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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.JsonParser

/** 会话列表筛选类型。 */
enum class SessionFilter(val labelResId: Int) {
    ALL(R.string.sessions_recent),
    CHARACTER_SESSIONS(R.string.sessions_filter_character_sessions),
    AGENT_SESSIONS(R.string.sessions_filter_agent_sessions),
    AGENT_CHARACTERS(R.string.sessions_filter_agent_characters),
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
 * 双栏拖动条宽度：整条都可拖动，视觉上只有中间 1dp 分隔线与抓手。
 */
private val TwoPaneHandleWidth = 16.dp

/** 双栏默认左列占比（列表 38% / 聊天 62%）。 */
private const val TwoPaneDefaultListFraction = 0.38f

/** 正常拖动时的占比上下限，避免某一侧被拖到无法使用。 */
private const val TwoPaneMinListFraction = 0.15f
private const val TwoPaneMaxListFraction = 0.85f

/** 松手吸附阈值：小于前者 → 聊天全屏；大于后者 → 列表全屏。 */
private const val TwoPaneCollapseThreshold = 0.12f
private const val TwoPaneExpandThreshold = 0.88f

/**
 * 会话-聊天双栏布局包装器。
 *
 * - Compact 模式：直接渲染 [content]，保持现有单栏行为
 * - Medium/Expanded 模式：Row 布局，左列显示会话列表 [content]，
 *   右列显示选中会话的聊天（ModernChatScreen）或占位文本；
 *   两列之间是可拖动的分隔条，拖动可改变占比，拖到两端并松手
 *   会让其中一侧全屏（列表全屏 / 聊天全屏）
 *
 * @param useTwoPane 是否使用双栏
 * @param selectedSessionId 当前选中的会话 ID（null 表示未选中）
 * @param onSessionSelected 选中会话回调
 * @param onClearSelection 清除选中回调
 * @param onOpenDetail 打开会话详情回调
 * @param onOpenWorkspace 打开会话工作区回调
 * @param onOpenContextAnalysis 打开上下文分析回调
 * @param onOpenStoryGraph 打开剧情图回调
 * @param onOpenWenku8Login 打开 wenku8 登录页回调
 * @param onChatMaximizedChange 聊天全屏状态变化回调（用于隐藏底部导航栏）
 * @param content 会话列表内容（Scaffold）
 */
@Composable
private fun TwoPaneSessionsWrapper(
    useTwoPane: Boolean,
    selectedSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onOpenContextAnalysis: (String) -> Unit,
    onOpenCompanionMemory: (String) -> Unit = {},
    onOpenStoryGraph: (String) -> Unit,
    onOpenEnvironmentVariables: (String) -> Unit,
    onOpenWenku8Login: () -> Unit,
    onChatMaximizedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    // 左右占比：0 = 聊天全屏，1 = 列表全屏。拖动时实时跟随手指，松手时吸附。
    var listFraction by rememberSaveable { mutableStateOf(TwoPaneDefaultListFraction) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动中不做动画（1:1 跟随手指），松手后的吸附用短动画过渡
    val fraction by animateFloatAsState(
        targetValue = listFraction,
        animationSpec = if (dragging) snap() else tween(180, easing = FastOutSlowInEasing),
        label = "twoPaneListFraction"
    )
    // 聊天占满整个宽度（左列收起）时，通知上层隐藏底部导航栏。
    // 这里用动画后的占比判断：底栏隐藏与输入区避让间距归零同时发生，不会出现跳动。
    val chatMaximized = useTwoPane && fraction <= 0.0001f
    LaunchedEffect(chatMaximized) { onChatMaximizedChange(chatMaximized) }
    // 底栏高度随窗口宽度变化（平板更高），避让间距必须取当前值。
    val bottomBarClearance = rememberLiquidGlassBottomBarClearance()

    if (!useTwoPane) {
        content()
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidth = (maxWidth - TwoPaneHandleWidth).coerceAtLeast(0.dp)
        val availablePx = with(LocalDensity.current) { availableWidth.toPx() }
        Row(modifier = Modifier.fillMaxSize()) {
            // 左列：会话列表（收起到 0 宽时不再组合内容，滚动位置由 ViewModel 的 LazyListState 保留）
            Box(
                modifier = Modifier
                    .width(availableWidth * fraction)
                    .fillMaxHeight()
            ) {
                if (fraction > 0.0001f) content()
            }
            // 拖动条：始终存在且位置固定，拖动过程中不会被重组打断
            TwoPaneDragHandle(
                dragging = dragging,
                onDragStart = { dragging = true },
                onDrag = { deltaX ->
                    if (availablePx > 0f) {
                        listFraction = (listFraction + deltaX / availablePx).coerceIn(0f, 1f)
                    }
                },
                onDragEnd = {
                    dragging = false
                    listFraction = when {
                        listFraction < TwoPaneCollapseThreshold -> 0f
                        listFraction > TwoPaneExpandThreshold -> 1f
                        else -> listFraction.coerceIn(TwoPaneMinListFraction, TwoPaneMaxListFraction)
                    }
                },
                modifier = Modifier
                    .width(TwoPaneHandleWidth)
                    .fillMaxHeight()
            )
            // 右列：聊天或占位（列表全屏时不再组合内容）
            Box(
                modifier = Modifier
                    .width(availableWidth * (1f - fraction))
                    .fillMaxHeight()
            ) {
                if (fraction < 0.9999f) {
                    val sid = selectedSessionId
                    if (sid != null) {
                        ModernChatScreen(
                            sessionId = sid,
                            onBack = onClearSelection,
                            onOpenChat = onSessionSelected,
                            onOpenSessionDetail = onOpenDetail,
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenContextAnalysis = onOpenContextAnalysis,
                            onOpenCompanionMemory = onOpenCompanionMemory,
                            onOpenStoryGraph = onOpenStoryGraph,
                            onOpenEnvironmentVariables = onOpenEnvironmentVariables,
                            onOpenWenku8Login = onOpenWenku8Login,
                            // 平板双栏：底部悬浮导航栏盖住输入框，输入区整体抬升避让；
                            // 聊天全屏时底栏已隐藏，不再需要避让
                            embeddedBottomBarClearance = if (chatMaximized) {
                                0.dp
                            } else {
                                bottomBarClearance
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.sessions_select_to_chat),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 双栏拖动条：整条区域可水平拖动，中间显示 1dp 分隔线与可抓取的小胶囊。
 * 拖动时胶囊变为主色，给出明确的"正在调整布局"反馈。
 */
@Composable
private fun TwoPaneDragHandle(
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.sessions_two_pane_drag_handle)
    val gripColor by animateColorAsState(
        targetValue = if (dragging) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "twoPaneGripColor"
    )
    Box(
        modifier = modifier
            // 与左右两栏的背景保持一致，拖动条不会出现色带
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        // 分隔线
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )
        // 抓手
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(gripColor)
        )
    }
}

/**
 * 会话页：角色、Agent、专属固定分页，各自搜索与滚动，原会话操作保留。
 * 点击会话项调用 [onOpenChat]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onOpenChat: (String) -> Unit,
    onOpenDetail: (String) -> Unit = onOpenChat,
    onOpenStoryGraph: (String) -> Unit = onOpenChat,
    onNavigate: (String) -> Unit = {},
    /** 主Pager会预组合相邻页；只有当前会话页可以接管统计页的系统返回。 */
    isActive: Boolean = true,
    /** 双栏模式下聊天全屏状态变化（用于隐藏底部导航栏）。 */
    onTwoPaneChatMaximizedChange: (Boolean) -> Unit = {}
) {
    val viewModel: SessionsViewModel = viewModel()
    val rowsByPage by viewModel.sessionRowsByPage.collectAsStateWithLifecycle()
    val pageQueries by viewModel.pageQueries.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val sessionCreationState by viewModel.sessionCreationState.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    // 有等待用户处理的 Agent 事件的会话（授权/提问），在列表中显示提示
    val attentionBySession by com.nekobot.app.data.local.ai.AgentAttentionCenter.pendingBySession
        .collectAsStateWithLifecycle()
    // 正在生成 AI 回复的会话集合：列表项左侧渲染主题色运行中竖条
    val generatingSessions by ServiceContainer.generatingSessions.collectAsStateWithLifecycle()
    val dashboardData by viewModel.dashboardData.collectAsStateWithLifecycle()
    val dashboardLoading by viewModel.dashboardLoading.collectAsStateWithLifecycle()

    val channelsByPage by viewModel.availableChannelsByPage.collectAsStateWithLifecycle()

    // 提取本地化字符串
    val titleText = stringResource(R.string.sessions_title)
    val refreshDesc = stringResource(R.string.sessions_refresh)
    val agentChatName = stringResource(R.string.sessions_agent_chat)
    val noMatchTitle = stringResource(R.string.sessions_no_match)
    val filterEmptyTitle = stringResource(R.string.sessions_filter_empty)
    val emptyTitleText = stringResource(R.string.sessions_empty)
    val noMatchHint = stringResource(R.string.sessions_no_match_hint)
    val filterEmptyHint = stringResource(R.string.sessions_filter_empty_hint)
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

    val pagerState = rememberPagerState(initialPage = viewModel.selectedPage.ordinal, pageCount = { SessionPage.entries.size })
    val pageScope = rememberCoroutineScope()
    val currentPage = SessionPage.entries[pagerState.currentPage]
    val currentQuery = pageQueries[currentPage]
    val availableChannels = channelsByPage[currentPage].orEmpty()
    val pageSwipeBoundary = rememberSessionPageSwipeBoundary()
    var dashboardVisible by rememberSaveable { mutableStateOf(false) }
    var listHiddenByChat by remember { mutableStateOf(false) }
    val dashboardStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(pagerState.currentPage) { viewModel.selectPage(currentPage) }
    BackHandler(enabled = isActive && dashboardVisible && !listHiddenByChat) { dashboardVisible = false }

    // 模式切换时自动刷新会话列表
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    LaunchedEffect(appMode, dataSourceRevision) {
        viewModel.onDataSourceChanged(appMode, dataSourceRevision)
    }

    // 角色卡立绘/头像变更后自动刷新会话列表，使会话头像跟随角色卡更新
    LaunchedEffect(Unit) {
        ServiceContainer.characterChanged.collect {
            viewModel.loadAll()
        }
    }

    // 会话改名等资料保存后立即刷新列表，避免列表显示旧名称
    LaunchedEffect(Unit) {
        ServiceContainer.sessionChanged.collect {
            viewModel.loadSessions()
        }
    }

    // 弹窗状态
    var showPageMenu by remember { mutableStateOf(false) }
    var createConfigMode by rememberSaveable { mutableStateOf<String?>(null) }
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

    ErrorToast(message = error, onShown = viewModel::clearError)

    TwoPaneSessionsWrapper(
        useTwoPane = useTwoPane,
        selectedSessionId = selectedSessionId,
        onSessionSelected = { id -> selectedSessionId = id },
        onClearSelection = { selectedSessionId = null },
        onOpenDetail = onOpenDetail,
        onOpenWorkspace = { id -> onNavigate(Routes.workspace(id)) },
        onOpenContextAnalysis = { id -> onNavigate(Routes.contextAnalysis(id)) },
        onOpenCompanionMemory = { id -> onNavigate(Routes.agentCharacterMemory(id)) },
        onOpenStoryGraph = onOpenStoryGraph,
        onOpenEnvironmentVariables = { id -> onNavigate(Routes.sandboxEnvironment(id)) },
        onOpenWenku8Login = { onNavigate(Routes.WENKU_LOGIN) },
        onChatMaximizedChange = { maximized ->
            listHiddenByChat = maximized
            onTwoPaneChatMaximizedChange(maximized)
        }
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (dashboardVisible) IconButton(onClick = { dashboardVisible = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                title = {
                    if (dashboardVisible) {
                        Text(dashboardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Box {
                        // 次要操作放在标题菜单，右侧只保留搜索和当前分类的新建。
                        Row(
                            modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClick = { showPageMenu = true })
                                .semantics { contentDescription = context.getString(R.string.sessions_page_menu) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(titleText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Filled.ArrowDropDown, null, Modifier.padding(start = 4.dp).size(18.dp))
                        }
                        DropdownMenu(expanded = showPageMenu, onDismissRequest = { showPageMenu = false }) {
                            DropdownMenuItem(text = { Text(dashboardTitle) }, onClick = {
                                showPageMenu = false
                                dashboardVisible = true
                            })
                            DropdownMenuItem(text = { Text(refreshDesc) }, onClick = {
                                showPageMenu = false
                                viewModel.loadAll()
                            })
                            DropdownMenuItem(text = { Text(importDesc) }, onClick = {
                                showPageMenu = false
                                importFileLauncher.launch("*/*")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sessions_create_group)) }, onClick = {
                                showPageMenu = false
                                createConfigMode = "group"
                            })
                        }
                    }
                },
                actions = {
                    if (dashboardVisible) {
                        SessionHeaderAction(Icons.Filled.Refresh, dashboardRefreshDesc, onClick = {
                            viewModel.loadAll()
                            viewModel.loadDashboardMetrics()
                        })
                        SessionHeaderAction(Icons.Filled.DashboardCustomize, stringResource(R.string.stats_customize_layout), onClick = { showDashboardLayout = true })
                    } else {
                        SessionHeaderAction(Icons.Filled.Search,
                            stringResource(R.string.sessions_page_search_action, stringResource(currentPage.titleResId)),
                            prominent = currentQuery.isActive, onClick = { showSearchPanel = true })
                        IconButton(
                            onClick = {
                                when (currentPage) {
                                    SessionPage.CHARACTER -> createConfigMode = "character"
                                    SessionPage.COMPANION -> onNavigate(Routes.COMPANION_CREATE)
                                    SessionPage.AGENT -> viewModel.createSession(
                                        CreateSessionRequest(
                                            name = agentChatName, sessionMode = "agent", systemPrompt = "", firstMessage = "",
                                            senderName = "Agent", characterId = "",
                                            userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                                        )
                                    ) { session -> session.id?.takeIf(String::isNotBlank)?.let(handleOpenChat) }
                                }
                            },
                            enabled = currentPage != SessionPage.COMPANION || appMode == AppMode.LOCAL,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Add, stringResource(R.string.sessions_page_create_action, stringResource(currentPage.titleResId)),
                                tint = if (currentPage != SessionPage.COMPANION || appMode == AppMode.LOCAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (dashboardVisible) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                dashboardStateHolder.SaveableStateProvider("sessions-dashboard") {
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
                        onOpenPluginPage = { pluginId, pageId -> onNavigate(Routes.pluginPage(pluginId, pageId)) },
                        onQuickAction = { action ->
                            when (action) {
                                DashboardQuickAction.NEW_SESSION -> createConfigMode = "character"
                                DashboardQuickAction.AI_CONFIG -> onNavigate(Routes.AI_CONFIG_CENTER)
                                DashboardQuickAction.WORLD_BOOKS -> onNavigate(Routes.WORLD_BOOKS)
                                DashboardQuickAction.SETTINGS -> onNavigate(Routes.SETTINGS)
                            }
                        },
                        onCharacterSelected = { character ->
                            viewModel.createSessionForCharacter(character.id, character.name, handleOpenChat)
                        },
                        randomCharacterIdeas = randomCharacterIdeas,
                        onGenerateIdeas = { viewModel.generateRandomCharacterIdeas { randomCharacterIdeas = it } },
                        onGenerateCharacter = { idea ->
                            showGeneratingCharacter = true
                            viewModel.aiGenerateCharacterAndCreateSession(idea.description,
                                onSuccess = { sessionId -> showGeneratingCharacter = false; handleOpenChat(sessionId) },
                                onError = { showGeneratingCharacter = false })
                        }
                    )
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding)) {
            SessionPageTabs(selected = currentPage, onSelect = { page ->
                pageScope.launch { pagerState.animateScrollToPage(page.ordinal) }
            })
            // 只在列表内容区接管横滑；分页边缘余量在此消费，不传给主导航的 Pager。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(pageSwipeBoundary),
                key = { SessionPage.entries[it].name },
                beyondViewportPageCount = 1
            ) { index ->
                val page = SessionPage.entries[index]
                val query = pageQueries[page]
                val rows = rowsByPage[page].orEmpty()
                Column(Modifier.fillMaxSize()) {
                    if (query.isActive) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(query.search.takeIf(String::isNotBlank),
                                    query.filter.takeIf { it != SessionFilter.ALL }?.let { stringResource(it.labelResId) },
                                    query.channel).joinToString(" · "),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            androidx.compose.material3.TextButton(onClick = { viewModel.resetPageQuery(page) }) {
                                Text(stringResource(R.string.sessions_page_clear_filters))
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            loading && rows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            rows.isEmpty() -> EmptyState(
                                title = if (query.search.isNotBlank()) noMatchTitle else if (query.isActive) filterEmptyTitle else emptyTitleText,
                                hint = when {
                                    page == SessionPage.COMPANION && appMode != AppMode.LOCAL -> stringResource(R.string.sessions_page_local_only)
                                    query.search.isNotBlank() -> noMatchHint
                                    query.isActive -> filterEmptyHint
                                    else -> stringResource(R.string.sessions_page_empty_hint, stringResource(page.titleResId))
                                },
                                icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            )
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxSize(), state = viewModel.scrollState(page),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)
                            ) {
                                itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "session-row" }) { rowIndex, row ->
                                    SessionItem(
                                        row = row, first = rowIndex == 0, last = rowIndex == rows.lastIndex, compact = true,
                                        hasAttention = row.id?.let { attentionBySession.containsKey(it) } == true,
                                        isGenerating = row.id != null && row.id in generatingSessions,
                                        onClick = { row.id?.let(handleOpenChat) },
                                        onOpenDetail = { row.id?.let(onOpenDetail) },
                                        onRename = { if (row.id != null) renaming = row },
                                        onDelete = { if (row.id != null) deleting = row },
                                        onToggleFavorite = { row.id?.let { viewModel.toggleFavorite(it, row.favorite) } },
                                        onTogglePinned = { row.id?.let { viewModel.togglePinned(it, row.pinned) } },
                                        onToggleArchived = { row.id?.let { viewModel.toggleArchived(it, row.archived) } },
                                        onOpenStoryGraph = { row.id?.let(onOpenStoryGraph) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    } // TwoPaneSessionsWrapper

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
    createConfigMode?.let { sessionMode ->
        if (sessionMode == "group") {
            CreateGroupSessionDialog(
                characters = sessionCreationState.characters,
                onDismiss = { createConfigMode = null },
                onCreate = { req -> viewModel.createSession(req) { createConfigMode = null } },
                onLoadCharacters = { viewModel.loadSessionCreationCharacters() }
            )
        } else {
            CreateCharacterSessionDialog(
                state = sessionCreationState,
                isLocalMode = appMode == AppMode.LOCAL,
                userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
                onDismiss = { createConfigMode = null },
                onCreate = { req -> viewModel.createSession(req) { createConfigMode = null } },
                onLoadCharacters = { viewModel.loadSessionCreationCharacters() }
            )
        }
    }

    // 重命名弹窗
    renaming?.let { row ->
        var name by rememberSaveable(row.key) { mutableStateOf(row.listTitle) }
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
            Text(
                stringResource(R.string.sessions_page_search_title, stringResource(currentPage.titleResId)),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            SearchPanelContent(
                searchQuery = currentQuery.search,
                onSearchChange = { value -> viewModel.updatePageQuery(currentPage) { it.copy(search = value) } },
                filter = currentQuery.filter,
                onFilterChange = { value -> viewModel.updatePageQuery(currentPage) { it.copy(filter = value, characterId = null) } },
                channelFilterValue = currentQuery.channel,
                availableChannels = availableChannels,
                onChannelFilterChange = { value -> viewModel.updatePageQuery(currentPage) { it.copy(channel = value) } },
                characters = characters,
                characterFilterId = currentQuery.characterId,
                onCharacterFilterChange = { value -> viewModel.updatePageQuery(currentPage) { it.copy(characterId = value) } }
            )
        }
    }
}

/** 兼容既有组件调用；正式会话页的固定分页使用 [SessionPageTabs]。 */
@Composable
internal fun SessionStatFilters(
    overview: SessionOverview,
    selected: SessionFilter,
    onToggle: (SessionFilter) -> Unit
) {
    SessionModeFilters(overview, selected, onToggle)
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
            .verticalScroll(rememberScrollState())
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
            .selectableGroup()
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
            .selectableGroup()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SESSION_PAGE_FILTERS.forEach { f ->
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
            .selectableGroup()
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
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            // 筛选 Chip 同样不使用点按波纹
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

/** 群聊沿用独立的多角色与发言策略配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSessionDialog(
    characters: List<CharacterPreset>,
    onDismiss: () -> Unit,
    onCreate: (CreateSessionRequest) -> Unit,
    onLoadCharacters: () -> Unit
) {
    val stringDefaultName = stringResource(R.string.sessions_new_default)
    val groupChatName = stringResource(R.string.sessions_group_chat)
    val nameOptional = stringResource(R.string.sessions_name_optional)
    val strategyLabel = stringResource(R.string.sessions_strategy)
    val strategyRoundRobin = stringResource(R.string.sessions_strategy_round_robin)
    val strategyMention = stringResource(R.string.sessions_strategy_mention)
    val strategyRelevance = stringResource(R.string.sessions_strategy_relevance)
    val strategyRandom = stringResource(R.string.sessions_strategy_random)
    val strategyWorldEngine = stringResource(R.string.sessions_strategy_world_engine)
    val selectCharactersLabel = stringResource(R.string.sessions_select_characters)
    val noCharactersText = stringResource(R.string.sessions_no_characters)
    var name by rememberSaveable { mutableStateOf(stringDefaultName) }
    var selectedGroupCharacterIds by remember { mutableStateOf(emptyList<String>()) }
    var speechStrategy by rememberSaveable { mutableStateOf("round_robin") }
    var speechStrategyExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoadCharacters() }
    LaunchedEffect(characters) {
        val availableIds = characters.mapNotNull { it.id }.toSet()
        selectedGroupCharacterIds = selectedGroupCharacterIds.filter { it in availableIds }
    }
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sessions_new),
        confirmText = stringResource(R.string.common_create),
        contentScrollable = true,
        onConfirm = {
            onCreate(CreateSessionRequest(
                name = name.ifBlank { groupChatName },
                sessionMode = "group",
                characterIds = selectedGroupCharacterIds,
                senderName = groupChatName,
                userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
                groupConfig = com.google.gson.JsonObject().apply {
                    addProperty("speaker_strategy", speechStrategy)
                }
            ))
        },
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
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
    )
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

/** 「等待处理」提示：该会话有 AI 授权/提问等待用户处理。 */
private val SessionPortraitShape = RoundedCornerShape(12.dp)

/** 单个会话项卡片。 */
@Composable
internal fun SessionItem(
    row: SessionListRow,
    first: Boolean,
    last: Boolean,
    hasAttention: Boolean = false,
    isGenerating: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onOpenDetail: () -> Unit = {},
    onToggleArchived: () -> Unit = {},
    onOpenStoryGraph: () -> Unit = {},
    compact: Boolean = false
) {
    val textResources = LocalResources.current
    var menuExpanded by remember(row.key) { mutableStateOf(false) }
    // 提取本地化字符串
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
    val containerColor = MaterialTheme.colorScheme.surface
    val itemShape = if (compact) RoundedCornerShape(0.dp) else RoundedCornerShape(
        topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
        bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .listItemSemantics(
                stringResource(
                    R.string.sessions_item_description,
                    row.listTitle,
                    listOfNotNull(
                        row.lastMessage ?: stringResource(R.string.sessions_no_message),
                        row.updatedAt
                    ).joinToString(" · ")
                )
            ),
        shape = itemShape,
        color = containerColor,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 16.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(shape = SessionPortraitShape, color = row.modeLabel.color().copy(alpha = .08f),
                    border = BorderStroke(1.dp, row.modeLabel.color().copy(alpha = .08f)), modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (row.modeLabel == SessionModeLabel.COMPANION) {
                            com.nekobot.app.ui.components.CharacterAvatarPhoto(row.portraitUrl, row.avatarFallbackUrl, row.listTitle, Modifier.fillMaxSize())
                        } else if (portraitRequest != null) {
                            AsyncImage(model = portraitRequest, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            val fallbackPainter = if (row.isAgentSession && !row.inheritCharacter) painterResource(R.drawable.ic_agent_neko)
                                else rememberVectorPainter(if (row.isGroupSession) Icons.Outlined.Group else Icons.AutoMirrored.Outlined.Chat)
                            Icon(fallbackPainter, null, Modifier.size(27.dp), tint = row.modeLabel.color())
                        }
                    }
                }
                if (hasAttention || isGenerating) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(9.dp)) { }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.listTitle, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row.pinned) SessionStatusIcon(Icons.Filled.PushPin, pinnedBadge)
                    if (row.favorite) SessionStatusIcon(Icons.Filled.Favorite, favoritedBadge)
                    if (row.archived) SessionStatusIcon(Icons.Filled.Archive, archivedBadge)
                    row.listDate?.let { date ->
                        Text(date, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f), maxLines = 1)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!compact || row.modeLabel == SessionModeLabel.GROUP) {
                        Icon(row.modeLabel.icon(), null, Modifier.size(12.dp), tint = row.modeLabel.color())
                        Text(row.modeLabel.title(), style = MaterialTheme.typography.labelSmall, color = row.modeLabel.color(), maxLines = 1)
                        Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
                    }
                    Text(row.lastMessage ?: stringResource(R.string.sessions_no_message), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = moreDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
        if (!last) HorizontalDivider(Modifier.padding(start = 72.dp, end = 14.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f), thickness = .5.dp)
        }
    }
}

/**
 * 会话列表 ViewModel。
 *
 * 内部维护原始会话列表 + 筛选/搜索状态，
 * 对外暴露 [sessionRowsByPage]（各自筛选、搜索、排序并解析角色回退）。
 */
class SessionsViewModel : BaseViewModel() {

    /**
     * 会话列表滚动状态。
     *
     * 挂在 ViewModel 上而不是 Composable 的 rememberSaveable：rememberSaveable 只保存
     * index/offset，从聊天返回时按重新排序后的同一 index 定位会错位；LazyListState 自身
     * 按 item key 跟踪首个可见项，跨页面存活才能在列表重排后依然停在进入会话前的位置。
     */
    private val pageScrollStates = SessionPage.entries.associateWith { LazyListState() }
    fun scrollState(page: SessionPage): LazyListState = pageScrollStates.getValue(page)

    var selectedPage: SessionPage = SessionPage.CHARACTER
        private set
    fun selectPage(page: SessionPage) { selectedPage = page }

    private val _pageQueries = MutableStateFlow(SessionPageQueries())
    val pageQueries: StateFlow<SessionPageQueries> = _pageQueries.asStateFlow()
    fun updatePageQuery(page: SessionPage, change: (SessionPageQuery) -> SessionPageQuery) {
        val previous = _pageQueries.value
        val updated = previous.update(page, change)
        if (updated != previous) {
            pageScrollStates.getValue(page).requestScrollToItem(0)
            _pageQueries.value = updated
        }
    }
    fun resetPageQuery(page: SessionPage) { updatePageQuery(page) { SessionPageQuery() } }

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
    private val _sessionCreationState = MutableStateFlow(SessionCreationUiState())
    private val _webDavStatus = MutableStateFlow<DashboardWebDavStatus?>(null)
    private val _localLogPreview = MutableStateFlow<List<DashboardLogEntry>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()
    internal val sessionCreationState: StateFlow<SessionCreationUiState> = _sessionCreationState.asStateFlow()
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

    /** 频道选项也限定在当前类别，不在角色页展示仅属于工具Agent的频道。 */
    val availableChannelsByPage: StateFlow<Map<SessionPage, List<ChannelOption>>> = _sessions
        .map { sessions ->
            SessionPage.entries.associateWith { page ->
                val types = sessions.asSequence()
                    .filter { it.isArchive != true && it.sessionPage() == page }
                    .map { it.type?.lowercase()?.trim()?.takeIf(String::isNotBlank) ?: "web" }
                    .distinct().sorted().toList()
                types.map { type ->
                    val label = when (type) {
                        "web" -> "Web"
                        "qq" -> "QQ"
                        "cli" -> "CLI"
                        else -> type.replaceFirstChar { it.uppercase() }
                    }
                    ChannelOption(label, type)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap()
        )

    /** 三页分别筛选并生成稳定列表行；不会因当前页切换而给其他页短暂投放错误数据。 */
    val sessionRowsByPage: StateFlow<Map<SessionPage, List<SessionListRow>>> = combine(
        _sessions, _characters, _pageQueries, ServiceContainer.prefs.recentSessionsIncludeArchivedFlow
    ) { sessions, characters, queries, includeArchived ->
        val characterNames = HashMap<String, String>(characters.size)
        characters.forEach { character ->
            character.id?.takeIf(String::isNotBlank)?.let { characterNames.putIfAbsent(it, character.displayName) }
        }
        SessionPage.entries.associateWith { page ->
            buildSessionListRows(
                selectSessionsForPage(sessions, page, queries[page], includeArchived, characterNames),
                characters,
                portraitUrlResolver = { resolveAvatarUrl(it) }
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyMap()
    )

    /** 加载会话 + 角色列表。 */
    fun loadAll(expectedDataSourceVersion: Long? = null) {
        val version = expectedDataSourceVersion ?: dataSourceVersion
        loadSessions(version)
        loadCharacters(version)
        loadSessionCreationCharacters(version)
    }

    private var lastDataSourceKey: Pair<AppMode, Long>? = null

    /**
     * 数据源（运行模式 / 数据库档案）真正变化时才清空重建列表；
     * 仅从聊天等页面返回时只做静默刷新，不清空列表，避免列表闪空、滚动位置跳变。
     */
    fun onDataSourceChanged(mode: AppMode, revision: Long) {
        val key = mode to revision
        if (lastDataSourceKey == key) {
            loadAll()
            loadDashboardMetrics()
            return
        }
        lastDataSourceKey = key
        reloadForDataSource()
    }

    /** 数据库 Profile / 运行模式切换时先清空旧快照，再从新数据源完整加载。 */
    fun reloadForDataSource() {
        val version = ++dataSourceVersion
        // 数据源已切换，旧的滚动锚点不再适用，回到列表顶部
        pageScrollStates.values.forEach { it.requestScrollToItem(0) }
        _pageQueries.value = SessionPageQueries()
        _sessions.value = emptyList()
        _characters.value = emptyList()
        _sessionCreationState.value = SessionCreationUiState()
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

    /** 加载完整角色列表，供筛选和统计使用。 */
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

    /** 每次打开新建弹窗时刷新可选角色，避免使用旧归属快照。 */
    fun loadSessionCreationCharacters(expectedDataSourceVersion: Long? = null) {
        if (_sessionCreationState.value.loading) return
        val version = expectedDataSourceVersion ?: dataSourceVersion
        _sessionCreationState.value = _sessionCreationState.value.copy(characters = emptyList(), loading = true, error = null)
        launchResult(
            block = {
                try {
                    unified.listCharactersForSessionCreation()
                } finally {
                    if (version == dataSourceVersion) {
                        _sessionCreationState.value = _sessionCreationState.value.copy(loading = false)
                    }
                }
            },
            onSuccess = { list ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(characters = list ?: emptyList())
                }
            },
            onError = { message ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(error = message)
                }
            }
        )
    }

    /** 新建会话，提交期间阻止重复创建，失败后保留弹窗里的输入。 */
    fun createSession(req: CreateSessionRequest, onSuccess: (Session) -> Unit = {}) {
        if (_sessionCreationState.value.creating) return
        val version = dataSourceVersion
        _sessionCreationState.value = _sessionCreationState.value.copy(creating = true, error = null)
        launchResult(
            block = {
                try {
                    unified.createSession(req)
                } finally {
                    if (version == dataSourceVersion) {
                        _sessionCreationState.value = _sessionCreationState.value.copy(creating = false)
                    }
                }
            },
            onSuccess = { session ->
                if (version == dataSourceVersion) {
                    showToast(string(R.string.sessions_created_toast))
                    loadSessions()
                    onSuccess(session)
                }
            },
            onError = { message ->
                if (version == dataSourceVersion) {
                    _sessionCreationState.value = _sessionCreationState.value.copy(error = message)
                    showError(message)
                }
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
                    showToast(string(R.string.character_ai_generate_failed, string(R.string.models_none_available)))
                }
            },
            onError = { msg ->
                showToast(string(R.string.character_ai_generate_failed, msg ?: ""))
            }
        )
    }

    /** 删除会话，成功后刷新并回调 [onSuccess]。 */
    fun deleteSession(id: String, onSuccess: () -> Unit = {}) {
        // 先暂停计时再删除：删除失败时批次与气泡保留（重进聊天页恢复计时），成功才提交撤回。
        ChatSessionManager.pauseDelayedReplyTimer(id)
        launchResult(
            block = { unified.deleteSession(id) },
            onSuccess = {
                ChatSessionManager.cancelDelayedReply(id, removeBubbles = true)
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
            block = { unified.updateSession(id, UpdateSessionRequest(name = name.trim(), updateListTitle = true)) },
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

}
