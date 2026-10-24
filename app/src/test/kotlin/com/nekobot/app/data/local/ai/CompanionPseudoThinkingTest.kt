package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CompanionPseudoThinkingTest {
    private val native = "Let me check the earlier conversation."
    private val thought = "我有点想他了。"
    private val body = "回来啦。（内心：见到他好开心。）<||>累不累？（内心：想给他倒杯茶。）"

    @Test fun jsonSelectsOnlyTaggedThoughtAndNeverCopiesNativeSignature() {
        val raw = LocalAiResult("<think>$thought</think>$body",
            thinkingContent = native, thinkingSignature = "native-signature")
        val result = characterThinkingResult(raw, true, bodyOnly = true)
        assertEquals(body, result.content)
        assertEquals(thought, result.thinkingContent)
        assertEquals("", result.thinkingSignature)
        assertEquals(native, raw.thinkingContent)
        assertEquals("native-signature", raw.thinkingSignature)
    }

    @Test fun everySplitBoundaryAndNativeArrivalOrderProduceTheSameVisibleAndSavedText() {
        val text = "<think>$thought</think>$body"
        for (split in 0..text.length) for (nativeFirst in listOf(true, false)) {
            val visible = StringBuilder()
            val thinking = StringBuilder()
            val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(
                onContentChunk = visible::append, onThinkingChunk = thinking::append), bodyOnly = true)
            stream.callbacks.onStart()
            if (nativeFirst) stream.callbacks.onThinkingChunk(native)
            assertTrue(thinking.isEmpty())
            stream.callbacks.onContentChunk(text.take(split))
            if (!nativeFirst) stream.callbacks.onThinkingChunk(native)
            stream.callbacks.onContentChunk(text.drop(split))
            val saved = stream.resolve(LocalAiResult(text, thinkingContent = native, thinkingSignature = "signature"))
            assertEquals(body, visible.toString())
            assertEquals(thought, thinking.toString())
            assertEquals(visible.toString(), saved.content)
            assertEquals(thinking.toString(), saved.thinkingContent)
            assertEquals("", saved.thinkingSignature)
        }
    }

    @Test fun missingTagsDoNotPromoteNativeReasoningActionsOrLeadingMonologue() {
        for (text in listOf(body, "（内心：很开心。）回来啦。", "（轻轻挥手）回来啦。", "")) {
            val result = characterThinkingResult(LocalAiResult(text, thinkingContent = native), true, bodyOnly = true)
            assertEquals(text, result.content)
            assertEquals("", result.thinkingContent)
        }
    }

    @Test fun incompleteTagsDoNotLeakAndUnclosedThoughtNeverBecomesSpokenDialogue() {
        for ((input, expected) in listOf("<thi" to "", "<think>$thought</thi" to thought, "<think>$thought" to thought)) {
            val result = characterThinkingResult(LocalAiResult(input, thinkingContent = native), true, bodyOnly = true)
            assertEquals("", result.content)
            assertEquals(expected, result.thinkingContent)
        }
    }

    @Test fun toolOnlyResponsePreservesCallsAndRawNativeMetadataWithoutInventingAThought() {
        val calls = listOf(mapOf<String, Any>("id" to "call-1", "name" to "lookup"))
        val raw = LocalAiResult("", thinkingContent = native, thinkingSignature = "signature",
            toolCalls = calls, finishReason = "tool_calls")
        val result = characterThinkingResult(raw, true, bodyOnly = true)
        assertEquals(calls, result.toolCalls)
        assertEquals("tool_calls", result.finishReason)
        assertEquals("", result.thinkingContent)
        assertEquals(native, raw.thinkingContent)
    }

    @Test fun retryStartClearsFailedAttemptAndNewChineseThoughtStreamsImmediately() {
        val visible = StringBuilder()
        val thinking = StringBuilder()
        val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onStart = { visible.setLength(0); thinking.setLength(0) },
            onContentChunk = visible::append, onThinkingChunk = thinking::append), bodyOnly = true)
        stream.callbacks.onStart()
        stream.callbacks.onContentChunk("<think>失败请求的片段")
        stream.callbacks.onStart()
        stream.callbacks.onThinkingChunk(native)
        stream.callbacks.onContentChunk("<think>$thought")
        assertEquals(thought, thinking.toString())
        assertTrue(visible.isEmpty())
        stream.callbacks.onContentChunk("</think>$body")
        stream.finish()
        assertEquals(body, visible.toString())
    }

    @Test fun heldFinalReplyIsReleasedBeforeParserFinishes() {
        val visible = StringBuilder()
        val thinking = StringBuilder()
        val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onContentChunk = visible::append, onThinkingChunk = thinking::append), bodyOnly = true)
        val guard = CompanionReplyGuard(stream.callbacks, hold = true)
        guard.callbacks.onStart()
        guard.callbacks.onThinkingChunk(native)
        guard.callbacks.onContentChunk("<think>$thought</think>$body")
        assertTrue(visible.isEmpty())
        val raw = LocalAiResult("<think>$thought</think>$body", thinkingContent = native)
        guard.finish(raw)
        stream.finish()
        val result = stream.resolve(raw)
        assertEquals(body, visible.toString())
        assertEquals(thought, thinking.toString())
        assertEquals(thought, result.thinkingContent)
    }
}
