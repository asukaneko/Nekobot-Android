package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 模式首页宿主：按路由参数分派到具体页面。
 *
 * 三个模式共用同一个 [ModeHomeViewModel]（一次导航一份数据快照），
 * 新增平行页面时只要在这里加一个分支。
 */
@Composable
fun ModeHomeScreen(
    modeValue: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenChat: (String) -> Unit,
    /** 新建会话后进入聊天：模式欢迎页从返回栈里退掉，返回时直接回到首页。 */
    onOpenNewChat: (String) -> Unit,
    onOpenSessions: (HomeMode) -> Unit,
    onSwitchMode: (HomeMode) -> Unit,
    onCreateCompanion: () -> Unit
) {
    val mode = HomeMode.fromRoute(modeValue)
    if (mode == null) {
        // 未知模式：直接退回上一页，避免停在空白页
        LaunchedEffect(modeValue) { onBack() }
        return
    }
    val viewModel: ModeHomeViewModel = viewModel()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(mode) { viewModel.load() }

    when (mode) {
        HomeMode.AGENT -> AgentModeScreen(
            sessions = rows.filter { it.modeLabel == HomeMode.AGENT.modeLabel },
            creating = creating,
            error = error,
            onErrorShown = viewModel::clearError,
            onBack = onBack,
            onHome = onHome,
            onOpenChat = onOpenChat,
            onChatCreated = onOpenNewChat,
            onOpenSessions = { onOpenSessions(HomeMode.AGENT) },
            onSwitchMode = onSwitchMode,
            onCreateSession = { text, onReady -> viewModel.createAgentSession(text, onReady) }
        )

        HomeMode.CHARACTER -> CharacterModeScreen(
            sessions = rows.filter { it.modeLabel == HomeMode.CHARACTER.modeLabel },
            characters = characters,
            creating = creating,
            error = error,
            onErrorShown = viewModel::clearError,
            onBack = onBack,
            onOpenChat = onOpenChat,
            onCreateSession = { characterId, name, onReady ->
                viewModel.createSessionForCharacter(characterId, name, onReady)
            }
        )

        HomeMode.COMPANION -> CompanionModeScreen(
            sessions = rows.filter { it.modeLabel == HomeMode.COMPANION.modeLabel },
            canCreate = com.nekobot.app.ServiceContainer.prefs.isLocalMode,
            error = error,
            onErrorShown = viewModel::clearError,
            onBack = onBack,
            onOpenChat = onOpenChat,
            onCreateCompanion = onCreateCompanion
        )
    }
}
