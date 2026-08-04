package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalFailoverHealthEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 通用故障转移协调器：按优先级顺序尝试模型，失败自动切换下一个。
 *
 * - 冷却期内的模型自动跳过
 * - 超过日/周 token 限额的模型自动跳过
 * - 每次尝试受 [LocalAiModelEntity.failoverTimeout] 约束（0 表示用默认值）
 * - [CancellationException] 原样抛出，不记录为失败
 * - 成功后重置该模型的健康状态
 * - 所有模型都失败时抛出 [FailoverAllFailedException]
 */
class FailoverCoordinator(
    private val store: FailoverHealthStore,
    private val usage: FailoverUsageReader,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 按 [models] 顺序逐个尝试执行 [block]。
     *
     * @param models  按优先级排序的模型列表（P0 在前）
     * @param purpose 用于默认超时的 purpose 名（chat/vision/tts/stt/image_generation/embedding）
     * @param block   每次尝试执行的挂起代码块，接收当前模型，返回结果
     * @return [FailoverExecution] 包含结果值、成功使用的模型、所有尝试过的模型 ID
     */
    suspend fun <T> execute(
        models: List<LocalAiModelEntity>,
        purpose: String,
        block: suspend (LocalAiModelEntity) -> T
    ): FailoverExecution<T> {
        if (models.isEmpty()) {
            throw FailoverAllFailedException(
                purpose = purpose,
                attempts = emptyList(),
                failures = emptyList(),
                message = "没有可用模型（purpose=$purpose）"
            )
        }

        val attempts = mutableListOf<String>()
        val failures = mutableListOf<FailoverFailure>()
        val now = clock()
        val today = todayString()

        for (model in models) {
            // 跳过超 token 限额的模型
            if (!isWithinTokenLimits(model, now, today)) continue

            // 跳过冷却期内的模型
            val health = store.get(model.id)
            if (health != null && health.cooldownUntilMs > now) continue

            attempts.add(model.id)

            try {
                val timeout = model.failoverTimeout.takeIf { it > 0 }?.seconds
                    ?: defaultTimeout(purpose)
                val result = withTimeout(timeout) { block(model) }

                // 成功：重置健康状态
                store.upsert(
                    LocalFailoverHealthEntity(
                        modelId = model.id,
                        consecutiveFailures = 0,
                        lastFailureCode = 0,
                        lastFailureAtMs = 0,
                        cooldownUntilMs = 0,
                        dailyFailures = health?.dailyFailures?.takeIf { health.dailyFailuresDate == today } ?: 0,
                        dailyFailuresDate = today
                    )
                )

                return FailoverExecution(
                    value = result,
                    model = model,
                    attempts = attempts,
                    failures = failures,
                    actualDurationMs = clock() - now
                )
            } catch (e: CancellationException) {
                // 协程取消不应记录为失败
                throw e
            } catch (e: FailoverHttpException) {
                recordFailure(model.id, e.statusCode, now, today, health)
                failures.add(FailoverFailure(model.id, e.statusCode, e.message ?: ""))
            } catch (e: Exception) {
                val statusCode = extractStatusCode(e)
                recordFailure(model.id, statusCode, now, today, health)
                failures.add(FailoverFailure(model.id, statusCode, e.message ?: ""))
            }
        }

        val hasLocalOrSelfHosted = models.any { model ->
            val url = model.baseUrl.lowercase()
            url.contains("localhost") ||
                url.contains("127.0.0.1") ||
                url.contains("0.0.0.0") ||
                Regex("""https?://(?:10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)""")
                    .containsMatchIn(url)
        }
        val fallbackHint = if (hasLocalOrSelfHosted) {
            ""
        } else {
            "；可添加本地或自建模型作为离线备用"
        }
        throw FailoverAllFailedException(
            purpose = purpose,
            attempts = attempts,
            failures = failures,
            message = failures.lastOrNull()?.message
                ?.plus(fallbackHint)
                ?: "所有模型均失败（purpose=$purpose, tried=${models.map { it.id }}）$fallbackHint"
        )
    }

    // ---- 内部辅助 ----

    private suspend fun isWithinTokenLimits(
        model: LocalAiModelEntity,
        now: Long,
        today: String
    ): Boolean {
        if (model.tokenLimitDaily <= 0 && model.tokenLimitWeekly <= 0) return true
        val u = runCatching { usage.getUsage(model.id) }.getOrDefault(FailoverUsage())
        if (model.tokenLimitDaily > 0 && u.dailyTokens >= model.tokenLimitDaily) return false
        if (model.tokenLimitWeekly > 0 && u.weeklyTokens >= model.tokenLimitWeekly) return false
        return true
    }

    private suspend fun recordFailure(
        modelId: String,
        statusCode: Int,
        now: Long,
        today: String,
        existing: LocalFailoverHealthEntity?
    ) {
        val prev = existing ?: LocalFailoverHealthEntity(modelId = modelId)
        val dailyFailures = if (prev.dailyFailuresDate == today) prev.dailyFailures + 1 else 1
        val consecutive = prev.consecutiveFailures + 1
        val cooldownMs = computeCooldownMs(consecutive, statusCode, now)

        store.upsert(
            prev.copy(
                consecutiveFailures = consecutive,
                lastFailureCode = statusCode,
                lastFailureAtMs = now,
                cooldownUntilMs = cooldownMs,
                dailyFailures = dailyFailures,
                dailyFailuresDate = today
            )
        )
    }

    private fun computeCooldownMs(
        consecutiveFailures: Int,
        statusCode: Int,
        now: Long
    ): Long {
        val cooldownSec = computeCooldown(consecutiveFailures, statusCode)
        if (cooldownSec <= 0f) return 0L
        return now + (cooldownSec * 1000).toLong()
    }

    private fun defaultTimeout(purpose: String): Duration = when (purpose) {
        "chat" -> 120.seconds
        "vision" -> 60.seconds
        "tts" -> 30.seconds
        "stt" -> 60.seconds
        "image_generation" -> 120.seconds
        "embedding" -> 30.seconds
        else -> 60.seconds
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock()))
}
