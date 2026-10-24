package com.nekobot.app.data.local.diagnostics

import com.nekobot.app.ServiceContainer
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Best effort metadata only. Recording failure must never change the chat or import result. */
internal object ProblemDiagnostics {
    private val losses = java.util.concurrent.atomic.AtomicLong()
    fun recordingLosses(): Long = losses.get()
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        { r -> Thread(r, "problem-diagnostic-writer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun store(): ProblemDiagnosticStore? = ServiceContainer.appContext?.filesDir?.let {
        ProblemDiagnosticStore(File(it, "problem_diagnostics"))
    }
    fun traceStore(): DiagnosticTraceStore? = ServiceContainer.appContext?.noBackupFilesDir?.let {
        DiagnosticTraceStore(File(it, "problem_request_traces"))
    }
    fun trace(attempt: String, profile: String, session: String, turn: String) =
        DiagnosticTraceSession(traceStore(), attempt, profile, session, turn) { losses.incrementAndGet() }
    fun record(id: String, profile: String, session: String, turn: String, type: String, kind: String,
               values: Map<String, Any?>) {
        runCatching {
        val target = store() ?: return
        val safe = DiagnosticMetadata.clean(values)
        // Recreate typed numbers/flags for the store's second allowlist pass.
        val copy = values.filterKeys { it in safe }.toMap()
        val now = System.currentTimeMillis()
        writer.execute { runCatching { target.append(id, profile, session, turn, type, kind, copy, now) }.onFailure { losses.incrementAndGet() } }
        }.onFailure { losses.incrementAndGet() }
    }
    /** Called only by the IO collector; no waiting on the UI or model thread. */
    fun flush(): Boolean {
        val latch = CountDownLatch(1)
        return runCatching { writer.execute { latch.countDown() }; latch.await(3, TimeUnit.SECONDS) }.getOrDefault(false)
    }
}
