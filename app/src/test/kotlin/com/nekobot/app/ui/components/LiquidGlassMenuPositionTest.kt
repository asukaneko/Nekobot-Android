package com.nekobot.app.ui.components

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidGlassMenuPositionTest {
    private val window = IntSize(1080, 1920)
    private val popup = IntSize(284, 244)
    private val padding = IntOffset(32, 32)

    @Test
    fun rightEdgeMenuKeepsItsCardAlignedWithTheAnchor() {
        val position = provider().calculatePosition(
            IntRect(1000, 40, 1080, 120), window, LayoutDirection.Ltr, popup,
        )
        assertEquals(IntOffset(860, 120), position + padding)
    }

    @Test
    fun rtlMenuKeepsItsCardAtTheLeftEdgeWithShadowOutsideTheWindow() {
        val position = provider().calculatePosition(
            IntRect(0, 40, 80, 120), window, LayoutDirection.Rtl, popup,
        )
        assertEquals(IntOffset(-32, 88), position)
        assertEquals(IntOffset(0, 120), position + padding)
    }

    @Test
    fun bottomMenuOpensAboveItsAnchor() {
        val position = provider().calculatePosition(
            IntRect(400, 1790, 480, 1870), window, LayoutDirection.Ltr, popup,
        )
        assertEquals(IntOffset(400, 1610), position + padding)
    }

    @Test
    fun anchorNearTheTopKeepsTheMenuInsideTheVerticalMargin() {
        val position = provider().calculatePosition(
            IntRect(400, 0, 480, 40), window, LayoutDirection.Ltr, popup,
        )
        assertEquals(IntOffset(400, 48), position + padding)
    }

    @Test
    fun animationPivotTracksTheCardEdgeInsteadOfTheShadowEdge() {
        var origin = TransformOrigin.Center
        val position = LiquidGlassMenuPositionProvider(32, 48) { origin = it }
            .calculatePosition(IntRect(1000, 40, 1080, 120), window, LayoutDirection.Ltr, popup)
        assertEquals(1040f, position.x + origin.pivotFractionX * popup.width, 0.001f)
        assertEquals(120f, position.y + origin.pivotFractionY * popup.height, 0.001f)
    }

    private fun provider() = LiquidGlassMenuPositionProvider(32, 48)
}
