package com.nekobot.app.data.local.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class FusedSummaryRecoveryTest {
    @Test fun incompleteBatchRetriesAtMostTwiceWithBackoff() = runBlocking {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val result = FusedSummaryRetry.run(pause = { pauses += it }) {
            calls++
            if (calls < 3) throw IncompleteMemoryStream()
            "saved"
        }
        assertEquals("saved", result)
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 2000L), pauses)
    }

    @Test fun repeatedNetworkFailureStopsAndManualRetryCanSucceed() = runBlocking {
        var calls = 0
        try {
            FusedSummaryRetry.run(pause = {}) { calls++; throw IOException("private token and chat") }
            fail()
        } catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.NETWORK, e.reason) }
        assertEquals(3, calls)
        assertEquals("ok", FusedSummaryRetry.run { "ok" })
    }

    @Test fun alreadyExhaustedModelQueueIsNotMultipliedOrHiddenByCooldown() = runBlocking {
        var calls = 0
        try {
            FusedSummaryRetry.run(pause = {}) {
                calls++
                throw FailoverAllFailedException("compression", listOf("fictional"),
                    listOf(FailoverFailure("fictional", -1, "private endpoint")), "private detail")
            }
            fail()
        } catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.NETWORK, e.reason) }
        assertEquals(1, calls)
    }

    @Test fun permanentErrorsDoNotAutomaticallyRepeatRequests() = runBlocking {
        for (reason in listOf(FusedSummaryFailure.Reason.AUTH, FusedSummaryFailure.Reason.QUOTA,
            FusedSummaryFailure.Reason.CAPACITY, FusedSummaryFailure.Reason.STORAGE)) {
            var calls = 0
            try { FusedSummaryRetry.run(pause = {}) { calls++; throw FusedSummaryFailure(reason) }; fail() }
            catch (e: FusedSummaryFailure) { assertEquals(reason, e.reason) }
            assertEquals(1, calls)
        }
    }

    @Test fun batchDeadlineIncludesGenerationAndBackoffAndStopsBeforeReceipt() = runBlocking {
        var receipt = false
        try {
            FusedSummaryRetry.run(timeoutMillis = 30) { awaitCancellation(); receipt = true }
            fail()
        } catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.TIMEOUT, e.reason) }
        assertFalse(receipt)
        try {
            FusedSummaryRetry.run(timeoutMillis = 30, pause = { awaitCancellation() }) {
                throw IncompleteMemoryStream()
            }
            fail()
        } catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.TIMEOUT, e.reason) }
    }

    @Test fun externalCancellationNeverBecomesTimeoutOrASecondAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            FusedSummaryRetry.run { calls++; entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
        try {
            withTimeout(20) { FusedSummaryRetry.run { awaitCancellation() } }
            fail()
        } catch (_: TimeoutCancellationException) { }
    }

    @Test fun storageFailureIsSafeAndCannotBeReportedAsSuccess() = runBlocking {
        var receipt = false
        try {
            FusedSummaryRetry.saving { throw IOException("SQL private text secret") }
            receipt = true
            fail()
        } catch (e: FusedSummaryFailure) {
            assertEquals(FusedSummaryFailure.Reason.STORAGE, e.reason)
            assertFalse(e.message.orEmpty().contains("secret"))
        }
        assertFalse(receipt)
    }

    @Test fun falseSuccessOrMissingDurableProgressNeverReachesReply() = runBlocking {
        for (reportedSuccess in listOf(false, true)) {
            var replied = false
            try {
                FusedCompanionSummary.advance(setOf("old"), { reportedSuccess }, { setOf("old") })
                replied = true
                fail()
            } catch (_: FusedSummaryFailure) { }
            assertFalse(replied)
        }
        FusedCompanionSummary.advance(setOf("old"), { true }, { setOf("old", "new") })
    }

    @Test fun rejectedEntriesNeverCreateACompletionReceipt() {
        for ((reason, expected) in listOf("unknown_ref" to FusedSummaryFailure.Reason.FORMAT,
            "bad_format" to FusedSummaryFailure.Reason.FORMAT,
            "source_changed" to FusedSummaryFailure.Reason.SOURCE_CHANGED)) {
            try { FusedMemoryStream.Summary(2, mapOf(reason to 1), 0).requireReceiptReady(); fail() }
            catch (e: FusedSummaryFailure) { assertEquals(expected, e.reason) }
        }
        FusedMemoryStream.Summary(0, emptyMap(), 0).requireReceiptReady()
    }

    @Test fun causesAreClassifiedWithoutDisplayingProviderBodiesOrCredentials() {
        val secret = "Bearer sk-fictional-secret https://private.test/path?token=hidden private-chat"
        val cases = listOf(
            IOException(secret) to FusedSummaryFailure.Reason.NETWORK,
            SocketTimeoutException(secret) to FusedSummaryFailure.Reason.TIMEOUT,
            FailoverHttpException(401, secret) to FusedSummaryFailure.Reason.AUTH,
            FailoverHttpException(429, "insufficient_quota $secret") to FusedSummaryFailure.Reason.QUOTA,
            FailoverHttpException(429, secret) to FusedSummaryFailure.Reason.RATE_LIMIT,
            FailoverHttpException(400, "context_length_exceeded $secret") to FusedSummaryFailure.Reason.CAPACITY,
            IncompleteMemoryStream() to FusedSummaryFailure.Reason.INCOMPLETE,
            IllegalStateException("模型返回了空响应") to FusedSummaryFailure.Reason.FORMAT,
            FailoverHttpException(0, "响应 JSON 格式无效") to FusedSummaryFailure.Reason.FORMAT,
            IllegalStateException("未配置可用的聊天模型，请在故障转移队列中启用 purpose=chat 的模型") to FusedSummaryFailure.Reason.UNAVAILABLE,
            IllegalStateException(secret) to FusedSummaryFailure.Reason.UNKNOWN)
        for ((error, reason) in cases) {
            val safe = FusedSummaryFailure.from(error)
            assertEquals(reason, safe.reason)
            assertNull(safe.cause)
            for (language in listOf("zh-CN", "zh-TW", "en", "ja", "ko")) {
                val text = safe.reason.text.value(language)
                assertTrue(text.isNotBlank())
                for (privatePart in listOf("sk-fictional", "private.test", "hidden", "private-chat")) {
                    assertFalse(text.contains(privatePart))
                }
            }
        }
    }
}
