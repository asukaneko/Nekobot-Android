package com.nekobot.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitFramingTest {

    @Test fun defaultFramingCoversBoxForBothOrientations() {
        // 竖版立绘铺满方形：取满宽度，高度按方形截取
        assertEquals(
            PortraitSourceRect(0, 500, 1000, 1000),
            portraitSourceRect(1000, 2000, 100, 100, PortraitFraming())
        )
        // 横版图片铺满方形：取满高度
        assertEquals(
            PortraitSourceRect(500, 0, 1000, 1000),
            portraitSourceRect(2000, 1000, 100, 100, PortraitFraming())
        )
    }

    @Test fun zoomHalvesVisibleArea() {
        assertEquals(
            PortraitSourceRect(250, 750, 500, 500),
            portraitSourceRect(1000, 2000, 100, 100, PortraitFraming(zoom = 2f))
        )
    }

    @Test fun nonSquareBoxKeepsBoxAspect() {
        val crop = portraitSourceRect(1000, 2000, 78, 100, PortraitFraming())
        assertEquals(crop!!.width.toFloat() / crop.height, 0.78f, 0.01f)
    }

    @Test fun sourceRectStaysInsideImageForAnyInput() {
        for (width in listOf(1, 7, 1080, 2048)) for (height in listOf(1, 9, 1920, 2048)) {
            for (zoom in listOf(-1f, 0f, 1f, 2f, 4f, 9f)) for (pan in listOf(-2f, 0f, .5f, 1f, 3f)) {
                val crop = portraitSourceRect(
                    width, height, 90, 120, PortraitFraming(zoom, pan, pan)
                ) ?: continue
                assertTrue(crop.width > 0 && crop.height > 0)
                assertTrue(crop.left >= 0 && crop.top >= 0)
                assertTrue(crop.left + crop.width <= width)
                assertTrue(crop.top + crop.height <= height)
            }
        }
    }

    @Test fun draggingMovesCenterAndClampsAtEdges() {
        // 竖图 + 方形框：zoom 1 时横向没有余量，纵向可以上下移动
        val moved = panPortraitFraming(
            framing = PortraitFraming(),
            panX = 40f,
            panY = -30f,
            boxWidth = 100f,
            boxHeight = 100f,
            imageAspect = 0.5f
        )
        assertEquals(0.5f, moved.centerX, 0.0001f)
        assertEquals(0.65f, moved.centerY, 0.0001f)

        val clamped = panPortraitFraming(
            framing = PortraitFraming(),
            panX = 0f,
            panY = -1000f,
            boxWidth = 100f,
            boxHeight = 100f,
            imageAspect = 0.5f
        )
        assertEquals(0.75f, clamped.centerY, 0.0001f)
    }

    @Test fun legacySquareFrameIsConvertedToZoom() {
        // 旧格式：角色ID|方框边长占图片宽度比例|centerX|centerY
        val framing = PortraitFraming.parse("char-1|0.5|0.4|0.6")
        assertEquals(2f, framing!!.zoom, 0.0001f)
        assertEquals(0.4f, framing.centerX, 0.0001f)
        assertEquals(0.6f, framing.centerY, 0.0001f)
        // 旧版"自动"（frame = 0）等价于铺满
        assertEquals(1f, PortraitFraming.parse("char-1|0|0.5|0.5")!!.zoom, 0.0001f)
    }

    @Test fun roundTripAndInvalidInput() {
        val framing = PortraitFraming(zoom = 1.5f, centerX = 0.25f, centerY = 0.75f)
        assertEquals(framing, PortraitFraming.parse(framing.serialise()))
        assertNull(PortraitFraming.parse(""))
        assertNull(PortraitFraming.parse("not-a-framing"))
        // 越界值会被夹回合法范围
        assertEquals(PortraitFraming(4f, 1f, 0f), PortraitFraming.parse("9|2|-1")!!)
    }
}
