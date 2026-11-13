package com.nekobot.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** 逐帧滚动的基础速度：一帧最多滚过可视高度的多少（120Hz 屏幕上速度约为两倍）。 */
private const val SCROLL_MAX_STEP_RATIO = 0.42f
/** 末尾进入视口后按剩余距离的比例减速，形成自然的收尾。 */
private const val SCROLL_TAIL_RATIO = 0.34f
/** 起步 8 帧内从 38% 速度加到全速，避免点击瞬间画面"弹"一下。 */
private const val SCROLL_RAMP_FRAMES = 8f
/** 贴底前的最小步进：末尾不足一屏时也能一帧帧真正推到底，不会提前收手。 */
private const val SCROLL_MIN_STEP_PX = 6f
/** 兜底帧数上限：约 20 秒，只用作死循环保护，正常会话远用不到。 */
private const val SCROLL_MAX_FRAMES = 1200

/**
 * 「真实滚动」的单帧位移。
 *
 * @param viewportPx 列表可视高度
 * @param remainingPx 距离列表底部的像素；为 null 表示末尾还没进入视口、剩余距离未知
 * @param frames 已经滚过的帧数（用于起步加速）
 */
internal fun chatScrollStepPx(viewportPx: Float, remainingPx: Float?, frames: Int): Float {
    if (viewportPx <= 0f) return 0f
    val ramp = ((frames + 1) / SCROLL_RAMP_FRAMES).coerceIn(0.38f, 1f)
    val maxStep = viewportPx * SCROLL_MAX_STEP_RATIO * ramp
    // 剩余距离已经被最后一条的底边吃满（含底部内边距的误差）时按 0 处理，交给最小步进收尾
    val step = if (remainingPx == null) maxStep
        else (remainingPx.coerceAtLeast(0f) * SCROLL_TAIL_RATIO).coerceAtMost(maxStep)
    return step.coerceAtLeast(SCROLL_MIN_STEP_PX)
}

/**
 * 直接（无动画）落到列表真正的底部。
 *
 * [LazyListState.scrollToItem] 只保证"这一条从视口顶部开始"：最后一条比屏幕还高时，
 * 它会把这条消息的**开头**对到顶部，看起来就像被拉回到气泡开头。这里再补几次整屏滚动，
 * 保证停在最新内容处。
 */
internal suspend fun LazyListState.jumpToBottom(fallbackIndex: Int) {
    // 列表还没测量过（刚进会话）时先按索引落位；已测量时直接从当前位置滚到底，避免多画一帧气泡开头
    if (layoutInfo.totalItemsCount == 0 && fallbackIndex >= 0) scrollToItem(fallbackIndex)
    // 再补齐"最后一条比屏幕高"时剩下的那段，保证停在最新内容处而不是它的开头。
    // 每轮直接请求十几屏（会被夹到底部），这样即使是超长消息也能在同一帧内落到底
    var guard = 0
    while (canScrollForward && guard++ < 64) {
        val layout = layoutInfo
        val viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
        if (viewportPx <= 0f) break
        if (scrollBy(viewportPx * 16f) <= 0f) break
    }
}

/**
 * 真实滚动到消息列表底部：每帧只推进一小段滚动位置（[scrollBy] + [withFrameNanos]），
 * 让消息在屏幕上真实地滚过去，而不是一次性跳到末尾；接近底部时自动减速。
 *
 * 用户手指按住列表时 Compose 会以更高优先级打断本次滚动，此时静默结束；
 * 页面销毁导致的取消继续向上抛，交给协程正常收尾。
 *
 * @return 是否已经停在底部
 */
internal suspend fun LazyListState.smoothScrollToBottom(): Boolean {
    var frames = 0
    var interrupted = false
    try {
        // 先等一帧：新消息刚插入时列表可能还没重新布局，先让布局生效再判断还能不能滚
        withFrameNanos { }
        while (frames < SCROLL_MAX_FRAMES && canScrollForward) {
            // 用户已经按住列表（拖动 / fling）时立即让位，避免松手后又被程序继续往下拽
            if (isScrollInProgress) {
                interrupted = true
                break
            }
            val layout = layoutInfo
            val viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
            val tail = layout.visibleItemsInfo.lastOrNull()
            val remainingPx = if (tail != null && tail.index == layout.totalItemsCount - 1) {
                // 末尾已经进入视口：剩下的是它超出视口底边的高度（底部内边距也算在内）
                (tail.offset + tail.size + layout.afterContentPadding - layout.viewportEndOffset)
                    .toFloat()
            } else {
                null
            }
            val step = chatScrollStepPx(viewportPx, remainingPx, frames)
            if (step <= 0f) break
            // 用户拖动 / fling 会以更高优先级接管，这里被取消即可退出
            if (scrollBy(step) <= 0f) break
            frames++
            withFrameNanos { }
        }
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        interrupted = true
    }
    // 提前收手（帧预算用尽 / 内容刚好测不到）时补齐到真正的底部；
    // 注意不能用 scrollToItem(最后一条)——那样只会把这条消息的开头对到顶部
    if (!interrupted && canScrollForward) {
        jumpToBottom(layoutInfo.totalItemsCount - 1)
    }
    return !canScrollForward
}

/**
 * 聊天消息区右下角的「滚动到底部」悬浮按钮。
 *
 * 只在列表离开底部时出现：贴着底部时没有可滚动的目标，按钮只会遮挡内容。
 * [visible] 的状态在这里读取，滚动时只有按钮自身重组，不影响消息列表。
 */
@Composable
internal fun ChatScrollToBottomButton(
    listState: LazyListState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible by remember(listState) { derivedStateOf { listState.canScrollForward } }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)) + scaleIn(tween(170), initialScale = 0.8f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f))
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("chat_scroll_to_bottom"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardDoubleArrowDown,
                contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
