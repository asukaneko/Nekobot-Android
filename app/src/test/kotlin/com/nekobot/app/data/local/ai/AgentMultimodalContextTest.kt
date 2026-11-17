package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMultimodalContextTest {
    private fun call(id: String): Map<String, Any> = mapOf(
        "role" to "assistant", "content" to "",
        "tool_calls" to listOf(mapOf("id" to id, "type" to "function",
            "function" to mapOf("name" to "read_image", "arguments" to "{}")))
    )

    private fun image(id: String): Map<String, Any> = mapOf(
        "role" to "tool", "tool_call_id" to id, "name" to "read_image",
        "content" to buildToolMessageContent(mapOf(
            "path" to "$id.jpg", "_image_urls" to listOf("data:image/jpeg;base64," + "A".repeat(50_000))
        ))
    )

    @Test
    fun `serialized and live multimodal history use the same token estimate`() {
        val history = listOf(call("image-1"), image("image-1"))
        val json = encodeToolCallHistory(history)

        assertEquals(estimateLocalMessagesTokens(history), estimateToolCallHistoryTokens(json))
        assertTrue(estimateToolCallHistoryTokens(json) < 10_000)
    }

    @Test
    fun `synchronizing trimmed history updates results and removes complete discarded blocks`() {
        val history = listOf(call("old"), image("old"), call("new"), image("new"))
        val retainedResult = image("new") + ("content" to "图片已移出上下文")
        val active = listOf(call("new"), retainedResult)
        val payloads = synchronizeAgentToolMessagePayloads(history.map(::encodeAgentToolMessageRow), active)

        assertEquals(listOf(null, null), payloads.take(2))
        val restored = payloads.filterNotNull().mapNotNull(::decodeAgentToolMessageRow)
        assertEquals(active, restored)
        assertEquals(estimateLocalMessagesTokens(active), estimateLocalMessagesTokens(restored))
    }

    @Test
    fun `current turn history survives removal of prepared context blocks`() {
        val user = mapOf<String, Any>("role" to "user", "content" to "查公告")
        val prepared = listOf(user, call("previous"), image("previous"))
        val newHistory = listOf(call("current"), image("current"))

        assertEquals(newHistory, extractCurrentTurnToolCallHistory(listOf(user) + newHistory, prepared))
    }

    @Test
    fun `long image loop synchronizes trimmed recovery history before the next model call`() = runBlocking {
        var iteration = 0
        var trims = 0
        val persisted = mutableListOf<String>()
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "读取公告并回答")),
            modelCall = { messages, _ ->
                assertTrue(estimateLocalMessagesTokens(messages) <= 24_000)
                if (iteration++ < 12) mapOf(
                    "content" to "", "finish_reason" to "tool_calls",
                    "tool_calls" to listOf(mapOf("id" to "image-$iteration", "name" to "read_image", "arguments" to mapOf("path" to "image-$iteration.jpg")))
                ) else mapOf("content" to "已确认", "finish_reason" to "stop")
            },
            toolExecutor = { toolCall, _, _, _ -> mapOf(
                "path" to "${toolCall["id"]}.jpg", "_image_urls" to listOf("data:image/jpeg;base64," + "A".repeat(50_000))
            ) },
            hooks = ToolLoopHooks(
                onToolMessageAppended = { message, _ -> persisted.add(encodeAgentToolMessageRow(message)) },
                onContextTrimmed = { messages ->
                    trims++
                    val synced = synchronizeAgentToolMessagePayloads(persisted, messages).filterNotNull()
                    persisted.clear()
                    persisted.addAll(synced)
                }
            ),
            maxIterations = 20,
            contextBudgetTokens = { 30_000 }
        )

        assertEquals("已确认", result.finalContent)
        assertTrue(trims > 0)
        val restored = persisted.mapNotNull(::decodeAgentToolMessageRow)
        assertEquals(extractToolCallHistory(result.toolMessages), restored)
        assertTrue(estimateLocalMessagesTokens(restored) < 24_000)
    }
}
