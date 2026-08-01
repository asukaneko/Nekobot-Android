package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDisplayPreviewTest {

    @Test
    fun shortStreamingContentIsUnchanged() {
        assertEquals("完整短回复", buildStreamingDisplayPreview("完整短回复", maxChars = 20))
    }

    @Test
    fun longStreamingContentKeepsBoundedLatestWindow() {
        val content = "a".repeat(40) + "b".repeat(20)
        val preview = buildStreamingDisplayPreview(content, maxChars = 20)

        assertTrue(preview.startsWith("…前文仍在生成并会完整保存"))
        assertTrue(preview.endsWith("b".repeat(20)))
        assertTrue(preview.length < content.length)
    }
}
