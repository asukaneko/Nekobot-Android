package com.nekobot.app.ui.screens.sessions

import androidx.compose.runtime.Immutable
import com.google.gson.JsonElement
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

internal enum class CharacterRankingMode {
    SESSIONS,
    TOKENS;

    companion object {
        fun fromStorage(value: String): CharacterRankingMode =
            entries.firstOrNull { it.name == value } ?: TOKENS
    }
}

@Immutable
data class DashboardActivityDay(
    val date: LocalDate,
    val activity: Long,
    val tokens: Long
)

@Immutable
data class DashboardRankItem(
    val name: String,
    val value: Long
)

@Immutable
data class DashboardChannelItem(
    val name: String,
    val count: Int
)

@Immutable
data class DashboardCharacterItem(
    val id: String,
    val name: String,
    val portraitUrl: String?,
    val chatTokens: Long,
    val sessionCount: Int
)

@Immutable
data class DashboardRecentSession(
    val id: String,
    val name: String,
    val portraitUrl: String?,
    val updatedAt: String?
)

@Immutable
data class DashboardTodayCharacter(
    val id: String,
    val name: String,
    val description: String?,
    val portraitUrl: String?
)

@Immutable
data class DashboardWebDavStatus(
    val enabled: Boolean,
    val configured: Boolean,
    val url: String?,
    val lastBackupAt: String?,
    val lastSyncAt: String?,
    val lastError: String?,
    val remoteFileSize: Long?,
    val remoteModifiedAt: String?
)

@Immutable
data class DashboardLogEntry(
    val time: String,
    val level: String,
    val tag: String,
    val message: String
)

@Immutable
data class DashboardAchievement(
    val id: String,
    val current: Long,
    val target: Long,
    val unlockedAt: Long?
) {
    val progress: Float get() = if (target > 0) (current.toFloat() / target).coerceIn(0f, 1f) else 0f
    val isUnlocked: Boolean get() = unlockedAt != null
}

@Immutable
data class DashboardTokenRatio(
    val input: Long,
    val output: Long
) {
    val total: Long get() = input + output
    val inputRatio: Float get() = if (total > 0) input.toFloat() / total else 0f
    val outputRatio: Float get() = if (total > 0) output.toFloat() / total else 0f
}

@Immutable
data class DashboardWeekComparison(
    val thisWeekSessions: Int,
    val lastWeekSessions: Int,
    val thisWeekMessages: Long,
    val lastWeekMessages: Long,
    val thisWeekTokens: Long,
    val lastWeekTokens: Long
) {
    fun sessionChangePercent(): Int = percentChange(lastWeekSessions, thisWeekSessions)
    fun messageChangePercent(): Int = percentChange(lastWeekMessages.toInt(), thisWeekMessages.toInt())
    fun tokenChangePercent(): Int = percentChange(lastWeekTokens.toInt(), thisWeekTokens.toInt())

    private fun percentChange(old: Int, new: Int): Int = when {
        old == 0 && new == 0 -> 0
        old == 0 -> 100
        else -> ((new - old) * 100f / old).roundToInt()
    }
}

@Immutable
data class SessionStatsDashboardData(
    val totalSessions: Int = 0,
    val totalMessages: Long = 0,
    val activeSessions: Int = 0,
    val favorites: Int = 0,
    val totalTokens: Long = 0,
    val todayTokens: Long = 0,
    val streakDays: Int = 0,
    val heatmap: List<DashboardActivityDay> = emptyList(),
    val trend: List<DashboardActivityDay> = emptyList(),
    val sessionRanking: List<DashboardRankItem> = emptyList(),
    val modelRanking: List<DashboardRankItem> = emptyList(),
    val frequentCharacters: List<DashboardCharacterItem> = emptyList(),
    val channels: List<DashboardChannelItem> = emptyList(),
    val recentSessions: List<DashboardRecentSession> = emptyList(),
    val tokenRatio: DashboardTokenRatio? = null,
    val weekComparison: DashboardWeekComparison? = null,
    val todayCharacters: List<DashboardTodayCharacter> = emptyList(),
    val webDavStatus: DashboardWebDavStatus? = null,
    val localLogPreview: List<DashboardLogEntry> = emptyList(),
    val achievements: List<DashboardAchievement> = emptyList()
)

internal fun sortDashboardCharacters(
    characters: List<DashboardCharacterItem>,
    rankingMode: CharacterRankingMode
): List<DashboardCharacterItem> = when (rankingMode) {
    CharacterRankingMode.SESSIONS -> characters.sortedWith(
        compareByDescending<DashboardCharacterItem> { it.sessionCount }
            .thenByDescending { it.chatTokens }
    )
    CharacterRankingMode.TOKENS -> characters.sortedWith(
        compareByDescending<DashboardCharacterItem> { it.chatTokens }
            .thenByDescending { it.sessionCount }
    )
}

