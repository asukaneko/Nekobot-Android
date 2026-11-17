package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具循环逐轮记录用量：每完成一轮工具调用就把该轮的模型与 Token 交给落库钩子。 */
class AgentModelUsageRecordingTest {
    @Test
    fun everyToolRoundCarriesItsOwnModelAndTokens() = runBlocking {
        var iteration = 0
        val appended = mutableListOf<Pair<Map<String, Any>, ModelCallUsage?>>()
        val recorded = mutableListOf<ModelCallUsage>()
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "synthetic prompt")),
            modelCall = { _, _ ->
                iteration++
                if (iteration < 3) {
                    mapOf(
                        "content" to "",
                        "finish_reason" to "tool_calls",
                        "tool_calls" to listOf(
                            mapOf("id" to "call-$iteration", "name" to "noop", "arguments" to emptyMap<String, Any>())
                        ),
                        "usage" to mapOf("prompt" to 100, "completion" to 10, "total" to 110),
                        "_model_id" to "primary",
                        "_model_name" to "主模型",
                        "_model_actual_name" to "model-a"
                    )
                } else {
                    mapOf(
                        "content" to "完成",
                        "finish_reason" to "stop",
                        "usage" to mapOf("prompt" to 50, "completion" to 5, "total" to 55),
                        "_model_id" to "backup",
                        "_model_name" to "备用模型",
                        "_model_actual_name" to "model-b",
                        "_failover_events" to listOf(
                            mapOf("model" to "主模型", "actual_model" to "model-a", "status_code" to 429)
                        )
                    )
                }
            },
            toolExecutor = { _, _, _, _ -> mapOf("success" to true) },
            maxIterations = 5,
            hooks = ToolLoopHooks(
                onToolMessageAppended = { message, usage -> appended += message to usage },
                onModelUsageRecorded = { recorded += it }
            )
        )

        assertEquals("完成", result.finalContent)
        assertEquals(3, result.usageRecords.size)
        // 每完成一轮工具调用就回调一次记账：Token 用量界面不必等整轮结束才有记录。
        assertEquals(result.usageRecords, recorded)
        assertEquals(listOf("主模型", "主模型", "备用模型"), result.usageRecords.map { it.modelName })
        assertEquals(100, result.usageRecords[0].inputTokens)
        assertEquals(10, result.usageRecords[0].outputTokens)
        assertTrue(result.usageRecords[0].failovers.isEmpty())
        assertTrue(result.usageRecords[2].failovers.isNotEmpty())
        assertEquals(429, result.usageRecords[2].failovers.single().statusCode)
        assertEquals("model-b", result.usageRecords[2].actualModel)

        // assistant 行（模型输出）带用量，tool 行不带，避免同一轮用量被重复记录。
        val assistantRows = appended.filter { it.first["role"] == "assistant" }
        val toolRows = appended.filter { it.first["role"] == "tool" }
        assertEquals(2, assistantRows.size)
        assertEquals(2, toolRows.size)
        assertTrue(assistantRows.all { it.second != null })
        assertTrue(toolRows.all { it.second == null })
        assertNull(appended.firstOrNull { it.first["role"] == "tool" }?.second)
        assertNotNull(assistantRows.first().second)

        // 聚合：同一模型两次调用合并，备用模型标记为回退。
        val usage = aggregateModelUsage(result.usageRecords)!!
        assertEquals(listOf("主模型", "备用模型"), usage.models.map { it.model })
        assertEquals(200, usage.models[0].inputTokens)
        assertEquals(20, usage.models[0].outputTokens)
        assertEquals(2, usage.models[0].calls)
        assertEquals(1, usage.models[1].calls)
        assertTrue(usage.models[1].fallback)
        assertEquals(429, usage.failovers.single().statusCode)
    }

    @Test
    fun singleModelRoundsWithoutFailoverAreNotMarked() = runBlocking {
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "synthetic prompt")),
            modelCall = { _, _ ->
                mapOf(
                    "content" to "直接回答",
                    "finish_reason" to "stop",
                    "usage" to mapOf("prompt" to 20, "completion" to 5),
                    "_model_id" to "primary",
                    "_model_name" to "主模型",
                    "_model_actual_name" to "model-a"
                )
            },
            toolExecutor = { _, _, _, _ -> mapOf("success" to true) },
            maxIterations = 3
        )
        val usage = aggregateModelUsage(result.usageRecords)!!
        assertEquals(1, usage.models.single().calls)
        assertTrue(!usage.models.single().fallback)
        assertTrue(usage.failovers.isEmpty())
    }
}
