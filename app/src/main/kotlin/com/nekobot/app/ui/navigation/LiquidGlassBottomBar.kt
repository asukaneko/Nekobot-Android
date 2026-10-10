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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.rememberShouldUseNavRail
import com.nekobot.app.ui.adaptive.rememberWindowWidthClass
import com.nekobot.app.ui.components.GlassBackdrop
import com.nekobot.app.ui.components.GlassPane
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底栏在三种窗口宽度下的几何规格。
 *
 * 手机（Compact）保持原有的紧凑胶囊；平板（Medium / Expanded）放大高度、图标与字号，
 * 并把文字从「图标下方」改为「与图标并排」，同时给胶囊一个最大宽度后居中，
 * 避免在宽屏上被拉成又长又扁、图标小得可怜的手机版底栏。
 */
internal data class BottomBarLayout(
    /** 胶囊内部高度（不含上下留白） */
    val barHeight: Dp,
    /** 胶囊到屏幕左右边缘的留白 */
    val horizontalPadding: Dp,
    /** 胶囊到屏幕上下边的留白 */
    val verticalPadding: Dp,
    /** 胶囊最大宽度；null 表示铺满可用宽度（手机不分栏时的原有行为） */
    val pillMaxWidth: Dp?,
    val iconSize: Dp,
    /** true：图标与文字并排（平板）；false：图标在上、文字在下（手机） */
    val labelBesideIcon: Boolean,
    /** 并排时图标与文字之间的间距 */
    val iconTextGap: Dp,
    /** 选中指示器相对槽位的内缩 */
    val indicatorInset: Dp,
    val indicatorCorner: Dp,
) {
    /**
     * 悬浮底栏（含上下边距）在系统导航栏之上占据的总高度。
     * 平板双栏等场景下，嵌入内容底部需要预留该高度，避免被底栏胶囊遮挡。
     */
    val clearance: Dp get() = barHeight + verticalPadding * 2

    /** 外层胶囊的圆角；等于 [barHeight] 一半，即完整胶囊。 */
    val pillCorner: Dp get() = barHeight / 2
}

/** 手机：原有紧凑形态（64dp 胶囊、24dp 图标、文字在图标下方）。 */
private val CompactBottomBarLayout = BottomBarLayout(
    barHeight = 64.dp,
    horizontalPadding = 16.dp,
    verticalPadding = 10.dp,
    pillMaxWidth = null,
    iconSize = 24.dp,
    labelBesideIcon = false,
    iconTextGap = 3.dp,
    indicatorInset = 6.dp,
    indicatorCorner = 22.dp,
)

/** 中等宽度（600~839dp，折叠屏展开 / 小平板）：放大胶囊并限制宽度居中。 */
private val MediumBottomBarLayout = BottomBarLayout(
    barHeight = 72.dp,
    horizontalPadding = 24.dp,
    verticalPadding = 12.dp,
    pillMaxWidth = 600.dp,
    iconSize = 26.dp,
    labelBesideIcon = true,
    iconTextGap = 8.dp,
    indicatorInset = 8.dp,
    indicatorCorner = 24.dp,
)

/** 大屏（≥840dp，平板横竖屏）：进一步放大，图标与文字并排的宽胶囊居中。 */
private val ExpandedBottomBarLayout = BottomBarLayout(
    barHeight = 80.dp,
    horizontalPadding = 32.dp,
    verticalPadding = 14.dp,
    pillMaxWidth = 760.dp,
    iconSize = 30.dp,
    labelBesideIcon = true,
    iconTextGap = 10.dp,
    indicatorInset = 8.dp,
    indicatorCorner = 26.dp,
)

/** 纯函数：按窗口宽度断点取底栏规格（供单元测试与 Composable 共用）。 */
internal fun bottomBarLayoutFor(widthClass: WindowWidthClass): BottomBarLayout = when (widthClass) {
    WindowWidthClass.Compact -> CompactBottomBarLayout
    WindowWidthClass.Medium -> MediumBottomBarLayout
    WindowWidthClass.Expanded -> ExpandedBottomBarLayout
}

