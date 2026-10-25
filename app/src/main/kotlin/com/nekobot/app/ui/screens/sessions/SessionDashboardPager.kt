package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** 负一屏从上层覆盖，会话页在原位缩小；所有变换直接跟随同一滑动进度。 */
@Composable
internal fun SessionDashboardPager(
    state: PagerState,
    content: @Composable (dashboardVisible: Boolean) -> Unit
) {
    val scrollDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f

    HorizontalPager(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        key = { it },
        // 覆盖期间底层会话页仍需绘制，完整进入概览后也保留原有列表状态。
        beyondViewportPageCount = 1
    ) { page ->
        val dashboardVisible = page == 0
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (dashboardVisible) 1f else 0f)
                .graphicsLayer {
                    val progress = (1f - state.currentPage - state.currentPageOffsetFraction)
                        .coerceIn(0f, 1f)
                    if (dashboardVisible) {
                        shape = RoundedCornerShape(24.dp * (1f - progress))
                        shadowElevation = 18.dp.toPx() * 4f * progress * (1f - progress)
                    } else {
                        // 抵消 Pager 的横移，让概览滑过原位缩小的会话页。
                        translationX = -scrollDirection * size.width * progress
                        scaleX = 1f - 0.08f * progress
                        scaleY = scaleX
                        shape = RoundedCornerShape(24.dp * progress)
                    }
                    clip = true
                }
        ) {
            content(dashboardVisible)
        }
    }
}
