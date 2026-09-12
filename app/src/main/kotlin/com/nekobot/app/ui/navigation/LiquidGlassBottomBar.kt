package com.nekobot.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.components.GlassBackdrop
import com.nekobot.app.ui.components.GlassPane
import kotlin.math.roundToInt

private val BarHeight = 64.dp
private val BarHorizontalPadding = 16.dp
private val BarVerticalPadding = 10.dp
private val IndicatorInset = 6.dp
private val IndicatorCorner = 22.dp

/** 外层胶囊的圆角；等于 [BarHeight] 一半，即完整胶囊。 */
private val PillCorner = BarHeight / 2

/**
 * 悬浮底栏（含上下边距）在系统导航栏之上占据的总高度：64 + 10 * 2 = 84dp。
 * 平板双栏等场景下，嵌入内容底部需要预留该高度，避免被底栏胶囊遮挡。
 */
val LiquidGlassBottomBarClearance: Dp = BarHeight + BarVerticalPadding * 2

/**
 * 苹果风格「圆岛」底部导航：悬浮的液态玻璃胶囊 + 在标签间平滑滚动切换的选中指示器。
 * 指示器的左右两条边采用不同刚度的弹簧，滑动过程中会短暂拉伸再回弹，营造液态形变效果。
 *
 * [backdrop] 不为 null 时使用真正的液态玻璃（采样并模糊下层页面内容 + 边缘折射 + 高光），
 * 由 `NekobotNavGraph` 在 API 31+ 且非低内存设备时注入；否则退回半透明渐变 + 高光描边的
 * 静态玻璃质感（兼容 API 26~30 与低内存设备）。
 */
@Composable
fun LiquidGlassBottomBar(
    items: List<BottomItem>,
    selectedRoute: String?,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    val dark = isSystemInDarkTheme()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val density = LocalDensity.current

    // 拖动状态：dragFraction 为连续的标签位置（如 2.4 表示在第 2、3 个标签之间），null 表示未拖动。
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    // 按压 / 拖动时玻璃进入“液态”：变透明、轻微放大、折射增强（iOS 手感）。
    // 5 个标签共用同一个交互源，任一标签被按下都算“正在交互”。
    val barInteraction = remember { MutableInteractionSource() }
    val pressed by barInteraction.collectIsPressedAsState()
    val liquidProgress = animateFloatAsState(
        targetValue = if (pressed || dragFraction != null) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "liquidProgress"
    )
    val liquid: () -> Float = { liquidProgress.value }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = BarHorizontalPadding, vertical = BarVerticalPadding)
    ) {
        GlassPill(dark = dark, backdrop = backdrop, liquidProgress = liquid) {
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
                    dark = dark,
                    backdrop = backdrop,
                    liquidProgress = liquid
                )
                BarRow(
                    items = items,
                    selectedIndex = selectedIndex,
                    onItemSelected = onItemSelected,
                    dark = dark,
                    interactionSource = barInteraction,
                    modifier = dragModifier
                )
            }
        }
    }
}

/**
 * 外层玻璃胶囊：真玻璃模式下采样下层页面内容（模糊 + 折射 + 极淡中性染色 + 高光），
 * 玻璃本身不混主题色，保持透明玻璃的观感；
 * 回退模式下则是高浓度半透明底色 + 柔和投影 + 顶部高光描边。
 */