/** 当前窗口宽度对应的底栏规格。 */
@Composable
internal fun bottomBarLayout(): BottomBarLayout = bottomBarLayoutFor(rememberWindowWidthClass())

/**
 * 当前形态下悬浮底部导航栏（含上下边距）占据的总高度。
 *
 * 平板形态改用侧边导航栏（见 `LiquidGlassNavRail`），底栏不再显示，因此这里返回 0：
 * 嵌入双栏的聊天输入区不必再为底栏抬升，可以直接铺到屏幕底部。
 * 手机（含手机横屏）仍返回底栏高度，嵌入内容必须按这个值避让，否则会被胶囊遮住。
 */
@Composable
fun rememberLiquidGlassBottomBarClearance(): Dp =
    if (rememberShouldUseNavRail()) 0.dp else bottomBarLayout().clearance

/** 紧凑（手机）布局下的避让高度基线：64 + 10 * 2 = 84dp，供非 Composable 场景兜底。 */
val LiquidGlassBottomBarClearance: Dp = CompactBottomBarLayout.clearance

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
 *
 * 大屏适配：胶囊高度、图标尺寸与标签字号按 [BottomBarLayout] 随窗口宽度放大（手机 64dp /
 * 平板 72~80dp），标签从「图标下方」改为「图标右侧并排」，并给胶囊设置最大宽度后居中，
 * 避免平板下底栏仍是手机那套紧凑布局、被拉伸成又长又扁的一条。
 */
@Composable
fun LiquidGlassBottomBar(
    items: List<BottomItem>,
    selectedRoute: String?,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    if (items.isEmpty()) return
    val dark = isSystemInDarkTheme()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val density = LocalDensity.current
    // 手机 / 平板使用不同的几何规格：平板放大胶囊、图标与字号，并居中限宽。
    val layout = bottomBarLayout()
    val maxPillWidth = layout.pillMaxWidth

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
    val liquid: () -> Float = { liquidProgress.value.coerceIn(0f, 1f) }
    val touchPosition = remember { mutableStateOf<Offset?>(null) }
    val tabsBackdrop = if (backdrop != null) rememberLayerBackdrop() else null
    val indicatorBackdrop = if (backdrop != null && tabsBackdrop != null) {
        rememberCombinedBackdrop(backdrop, tabsBackdrop)
    } else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
        // 平板下胶囊被限宽，居中悬浮，两侧留白不再被拉伸的标签槽位吃掉。
        contentAlignment = Alignment.Center
    ) {
        NavGlassSurface(
            dark = dark,
            backdrop = backdrop,
            corner = layout.pillCorner,
            liquidProgress = liquid,
            touchPosition = { touchPosition.value },
            modifier = if (maxPillWidth != null) {
                Modifier.widthIn(max = maxPillWidth).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.barHeight)
            ) {
                val itemWidth: Dp = maxWidth / items.size
                val itemWidthPx = with(density) { itemWidth.toPx() }
                val lastIndex = items.lastIndex
                // 指示器左右各内缩 indicatorInset，换算成「标签宽度」的比例。
                val insetFraction = if (itemWidthPx > 0f) {
                    with(density) { layout.indicatorInset.toPx() } / itemWidthPx
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
                        touchPosition.value = down.position
                        val slop = viewConfiguration.touchSlop
                        var active = false
                        var fraction = currentIndex.toFloat()

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                touchPosition.value = change.position
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

                // 静态回退的指示器在文字后面；真实透镜覆盖标签，从组合采样里绘制标签副本。
                if (indicatorBackdrop == null) {
                    SlidingIndicator(
                        leftEdge, rightEdge, itemWidth, dark, null, liquid,
                        layout.indicatorInset, layout.indicatorCorner,
                    )
                }
                BarRow(
                    items = items,
                    selectedIndex = selectedIndex,
                    onItemSelected = onItemSelected,
                    dark = dark,
                    interactionSource = barInteraction,
                    layout = layout,
                    modifier = dragModifier,
                    animateSelection = backdrop == null,
                )
                if (backdrop != null && tabsBackdrop != null) {
                    // 与上游 LiquidBottomTabs 一样：隐藏副本仍录入图层，供透镜采样。
                    // 副本没有点击节点或无障碍语义，避免重复标签抢走真实标签的交互。
                    Box(
                        Modifier.matchParentSize()
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .layerBackdrop(tabsBackdrop)
                    ) {
                        GlassPane(
                            backdrop = backdrop,
                            cornerRadius = layout.pillCorner,
                            modifier = Modifier.matchParentSize(),
                            blur = 8.dp,
                            refraction = 12.dp,
                            tint = if (dark) Color(0x33101012) else Color(0x1FFFFFFF),
                            innerShadowAlpha = if (dark) 0.10f else 0.05f,
                            liquidProgress = liquid,
                        )
                        BarRow(
                            items = items,
                            selectedIndex = selectedIndex,
                            onItemSelected = {},
                            dark = dark,
                            interactionSource = barInteraction,
                            layout = layout,
                            interactive = false,
                            animateSelection = false,
                            forceActiveColor = true,
                            contentScale = { 1f + 0.2f * liquid() },
                        )
                    }
                    SlidingIndicator(
                        leftEdge, rightEdge, itemWidth, dark, indicatorBackdrop, liquid,
                        layout.indicatorInset, layout.indicatorCorner,
                    )
                }
            }
        }
    }
}

