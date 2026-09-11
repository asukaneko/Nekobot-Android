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

    @Test
    fun formatTokenSpeed_dividesOutputTokensByGenerationTime() {
        // 100 token / 2000ms = 50 tok/s
        assertEquals("50.0 tok/s", formatTokenSpeed(100, 2_000.0))
        // 输出很短时保留一位小数，便于区分快慢
        assertEquals("8.3 tok/s", formatTokenSpeed(50, 6_000.0))
    }

    @Test
    fun formatTokenSpeed_dropsFractionFromThreeDigits() {
        assertEquals("120 tok/s", formatTokenSpeed(600, 5_000.0))
    }

    @Test
    fun formatTokenSpeed_hidesWhenSampleIsMissingOrTooShort() {
        // 服务端消息、导入的历史消息没有耗时
        assertEquals(null, formatTokenSpeed(120, null))
        // 没有输出 token（例如只有工具调用、或用量缺失）
        assertEquals(null, formatTokenSpeed(null, 3_000.0))
        assertEquals(null, formatTokenSpeed(0, 3_000.0))
        // 采样过短：换算结果没有参考价值
        assertEquals(null, formatTokenSpeed(3, 20.0))
    }
}
