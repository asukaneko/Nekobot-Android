package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 长按会话后横条里的一个动作。 */
internal data class SessionAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * 长按会话弹出的横向动作条状态。
 *
 * 交互：长按会话行 → 横条浮在该行上方 → 手指不离开时左右拖动切换高亮 → 松手后横条保留，
 * 点选项执行、点空白处收起。
 */
internal class SessionActionStripState {
    var actions by mutableStateOf<List<SessionAction>>(emptyList())
        private set
    var targetKey by mutableStateOf<String?>(null)
        private set

    /** 触发行的上下边界（窗口坐标），横条优先浮在该行上方。 */
    var anchorTop by mutableFloatStateOf(0f)
        private set
    var anchorBottom by mutableFloatStateOf(0f)
        private set
    var highlight by mutableStateOf<Int?>(null)
        private set

    /** 横条在窗口坐标里的实际位置，用于把手指横坐标映射成选项序号。 */
    var bounds by mutableStateOf(Rect.Zero)

    val visible: Boolean get() = actions.isNotEmpty()

    fun show(key: String, actions: List<SessionAction>, top: Float, bottom: Float) {
        if (actions.isEmpty()) return
        this.actions = actions
        this.targetKey = key
        this.anchorTop = top
        this.anchorBottom = bottom
        this.highlight = null
    }

    /** 手指横坐标（窗口坐标）→ 高亮项；横条还没量出位置时保持不高亮。 */
    fun updatePointer(windowX: Float) {
        if (bounds.width <= 0f || actions.isEmpty()) return
        val step = bounds.width / actions.size
        highlight = ((windowX - bounds.left) / step).toInt().coerceIn(0, actions.lastIndex)
    }

    /** 松手：只清掉高亮，横条保留（用户接下来点选项）。 */
    fun release() {
        highlight = null
    }

    fun run(index: Int) {
        val action = actions.getOrNull(index) ?: return
        hide()
        action.onSelect()
    }

    fun hide() {
        actions = emptyList()
        targetKey = null
        highlight = null
        anchorTop = 0f
        anchorBottom = 0f
    }
}

/**
 * 会话列表的长按动作条。
 *
 * 用普通层叠加绘制（不是 Popup），这样长按后手指仍在同一窗口里，可以继续拖动；
 * 横条优先浮在触发行的正上方（上方空间不足时落到下方），水平居中，
 * 松手后保留，点选项执行、点空白收起。
 */
@Composable
internal fun SessionActionStripOverlay(
    state: SessionActionStripState,
    origin: Offset,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val actions = state.actions
    val density = LocalDensity.current
    val itemWidth = 46.dp
    val stripHeight = 58.dp
    val horizontalPadding = 6.dp
    val cornerRadius = 20.dp
    val widthPx = with(density) { (itemWidth * actions.size + horizontalPadding * 2).toPx() }
    val heightPx = with(density) { stripHeight.toPx() }
    val marginPx = with(density) { 8.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val left = ((containerSize.width - widthPx) / 2f).coerceAtLeast(marginPx)
    val maxTop = (containerSize.height - heightPx - marginPx).coerceAtLeast(marginPx)
    // 优先浮在该行上方；上方放不下就放到行下方
    val above = state.anchorTop - origin.y - heightPx - gapPx
    val below = state.anchorBottom - origin.y + gapPx
    val top = (if (above >= marginPx) above else below).coerceIn(marginPx, maxTop)

    Box(modifier.fillMaxSize()) {
        // 点空白处收起
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(state) { detectTapGestures { state.hide() } }
        )
        Surface(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(itemWidth * actions.size + horizontalPadding * 2)
                .height(stripHeight)
                .onGloballyPositioned { state.bounds = it.boundsInWindow() },
            shape = RoundedCornerShape(cornerRadius),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEachIndexed { index, action ->
                    val selected = state.highlight == index
                    val tint = when {
                        action.destructive -> MaterialTheme.colorScheme.error
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .width(itemWidth)
                            .fillMaxHeight()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                            .pointerInput(action) {
                                detectTapGestures { state.run(index) }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = tint
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = action.label,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}
