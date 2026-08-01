package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.RealtimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreamEventCoalescerTest {

    @Test
    fun tenThousandTinyChunksArePreservedWithoutTenThousandUiEvents() {
        val events = mutableListOf<RealtimeEvent>()
        val coalescer = LocalStreamEventCoalescer(
            onEvent = events::add,
            nowNanos = { 0L },
            emitIntervalNanos = Long.MAX_VALUE,
            charBatch = 256
        )

        coalescer.onStart()
        repeat(10_000) { coalescer.onContentChunk("x") }
        coalescer.flush()

        val contentEvents = events.filterIsInstance<RealtimeEvent.StreamChunk>()
        assertEquals(10_000, contentEvents.sumOf { it.chunk.length })
        assertEquals("x".repeat(10_000), contentEvents.joinToString("") { it.chunk })
        assertTrue(contentEvents.size < 50)
    }

    @Test
    fun switchingFromReasoningToContentFlushesInOrder() {
        val events = mutableListOf<RealtimeEvent>()
        val coalescer = LocalStreamEventCoalescer(
            onEvent = events::add,
            nowNanos = { 0L },
            emitIntervalNanos = Long.MAX_VALUE,
            charBatch = 256
        )

        coalescer.onStart()
        coalescer.onReasoningChunk("思")
        coalescer.onReasoningChunk("考")
        coalescer.onContentChunk("答")
        coalescer.onContentChunk("案")
        coalescer.flush()

        assertTrue(events[0] is RealtimeEvent.StreamStart)
        assertEquals("思考", events.filterIsInstance<RealtimeEvent.ReasoningChunk>().joinToString("") { it.chunk })
        assertEquals("答案", events.filterIsInstance<RealtimeEvent.StreamChunk>().joinToString("") { it.chunk })
        val lastReasoning = events.indexOfLast { it is RealtimeEvent.ReasoningChunk }
        val firstContent = events.indexOfFirst { it is RealtimeEvent.StreamChunk }
        assertTrue(lastReasoning < firstContent)
    }
}