internal fun buildSessionStatsDashboardData(
    sessions: List<Session>,
    stats: TokenStats?,
    rankings: TokenRankings?,
    characters: List<CharacterPreset> = emptyList(),
    today: LocalDate = LocalDate.now(),
    webDavStatus: DashboardWebDavStatus? = null,
    localLogPreview: List<DashboardLogEntry> = emptyList(),
    highAffectionCharacterCount: Int? = null
): SessionStatsDashboardData {
    val visibleSessions = sessions.filter { it.isArchive != true }
    val activityByDate = linkedMapOf<LocalDate, Long>()
    val tokensByDate = linkedMapOf<LocalDate, Long>()

    val records = stats?.records
        ?: stats?.recentRecords
        ?: stats?.history
        ?: emptyList()
    records.forEach { element ->
        val date = dashboardRecordDate(element) ?: return@forEach
        val count = element.dashboardLong("count", "message_count", "requests") ?: 1L
        val input = element.dashboardLong("input", "input_tokens") ?: 0L
        val output = element.dashboardLong("output", "output_tokens") ?: 0L
        val tokens = element.dashboardLong("total", "total_tokens", "tokens", "value")
            ?: (input + output)
        activityByDate[date] = (activityByDate[date] ?: 0L) + count.coerceAtLeast(1L)
        tokensByDate[date] = (tokensByDate[date] ?: 0L) + tokens.coerceAtLeast(0L)
    }

    // 某些远程接口不返回 records；此时用会话更新时间提供真实的最低限度活跃度。
    if (activityByDate.isEmpty()) {
        visibleSessions.forEach { session ->
            parseDashboardDate(session.updatedAt ?: session.createdAt)?.let { date ->
                activityByDate[date] = (activityByDate[date] ?: 0L) + 1L
                tokensByDate.putIfAbsent(date, 0L)
            }
        }
    }

    val heatmapStart = today.minusDays(83)
    val heatmap = (0L until 84L).map { offset ->
        val date = heatmapStart.plusDays(offset)
        DashboardActivityDay(
            date = date,
            activity = activityByDate[date] ?: 0L,
            tokens = tokensByDate[date] ?: 0L
        )
    }
    val trend = heatmap.takeLast(7)
    var streak = 0
    for (day in heatmap.asReversed()) {
        if (day.activity <= 0L) break
        streak++
    }

    val fallbackSessionRanking = visibleSessions
        .sortedByDescending { it.messageCount ?: 0 }
        .take(5)
        .map { DashboardRankItem(it.displayName, (it.messageCount ?: 0).toLong()) }
    val sessionRanking = parseDashboardRanking(rankings?.sessions)
        .ifEmpty { parseDashboardRanking(stats?.sessions?.dashboardArray()) }
        .ifEmpty { fallbackSessionRanking }
        .take(5)
    val modelRanking = parseDashboardRanking(rankings?.models)
        .ifEmpty { parseDashboardRanking(stats?.models?.dashboardArray()) }
        .take(5)

    val characterById = characters
        .mapNotNull { character -> character.id?.takeIf(String::isNotBlank)?.let { it to character } }
        .toMap()
    val fallbackIdentity = visibleSessions
        .mapNotNull { session ->
            session.characterId?.takeIf(String::isNotBlank)?.let { id ->
                id to Triple(
                    session.characterName?.takeIf(String::isNotBlank) ?: session.senderName?.takeIf(String::isNotBlank) ?: id,
                    session.portraitUrl,
                    id
                )
            }
        }
        .toMap()
    val characterSessionCounts = linkedMapOf<String, Int>()
    val sessionTokensFromRanking = rankings?.sessions.orEmpty()
        .mapNotNull { element ->
            val sessionId = element.dashboardString("session_id", "id")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            sessionId to (element.dashboardLong("total_tokens", "tokens", "total", "value") ?: 0L)
        }
        .toMap()
    val sessionTokensFromRecords = records
        .mapNotNull { element ->
            val sessionId = element.dashboardString("session_id")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val input = element.dashboardLong("input", "input_tokens") ?: 0L
            val output = element.dashboardLong("output", "output_tokens") ?: 0L
            val total = element.dashboardLong("total", "total_tokens", "tokens") ?: (input + output)
            sessionId to total
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.sum() }
    val characterTokens = linkedMapOf<String, Long>()
    visibleSessions.forEach { session ->
        val ids = linkedSetOf<String>().apply {
            session.characterId?.takeIf(String::isNotBlank)?.let(::add)
            session.characterIds.orEmpty().filter(String::isNotBlank).forEach(::add)
        }
        ids.forEach { id ->
            characterSessionCounts[id] = (characterSessionCounts[id] ?: 0) + 1
            val sessionId = session.id.orEmpty()
            val sessionTokens = sessionTokensFromRanking[sessionId]
                ?: sessionTokensFromRecords[sessionId]
                ?: 0L
            characterTokens[id] = (characterTokens[id] ?: 0L) + sessionTokens
        }
    }
    val frequentCharacters = characterSessionCounts.keys
        .map { id ->
            val character = characterById[id]
            val fallback = fallbackIdentity[id]
            DashboardCharacterItem(
                id = id,
                name = character?.displayName ?: fallback?.first ?: id,
                portraitUrl = character?.avatarUrl ?: fallback?.second,
                chatTokens = characterTokens[id] ?: 0L,
                sessionCount = characterSessionCounts[id] ?: 0
            )
        }
        .sortedWith(compareByDescending<DashboardCharacterItem> { it.chatTokens }.thenByDescending { it.sessionCount })

    val channels = visibleSessions
        .groupingBy { session ->
            when (val type = session.type?.trim()?.lowercase()) {
                null, "" -> "Web"
                "web" -> "Web"
                "qq" -> "QQ"
                "cli" -> "CLI"
                else -> type.replaceFirstChar { it.uppercase() }
            }
        }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { DashboardChannelItem(it.key, it.value) }

    // 最近会话：按更新时间倒序取前 5 个
    val recentSessions = visibleSessions
        .sortedByDescending { parseDashboardDate(it.updatedAt ?: it.createdAt)?.atStartOfDay()?.toEpochSecond(java.time.ZoneId.systemDefault().rules.getOffset(java.time.LocalDateTime.now())) ?: 0L }
        .take(5)
        .map { session ->
            DashboardRecentSession(
                id = session.id.orEmpty(),
                name = session.displayName,
                portraitUrl = session.portraitUrl ?: session.characterAvatar ?: session.senderAvatar,
                updatedAt = session.updatedAt ?: session.createdAt
            )
        }

    // Token 输入/输出比：优先用今日汇总，否则按 records 聚合
    val tokenRatio = when {
        stats?.todayInput != null || stats?.todayOutput != null -> {
            DashboardTokenRatio(
                input = stats.todayInput ?: 0L,
                output = stats.todayOutput ?: 0L
            )
        }
        records.isNotEmpty() -> {
            var inputSum = 0L
            var outputSum = 0L
            records.forEach { element ->
                inputSum += element.dashboardLong("input", "input_tokens") ?: 0L
                outputSum += element.dashboardLong("output", "output_tokens") ?: 0L
            }
            if (inputSum > 0L || outputSum > 0L) DashboardTokenRatio(inputSum, outputSum) else null
        }
        else -> null
    }

    // 本周 vs 上周：按会话更新时间落在近 14 天内统计
    val thisWeekStart = today.minusDays(6)
    val lastWeekStart = today.minusDays(13)
    val lastWeekEnd = today.minusDays(7)
    var thisWeekSessions = 0
    var lastWeekSessions = 0
    var thisWeekMessages = 0L
    var lastWeekMessages = 0L
    var thisWeekTokens = 0L
    var lastWeekTokens = 0L
    visibleSessions.forEach { session ->
        val date = parseDashboardDate(session.updatedAt ?: session.createdAt) ?: return@forEach
        val sessionId = session.id.orEmpty()
        val tokens = sessionTokensFromRanking[sessionId]
            ?: sessionTokensFromRecords[sessionId]
            ?: 0L
        val messages = (session.messageCount ?: 0).toLong()
        when {
            !date.isBefore(thisWeekStart) && !date.isAfter(today) -> {
                thisWeekSessions++
                thisWeekMessages += messages
                thisWeekTokens += tokens
            }
            !date.isBefore(lastWeekStart) && !date.isAfter(lastWeekEnd) -> {
                lastWeekSessions++
                lastWeekMessages += messages
                lastWeekTokens += tokens
            }
        }
    }
    // 若 records 有日期维度，也用 records 补齐 token 统计（会话更新时间与 token 记录日期可能不同）
    records.forEach { element ->
        val date = dashboardRecordDate(element) ?: return@forEach
        val input = element.dashboardLong("input", "input_tokens") ?: 0L
        val output = element.dashboardLong("output", "output_tokens") ?: 0L
        val tokens = element.dashboardLong("total", "total_tokens", "tokens") ?: (input + output)
        when {
            !date.isBefore(thisWeekStart) && !date.isAfter(today) -> thisWeekTokens += tokens
            !date.isBefore(lastWeekStart) && !date.isAfter(lastWeekEnd) -> lastWeekTokens += tokens
        }
    }
    val weekComparison = DashboardWeekComparison(
        thisWeekSessions = thisWeekSessions,
        lastWeekSessions = lastWeekSessions,
        thisWeekMessages = thisWeekMessages,
        lastWeekMessages = lastWeekMessages,
        thisWeekTokens = thisWeekTokens,
        lastWeekTokens = lastWeekTokens
    )

    // 今日角色：随机抽取 3 个已有角色卡；若不足则全部展示
    val todayCharacters = characters
        .shuffled(java.util.Random(System.currentTimeMillis()))
        .take(3)
        .map { char ->
            DashboardTodayCharacter(
                id = char.id.orEmpty(),
                name = char.displayName,
                description = char.description?.takeIf { it.isNotBlank() }
                    ?: char.personality?.takeIf { it.isNotBlank() }
                    ?: char.basicInfo?.takeIf { it.isNotBlank() },
                portraitUrl = char.portrait ?: char.avatar
            )
        }

    // 成就进度：基于当前全局统计数据计算并触发解锁
    val totalMessages = visibleSessions.sumOf { (it.messageCount ?: 0).toLong() }
    val totalTokensForAchievements = stats?.totalDisplay ?: 0L
    val resolvedHighAffectionCharacterCount = highAffectionCharacterCount
        ?: characters.count { char ->
            char.state
                ?.get("affection")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
                ?.let { it >= 90 }
                ?: false
        }
    val achievementSnapshots = com.nekobot.app.data.local.AchievementManager.getSnapshots(
        totalTokens = totalTokensForAchievements,
        totalMessages = totalMessages,
        totalSessions = visibleSessions.size,
        highAffectionCharacterCount = resolvedHighAffectionCharacterCount
    ).map { snapshot ->
        DashboardAchievement(
            id = snapshot.id,
            current = snapshot.current,
            target = snapshot.target,
            unlockedAt = snapshot.unlockedAt
        )
    }

    return SessionStatsDashboardData(
        totalSessions = visibleSessions.size,
        totalMessages = totalMessages,
        activeSessions = visibleSessions.count { session ->
            parseDashboardDate(session.updatedAt ?: session.createdAt)?.let {
                !it.isBefore(today.minusDays(6)) && !it.isAfter(today)
            } == true
        },
        favorites = visibleSessions.count { it.favorite == true },
        totalTokens = stats?.totalDisplay ?: 0L,
        todayTokens = if (records.isNotEmpty()) tokensByDate[today] ?: 0L else stats?.todayTotal ?: 0L,
        streakDays = streak,
        heatmap = heatmap,
        trend = trend,
        sessionRanking = sessionRanking,
        modelRanking = modelRanking,
        frequentCharacters = frequentCharacters,
        channels = channels,
        recentSessions = recentSessions,
        tokenRatio = tokenRatio,
        weekComparison = weekComparison,
        todayCharacters = todayCharacters,
        webDavStatus = webDavStatus,
        localLogPreview = localLogPreview,
        achievements = achievementSnapshots
    )
}