@Composable
private fun GlassPill(
    dark: Boolean,
    backdrop: GlassBackdrop?,
    liquidProgress: () -> Float,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(PillCorner)
    // 回退样式：用高浓度半透明渐变压低背景细节，形成磨砂玻璃的乳化质感。
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
    ) {
        if (backdrop != null) {
            GlassPane(
                backdrop = backdrop,
                cornerRadius = PillCorner,
                modifier = Modifier.matchParentSize(),
                blur = 16.dp,
                saturation = 1.25f,
                // 折射深度（12dp）小于胶囊到屏幕边缘的留白（16dp），
                // 保证边缘取样不会越过屏幕边界而取到空像素。
                refraction = 12.dp,
                // 色散会在边缘产生彩色描边，这里关闭，保持干净的透明玻璃。
                dispersion = 0f,
                // 极淡的中性染色：只为图标可读性，不引入主题色。
                tint = if (dark) Color(0x33101012) else Color(0x1FFFFFFF),
                rimColors = if (dark) {
                    // 深色模式下白边要非常克制，否则整条胶囊像被镶了银边。
                    listOf(Color(0x24FFFFFF), Color(0x08FFFFFF))
                } else {
                    listOf(Color(0x99FFFFFF), Color(0x12000000))
                },
                innerShadowAlpha = if (dark) 0.10f else 0.05f,
                liquidProgress = liquidProgress,
            )
        } else {
            Box(modifier = Modifier.matchParentSize().background(fill))
            Box(modifier = Modifier.matchParentSize().border(1.dp, borderBrush, shape))
        }
        content()
    }
}

/**
 * 液态滑动指示器：左右两边分别用不同刚度的弹簧动画。
 * 切换时前导边先动、后随边慢动，中途胶囊被“拉长”，到位后回弹收拢，形成液态形变。
 * 有 [backdrop] 时指示器本身就是一块透明玻璃光斑（轻模糊 + 强折射），
 * 按压/拖动时会变得更透明、更大并放大折射背景——即 iOS 的“液态”手感。
 */
@Composable
private fun SlidingIndicator(
    selectedIndex: Int,
    dragFraction: Float?,
    itemWidth: Dp,
    dark: Boolean,
    backdrop: GlassBackdrop?,
    liquidProgress: () -> Float,
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
    ) {
        if (backdrop != null) {
            GlassPane(
                backdrop = backdrop,
                cornerRadius = IndicatorCorner,
                modifier = Modifier.matchParentSize(),
                // 轻模糊 + 强折射：选中项像一块正在放大背景的透明玻璃。
                blur = 4.dp,
                saturation = 1.15f,
                refraction = 10.dp,
                dispersion = 0f,
                // 静止时是一块均匀的中性深色（背光阴影感，不混主题色、不带渐变）；
                // 按压/拖动时完全透明（tintFade = 1f），只剩折射背景，即 iOS 的液态光斑。
                tint = if (dark) Color(0x40000000) else Color(0x1A000000),
                rimColors = if (dark) {
                    listOf(Color(0x2EFFFFFF), Color(0x0AFFFFFF))
                } else {
                    listOf(Color(0xB3FFFFFF), Color(0x14000000))
                },
                // 均匀阴影：不做顶部内阴影渐变
                innerShadowAlpha = 0f,
                liquidProgress = liquidProgress,
                // 按压/拖动时整块玻璃放大（连同折射背景一起放大，形成液态透镜感）。
                scaleBoost = 0.10f,
                tintFade = 1f,
                // 静止时描边很淡，交互时才亮起来
                rimRestAlpha = 0.35f,
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .shadow(
                        10.dp,
                        RoundedCornerShape(IndicatorCorner),
                        clip = false,
                        spotColor = glow,
                        ambientColor = glow
                    )
                    .clip(RoundedCornerShape(IndicatorCorner))
                    .background(indicatorFill)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.5f else 0.35f),
                        RoundedCornerShape(IndicatorCorner)
                    )
            )
        }
    }
}

@Composable
private fun BarRow(
    items: List<BottomItem>,
    selectedIndex: Int,
    onItemSelected: (BottomItem) -> Unit,
    dark: Boolean,
    interactionSource: MutableInteractionSource,
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
                interactionSource = interactionSource,
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
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dark) 0.85f else 1f)
    val haptics = LocalHapticFeedback.current

    // 轻微震动反馈：仅点击「不同」标签时触发，避免重复点击当前标签反复震动。
    val onTabClick = {
        if (!selected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onClick()
    }

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

    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onTabClick
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
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
