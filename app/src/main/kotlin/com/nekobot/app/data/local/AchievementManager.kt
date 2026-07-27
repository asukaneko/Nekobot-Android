package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.locks.ReentrantLock

/**
 * 成就系统管理器。
 *
 * 负责成就进度的统计、解锁与持久化。成就触发基于应用内真实行为：
 * - token 累计消耗
 * - 用户发送消息数
 * - 会话数量
 * - 好感度达到 90 的角色数量
 *
 * 解锁状态持久化到 SharedPreferences，跨会话保留。
 */
object AchievementManager {

    private const val PREF_NAME = "nekobot_achievements"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_LEGACY_MIGRATED_SCOPE = "legacy_migrated_scope"
    private const val KEY_SCOPED_UNLOCKED_PREFIX = "unlocked::"
    private const val KEY_SCOPED_UNLOCKED_V2_PREFIX = "unlocked_v2::"
    private const val KEY_SCOPED_UNLOCKED_V3_PREFIX = "unlocked_v3::"

    private val lock = ReentrantLock()

    private var prefs: SharedPreferences? = null
    @Volatile
    private var unlockedStorageKey: String = KEY_UNLOCKED
    @Volatile
    private var currentScopeId: String = "default"

    private val unlockEventChannel = Channel<UnlockEvent>(Channel.UNLIMITED)
    val unlockEvents: Flow<UnlockEvent> = unlockEventChannel.receiveAsFlow()

    /** 成就定义 ID。 */
    object Id {
        const val TOKEN_1000 = "token_1000"
        const val TOKEN_10000 = "token_10000"
        const val TOKEN_100000 = "token_100000"
        const val TOKEN_1000000 = "token_1000000"
        const val TOKEN_10000000 = "token_10000000"
        const val MESSAGES_10 = "messages_10"
        const val MESSAGES_100 = "messages_100"
        const val MESSAGES_1000 = "messages_1000"
        const val MESSAGES_5000 = "messages_5000"
        const val MESSAGES_10000 = "messages_10000"
        const val SESSIONS_1 = "sessions_1"
        const val SESSIONS_10 = "sessions_10"
        const val SESSIONS_50 = "sessions_50"
        const val SESSIONS_100 = "sessions_100"
        const val SESSIONS_500 = "sessions_500"
        const val HIGH_AFFECTION_CHARACTERS_1 = "first_affection_100"
        @Deprecated("使用 HIGH_AFFECTION_CHARACTERS_1；保留旧 ID 仅为兼容已持久化数据")
        const val FIRST_AFFECTION_100 = HIGH_AFFECTION_CHARACTERS_1
        const val HIGH_AFFECTION_CHARACTERS_3 = "high_affection_characters_3"
        const val HIGH_AFFECTION_CHARACTERS_5 = "high_affection_characters_5"
        const val HIGH_AFFECTION_CHARACTERS_10 = "high_affection_characters_10"
        const val HIGH_AFFECTION_CHARACTERS_20 = "high_affection_characters_20"
        const val CHARACTERS_1 = "characters_1"
        const val CHARACTERS_3 = "characters_3"
        const val CHARACTERS_10 = "characters_10"
        const val CHARACTERS_30 = "characters_30"
        const val CHARACTERS_100 = "characters_100"
        const val WORLD_BOOKS_1 = "world_books_1"
        const val WORLD_BOOKS_3 = "world_books_3"
        const val WORLD_BOOKS_10 = "world_books_10"
        const val WORLD_BOOKS_30 = "world_books_30"
        const val WORLD_BOOKS_100 = "world_books_100"
        const val FAVORITE_SESSIONS_1 = "favorite_sessions_1"
        const val FAVORITE_SESSIONS_5 = "favorite_sessions_5"
        const val FAVORITE_SESSIONS_20 = "favorite_sessions_20"
        const val FAVORITE_SESSIONS_50 = "favorite_sessions_50"
        const val FAVORITE_SESSIONS_100 = "favorite_sessions_100"
    }

    /** 成就目标配置。 */
    data class Target(
        val id: String,
        val target: Long,
        val metric: Metric,
        val tier: Tier
    ) {
        enum class Metric {
            TOKENS,
            MESSAGES,
            SESSIONS,
            HIGH_AFFECTION_CHARACTERS,
            CHARACTERS,
            WORLD_BOOKS,
            FAVORITE_SESSIONS
        }

        enum class Tier {
            BRONZE, SILVER, GOLD, PLATINUM, DIAMOND
        }
    }

