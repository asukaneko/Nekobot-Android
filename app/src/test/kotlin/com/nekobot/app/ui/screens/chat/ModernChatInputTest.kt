package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernChatInputTest {
    @Test
    fun estimateModernChatDraftTokens_handlesMixedText() {
        assertEquals(0, estimateModernChatDraftTokens(""))
        assertEquals(4, estimateModernChatDraftTokens("测试文本"))
        assertEquals(1, estimateModernChatDraftTokens("abcd"))
        assertEquals(3, estimateModernChatDraftTokens("测试abcd"))
    }

    @Test
    fun buildChatMessageContent_addsImageReferenceWithoutReplacingText() {
        val content = buildChatMessageContent(
            text = "看看这张图",
            attachments = listOf(
                mapOf("type" to "image/png", "name" to "cat.png", "path" to "/tmp/cat.png")
            )
        )

        assertTrue(content.startsWith("看看这张图"))
        assertTrue(content.contains("[File: cat.png]"))
    }

    @Test
    fun buildChatMessageContent_supportsImageOnlyMessage() {
        assertEquals(
            "[File: cat.png]",
            buildChatMessageContent(
                text = "",
                attachments = listOf(mapOf("type" to "image/png", "name" to "cat.png"))
            )
        )
    }

    @Test
    fun buildChatMessageContent_doesNotDuplicateExistingFileReference() {
        assertEquals(
            "[File: cat.png]",
            buildChatMessageContent(
                text = "[File: cat.png]",
                attachments = listOf(mapOf("type" to "image/png", "name" to "cat.png"))
            )
        )
    }
}
