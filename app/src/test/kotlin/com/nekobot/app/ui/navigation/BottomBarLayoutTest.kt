package com.nekobot.app.ui.navigation

import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试底栏在三种窗口宽度下的几何规格：
 * 手机保持原有紧凑形态，平板必须放大（更高 / 更大图标 / 标签并排）并限制胶囊宽度，
 * 避免「平板下底栏仍然和手机一样紧凑」。
 */
class BottomBarLayoutTest {

    @Test
    fun compact_keepsOriginalSizesAndStackedLabels() {
        val layout = bottomBarLayoutFor(WindowWidthClass.Compact)
        assertEquals(64.dp, layout.barHeight)
        assertEquals(24.dp, layout.iconSize)
        // 手机：标签在图标下方，胶囊铺满可用宽度，无最大宽度限制
        assertTrue(!layout.labelBesideIcon)
        assertNull(layout.pillMaxWidth)
        assertEquals(84.dp, layout.clearance)
    }

    @Test
    fun tabletLayouts_areLargerThanCompact() {
        val compact = bottomBarLayoutFor(WindowWidthClass.Compact)
        listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded).forEach { widthClass ->
            val layout = bottomBarLayoutFor(widthClass)
            assertTrue("$widthClass 高度需大于手机", layout.barHeight > compact.barHeight)
            assertTrue("$widthClass 图标需大于手机", layout.iconSize > compact.iconSize)
            assertTrue("$widthClass 避让高度需大于手机", layout.clearance > compact.clearance)
            // 平板：图标与文字并排，且胶囊限宽居中
            assertTrue("$widthClass 标签应与图标并排", layout.labelBesideIcon)
            assertNotNull("$widthClass 胶囊应限制最大宽度", layout.pillMaxWidth)
        }
    }

    @Test
    fun expanded_isLargerThanMedium() {
        val medium = bottomBarLayoutFor(WindowWidthClass.Medium)
        val expanded = bottomBarLayoutFor(WindowWidthClass.Expanded)
        assertTrue(expanded.barHeight > medium.barHeight)
        assertTrue(expanded.iconSize > medium.iconSize)
        assertTrue(expanded.pillMaxWidth!! > medium.pillMaxWidth!!)
    }

    @Test
    fun pillCorner_isHalfOfBarHeight() {
        WindowWidthClass.entries.forEach { widthClass ->
            val layout = bottomBarLayoutFor(widthClass)
            assertEquals(layout.barHeight / 2, layout.pillCorner)
        }
    }

    @Test
    fun pillMaxWidth_isNarrowerThanBreakpointUpperBound() {
        // 限宽的意义在于「宽屏不被拉伸」：必须明显小于该断点上限窗口的可用宽度。
        val medium = bottomBarLayoutFor(WindowWidthClass.Medium)
        assertTrue(medium.pillMaxWidth!! < 840.dp - medium.horizontalPadding * 2)

        // 大屏上限取常见平板横屏 1280dp：限宽后两侧应留出可观的居中留白。
        val expanded = bottomBarLayoutFor(WindowWidthClass.Expanded)
        assertTrue(expanded.pillMaxWidth!! < 1280.dp - expanded.horizontalPadding * 2)
    }

    @Test
    fun pillMaxWidth_leavesEnoughRoomForIconAndLabelPerItem() {
        // 每个槽位至少要放得下「图标 + 间距 + 4 个字符的标签」，否则平板会集体省略号。
        listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded).forEach { widthClass ->
            val layout = bottomBarLayoutFor(widthClass)
            val itemWidth = layout.pillMaxWidth!! / 5
            assertTrue(
                "$widthClass 槽位过窄：$itemWidth",
                itemWidth >= layout.iconSize + layout.iconTextGap + 64.dp
            )
        }
    }
}
