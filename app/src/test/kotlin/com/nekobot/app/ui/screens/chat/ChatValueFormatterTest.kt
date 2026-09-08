package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatValueFormatterTest {

    @Test
    fun keepsEqualsSignsReadableInToolPayloadJson() {
        val formatted = formatJsonForDisplay(
            mapOf("preview" to "path=/workspace/file.txt")
        )

        assertTrue(formatted.contains("path=/workspace/file.txt"))
        assertFalse(formatted.contains("\\u003d"))
    }

    @Test
    fun formatTokenCount_showsPlainNumberBelowOneK() {
        assertEquals("0", formatTokenCount(0))
        assertEquals("512", formatTokenCount(512))
        assertEquals("999", formatTokenCount(999))
    }

    @Test
    fun formatTokenCount_usesKForThousands() {
        assertEquals("1k", formatTokenCount(1_000))
        assertEquals("1.2k", formatTokenCount(1_234))
        assertEquals("9.8k", formatTokenCount(9_750))
        assertEquals("10k", formatTokenCount(10_000))
        assertEquals("123k", formatTokenCount(123_456))
        assertEquals("999k", formatTokenCount(999_940))
        assertEquals("1M", formatTokenCount(999_950))
    }

    @Test
    fun formatTokenCount_usesMForMillions() {
        assertEquals("1.5M", formatTokenCount(1_500_000))
        assertEquals("12M", formatTokenCount(12_000_000))
        assertEquals("350M", formatTokenCount(350_000_000))
    }
}
