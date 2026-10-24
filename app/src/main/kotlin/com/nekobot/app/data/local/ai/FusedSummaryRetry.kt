package com.nekobot.app.data.local.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Two corrections at most; the existing compression deadline covers the entire batch, including retry. */
internal object FusedSummaryRetry {
    const val MAX_ATTEMPTS = 3

    suspend fun <T> saving(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: FusedSummaryFailure) { throw failure }
    catch (_: Exception) { throw FusedSummaryFailure(FusedSummaryFailure.Reason.STORAGE) }

    suspend fun <T> run(
        timeoutMillis: Long = defaultFailoverTimeout("compression").inWholeMilliseconds,
        pause: suspend (Long) -> Unit = { delay(it) },
        action: suspend () -> T
    ): T {
        try {
            return withTimeout(timeoutMillis) {
                for (attempt in 1..MAX_ATTEMPTS) {
                    currentCoroutineContext().ensureActive()
                    try {
                        return@withTimeout action()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val failure = FusedSummaryFailure.from(error)
                        // The model queue already tried its configured candidates. Do not re-enter cooldown
                        // and replace the original cause with a misleading "no available model" failure.
                        if (attempt == MAX_ATTEMPTS || !failure.reason.retryable || error is FailoverAllFailedException) throw failure
                        pause(1000L shl (attempt - 1))
                    }
                }
                throw FusedSummaryFailure(FusedSummaryFailure.Reason.UNKNOWN)
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw FusedSummaryFailure(FusedSummaryFailure.Reason.TIMEOUT)
        }
    }
}