internal fun parseDashboardDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: value.takeIf { it.length >= 10 }?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

private fun dashboardRecordDate(element: JsonElement): LocalDate? {
    if (!element.isJsonObject) return null
    val raw = element.dashboardString("date", "timestamp", "created_at", "time")
    return parseDashboardDate(raw)
}

private fun parseDashboardRanking(elements: List<JsonElement>?): List<DashboardRankItem> =
    elements.orEmpty()
        .mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val name = element.dashboardString(
                "name", "session_name", "title", "model", "model_name", "character_name", "id", "key"
            ).orEmpty().trim()
            if (name.isEmpty()) return@mapNotNull null
            val value = element.dashboardLong(
                "total_tokens", "tokens", "total", "value", "message_count", "count"
            ) ?: 0L
            DashboardRankItem(name, value)
        }
        .sortedByDescending(DashboardRankItem::value)

private fun JsonElement.dashboardArray(): List<JsonElement>? =
    takeIf { isJsonArray }?.asJsonArray?.toList()

private fun JsonElement.dashboardString(vararg keys: String): String? {
    if (!isJsonObject) return null
    val obj = asJsonObject
    return keys.firstNotNullOfOrNull { key ->
        obj.get(key)
            ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
            ?.runCatching { asString }
            ?.getOrNull()
    }
}

private fun JsonElement.dashboardLong(vararg keys: String): Long? {
    if (!isJsonObject) return null
    val obj = asJsonObject
    return keys.firstNotNullOfOrNull { key ->
        obj.get(key)
            ?.takeIf { !it.isJsonNull }
            ?.runCatching { asLong }
            ?.getOrNull()
    }
}
