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

/** 左侧抽屉导航所需的最小窗口高度：竖直排下 4~5 个导航项还要留出标题与上下留白。 */
const val NavDrawerMinHeightDp = 520

/**
 * 可测试的纯函数：是否使用左侧抽屉导航（平板形态）。
 *
 * 需要「够宽 + 够高」同时成立：宽度达到平板断点（≥600dp），且高度不低于 [NavDrawerMinHeightDp]。
 * 手机横屏虽然够宽，但高度通常不足 520dp，竖直排列导航项会非常拥挤，
 * 因此仍沿用底部导航栏。
 */
fun shouldUseNavDrawer(widthDp: Int, heightDp: Int): Boolean =
    computeWindowWidthClass(widthDp) != WindowWidthClass.Compact && heightDp >= NavDrawerMinHeightDp

/** 当前窗口是否应使用左侧抽屉导航。 */
@Composable
fun rememberShouldUseNavDrawer(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        shouldUseNavDrawer(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}
