package com.nekobot.app.ui.navigation

import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.NavDrawerMinHeightDp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.shouldUseNavDrawer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试平板形态的左侧抽屉导航：
 * 形态判定（够宽 + 够高）、抽屉几何规格（大屏更大）、展开 / 收起宽度切换与内容避让。
 */
class NavDrawerLayoutTest {

    private val tabletWidthClasses = listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded)

    @Test
    fun phoneWindow_keepsBottomBar() {
        // 手机竖屏（411x891）
        assertFalse(shouldUseNavDrawer(widthDp = 411, heightDp = 891))
        // 手机横屏：够宽但过矮，竖直排 4 个导航项会挤，仍用底栏
        assertFalse(shouldUseNavDrawer(widthDp = 915, heightDp = 412))
        // 宽度不足的一般窗口
        assertFalse(shouldUseNavDrawer(widthDp = 599, heightDp = 1000))
    }

    @Test
    fun tabletWindow_usesNavDrawer() {
        // 平板竖屏 / 横屏，以及折叠屏展开
        assertTrue(shouldUseNavDrawer(widthDp = 800, heightDp = 1280))
        assertTrue(shouldUseNavDrawer(widthDp = 1280, heightDp = 800))
        assertTrue(shouldUseNavDrawer(widthDp = 673, heightDp = 841))
        // 边界：刚好达到宽度与高度门槛
        assertTrue(shouldUseNavDrawer(widthDp = 600, heightDp = NavDrawerMinHeightDp))
    }

    @Test
    fun widthFor_switchesBetweenExpandedAndCollapsed() {
        tabletWidthClasses.forEach { widthClass ->
            val layout = navDrawerLayoutFor(widthClass)
            assertEquals(layout.drawerWidth, layout.widthFor(expanded = true))
            assertEquals(layout.railWidth, layout.widthFor(expanded = false))
            // 展开态明显宽于收起态，收起态必须窄到只放得下图标
            assertTrue("$widthClass 展开 / 收起宽度没有差别", layout.drawerWidth > layout.railWidth)
            assertTrue("$widthClass 收起态过宽", layout.widthFor(expanded = false) <= 100.dp)
        }
    }

    @Test
    fun expandedDrawer_leavesEnoughContentWidth() {
        tabletWidthClasses.forEach { widthClass ->
            val layout = navDrawerLayoutFor(widthClass)
            // 抽屉不能吃掉太多内容宽度（折叠屏展开约 673dp 时仍要留出列表宽度）
            assertTrue("$widthClass 抽屉过宽", layout.drawerWidth in 200.dp..300.dp)
        }
    }

    @Test
    fun expandedDrawer_isLargerThanMedium() {
        val medium = navDrawerLayoutFor(WindowWidthClass.Medium)
        val expanded = navDrawerLayoutFor(WindowWidthClass.Expanded)
        assertTrue(expanded.drawerWidth > medium.drawerWidth)
        assertTrue(expanded.headerHeight > medium.headerHeight)
        assertTrue(expanded.itemHeight > medium.itemHeight)
        assertTrue(expanded.iconSize > medium.iconSize)
        assertTrue(expanded.largerLabel)
    }

    @Test
    fun navItemGeometry_isRoundedRectAndFitsIcon() {
        tabletWidthClasses.forEach { widthClass ->
            val layout = navDrawerLayoutFor(widthClass)
            // 导航项是圆角块，不是胶囊
            assertTrue("$widthClass 导航项圆角过大", layout.itemCorner < layout.itemHeight / 2)
            // 收起态去掉左右留白后要放得下图标
            assertTrue(
                "$widthClass 收起态放不下图标",
                layout.railWidth - layout.drawerPadding * 2 >= layout.iconSize
            )
            // 展开态：留白 + 图标 + 间距之后还要留出至少 4 个中文字的宽度
            val labelRoom = layout.drawerWidth - layout.drawerPadding * 2 -
                layout.itemPadding * 2 - layout.labelGap - layout.iconSize
            assertTrue("$widthClass 展开态文字放不下", labelRoom >= 64.dp)
        }
    }

    @Test
    fun itemsFitWithinMinimumDrawerHeight() {
        // 门槛高度下，标题行 + 全部导航项 + 上下留白必须仍然放得下
        tabletWidthClasses.forEach { widthClass ->
            val layout = navDrawerLayoutFor(widthClass)
            val needed = layout.headerHeight +
                layout.itemsHeightFor(bottomRoutes.size) + layout.drawerPadding * 2
            assertTrue("$widthClass 导航项总高超出门槛高度：$needed", needed <= NavDrawerMinHeightDp.dp)
        }
    }
}
