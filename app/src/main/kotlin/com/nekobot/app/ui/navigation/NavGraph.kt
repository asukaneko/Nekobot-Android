package com.nekobot.app.ui.navigation

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.screens.aiconfig.AiConfigCenterScreen
import com.nekobot.app.ui.screens.aiconfig.AiConfigScreen
import com.nekobot.app.ui.screens.aiconfig.AiModelsScreen
import com.nekobot.app.ui.screens.aiconfig.FailoverQueueScreen
import com.nekobot.app.ui.screens.aiconfig.LocalAiModelsScreen
import com.nekobot.app.ui.screens.aiconfig.ModelProxyScreen
import com.nekobot.app.ui.screens.aiconfig.OAuthAccountsScreen
import com.nekobot.app.ui.screens.characters.CharacterDetailScreen
import com.nekobot.app.ui.screens.characters.CharacterViewScreen
import com.nekobot.app.ui.screens.characters.CharactersScreen
import com.nekobot.app.ui.screens.chat.ModernChatScreen
import com.nekobot.app.ui.screens.chat.WorkspaceScreen
import com.nekobot.app.ui.screens.login.LoginScreen
import com.nekobot.app.ui.screens.onboarding.QuickSetupScreen
import com.nekobot.app.ui.screens.memory.MemoryScreen
import com.nekobot.app.ui.screens.more.MoreScreen
import com.nekobot.app.ui.screens.search.GlobalSearchScreen
import com.nekobot.app.ui.screens.sessions.SessionsScreen
import com.nekobot.app.ui.screens.sessions.SessionDetailScreen
import com.nekobot.app.ui.screens.plot.StoryGraphScreen
import com.nekobot.app.ui.screens.settings.ConfigTransferScreen
import com.nekobot.app.ui.screens.settings.DataMaintenanceScreen
import com.nekobot.app.ui.screens.settings.DataPortabilityScreen
import com.nekobot.app.ui.screens.settings.DbProfileScreen
import com.nekobot.app.ui.screens.settings.DiagnosticCenterScreen
import com.nekobot.app.ui.screens.settings.FeatureSwitchesScreen
import com.nekobot.app.ui.screens.settings.AbTestSettingsScreen
import com.nekobot.app.ui.screens.settings.RagSettingsScreen
import com.nekobot.app.ui.screens.settings.SettingsScreen
import com.nekobot.app.ui.screens.settings.StyleSettingsScreen
import com.nekobot.app.ui.screens.settings.SystemSettingsScreen
import com.nekobot.app.ui.screens.settings.SystemOperationsScreen
import com.nekobot.app.ui.screens.settings.GlobalAgentMemoryScreen
import com.nekobot.app.ui.screens.settings.WebDavBackupScreen
import com.nekobot.app.ui.screens.settings.AboutScreen
import com.nekobot.app.ui.screens.settings.DownloadUiState
import com.nekobot.app.ui.screens.settings.DeveloperOptionsScreen
import com.nekobot.app.ui.screens.settings.PerformanceMonitorScreen
import com.nekobot.app.ui.screens.settings.LicenseScreen
import com.nekobot.app.ui.screens.settings.PrivacyScreen
import com.nekobot.app.ui.screens.settings.Wenku8LoginScreen
import com.nekobot.app.ui.screens.settings.UpdateDetailDialog
import com.nekobot.app.ui.screens.statehistory.StateHistoryScreen
import com.nekobot.app.ui.screens.tokens.TokensScreen
import com.nekobot.app.ui.screens.tokens.RoutingHistoryScreen
import com.nekobot.app.ui.screens.worldbook.WorldBookDetailScreen
import com.nekobot.app.ui.screens.worldbook.WorldBooksScreen
import com.nekobot.app.ui.screens.extensions.ExtensionsScreen
import com.nekobot.app.ui.screens.extensions.HooksScreen
import com.nekobot.app.ui.screens.extensions.TaskCenterScreen
import com.nekobot.app.ui.screens.extensions.WorkflowsScreen
import com.nekobot.app.ui.screens.extensions.KnowledgeScreen
import com.nekobot.app.ui.screens.extensions.SkillsScreen
import com.nekobot.app.ui.screens.extensions.SkillStorageScreen
import com.nekobot.app.ui.screens.extensions.ToolsScreen
import com.nekobot.app.ui.screens.extensions.McpServersScreen
import com.nekobot.app.ui.screens.extensions.ChannelsScreen
import com.nekobot.app.ui.screens.extensions.MessageFilterScreen
import com.nekobot.app.ui.screens.extensions.TtsPlaygroundScreen
import com.nekobot.app.ui.screens.extensions.ImageGenerationPlaygroundScreen
import com.nekobot.app.ui.screens.extensions.LoginTokensScreen
import com.nekobot.app.ui.screens.extensions.ApiKeysScreen
import com.nekobot.app.ui.screens.extensions.AchievementsScreen
import com.nekobot.app.ui.components.AchievementUnlockHost
import com.nekobot.app.update.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.absoluteValue

