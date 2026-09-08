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
    fun cardFallbackSkipsUrgentBubbleSoNoSecondProgressCardAppears() {
        val persistedUser = Message(
            id = "persisted-user-1",
            role = "user",
            content = "当前问题",
            thinkingCards = listOf(ThinkingCard(id = "card-1", content = "处理中", progress = 40))
        )
        // 排队消息“立即发送”的乐观气泡位于列表末尾（等待注入）
        val urgentBubble = Message(
            id = "${ChatViewModel.URGENT_BUBBLE_PREFIX}item-x",
            role = "user",
            content = "插队消息"
        )
        // 父消息 id 指向尚未落库的正式 id：UI 中匹配不到，必须回退到最后一条普通用户消息，
        // 而不是回退到插队乐观气泡下形成第二个进度卡片
        val card = ThinkingCard(
            id = "card-2",
            content = "继续处理中",
            isAgent = true,
            parentMessageId = "server-user-9"
        )

        val result = attachThinkingCardToMessages(listOf(persistedUser, urgentBubble), card)

        // 新卡片与原有卡片一起挂在普通用户消息下，插队气泡不挂卡
        assertEquals(listOf("card-1", "card-2"), result.first().thinkingCards?.map { it.id })
        assertTrue(result.last().thinkingCards.isNullOrEmpty())
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

    @Test
    fun formatToolDurationShowsMillisecondsUnderOneSecond() {
        assertEquals("0ms", formatToolDuration(0))
        assertEquals("320ms", formatToolDuration(320))
        assertEquals("999ms", formatToolDuration(999))
    }

    @Test
    fun formatToolDurationShowsSecondsAtOrOverOneSecond() {
        assertEquals("1.0s", formatToolDuration(1_000))
        assertEquals("1.5s", formatToolDuration(1_500))
        assertEquals("12.3s", formatToolDuration(12_345))
        assertEquals("75.0s", formatToolDuration(75_000))
    }

    @Test
    fun formatToolDurationClampsNegativeInput() {
        assertEquals("0ms", formatToolDuration(-5))
    }
}
