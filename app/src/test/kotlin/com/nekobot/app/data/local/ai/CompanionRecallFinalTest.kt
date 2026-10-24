package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallFinalTest {
    private val call = listOf(mapOf<String, Any>("id" to "c1", "name" to "agent_recall_search"))

    @Test fun exhaustedRecallWithToolCallsUsesTheNoToolAnswer() = runBlocking {
        var retried = 0
        val result = CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), mustAnswer = true) {
            retried++; LocalAiResult("我想起来了，是你换部门的那件事。")
        }
        assertEquals(1, retried)
        assertEquals("我想起来了，是你换部门的那件事。", result.content)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test fun failedRetryFailsInsteadOfInventingACharacterReply() = runBlocking {
        for (second in listOf(LocalAiResult("", toolCalls = call), LocalAiResult("  "), LocalAiResult("", error = "超时"))) {
            assertTrue(runCatching { CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), true) { second } }.isFailure)
        }
        assertTrue(runCatching { CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), true) { error("网络") } }.isFailure)
    }

    @Test fun normalRepliesAreUntouched() = runBlocking {
        val normal = LocalAiResult("好呀")
        assertSame(normal, CompanionRecallFinal.resolve(normal, true) { error("不应重试") })
        val tools = LocalAiResult("", toolCalls = call)
        assertSame(tools, CompanionRecallFinal.resolve(tools, false) { error("不应重试") })
    }

    @Test fun finalRequestAddsOnlyAnInternalSystemNote() {
        val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "去翻一翻记忆"))
        val final = CompanionRecallFinal.finalMessages(messages)
        assertEquals(messages, final.dropLast(1))
        assertEquals("system", final.last()["role"])
    }

    @Test fun rawDsmlDoesNotBecomeAnAnswerOrAnImplicitRetry() = runBlocking {
        for (markup in listOf("<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name=\"agent_history_search\">",
            "<|DSML|invoke name=\"agent_history_search\"", "前文\n<｜DSML｜function_calls>")) {
            var retries = 0
            assertTrue(runCatching { CompanionRecallFinal.resolve(LocalAiResult(markup), true) {
                retries++; LocalAiResult("不应执行")
            } }.isFailure)
            assertEquals(0, retries)
        }
    }

    @Test fun retryAlsoRejectsRawDsmlAndRunsAtMostOnce() = runBlocking {
        var retries = 0
        assertTrue(runCatching { CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), true) {
            retries++; LocalAiResult("<｜｜DSML｜｜ calls>")
        } }.isFailure)
        assertEquals(1, retries)
    }

    @Test fun emptyFinalResultFailsAndOrdinaryDsmlDiscussionIsAllowed() = runBlocking {
        assertTrue(runCatching { CompanionRecallFinal.resolve(LocalAiResult("  "), true) { error("不应重试") } }.isFailure)
        val normal = LocalAiResult("DSML只是一个格式名称，我们接着聊吧。")
        assertSame(normal, CompanionRecallFinal.resolve(normal, true) { error("不应重试") })
    }

    @Test fun finalSystemNoteDoesNotAccumulateAcrossPreparationAndRetry() {
        val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "原问题"))
        val once = CompanionRecallFinal.finalMessages(messages)
        assertEquals(once, CompanionRecallFinal.finalMessages(once))
        assertEquals(messages, once.dropLast(1))
    }

    @Test fun cancellationIsNotConvertedIntoASuccessfulReply() = runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("停止")
        val failure = runCatching { CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), true) { throw cancelled } }.exceptionOrNull()
        assertSame(cancelled, failure)
    }
}