private val mainRoutes = setOf(
    Routes.SESSIONS, Routes.CHARACTERS, Routes.WORLD_BOOKS,
    Routes.TOKENS, Routes.MORE
)

// 底栏 Tab 的显示顺序，用于判断横向滑动方向。
private val tabOrder = bottomRoutes

// 入场淡入刻意比滑动更长，滑动到位后透明度还留有一段"余韵"，
// 这段缓慢收尾正是阻尼感的来源。离场淡出更短，让旧页面快速让位。
private const val TAB_FADE_IN_MS = 260
private const val TAB_FADE_DELAY_MS = 20
private const val DETAIL_ANIM_MS = 200
private const val DETAIL_FADE_IN_MS = 300
private const val DETAIL_FADE_OUT_MS = 160
private const val DETAIL_FADE_DELAY_MS = 50

// 点击底栏切换 Tab：先拉起一层与背景同色的"纱幕"盖住旧页，
// 在纱幕完全遮住的瞬间无动画瞬切页面，再让纱幕带阻尼长尾褪去。
// 纱幕是纯色层（graphicsLayer 延迟读取 alpha），动画开销几乎为零；
// 瞬切引发的多页组合/布局开销被藏在纱幕之后——既不卡顿也不闪屏。
// 手势左右滑动切换不经过纱幕，仍走 Pager 的横向滚动。
private const val TAB_CLICK_VEIL_ON_MS = 110
private const val TAB_CLICK_VEIL_OFF_MS = 260

// 阻尼缓动曲线（easeOutQuint 风格）：起步迅猛、末端长尾缓慢趋停，
// 页面像被阻尼滑轨"吸入"到位，而非匀速生硬地切换。
private val DampedEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 返回主 Tab 间切换的滑动方向：
 * +1 表示目标在右侧（内容向左推入），-1 表示目标在左侧，0 表示不是主 Tab 间切换（走详情页转场）。
 */
private fun tabDirection(from: String?, to: String?): Int {
    val fromIndex = tabOrder.indexOf(from)
    val toIndex = tabOrder.indexOf(to)
    if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return 0
    return if (toIndex > fromIndex) 1 else -1
}

