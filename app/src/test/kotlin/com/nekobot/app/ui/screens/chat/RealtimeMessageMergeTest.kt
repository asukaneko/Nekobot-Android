package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeMessageMergeTest {

    @Test
    fun webUiUserMessageIsVisibleInAndroidConversation() {
        val existing = Message(id = "assistant-old", role = "assistant", content = "上一条")
        val webMessage = Message(
            id = "web-user-1",
            role = "user",
            content = "从 WebUI 发出",
            sessionId = "session-a"
        )

        val merged = mergeRealtimeNewMessage(
            current = listOf(existing),
            incoming = webMessage,
            isSending = false
        )

        assertEquals(listOf(existing, webMessage), merged)
    }

    @Test
    fun androidOptimisticUserMessageIsReplacedByServerMessage() {
        val optimistic = Message(role = "user", content = "同一条消息")
        val serverMessage = Message(
            id = "server-user-1",
            role = "user",
            content = "同一条消息",
            sessionId = "session-a"
        )

        val merged = mergeRealtimeNewMessage(
            current = listOf(optimistic),
            incoming = serverMessage,
            isSending = true
        )

        assertEquals(1, merged.size)
        assertEquals("server-user-1", merged.single().id)
    }

    @Test
    fun assistantMessageReplacesStreamingPlaceholderWithoutTouchingOtherMessages() {
        val user = Message(id = "user-1", role = "user", content = "问题")
        val placeholder = Message(
            id = ChatViewModel.STREAMING_ID,
            role = "assistant",
            content = "生成中"
        )
        val assistant = Message(
            id = "assistant-1",
            role = "assistant",
            content = "回答",
            sessionId = "session-a"
        )

        val merged = mergeRealtimeNewMessage(
            current = listOf(user, placeholder),
            incoming = assistant,
            isSending = true
        )

        assertTrue(merged.contains(user))
        assertTrue(merged.contains(assistant))
        assertFalse(merged.any { it.id == ChatViewModel.STREAMING_ID })
    }
}
