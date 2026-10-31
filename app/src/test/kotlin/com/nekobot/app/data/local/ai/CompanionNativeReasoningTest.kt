package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CompanionNativeReasoningTest {
    @Test fun nonStreamingKeepsBodyMonologueSeparateFromNativeReasoning() {
        val result = LocalAiResult(content = "（内心：有点想他。）你回来啦。", thinkingContent = "先理解这句话，再回答。")
        assertEquals(result, characterThinkingResult(result, enabled = true, nativeOnly = true))
    }

    @Test fun bodyOnlyNeverInventsReasoningContent() {
        val result = LocalAiResult(content = "（内心：好开心。）今天过得好吗？")
        val resolved = characterThinkingResult(result, enabled = true, nativeOnly = true)
        assertEquals(result.content, resolved.content)
        assertTrue(resolved.thinkingContent.isEmpty())
    }

    @Test fun streamingRelaysEachNativeAndBodyChunkWithoutCrossingChannels() {
        val body = StringBuilder()
        val thought = StringBuilder()
        val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onContentChunk = { body.append(it) }, onThinkingChunk = { thought.append(it) }), nativeOnly = true)
        stream.callbacks.onStart()
        stream.callbacks.onContentChunk("（内心：")
        assertEquals("（内心：", body.toString())
        assertTrue(thought.isEmpty())
        stream.callbacks.onThinkingChunk("先理解。")
        stream.callbacks.onContentChunk("好开心。）你回来啦。")
        stream.callbacks.onThinkingChunk("再回答。")
        val original = LocalAiResult(content = body.toString(), thinkingContent = thought.toString())
        assertEquals(original, stream.resolve(original))
        assertEquals("（内心：好开心。）你回来啦。", body.toString())
        assertEquals("先理解。再回答。", thought.toString())
    }

    @Test fun ordinaryCharacterStillUsesItsExistingBodyThinkingParser() {
        val resolved = characterThinkingResult(LocalAiResult(content = "（有点想他。）你回来啦。"), enabled = true)
        assertEquals("有点想他。", resolved.thinkingContent)
        assertEquals("你回来啦。", resolved.content)
    }
}
