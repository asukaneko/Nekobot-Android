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
import androidx.compose.foundation.border as materialBorder
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.nekobot.app.ui.screens.chat.ChatSessionManager
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.nekobot.app.data.local.ai.AgentAttentionItem
import com.nekobot.app.data.local.ai.AgentAttentionKind
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
import com.nekobot.app.ui.components.NekoCheckMark
import com.nekobot.app.ui.components.NekoCountBadge
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.NekoEmpty
import com.nekobot.app.ui.components.NekoGroup
import com.nekobot.app.ui.components.NekoIconAction
import com.nekobot.app.ui.components.NekoIconTile
import com.nekobot.app.ui.components.NekoPageHorizontalPadding
import com.nekobot.app.ui.components.NekoPageTopBar
import com.nekobot.app.ui.components.NekoSecondaryAction
import com.nekobot.app.ui.components.NekoSectionHeader
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.NekoTag
import com.nekobot.app.ui.components.NekoTone
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.theme.nekoAccentSoft
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.navigation.rememberBottomBarClearance
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.adaptive.listItemSemantics
import com.nekobot.app.ui.adaptive.rememberShouldUseTwoPane
import com.nekobot.app.ui.screens.chat.ModernChatScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FileUpload
import com.google.gson.JsonParser
import com.nekobot.app.data.repository.SessionImportResult

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

