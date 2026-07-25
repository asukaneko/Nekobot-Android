package com.nekobot.app.ui.navigation

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.screens.aiconfig.AiConfigCenterScreen
import com.nekobot.app.ui.screens.aiconfig.AiConfigScreen
import com.nekobot.app.ui.screens.aiconfig.AiModelsScreen
import com.nekobot.app.ui.screens.aiconfig.FailoverQueueScreen
import com.nekobot.app.ui.screens.aiconfig.LocalAiModelsScreen
import com.nekobot.app.ui.screens.aiconfig.OAuthAccountsScreen
import com.nekobot.app.ui.screens.characters.CharacterDetailScreen
import com.nekobot.app.ui.screens.characters.CharacterViewScreen
import com.nekobot.app.ui.screens.characters.CharactersScreen
import com.nekobot.app.ui.screens.chat.ModernChatScreen
import com.nekobot.app.ui.screens.chat.WorkspaceScreen
import com.nekobot.app.ui.screens.login.LoginScreen
import com.nekobot.app.ui.screens.memory.MemoryScreen
import com.nekobot.app.ui.screens.more.MoreScreen
import com.nekobot.app.ui.screens.sessions.SessionsScreen
import com.nekobot.app.ui.screens.sessions.SessionDetailScreen
import com.nekobot.app.ui.screens.plot.StoryGraphScreen
import com.nekobot.app.ui.screens.settings.ConfigTransferScreen
import com.nekobot.app.ui.screens.settings.DataMaintenanceScreen
import com.nekobot.app.ui.screens.settings.DbProfileScreen
import com.nekobot.app.ui.screens.settings.FeatureSwitchesScreen
import com.nekobot.app.ui.screens.settings.SettingsScreen
import com.nekobot.app.ui.screens.settings.StyleSettingsScreen
import com.nekobot.app.ui.screens.settings.SystemSettingsScreen
import com.nekobot.app.ui.screens.settings.WebDavBackupScreen
import com.nekobot.app.ui.screens.settings.AboutScreen
import com.nekobot.app.ui.screens.settings.LicenseScreen
import com.nekobot.app.ui.screens.settings.PrivacyScreen
import com.nekobot.app.ui.screens.statehistory.StateHistoryScreen
import com.nekobot.app.ui.screens.tokens.TokensScreen
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
import kotlinx.coroutines.launch

private val mainRoutes = setOf(
    Routes.SESSIONS, Routes.CHARACTERS, Routes.WORLD_BOOKS,
    Routes.TOKENS, Routes.MORE
)

// 底栏 Tab 的显示顺序，用于判断横向滑动方向。
private val tabOrder = bottomRoutes

private const val TAB_ANIM_MS = 200
private const val DETAIL_ANIM_MS = 220

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
    val mainPagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { bottomRoutes.size }
    )
    val mainPagerScope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in mainRoutes
    val selectedMainRoute = bottomRoutes.getOrElse(mainPagerState.currentPage) {
        Routes.SESSIONS
    }

    // 观察全局登录态：登出时自动跳登录页，登录时跳会话页
    val isLoggedIn by ServiceContainer.loginStateFlow.collectAsState()
    LaunchedEffect(isLoggedIn) {
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
    val pendingSessionId by ServiceContainer.pendingSessionId.collectAsState()
    LaunchedEffect(pendingSessionId) {
        val sid = pendingSessionId ?: return@LaunchedEffect
        ServiceContainer.setPendingSessionId(null) // 消费掉
        if (isLoggedIn) {
            navController.navigate(Routes.chat(sid))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Routes.SESSIONS else Routes.LOGIN,
            modifier = Modifier.fillMaxSize(),
            // 主 Tab 之间：按底栏顺序做方向性横向滑动 + 淡入，呼应圆岛的滑动方向；
            // 进入/退出详情页：从右侧滑入、向右滑出，形成层级纵深感。
            enterTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val dir = tabDirection(from, to)
                if (dir != 0) {
                    slideInHorizontally(tween(TAB_ANIM_MS)) { w -> dir * w / 6 } +
                        fadeIn(tween(TAB_ANIM_MS))
                } else {
                    slideInHorizontally(tween(DETAIL_ANIM_MS)) { w -> w } +
                        fadeIn(tween(DETAIL_ANIM_MS))
                }
            },
            exitTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val dir = tabDirection(from, to)
                if (dir != 0) {
                    slideOutHorizontally(tween(TAB_ANIM_MS)) { w -> -dir * w / 6 } +
                        fadeOut(tween(TAB_ANIM_MS))
                } else {
                    fadeOut(tween(DETAIL_ANIM_MS))
                }
            },
            popEnterTransition = { fadeIn(tween(DETAIL_ANIM_MS)) },
            popExitTransition = {
                slideOutHorizontally(tween(DETAIL_ANIM_MS)) { w -> w } +
                    fadeOut(tween(DETAIL_ANIM_MS))
            }
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(onLoggedIn = {
                    // 广播登录成功，LaunchedEffect 会自动导航到 SESSIONS
                    ServiceContainer.notifyLoginState(true)
                })
            }
            composable(Routes.SESSIONS) {
                HorizontalPager(
                    state = mainPagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { page -> bottomRoutes[page] },
                    beyondViewportPageCount = 1
                ) { page ->
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

                        Routes.TOKENS -> TokensScreen()

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
            composable(Routes.TOKENS) { TokensScreen() }
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
                    onOpenLicense = { navController.navigate(Routes.LICENSE) },
                    onOpenPrivacy = { navController.navigate(Routes.PRIVACY) }
                )
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
                KnowledgeScreen(onBack = { navController.popBackStack() })
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
                            mainPagerScope.launch {
                                mainPagerState.animateScrollToPage(targetPage)
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
    }
}
