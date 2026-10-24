package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CompanionReplyGuardTest {
    private val markup = "<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name=\"agent_history_search\">"

    @Test fun everyStreamBoundaryKeepsInvocationTagsOutOfPublishedContent() {
        for (at in 0..markup.length) {
            val shown = StringBuilder()
            val guard = CompanionReplyGuard(LocalAiStreamCallbacks(onContentChunk = shown::append))
            guard.callbacks.onContentChunk(markup.take(at))
            guard.callbacks.onContentChunk(markup.drop(at))
            assertTrue("split=$at", runCatching { guard.finish(LocalAiResult(markup)) }.isFailure)
            assertEquals("split=$at", "", shown.toString())
        }
    }

    @Test fun stoppedRecallReleasesOnlyTheValidatedFinalAnswerAndThinking() {
        val shown = StringBuilder(); val thought = StringBuilder()
        val guard = CompanionReplyGuard(LocalAiStreamCallbacks(onContentChunk = shown::append,
            onThinkingChunk = thought::append), hold = true)
        guard.callbacks.onContentChunk("未确认的中间文字")
        guard.callbacks.onThinkingChunk("中间思考")
        assertEquals("", shown.toString()); assertEquals("", thought.toString())
        guard.finish(LocalAiResult("我记得是在书店门口。", thinkingContent = "想起那天了。"))
        assertEquals("我记得是在书店门口。", shown.toString())
        assertEquals("想起那天了。", thought.toString())
        val bad = CompanionReplyGuard(LocalAiStreamCallbacks(onContentChunk = { fail("坏格式不应发布") }), hold = true)
        bad.callbacks.onContentChunk(markup)
        assertTrue(runCatching { bad.finish(LocalAiResult(markup)) }.isFailure)
    }

    @Test fun ordinaryTextMentionsEscapedExamplesAndOtherMarkupKeepStreaming() {
        for (text in listOf("DSML只是格式名称。", "a < b，2 < 3。", "<think>普通正文</think>",
            "示例写成 &lt;｜DSML｜invoke，不是真实标签。", "结尾的符号 <")) {
            val shown = StringBuilder()
            val guard = CompanionReplyGuard(LocalAiStreamCallbacks(onContentChunk = shown::append))
            text.forEach { guard.callbacks.onContentChunk(it.toString()) }
            guard.finish(LocalAiResult(text))
            assertEquals(text, shown.toString())
        }
    }

    @Test fun aNewAttemptResetsBlockedStateWithoutPublishingAnOldPrefix() {
        val shown = StringBuilder()
        val guard = CompanionReplyGuard(LocalAiStreamCallbacks(onStart = { shown.setLength(0) }, onContentChunk = shown::append))
        guard.callbacks.onContentChunk(markup)
        guard.callbacks.onStart()
        guard.callbacks.onContentChunk("新的正常回复")
        guard.finish(LocalAiResult("新的正常回复"))
        assertEquals("新的正常回复", shown.toString())
    }

    @Test fun malformedAndUnfinishedTagsAreRejectedWithoutDroppingOrdinaryWords() {
        for (text in listOf("<｜DSML｜invoke", "<||DSML|| calls>", "</ | DSML | calls>", "<｜｜DSM")) {
            val shown = StringBuilder()
            val guard = CompanionReplyGuard(LocalAiStreamCallbacks(onContentChunk = shown::append))
            text.forEach { guard.callbacks.onContentChunk(it.toString()) }
            assertTrue(text, runCatching { guard.finish(LocalAiResult(text)) }.isFailure)
            assertEquals("", shown.toString())
        }
    }

    @Test fun validationWorksWithoutStreamingCallbacks() {
        assertTrue(runCatching { CompanionReplyGuard().finish(LocalAiResult(markup)) }.isFailure)
        CompanionReplyGuard().finish(LocalAiResult("正常的非流式回答"))
    }

    @Test fun unfinishedBufferedTagIsRejectedEvenIfTheCallerAggregatedOnlyPublishedText() {
        val shown = StringBuilder()
        val guard = CompanionReplyGuard(LocalAiStreamCallbacks(onContentChunk = shown::append))
        guard.callbacks.onContentChunk("<｜｜DSM")
        assertTrue(runCatching { guard.finish(LocalAiResult(shown.toString())) }.isFailure)
        assertEquals("", shown.toString())
    }
}
