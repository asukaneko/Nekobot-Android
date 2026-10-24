package com.nekobot.app.ui.screens.sessions

import org.junit.Assert.*
import org.junit.Test

class AvatarCropTest {
    @Test fun landscapeAndPortraitStartWithCenteredSquare() {
        assertEquals(AvatarCrop(500, 0, 1000), avatarCrop(2000, 1000, 1f, .5f, .5f))
        assertEquals(AvatarCrop(0, 500, 1000), avatarCrop(1000, 2000, 1f, .5f, .5f))
    }
    @Test fun zoomAndPanStayWithinImageIncludingTinyInputs() {
        for (width in listOf(1, 7, 1080, 2048)) for (height in listOf(1, 9, 1920, 2048)) {
            for (zoom in listOf(.1f, 1f, 2f, 4f, 8f)) for (pan in listOf(-1f, 0f, .5f, 1f, 2f)) {
                val crop = avatarCrop(width, height, zoom, pan, pan)
                assertTrue(crop.side > 0)
                assertTrue(crop.left >= 0 && crop.top >= 0)
                assertTrue(crop.left + crop.side <= width && crop.top + crop.side <= height)
            }
        }
    }
    @Test fun zoomedBottomRightMatchesSavedPixelBounds() {
        assertEquals(AvatarCrop(1500, 500, 500), avatarCrop(2000, 1000, 2f, 1f, 1f))
    }
}
