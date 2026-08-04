package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.RoutingDecisionLogEntity

/** 单个候选模型最近一次参与路由时的分项得分。 */
data class RoutingScoreBreakdown(
    val priceScore: Double = 0.0,
    val speedScore: Double = 0.0,
    val failurePenalty: Double = 0.0,
    val contextBonus: Double = 0.0,
    val capabilityBonus: Double = 0.0,
    val priorityBonus: Double = 0.0,
    val noHistoryPenalty: Double = 0.0
)

/** 路由历史按模型聚合后的可解释性数据。分数越低表示越优先。 */
data class RoutingModelStats(
    val modelId: String,
    val modelName: String,
    val latestScore: Double? = null,
    val averageScore: Double? = null,
    val scoreSamples: Int = 0,
    val candidateCount: Int = 0,
    val selectedCount: Int = 0,
    val completedCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val qualitySamples: Int = 0,
    val positiveQualityCount: Int = 0,
    val negativeQualityCount: Int = 0,
    val averageEstimatedCostUsd: Double? = null,
    val averageActualCostUsd: Double? = null,
    val averageDurationMs: Double? = null,
    val averageTtftMs: Double? = null,
    val latestBreakdown: RoutingScoreBreakdown? = null,
    val lastFailureReason: String? = null
) {
    val successRate: Double?
        get() = completedCount.takeIf { it > 0 }?.let { successCount.toDouble() / it }

    val qualityRate: Double?
        get() = qualitySamples.takeIf { it > 0 }?.let { positiveQualityCount.toDouble() / it }

    val selectionRate: Double?
        get() = candidateCount.takeIf { it > 0 }?.let { selectedCount.toDouble() / it }
}

/** A/B 测试某个分组的汇总数据。 */
data class RoutingAbTestStats(
    val group: String,
    val modelNames: List<String> = emptyList(),
    val sampleCount: Int = 0,
    val completedCount: Int = 0,
    val successCount: Int = 0,
    val qualitySamples: Int = 0,
    val positiveQualityCount: Int = 0,
    val negativeQualityCount: Int = 0,
    val averageCostUsd: Double? = null,
    val averageDurationMs: Double? = null,
    val averageTtftMs: Double? = null
) {
    val successRate: Double?
        get() = completedCount.takeIf { it > 0 }?.let { successCount.toDouble() / it }

    val qualityRate: Double?
        get() = qualitySamples.takeIf { it > 0 }?.let { positiveQualityCount.toDouble() / it }
}

private data class CandidateSnapshot(
    val modelId: String,
    val modelName: String,
    val score: Double,
    val breakdown: RoutingScoreBreakdown
)

/**
 * 路由分析的纯聚合函数，独立于 Room，方便单元测试并避免把统计逻辑塞进 Compose。
 * [logs] 期望按 createdAt 倒序传入，这样 latestScore 就是最新一次决策分数。
 */
fun aggregateRoutingModelStats(
    logs: List<RoutingDecisionLogEntity>,
    gson: Gson = Gson()
): List<RoutingModelStats> {
    data class MutableStats(
        val modelId: String,
        var modelName: String,
        var latestScore: Double? = null,
        var scoreSum: Double = 0.0,
        var scoreSamples: Int = 0,
        var candidateCount: Int = 0,
        var selectedCount: Int = 0,
        var completedCount: Int = 0,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var qualitySamples: Int = 0,
        var positiveQualityCount: Int = 0,
        var negativeQualityCount: Int = 0,
        var estimatedCostSum: Double = 0.0,
        var estimatedCostSamples: Int = 0,
        var actualCostSum: Double = 0.0,
        var actualCostSamples: Int = 0,
        var durationSum: Double = 0.0,
        var durationSamples: Int = 0,
        var ttftSum: Double = 0.0,
        var ttftSamples: Int = 0,
        var latestBreakdown: RoutingScoreBreakdown? = null,
        var lastFailureReason: String? = null
    )

    val buckets = linkedMapOf<String, MutableStats>()
    logs.forEach { log ->
        parseCandidateSnapshots(log.decisionJson).forEach { candidate ->
            val bucket = buckets.getOrPut(candidate.modelId) {
                MutableStats(candidate.modelId, candidate.modelName)
            }
            if (bucket.scoreSamples == 0) {
                bucket.latestScore = candidate.score
                bucket.latestBreakdown = candidate.breakdown
            }
            if (candidate.modelName.isNotBlank()) bucket.modelName = candidate.modelName
            bucket.scoreSum += candidate.score
            bucket.scoreSamples++
            bucket.candidateCount++

            if (candidate.modelId != log.selectedModelId) return@forEach
            bucket.selectedCount++
            bucket.estimatedCostSum += log.estimatedCostUsd
            bucket.estimatedCostSamples++

            val actualCost = log.actualCostUsd
            if (actualCost != null) {
                bucket.completedCount++
                bucket.actualCostSum += actualCost
                bucket.actualCostSamples++
                if (log.success) {
                    bucket.successCount++
                } else {
                    bucket.failureCount++
                    log.failureReason?.takeIf { it.isNotBlank() }?.let { bucket.lastFailureReason = it }
                }
            }
            log.actualDurationMs?.let {
                bucket.durationSum += it
                bucket.durationSamples++
            }
            log.actualTtftMs?.let {
                bucket.ttftSum += it
                bucket.ttftSamples++
            }
            when {
                log.qualityScore > 0 -> {
                    bucket.qualitySamples++
                    bucket.positiveQualityCount++
                }
                log.qualityScore < 0 -> {
                    bucket.qualitySamples++
                    bucket.negativeQualityCount++
                }
            }
        }
    }

    return buckets.values.map { bucket ->
        RoutingModelStats(
            modelId = bucket.modelId,
            modelName = bucket.modelName,
            latestScore = bucket.latestScore,
            averageScore = bucket.scoreSum.takeIf { bucket.scoreSamples > 0 }
                ?.div(bucket.scoreSamples),
            scoreSamples = bucket.scoreSamples,
            candidateCount = bucket.candidateCount,
            selectedCount = bucket.selectedCount,
            completedCount = bucket.completedCount,
            successCount = bucket.successCount,
            failureCount = bucket.failureCount,
            qualitySamples = bucket.qualitySamples,
            positiveQualityCount = bucket.positiveQualityCount,
            negativeQualityCount = bucket.negativeQualityCount,
            averageEstimatedCostUsd = bucket.estimatedCostSum
                .takeIf { bucket.estimatedCostSamples > 0 }
                ?.div(bucket.estimatedCostSamples),
            averageActualCostUsd = bucket.actualCostSum
                .takeIf { bucket.actualCostSamples > 0 }
                ?.div(bucket.actualCostSamples),
            averageDurationMs = bucket.durationSum
                .takeIf { bucket.durationSamples > 0 }
                ?.div(bucket.durationSamples),
            averageTtftMs = bucket.ttftSum
                .takeIf { bucket.ttftSamples > 0 }
                ?.div(bucket.ttftSamples),
            latestBreakdown = bucket.latestBreakdown,
            lastFailureReason = bucket.lastFailureReason
        )
    }.sortedWith(
        compareBy<RoutingModelStats> { it.latestScore ?: Double.MAX_VALUE }
            .thenByDescending { it.selectedCount }
            .thenBy { it.modelName }
    )
}

