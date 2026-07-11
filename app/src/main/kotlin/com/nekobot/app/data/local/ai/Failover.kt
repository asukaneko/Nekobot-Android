package com.nekobot.app.data.local.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * 模型故障转移管理，对应原仓库 nbot/core/failover.py。
 *
 * 追踪每个模型的健康状态，在遇到可恢复 HTTP 错误（429/5xx）时自动切换到下一个模型。
 * 使用指数退避冷却避免持续请求失败中的模型。
 */

// ============================================================================
// 错误分类
// ============================================================================

/**
 * 分类 HTTP 状态码以决定故障转移策略。
 *
 * @return "failover" / "config" / "transient"
 */
fun classifyHttpError(statusCode: Int): String {
    if (statusCode in 400..499) return "failover"
    if (statusCode in 500..599) return "failover"
    if (statusCode < 0) return "transient"  // 连接错误 / 超时
    return "transient"
}

/**
 * 从异常中提取 HTTP 状态码。
 *
 * @return >0: HTTP 状态码; -1: 连接错误; -2: 超时; 0: 未知
 */
fun extractStatusCode(error: Throwable): Int {
    // OkHttp 异常
    val className = error.javaClass.simpleName
    when {
        className.contains("ConnectException", true) ||
            className.contains("UnknownHostException", true) ||
            className.contains("SocketException", true) -> return -1
        className.contains("TimeoutException", true) ||
            className.contains("SocketTimeoutException", true) -> return -2
    }
    // 从消息中提取 HTTP 状态码
    val message = error.message ?: ""
    val httpMatch = Regex("""HTTP\s+(\d{3})""").find(message)
    httpMatch?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
    val codeMatch = Regex("""code\s*[:=]?\s*(\d{3})""", RegexOption.IGNORE_CASE).find(message)
    codeMatch?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
    return 0
}

// ============================================================================
// 冷却参数
// ============================================================================

/** 按错误类别返回 (base_seconds, max_seconds) */
private fun cooldownParams(category: String): Pair<Float, Float> = when (category) {
    "rate_limit" -> 60f to 300f
    "server" -> 30f to 120f
    "bad_request" -> 30f to 120f
    "transient" -> 15f to 60f
    "config" -> 0f to 0f
    else -> 15f to 60f
}

/** 将状态码映射到冷却类别 */
private fun cooldownCategory(statusCode: Int): String = when {
    statusCode == 429 -> "rate_limit"
    statusCode in 400..499 -> "bad_request"
    statusCode in 500..599 -> "server"
    else -> "transient"
}

/** 计算冷却秒数（指数退避） */
private fun computeCooldown(consecutiveFailures: Int, statusCode: Int): Float {
    val category = cooldownCategory(statusCode)
    val (base, maxVal) = cooldownParams(category)
    if (base <= 0f) return 0f
    val cooldown = base * (2f.pow((consecutiveFailures - 1).coerceAtLeast(0)))
    return minOf(cooldown, maxVal)
}

private fun Float.pow(n: Int): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()

// ============================================================================
// 模型健康状态
// ============================================================================

/**
 * 单个模型的健康状态追踪。
 * 对应原仓库 ModelHealth。
 */
data class ModelHealth(
    val modelId: String,
    var consecutiveFailures: Int = 0,
    var lastFailureAt: Float = 0f,
    var lastFailureCode: Int = 0,
    var cooldownUntil: Float = 0f,
    var dailyFailures: Int = 0,
    var dailyFailuresDate: String = ""
)

// ============================================================================
// 故障转移状态管理器
// ============================================================================

/**
 * 线程安全的模型故障转移队列管理器。
 * 对应原仓库 FailoverState。
 */
class FailoverState {
    private val healthMap = ConcurrentHashMap<String, ModelHealth>()

    /**
     * 从有序模型配置列表中选择最佳可用模型。
     *
     * 跳过处于冷却期的模型。
     * 返回第一个可用模型，或作为最后手段返回第一个模型。
     *
     * @param modelConfigs 按优先级排序的模型配置列表
     * @param excludeIds 需排除的模型 ID 集合
     * @return 选中的模型配置，或 null
     */
    fun selectModel(
        modelConfigs: List<Map<String, Any>>,
        excludeIds: Set<String> = emptySet()
    ): Map<String, Any>? {
        val now = System.nanoTime() / 1e9f
        var fallback: Map<String, Any>? = null

        for (config in modelConfigs) {
            val modelId = (config["model_id"] as? String) ?: ""
            if (modelId in excludeIds) continue
            if (fallback == null) fallback = config

            val health = healthMap[modelId]
            if (health == null || now >= health.cooldownUntil) {
                return config
            }
        }
        return fallback
    }

    /** 记录模型调用成功，重置连续失败计数 */
    fun recordSuccess(modelId: String) {
        healthMap[modelId]?.let {
            it.consecutiveFailures = 0
            it.cooldownUntil = 0f
        }
    }

    /**
     * 记录模型调用失败，更新健康状态并计算冷却期。
     *
     * @param modelId 模型 ID
     * @param statusCode HTTP 状态码
     */
    fun recordFailure(modelId: String, statusCode: Int = 0) {
        val now = System.nanoTime() / 1e9f
        val health = healthMap.computeIfAbsent(modelId) { ModelHealth(modelId) }
        health.consecutiveFailures++
        health.lastFailureAt = now
        health.lastFailureCode = statusCode
        health.cooldownUntil = now + computeCooldown(health.consecutiveFailures, statusCode)
    }

    /** 获取模型健康状态（调试用） */
    fun getHealth(modelId: String): ModelHealth? = healthMap[modelId]

    /** 清除所有健康状态记录 */
    fun clear() = healthMap.clear()
}

// ============================================================================
// 单例
// ============================================================================

/** 全局故障转移状态单例 */
private val globalFailoverState = FailoverState()

/** 获取全局故障转移状态实例 */
fun getFailoverState(): FailoverState = globalFailoverState
