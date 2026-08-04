package com.nekobot.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val BarHeight = 64.dp
private val BarHorizontalPadding = 16.dp
private val BarVerticalPadding = 10.dp
private val IndicatorInset = 6.dp
private val IndicatorCorner = 22.dp

/**
 * 苹果风格「圆岛」底部导航：悬浮的液态玻璃胶囊 + 在标签间平滑滚动切换的选中指示器。
 * 指示器的左右两条边采用不同刚度的弹簧，滑动过程中会短暂拉伸再回弹，营造液态形变效果。
 *
 * 说明：真正的背景毛玻璃（模糊其后内容）需要 Haze 库或 API 31+ 的 RenderEffect，
 * 这里用半透明渐变 + 高光描边 + 柔和投影模拟玻璃质感，无额外依赖，兼容 minSdk 26。
 */
@Composable
fun LiquidGlassBottomBar(
    items: List<BottomItem>,
    selectedRoute: String?,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val density = LocalDensity.current

    // 拖动状态：dragFraction 为连续的标签位置（如 2.4 表示在第 2、3 个标签之间），null 表示未拖动。
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BarHorizontalPadding, vertical = BarVerticalPadding)
    ) {
        GlassPill(dark = dark) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BarHeight)
            ) {
                val itemWidth: Dp = maxWidth / items.size
                val itemWidthPx = with(density) { itemWidth.toPx() }
                val lastIndex = items.lastIndex

                // 拖动手势：拖动过程中仅更新指示器视觉位置（不触发导航/加载），
                // 松手后才切换到最近的标签——避免服务器模式在拖动中反复触发加载导致卡顿。
                val dragModifier = Modifier.pointerInput(items.size, itemWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / itemWidthPx - 0.5f)
                                .coerceIn(0f, lastIndex.toFloat())
                        },
                        onDragEnd = {
                            val nearest = (dragFraction ?: selectedIndex.toFloat())
                                .roundToInt().coerceIn(0, lastIndex)
                            dragFraction = null
                            if (items[nearest].route != selectedRoute) {
                                onItemSelected(items[nearest])
                            }
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / itemWidthPx - 0.5f)
                                .coerceIn(0f, lastIndex.toFloat())
                        }
                    )
                }

                SlidingIndicator(
                    selectedIndex = selectedIndex,
                    dragFraction = dragFraction,
                    itemWidth = itemWidth,
                    dark = dark
                )
                BarRow(
                    items = items,
                    selectedIndex = selectedIndex,
                    onItemSelected = onItemSelected,
                    dark = dark,
                    modifier = dragModifier
                )
            }
        }
    }
}

/** 外层磨砂玻璃胶囊：高浓度半透明底色 + 柔和投影 + 顶部高光描边。 */
@Composable
private fun GlassPill(dark: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    // 用高浓度半透明渐变压低背景细节，形成磨砂玻璃的乳化质感。
    val fill = if (dark) {
        Brush.verticalGradient(
            listOf(Color(0xE632353C), Color(0xD925282E))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xF7FFFFFF), Color(0xE6F1F3F6))
        )
    }
    val borderBrush = if (dark) {
        Brush.verticalGradient(listOf(Color(0x80FFFFFF), Color(0x1FFFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0x29000000)))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (dark) 14.dp else 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.26f),
                spotColor = Color.Black.copy(alpha = 0.26f)
            )
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, borderBrush, shape)
    ) {
        content()
    }
}

/**
 * 液态滑动指示器：左右两边分别用不同刚度的弹簧动画。
 * 切换时前导边先动、后随边慢动，中途胶囊被“拉长”，到位后回弹收拢，形成液态形变。
 */
@Composable
private fun SlidingIndicator(
    selectedIndex: Int,
    dragFraction: Float?,
    itemWidth: Dp,
    dark: Boolean,
) {
    // 拖动时用连续位置直接跟随手指，松手后回落到选中标签。
    val position = dragFraction ?: selectedIndex.toFloat()
    val targetLeft = itemWidth * position + IndicatorInset
    val targetRight = targetLeft + itemWidth - IndicatorInset * 2
    val isDragging = dragFraction != null

    // 拖动时直接取原始位置逐帧跟手（零动画延迟，最高帧率）；
    // 松手切换时才启用错峰弹簧形成液态拉伸。
    val animatedLeft by animateDpAsState(
        targetValue = targetLeft,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "indicatorLeft"
    )
    val animatedRight by animateDpAsState(
        targetValue = targetRight,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "indicatorRight"
    )
    val leftEdge = if (isDragging) targetLeft else animatedLeft
    val rightEdge = if (isDragging) targetRight else animatedRight

    val indicatorFill = if (dark) {
        Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
            )
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
            )
        )
    }
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.30f else 0.18f)

    Box(
        modifier = Modifier
            .offset(x = leftEdge)
            .width((rightEdge - leftEdge).coerceAtLeast(0.dp))
            .fillMaxHeight()
            .padding(vertical = IndicatorInset + 2.dp)
            .shadow(10.dp, RoundedCornerShape(IndicatorCorner), clip = false, spotColor = glow, ambientColor = glow)
            .clip(RoundedCornerShape(IndicatorCorner))
            .background(indicatorFill)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.5f else 0.35f),
                RoundedCornerShape(IndicatorCorner)
            )
    )
}

@Composable
private fun BarRow(
    items: List<BottomItem>,
    selectedIndex: Int,
    onItemSelected: (BottomItem) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().fillMaxHeight(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, item ->
            BarItem(
                item = item,
                selected = index == selectedIndex,
                dark = dark,
                onClick = { onItemSelected(item) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BarItem(
    item: BottomItem,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dark) 0.85f else 1f)

    val contentColor by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "itemColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.14f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "iconScale"
    )
    val liftUp by animateDpAsState(
        targetValue = if (selected) (-2).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "iconLift"
    )

    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            // 显式设置 contentDescription，TalkBack 朗读一次即可（覆盖子节点的 text）
            .semantics { contentDescription = item.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .offset(y = liftUp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .size(24.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = item.label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