/** 按 A/B 分组汇总完成率、质量、费用和延迟。 */
fun aggregateRoutingAbTestStats(logs: List<RoutingDecisionLogEntity>): List<RoutingAbTestStats> {
    data class MutableStats(
        val group: String,
        val modelNames: MutableSet<String> = linkedSetOf(),
        var sampleCount: Int = 0,
        var completedCount: Int = 0,
        var successCount: Int = 0,
        var qualitySamples: Int = 0,
        var positiveQualityCount: Int = 0,
        var negativeQualityCount: Int = 0,
        var costSum: Double = 0.0,
        var costSamples: Int = 0,
        var durationSum: Double = 0.0,
        var durationSamples: Int = 0,
        var ttftSum: Double = 0.0,
        var ttftSamples: Int = 0
    )

    val buckets = linkedMapOf<String, MutableStats>()
    logs.filter { it.isAbTest && !it.abTestGroup.isNullOrBlank() }.forEach { log ->
        val group = log.abTestGroup.orEmpty()
        val bucket = buckets.getOrPut(group) { MutableStats(group) }
        bucket.sampleCount++
        log.selectedModelName.takeIf { it.isNotBlank() }?.let(bucket.modelNames::add)
        log.actualCostUsd?.let {
            bucket.completedCount++
            bucket.costSum += it
            bucket.costSamples++
            if (log.success) bucket.successCount++
        }
        when {
            log.qualityScore > 0 -> {
                bucket.qualitySamples++
                bucket.positiveQualityCount++
            }
            log.qualityScore < 0 -> {
                bucket.qualitySamples++
                bucket.negativeQualityCount++
            }
        }
        log.actualDurationMs?.let {
            bucket.durationSum += it
            bucket.durationSamples++
        }
        log.actualTtftMs?.let {
            bucket.ttftSum += it
            bucket.ttftSamples++
        }
    }

    return buckets.values.map { bucket ->
        RoutingAbTestStats(
            group = bucket.group,
            modelNames = bucket.modelNames.toList(),
            sampleCount = bucket.sampleCount,
            completedCount = bucket.completedCount,
            successCount = bucket.successCount,
            qualitySamples = bucket.qualitySamples,
            positiveQualityCount = bucket.positiveQualityCount,
            negativeQualityCount = bucket.negativeQualityCount,
            averageCostUsd = bucket.costSum.takeIf { bucket.costSamples > 0 }
                ?.div(bucket.costSamples),
            averageDurationMs = bucket.durationSum.takeIf { bucket.durationSamples > 0 }
                ?.div(bucket.durationSamples),
            averageTtftMs = bucket.ttftSum.takeIf { bucket.ttftSamples > 0 }
                ?.div(bucket.ttftSamples)
        )
    }.sortedBy { it.group }
}

private fun parseCandidateSnapshots(json: String): List<CandidateSnapshot> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        root.getAsJsonArray("candidates")?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val candidate = element.asJsonObject
            CandidateSnapshot(
                modelId = candidate.string("modelId"),
                modelName = candidate.string("modelName"),
                score = candidate.double("score"),
                breakdown = RoutingScoreBreakdown(
                    priceScore = candidate.double("priceScore"),
                    speedScore = candidate.double("speedScore"),
                    failurePenalty = candidate.double("failurePenalty"),
                    contextBonus = candidate.double("contextBonus"),
                    capabilityBonus = candidate.double("capabilityBonus"),
                    priorityBonus = candidate.double("priorityBonus"),
                    noHistoryPenalty = candidate.double("noHistoryPenalty")
                )
            ).takeIf { it.modelId.isNotBlank() }
        }.orEmpty()
    }.getOrElse { emptyList() }
}

private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

private fun JsonObject.double(name: String): Double =
    get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
