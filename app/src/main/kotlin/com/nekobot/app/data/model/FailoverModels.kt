package com.nekobot.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * 故障转移相关的类型化模型，对应后端 /api/ai-models/failover-detail 端点返回结构。
 *
 * 远程模式由 [com.nekobot.app.data.repository.NekobotRepository] 直接反序列化；
 * 本地模式由 [com.nekobot.app.data.local.LocalRepository] 从 Room + 健康状态存储组装。
 * 共享 Compose UI 通过 [com.nekobot.app.data.repository.UnifiedRepository] 消费统一类型。
 */

/** 单个模型的健康状态快照 */
data class FailoverHealth(
    val available: Boolean = true,
    @SerializedName("daily_failures") val dailyFailures: Int = 0,
    @SerializedName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerializedName("last_failure_code") val lastFailureCode: Int = 0,
    @SerializedName("cooldown_remaining") val cooldownRemaining: Double = 0.0,
    @SerializedName("cooldown_until_ms") val cooldownUntilMs: Long = 0,
    @SerializedName("last_failure_at_ms") val lastFailureAtMs: Long = 0
)

/** 模型 token 用量快照，用于显示日/周用量百分比 */
data class FailoverTokenUsage(
    @SerializedName("daily_tokens") val dailyTokens: Long = 0,
    @SerializedName("weekly_tokens") val weeklyTokens: Long = 0,
    @SerializedName("daily_limit") val dailyLimit: Long = 0,
    @SerializedName("weekly_limit") val weeklyLimit: Long = 0
) {
    /** 日用量百分比，0..100，无限额时返回 0 */
    val dailyPercent: Int
        get() = if (dailyLimit <= 0) 0 else ((dailyTokens.toDouble() / dailyLimit) * 100).toInt().coerceIn(0, 100)

    /** 周用量百分比，0..100，无限额时返回 0 */
    val weeklyPercent: Int
        get() = if (weeklyLimit <= 0) 0 else ((weeklyTokens.toDouble() / weeklyLimit) * 100).toInt().coerceIn(0, 100)
}

/**
 * 单个模型的故障转移详情：基础信息 + 健康状态 + 用量 + 策略 + 价格。
 * 由本地/远程仓库统一组装后返回给 UI。
 */
data class FailoverModelDetail(
    @SerializedName(value = "model_id", alternate = ["id"]) val modelId: String,
    val name: String = "",
    val model: String = "",
    val provider: String? = null,
    val priority: Int = 0,
    val active: Boolean = false,
    val purpose: String = "",
    val enabled: Boolean = true,
    val health: FailoverHealth = FailoverHealth(),
    val usage: FailoverTokenUsage = FailoverTokenUsage(),
    @SerializedName("token_limit_daily") val tokenLimitDaily: Long = 0,
    @SerializedName("token_limit_weekly") val tokenLimitWeekly: Long = 0,
    @SerializedName("failover_timeout") val failoverTimeout: Int = 0,
    @SerializedName("input_price") val inputPrice: Double? = null,
    @SerializedName("output_price") val outputPrice: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerializedName("top_p") val topP: Double? = null
)

/**
 * 故障转移策略更新请求体。
 * 序列化结果与后端约定一致：
 * ```json
 * {"model_id":"m1","token_limit_daily":1000,"token_limit_weekly":5000,"failover_timeout":30}
 * ```
 *
 * 三个数值字段必须为非负数，由 [com.nekobot.app.data.repository.UnifiedRepository] 在分发前校验。
 */
data class FailoverPolicyUpdate(
    @SerializedName("model_id") val modelId: String,
    @SerializedName("token_limit_daily") val tokenLimitDaily: Long,
    @SerializedName("token_limit_weekly") val tokenLimitWeekly: Long,
    @SerializedName("failover_timeout") val failoverTimeout: Int
)
