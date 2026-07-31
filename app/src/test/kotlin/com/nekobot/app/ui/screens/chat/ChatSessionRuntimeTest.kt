package com.nekobot.app.ui.screens.chat

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionRuntimeTest {

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
}
