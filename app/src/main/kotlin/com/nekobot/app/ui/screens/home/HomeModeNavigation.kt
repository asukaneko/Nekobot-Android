package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lianxu.independent.LianxuActivity
import com.nekobot.app.NekobotPrivacyGate
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.screens.sessions.SessionListRow

/**
 * 首页导航区：三张模式卡。
 *
 * 单会话卡和胶囊均进入独立界面，其余胶囊打开对应会话列表。
 * 高度由外层按剩余空间给出，卡片据此缩放，保证首页整屏不滚动。
 */
@Composable
fun HomeModeNavigation(
    rows: List<SessionListRow>,
    characters: List<CharacterPreset>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cards = homeModeCards(rows, characters)
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
