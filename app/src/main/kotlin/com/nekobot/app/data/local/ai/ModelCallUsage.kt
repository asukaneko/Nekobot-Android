package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nekobot.app.data.model.MessageModelFailoverEntry
import com.nekobot.app.data.model.MessageModelUsage
import com.nekobot.app.data.model.MessageModelUsageEntry

/**
 * 工具循环内单次模型调用的 Token 用量与实际模型。
 *
 * 每完成一轮工具调用就用它落库（与工具轨迹同一行），进程被回收时不会丢掉已经产生的用量；
 * 整轮结束时再按模型聚合成 [MessageModelUsage] 写到助手消息上，供消息详情展示。
 */
data class ModelCallUsage(
    val iteration: Int = 0,
    @SerializedName("model_id") val modelId: String = "",
    /** 用户为该模型配置的名称。 */
    @SerializedName("model_name") val modelName: String = "",
    /** 实际请求的模型标识（如 gpt-4o）。 */
    @SerializedName("actual_model") val actualModel: String = "",
    @SerializedName("input_tokens") val inputTokens: Int = 0,
    @SerializedName("output_tokens") val outputTokens: Int = 0,
    @SerializedName("cached_input_tokens") val cachedInputTokens: Int = 0,
    @SerializedName("cache_write_tokens") val cacheWriteTokens: Int = 0,
    @SerializedName("duration_ms") val durationMs: Double? = null,
    /** 服务商未上报用量、由本地估算时为 true（此时不会单独记账）。 */
    val estimated: Boolean = false,
    /**
     * 本次调用之前被故障转移掉的模型（按尝试顺序）。
     *
     * 为空表示这次调用没有发生回退；非空表示当前模型是备用模型接手。
     */
    val failovers: List<ModelCallFailover> = emptyList()
)

/** 单次模型回退记录：先在 [modelName] 上失败，才切换到下一个候选。 */
data class ModelCallFailover(
    @SerializedName("model") val modelName: String = "",
    @SerializedName("actual_model") val actualModel: String = "",
    @SerializedName("status_code") val statusCode: Int = 0
)

private val modelCallUsageGson = Gson()

/** 序列化单次调用的用量，写入 agent 工具轨迹行；没有可展示信息时返回 null。 */
internal fun encodeModelCallUsage(usage: ModelCallUsage): String =
    modelCallUsageGson.toJson(usage)

/** 反序列化工具轨迹行上的用量；旧行或损坏数据返回 null。 */
internal fun decodeModelCallUsage(raw: String?): ModelCallUsage? {
    if (raw.isNullOrBlank()) return null
    return runCatching { modelCallUsageGson.fromJson(raw, ModelCallUsage::class.java) }
        .getOrNull()
        ?.takeUnless { it.modelName.isBlank() && it.actualModel.isBlank() && it.inputTokens <= 0 && it.outputTokens <= 0 }
}

/**
 * 逐轮用量 → 按模型聚合（保持首次使用的顺序），并汇总回退记录。
 *
 * 同一个模型在循环里被多次调用时合并成一行：Token 相加、调用次数累加；
 * 只要其中一次是回退接手，该模型就标记为回退模型。
 */
internal fun aggregateModelUsage(records: List<ModelCallUsage>): MessageModelUsage? {
    val usable = records.filterNot { record ->
        record.modelName.isBlank() && record.actualModel.isBlank() && record.modelId.isBlank()
    }
    if (usable.isEmpty()) return null
    val order = mutableListOf<String>()
    val totals = mutableMapOf<String, MessageModelUsageEntry>()
    val failovers = mutableListOf<MessageModelFailoverEntry>()
    usable.forEach { record ->
        record.failovers.forEach { failure ->
            val entry = MessageModelFailoverEntry(
                model = failure.modelName,
                actualModel = failure.actualModel,
                statusCode = failure.statusCode
            )
            // 同一模型连续失败多次时只保留一条，避免详情里刷屏。
            if (failovers.none { it.model == entry.model && it.actualModel == entry.actualModel && it.statusCode == entry.statusCode }) {
                failovers += entry
            }
        }
        val key = record.modelId.ifBlank { record.actualModel }.ifBlank { record.modelName }
        val previous = totals[key]
        if (previous == null) order += key
        totals[key] = MessageModelUsageEntry(
            model = record.modelName.ifBlank { previous?.model.orEmpty() },
            actualModel = record.actualModel.ifBlank { previous?.actualModel.orEmpty() },
            inputTokens = (previous?.inputTokens ?: 0) + record.inputTokens.coerceAtLeast(0),
            outputTokens = (previous?.outputTokens ?: 0) + record.outputTokens.coerceAtLeast(0),
            cachedInputTokens = (previous?.cachedInputTokens ?: 0) + record.cachedInputTokens.coerceAtLeast(0),
            cacheWriteTokens = (previous?.cacheWriteTokens ?: 0) + record.cacheWriteTokens.coerceAtLeast(0),
            calls = (previous?.calls ?: 0) + 1,
            fallback = (previous?.fallback == true) || record.failovers.isNotEmpty()
        )
    }
    return MessageModelUsage(
        models = order.mapNotNull(totals::get),
        failovers = failovers
    ).takeUnless { it.isEmpty }
}

/** 从每次模型调用的响应里取出该轮实际用量（只采用服务商报告的数值，不做估算）。 */
internal fun modelCallUsageOf(
    iteration: Int,
    modelId: String,
    modelName: String,
    actualModel: String,
    usage: Map<String, Any>,
    durationMs: Double?,
    failovers: List<ModelCallFailover>
): ModelCallUsage {
    val resolved = resolveLocalTokenUsage(usage, emptyList(), "")
    return ModelCallUsage(
        iteration = iteration,
        modelId = modelId,
        modelName = modelName,
        actualModel = actualModel,
        inputTokens = resolved.inputTokens.coerceAtLeast(0),
        outputTokens = resolved.outputTokens.coerceAtLeast(0),
        cachedInputTokens = resolved.cachedInputTokens.coerceAtLeast(0),
        cacheWriteTokens = resolved.cacheWriteTokens.coerceAtLeast(0),
        durationMs = durationMs?.takeIf { it.isFinite() && it > 0 },
        estimated = resolved.estimated,
        failovers = failovers
    )
}

/** 解析模型响应里的 `_failover_events`（字符串/数字混合的来源也能容忍）。 */
internal fun parseModelCallFailovers(raw: Any?): List<ModelCallFailover> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { element ->
        val map = element as? Map<*, *> ?: return@mapNotNull null
        ModelCallFailover(
            modelName = (map["model"] as? String).orEmpty(),
            actualModel = (map["actual_model"] as? String).orEmpty(),
            statusCode = (map["status_code"] as? Number)?.toInt() ?: 0
        )
    }.filter { it.modelName.isNotBlank() || it.actualModel.isNotBlank() }
}
