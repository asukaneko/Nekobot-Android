package com.nekobot.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.rememberShouldUseNavRail
import com.nekobot.app.ui.adaptive.rememberWindowWidthClass

/**
 * 底栏在三种窗口宽度下的几何规格。
 *
 * 手机保持紧凑的一行图标 + 文字；平板放大高度、图标与字号，并把文字从「图标下方」
 * 改为「与图标并排」，同时给内容一个最大宽度后居中，避免在宽屏上被拉成又长又扁的一条。
 */
internal data class BottomBarLayout(
    /** 栏内容高度（不含上下留白与系统导航栏） */
    val barHeight: Dp,
    /** 到屏幕左右边缘的留白 */
    val horizontalPadding: Dp,
    /** 上下留白 */
    val verticalPadding: Dp,
    /** 内容最大宽度；null 表示铺满可用宽度 */
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
     * 底栏（含上下留白）在系统导航栏之上占据的总高度。
     * 页面内容的底部留白按它推算，避免最后一行被底栏遮挡。
     */
    val clearance: Dp get() = barHeight + verticalPadding * 2

    /** 选中指示器的圆角；等于 [barHeight] 一半时是完整胶囊。 */
    val pillCorner: Dp get() = barHeight / 2
}

/** 手机：紧凑形态（64dp、24dp 图标、文字在图标下方）。 */
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

/** 中等宽度（600~839dp，折叠屏展开 / 小平板）：放大并把内容限宽居中。 */
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

/** 大屏（≥840dp，平板横竖屏）：进一步放大，图标与文字并排。 */
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
 * 当前形态下底部导航栏（含上下边距）占据的总高度。
 *
 * 平板形态改用侧边导航栏（见 `NekoNavRail`），底栏不再显示，因此返回 0：
 * 嵌入双栏的聊天输入区不必再为底栏抬升。
 */
@Composable
fun rememberBottomBarClearance(): Dp =
    if (rememberShouldUseNavRail()) 0.dp else bottomBarLayout().clearance

/** 紧凑（手机）布局下的避让高度基线：64 + 10 * 2 = 84dp，供非 Composable 场景兜底。 */
val NekoBottomBarClearance: Dp = CompactBottomBarLayout.clearance

/**
 * 侧边导航栏在两种平板宽度下的几何规格。
 *
 * 侧栏是竖排面板（非长条），宽度明显窄于底栏，因此单列固定几何即可：
 * 每个标签固定高度、图标在上文字在下，整体面板在垂直方向居中。
 */
internal data class NavRailLayout(
    /** 面板本身的宽度 */
    val railWidth: Dp,
    /** 面板到屏幕左边缘的留白 */
    val horizontalPadding: Dp,
    /** 面板到屏幕上下边的留白（面板高度不足整屏时用于居中留空） */
    val verticalPadding: Dp,
    /** 单个标签的槽位高度 */
    val itemHeight: Dp,
    val iconSize: Dp,
    /** 图标与文字之间的间距 */
    val iconTextGap: Dp,
    /** 选中指示器相对槽位的内缩 */
    val indicatorInset: Dp,
    val indicatorCorner: Dp,
    /** 面板圆角 */
    val corner: Dp,
    /** 标签是否使用更大的字号（大屏用） */
    val largerLabel: Boolean,
) {
    /** 侧栏在屏幕左侧占据的总宽度；主界面内容需按它向右避让。 */
    val clearance: Dp get() = railWidth + horizontalPadding * 2

    /** 多个标签竖直排布时面板的自然高度。 */
    fun panelHeightFor(itemCount: Int): Dp = itemHeight * itemCount
}

/** 中等宽度（600~839dp，折叠屏展开 / 小平板）：贴近 Material 侧栏的窄面板。 */
private val MediumNavRailLayout = NavRailLayout(
    railWidth = 68.dp,
    horizontalPadding = 8.dp,
    verticalPadding = 12.dp,
    itemHeight = 64.dp,
    iconSize = 24.dp,
    iconTextGap = 4.dp,
    indicatorInset = 6.dp,
    indicatorCorner = 18.dp,
    corner = 24.dp,
    largerLabel = false,
)

/** 大屏（≥840dp，平板横竖屏）：面板与图标同步放大，标签更易读。 */
private val ExpandedNavRailLayout = NavRailLayout(
    railWidth = 76.dp,
    horizontalPadding = 10.dp,
    verticalPadding = 14.dp,
    itemHeight = 72.dp,
    iconSize = 28.dp,
    iconTextGap = 6.dp,
    indicatorInset = 8.dp,
    indicatorCorner = 22.dp,
    corner = 28.dp,
    largerLabel = true,
)

/**
 * 纯函数：按窗口宽度断点取侧栏规格（供单元测试与 Composable 共用）。
 * 手机（Compact）不会使用侧栏，这里返回最紧凑规格兜底。
 */
internal fun navRailLayoutFor(widthClass: WindowWidthClass): NavRailLayout = when (widthClass) {
    WindowWidthClass.Compact -> MediumNavRailLayout
    WindowWidthClass.Medium -> MediumNavRailLayout
    WindowWidthClass.Expanded -> ExpandedNavRailLayout
}

/** 当前窗口宽度对应的侧栏规格。 */
@Composable
internal fun navRailLayout(): NavRailLayout = navRailLayoutFor(rememberWindowWidthClass())

/**
 * 当前形态下主界面左侧需要为侧栏预留的宽度。
 *
 * 手机（或不够高的宽窗口）不使用侧栏，返回 0；侧栏形态下返回 [NavRailLayout.clearance]。
 * 主界面内容按它向右避让，详情页不避让（仍为全屏）。
 */
@Composable
fun rememberNavRailClearance(): Dp =
    if (rememberShouldUseNavRail()) navRailLayout().clearance else 0.dp
