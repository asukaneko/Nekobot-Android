package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallUsageTest {
    @Test
    fun recordsAreAggregatedPerModelInFirstUseOrder() {
        val usage = aggregateModelUsage(
            listOf(
                ModelCallUsage(iteration = 0, modelId = "m1", modelName = "主模型", actualModel = "model-a",
                    inputTokens = 100, outputTokens = 10),
                ModelCallUsage(iteration = 1, modelId = "m1", modelName = "主模型", actualModel = "model-a",
                    inputTokens = 200, outputTokens = 20, cachedInputTokens = 30),
                ModelCallUsage(iteration = 2, modelId = "m2", modelName = "备用模型", actualModel = "model-b",
                    inputTokens = 300, outputTokens = 30,
                    failovers = listOf(ModelCallFailover("主模型", "model-a", 429)))
            )
        )!!
        assertEquals(listOf("主模型", "备用模型"), usage.models.map { it.model })
        assertEquals(300, usage.models[0].inputTokens)
        assertEquals(30, usage.models[0].outputTokens)
        assertEquals(30, usage.models[0].cachedInputTokens)
        assertEquals(2, usage.models[0].calls)
        assertFalse(usage.models[0].fallback)
        assertEquals(1, usage.models[1].calls)
        assertTrue(usage.models[1].fallback)
        assertEquals(1, usage.failovers.size)
        assertEquals(429, usage.failovers.single().statusCode)
        assertEquals("主模型", usage.failovers.single().model)
    }

    @Test
    fun sameFailoverIsListedOnceButDifferentCodesAreKept() {
        val usage = aggregateModelUsage(
            listOf(
                ModelCallUsage(modelId = "m2", modelName = "备用", actualModel = "b", inputTokens = 1, outputTokens = 1,
                    failovers = listOf(ModelCallFailover("主模型", "a", 429), ModelCallFailover("主模型", "a", 429))),
                ModelCallUsage(modelId = "m2", modelName = "备用", actualModel = "b", inputTokens = 1, outputTokens = 1,
                    failovers = listOf(ModelCallFailover("主模型", "a", 500)))
            )
        )!!
        assertEquals(listOf(429, 500), usage.failovers.map { it.statusCode })
        assertEquals(2, usage.models.single().calls)
    }

    @Test
    fun namelessRecordsDoNotCreateAnEmptyModelRow() {
        assertNull(aggregateModelUsage(emptyList<ModelCallUsage>()))
        assertNull(aggregateModelUsage(listOf(ModelCallUsage(inputTokens = 10, outputTokens = 5))))
    }

    @Test
    fun singleCallUsageSerializesWithReportedTokensOnly() {
        val usage = modelCallUsageOf(
            iteration = 3,
            modelId = "m1",
            modelName = "主模型",
            actualModel = "model-a",
            usage = mapOf("prompt" to 1000, "completion" to 20, "total" to 1020, LocalModelUsage.KEY_CACHED_PROMPT to 400),
            durationMs = 2000.0,
            failovers = emptyList()
        )
        assertEquals(1000, usage.inputTokens)
        assertEquals(20, usage.outputTokens)
        assertEquals(400, usage.cachedInputTokens)
        assertEquals(2000.0, usage.durationMs!!, 0.001)
        assertEquals(usage, decodeModelCallUsage(encodeModelCallUsage(usage)))
        // 没有服务商上报时不估算输入输出，避免逐轮累加把上下文重复计费
        assertEquals(0, modelCallUsageOf(3, "m1", "主模型", "model-a", emptyMap(), null, emptyList()).inputTokens)
    }

    @Test
    fun failoverEventsAreParsedDefensively() {
        val parsed = parseModelCallFailovers(
            listOf(
                mapOf("model" to "主模型", "actual_model" to "model-a", "status_code" to 429),
                mapOf("actual_model" to "model-b"),
                "unexpected"
            )
        )
        assertEquals(2, parsed.size)
        assertEquals(429, parsed[0].statusCode)
        assertEquals("", parsed[1].modelName)
        assertEquals(emptyList<ModelCallFailover>(), parseModelCallFailovers(null))
        assertEquals(emptyList<ModelCallFailover>(), parseModelCallFailovers("bad"))
    }
}
