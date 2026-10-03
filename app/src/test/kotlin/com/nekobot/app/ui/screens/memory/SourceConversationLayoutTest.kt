package com.nekobot.app.ui.screens.memory

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class SourceConversationLayoutTest {
    private fun message(id: String, role: String, sender: String? = null) = LocalMessageEntity(
        id = id, sessionId = "s", role = role, sender = sender,
        content = "未经改写的原话 $id", timestamp = "now", createdAt = "now"
    )

    @Test fun consecutiveInputsAndAllRepliesStayInOneCompleteTurn() {
        val rows = listOf(message("u1", "user"), message("u2", "user")) +
            (1..50).map { message("a$it", "assistant") } + message("u3", "user") + message("a51", "assistant")
        val turns = sourceChatTurns(rows)
        assertEquals(2, turns.size)
        assertEquals(52, turns.first().size)
        assertEquals(listOf("u3", "a51"), turns.last().map { it.id })
        assertEquals(rows, turns.flatten())
        assertEquals(listOf(2, 50), sourceSpeakerGroups(turns.first(), "苏雨").map { it.size })
    }

    @Test fun leadingRepliesAndAnUnansweredUserBatchKeepOriginalOrder() {
        val rows = listOf(message("a0", "assistant"), message("a1", "assistant"), message("u1", "user"), message("u2", "user"))
        assertEquals(listOf(listOf("a0", "a1"), listOf("u1", "u2")), sourceChatTurns(rows).map { turn -> turn.map { it.id } })
        assertEquals(rows, sourceChatTurns(rows).flatten())
        assertTrue(sourceChatTurns(emptyList()).isEmpty())
    }

    @Test fun highlightGapsSplitDisplayWithoutAttributingUnrelatedMessagesToArchive() {
        val rows = (1..7).map { message("a$it", "assistant") }
        val turns = sourceChatTurns(rows, setOf("a2", "a3", "a5"))
        assertEquals(listOf(listOf("a1"), listOf("a2", "a3"), listOf("a4"), listOf("a5"), listOf("a6", "a7")),
            turns.map { turn -> turn.map { it.id } })
        assertEquals(rows, turns.flatten())
    }

    @Test fun explicitSpeakerAndImportedRoleFallbackAreGenericAndDoNotChangeMessages() {
        val unnamed = message("a1", "assistant", "  ")
        assertEquals("苏雨", sourceAssistantName(unnamed, "苏雨"))
        assertEquals("林夜", sourceAssistantName(unnamed, "林夜"))
        assertEquals("客串角色", sourceAssistantName(unnamed.copy(sender = "客串角色"), "苏雨"))
        assertNull(sourceAssistantName(unnamed, null))
        assertEquals("  ", unnamed.sender)
        assertEquals("assistant", unnamed.role)
        val rows = listOf(unnamed, message("a2", "assistant", "苏雨"), message("a3", "assistant", "客串角色"))
        assertEquals(listOf(2, 1), sourceSpeakerGroups(rows, "苏雨").map { it.size })
    }
}
