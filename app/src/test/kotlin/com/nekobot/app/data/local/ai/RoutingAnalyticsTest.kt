package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.RoutingDecisionLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoutingAnalyticsTest {

    @Test
    fun `aggregates latest and average score per model`() {
        val stats = aggregateRoutingModelStats(
            listOf(
                log(
                    id = "new",
                    selectedModelId = "model-a",
                    selectedModelName = "模型 A",
                    estimatedCostUsd = 0.01,
                    actualCostUsd = 0.01,
                    durationMs = 100,
                    success = true,
                    qualityScore = 1,
                    decisionJson = candidatesJson(
                        "model-a" to ("模型 A" to 10.0),
                        "model-b" to ("模型 B" to 30.0)
                    )
                ),
                log(
                    id = "old",
                    selectedModelId = "model-b",
                    selectedModelName = "模型 B",
                    estimatedCostUsd = 0.02,
                    actualCostUsd = 0.02,
                    durationMs = 200,
                    success = false,
                    qualityScore = -1,
                    decisionJson = candidatesJson(
                        "model-a" to ("模型 A" to 20.0),
                        "model-b" to ("模型 B" to 15.0)
                    )
                )
            )
        )

        val modelA = stats.first { it.modelId == "model-a" }
        val modelB = stats.first { it.modelId == "model-b" }

        assertEquals(10.0, modelA.latestScore!!, 0.001)
        assertEquals(15.0, modelA.averageScore!!, 0.001)
        assertEquals(2, modelA.candidateCount)
        assertEquals(1, modelA.selectedCount)
        assertEquals(1.0, modelA.successRate!!, 0.001)
        assertEquals(1.0, modelA.qualityRate!!, 0.001)
        assertNotNull(modelA.latestBreakdown)

        assertEquals(30.0, modelB.latestScore!!, 0.001)
        assertEquals(1, modelB.failureCount)
        assertEquals(0.0, modelB.successRate!!, 0.001)
        assertEquals(0.0, modelB.qualityRate!!, 0.001)
    }

    @Test
    fun `aggregates ab test groups`() {
        val stats = aggregateRoutingAbTestStats(
            listOf(
                log(
                    id = "a",
                    selectedModelId = "model-a",
                    selectedModelName = "模型 A",
                    estimatedCostUsd = 0.01,
                    actualCostUsd = 0.01,
                    durationMs = 120,
                    success = true,
                    qualityScore = 1,
                    abGroup = "A",
                    decisionJson = candidatesJson("model-a" to ("模型 A" to 10.0))
                ),
                log(
                    id = "b",
                    selectedModelId = "model-b",
                    selectedModelName = "模型 B",
                    estimatedCostUsd = 0.02,
                    actualCostUsd = 0.02,
                    durationMs = 220,
                    success = false,
                    qualityScore = -1,
                    abGroup = "B",
                    decisionJson = candidatesJson("model-b" to ("模型 B" to 20.0))
                )
            )
        )

        assertEquals(listOf("A", "B"), stats.map { it.group })
        assertEquals(1.0, stats.first { it.group == "A" }.successRate!!, 0.001)
        assertEquals(0.0, stats.first { it.group == "B" }.qualityRate!!, 0.001)
    }

    private fun candidatesJson(vararg entries: Pair<String, Pair<String, Double>>): String =
        """{"candidates":[${entries.joinToString(",") { (id, value) ->
            "{\"modelId\":\"$id\",\"modelName\":\"${value.first}\",\"score\":${value.second}," +
                "\"priceScore\":0.1,\"speedScore\":0.2,\"failurePenalty\":0.0," +
                "\"contextBonus\":4.0,\"capabilityBonus\":12.0,\"priorityBonus\":3.0," +
                "\"noHistoryPenalty\":0.0}"
        }}]}"""

    private fun log(
        id: String,
        selectedModelId: String,
        selectedModelName: String,
        estimatedCostUsd: Double,
        actualCostUsd: Double,
        durationMs: Long,
        success: Boolean,
        qualityScore: Int,
        decisionJson: String,
        abGroup: String? = null
    ) = RoutingDecisionLogEntity(
        id = id,
        sessionId = "session",
        createdAt = id,
        decisionJson = decisionJson,
        selectedModelId = selectedModelId,
        selectedModelName = selectedModelName,
        estimatedCostUsd = estimatedCostUsd,
        actualCostUsd = actualCostUsd,
        actualDurationMs = durationMs,
        actualTtftMs = durationMs / 2,
        success = success,
        failureReason = if (success) null else "请求失败",
        qualityScore = qualityScore,
        isAbTest = abGroup != null,
        abTestGroup = abGroup
    )
}
