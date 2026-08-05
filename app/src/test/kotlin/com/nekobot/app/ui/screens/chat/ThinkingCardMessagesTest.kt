package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.ThinkingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCardMessagesTest {

    @Test
    fun acceptsAgentCardBeforeSessionMetadataFinishesLoading() {
        assertTrue(shouldApplyThinkingCardUpdate(sessionMode = null, isAgentCard = true))
        assertFalse(shouldApplyThinkingCardUpdate(sessionMode = null, isAgentCard = false))
    }

    @Test
    fun attachesRealtimeCardToOptimisticUserWhenPersistedIdIsNotLoadedYet() {
        val optimisticUser = Message(
            id = null,
            role = "user",
            content = "/jm 123",
            timestamp = "1000"
        )
        val placeholder = Message(
            id = ChatViewModel.STREAMING_ID,
            role = "assistant",
            content = "",
            timestamp = "1001"
        )
        val card = ThinkingCard(
            id = "local-command-db-message-id",
            content = "下载中",
            progress = 20,
            parentMessageId = "db-message-id"
        )

        val result = attachThinkingCardToMessages(listOf(optimisticUser, placeholder), card)

        assertEquals(listOf(card), result.first().thinkingCards)
        assertSame(placeholder, result.last())
    }

    @Test
    fun replacesPreviousRealtimeUpdateForTheSameCard() {
        val first = ThinkingCard(id = "card-1", content = "下载中", progress = 20)
        val latest = first.copy(content = "继续下载", progress = 65)
        val user = Message(
            id = "message-1",
            role = "user",
            content = "/jm 123",
            thinkingCards = listOf(first)
        )

        val result = attachThinkingCardToMessages(listOf(user), latest.copy(parentMessageId = "message-1"))

        assertEquals(1, result.single().thinkingCards?.size)
        assertEquals(65, result.single().thinkingCards?.single()?.progress)
    }

    @Test
    fun keepsAgentCardExpandedAfterNewToolUpdatesTheSameCard() {
        val expansionOverrides = mapOf("agent-card" to true)

        assertTrue(
            resolveProgressCardExpanded(
                cardId = "agent-card",
                isAgent = true,
                expansionOverrides = expansionOverrides
            )
        )
    }

    @Test
    fun usesCardDefaultsUntilUserChangesExpansion() {
        assertFalse(resolveProgressCardExpanded("agent-card", isAgent = true, emptyMap()))
        assertTrue(resolveProgressCardExpanded("local-card", isAgent = false, emptyMap()))
        assertFalse(
            resolveProgressCardExpanded(
                cardId = "local-card",
                isAgent = false,
                expansionOverrides = mapOf("local-card" to false)
            )
        )
    }

    @Test
    fun hidesProgressCardsForRemoteCharacterAndGroupSessions() {
        assertFalse(shouldRenderProgressCards(isLocalMode = false, sessionMode = "character"))
        assertFalse(shouldRenderProgressCards(isLocalMode = false, sessionMode = "group"))
        assertTrue(shouldRenderProgressCards(isLocalMode = false, sessionMode = "agent"))
        assertTrue(shouldRenderProgressCards(isLocalMode = true, sessionMode = "character"))
    }
}
