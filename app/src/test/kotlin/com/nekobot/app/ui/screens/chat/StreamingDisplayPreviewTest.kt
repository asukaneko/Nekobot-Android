package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDisplayPreviewTest {

    @Test
    fun streamingAssistantContentKeepsMarkdownRenderer() {
        assertTrue(
            !shouldUseSafePlainText(
                isUser = false,
                contentLength = 16_000,
                isStreaming = true
            )
        )
    }

    @Test
    fun nonStreamingLongAssistantContentUsesPlainTextFallback() {
        assertTrue(
            shouldUseSafePlainText(
                isUser = false,
                contentLength = 24_001,
                isStreaming = false
            )
        )
    }

    @Test
    fun shortStreamingContentIsUnchanged() {
        assertEquals("完整短回复", buildStreamingDisplayPreview("完整短回复", maxChars = 20))
    }

    @Test
    fun longStreamingContentKeepsBoundedLatestWindow() {
        val content = "a".repeat(40) + "b".repeat(20)
        val preview = buildStreamingDisplayPreview(content, maxChars = 20)

        assertTrue(preview.startsWith("…\n"))
        assertTrue(preview.endsWith("b".repeat(20)))
        assertTrue(preview.length < content.length)
    }
}
