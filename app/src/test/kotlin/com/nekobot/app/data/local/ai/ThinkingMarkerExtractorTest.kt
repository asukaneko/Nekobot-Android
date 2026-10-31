package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingMarkerExtractorTest {

    private fun config(start: String = "<think>", end: String = "</think>") =
        ThinkingSourceConfig(markerStart = start, markerEnd = end)

    private fun List<ExtractEvent>.content() = filterIsInstance<ExtractEvent.Content>()
        .joinToString("") { it.text }

    private fun List<ExtractEvent>.thinking() = filterIsInstance<ExtractEvent.Thinking>()
        .joinToString("") { it.text }

    @Test
    fun `markers disabled passes content through untouched`() {
        val extractor = ThinkingMarkerExtractor(ThinkingSourceConfig())
        val events = extractor.feed("<think>abc</think>hello") + extractor.finish()
        assertEquals("<think>abc</think>hello", events.content())
        assertEquals("", events.thinking())
        assertTrue(events.all { it is ExtractEvent.Content })
    }

    @Test
    fun `marker at start is clipped into thinking`() {
        val extractor = ThinkingMarkerExtractor(config())
        val events = extractor.feed("<think>secret</think>hello") + extractor.finish()
        assertEquals("secret", events.thinking())
        assertEquals("hello", events.content())
        assertTrue(extractor.didExtract)
    }

    @Test
    fun `marker split across chunks is still clipped`() {
        val extractor = ThinkingMarkerExtractor(config())
        val events = buildList {
            addAll(extractor.feed("<thi"))
            addAll(extractor.feed("nk>sec"))
            addAll(extractor.feed("ret</thi"))
            addAll(extractor.feed("nk>hi"))
            addAll(extractor.finish())
        }
        assertEquals("secret", events.thinking())
        assertEquals("hi", events.content())
        assertTrue(extractor.didExtract)
    }

    @Test
    fun `start marker without end marker becomes plain content on finish`() {
        val extractor = ThinkingMarkerExtractor(config())
        val fed = extractor.feed("<think>abc") + extractor.feed("def")
        assertTrue(fed.isEmpty())
        val events = extractor.finish()
        assertEquals("", events.thinking())
        assertEquals("<think>abcdef", events.content())
        assertTrue(!extractor.didExtract)
    }

    @Test
    fun `reply that does not start like a marker passes through`() {
        val extractor = ThinkingMarkerExtractor(config())
        val events = extractor.feed("hello <think>abc</think>") + extractor.finish()
        assertEquals("hello <think>abc</think>", events.content())
        assertEquals("", events.thinking())
    }

    @Test
    fun `partial prefix that turns out to be ordinary text passes through`() {
        val extractor = ThinkingMarkerExtractor(config())
        val events = extractor.feed("<thi") + extractor.feed("s is not a marker")
        val finished = extractor.finish()
        assertEquals("<this is not a marker", (events + finished).content())
        assertEquals("", (events + finished).thinking())
    }

    @Test
    fun `leading whitespace before marker is ignored`() {
        val extractor = ThinkingMarkerExtractor(config())
        val events = extractor.feed("  \n<think>t</think>  world") + extractor.finish()
        assertEquals("t", events.thinking())
        assertEquals("  world", events.content())
    }

    @Test
    fun `content after marker keeps streaming through`() {
        val extractor = ThinkingMarkerExtractor(config())
        val events = buildList {
            addAll(extractor.feed("<think>x</think>a"))
            addAll(extractor.feed("b"))
            addAll(extractor.feed("c"))
            addAll(extractor.finish())
        }
        assertEquals("abc", events.content())
        assertEquals("x", events.thinking())
    }

    @Test
    fun `abandon restores pending text for field-first handling`() {
        val extractor = ThinkingMarkerExtractor(config())
        val fed = extractor.feed("<think>pending")
        assertTrue(fed.isEmpty())
        val abandoned = extractor.abandon()
        assertEquals("<think>pending", abandoned.content())
        assertEquals("", abandoned.thinking())
        assertTrue(!extractor.didExtract)
        assertEquals("later", extractor.feed("later").content())
    }
}
