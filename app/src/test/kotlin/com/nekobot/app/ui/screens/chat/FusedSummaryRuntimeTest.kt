package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FusedSummaryRuntimeTest {
    private suspend fun <T> waiting(state: ChatSessionState, block: suspend () -> T): T =
        FusedCompanionSummary.waiting({ active ->
            state.applyContextCompressionNotice(active, "Synthetic input", memorySummarizing = true)
        }, block)

    @Test fun timeoutClearsBusyNoticeAndNextAttemptCanStart() = runBlocking {
        val state = ChatSessionState("fictional-timeout")
        try {
            waiting(state) {
                assertNotNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
                FusedSummaryRetry.run(timeoutMillis = 30) { awaitCancellation() }
            }
            fail()
        } catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.TIMEOUT, e.reason) }
        assertNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
        assertEquals("ok", waiting(state) { "ok" })
        assertNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
    }

    @Test fun cancellationClearsNoticeReleasesJobAndDoesNotStartReply() = runBlocking {
        val state = ChatSessionState("fictional-cancel")
        val started = CompletableDeferred<Unit>()
        var replied = false
        val job = launch {
            waiting(state) { FusedSummaryRetry.run { started.complete(Unit); awaitCancellation() } }
            replied = true
        }
        assertTrue(state.installLocalChatJob(job))
        job.invokeOnCompletion { state.clearLocalChatJob(job) }
        started.await()
        job.cancelAndJoin()
        assertFalse(replied)
        assertNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
        assertNull(state.localChatJob)
        val next = launch { waiting(state) { "retry" } }
        assertTrue(state.installLocalChatJob(next))
        next.join()
        assertTrue(state.clearLocalChatJob(next))
    }

    @Test fun permanentFailureShowsSpecificSafeCauseAndClearsNotice() = runBlocking {
        val state = ChatSessionState("fictional-auth")
        var shown = ""
        try {
            waiting(state) {
                FusedSummaryRetry.run {
                    throw FailoverHttpException(401, "Bearer fictional-secret private user content")
                }
            }
        } catch (e: FusedSummaryFailure) {
            shown = e.message.orEmpty()
            assertEquals(FusedSummaryFailure.Reason.AUTH, e.reason)
        }
        assertTrue(shown.isNotBlank())
        assertFalse(shown.contains("fictional-secret"))
        assertFalse(shown.contains("private user"))
        assertNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
    }
}
