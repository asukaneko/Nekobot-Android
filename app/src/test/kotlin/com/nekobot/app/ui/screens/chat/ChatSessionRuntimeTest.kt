package com.nekobot.app.ui.screens.chat

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionRuntimeTest {

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
}
