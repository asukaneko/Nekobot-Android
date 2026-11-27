package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lianxu.independent.LianxuActivity
import com.nekobot.app.NekobotPrivacyGate
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.SessionActivityBus
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.screens.sessions.SessionListRow

/**
 * 首页导航区：三张模式卡。
 *
 * 单会话卡和胶囊均进入独立界面，其余胶囊打开对应会话列表。
 * 高度由外层按剩余空间给出，卡片据此缩放，保证首页整屏不滚动。
 *
 * 运行状态在这里统一收集（而不是由调用方传参）：Agent 卡需要「哪些会话在跑」
 * 以及「每个会话是在思考还是在调工具」，两者都是全局状态。
 */
@Composable
fun HomeModeNavigation(
    rows: List<SessionListRow>,
    characters: List<CharacterPreset>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val runningSessionIds by ServiceContainer.generatingSessions.collectAsStateWithLifecycle()
    val activities by SessionActivityBus.activities.collectAsStateWithLifecycle()
    val cards = homeModeCards(
        rows = rows,
        characters = characters,
        runningSessionIds = runningSessionIds,
        activities = activities
    )
    var activeIndex by rememberSaveable { mutableIntStateOf(0) }

    HomeModeDeck(
        cards = cards,
        activeIndex = activeIndex,
        onActiveIndexChange = { activeIndex = it },
        onOpenMode = { mode ->
            when (mode) {
                HomeMode.COMPANION -> {
                    context.startActivity(LianxuActivity.createIntent(context))
                    NekobotPrivacyGate.enteringCompanion()
                }
                else -> onNavigate(Routes.mode(mode.routeValue))
            }
        },
        onOpenModeList = { mode -> onNavigate(Routes.sessionsList(mode.routeValue)) },
        modifier = modifier
    )
}
