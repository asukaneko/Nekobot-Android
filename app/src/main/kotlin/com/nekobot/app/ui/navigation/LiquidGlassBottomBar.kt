package com.nekobot.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlinx.coroutines.launch
import kotlin.math.abs
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
 * 指示器的位置由「左右边缘的连续位置」两个 State 描述，且只在这些 State 的绘制阶段
 * （见 `SlidingIndicator` 的 graphicsLayer）读取：拖动时逐帧写入既不会触发重组，
 * 也不会触发重新布局，因此滑块可以严格跟手；松手后从手指所在位置直接弹到最近的标签
 * （不会先弹回旧标签再慢半拍地追过去）。
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

    // 手势是否正在拖动（拖动期间指示器位置完全交给手指，收敛动画让位）。
    var dragging by remember { mutableStateOf(false) }

    // 按压 / 拖动时玻璃进入“液态”：变透明、轻微放大、折射增强（iOS 手感）。
    // 5 个标签共用同一个交互源，任一标签被按下都算“正在交互”。
    val barInteraction = remember { MutableInteractionSource() }
    val pressed by barInteraction.collectIsPressedAsState()
    val liquidProgress = animateFloatAsState(
        targetValue = if (pressed || dragging) 1f else 0f,
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
                // 指示器左右各内缩 IndicatorInset，换算成「标签宽度」的比例。
                val insetFraction = if (itemWidthPx > 0f) {
                    with(density) { IndicatorInset.toPx() } / itemWidthPx
                } else {
                    0f
                }

                // 指示器左右边缘的连续位置（单位：标签宽度）。整数值 n 表示停在标签 n 的槽位，
                // 0.5 的整数偏移表示正处于两个标签之间。
                val leftEdge = remember { mutableFloatStateOf(selectedIndex + insetFraction) }
                val rightEdge = remember { mutableFloatStateOf(selectedIndex + 1f - insetFraction) }

                // 每次「手指接管 / 弹性收敛」自增：让仍在运行的旧收敛动画立即失效，
                // 避免它与手指抢位置（连续快速拖动时不会抖动）。
                val motionGen = remember { mutableIntStateOf(0) }
                // 指示器要弹性收敛到的标签；seq 用于反复触发收敛（即使目标标签没变也要弹回槽位）。
                val settleTarget = remember { mutableIntStateOf(selectedIndex) }
                val settleSeq = remember { mutableIntStateOf(0) }

                // 手势/动画回调里一律读取最新值，避免闭包捕获到过期的选中项或回调。
                val currentIndex by rememberUpdatedState(selectedIndex)
                val currentItems by rememberUpdatedState(items)
                val currentOnSelect by rememberUpdatedState(onItemSelected)
                val haptics = LocalHapticFeedback.current

                // 收敛动画：左右两条边用不同刚度的弹簧，移动途中先拉伸再回弹（液态形变）。
                // 动画只写 leftEdge / rightEdge，而它们只在绘制阶段被读取，因此逐帧动画不重组。
                LaunchedEffect(settleSeq.intValue) {
                    if (settleSeq.intValue == 0) return@LaunchedEffect
                    val gen = motionGen.intValue
                    val target = settleTarget.intValue
                    launch {
                        animate(
                            typeConverter = Float.VectorConverter,
                            initialValue = leftEdge.floatValue,
                            targetValue = target + insetFraction,
                            animationSpec = spring(
                                dampingRatio = 0.72f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { value, _ ->
                            if (motionGen.intValue == gen) leftEdge.floatValue = value
                        }
                    }
                    launch {
                        animate(
                            typeConverter = Float.VectorConverter,
                            initialValue = rightEdge.floatValue,
                            targetValue = target + 1f - insetFraction,
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessLow
                            )
                        ) { value, _ ->
                            if (motionGen.intValue == gen) rightEdge.floatValue = value
                        }
                    }
                }

                // 选中项被外部改变（点击标签、左右滑动分页）时，指示器平滑跟随。
                // 拖动过程中不跟：位置归手指，松手时由手势自己收敛。
                LaunchedEffect(selectedIndex) {
                    if (!dragging && settleTarget.intValue != selectedIndex) {
                        settleTarget.intValue = selectedIndex
                        settleSeq.intValue++
                    }
                }

                // 拖动手势：越过 touch slop 后由手指绝对接管指示器（中心即手指，无插值延迟），
                // 松手后收敛到最近的标签并切换页面。
                // 手势跑在 Initial 阶段：普通点击完全不消费事件（照旧由标签自己的 selectable 处理），
                // 一旦判定为拖动就吃掉后续事件，保证「拖完松手」不会再额外触发一次标签点击。
                val dragModifier = Modifier.pointerInput(items.size, itemWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        val downX = down.position.x
                        val slop = viewConfiguration.touchSlop
                        var active = false
                        var fraction = currentIndex.toFloat()

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // 抬起：拖动中必须吃掉它，否则原标签会再触发一次点击
                                    if (active) change.consume()
                                    break
                                }
                                if (!active && abs(change.position.x - downX) > slop) {
                                    active = true
                                    dragging = true
                                    motionGen.intValue++ // 正在收敛的动画立即让位给手指
                                }
                                if (active) {
                                    change.consume()
                                    fraction = (change.position.x / itemWidthPx - 0.5f)
                                        .coerceIn(0f, lastIndex.toFloat())
                                    leftEdge.floatValue = fraction + insetFraction
                                    rightEdge.floatValue = fraction + 1f - insetFraction
                                }
                            }
                        } finally {
                            // 手势被系统/重组打断时也要收尾，避免玻璃卡在「液态」状态。
                            if (active) {
                                dragging = false
                                motionGen.intValue++
                                val target = fraction.roundToInt().coerceIn(0, lastIndex)
                                // 先落位、再切换页面：即使分页器有延迟，滑块也立刻收在目标上，
                                // 不会先弹回旧标签再慢半拍地追过去。
                                settleTarget.intValue = target
                                settleSeq.intValue++
                                if (target != currentIndex) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnSelect(currentItems[target])
                                }
                            }
                        }
                    }
                }

                SlidingIndicator(
                    leftEdge = leftEdge,
                    rightEdge = rightEdge,
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
 *
 * 布局尺寸固定（一个标签宽减掉内缩），实际位置与拉伸全部由 graphicsLayer 的
 * translationX / scaleX 表达，并在绘制阶段读取 [leftEdge] / [rightEdge]：
 * 拖动逐帧更新只失效这一层，不重组、不重新布局，所以跟手且不掉帧。
 */
@Composable
private fun SlidingIndicator(
    leftEdge: FloatState,
    rightEdge: FloatState,
    itemWidth: Dp,
    dark: Boolean,
    backdrop: GlassBackdrop?,
    liquidProgress: () -> Float,
) {
    val slotWidth = (itemWidth - IndicatorInset * 2).coerceAtLeast(1.dp)

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
            .width(slotWidth)
            .fillMaxHeight()
            .graphicsLayer {
                // 连续位置在这里才被读取：拖动时每帧写 State 只会让这一层失效。
                val itemWidthPx = itemWidth.toPx()
                val leftPx = leftEdge.floatValue * itemWidthPx
                val widthPx = ((rightEdge.floatValue - leftEdge.floatValue) * itemWidthPx)
                    .coerceAtLeast(1f)
                translationX = leftPx
                transformOrigin = TransformOrigin(0f, 0.5f)
                // 两条边错峰运动时宽度会短暂变化，这里用横向缩放表达液态拉伸。
                scaleX = widthPx / slotWidth.toPx()
            }
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
