package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageContentGroupingTest {
    @Test
    fun `text and image attachment are rendered as separate ordered groups`() {
        val segments = parseContentSegments("请看看这张图\n[File: photo.png]")
        val groups = groupUserMessageContent(segments)

        assertEquals(2, groups.size)
        assertFalse(groups[0].first().isImageContent())
        assertTrue(groups[1].first().isImageContent())
        assertEquals("请看看这张图\n", groups[0].single().text)
        assertEquals("photo.png", groups[1].single().fileName)
    }

    @Test
    fun `image only message does not create an empty text bubble`() {
        val groups = groupUserMessageContent(parseContentSegments("[File: photo.png]"))

        assertEquals(1, groups.size)
        assertTrue(groups.single().single().isImageContent())
    }
}
