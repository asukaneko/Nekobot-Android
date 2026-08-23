package com.nekobot.app.ui.screens.chat

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
}
