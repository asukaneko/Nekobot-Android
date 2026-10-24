package com.nekobot.app.ui.screens.chat

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionRuntimeTest {
    @Test
    fun memorySummaryNoticeKeepsItsKindAndAnchorUntilFinishedOrStopped() {
        val state = ChatSessionState("fictional-memory-summary")
        state.applyContextCompressionNotice(true, "虚构测试消息", memorySummarizing = true)
        val notice = state.inlineNoticeOrNull<ContextCompressionUiState>()!!
        assertTrue(notice.memorySummarizing)
        assertTrue(notice.running)
        assertEquals("虚构测试消息", notice.anchorContent)
        state.applyContextCompressionNotice(false)
        assertNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
        state.applyContextCompressionNotice(true, "普通压缩")
        assertFalse(state.inlineNoticeOrNull<ContextCompressionUiState>()!!.memorySummarizing)
    }

    @Test
    fun simultaneousContentAndReasoningCannotStarveEachOthersPreview() {
        val state = ChatSessionState("stream-throttle")
        for (now in listOf(0L, 100L, 200L, 300L)) {
            assertTrue(state.shouldUpdateStreamPreview(reasoning = true, nowMs = now))
            assertTrue(state.shouldUpdateStreamPreview(reasoning = false, nowMs = now))
            assertFalse(state.shouldUpdateStreamPreview(reasoning = true, nowMs = now + 1))
            assertFalse(state.shouldUpdateStreamPreview(reasoning = false, nowMs = now + 1))
        }
        state.resetStreamingState()
        assertTrue(state.shouldUpdateStreamPreview(reasoning = false, nowMs = 301L))
        assertTrue(state.shouldUpdateStreamPreview(reasoning = true, nowMs = 301L))
        state.resetStreamPreviewThrottle()
        assertTrue(state.shouldUpdateStreamPreview(reasoning = true, nowMs = 301L))
        assertTrue(state.shouldUpdateStreamPreview(reasoning = false, nowMs = 301L))
    }

    @Test
    fun leavingIdleSessionCancelsListenerAndReentryStartsFromFreshState() = runBlocking {
        val sessionId = "idle-reentry-${System.nanoTime()}"
        val state = ChatSessionManager.acquire(sessionId)
        state.messages.value = listOf(com.nekobot.app.data.model.Message(role = "user", content = "旧页面"))
        val listener = launch { awaitCancellation() }
        state.eventsJob = listener
        yield()

        ChatSessionManager.release(sessionId)
        listener.join()

        assertNull(ChatSessionManager.get(sessionId))
        val reentered = ChatSessionManager.acquire(sessionId)
        assertNotSame(state, reentered)
        assertTrue(reentered.messages.value.isEmpty())
        ChatSessionManager.release(sessionId)
    }

    @Test
    fun leavingDuringBackgroundReplyKeepsStateUntilReplyFinishes() = runBlocking {
        val sessionId = "active-reentry-${System.nanoTime()}"
        val state = ChatSessionManager.acquire(sessionId)
        val reply = launch { awaitCancellation() }
        yield()
        assertTrue(state.installLocalChatJob(reply))

        ChatSessionManager.release(sessionId)
        assertSame(state, ChatSessionManager.get(sessionId))

        reply.cancelAndJoin()
        assertTrue(state.clearLocalChatJob(reply))
        ChatSessionManager.pruneIfIdle(sessionId)
        assertNull(ChatSessionManager.get(sessionId))
    }

    @Test
    fun passiveRealtimeListenerDoesNotCountAsActiveGeneration() = runBlocking {
        val state = ChatSessionState("session")
        val listener = launch { awaitCancellation() }
        state.eventsJob = listener

        assertTrue(state.hasActiveJobs())
        assertFalse(state.hasActiveGeneration())

        state.sending.value = true
        assertTrue(state.hasActiveGeneration())

        listener.cancelAndJoin()
    }

    @Test
    fun activeLocalTurnCannotBeReplacedBySecondTurn() = runBlocking {
        val state = ChatSessionState("session")
        val first = launch { awaitCancellation() }
        val second = launch { awaitCancellation() }
        yield()

        assertTrue(state.installLocalChatJob(first))
        assertFalse(state.installLocalChatJob(second))
        assertTrue(state.localChatJob === first)

        state.sending.value = true
        assertTrue(state.markLocalResponseComplete(first))
        assertFalse(state.sending.value)
        assertFalse(state.hasBlockingLocalChatJob())
        assertFalse(state.hasActiveGeneration())
        assertTrue(state.hasActiveJobs())
        assertTrue(state.ownsLocalChatJob(first))

        // 正文完成后第二轮可立即开始，并安全取消仍在运行的旧后处理。
        assertTrue(state.installLocalChatJob(second))
        first.join()
        assertTrue(state.localChatJob === second)
        assertFalse(state.markLocalResponseComplete(first))
        assertFalse(state.clearLocalChatJob(first))

        second.cancelAndJoin()
    }

    @Test
    fun completedTurnCanClearItselfAndNextTurnCanStart() = runBlocking {
        val state = ChatSessionState("session")
        val first = launch { awaitCancellation() }
        yield()

        assertTrue(state.installLocalChatJob(first))
        first.cancelAndJoin()
        assertTrue(state.clearLocalChatJob(first))

        val second = launch { awaitCancellation() }
        yield()
        assertTrue(state.installLocalChatJob(second))
        assertFalse(state.clearLocalChatJob(first))
        assertTrue(state.localChatJob === second)

        second.cancelAndJoin()
    }

    @Test
    fun cancellingDelayedBatchStopsTimerAndRemovesEveryPendingBubble() = runBlocking {
        val sessionId = "delay-cancel-${System.nanoTime()}"
        val state = ChatSessionManager.acquire(sessionId)
        val first = DelayedReplyMessage(
            bubbleId = "pending-a",
            content = "A",
            timestamp = "1"
        )
        val second = DelayedReplyMessage(
            bubbleId = "pending-b",
            content = "B",
            timestamp = "2"
        )
        state.delayReply.arm(first, nowMs = 0L, delayMs = 10_000L)
        state.delayReply.arm(second, nowMs = 1L, delayMs = 10_000L)
        state.messages.value = listOf(
            com.nekobot.app.data.model.Message(id = "stored", role = "assistant", content = "旧回复"),
            com.nekobot.app.data.model.Message(id = first.bubbleId, role = "user", content = first.content),
            com.nekobot.app.data.model.Message(id = second.bubbleId, role = "user", content = second.content)
        )
        state.generationStopRequested = true
        val timer = launch { awaitCancellation() }
        state.installDelayReplyJob(timer)
        yield()

        assertTrue(ChatSessionManager.cancelDelayedReply(sessionId, removeBubbles = true))
        timer.join()

        assertFalse(state.delayReply.hasPending())
        assertNull(state.delayReplyJob)
        assertFalse(state.generationStopRequested)
        assertEquals(listOf("stored"), state.messages.value.map { it.id })
        ChatSessionManager.release(sessionId)
    }

    @Test
    fun pauseDelayedReplyTimerStopsTimerKeepsBatchAndCommitCancelsAfterSuccess() = runBlocking {
        val sessionId = "delay-pause-${System.nanoTime()}"
        val state = ChatSessionManager.acquire(sessionId)
        val pending = DelayedReplyMessage(
            bubbleId = "pause-bubble",
            content = "等待期间的消息",
            timestamp = "1"
        )
        state.delayReply.arm(pending, nowMs = 0L, delayMs = 10_000L)
        state.messages.value = listOf(
            com.nekobot.app.data.model.Message(id = pending.bubbleId, role = "user", content = pending.content)
        )
        val timer = launch { awaitCancellation() }
        state.installDelayReplyJob(timer)

        // 设置保存/删除会话前先暂停：计时任务取消，操作进行中批次不可能被发往 AI
        assertTrue(ChatSessionManager.pauseDelayedReplyTimer(sessionId))
        timer.join()
        assertTrue(state.delayReply.hasPending())

        // 操作失败路径不做任何处理：批次与气泡保留，重进聊天页由 currentPlan() 重建计时
        assertEquals(listOf(pending.bubbleId), state.delayReply.pendingMessages().map { it.bubbleId })
        assertEquals(listOf(pending.bubbleId), state.messages.value.map { it.id })

        // 操作成功后提交撤回：批次与气泡一并清理
        assertTrue(ChatSessionManager.cancelDelayedReply(sessionId, removeBubbles = true))
        assertFalse(state.delayReply.hasPending())
        assertTrue(state.messages.value.isEmpty())
        ChatSessionManager.release(sessionId)
    }

    @Test
    fun pauseDelayedReplyTimerReturnsFalseForMissingSessionOrIdleState() = runBlocking {
        assertFalse(ChatSessionManager.pauseDelayedReplyTimer("missing-${System.nanoTime()}"))

        // 有会话状态但没有待发批次时，暂停是无害的 no-op
        val sessionId = "delay-pause-idle-${System.nanoTime()}"
        val state = ChatSessionManager.acquire(sessionId)
        assertFalse(ChatSessionManager.pauseDelayedReplyTimer(sessionId))
        assertFalse(state.delayReply.hasPending())
        ChatSessionManager.release(sessionId)
    }
}
