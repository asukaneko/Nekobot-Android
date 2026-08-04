package com.nekobot.app.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** 窗口宽度断点分类，参照 Material3 WindowSizeClass 简化版 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/** 可测试的纯函数：根据宽度 dp 计算断点 */
fun computeWindowWidthClass(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.Compact
    widthDp < 840 -> WindowWidthClass.Medium
    else -> WindowWidthClass.Expanded
}

/** 根据当前 Configuration 获取窗口宽度断点 */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        computeWindowWidthClass(configuration.screenWidthDp)
    }
}

/** 是否应使用双栏布局（Medium 及以上） */
@Composable
fun rememberShouldUseTwoPane(): Boolean =
    rememberWindowWidthClass() != WindowWidthClass.Compact