/**
 * 外层玻璃面板：真玻璃模式下采样下层页面内容（模糊 + 折射 + 淡染色 + 高光），
 * 回退模式下则是高浓度半透明底色 + 柔和投影 + 顶部高光描边。
 *
 * 底栏胶囊与侧边导航栏面板共用（仅圆角、尺寸与 [tint] 不同）。
 *
 * @param tint 玻璃底色。默认是极淡的中性染色（底栏不混主题色，保持透明玻璃观感）；
 *   侧栏下方始终是纯背景色（主界面内容已向右避让，采样无从发挥），
 *   因此侧栏传入主题 surface 的淡染色，让大面积面板在纯色背景上仍有明确层次。
 */
@Composable
internal fun NavGlassSurface(
    dark: Boolean,
    backdrop: GlassBackdrop?,
    corner: Dp,
    liquidProgress: () -> Float,
    modifier: Modifier = Modifier,
    tint: Color = if (dark) Color(0x33101012) else Color(0x1FFFFFFF),
    touchPosition: () -> Offset? = { null },
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
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
        modifier = modifier
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
                cornerRadius = corner,
                modifier = Modifier.matchParentSize(),
                blur = 8.dp,
                saturation = 1.25f,
                // 折射深度（12dp）小于胶囊到屏幕边缘的留白（手机 16dp，平板更大），
                // 保证边缘取样不会越过屏幕边界而取到空像素。
                refraction = 12.dp,
                // 色散会在边缘产生彩色描边，这里关闭，保持干净的透明玻璃。
                dispersion = 0f,
                tint = tint,
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
        if (backdrop != null) {
            Box(Modifier.matchParentSize().drawBehind {
                val position = touchPosition() ?: return@drawBehind
                val progress = liquidProgress()
                if (progress <= 0f) return@drawBehind
                val radius = 140.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.14f * progress), Color.Transparent),
                        center = position,
                        radius = radius,
                    ),
                    radius = radius,
                    center = position,
                )
            })
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
    inset: Dp,
    corner: Dp,
) {
    val slotWidth = (itemWidth - inset * 2).coerceAtLeast(1.dp)

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
                scaleX = if (backdrop == null) widthPx / slotWidth.toPx() else 1f
            }
            .padding(vertical = inset + 2.dp)
    ) {
        if (backdrop != null) {
            // 透镜使用上游的光学效果，直接采样页面与放大标签的组合图层。
            Box(
                Modifier.matchParentSize().drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(corner) },
                    effects = {
                        val progress = liquidProgress()
                        lens(
                            10.dp.toPx() * progress,
                            14.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = { Highlight.Default.copy(alpha = liquidProgress()) },
                    shadow = { Shadow(radius = 8.dp, alpha = liquidProgress()) },
                    innerShadow = {
                        InnerShadow(radius = 8.dp, alpha = liquidProgress())
                    },
                    layerBlock = {
                        val progress = liquidProgress()
                        val stretch = ((rightEdge.floatValue - leftEdge.floatValue) *
                            itemWidth.toPx() / size.width).coerceAtLeast(0.01f)
                        // 形变交给库的图层，库会反向补偿采样坐标，避免标签随拉伸错位。
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = stretch * (1f + 0.12f * progress)
                        scaleY = 1f + 0.20f * progress
                        translationX = -size.width * stretch * 0.06f * progress
                    },
                    onDrawSurface = {
                        drawRect(
                            if (dark) Color.White.copy(alpha = 0.10f)
                            else Color.Black.copy(alpha = 0.10f),
                            alpha = 1f - liquidProgress(),
                        )
                    },
                )
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .shadow(
                        10.dp,
                        RoundedCornerShape(corner),
                        clip = false,
                        spotColor = glow,
                        ambientColor = glow
                    )
                    .clip(RoundedCornerShape(corner))
                    .background(indicatorFill)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.5f else 0.35f),
                        RoundedCornerShape(corner)
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
    layout: BottomBarLayout,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    animateSelection: Boolean = true,
    forceActiveColor: Boolean = false,
    contentScale: () -> Float = { 1f },
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
                layout = layout,
                interactionSource = interactionSource,
                onClick = { onItemSelected(item) },
                modifier = Modifier.weight(1f)
                    .graphicsLayer {
                        val scale = contentScale()
                        scaleX = scale
                        scaleY = scale
                    },
                interactive = interactive,
                animateSelection = animateSelection,
                forceActiveColor = forceActiveColor,
            )
        }
    }
}

