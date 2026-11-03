package com.nekobot.app.data.local.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FusedMemoryStreamTest {
    private val line = """{"op":"add","category":"episode","content":"虚构经历","evidence":["S1"],"date_type":"day"}"""
    private val done = "{\"done\":true}"
    @Test fun arbitraryChunkBoundariesHaveIdenticalResult() = runBlocking {
        val text = "$line\r\n$line\n$done"
        for (size in 1..text.length) {
            var count = 0
            val result = FusedMemoryStream.consume({ cb -> text.chunked(size).forEach(cb.onContentChunk); text }) {
                count++; FusedMemoryWriteResult("m$count")
            }
            assertEquals(2, count); assertEquals(2, result.ok)
        }
    }
    @Test fun firstLineCommitsWhileProducerIsStillRunning() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        FusedMemoryStream.consume({ cb ->
            cb.onContentChunk("$line\n")
            withTimeout(1000) { committed.await() }
            cb.onContentChunk(done); ""
        }) { committed.complete(Unit); FusedMemoryWriteResult("first") }
        Unit
    }
    @Test fun malformedLineDoesNotStopValidLines() = runBlocking {
        val result = FusedMemoryStream.consume({ "$line\nnot-json\n$line\n$done\n" }) {
            if (it is FusedMemoryLine.Rejected) FusedMemoryWriteResult(reason = it.reason) else FusedMemoryWriteResult("ok")
        }
        assertEquals(2, result.ok); assertEquals(mapOf("bad_format" to 1), result.dropped)
    }
    @Test fun networkErrorDrainsCompletedLinesWithoutReceiptSuccess() = runBlocking {
        var committed = 0
        try {
            FusedMemoryStream.consume({ cb -> cb.onContentChunk("$line\n$line\n{\"op\""); error("network") }) {
                committed++; FusedMemoryWriteResult("$committed")
            }; fail()
        } catch (e: IllegalStateException) { assertEquals("network", e.message) }
        assertEquals(2, committed)
    }
    @Test fun missingDoneNeverReportsSuccessEvenAfterFiveCommits() = runBlocking {
        var committed = 0
        try { FusedMemoryStream.consume({ List(5) { line }.joinToString("\n") }) {
            committed++; FusedMemoryWriteResult("$committed")
        }; fail() } catch (_: IncompleteMemoryStream) { }
        assertEquals(5, committed)
    }
    @Test fun failoverResetsPartialLineAndDoneButKeepsCommittedEntries() = runBlocking {
        val first = CompletableDeferred<Unit>(); var resets = 0; var committed = 0
        val result = FusedMemoryStream.consume({ cb ->
            cb.onStart(); cb.onContentChunk("$line\n{\"broken\"")
            first.await()
            cb.onStart(); cb.onContentChunk("$line\n$done"); ""
        }, reset = { resets++ }) { committed++; first.complete(Unit); FusedMemoryWriteResult("$committed") }
        assertEquals(2, resets); assertEquals(2, result.ok)
    }
    @Test fun cancellationKeepsEarlierCommitAndPropagates() = runBlocking {
        var committed = 0; val signal = CompletableDeferred<Unit>()
        val job = launch {
            FusedMemoryStream.consume({ cb -> cb.onContentChunk("$line\n"); signal.await(); awaitCancellation() }) {
                committed++; signal.complete(Unit); FusedMemoryWriteResult("m")
            }
        }
        signal.await(); job.cancelAndJoin(); assertEquals(1, committed)
    }
    @Test fun doneMustBeLastAndEmptyOutputIsIncomplete() = runBlocking {
        for (text in listOf("", "$done\n$line", "$done\n$done")) {
            try { FusedMemoryStream.consume({ text }) { FusedMemoryWriteResult() }; fail(text) }
            catch (_: IncompleteMemoryStream) { }
        }
    }
    @Test fun interruptionLogsHaveCauseAndCommittedCountWithoutMessageBody() = runBlocking {
        for (truncated in listOf(false, true)) {
            val logs = mutableListOf<String>()
            try {
                FusedMemoryStream.consume({ cb -> cb.onContentChunk("$line\n")
                    if (truncated) throw IncompleteMemoryStream("truncated") else error("private network detail")
                }, log = { _, message -> logs += message }) { FusedMemoryWriteResult("saved") }
                fail()
            } catch (_: IllegalStateException) { }
            assertTrue(logs.any { "cause=${if (truncated) "truncated" else "network"} committed=1" in it })
            assertTrue(logs.any { "ok=1" in it })
            assertTrue(logs.none { "虚构经历" in it || "private network detail" in it })
        }
    }
}
