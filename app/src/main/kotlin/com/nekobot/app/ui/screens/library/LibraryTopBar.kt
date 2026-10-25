package com.nekobot.app.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 「内容库」宿主与两个子页（角色 / 世界书）之间的顶栏契约。
 *
 * 内容库把两个列表合成一页，顶栏只应该有一份；而顶栏上的动作（切换视图、刷新、
 * 导入、新建）天然属于各自列表。因此子页在嵌入模式下把自己的动作登记到宿主，
 * 由宿主统一渲染，避免出现「两层标题栏」或功能丢失。
 */
@Immutable
data class LibraryTopAction(
    /** 稳定的动作标识，用于测试与定位 */
    val key: String,
    val icon: ImageVector,
    val label: String,
    /** 主行动（用强调色显示） */
    val prominent: Boolean = false,
    val onClick: () -> Unit,
)

/** 内容库顶栏动作的持有者。 */
@Stable
class LibraryTopBarState {
    var actions by mutableStateOf<List<LibraryTopAction>>(emptyList())
        private set

    fun update(actions: List<LibraryTopAction>) {
        this.actions = actions
    }
}

val LocalLibraryTopBar = staticCompositionLocalOf<LibraryTopBarState?> { null }

/**
 * 子页登记顶栏动作。
 *
 * 用 [SideEffect] 写入：宿主只读取这个状态渲染顶栏，子页不读取它，
 * 因此不会形成重组回路；每次子页重组时顺手刷新闭包，保证动作始终指向最新状态。
 */
@Composable
fun LibraryTopBarActions(actions: () -> List<LibraryTopAction>) {
    val host = LocalLibraryTopBar.current ?: return
    val latest = rememberUpdatedState(actions)
    SideEffect { host.update(latest.value()) }
}
