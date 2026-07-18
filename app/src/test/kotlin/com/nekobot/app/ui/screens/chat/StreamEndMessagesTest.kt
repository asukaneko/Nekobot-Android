package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEndMessagesTest {

    private val user = Message(id = "user-1", role = "user", content = "你好")
    private val placeholder = Message(id = ChatViewModel.STREAMING_ID, role = "assistant", content = "回复")

    @Test
    fun localStreamEndRemovesPlaceholderWithoutCreatingDuplicateAssistant() {
        val result = finalizeStreamEndMessages(
            current = listOf(user, placeholder),
            streamingId = ChatViewModel.STREAMING_ID,
            finalContent = "回复",
            materializeFallback = false
        )

        assertEquals(listOf(user), result)
        assertFalse(result.any { it.content == "回复" })
    }

    @Test
    fun remoteStreamEndKeepsOneFallbackUntilPersistenceRefreshes() {
        val result = finalizeStreamEndMessages(
            current = listOf(user, placeholder),
            streamingId = ChatViewModel.STREAMING_ID,
            finalContent = "回复",
            materializeFallback = true,
            fallbackId = "assistant-fallback",
            fallbackTimestamp = "now"
        )

        assertFalse(result.any { it.id == ChatViewModel.STREAMING_ID })
        assertTrue(result.any { it.id == "assistant-fallback" })
        assertEquals(1, result.count { it.content == "回复" })
    }

    @Test
    fun remoteStreamEndDoesNotAddFallbackWhenRealMessageAlreadyArrived() {
        val real = Message(id = "assistant-real", role = "assistant", content = "回复")

        val result = finalizeStreamEndMessages(
            current = listOf(user, real),
            streamingId = ChatViewModel.STREAMING_ID,
            finalContent = "回复",
            materializeFallback = true
        )

        assertEquals(listOf(user, real), result)
        assertEquals(1, result.count { it.content == "回复" })
    }
}
