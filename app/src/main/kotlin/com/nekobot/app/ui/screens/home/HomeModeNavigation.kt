package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.screens.sessions.SessionListRow
import com.nekobot.app.ui.screens.sessions.SessionModeLabel

/**
 * 首页导航区：三张模式卡。
 *
 * 卡身点击进入该模式的页面；右下角胶囊进入该模式的全部会话列表。
 * 高度由外层按剩余空间给出，卡片据此缩放，保证首页整屏不滚动。
 */
@Composable
fun HomeModeNavigation(
    rows: List<SessionListRow>,
    characters: List<CharacterPreset>,
    onOpenSession: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cards = homeModeCards(rows, characters)
    var activeIndex by rememberSaveable { mutableIntStateOf(0) }

    HomeModeDeck(
        cards = cards,
        activeIndex = activeIndex,
        onActiveIndexChange = { activeIndex = it },
        onOpenMode = { mode ->
            when (mode) {
                // 单角色只有一个会话：有就直接进，没有才去模式页新建
                HomeMode.COMPANION -> {
                    val latest = rows.firstOrNull { it.modeLabel == SessionModeLabel.COMPANION }?.id
                    if (latest != null) onOpenSession(latest) else onNavigate(Routes.mode(mode.routeValue))
                }
                else -> onNavigate(Routes.mode(mode.routeValue))
            }
        },
        onOpenModeList = { mode -> onNavigate(Routes.sessionsList(mode.routeValue)) },
        modifier = modifier
    )
}
