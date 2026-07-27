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
internal fun computeCooldown(consecutiveFailures: Int, statusCode: Int): Float {
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
 * 保留内存版用于向后兼容；新代码应使用 [FailoverHealthStore]。
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
// 故障转移类型定义（持久化版）
// ============================================================================

/** 模型 token 用量快照，用于限额检查 */
data class FailoverUsage(
    val dailyTokens: Long = 0,
    val weeklyTokens: Long = 0
)

/** 单次执行结果 */
data class FailoverExecution<T>(
    val value: T,
    val model: com.nekobot.app.data.local.db.LocalAiModelEntity,
    val attempts: List<String>
)

/**
 * 本地非流式聊天调用的统一故障转移入口。
 *
 * 角色生成、世界书生成、自动记忆等辅助 LLM 任务都通过该接口复用 chat 队列，
 * 避免各功能自行读取单个 active 模型而绕过健康状态、限额和超时策略。
 */
fun interface LocalChatFailoverExecutor {
    suspend fun execute(messages: List<Map<String, Any>>): FailoverExecution<LocalAiResult>
}

/** 单次失败记录 */
data class FailoverFailure(
    val modelId: String,
    val statusCode: Int,
    val message: String
)

/** 所有模型都失败时抛出 */
class FailoverAllFailedException(
    val purpose: String,
    val attempts: List<String>,
    val failures: List<FailoverFailure>,
    message: String
) : Exception(message)

/** HTTP 失败异常，携带状态码供协调器分类冷却 */
class FailoverHttpException(val statusCode: Int, message: String) : Exception(message)

/** 健康状态持久化接口（实现可委托给 Room DAO） */
interface FailoverHealthStore {
    suspend fun get(modelId: String): com.nekobot.app.data.local.db.LocalFailoverHealthEntity?
    suspend fun upsert(entity: com.nekobot.app.data.local.db.LocalFailoverHealthEntity)
    suspend fun listAll(): List<com.nekobot.app.data.local.db.LocalFailoverHealthEntity>
    suspend fun delete(modelId: String)
    suspend fun clear()
}

/** token 用量读取接口 */
interface FailoverUsageReader {
    suspend fun getUsage(modelId: String): FailoverUsage
}

// ============================================================================
// 故障转移状态管理器（内存版，向后兼容）
// ============================================================================

/**
 * 线程安全的模型故障转移队列管理器。
 * 对应原仓库 FailoverState。
 * @deprecated 新代码应使用 [FailoverCoordinator] + [FailoverHealthStore]。
 */
class FailoverState {
    private val healthMap = ConcurrentHashMap<String, ModelHealth>()

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

    fun recordSuccess(modelId: String) {
        healthMap[modelId]?.let {
            it.consecutiveFailures = 0
            it.cooldownUntil = 0f
        }
    }

    fun recordFailure(modelId: String, statusCode: Int = 0) {
        val now = System.nanoTime() / 1e9f
        val health = healthMap.computeIfAbsent(modelId) { ModelHealth(modelId) }
        health.consecutiveFailures++
        health.lastFailureAt = now
        health.lastFailureCode = statusCode
        health.cooldownUntil = now + computeCooldown(health.consecutiveFailures, statusCode)
    }

    fun getHealth(modelId: String): ModelHealth? = healthMap[modelId]
    fun clear() = healthMap.clear()
}

// ============================================================================
// 单例
// ============================================================================

private val globalFailoverState = FailoverState()
fun getFailoverState(): FailoverState = globalFailoverState
