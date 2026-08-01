package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeMessageMergeTest {

    @Test
    fun pendingAgentMessageKeyDoesNotHashNestedProgressPayload() {
        val explosiveResult = object {
            override fun hashCode(): Int = error("不应计算 Agent 工具结果的 hashCode")
        }
        val message = Message(
            role = "user",
            content = "继续",
            timestamp = "same-time",
            thinkingCards = listOf(
                ThinkingCard(
                    id = "card",
                    content = "处理完成",
                    steps = listOf(ThinkingStep(fullResult = explosiveResult)),
                    isAgent = true
                )
            )
        )

        val first = chatMessageItemKey(0, message)
        val second = chatMessageItemKey(1, message)

        assertNotEquals(first, second)
        assertTrue(first.startsWith("pending:"))
    }

    @Test
    fun duplicatePersistedMessageIdsStillReceiveUniqueComposeKeys() {
        val message = Message(id = "same-id", role = "assistant", content = "reply")

        val first = chatMessageItemKey(0, message)
        val second = chatMessageItemKey(1, message)

        assertNotEquals(first, second)
        assertTrue(first.startsWith("message:same-id:"))
    }

    @Test
    fun duplicatePersistedMessagesAreRemovedBeforeRendering() {
        val first = Message(id = "same-id", role = "assistant", content = "first")
        val duplicate = Message(id = "same-id", role = "assistant", content = "duplicate")
        val pendingOne = Message(role = "user", content = "pending one")
        val pendingTwo = Message(role = "user", content = "pending two")

        val result = deduplicateMessagesById(listOf(first, pendingOne, duplicate, pendingTwo))

        assertEquals(listOf(first, pendingOne, pendingTwo), result)
    }

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

    @Test
    fun streamFallbackKeepsReasoningSeparateFromAnswer() {
        val result = finalizeStreamEndMessages(
            current = listOf(
                Message(
                    id = ChatViewModel.STREAMING_ID,
                    role = "assistant",
                    content = "回答",
                    reasoningContent = "分析"
                )
            ),
            streamingId = ChatViewModel.STREAMING_ID,
            finalContent = "回答",
            finalReasoning = "分析",
            materializeFallback = true,
            fallbackId = "fallback"
        )

        assertEquals("回答", result.single().content)
        assertEquals("分析", result.single().reasoningContent)
    }

    @Test
    fun refreshKeepsStreamedAgentReasoningInsideProgressCard() {
        val fresh = ThinkingCard(
            id = "card-1",
            content = "处理完成",
            steps = listOf(ThinkingStep(type = "thinking", name = "AI 正在思考...")),
            isComplete = true,
            isAgent = true
        )
        val current = fresh.copy(
            isComplete = false,
            steps = listOf(
                ThinkingStep(
                    type = "thinking",
                    name = "AI 正在思考...",
                    detail = "正在分析",
                    thinkingContent = "正在分析完整上下文"
                )
            )
        )

        val merged = mergeThinkingCardReasoning(listOf(fresh), listOf(current)).orEmpty()
        val thinkingStep = merged.single().steps.single()

        assertEquals("正在分析完整上下文", thinkingStep.thinkingContent)
        assertEquals("正在分析", thinkingStep.detail)
        assertTrue(merged.single().isComplete)
    }

    @Test
    fun refreshUsesLongerPersistedAgentReasoning() {
        val current = ThinkingCard(
            id = "card-1",
            content = "处理中",
            steps = listOf(
                ThinkingStep(type = "thinking", thinkingContent = "短")
            ),
            isAgent = true
        )
        val fresh = current.copy(
            steps = listOf(
                ThinkingStep(type = "thinking", thinkingContent = "服务端已经保存的更完整思考")
            )
        )

        val merged = mergeThinkingCardReasoning(listOf(fresh), listOf(current)).orEmpty()

        assertEquals("服务端已经保存的更完整思考", merged.single().steps.single().thinkingContent)
    }
}
