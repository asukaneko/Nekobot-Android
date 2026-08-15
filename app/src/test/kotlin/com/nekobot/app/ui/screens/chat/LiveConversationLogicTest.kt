package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveConversationLogicTest {

    @Test
    fun liveButtonSupportsCharacterAndAgentSessions() {
        assertTrue(isLiveConversationSession(null))
        assertTrue(isLiveConversationSession("character"))
        assertTrue(isLiveConversationSession("CHARACTER"))
        assertTrue(isLiveConversationSession("agent"))
        assertTrue(isLiveConversationSession("AGENT"))
        assertFalse(isLiveConversationSession("group"))
    }

    @Test
    fun replyDetectionKeepsExistingConversationOutOfTheCurrentLiveTurn() {
        val existing = Message(id = "old", role = "assistant", content = "旧回复", timestamp = "1")
        val baseline = setOf(liveMessageFingerprint(existing))

        assertNull(findLiveAssistantReply(listOf(existing), baseline))

        val reply = Message(id = "new", role = "assistant", content = "新的实时回复", timestamp = "2")
        assertEquals(reply, findLiveAssistantReply(listOf(existing, reply), baseline))
    }

    @Test
    fun streamingPlaceholderIsNotTreatedAsCompletedReply() {
        val placeholder = Message(
            id = ChatViewModel.STREAMING_ID,
            role = "assistant",
            content = "正在流式输出",
            timestamp = "2"
        )
        assertNull(findLiveAssistantReply(listOf(placeholder), emptySet()))
    }

    @Test
    fun subtitleKeepsTheCompleteReplyByDefault() {
        val text = "前".repeat(40) + "最后字幕"
        assertEquals(text, liveSubtitleWindow(text))
        assertEquals(text.takeLast(12), liveSubtitleWindow(text, 12))
    }
}
