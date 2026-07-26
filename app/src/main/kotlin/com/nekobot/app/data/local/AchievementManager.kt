package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.locks.ReentrantLock

/**
 * 成就系统管理器。
 *
 * 负责成就进度的统计、解锁与持久化。成就触发基于应用内真实行为：
 * - token 累计消耗
 * - 用户发送消息数
 * - 会话数量
 * - 首个角色好感度达到 100
 *
 * 解锁状态持久化到 SharedPreferences，跨会话保留。
 */
object AchievementManager {

    private const val PREF_NAME = "nekobot_achievements"
    private const val KEY_UNLOCKED = "unlocked"

    private val gson = Gson()
    private val lock = ReentrantLock()

    private var prefs: SharedPreferences? = null

    /** 成就定义 ID。 */
    object Id {
        const val TOKEN_1000 = "token_1000"
        const val TOKEN_10000 = "token_10000"
        const val TOKEN_100000 = "token_100000"
        const val MESSAGES_100 = "messages_100"
        const val MESSAGES_1000 = "messages_1000"
        const val SESSIONS_10 = "sessions_10"
        const val SESSIONS_50 = "sessions_50"
        const val FIRST_AFFECTION_100 = "first_affection_100"
    }

    /** 成就目标配置。 */
    data class Target(
        val id: String,
        val target: Long,
        val metric: Metric
    ) {
        enum class Metric {
            TOKENS, MESSAGES, SESSIONS, AFFECTION
        }
    }

    val targets = listOf(
        Target(Id.TOKEN_1000, 1000, Target.Metric.TOKENS),
        Target(Id.TOKEN_10000, 10000, Target.Metric.TOKENS),
        Target(Id.TOKEN_100000, 100000, Target.Metric.TOKENS),
        Target(Id.MESSAGES_100, 100, Target.Metric.MESSAGES),
        Target(Id.MESSAGES_1000, 1000, Target.Metric.MESSAGES),
        Target(Id.SESSIONS_10, 10, Target.Metric.SESSIONS),
        Target(Id.SESSIONS_50, 50, Target.Metric.SESSIONS),
        Target(Id.FIRST_AFFECTION_100, 100, Target.Metric.AFFECTION)
    )

    /** 成就快照，用于 UI 展示。 */
    data class Snapshot(
        val id: String,
        val current: Long,
        val target: Long,
        val unlockedAt: Long?
    ) {
        val progress: Float
            get() = if (target > 0) (current.toFloat() / target).coerceIn(0f, 1f) else 0f
        val isUnlocked: Boolean get() = unlockedAt != null
    }

    /** 初始化（在 ServiceContainer.init 中调用）。 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 报告事件进度。在关键行为处调用，立即解锁已达成成就。
     * 数值传入当前累计值即可，内部会判断是否已解锁并持久化。
     */
    fun reportProgress(metric: Target.Metric, currentValue: Long) {
        val ids = targets.filter { it.metric == metric && currentValue >= it.target }.map { it.id }
        if (ids.isNotEmpty()) {
            unlockAll(ids)
        }
    }

    /**
     * 根据当前全局统计数据生成成就快照。
     * 同时会触发未记录成就的解锁（用于首次加载/升级时补齐）。
     */
    fun getSnapshots(
        totalTokens: Long,
        totalMessages: Long,
        totalSessions: Int,
        maxAffection: Int
    ): List<Snapshot> {
        // 先补齐可能未记录的解锁
        reportProgress(Target.Metric.TOKENS, totalTokens)
        reportProgress(Target.Metric.MESSAGES, totalMessages)
        reportProgress(Target.Metric.SESSIONS, totalSessions.toLong())
        reportProgress(Target.Metric.AFFECTION, maxAffection.toLong())

        val unlocked = readUnlockedMap()
        return targets.map { target ->
            val current = when (target.metric) {
                Target.Metric.TOKENS -> totalTokens
                Target.Metric.MESSAGES -> totalMessages
                Target.Metric.SESSIONS -> totalSessions.toLong()
                Target.Metric.AFFECTION -> maxAffection.toLong()
            }
            Snapshot(
                id = target.id,
                current = current,
                target = target.target,
                unlockedAt = unlocked[target.id]
            )
        }
    }

    /** 获取已解锁成就 ID 集合。 */
    fun getUnlockedIds(): Set<String> {
        return readUnlockedMap().keys
    }

    /** 检查指定成就是否已解锁。 */
    fun isUnlocked(id: String): Boolean {
        return readUnlockedMap().containsKey(id)
    }

    /** 手动解锁指定成就（通常由 reportProgress 自动调用）。 */
    fun unlock(id: String) {
        unlockAll(listOf(id))
    }

    private fun unlockAll(ids: List<String>) {
        val sp = prefs ?: return
        lock.lock()
        try {
            val map = readUnlockedMap().toMutableMap()
            var changed = false
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                if (!map.containsKey(id)) {
                    map[id] = now
                    changed = true
                }
            }
            if (changed) {
                val obj = JsonObject()
                map.forEach { (k, v) -> obj.addProperty(k, v) }
                sp.edit().putString(KEY_UNLOCKED, obj.toString()).apply()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun readUnlockedMap(): Map<String, Long> {
        val sp = prefs ?: return emptyMap()
        val raw = sp.getString(KEY_UNLOCKED, "{}") ?: "{}"
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            obj.entrySet().mapNotNull { entry ->
                val value = entry.value
                if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                    entry.key to value.asLong
                } else null
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