    val targets = listOf(
        Target(Id.TOKEN_1000, 1_000, Target.Metric.TOKENS, Target.Tier.BRONZE),
        Target(Id.TOKEN_10000, 10_000, Target.Metric.TOKENS, Target.Tier.SILVER),
        Target(Id.TOKEN_100000, 100_000, Target.Metric.TOKENS, Target.Tier.GOLD),
        Target(Id.TOKEN_1000000, 1_000_000, Target.Metric.TOKENS, Target.Tier.PLATINUM),
        Target(Id.TOKEN_10000000, 10_000_000, Target.Metric.TOKENS, Target.Tier.DIAMOND),
        Target(Id.MESSAGES_10, 10, Target.Metric.MESSAGES, Target.Tier.BRONZE),
        Target(Id.MESSAGES_100, 100, Target.Metric.MESSAGES, Target.Tier.SILVER),
        Target(Id.MESSAGES_1000, 1_000, Target.Metric.MESSAGES, Target.Tier.GOLD),
        Target(Id.MESSAGES_5000, 5_000, Target.Metric.MESSAGES, Target.Tier.PLATINUM),
        Target(Id.MESSAGES_10000, 10_000, Target.Metric.MESSAGES, Target.Tier.DIAMOND),
        Target(Id.SESSIONS_1, 1, Target.Metric.SESSIONS, Target.Tier.BRONZE),
        Target(Id.SESSIONS_10, 10, Target.Metric.SESSIONS, Target.Tier.SILVER),
        Target(Id.SESSIONS_50, 50, Target.Metric.SESSIONS, Target.Tier.GOLD),
        Target(Id.SESSIONS_100, 100, Target.Metric.SESSIONS, Target.Tier.PLATINUM),
        Target(Id.SESSIONS_500, 500, Target.Metric.SESSIONS, Target.Tier.DIAMOND),
        Target(Id.HIGH_AFFECTION_CHARACTERS_1, 1, Target.Metric.HIGH_AFFECTION_CHARACTERS, Target.Tier.BRONZE),
        Target(Id.HIGH_AFFECTION_CHARACTERS_3, 3, Target.Metric.HIGH_AFFECTION_CHARACTERS, Target.Tier.SILVER),
        Target(Id.HIGH_AFFECTION_CHARACTERS_5, 5, Target.Metric.HIGH_AFFECTION_CHARACTERS, Target.Tier.GOLD),
        Target(Id.HIGH_AFFECTION_CHARACTERS_10, 10, Target.Metric.HIGH_AFFECTION_CHARACTERS, Target.Tier.PLATINUM),
        Target(Id.HIGH_AFFECTION_CHARACTERS_20, 20, Target.Metric.HIGH_AFFECTION_CHARACTERS, Target.Tier.DIAMOND),
        Target(Id.CHARACTERS_1, 1, Target.Metric.CHARACTERS, Target.Tier.BRONZE),
        Target(Id.CHARACTERS_3, 3, Target.Metric.CHARACTERS, Target.Tier.SILVER),
        Target(Id.CHARACTERS_10, 10, Target.Metric.CHARACTERS, Target.Tier.GOLD),
        Target(Id.CHARACTERS_30, 30, Target.Metric.CHARACTERS, Target.Tier.PLATINUM),
        Target(Id.CHARACTERS_100, 100, Target.Metric.CHARACTERS, Target.Tier.DIAMOND),
        Target(Id.WORLD_BOOKS_1, 1, Target.Metric.WORLD_BOOKS, Target.Tier.BRONZE),
        Target(Id.WORLD_BOOKS_3, 3, Target.Metric.WORLD_BOOKS, Target.Tier.SILVER),
        Target(Id.WORLD_BOOKS_10, 10, Target.Metric.WORLD_BOOKS, Target.Tier.GOLD),
        Target(Id.WORLD_BOOKS_30, 30, Target.Metric.WORLD_BOOKS, Target.Tier.PLATINUM),
        Target(Id.WORLD_BOOKS_100, 100, Target.Metric.WORLD_BOOKS, Target.Tier.DIAMOND),
        Target(Id.FAVORITE_SESSIONS_1, 1, Target.Metric.FAVORITE_SESSIONS, Target.Tier.BRONZE),
        Target(Id.FAVORITE_SESSIONS_5, 5, Target.Metric.FAVORITE_SESSIONS, Target.Tier.SILVER),
        Target(Id.FAVORITE_SESSIONS_20, 20, Target.Metric.FAVORITE_SESSIONS, Target.Tier.GOLD),
        Target(Id.FAVORITE_SESSIONS_50, 50, Target.Metric.FAVORITE_SESSIONS, Target.Tier.PLATINUM),
        Target(Id.FAVORITE_SESSIONS_100, 100, Target.Metric.FAVORITE_SESSIONS, Target.Tier.DIAMOND)
    )

