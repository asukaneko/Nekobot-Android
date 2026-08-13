package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StylePreferencesTest {

    @Test
    fun `font size and scale convert around 16sp baseline`() {
        assertEquals(1f, PrefsManager.bodySpToFontScale(16f), 0.0001f)
        assertEquals(1.25f, PrefsManager.bodySpToFontScale(20f), 0.0001f)
        assertEquals(20f, PrefsManager.fontScaleToBodySp(1.25f), 0.0001f)
    }

    @Test
    fun `font size conversions clamp to supported range`() {
        assertEquals(0.75f, PrefsManager.bodySpToFontScale(5f), 0.0001f)
        assertEquals(1.5f, PrefsManager.bodySpToFontScale(40f), 0.0001f)
        assertEquals(12f, PrefsManager.fontScaleToBodySp(0.25f), 0.0001f)
        assertEquals(24f, PrefsManager.fontScaleToBodySp(3f), 0.0001f)
    }

    @Test
    fun `chat background follows selected source`() {
        assertEquals(
            "portrait.jpg",
            PrefsManager.selectChatBackgroundPath(
                PrefsManager.CHAT_BACKGROUND_PORTRAIT,
                portraitPath = "portrait.jpg",
                customPath = "custom.jpg"
            )
        )
        assertEquals(
            "custom.jpg",
            PrefsManager.selectChatBackgroundPath(
                PrefsManager.CHAT_BACKGROUND_CUSTOM,
                portraitPath = "portrait.jpg",
                customPath = "custom.jpg"
            )
        )
        assertNull(
            PrefsManager.selectChatBackgroundPath(
                PrefsManager.CHAT_BACKGROUND_NONE,
                portraitPath = "portrait.jpg",
                customPath = "custom.jpg"
            )
        )
        assertNull(
            PrefsManager.selectChatBackgroundPath(
                PrefsManager.CHAT_BACKGROUND_PORTRAIT,
                portraitPath = "  ",
                customPath = "custom.jpg"
            )
        )
    }
}
