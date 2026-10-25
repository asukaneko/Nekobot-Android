package com.nekobot.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.NekoIconAction
import com.nekobot.app.ui.components.NekoPageHorizontalPadding
import com.nekobot.app.ui.components.NekoPageTopBar
import com.nekobot.app.ui.components.NekoSearchField
import com.nekobot.app.ui.components.NekoSegmentedControl
import com.nekobot.app.ui.screens.characters.CharactersScreen
import com.nekobot.app.ui.screens.worldbook.WorldBooksScreen

/**
 * 「内容库」一级页面：角色与世界书合成一页，用分段控件切换。
 *
 * 设计取向：一个入口下放两类内容，列表本身保持单一焦点；
 * 顶栏只保留当前列表真正需要的动作（由子页登记，见 [LibraryTopBarActions]）。
 */
@Composable
fun LibraryScreen(
    onOpenCharacter: (String) -> Unit,
    onOpenEdit: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenMatchDebug: () -> Unit,
    /** 初始分段：0 角色 / 1 世界书（全局搜索可直接落到世界书）。 */
    initialTab: Int = 0
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var query by rememberSaveable { mutableStateOf("") }
    val topBarState = remember { LibraryTopBarState() }
    val tabs = listOf(
        stringResource(R.string.nav_characters),
        stringResource(R.string.nav_world_books),
    )

    CompositionLocalProvider(LocalLibraryTopBar provides topBarState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NekoPageTopBar(
                title = stringResource(R.string.nav_library),
                actions = {
                    topBarState.actions.forEach { action ->
                        NekoIconAction(
                            icon = action.icon,
                            contentDescription = action.label,
                            onClick = action.onClick,
                            tint = if (action.prominent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NekoPageHorizontalPadding)
            ) {
                NekoSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.shell_library_search)
                )
                Spacer(Modifier.height(10.dp))
                NekoSegmentedControl(
                    options = tabs,
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it }
                )
                Spacer(Modifier.height(6.dp))
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedTab == 0) {
                    CharactersScreen(
                        onOpenCharacter = onOpenCharacter,
                        onOpenEdit = onOpenEdit,
                        embedded = true,
                        searchQuery = query
                    )
                } else {
                    WorldBooksScreen(
                        onOpenBook = onOpenBook,
                        onOpenMatchDebug = onOpenMatchDebug,
                        embedded = true,
                        searchQuery = query
                    )
                }
            }
        }
    }
}
