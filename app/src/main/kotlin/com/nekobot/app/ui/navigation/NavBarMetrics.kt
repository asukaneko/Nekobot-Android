package com.nekobot.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.rememberShouldUseNavDrawer
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
 * 平板形态改用左侧抽屉导航（见 `NekoNavDrawer`），底栏不再显示，因此返回 0：
 * 嵌入双栏的聊天输入区不必再为底栏抬升。
 */
@Composable
fun rememberBottomBarClearance(): Dp =
    if (rememberShouldUseNavDrawer()) 0.dp else bottomBarLayout().clearance

/** 紧凑（手机）布局下的避让高度基线：64 + 10 * 2 = 84dp，供非 Composable 场景兜底。 */
val NekoBottomBarClearance: Dp = CompactBottomBarLayout.clearance

/**
 * 左侧抽屉在两种平板宽度下的几何规格。
 *
 * 抽屉是贴着屏幕左边缘、从上到下的整条面板（展开态）：
 * 顶部一行标题，下面一列「图标 + 文字」导航项，选中项用强调色浅底圆角块。
 * 收起态退化为只留图标的窄轨，两种宽度共用同一套语义，只是尺寸不同。
 */
internal data class NavDrawerLayout(
    /** 展开态面板宽度 */
    val drawerWidth: Dp,
    /** 收起态窄轨宽度 */
    val railWidth: Dp,
    /** 顶部标题行高度 */
    val headerHeight: Dp,
    /** 单个导航项高度 */
    val itemHeight: Dp,
    /** 导航项（选中底块）圆角 */
    val itemCorner: Dp,
    /** 面板左右内留白 */
    val drawerPadding: Dp,
    /** 导航项内部左右留白 */
    val itemPadding: Dp,
    val iconSize: Dp,
    /** 展开态图标与文字的间距 */
    val labelGap: Dp,
    /** 收起态图标与文字的间距 */
    val railLabelGap: Dp,
    /** 导航项文字是否使用更大字号（大屏用） */
    val largerLabel: Boolean,
    /** 相邻导航项的竖直间距 */
    val itemSpacing: Dp,
) {
    /** 当前展开状态下面板宽度；主界面内容按它向右避让。 */
    fun widthFor(expanded: Boolean): Dp = if (expanded) drawerWidth else railWidth

    /** 全部导航项竖排所需高度（不含标题行）。 */
    fun itemsHeightFor(itemCount: Int): Dp =
        itemHeight * itemCount + itemSpacing * (itemCount - 1).coerceAtLeast(0)
}

/** 中等宽度（600~839dp，折叠屏展开 / 小平板）：抽屉略窄，给内容留出更多空间。 */
private val MediumNavDrawerLayout = NavDrawerLayout(
    drawerWidth = 236.dp,
    railWidth = 68.dp,
    headerHeight = 60.dp,
    itemHeight = 46.dp,
    itemCorner = 12.dp,
    drawerPadding = 10.dp,
    itemPadding = 12.dp,
    iconSize = 22.dp,
    labelGap = 12.dp,
    railLabelGap = 3.dp,
    largerLabel = false,
    itemSpacing = 2.dp,
)

/** 大屏（≥840dp，平板横竖屏）：抽屉、图标与文字同步放大，更易读。 */
private val ExpandedNavDrawerLayout = NavDrawerLayout(
    drawerWidth = 264.dp,
    railWidth = 76.dp,
    headerHeight = 68.dp,
    itemHeight = 50.dp,
    itemCorner = 14.dp,
    drawerPadding = 12.dp,
    itemPadding = 14.dp,
    iconSize = 24.dp,
    labelGap = 14.dp,
    railLabelGap = 4.dp,
    largerLabel = true,
    itemSpacing = 2.dp,
)

/**
 * 纯函数：按窗口宽度断点取抽屉规格（供单元测试与 Composable 共用）。
 * 手机（Compact）不会使用抽屉，这里返回最紧凑规格兜底。
 */
internal fun navDrawerLayoutFor(widthClass: WindowWidthClass): NavDrawerLayout = when (widthClass) {
    WindowWidthClass.Compact -> MediumNavDrawerLayout
    WindowWidthClass.Medium -> MediumNavDrawerLayout
    WindowWidthClass.Expanded -> ExpandedNavDrawerLayout
}

/** 当前窗口宽度对应的抽屉规格。 */
@Composable
internal fun navDrawerLayout(): NavDrawerLayout = navDrawerLayoutFor(rememberWindowWidthClass())
