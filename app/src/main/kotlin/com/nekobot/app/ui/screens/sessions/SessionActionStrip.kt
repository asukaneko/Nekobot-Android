package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.components.nekoTextSize
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

    // 横条回报的布局数据：每项宽度按自身文案单独计算，窄屏时会被压窄、放不下时整条可横向滚动
    private var itemWidthsPx = emptyList<Float>()
    private var paddingPx = 0f
    private var scrollOffsetPx = 0f

    val visible: Boolean get() = actions.isNotEmpty()

    fun show(key: String, actions: List<SessionAction>, top: Float, bottom: Float) {
        if (actions.isEmpty()) return
        this.actions = actions
        this.targetKey = key
        this.anchorTop = top
        this.anchorBottom = bottom
        this.highlight = null
    }

    /** 横条回报布局（每项宽度、左右内边距、滚动偏移），用于拖动命中计算。 */
    fun reportLayout(itemWidths: List<Float>, padding: Float, scrollOffset: Float) {
        itemWidthsPx = itemWidths
        paddingPx = padding
        scrollOffsetPx = scrollOffset
    }

    /** 手指横坐标（窗口坐标）→ 高亮项；横条还没量出位置时保持不高亮。 */
    fun updatePointer(windowX: Float) {
        if (actions.isEmpty() || bounds.width <= 0f) return
        if (itemWidthsPx.size != actions.size) return
        val contentX = windowX - bounds.left - paddingPx + scrollOffsetPx
        // 按累加宽度定位，每项宽度不同（"置顶" 与 "取消置顶" 不共用同一个宽度）
        var edge = 0f
        var hit = actions.lastIndex
        for (index in itemWidthsPx.indices) {
            edge += itemWidthsPx[index]
            if (contentX < edge) {
                hit = index
                break
            }
        }
        highlight = hit.coerceIn(0, actions.lastIndex)
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
 * 横条优先浮在触发行的正上方（上方空间不足时落到下方），水平居中。
 * 每项宽度按可用宽度收缩，仍放不下时整条可横向滚动，保证最右侧的动作也能点到。
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
    val stripHeight = with(density) { nekoTextSize(16f).toDp() } + 44.dp
    val horizontalPadding = 6.dp
    val cornerRadius = 20.dp

    val marginPx = with(density) { 8.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val paddingPx = with(density) { horizontalPadding.toPx() }
    // 每项按自身文案的排版宽度单独定宽：置顶态的「取消置顶」不会把「删除/更多」一起撑宽
    val labelStyle = TextStyle(
        fontSize = nekoTextSize(11f),
        lineHeight = nekoTextSize(16f),
        // 与主题同一字体族，保证实测宽度和实际渲染一致（自定义字体也按真实字宽算）
        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
    )
    val textMeasurer = rememberTextMeasurer()
    val labelWidthsPx = remember(actions, labelStyle) {
        actions.map { action ->
            textMeasurer.measure(
                text = AnnotatedString(action.label),
                style = labelStyle,
                maxLines = 1,
                softWrap = false
            ).size.width.toFloat()
        }
    }
    val labelGapPx = with(density) { 10.dp.toPx() }
    val minItemPx = with(density) { 40.dp.toPx() }
    val heightPx = with(density) { stripHeight.toPx() }

    val maxWidthPx = (containerSize.width - marginPx * 2f).coerceAtLeast(minItemPx)
    val idealWidthsPx = labelWidthsPx.map { maxOf(it + labelGapPx, minItemPx) }
    val availablePx = (maxWidthPx - paddingPx * 2f).coerceAtLeast(0f)
    val idealTotalPx = idealWidthsPx.sum()
    // 窗口太窄时按比例压窄，但每项不低于最小点击宽度；仍放不下就整条横向滚动
    val itemWidthsPx = if (idealTotalPx <= availablePx) {
        idealWidthsPx
    } else {
        idealWidthsPx.map { maxOf(minItemPx, it * availablePx / idealTotalPx) }
    }
    val itemWidthsDp = itemWidthsPx.map { with(density) { it.toDp() } }
    val fullWidthPx = itemWidthsPx.sum() + paddingPx * 2f
    val scrollable = fullWidthPx > maxWidthPx
    val widthPx = minOf(fullWidthPx, maxWidthPx)
    val stripWidth = with(density) { widthPx.toDp() }

    val scrollState = rememberScrollState()
    SideEffect { state.reportLayout(itemWidthsPx, paddingPx, scrollState.value.toFloat()) }

    val left = ((containerSize.width - widthPx) / 2f).coerceAtLeast(marginPx)
    val maxTop = (containerSize.height - heightPx - marginPx).coerceAtLeast(marginPx)
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
                .width(stripWidth)
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
                    .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
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
                            .width(itemWidthsDp[index])
                            .fillMaxHeight()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                            // clickable 同时提供无障碍点击语义、角色与点击反馈
                            .clickable(role = Role.Button) { state.run(index) },
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
                            style = labelStyle,
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
