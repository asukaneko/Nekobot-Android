package com.nekobot.app.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试 computeWindowWidthClass 纯函数的断点计算逻辑。
 */
class WindowWidthClassTest {

    @Test
    fun widthZero_returnsCompact() {
        assertEquals(WindowWidthClass.Compact, computeWindowWidthClass(0))
    }

    @Test
    fun width599_returnsCompact() {
        assertEquals(WindowWidthClass.Compact, computeWindowWidthClass(599))
    }

    @Test
    fun width600_returnsMedium() {
        assertEquals(WindowWidthClass.Medium, computeWindowWidthClass(600))
    }

    @Test
    fun width839_returnsMedium() {
        assertEquals(WindowWidthClass.Medium, computeWindowWidthClass(839))
    }

    @Test
    fun width840_returnsExpanded() {
        assertEquals(WindowWidthClass.Expanded, computeWindowWidthClass(840))
    }

    @Test
    fun width1920_returnsExpanded() {
        assertEquals(WindowWidthClass.Expanded, computeWindowWidthClass(1920))
    }
}
