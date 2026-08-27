package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectVisionInputTest {

    @Test
    fun imagesAreAttachedToTheLatestUserMessageAsMultimodalContent() {
        val messages = listOf(
            mapOf("role" to "system", "content" to "Follow the rules"),
            mapOf("role" to "user", "content" to "Describe this image")
        )

        val result = attachImagesToLatestUserMessage(
            messages,
            listOf("data:image/png;base64,aGVsbG8=")
        )

        @Suppress("UNCHECKED_CAST")
        val content = result.last()["content"] as List<Map<String, Any>>
        assertEquals(mapOf("type" to "text", "text" to "Describe this image"), content.first())
        assertEquals("image_url", content[1]["type"])
        @Suppress("UNCHECKED_CAST")
        val imageUrl = content[1]["image_url"] as Map<String, String>
        assertEquals("data:image/png;base64,aGVsbG8=", imageUrl["url"])
    }

    @Test
    fun imagesAreNotAttachedWhenThereIsNoUserMessage() {
        val messages = listOf(mapOf("role" to "system", "content" to "Follow the rules"))

        val result = attachImagesToLatestUserMessage(messages, listOf("https://example.com/image.png"))

        assertTrue(result === messages)
    }
}
