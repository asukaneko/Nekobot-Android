package com.nekobot.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.nekobot.app.ui.screens.characters.CharacterDetailScreen
import com.nekobot.app.ui.screens.characters.CharactersScreen
import com.nekobot.app.ui.screens.chat.ChatScreen
import com.nekobot.app.ui.screens.login.LoginScreen
import com.nekobot.app.ui.screens.more.MoreScreen
import com.nekobot.app.ui.screens.sessions.SessionsScreen
import com.nekobot.app.ui.screens.sessions.SessionDetailScreen
import com.nekobot.app.ui.screens.settings.SettingsScreen
import com.nekobot.app.ui.screens.settings.SystemSettingsScreen
import com.nekobot.app.ui.screens.statehistory.StateHistoryScreen
import com.nekobot.app.ui.screens.tokens.TokensScreen
import com.nekobot.app.ui.screens.worldbook.WorldBookDetailScreen
import com.nekobot.app.ui.screens.worldbook.WorldBooksScreen
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary

private val mainRoutes = setOf(
    Routes.SESSIONS, Routes.CHARACTERS, Routes.WORLD_BOOKS,
    Routes.TOKENS, Routes.MORE
)

@Composable
fun NekobotNavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in mainRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = BgDark) {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(Routes.SESSIONS) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { androidx.compose.material3.Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                unselectedIconColor = OnSurfaceVariant,
                                unselectedTextColor = OnSurfaceVariant,
                                indicatorColor = BgDark
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // 只应用底部 padding（bottomBar 高度 + 导航栏 inset），
        // 顶部 inset 交给各 Screen 内部的 TopAppBar 消耗，避免状态栏 inset 被应用两次导致顶部空白过高。
        NavHost(
            navController = navController,
            startDestination = if (ServiceContainer.prefs.isLoggedIn) Routes.SESSIONS else Routes.LOGIN,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
            // 加快切换动画：淡入淡出 150ms，底部 Tab 间几乎瞬时
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(120)) },
            popEnterTransition = { fadeIn(animationSpec = tween(150)) },
            popExitTransition = { fadeOut(animationSpec = tween(120)) }
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(onLoggedIn = {
                    navController.navigate(Routes.SESSIONS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
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
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
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
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onLogout = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
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
        }
    }
}
