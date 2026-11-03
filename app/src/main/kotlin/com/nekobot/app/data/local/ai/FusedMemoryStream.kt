package com.nekobot.app.data.local.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class IncompleteMemoryStream(val causeCode: String = "no_done") : IllegalStateException(FusedMemoryText.INCOMPLETE.value())

/** 网络回调不挂起；消费者按顺序执行每行独立事务。 */
internal object FusedMemoryStream {
    private sealed interface Event {
        data object Reset : Event
        data class Line(val text: String) : Event
    }
    data class Summary(val ok: Int, val dropped: Map<String, Int>, val uncertain: Int) {
        fun requireReceiptReady() {
            if (dropped.isNotEmpty()) throw FusedSummaryFailure(
                if ("source_changed" in dropped) FusedSummaryFailure.Reason.SOURCE_CHANGED
                else FusedSummaryFailure.Reason.FORMAT)
        }
    }

    suspend fun consume(generate: suspend (LocalAiStreamCallbacks) -> String,
        reset: () -> Unit = {}, log: (String, String) -> Unit = ::fusedMemoryLog,
        write: suspend (FusedMemoryLine) -> FusedMemoryWriteResult): Summary = coroutineScope {
        val channel = Channel<Event>(Channel.UNLIMITED)
        val buffer = StringBuilder()
        var hadChunks = false
        var done = false
        var afterDone = false
        var ok = 0
        var uncertain = 0
        val dropped = linkedMapOf<String, Int>()
        fun feed(chunk: String) = synchronized(buffer) {
            hadChunks = true
            buffer.append(chunk)
            while (true) {
                val end = buffer.indexOf("\n")
                if (end < 0) break
                channel.trySend(Event.Line(buffer.substring(0, end).removeSuffix("\r")))
                buffer.delete(0, end + 1)
            }
        }
        val worker = launch {
            for (event in channel) when (event) {
                Event.Reset -> { done = false; afterDone = false; reset() }
                is Event.Line -> if (event.text.isNotBlank()) {
                    val parsed = FusedMemoryValidation.parseLine(event.text)
                    if (done) afterDone = true
                    if (parsed == FusedMemoryLine.Done) done = true
                    else {
                        val result = write(parsed)
                        if (result.success) { if (!result.skipped) ok++; if (result.uncertain) uncertain++ }
                        else dropped[result.reason!!] = (dropped[result.reason] ?: 0) + 1
                    }
                }
            }
        }
        var failure: Exception? = null
        try {
            val result = generate(LocalAiStreamCallbacks(onStart = {
                synchronized(buffer) { buffer.clear(); hadChunks = false; channel.trySend(Event.Reset) }
            }, onContentChunk = ::feed))
            synchronized(buffer) {
                if (!hadChunks) feed(result)
                if (buffer.isNotBlank()) channel.trySend(Event.Line(buffer.toString()))
                buffer.clear()
            }
        } catch (cancelled: CancellationException) {
            log("warn", "interrupted cause=cancel committed=$ok")
            log("info", "ok=$ok dropped=$dropped uncertain=$uncertain")
            throw cancelled
        } catch (error: Exception) { failure = error
        } finally { channel.close() }
        worker.join()
        log("info", "ok=$ok dropped=$dropped uncertain=$uncertain")
        failure?.let {
            log("warn", "interrupted cause=${(it as? IncompleteMemoryStream)?.causeCode ?: "network"} committed=$ok")
            throw it
        }
        if (!done || afterDone) {
            log("warn", "interrupted cause=no_done committed=$ok")
            throw IncompleteMemoryStream()
        }
        Summary(ok, dropped, uncertain)
    }
}