    data class UnlockEvent(
        val id: String,
        val target: Long,
        val metric: Target.Metric,
        val tier: Target.Tier,
        val unlockedAt: Long,
        val scopeId: String
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
    fun init(context: Context, initialScopeId: String) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        switchScope(initialScopeId)
    }

    /**
     * 切换成就存储作用域。本地模式按数据库 Profile 隔离，服务端模式按账号隔离。
     * 首次升级时仅将旧版全局记录迁移给当前作用域，其他作用域从空记录开始。
     */
    fun switchScope(scopeId: String) {
        val sp = prefs ?: return
        val normalizedScope = scopeId.trim().ifEmpty { "default" }
        val nextStorageKey = storageKeyForScope(normalizedScope)
        lock.lock()
        try {
            if (!sp.contains(nextStorageKey)) {
                val previousScopedKey = "$KEY_SCOPED_UNLOCKED_PREFIX$normalizedScope"
                val v2ScopedKey = "$KEY_SCOPED_UNLOCKED_V2_PREFIX$normalizedScope"
                val migratedValue = resolveV3MigrationValue(
                    scopeId = normalizedScope,
                    previousScopedValue = sp.getString(previousScopedKey, null),
                    v2ScopedValue = sp.getString(v2ScopedKey, null),
                    legacyGlobalValue = sp.getString(KEY_UNLOCKED, null),
                    legacyMigratedScope = sp.getString(KEY_LEGACY_MIGRATED_SCOPE, null)
                )
                sp.edit()
                    .putString(nextStorageKey, migratedValue ?: "{}")
                    .apply()
            }
            unlockedStorageKey = nextStorageKey
            currentScopeId = normalizedScope
            while (unlockEventChannel.tryReceive().isSuccess) {
                // 数据源切换后不再展示上一作用域尚未消费的解锁弹窗。
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 报告事件进度。在关键行为处调用，立即解锁已达成成就。
     * 数值传入当前累计值即可，内部会判断是否已解锁并持久化。
     */
    fun reportProgress(
        metric: Target.Metric,
        currentValue: Long,
        expectedScopeId: String? = null
    ) {
        val ids = targets.filter { it.metric == metric && currentValue >= it.target }.map { it.id }
        if (ids.isNotEmpty()) {
            unlockAll(ids, expectedScopeId)
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
        highAffectionCharacterCount: Int,
        characterCount: Int = 0,
        worldBookCount: Int = 0,
        favoriteSessionCount: Int = 0,
        expectedScopeId: String? = null
    ): List<Snapshot> {
        // 先补齐可能未记录的解锁
        reportProgress(Target.Metric.TOKENS, totalTokens, expectedScopeId)
        reportProgress(Target.Metric.MESSAGES, totalMessages, expectedScopeId)
        reportProgress(Target.Metric.SESSIONS, totalSessions.toLong(), expectedScopeId)
        reportProgress(
            Target.Metric.HIGH_AFFECTION_CHARACTERS,
            highAffectionCharacterCount.toLong(),
            expectedScopeId
        )
        reportProgress(Target.Metric.CHARACTERS, characterCount.toLong(), expectedScopeId)
        reportProgress(Target.Metric.WORLD_BOOKS, worldBookCount.toLong(), expectedScopeId)
        reportProgress(
            Target.Metric.FAVORITE_SESSIONS,
            favoriteSessionCount.toLong(),
            expectedScopeId
        )

        val unlocked = readUnlockedMap(expectedScopeId)
        return targets.map { target ->
            val current = when (target.metric) {
                Target.Metric.TOKENS -> totalTokens
                Target.Metric.MESSAGES -> totalMessages
                Target.Metric.SESSIONS -> totalSessions.toLong()
                Target.Metric.HIGH_AFFECTION_CHARACTERS -> highAffectionCharacterCount.toLong()
                Target.Metric.CHARACTERS -> characterCount.toLong()
                Target.Metric.WORLD_BOOKS -> worldBookCount.toLong()
                Target.Metric.FAVORITE_SESSIONS -> favoriteSessionCount.toLong()
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

    fun targetFor(id: String): Target? = targets.firstOrNull { it.id == id }

    fun isScopeCurrent(scopeId: String): Boolean = currentScopeId == scopeId

    fun activeScopeId(): String = currentScopeId

    /** 与数据库备份共用的稳定存储键；Profile 名必须与数据库切换时使用的名称一致。 */
    fun storageKeyForScope(scopeId: String): String {
        val normalizedScope = scopeId.trim().ifEmpty { "default" }
        return "$KEY_SCOPED_UNLOCKED_V3_PREFIX$normalizedScope"
    }

    /**
     * V2 曾会把旧版全局记录同时复制到 server 与默认本地库。迁移到 V3 时，只允许旧版
     * 标记的归属作用域继承全局记录；其他作用域会剔除 ID、时间戳完全相同的复制项。
     */
    internal fun resolveV3MigrationValue(
        scopeId: String,
        previousScopedValue: String?,
        v2ScopedValue: String?,
        legacyGlobalValue: String?,
        legacyMigratedScope: String?
    ): String? {
        if (
            previousScopedValue == null &&
            v2ScopedValue == null &&
            legacyGlobalValue == null
        ) {
            return null
        }

        val previousScoped = parseUnlockedMap(previousScopedValue)
        val v2Scoped = parseUnlockedMap(v2ScopedValue)
        val legacyGlobal = parseUnlockedMap(legacyGlobalValue)
        val ownsLegacyGlobal = legacyMigratedScope == scopeId
        val migrated = linkedMapOf<String, Long>()

        if (ownsLegacyGlobal) {
            migrated.putAll(legacyGlobal)
        }
        migrated.putAll(previousScoped)
        v2Scoped.forEach { (id, unlockedAt) ->
            val isCopiedFromAnotherScope =
                !ownsLegacyGlobal && legacyGlobal[id] == unlockedAt
            if (!isCopiedFromAnotherScope) {
                migrated[id] = unlockedAt
            }
        }
        return encodeUnlockedMap(migrated)
    }

    /** 清除被替换/删除数据库的解锁记录，避免同名 Profile 复用旧成就。 */
    fun clearScope(scopeId: String) {
        val sp = prefs ?: return
        val normalizedScope = scopeId.trim().ifEmpty { "default" }
        lock.lock()
        try {
            sp.edit()
                .putString(storageKeyForScope(normalizedScope), "{}")
                .remove("$KEY_SCOPED_UNLOCKED_PREFIX$normalizedScope")
                .remove("$KEY_SCOPED_UNLOCKED_V2_PREFIX$normalizedScope")
                .apply()
            if (currentScopeId == normalizedScope) {
                unlockedStorageKey = storageKeyForScope(normalizedScope)
                while (unlockEventChannel.tryReceive().isSuccess) {
                    // 被替换数据库的未展示解锁事件也必须一并丢弃。
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun unlockAll(ids: List<String>, expectedScopeId: String? = null) {
        val sp = prefs ?: return
        val events = mutableListOf<UnlockEvent>()
        lock.lock()
        try {
            if (expectedScopeId != null && expectedScopeId != currentScopeId) return
            val map = readUnlockedMap().toMutableMap()
            var changed = false
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                val target = targetFor(id)
                if (target != null && !map.containsKey(id)) {
                    map[id] = now
                    changed = true
                    events += UnlockEvent(
                        id = target.id,
                        target = target.target,
                        metric = target.metric,
                        tier = target.tier,
                        unlockedAt = now,
                        scopeId = currentScopeId
                    )
                }
            }
            if (changed) {
                sp.edit().putString(unlockedStorageKey, encodeUnlockedMap(map)).apply()
            }
        } finally {
            lock.unlock()
        }
        events.forEach { event -> unlockEventChannel.trySend(event) }
    }

    private fun readUnlockedMap(expectedScopeId: String? = null): Map<String, Long> {
        val sp = prefs ?: return emptyMap()
        lock.lock()
        try {
            if (expectedScopeId != null && expectedScopeId != currentScopeId) {
                return emptyMap()
            }
            return parseUnlockedMap(sp.getString(unlockedStorageKey, "{}"))
        } finally {
            lock.unlock()
        }
    }

    private fun parseUnlockedMap(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
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

    private fun encodeUnlockedMap(map: Map<String, Long>): String {
        val obj = JsonObject()
        map.forEach { (id, unlockedAt) -> obj.addProperty(id, unlockedAt) }
        return obj.toString()
    }
}