@Composable
private fun BarItem(
    item: BottomItem,
    selected: Boolean,
    dark: Boolean,
    layout: BottomBarLayout,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    animateSelection: Boolean = true,
    forceActiveColor: Boolean = false,
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
        targetValue = if (selected || forceActiveColor) activeColor else inactiveColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "itemColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected && animateSelection) 1.14f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "iconScale"
    )
    val liftUp by animateDpAsState(
        targetValue = if (selected && animateSelection) (-2).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "iconLift"
    )

    // 图标与文字分别抽出：手机上下堆叠，平板并排，两者的动效与样式完全一致。
    val iconSlot: @Composable (Modifier) -> Unit = { m ->
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = m
                .offset(y = liftUp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .size(layout.iconSize)
        )
    }
    val labelSlot: @Composable () -> Unit = {
        Text(
            text = item.label,
            color = contentColor,
            style = if (layout.labelBesideIcon) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.labelSmall
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }

    val itemModifier = modifier
        .fillMaxHeight()
        .then(
            if (interactive) Modifier.selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onTabClick,
            ).semantics { contentDescription = item.label } else Modifier
        )

    if (layout.labelBesideIcon) {
        // 平板：图标与文字并排，胶囊更矮胖、标签更易读。
        Row(
            modifier = itemModifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconSlot(Modifier)
            Spacer(Modifier.width(layout.iconTextGap))
            labelSlot()
        }
    } else {
        Column(
            modifier = itemModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            iconSlot(Modifier)
            Spacer(Modifier.height(layout.iconTextGap))
            labelSlot()
        }
    }
}
