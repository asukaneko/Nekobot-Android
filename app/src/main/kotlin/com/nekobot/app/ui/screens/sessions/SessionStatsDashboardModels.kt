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
    val channels: List<DashboardChannelItem> = emptyList()
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
    today: LocalDate = LocalDate.now()
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

    return SessionStatsDashboardData(
        totalSessions = visibleSessions.size,
        totalMessages = visibleSessions.sumOf { (it.messageCount ?: 0).toLong() },
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
        channels = channels
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
