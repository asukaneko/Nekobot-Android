package com.nekobot.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.screens.aiconfig.AiConfigScreen
import com.nekobot.app.ui.screens.aiconfig.AiModelsScreen
import com.nekobot.app.ui.screens.aiconfig.LocalAiModelsScreen
import com.nekobot.app.ui.screens.characters.CharacterDetailScreen
import com.nekobot.app.ui.screens.characters.CharactersScreen
import com.nekobot.app.ui.screens.chat.ChatScreen
import com.nekobot.app.ui.screens.chat.WorkspaceScreen
import com.nekobot.app.ui.screens.login.LoginScreen
import com.nekobot.app.ui.screens.memory.MemoryScreen
import com.nekobot.app.ui.screens.more.MoreScreen
import com.nekobot.app.ui.screens.sessions.SessionsScreen
import com.nekobot.app.ui.screens.sessions.SessionDetailScreen
import com.nekobot.app.ui.screens.settings.SettingsScreen
import com.nekobot.app.ui.screens.settings.StyleSettingsScreen
import com.nekobot.app.ui.screens.settings.SystemSettingsScreen
import com.nekobot.app.ui.screens.statehistory.StateHistoryScreen
import com.nekobot.app.ui.screens.tokens.TokensScreen
import com.nekobot.app.ui.screens.worldbook.WorldBookDetailScreen
import com.nekobot.app.ui.screens.worldbook.WorldBooksScreen

private val mainRoutes = setOf(
    Routes.SESSIONS, Routes.CHARACTERS, Routes.WORLD_BOOKS,
    Routes.TOKENS, Routes.MORE
)

// 底栏 Tab 的显示顺序，用于判断横向滑动方向。
private val tabOrder = bottomItems.map { it.route }

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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in mainRoutes

    // 观察全局登录态：登出时自动跳登录页，登录时跳会话页
    val isLoggedIn by ServiceContainer.loginStateFlow.collectAsState()
    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        } else if (isLoggedIn && currentRoute == Routes.LOGIN) {
            navController.navigate(Routes.SESSIONS) {
                popUpTo(Routes.LOGIN) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                LiquidGlassBottomBar(
                    items = bottomItems,
                    selectedRoute = currentRoute,
                    onItemSelected = { item ->
                        navController.navigate(item.route) {
                            popUpTo(Routes.SESSIONS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        // 只应用底部 padding（bottomBar 高度 + 导航栏 inset），
        // 顶部 inset 交给各 Screen 内部的 TopAppBar 消耗，避免状态栏 inset 被应用两次导致顶部空白过高。
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) Routes.SESSIONS else Routes.LOGIN,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
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
                SessionsScreen(
                    onOpenChat = { id ->
                        navController.navigate(Routes.chat(id))
                    },
                    onOpenDetail = { id ->
                        navController.navigate(Routes.sessionDetail(id))
                    }
                )
            }
            composable(
                route = Routes.SESSION_DETAIL,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                SessionDetailScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id ->
                        navController.navigate(Routes.chat(id))
                    }
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { entry ->
                ChatScreen(
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                    onOpenSessionDetail = { id -> navController.navigate(Routes.sessionDetail(id)) },
                    onOpenWorkspace = { id -> navController.navigate(Routes.workspace(id)) }
                )
            }
            composable(Routes.CHARACTERS) {
                CharactersScreen(onOpenCharacter = { id ->
                    navController.navigate(Routes.characterDetail(id))
                })
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
            composable(Routes.AI_CONFIG) {
                AiConfigScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AI_MODELS) {
                AiModelsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOCAL_AI_MODELS) {
                LocalAiModelsScreen(onBack = { navController.popBackStack() })
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
        }
    }
}
