package com.nekobot.app.ui.screens.sessions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 左滑抽屉里的一个动作：只显示图标，不显示文字。 */
internal data class SessionSwipeAction(
    val icon: ImageVector,
    val contentDescription: String,
    val tint: Color,
    val onSelect: () -> Unit
)

/** 抽屉弹簧参数：接近临界阻尼，回弹很轻，收放都不费劲。 */
private val SwipeSpring = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = Spring.StiffnessMediumLow
)

/** 最多拉伸到 108%，超出后阻力变大，形成很轻的"橡胶带"手感。 */
private const val SwipeStretchRatio = 1.08f
private const val SwipeResistance = 0.18f

/** 松手时判定为展开/收起的拖拽比例阈值（收放都只需拖过四分之一）。 */
private const val SwipeOpenFraction = 0.25f

/**
 * 会话行左滑抽屉。
 *
 * - 手指向左拖动展开抽屉（4 个仅图标按钮），超出宽度后按阻力继续拉伸；
 * - 松手用接近临界阻尼的弹簧回弹到展开/收起位置，回弹很轻，不会来回弹；
 * - 同一时刻只允许一行展开，展开状态由 [openKey] 统一管理。
 *
 * 展开状态下点击行内容只收起抽屉，不会进入会话（由调用方在 [content] 里处理）。
 */
@Composable
internal fun SessionSwipeRow(
    rowKey: String,
    openKey: String?,
    onOpenKeyChange: (String?) -> Unit,
    shape: Shape,
    actions: List<SessionSwipeAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val actionSize = 44.dp
    val drawerWidthPx = with(density) {
        (actionSize * actions.size.coerceAtLeast(1)).toPx()
    }
    val maxStretchPx = drawerWidthPx * SwipeStretchRatio
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 展开状态由外层统一管理：打开另一行时本行自动回弹收起
    LaunchedEffect(openKey, drawerWidthPx) {
        val target = if (openKey == rowKey) -drawerWidthPx else 0f
        offset.animateTo(target, SwipeSpring)
    }

    val drawerColor = MaterialTheme.colorScheme.surfaceVariant

    // 关掉抽屉：回弹到 0 并清空展开状态
    val closeDrawer: () -> Unit = {
        onOpenKeyChange(null)
        scope.launch { offset.animateTo(0f, SwipeSpring) }
    }

    Box(
        modifier = modifier
            // 自定义手势：只在「向左滑」或「抽屉已展开」时接管横滑，
            // 向右滑仍然交给负一屏 Pager，避免把整页横滑手势吃掉。
            .pointerInput(enabled, rowKey, drawerWidthPx) {
                if (!enabled || actions.isEmpty()) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Main
                    )
                    var claimed = false
                    val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        val opening = overSlop < 0f || offset.value < -0.5f
                        if (opening) {
                            change.consume()
                            claimed = true
                        }
                    } ?: return@awaitEachGesture
                    if (!claimed) return@awaitEachGesture
                    var lastX = slopChange.position.x
                    drag(slopChange.id) { change ->
                        val delta = change.position.x - lastX
                        lastX = change.position.x
                        change.consume()
                        // 越界后阻力递增：先到 100%，再慢慢拉伸到 125%
                        val raw = offset.value + delta
                        val resisted = if (raw < -drawerWidthPx) {
                            -drawerWidthPx + (raw + drawerWidthPx) * SwipeResistance
                        } else {
                            raw
                        }
                        scope.launch {
                            offset.snapTo(resisted.coerceIn(-maxStretchPx, 0f))
                        }
                    }
                    // 松手：超过阈值则完全展开，否则弹回原位
                    val shouldOpen = offset.value < -drawerWidthPx * SwipeOpenFraction
                    onOpenKeyChange(if (shouldOpen) rowKey else null)
                    scope.launch {
                        offset.animateTo(
                            if (shouldOpen) -drawerWidthPx else 0f,
                            SwipeSpring
                        )
                    }
                }
            }
    ) {
        // 抽屉：贴在行右侧，随拉伸幅度横向轻微延展，回弹时最明显
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(drawerColor)
                .graphicsLayer {
                    val reveal = (-offset.value / drawerWidthPx).coerceIn(0f, SwipeStretchRatio)
                    // 以右边缘为锚点横向延展：拉伸与回弹都只影响抽屉自身
                    transformOrigin = TransformOrigin(1f, 0.5f)
                    scaleX = 0.97f + 0.03f * reveal
                    alpha = (reveal / 0.18f).coerceIn(0f, 1f)
                },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                Box(
                    modifier = Modifier
                        .size(actionSize)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            closeDrawer()
                            action.onSelect()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.contentDescription,
                        tint = action.tint,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
        // 行内容：左滑时整体左移，露出右侧抽屉
        Box(
            modifier = Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
        ) {
            content()
        }
    }
}

/** 会话行首尾圆角：整组拼成一张分组卡，中间行保持直角。 */
internal fun sessionItemShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) 18.dp else 0.dp,
    topEnd = if (first) 18.dp else 0.dp,
    bottomStart = if (last) 18.dp else 0.dp,
    bottomEnd = if (last) 18.dp else 0.dp
)
