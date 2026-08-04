package com.nekobot.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 根据窗口宽度自适应选择底栏或侧边导航。
 *
 * - Compact：使用提供的 [bottomBar]（Scaffold 底栏模式）
 * - Medium：同 Compact（保持底栏，避免中等宽度下侧栏过窄）
 * - Expanded：使用提供的 [sideBar]（NavigationRail 风格侧边栏）+ Row 布局
 *
 * @param bottomBar 紧凑模式下的底栏
 * @param sideBar 展开模式下的侧边栏，为 null 时退化为底栏
 * @param content 内容区域，接收 padding Modifier
 */
@Composable
fun AdaptiveNavScaffold(
    bottomBar: @Composable () -> Unit,
    sideBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit
) {
    val widthClass = rememberWindowWidthClass()

    when (widthClass) {
        WindowWidthClass.Expanded -> {
            // 大屏：侧边栏 + 内容
            Row(modifier = Modifier.fillMaxSize()) {
                if (sideBar != null) {
                    sideBar()
                } else {
                    // 无侧栏时退化为不显示，仅内容
                }
                Box(modifier = Modifier.weight(1f)) {
                    content(Modifier)
                }
            }
        }
        else -> {
            // 紧凑和中等宽度：底栏模式
            Scaffold(
                bottomBar = bottomBar
            ) { innerPadding ->
                content(Modifier)
            }
        }
    }
}
