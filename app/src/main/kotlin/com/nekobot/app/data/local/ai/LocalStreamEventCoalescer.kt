package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.RealtimeEvent

/**
 * 合并模型产生的细粒度流式分片，避免每个 token 都进入 UI 事件队列。
 *
 * 完整内容仍由调用方单独累加；这里仅改变事件批次，不截断任何正文或思考内容。
 */
internal class LocalStreamEventCoalescer(
    private val onEvent: (RealtimeEvent) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val emitIntervalNanos: Long = 100_000_000L,
    private val charBatch: Int = 256
) {
    private enum class Kind { CONTENT, REASONING }

    private val pending = StringBuilder()
    private var pendingKind: Kind? = null
    private var lastEmitNanos: Long? = null
    private var hasEmittedChunk = false

    fun onStart(sessionId: String? = null) {
        flush()
        pendingKind = null
        lastEmitNanos = null
        hasEmittedChunk = false
        onEvent(RealtimeEvent.StreamStart(sessionId))
    }

    fun onContentChunk(chunk: String) {
        append(Kind.CONTENT, chunk)
    }

    fun onReasoningChunk(chunk: String) {
        append(Kind.REASONING, chunk)
    }

    fun flush() {
        val kind = pendingKind ?: return
        if (pending.isEmpty()) return
        val chunk = pending.toString()
        pending.setLength(0)
        pendingKind = null
        lastEmitNanos = nowNanos()
        hasEmittedChunk = true
        onEvent(
            when (kind) {
                Kind.CONTENT -> RealtimeEvent.StreamChunk(chunk)
                Kind.REASONING -> RealtimeEvent.ReasoningChunk(chunk)
            }
        )
    }

    private fun append(kind: Kind, chunk: String) {
        if (chunk.isEmpty()) return
        if (pendingKind != null && pendingKind != kind) flush()
        pendingKind = kind
        pending.append(chunk)

        val now = nowNanos()
        val elapsed = lastEmitNanos?.let { now - it } ?: Long.MAX_VALUE
        if (!hasEmittedChunk || pending.length >= charBatch || elapsed >= emitIntervalNanos) {
            flush()
        }
    }
}