/** 会话多选后的批量操作。 */
enum class SessionBatchAction {
    PIN, FAVORITE, ARCHIVE, DELETE
}

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
    val bottomBarClearance = rememberBottomBarClearance()

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
 * 会话列表页：展示所有会话，支持新建、删除、收藏 / 置顶切换、筛选、搜索与多选批量操作。
 * 点击会话项调用 [onOpenChat]，长按直接进入多选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onOpenChat: (String) -> Unit,
    onOpenDetail: (String) -> Unit = onOpenChat,
    onOpenStoryGraph: (String) -> Unit = onOpenChat,
    onNavigate: (String) -> Unit = {},
    /** 双栏模式下聊天全屏状态变化（用于隐藏底部导航栏）。 */
    onTwoPaneChatMaximizedChange: (Boolean) -> Unit = {},
    /** 多选模式变化：用于隐藏底部导航栏，交给页面自己的批量操作栏。 */
    onSelectionModeChange: (Boolean) -> Unit = {}
) {
    val viewModel: SessionsViewModel = viewModel()
    val sessionRows by viewModel.displayedSessionRows.collectAsStateWithLifecycle()
    // banner 用未过滤的会话行（最新会话 / 固定的那一个）
    val homeRows by viewModel.homeBannerRows.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val sessionCreationState by viewModel.sessionCreationState.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    // 有等待用户处理的 Agent 事件的会话（授权/提问），在列表中显示提示
    val attentionBySession by com.nekobot.app.data.local.ai.AgentAttentionCenter.pendingBySession
        .collectAsStateWithLifecycle()
    // 正在生成 AI 回复的会话集合：列表项左侧渲染主题色运行中竖条
    val generatingSessions by ServiceContainer.generatingSessions.collectAsStateWithLifecycle()
    val dashboardData by viewModel.dashboardData.collectAsStateWithLifecycle()
    val dashboardLoading by viewModel.dashboardLoading.collectAsStateWithLifecycle()

    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val channelFilterValue by viewModel.channelFilterValue.collectAsStateWithLifecycle()
    val availableChannels by viewModel.availableChannels.collectAsStateWithLifecycle()
    val characterFilterId by viewModel.characterFilterId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    // 提取本地化字符串
    val titleText = stringResource(R.string.sessions_title)
    val refreshDesc = stringResource(R.string.sessions_refresh)
    val newSessionDesc = stringResource(R.string.sessions_new)
    val agentChatName = stringResource(R.string.sessions_agent_chat)
    val temporaryChatName = stringResource(R.string.temporary_session)
    val noMatchTitle = stringResource(R.string.sessions_no_match)
    val filterEmptyTitle = stringResource(R.string.sessions_filter_empty)
    val emptyTitleText = stringResource(R.string.sessions_empty)
    val noMatchHint = stringResource(R.string.sessions_no_match_hint)
    val filterEmptyHint = stringResource(R.string.sessions_filter_empty_hint)
    val emptyHintText = stringResource(R.string.sessions_empty_hint)
    val deleteTitle = stringResource(R.string.sessions_delete_title)
    val deleteConfirmFmt = stringResource(R.string.sessions_delete_confirm)
    val deleteText = stringResource(R.string.common_delete)
    val dashboardTitle = stringResource(R.string.stats_dashboard_title)
    val dashboardRefreshDesc = stringResource(R.string.stats_dashboard_refresh)
    val importDesc = stringResource(R.string.sessions_import)
    // 多选顶栏 / 左滑抽屉用到的文案
    val pinText = stringResource(R.string.sessions_pin)
    val unpinText = stringResource(R.string.sessions_unpin)
    val favoriteText = stringResource(R.string.sessions_favorite)
    val unfavoriteText = stringResource(R.string.sessions_unfavorite)
    val archiveText = stringResource(R.string.sessions_archive)
    val unarchiveText = stringResource(R.string.sessions_unarchive)
    val batchEmptyHint = stringResource(R.string.sessions_batch_empty)
    val batchDeleteConfirmFmt = stringResource(R.string.sessions_batch_delete_confirm)

    val context = LocalContext.current
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importSessions(uri, context)
        }
    }

    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 2 })
    // 滚动状态由 ViewModel 持有：进入聊天/详情再返回时仍停在原位置；
    // LazyListState 会按会话 key 记住首个可见项，列表因聊天更新排序后也不会跳回顶部。
    val sessionListState = viewModel.listScrollState
    // 多选模式：进入后点击会话行即勾选，顶栏与底栏换成批量操作
    var selectionMode by remember { mutableStateOf(false) }
    var selectedSessionIds by remember { mutableStateOf(emptySet<String>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    // 左滑抽屉：同一时刻只允许一行展开
    var openSwipeRowKey by remember { mutableStateOf<String?>(null) }
    // 列表滚动时收起已展开的抽屉
    LaunchedEffect(sessionListState.isScrollInProgress) {
        if (sessionListState.isScrollInProgress) openSwipeRowKey = null
    }
    // 多选时隐藏底部导航栏，由页面自己的批量操作栏占位
    LaunchedEffect(selectionMode) { onSelectionModeChange(selectionMode) }
    val exitSelection: () -> Unit = {
        selectionMode = false
        selectedSessionIds = emptySet()
    }
    // 批量操作：提交后立即退出多选，结果由 ViewModel 汇总提示
    val runBatchAction: (SessionBatchAction, Boolean) -> Unit = { action, enable ->
        val ids = selectedSessionIds
        if (ids.isEmpty()) {
            viewModel.showToast(batchEmptyHint)
        } else {
            viewModel.batchUpdateSessions(action, ids, enable)
            exitSelection()
        }
    }

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
    var showCreateModeMenu by remember { mutableStateOf(false) }
    var createConfigMode by rememberSaveable { mutableStateOf<String?>(null) }
    // 新建角色会话时预选的角色（如首页 banner 固定角色直接新建）
    var createCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SessionListRow?>(null) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
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
    // 列表底部需要给底部导航栏留出避让高度（随窗口宽度变化）
    val bottomBarClearance = rememberBottomBarClearance()

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
        onChatMaximizedChange = onTwoPaneChatMaximizedChange
    ) {
    SessionDashboardPager(state = pagerState) { dashboardVisible ->
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode && !dashboardVisible) {
                SessionSelectionTopBar(
                    selectedCount = selectedSessionIds.size,
                    totalCount = sessionRows.size,
                    onSelectAll = {
                        selectedSessionIds = sessionRows.mapNotNull { it.id }.toSet()
                    },
                    onClearAll = { selectedSessionIds = emptySet() },
                    onCancel = exitSelection
                )
            } else {
            NekoPageTopBar(
                title = if (dashboardVisible) dashboardTitle else titleText,
                badge = if (dashboardVisible) {
                    { NekoTag(stringResource(R.string.stats_dashboard_badge)) }
                } else {
                    { NekoCountBadge(sessionRows.size) }
                },
                actions = {
                    if (dashboardVisible) {
                        NekoIconAction(Icons.Filled.Refresh, dashboardRefreshDesc) {
                            viewModel.loadAll()
                            viewModel.loadDashboardMetrics()
                        }
                        NekoIconAction(
                            Icons.Filled.DashboardCustomize,
                            stringResource(R.string.stats_customize_layout),
                            tint = MaterialTheme.colorScheme.primary
                        ) { showDashboardLayout = true }
                    } else {
                        NekoIconAction(Icons.Filled.Search, stringResource(R.string.global_search_title)) {
                            onNavigate(Routes.GLOBAL_SEARCH)
                        }
                        NekoIconAction(Icons.Filled.FileUpload, importDesc) {
                            importFileLauncher.launch("*/*")
                        }
                        Box {
                            NekoIconAction(
                                Icons.Filled.Add,
                                newSessionDesc,
                                tint = MaterialTheme.colorScheme.primary
                            ) { showCreateModeMenu = true }
                            DropdownMenu(
                                expanded = showCreateModeMenu,
                                onDismissRequest = { showCreateModeMenu = false }
                            ) {
                                if (ServiceContainer.prefs.isLocalMode) DropdownMenuItem(
                                    text = { SessionModeMenuText(SessionModeLabel.COMPANION) },
                                    leadingIcon = { Icon(SessionModeLabel.COMPANION.icon(), null, tint = SessionModeLabel.COMPANION.color()) },
                                    onClick = { showCreateModeMenu = false; onNavigate(com.nekobot.app.ui.navigation.Routes.COMPANION_CREATE) }
                                )
                                DropdownMenuItem(
                                    text = { SessionModeMenuText(SessionModeLabel.CHARACTER) },
                                    leadingIcon = {
                                        Icon(SessionModeLabel.CHARACTER.icon(), null, tint = SessionModeLabel.CHARACTER.color())
                                    },
                                    onClick = {
                                        showCreateModeMenu = false
                                        createCharacterId = null
                                        createConfigMode = "character"
                                    }
                                )
                                DropdownMenuItem(
                                    text = { SessionModeMenuText(SessionModeLabel.AGENT) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = SessionModeLabel.AGENT.icon(),
                                            tint = SessionModeLabel.AGENT.color(),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showCreateModeMenu = false
                                        viewModel.createSession(
                                            CreateSessionRequest(
                                                name = agentChatName,
                                                sessionMode = "agent",
                                                systemPrompt = "",
                                                firstMessage = "",
                                                senderName = "Agent",
                                                characterId = "",
                                                userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                                            )
                                        ) { session ->
                                            session.id?.takeIf { it.isNotBlank() }?.let(handleOpenChat)
                                        }
                                    }
                                )
                                if (ServiceContainer.prefs.isLocalMode) DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.temporary_session),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.VisibilityOff,
                                            contentDescription = null,
                                            tint = SessionModeLabel.AGENT.color()
                                        )
                                    },
                                    onClick = {
                                        showCreateModeMenu = false
                                        viewModel.createTemporarySession(temporaryChatName) { session ->
                                            session.id?.takeIf { it.isNotBlank() }?.let(handleOpenChat)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessions_mode_group)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Group, contentDescription = null)
                                    },
                                    onClick = {
                                        showCreateModeMenu = false
                                        createCharacterId = null
                                        createConfigMode = "group"
                                    }
                                )
                            }
                        }
                    }
                }
            )
            }
        },
        bottomBar = {
            // 多选时把四个批量操作放到原底部导航栏的位置。
            // 置顶 / 收藏 / 归档 跟随所选会话状态：全部已置顶则变为「取消置顶」，
            // 全部未置顶为普通状态，既有选中又有未选中（混合）时置灰不可点。
            if (selectionMode && !dashboardVisible) {
                val selectedRows = sessionRows.filter { it.id != null && it.id in selectedSessionIds }
                val hasSelection = selectedRows.isNotEmpty()
                val allPinned = hasSelection && selectedRows.all { it.pinned }
                val anyPinned = selectedRows.any { it.pinned }
                val allFavorited = hasSelection && selectedRows.all { it.favorite }
                val anyFavorited = selectedRows.any { it.favorite }
                val allArchived = hasSelection && selectedRows.all { it.archived }
                val anyArchived = selectedRows.any { it.archived }
                SessionSelectionActionBar(
                    actions = listOf(
                        SelectionBarActionSpec(
                            icon = Icons.Filled.PushPin,
                            label = if (allPinned) unpinText else pinText,
                            enabled = hasSelection && (allPinned || !anyPinned),
                            onClick = { runBatchAction(SessionBatchAction.PIN, !allPinned) }
                        ),
                        SelectionBarActionSpec(
                            icon = if (allFavorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            label = if (allFavorited) unfavoriteText else favoriteText,
                            enabled = hasSelection && (allFavorited || !anyFavorited),
                            onClick = { runBatchAction(SessionBatchAction.FAVORITE, !allFavorited) }
                        ),
                        SelectionBarActionSpec(
                            icon = if (allArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                            label = if (allArchived) unarchiveText else archiveText,
                            enabled = hasSelection && (allArchived || !anyArchived),
                            onClick = { runBatchAction(SessionBatchAction.ARCHIVE, !allArchived) }
                        ),
                        SelectionBarActionSpec(
                            icon = Icons.Outlined.Delete,
                            label = deleteText,
                            enabled = hasSelection,
                            destructive = true,
                            onClick = { showBatchDeleteConfirm = true }
                        )
                    )
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (dashboardVisible) {
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
                    onOpenPluginPage = { pluginId, pageId ->
                        onNavigate(Routes.pluginPage(pluginId, pageId))
                    },
                    onQuickAction = { action ->
                        when (action) {
                            DashboardQuickAction.NEW_SESSION -> {
                                createCharacterId = null
                                createConfigMode = "character"
                            }
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
            // 首屏头部：加载态 / 空态 / 列表态共用同一段内容，三种状态样式一致
            val homeHeader: @Composable () -> Unit = {
                SessionHomeHeaderContent(
                    characters = characters,
                    totalCount = overview.total,
                    filter = filter,
                    homeRows = homeRows,
                    onOpenSession = handleOpenChat,
                    onNewChat = { showCreateModeMenu = true },
                    onOpenFilter = { showFilterSheet = true },
                    onSelectFilter = viewModel::setFilter,
                    onCreateSessionForCharacter = { characterId ->
                        createCharacterId = characterId.takeIf { it.isNotBlank() }
                        createConfigMode = "character"
                    }
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize().semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(refreshDesc) {
                                viewModel.refresh()
                                true
                            }
                        )
                    }
                ) {
                    // 空态也使用可滚动容器，确保无会话或筛选无结果时仍可下拉刷新。
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = sessionListState,
                        contentPadding = PaddingValues(
                            start = NekoPageHorizontalPadding,
                            end = NekoPageHorizontalPadding,
                            top = 4.dp,
                            // 多选时底栏换成批量操作栏，底部留白按 Scaffold 的 padding 计算即可
                            bottom = if (selectionMode) 16.dp else bottomBarClearance + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // 多选时收起首屏头部，把高度留给会话行
                        if (!selectionMode) {
                            item(key = "home-header", contentType = "home-header") {
                                homeHeader()
                            }
                        }
                        when {
                            loading && sessionRows.isEmpty() && !refreshing -> item(key = "loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            sessionRows.isEmpty() -> item(key = "empty") {
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
                                NekoEmpty(
                                    title = emptyTitle,
                                    hint = emptyHint,
                                    icon = Icons.AutoMirrored.Outlined.Chat
                                )
                            }
                            else -> itemsIndexed(
                                items = sessionRows,
                                key = { _, row -> row.key },
                                contentType = { _, _ -> "session-row" }
                            ) { index, row ->
                                val rowId = row.id
                                val rowSelected = rowId != null && rowId in selectedSessionIds
                                val swipeActions = listOf(
                                    SessionSwipeAction(
                                        icon = Icons.Filled.PushPin,
                                        contentDescription = if (row.pinned) unpinText else pinText,
                                        tint = if (row.pinned) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    ) { rowId?.let { viewModel.togglePinned(it, row.pinned) } },
                                    SessionSwipeAction(
                                        icon = if (row.favorite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
                                        contentDescription = if (row.favorite) unfavoriteText else favoriteText,
                                        tint = if (row.favorite) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    ) { rowId?.let { viewModel.toggleFavorite(it, row.favorite) } },
                                    SessionSwipeAction(
                                        icon = Icons.Filled.Archive,
                                        contentDescription = if (row.archived) unarchiveText else archiveText,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    ) { rowId?.let { viewModel.toggleArchived(it, row.archived) } },
                                    SessionSwipeAction(
                                        icon = Icons.Outlined.Delete,
                                        contentDescription = deleteText,
                                        tint = MaterialTheme.colorScheme.error
                                    ) { if (rowId != null) deleting = row }
                                )
                                SessionSwipeRow(
                                    rowKey = row.key,
                                    openKey = openSwipeRowKey,
                                    onOpenKeyChange = { openSwipeRowKey = it },
                                    shape = sessionItemShape(index == 0, index == sessionRows.lastIndex),
                                    enabled = !selectionMode,
                                    actions = swipeActions
                                ) {
                                    SessionItem(
                                        row = row,
                                        first = index == 0,
                                        last = index == sessionRows.lastIndex,
                                        attention = sessionAttentionStatus(
                                            row.id?.let { attentionBySession[it] }.orEmpty()
                                        ),
                                        isGenerating = row.id != null && row.id in generatingSessions,
                                        // 抽屉展开时点击行只收起抽屉，不进入会话
                                        onClick = {
                                            when {
                                                openSwipeRowKey == row.key -> openSwipeRowKey = null
                                                // 已展开别的行：先收起抽屉，不误触进入会话
                                                openSwipeRowKey != null -> openSwipeRowKey = null
                                                else -> rowId?.let(handleOpenChat)
                                            }
                                        },
                                        // 长按直接进入多选，并勾选当前会话
                                        onLongPress = {
                                            rowId?.let { id ->
                                                openSwipeRowKey = null
                                                selectionMode = true
                                                selectedSessionIds = setOf(id)
                                            }
                                        },
                                        selectionMode = selectionMode,
                                        selected = rowSelected,
                                        onToggleSelection = {
                                            rowId?.let { id ->
                                                selectedSessionIds = if (id in selectedSessionIds) {
                                                    selectedSessionIds - id
                                                } else {
                                                    selectedSessionIds + id
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 错误提示统一走系统 Toast（屏幕下方横条）
                ErrorToast(message = error, onShown = { viewModel.clearError() })
            }
            }
        }
    }
    } // 结束 SessionDashboardPager
    } // 结束 TwoPaneSessionsWrapper

    // 多选模式下返回键先退出多选，不直接返回上一页
    BackHandler(enabled = selectionMode) { exitSelection() }

    if (showBatchDeleteConfirm) {
        NekoDialog(
            onDismiss = { showBatchDeleteConfirm = false },
            title = deleteTitle,
            message = batchDeleteConfirmFmt.format(selectedSessionIds.size),
            confirmText = deleteText,
            onConfirm = {
                showBatchDeleteConfirm = false
                runBatchAction(SessionBatchAction.DELETE, true)
            }
        )
    }

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
                onDismiss = { createConfigMode = null; createCharacterId = null },
                onCreate = { req ->
                    viewModel.createSession(req) {
                        createConfigMode = null
                        createCharacterId = null
                    }
                },
                onLoadCharacters = { viewModel.loadSessionCreationCharacters() }
            )
        } else {
            CreateCharacterSessionDialog(
                state = sessionCreationState,
                isLocalMode = appMode == AppMode.LOCAL,
                userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
                onDismiss = { createConfigMode = null; createCharacterId = null },
                onCreate = { req ->
                    viewModel.createSession(req) {
                        createConfigMode = null
                        createCharacterId = null
                    }
                },
                onLoadCharacters = { viewModel.loadSessionCreationCharacters() },
                initialCharacterId = createCharacterId
            )
        }
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

    // 半屏筛选抽屉（搜索交给顶栏的全局搜索，这里只保留筛选）
    if (showFilterSheet) {
        SessionFilterSheet(
            filter = filter,
            onFilterChange = viewModel::setFilter,
            channelFilterValue = channelFilterValue,
            availableChannels = availableChannels,
            onChannelFilterChange = viewModel::setChannelFilter,
            characters = characters,
            characterFilterId = characterFilterId,
            onCharacterFilterChange = viewModel::setCharacterFilter,
            onDismiss = { showFilterSheet = false }
        )
    }
}

/** 会话类型筛选：与首页分段控件一一对应的三种会话形态 + 最近会话。 */
private val sessionTypeFilters = listOf(
    SessionFilter.ALL,
    SessionFilter.CHARACTER_SESSIONS,
    SessionFilter.AGENT_SESSIONS,
    SessionFilter.AGENT_CHARACTERS
)

/** 其它筛选：收藏 / 置顶 / 归档 / 公开 / 按角色，胶囊互斥单选。 */
private val sessionStateFilters = listOf(
    SessionFilter.FAVORITE,
    SessionFilter.PINNED,
    SessionFilter.UNARCHIVED,
    SessionFilter.ARCHIVED,
    SessionFilter.PUBLIC,
    SessionFilter.BY_CHARACTER
)

/** 每种筛选的图形：会话类型沿用列表里的模式图标。 */
private fun SessionFilter.iconVector(): ImageVector = when (this) {
    SessionFilter.ALL -> Icons.Filled.History
    SessionFilter.CHARACTER_SESSIONS -> SessionModeLabel.CHARACTER.icon()
    SessionFilter.AGENT_SESSIONS -> SessionModeLabel.AGENT.icon()
    SessionFilter.AGENT_CHARACTERS -> SessionModeLabel.COMPANION.icon()
    SessionFilter.UNARCHIVED -> Icons.Filled.Unarchive
    SessionFilter.ARCHIVED -> Icons.Filled.Archive
    SessionFilter.FAVORITE -> Icons.Filled.FavoriteBorder
    SessionFilter.PINNED -> Icons.Filled.PushPin
    SessionFilter.PUBLIC -> Icons.Filled.Public
    SessionFilter.BY_CHARACTER -> Icons.Filled.Person
}

/** 每种筛选的色相：会话类型跟随模式色，其余用强调色。 */
@Composable
private fun SessionFilter.iconTint(): Color = when (this) {
    SessionFilter.ALL -> MaterialTheme.colorScheme.onSurfaceVariant
    SessionFilter.CHARACTER_SESSIONS -> SessionModeLabel.CHARACTER.color()
    SessionFilter.AGENT_SESSIONS -> SessionModeLabel.AGENT.color()
    SessionFilter.AGENT_CHARACTERS -> SessionModeLabel.COMPANION.color()
    else -> MaterialTheme.colorScheme.primary
}

/** 半屏筛选抽屉：会话类型 + 其它筛选 + 频道 + 角色。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionFilterSheet(
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    channelFilterValue: String?,
    availableChannels: List<ChannelOption>,
    onChannelFilterChange: (String?) -> Unit,
    characters: List<CharacterPreset>,
    characterFilterId: String?,
    onCharacterFilterChange: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val filterLabelText = stringResource(R.string.sessions_filter_label)
    val channelText = stringResource(R.string.sessions_channel)
    val characterLabelText = stringResource(R.string.sessions_character_label)
    val allText = stringResource(R.string.sessions_all)
    val allCharactersText = stringResource(R.string.sessions_all_characters)
    val resetText = stringResource(R.string.common_reset)
    val showCharacters = filter == SessionFilter.BY_CHARACTER && characters.isNotEmpty()
    val customized = filter != SessionFilter.ALL ||
        channelFilterValue != null ||
        characterFilterId != null

    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(title = filterLabelText, onClose = onDismiss)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 会话类型：图标 + 选中勾
            NekoGroup {
                sessionTypeFilters.forEachIndexed { index, item ->
                    if (index > 0) NekoDivider(startInset = 61.dp)
                    SessionFilterTypeRow(
                        filter = item,
                        selected = filter == item,
                        onClick = { onFilterChange(item) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // 其它筛选：再点一次回到「最近会话」
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sessionStateFilters.forEach { item ->
                    val selected = filter == item
                    FilterPill(
                        label = stringResource(item.labelResId),
                        icon = item.iconVector(),
                        selected = selected,
                        onClick = {
                            val next = if (selected) SessionFilter.ALL else item
                            onFilterChange(next)
                            if (next != SessionFilter.BY_CHARACTER) onCharacterFilterChange(null)
                        }
                    )
                }
            }
            // 频道筛选
            NekoSectionHeader(title = channelText)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterPill(
                    label = allText,
                    selected = channelFilterValue == null,
                    onClick = { onChannelFilterChange(null) }
                )
                availableChannels.forEach { ch ->
                    FilterPill(
                        label = ch.label,
                        selected = channelFilterValue == ch.value,
                        onClick = { onChannelFilterChange(ch.value) }
                    )
                }
            }
            // 角色筛选（仅「按角色」时显示）
            if (showCharacters) {
                NekoSectionHeader(title = characterLabelText)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterPill(
                        label = allCharactersText,
                        selected = characterFilterId == null,
                        onClick = { onCharacterFilterChange(null) }
                    )
                    characters.forEach { c ->
                        FilterPill(
                            label = c.displayName,
                            selected = c.id == characterFilterId,
                            onClick = { onCharacterFilterChange(c.id) }
                        )
                    }
                }
            }
            if (customized) {
                Spacer(Modifier.height(20.dp))
                NekoSecondaryAction(
                    text = resetText,
                    onClick = {
                        onFilterChange(SessionFilter.ALL)
                        onChannelFilterChange(null)
                        onCharacterFilterChange(null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 会话类型行：图标底座 + 名称 + 单选勾。 */
@Composable
private fun SessionFilterTypeRow(
    filter: SessionFilter,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = filter.iconTint()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .heightIn(min = 54.dp)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NekoIconTile(
            icon = filter.iconVector(),
            tone = NekoTone.Neutral,
            tint = tint,
            background = tint.copy(alpha = 0.10f)
        )
        Spacer(Modifier.width(13.dp))
        Text(
            text = stringResource(filter.labelResId),
            style = nekoRowTitleStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        NekoCheckMark(selected = selected)
    }
}

/** 筛选胶囊：选中时浅强调底 + 强调色描边。 */
@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    val shape = RoundedCornerShape(11.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) nekoAccentSoft() else MaterialTheme.colorScheme.surface)
            .materialBorder(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline,
                shape = shape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = nekoRowSubtitleStyle(),
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
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

/**
 * 多选顶栏：左侧「全选 / 全不选」，中间已选数量，右侧「取消」。
 */
@Composable
private fun SessionSelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onCancel: () -> Unit
) {
    val allSelected = totalCount > 0 && selectedCount >= totalCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 全选 / 全不选是同一个按钮的两个状态
        SelectionTextAction(
            text = stringResource(
                if (allSelected) R.string.sessions_select_none else R.string.sessions_select_all
            ),
            enabled = totalCount > 0,
            onClick = if (allSelected) onClearAll else onSelectAll
        )
        Text(
            text = stringResource(R.string.sessions_selected_count, selectedCount),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SelectionTextAction(
            text = stringResource(R.string.common_cancel),
            enabled = true,
            onClick = onCancel
        )
    }
}

/** 顶栏文字动作：可点区域够大，禁用时降到次要色。 */
@Composable
private fun SelectionTextAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
            maxLines = 1
        )
    }
}

/**
 * 多选底部操作栏：占原底部导航栏的位置，四个批量操作（置顶 / 收藏 / 归档 / 删除）。
 */
@Composable
private fun SessionSelectionActionBar(actions: List<SelectionBarActionSpec>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { spec -> SelectionBarAction(spec) }
        }
    }
}

/** 底部批量操作栏里的一项（文案与启用状态跟随所选会话）。 */
private data class SelectionBarActionSpec(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

/** 批量操作栏里的单个操作：图标在上、文字在下，与底部导航栏同一套节奏。 */
@Composable
private fun RowScope.SelectionBarAction(spec: SelectionBarActionSpec) {
    val tint = when {
        !spec.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        spec.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = spec.enabled, role = Role.Button, onClick = spec.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(spec.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            text = spec.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 等待事项正文压成单行时复用的空白匹配（避免每帧重新构造正则）。 */
private val ATTENTION_WHITESPACE = Regex("\\s+")

/** 把该会话的等待事项压成一行状态；没有等待事项时返回 null。 */
@Composable
private fun sessionAttentionStatus(items: List<AgentAttentionItem>): SessionAttentionStatus? {
    val lead = items.firstOrNull() ?: return null
    val label = when (lead.kind) {
        AgentAttentionKind.ExecAuthorization -> stringResource(R.string.sessions_attention_exec)
        AgentAttentionKind.Question -> stringResource(R.string.sessions_attention_question)
        AgentAttentionKind.PluginInstall -> stringResource(R.string.sessions_attention_plugin)
    }
    // 提问正文可能自带换行与缩进：压成单行后交给行内省略号处理
    val body = lead.text.replace(ATTENTION_WHITESPACE, " ").trim()
    val more = if (items.size > 1) {
        stringResource(R.string.sessions_attention_more, items.size - 1)
    } else {
        null
    }
    return SessionAttentionStatus(
        kind = lead.kind,
        text = buildString {
            append(label)
            if (body.isNotEmpty()) {
                append(" · ")
                append(body)
            }
            more?.let {
                append(' ')
                append(it)
            }
        }
    )
}

/** 单个会话行：头像 + 标题 + 最后一条消息（有待处理事项时换成等待状态） + 时间，长按直接进入多选。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionItem(
    row: SessionListRow,
    first: Boolean,
    last: Boolean,
    /** 该会话当前等待用户处理的事项（命令授权 / 提问 / 插件安装）。 */
    attention: SessionAttentionStatus? = null,
    isGenerating: Boolean = false,
    onClick: () -> Unit,
    /** 长按：直接进入多选，并把当前会话作为第一个选中项。 */
    onLongPress: () -> Unit = {},
    /** 多选模式：点击整行改为勾选/取消勾选。 */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    val textResources = LocalResources.current
    // 提取本地化字符串
    val pinnedBadge = stringResource(R.string.sessions_pinned_badge)
    val favoritedBadge = stringResource(R.string.sessions_favorited_badge)
    val archivedBadge = stringResource(R.string.sessions_archived_badge)
    val noMessageText = stringResource(R.string.sessions_no_message)
    val moreActionsLabel = stringResource(R.string.sessions_multi_select)
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
    // 只给整组首尾加圆角：多行拼成一张分组卡，中间行保持直角与细线分隔
    val itemShape = sessionItemShape(first, last)
    // 置顶行用极浅的强调色底：一眼看出它被固定在顶部，又不需要额外的徽标或彩色描边
    val baseColor = if (row.pinned) {
        lerp(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.primaryContainer,
            0.55f
        )
    } else {
        MaterialTheme.colorScheme.surface
    }
    val modeColor = row.modeLabel.color()
    val containerColor = when {
        // 多选：选中行用更明显的强调色底
        selectionMode && selected ->
            lerp(baseColor, MaterialTheme.colorScheme.primaryContainer, 0.8f)
        else -> baseColor
    }
    // 长按成立时给一次明确的触感反馈（进入多选）
    val haptic = LocalHapticFeedback.current

    // 等待用户处理的状态占用「最后一条消息」的位置：直接说清在等什么，
    // 比只在头像上亮一个小圆点更容易在列表里被发现。
    val subtitleText = attention?.text ?: row.lastMessage ?: noMessageText

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            // 点击进入会话 / 长按直接进入多选：用标准 combinedClickable，
            // 由它自己区分长按与抬手，不会把长按后的抬起再当成一次点击。
            .combinedClickable(
                role = Role.Button,
                onClickLabel = null,
                onLongClickLabel = moreActionsLabel,
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClick = {
                    if (!selectionMode) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                }
            )
            .listItemSemantics(
                stringResource(
                    R.string.sessions_item_description,
                    row.listTitle,
                    listOfNotNull(
                        subtitleText,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 78.dp)
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(modeColor.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            portraitRequest != null -> AsyncImage(
                                model = portraitRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            row.modeLabel == SessionModeLabel.COMPANION -> Text(
                                text = row.listTitle
                                    .takeIf(String::isNotBlank)
                                    ?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }
                                    ?: textResources.getString(R.string.single_role_c),
                                style = nekoRowTitleStyle(),
                                fontWeight = FontWeight.Medium,
                                color = modeColor
                            )
                            else -> {
                                val fallbackPainter =
                                    if (row.isAgentSession && !row.inheritCharacter) painterResource(R.drawable.ic_agent_neko)
                                    else rememberVectorPainter(
                                        if (row.isGroupSession) Icons.Outlined.Group
                                        else Icons.AutoMirrored.Outlined.Chat
                                    )
                                Icon(fallbackPainter, null, Modifier.size(23.dp), tint = modeColor)
                            }
                        }
                    }
                    // 等待处理 / 正在生成：头像角标，不做整行高亮
                    if (attention != null || isGenerating) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // 第一行：置顶针 + 标题 + 状态图标 + 时间（时间贴在该区域右上）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.pinned) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = pinnedBadge,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = row.listTitle,
                            style = nekoRowTitleStyle(),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (row.favorite) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = favoritedBadge,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (row.archived) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Archive,
                                contentDescription = archivedBadge,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        row.listDate?.let { date ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = date,
                                style = nekoCaptionStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        // 多选：行尾勾选圈，选中填色打勾
                        if (selectionMode) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                                    )
                                    .materialBorder(
                                        1.5.dp,
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    // 第二行：模式图标 + 最后一条消息；有待处理事项时改为状态图标 + 等待内容
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.isTemporary) {
                            Text(
                                text = stringResource(R.string.temporary_session_badge),
                                style = nekoCaptionStyle(),
                                color = modeColor,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (attention != null) {
                            Icon(
                                imageVector = when (attention.kind) {
                                    AgentAttentionKind.ExecAuthorization -> Icons.Outlined.Terminal
                                    AgentAttentionKind.Question -> Icons.AutoMirrored.Outlined.Help
                                    AgentAttentionKind.PluginInstall -> Icons.Outlined.Extension
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                        } else {
                            Icon(
                                row.modeLabel.icon(),
                                contentDescription = row.modeLabel.title(),
                                tint = modeColor,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = subtitleText,
                            style = nekoRowSubtitleStyle(),
                            color = if (attention != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (attention != null) FontWeight.Medium else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (!last) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 70.dp, end = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp
                )
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

    /**
     * 会话列表滚动状态。
     *
     * 挂在 ViewModel 上而不是 Composable 的 rememberSaveable：rememberSaveable 只保存
     * index/offset，从聊天返回时按重新排序后的同一 index 定位会错位；LazyListState 自身
     * 按 item key 跟踪首个可见项，跨页面存活才能在列表重排后依然停在进入会话前的位置。
     */
    val listScrollState = LazyListState()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
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
                when {
                    f in setOf(SessionFilter.CHARACTER_SESSIONS, SessionFilter.AGENT_SESSIONS, SessionFilter.AGENT_CHARACTERS) -> {
                        sessions.filter { it.archived != true }
                    }
                    f == SessionFilter.ALL && !includeArchived -> {
                        sessions.filter { it.archived != true }
                    }
                    else -> sessions
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

    /**
     * 首页 banner 用的会话行：不受筛选/搜索影响，按更新时间倒序，且排除归档。
     *
     * 与 [displayedSessionRows] 分开，避免用户在当前筛选下看不到 banner 该显示的那条会话。
     */
    val homeBannerRows: StateFlow<List<SessionListRow>> = combine(
        _sessions,
        _characters
    ) { sessions, characters ->
        buildSessionListRows(
            sessions = sessions
                .filter { it.isArchive != true && it.archived != true }
                .sortedByDescending { it.updatedAt ?: it.createdAt.orEmpty() },
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

    /**
     * 顶部四个筛选按钮的切换：再次点按已选中的筛选恢复到默认「最近会话」（ALL）。
     * 同时清空仅对 BY_CHARACTER 有意义的角色筛选，避免残留状态。
     */
    fun toggleFilter(f: SessionFilter) {
        if (_filter.value == f) {
            _filter.value = SessionFilter.ALL
            _characterFilterId.value = null
        } else {
            _filter.value = f
        }
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
        loadSessionCreationCharacters(version)
    }

    /** 下拉刷新独立管理进度，保留列表并等待全部请求完成，避免重复触发。 */
    fun refresh() {
        if (!_refreshing.compareAndSet(expect = false, update = true)) return
        val version = dataSourceVersion
        viewModelScope.launch {
            try {
                val (sessionsResult, charactersResult, creationCharactersResult) = coroutineScope {
                    val sessionsRequest = async { unified.listSessions() }
                    val charactersRequest = async { unified.listCharacters() }
                    val creationCharactersRequest = async { unified.listCharactersForSessionCreation() }
                    Triple(sessionsRequest.await(), charactersRequest.await(), creationCharactersRequest.await())
                }
                if (version != dataSourceVersion) return@launch
                when (sessionsResult) {
                    is Resource.Success -> _sessions.value = sessionsResult.data ?: emptyList()
                    is Resource.Error -> showError(sessionsResult.message)
                    is Resource.Loading -> {}
                }
                when (charactersResult) {
                    is Resource.Success -> _characters.value = charactersResult.data ?: emptyList()
                    is Resource.Error -> showError(charactersResult.message)
                    is Resource.Loading -> {}
                }
                when (creationCharactersResult) {
                    is Resource.Success -> _sessionCreationState.value = _sessionCreationState.value.copy(
                        characters = creationCharactersResult.data ?: emptyList(),
                        error = null
                    )
                    is Resource.Error -> _sessionCreationState.value = _sessionCreationState.value.copy(
                        error = creationCharactersResult.message
                    )
                    is Resource.Loading -> {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == dataSourceVersion) {
                    showError(e.message ?: string(R.string.common_unknown_error))
                }
            } finally {
                _refreshing.value = false
            }
        }
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
        listScrollState.requestScrollToItem(0)
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

    /** 新建临时 Agent 会话：不保存记录、不使用全局记忆，成功后回调。 */
    fun createTemporarySession(name: String, onSuccess: (Session) -> Unit = {}) {
        if (_sessionCreationState.value.creating) return
        val version = dataSourceVersion
        _sessionCreationState.value = _sessionCreationState.value.copy(creating = true, error = null)
        launchResult(
            block = {
                try {
                    unified.createTemporarySession(name)
                } finally {
                    if (version == dataSourceVersion) {
                        _sessionCreationState.value = _sessionCreationState.value.copy(creating = false)
                    }
                }
            },
            onSuccess = { session ->
                if (version == dataSourceVersion) {
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

    /**
     * 批量操作：置顶 / 收藏 / 归档 / 删除。
     *
     * [enable] 为目标状态：所选会话已全部置顶时传 false 即批量取消置顶，
     * 收藏与归档同理。逐个会话提交，单个失败不影响其余会话。
     */
    fun batchUpdateSessions(
        action: SessionBatchAction,
        sessionIds: Collection<String>,
        enable: Boolean = true
    ) {
        val targets = sessionIds.filter { it.isNotBlank() }
        if (targets.isEmpty()) return
        val version = dataSourceVersion
        viewModelScope.launch {
            setLoading(true)
            var success = 0
            var failed = 0
            try {
                targets.forEach { id ->
                    val ok = try {
                        when (action) {
                            SessionBatchAction.PIN ->
                                unified.updateSession(id, UpdateSessionRequest(pinned = enable)) is Resource.Success

                            SessionBatchAction.FAVORITE ->
                                unified.updateSession(id, UpdateSessionRequest(favorite = enable)) is Resource.Success

                            SessionBatchAction.ARCHIVE ->
                                if (enable) {
                                    unified.archiveSession(id) is Resource.Success
                                } else {
                                    unified.restoreSession(id) is Resource.Success
                                }

                            SessionBatchAction.DELETE -> {
                                // 与单条删除一致：成功后才撤回待发的延迟回复
                                ChatSessionManager.pauseDelayedReplyTimer(id)
                                val result = unified.deleteSession(id)
                                if (result is Resource.Success) {
                                    ChatSessionManager.cancelDelayedReply(id, removeBubbles = true)
                                }
                                result is Resource.Success
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) success++ else failed++
                }
            } finally {
                setLoading(false)
            }
            if (version != dataSourceVersion) return@launch
            loadSessions()
            when {
                success == 0 -> showError(string(R.string.sessions_batch_failed, failed))
                failed == 0 -> showToast(string(batchSuccessToastRes(action, enable), success))
                else -> showToast(string(R.string.sessions_batch_partial, success, failed))
            }
        }
    }

    private fun batchSuccessToastRes(action: SessionBatchAction, enable: Boolean): Int = when (action) {
        SessionBatchAction.PIN -> if (enable) {
            R.string.sessions_batch_pinned_toast
        } else {
            R.string.sessions_batch_unpinned_toast
        }

        SessionBatchAction.FAVORITE -> if (enable) {
            R.string.sessions_batch_favorited_toast
        } else {
            R.string.sessions_batch_unfavorited_toast
        }

        SessionBatchAction.ARCHIVE -> if (enable) {
            R.string.sessions_batch_archived_toast
        } else {
            R.string.sessions_batch_unarchived_toast
        }

        SessionBatchAction.DELETE -> R.string.sessions_batch_deleted_toast
    }

    /** 根据频道过滤会话（先于其他筛选）。channelValue 为 null 时返回全部。 */    private fun applyChannelFilter(all: List<Session>, channelValue: String?): List<Session> {
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
            SessionFilter.CHARACTER_SESSIONS -> all.filter(Session::isCharacterSession)
            SessionFilter.AGENT_SESSIONS -> all.filter { it.isAgentSession() && !it.isAgentCharacterSession() }
            SessionFilter.AGENT_CHARACTERS -> all.filter(Session::isAgentCharacterSession)
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