@Composable
fun NekobotNavGraph() {
    val navController = rememberNavController()
    var isQuickSetupCompleted by remember {
        mutableStateOf(ServiceContainer.prefs.quickSetupCompleted)
    }
    val mainPagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { bottomRoutes.size }
    )
    val mainPagerScope = rememberCoroutineScope()
    // 点击底栏切换 Tab 的纱幕透明度（0 = 无纱幕）；手势左右滑动切换不经过它。
    val tabSwitchVeil = remember { Animatable(0f) }
    var tabClickJob by remember { mutableStateOf<Job?>(null) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in mainRoutes
    val selectedMainRoute = bottomRoutes.getOrElse(mainPagerState.currentPage) {
        Routes.SESSIONS
    }

    // 观察全局登录态：登出时自动跳登录页，登录时跳会话页
    val isLoggedIn by ServiceContainer.loginStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(isLoggedIn, isQuickSetupCompleted) {
        if (!isQuickSetupCompleted) return@LaunchedEffect
        if (!isLoggedIn && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        } else if (isLoggedIn && currentRoute == Routes.LOGIN) {
            mainPagerState.scrollToPage(0)
            navController.navigate(Routes.SESSIONS) {
                popUpTo(Routes.LOGIN) { inclusive = true }
            }
        }
    }

    // 观察通知点击的待跳转会话 ID
    val pendingSessionId by ServiceContainer.pendingSessionId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSessionId) {
        val sid = pendingSessionId ?: return@LaunchedEffect
        ServiceContainer.setPendingSessionId(null) // 消费掉
        if (isQuickSetupCompleted && isLoggedIn) {
            navController.navigate(Routes.chat(sid))
        }
    }

    // 系统分享先回到会话页，内容在用户进入目标聊天后才会被消费。
    val pendingShare by ServiceContainer.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare?.id, isLoggedIn) {
        if (pendingShare == null || !isQuickSetupCompleted || !isLoggedIn) return@LaunchedEffect
        mainPagerState.scrollToPage(0)
        if (currentRoute != Routes.SESSIONS) {
            navController.navigate(Routes.SESSIONS) {
                launchSingleTop = true
            }
        }
    }

    var shouldOpenLatestSession by remember {
        mutableStateOf(ServiceContainer.prefs.openLatestSessionOnLaunch)
    }
    LaunchedEffect(isQuickSetupCompleted, isLoggedIn, pendingSessionId, pendingShare?.id) {
        if (!shouldOpenLatestSession || !isQuickSetupCompleted || !isLoggedIn) return@LaunchedEffect
        if (pendingSessionId != null || pendingShare != null) {
            shouldOpenLatestSession = false
            return@LaunchedEffect
        }
        shouldOpenLatestSession = false
        val sessionId = runCatching {
            when (val result = ServiceContainer.unified.listSessions()) {
                is Resource.Success -> result.data
                    .maxByOrNull { it.updatedAt ?: it.createdAt.orEmpty() }
                    ?.id
                else -> null
            }
        }.getOrNull()
        sessionId?.let { id ->
            navController.navigate(Routes.chat(id)) {
                launchSingleTop = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = when {
                !isQuickSetupCompleted -> Routes.QUICK_SETUP
                isLoggedIn -> Routes.SESSIONS
                else -> Routes.LOGIN
            },
            modifier = Modifier.fillMaxSize(),
            // 主 Tab 之间（点击底栏触发）：纯阻尼淡入淡出，不再左右位移；
            // 左右滑动切换保留在 Pager 手势上。进入详情页：从右侧滑入形成层级纵深感。
            // 所有淡入淡出统一走阻尼曲线：离场页面快速褪去，
            // 入场页面带长尾缓缓"显影"落实，交叉之间产生柔和的阻尼手感。
            enterTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val dir = tabDirection(from, to)
                when {
                    // Tab 间切换：纯阻尼淡入，无横向位移
                    dir != 0 -> fadeIn(
                        tween(TAB_FADE_IN_MS, delayMillis = TAB_FADE_DELAY_MS, easing = DampedEasing)
                    )
                    // 回到顶层 Tab（从详情页点底栏、登录完成等）：同样纯淡入
                    to in mainRoutes -> fadeIn(
                        tween(DETAIL_FADE_IN_MS, delayMillis = DETAIL_FADE_DELAY_MS, easing = DampedEasing)
                    )
                    // 进入详情页：从右侧滑入 + 淡入，保留层级纵深感
                    else -> slideInHorizontally(tween(DETAIL_ANIM_MS, easing = DampedEasing)) { w -> w } +
                        fadeIn(
                            tween(
                                DETAIL_FADE_IN_MS,
                                delayMillis = DETAIL_FADE_DELAY_MS,
                                easing = DampedEasing
                            )
                        )
                }
            },
            exitTransition = {
                // 离场统一纯淡出：Tab 切换与详情页被覆盖时均不做横向位移
                fadeOut(tween(DETAIL_FADE_OUT_MS, easing = DampedEasing))
            },
            popEnterTransition = {
                slideInHorizontally(tween(DETAIL_ANIM_MS, easing = DampedEasing)) { w -> -w / 4 } +
                    fadeIn(tween(DETAIL_FADE_IN_MS, easing = DampedEasing))
            },
            popExitTransition = {
                slideOutHorizontally(tween(DETAIL_ANIM_MS, easing = DampedEasing)) { w -> w } +
                    fadeOut(tween(DETAIL_FADE_OUT_MS, easing = DampedEasing))
            }
        ) {
            composable(Routes.QUICK_SETUP) {
                QuickSetupScreen { mode ->
                    ServiceContainer.switchAppMode(mode)
                    ServiceContainer.prefs.quickSetupCompleted = true
                    isQuickSetupCompleted = true
                    val destination = if (mode == AppMode.LOCAL) {
                        Routes.SESSIONS
                    } else {
                        Routes.LOGIN
                    }
                    navController.navigate(destination) {
                        popUpTo(Routes.QUICK_SETUP) { inclusive = true }
                    }
                }
            }
            composable(Routes.LOGIN) {
                LoginScreen(onLoggedIn = {
                    // 广播登录成功，LaunchedEffect 会自动导航到 SESSIONS
                    ServiceContainer.notifyLoginState(true)
                })
            }
            composable(Routes.SESSIONS) {
                // 纱幕颜色与全局背景一致，盖住页面时视觉上等同于"淡入背景色"
                val veilColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = mainPagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { page -> bottomRoutes[page] },
                    beyondViewportPageCount = 1
                ) { page ->
                    // 手势左右滑动时：页面随偏离中心的程度做淡入淡出。
                    // 淡化幅度刻意收敛（图片密集页面过强的透明叠加会引发闪烁感），
                    // 滑出侧轻微褪去、滑入侧缓缓显影，形成柔和的阻尼层次感。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = (
                                    (mainPagerState.currentPage - page) +
                                        mainPagerState.currentPageOffsetFraction
                                    ).absoluteValue
                                val progress = pageOffset.coerceIn(0f, 1f)
                                alpha = 1f - 0.28f * progress
                            }
                    ) {
                            when (bottomRoutes[page]) {
                                Routes.SESSIONS -> SessionsScreen(
                                    onOpenChat = { id ->
                                        navController.navigate(Routes.chat(id))
                                    },
                                    onOpenDetail = { id ->
                                        navController.navigate(Routes.sessionDetail(id))
                                    },
                                    onOpenStoryGraph = { id ->
                                        navController.navigate(Routes.storyGraph(id))
                                    },
                                    onNavigate = { route ->
                                        navController.navigate(route)
                                    }
                                )

                                Routes.CHARACTERS -> CharactersScreen(
                                    onOpenCharacter = { id ->
                                        if (id == "new") navController.navigate(Routes.characterDetail(id))
                                        else navController.navigate(Routes.characterView(id))
                                    },
                                    onOpenEdit = { id ->
                                        navController.navigate(Routes.characterDetail(id))
                                    }
                                )

                                Routes.WORLD_BOOKS -> WorldBooksScreen(onOpenBook = { id ->
                                    navController.navigate(Routes.worldBookDetail(id))
                                })

                                Routes.TOKENS -> TokensScreen(
                                    onNavigate = { route -> navController.navigate(route) }
                                )

                                Routes.MORE -> MoreScreen(
                                    onNavigate = { route -> navController.navigate(route) },
                                    onLogout = {
                                        ServiceContainer.socket.disconnect()
                                        ServiceContainer.repository.logoutLocal()
                                        ServiceContainer.notifyLoginState(false)
                                    }
                                )
                            }
                    }
                }

                    // 纱幕：点击底栏切换时拉起盖住页面（见 onItemSelected），
                    // 纯色层 + graphicsLayer 延迟读取 alpha，动画开销几乎为零；
                    // 不带 clickable/pointerInput，不拦截任何触摸事件。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = tabSwitchVeil.value
                            }
                            .background(veilColor)
                    )
                }
            }
            composable(
                route = Routes.SESSION_DETAIL,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                SessionDetailScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                ModernChatScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                    onOpenSessionDetail = { id -> navController.navigate(Routes.sessionDetail(id)) },
                    onOpenWorkspace = { id -> navController.navigate(Routes.workspace(id)) },
                    onOpenStoryGraph = { id -> navController.navigate(Routes.storyGraph(id)) },
                    onOpenWenku8Login = { navController.navigate(Routes.WENKU_LOGIN) },
                    onJumpToLatest = {
                        val route = Routes.chat(sessionId)
                        navController.navigate(route) {
                            popUpTo(route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Routes.CHARACTERS) {
                CharactersScreen(
                    onOpenCharacter = { id ->
                        // 列表点击 → 只读详情视图；新建仍走编辑页
                        if (id == "new") navController.navigate(Routes.characterDetail(id))
                        else navController.navigate(Routes.characterView(id))
                    },
                    onOpenEdit = { id ->
                        navController.navigate(Routes.characterDetail(id))
                    }
                )
            }
            composable(
                route = Routes.CHARACTER_VIEW,
                arguments = listOf(navArgument("characterId") { type = NavType.StringType })
            ) { entry ->
                CharacterViewScreen(
                    characterId = entry.arguments?.getString("characterId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Routes.characterDetail(id)) },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
                )
            }
            composable(
                route = Routes.CHARACTER_DETAIL,
                arguments = listOf(navArgument("characterId") { type = NavType.StringType })
            ) { entry ->
                CharacterDetailScreen(
                    characterId = entry.arguments?.getString("characterId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.WORLD_BOOKS) {
                WorldBooksScreen(onOpenBook = { id ->
                    navController.navigate(Routes.worldBookDetail(id))
                })
            }
            composable(
                route = Routes.WORLD_BOOK_DETAIL,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { entry ->
                WorldBookDetailScreen(
                    bookId = entry.arguments?.getString("bookId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.TOKENS) {
                TokensScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onLogout = {
                        // 清除本地 token 并断开 socket，广播登录态变化（LaunchedEffect 会自动导航）
                        ServiceContainer.socket.disconnect()
                        ServiceContainer.repository.logoutLocal()
                        ServiceContainer.notifyLoginState(false)
                    }
                )
            }
            composable(Routes.GLOBAL_SEARCH) {
                GlobalSearchScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onLogout = {
                        ServiceContainer.socket.disconnect()
                        ServiceContainer.repository.logoutLocal()
                        ServiceContainer.notifyLoginState(false)
                    },
                    onNavigate = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.STATE_HISTORY) {
                StateHistoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SYSTEM_SETTINGS) {
                SystemSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SYSTEM_OPERATIONS) {
                SystemOperationsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AGENT_MEMORY) {
                GlobalAgentMemoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AI_CONFIG_CENTER) {
                AiConfigCenterScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.AI_CONFIG) {
                AiConfigScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AI_MODELS) {
                AiModelsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AI_FAILOVER) {
                FailoverQueueScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOCAL_AI_MODELS) {
                LocalAiModelsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MODEL_PROXY) {
                ModelProxyScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.OAUTH_ACCOUNTS) {
                OAuthAccountsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MEMORY) {
                MemoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STYLE_SETTINGS) {
                StyleSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.WORKSPACE,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                WorkspaceScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Routes.STORY_GRAPH,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                StoryGraphScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.FEATURE_SWITCHES) {
                FeatureSwitchesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DATA_MAINTENANCE) {
                DataMaintenanceScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DATA_PORTABILITY) {
                DataPortabilityScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.CONFIG_TRANSFER) {
                ConfigTransferScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WEBDAV_BACKUP) {
                WebDavBackupScreen(onBack = { navController.popBackStack() })
            }
            // ==================== 关于 / 许可证 / 隐私 ====================
            composable(Routes.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) },
                    onOpenLicense = { navController.navigate(Routes.LICENSE) },
                    onOpenPrivacy = { navController.navigate(Routes.PRIVACY) }
                )
            }
            composable(Routes.DEVELOPER_OPTIONS) {
                DeveloperOptionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPerformanceMonitor = {
                        navController.navigate(Routes.PERFORMANCE_MONITOR)
                    }
                )
            }
            composable(Routes.PERFORMANCE_MONITOR) {
                PerformanceMonitorScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WENKU_LOGIN) {
                Wenku8LoginScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LICENSE) {
                LicenseScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PRIVACY) {
                PrivacyScreen(onBack = { navController.popBackStack() })
            }
            // ==================== 扩展功能（仅远程模式）====================
            composable(Routes.EXTENSIONS) {
                ExtensionsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.ACHIEVEMENTS) {
                AchievementsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.HOOKS) {
                HooksScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TASK_CENTER) {
                TaskCenterScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WORKFLOWS) {
                WorkflowsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.KNOWLEDGE) {
                KnowledgeScreen(
                    onBack = { navController.popBackStack() },
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.RAG_SETTINGS) {
                RagSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SKILLS) {
                SkillsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenStorage = { skill ->
                        skill.name.takeIf { it.isNotBlank() }?.let { name ->
                            navController.navigate(Routes.skillDetail(name))
                        }
                    }
                )
            }
            composable(
                route = Routes.SKILL_DETAIL,
                arguments = listOf(navArgument("skillId") { type = NavType.StringType })
            ) { entry ->
                val skillId = entry.arguments?.getString("skillId").orEmpty()
                SkillStorageScreen(
                    skillName = java.net.URLDecoder.decode(skillId, "UTF-8"),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.TOOLS) {
                ToolsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MCP_SERVERS) {
                McpServersScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.CHANNELS) {
                ChannelsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MESSAGE_FILTER) {
                MessageFilterScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TTS_PLAYGROUND) {
                TtsPlaygroundScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.IMAGE_GENERATION_PLAYGROUND) {
                ImageGenerationPlaygroundScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOGIN_TOKENS) {
                LoginTokensScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.API_KEYS) {
                ApiKeysScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DB_PROFILE) {
                DbProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DIAGNOSTIC_CENTER) {
                DiagnosticCenterScreen(onBack = { navController.popBackStack() })
            }
            // 路由决策历史
            composable(Routes.ROUTING_HISTORY) {
                RoutingHistoryScreen(onBack = { navController.popBackStack() })
            }
            // A/B 测试配置
            composable(Routes.AB_TEST_SETTINGS) {
                AbTestSettingsScreen(onBack = { navController.popBackStack() })
            }
        }

        // 作为覆盖层悬浮在页面上方，不再为底栏预留整块背景；
        // 胶囊外的透明区域没有手势处理，点击和滚动会继续交给下方内容。
        if (showBottomBar) {
            // 底栏下方的渐变遮罩：从透明渐变到背景色，让接近底栏的列表内容
            // 自然"淡入"背景，模拟毛玻璃的朦胧感（Telegram / iOS 常用技巧）。
            // 不消耗手势事件，点击与滚动穿透到下方内容。
            val bgColor = androidx.compose.material3.MaterialTheme.colorScheme.background
            Spacer(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to bgColor.copy(alpha = 0.45f),
                            1f to bgColor.copy(alpha = 0.85f)
                        )
                    )
            )
            LiquidGlassBottomBar(
                items = bottomItems(),
                selectedRoute = if (currentRoute == Routes.SESSIONS) {
                    selectedMainRoute
                } else {
                    currentRoute
                },
                onItemSelected = { item ->
                    val targetPage = bottomRoutes.indexOf(item.route)
                    if (targetPage == -1) return@LiquidGlassBottomBar

                    if (currentRoute == Routes.SESSIONS) {
                        if (targetPage != mainPagerState.currentPage) {
                            // 点击切换：拉起纱幕盖住旧页 → 纱幕全遮时无动画瞬切 →
                            // 纱幕带阻尼长尾褪去显出新页。瞬切引发的多页组合开销
                            // 藏在纱幕之后，不卡顿、不闪屏；
                            // 手势左右滑动仍走横向滚动，两种切换方式互不干扰。
                            tabClickJob?.cancel()
                            tabClickJob = mainPagerScope.launch {
                                tabSwitchVeil.animateTo(
                                    1f,
                                    tween(TAB_CLICK_VEIL_ON_MS, easing = DampedEasing)
                                )
                                mainPagerState.scrollToPage(targetPage)
                                tabSwitchVeil.animateTo(
                                    0f,
                                    tween(TAB_CLICK_VEIL_OFF_MS, easing = DampedEasing)
                                )
                            }
                        }
                    } else {
                        mainPagerScope.launch {
                            mainPagerState.scrollToPage(targetPage)
                        }
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(Routes.SESSIONS) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        AchievementUnlockHost()
        StartupUpdateHost()
    }
}

/** 应用启动后静默检查更新，仅在发现未忽略的新版本时显示提示。 */
@Composable
private fun StartupUpdateHost() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentVersion = remember(context) { getCurrentAppVersion(context) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    val downloadStateFlow = remember { MutableStateFlow<DownloadUiState>(DownloadUiState.Idle) }
    val downloadState by downloadStateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(currentVersion) {
        UpdateChecker.consumeStartupUpdatePreview(context)?.let { preview ->
            updateInfo = preview
            return@LaunchedEffect
        }
        when (val result = UpdateChecker.checkForUpdate(context, currentVersion)) {
            is UpdateChecker.CheckResult.Available -> {
                if (!UpdateChecker.isVersionIgnored(context, result.info.tagName)) {
                    updateInfo = result.info
                }
            }
            else -> Unit
        }
    }

    val info = updateInfo ?: return
    UpdateDetailDialog(
        info = info,
        currentVersion = currentVersion,
        downloadState = downloadState,
        onDismiss = {
            updateInfo = null
            downloadStateFlow.value = DownloadUiState.Idle
        },
        onDownload = { asset ->
            if (downloadState is DownloadUiState.Downloading) return@UpdateDetailDialog
            downloadStateFlow.value = DownloadUiState.Downloading(0)
            coroutineScope.launch {
                when (
                    val result = UpdateChecker.downloadApk(context, asset) { progress ->
                        downloadStateFlow.value = when (progress) {
                            is UpdateChecker.DownloadResult.Progress -> {
                                DownloadUiState.Downloading(progress.percent)
                            }
                            is UpdateChecker.DownloadResult.Done -> DownloadUiState.Done(progress.file)
                            is UpdateChecker.DownloadResult.Error -> DownloadUiState.Idle
                        }
                    }
                ) {
                    is UpdateChecker.DownloadResult.Done -> {
                        runCatching {
                            context.startActivity(UpdateChecker.buildInstallIntent(context, result.file))
                        }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(com.nekobot.app.R.string.update_install_failed),
                                Toast.LENGTH_LONG
                            ).show()
                            runCatching { context.startActivity(UpdateChecker.buildReleasesPageIntent()) }
                        }
                    }
                    is UpdateChecker.DownloadResult.Error -> {
                        downloadStateFlow.value = DownloadUiState.Idle
                        Toast.makeText(
                            context,
                            context.getString(com.nekobot.app.R.string.update_download_failed, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> Unit
                }
            }
        },
        onOpenInBrowser = {
            runCatching { context.startActivity(UpdateChecker.buildReleasesPageIntent()) }
        },
        showIgnoreOption = true,
        onIgnoreVersion = { tagName ->
            if (UpdateChecker.isStartupUpdatePreview(tagName)) {
                UpdateChecker.ignoreStartupUpdatePreview(context)
            } else {
                UpdateChecker.ignoreVersion(context, tagName)
            }
        }
    )
}

private fun getCurrentAppVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}.getOrDefault("unknown")
