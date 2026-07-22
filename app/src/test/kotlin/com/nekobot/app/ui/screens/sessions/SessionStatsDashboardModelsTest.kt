package com.nekobot.app.ui.screens.sessions

import com.google.gson.JsonParser
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsDashboardModelsTest {
    @Test
    fun `dashboard combines real session and token usage data`() {
        val today = LocalDate.of(2026, 7, 22)
        val sessions = listOf(
            Session(id = "s1", name = "猫猫", characterId = "cat", type = "web", messageCount = 12, favorite = true, updatedAt = "2026-07-22T08:00:00"),
            Session(id = "s2", name = "群聊", characterId = "group", type = "qq", messageCount = 7, updatedAt = "2026-07-20T08:00:00")
        )
        val records = JsonParser.parseString(
            """[{"date":"2026-07-22","total_tokens":120},{"date":"2026-07-21","total_tokens":80}]"""
        ).asJsonArray.toList()
        val sessionRanks = JsonParser.parseString(
            """[{"session_id":"s1","name":"猫猫","total_tokens":900},{"session_id":"s2","name":"群聊","total_tokens":300}]"""
        ).asJsonArray.toList()

        val result = buildSessionStatsDashboardData(
            sessions = sessions,
            stats = TokenStats(totalTokens = 1_200, today = 120, records = records),
            rankings = TokenRankings(sessions = sessionRanks),
            characters = listOf(
                CharacterPreset(id = "cat", name = "猫猫", portrait = "cat.png"),
                CharacterPreset(id = "group", name = "群聊角色", portrait = "group.png")
            ),
            today = today
        )

        assertEquals(2, result.totalSessions)
        assertEquals(19, result.totalMessages)
        assertEquals(2, result.activeSessions)
        assertEquals(1, result.favorites)
        assertEquals(1_200, result.totalTokens)
        assertEquals(120, result.todayTokens)
        assertEquals(2, result.streakDays)
        assertEquals("猫猫", result.sessionRanking.first().name)
        assertEquals(84, result.heatmap.size)
        assertTrue(result.channels.any { it.name == "QQ" && it.count == 1 })
        assertEquals("猫猫", result.frequentCharacters.first().name)
        assertEquals(900, result.frequentCharacters.first().chatTokens)
        assertEquals(1, result.frequentCharacters.first().sessionCount)
    }

    @Test
    fun `dashboard falls back to session activity when token records are absent`() {
        val today = LocalDate.of(2026, 7, 22)
        val result = buildSessionStatsDashboardData(
            sessions = listOf(Session(name = "会话", messageCount = 3, updatedAt = "2026-07-22T12:00:00")),
            stats = null,
            rankings = null,
            today = today
        )

        assertEquals(1, result.streakDays)
        assertEquals(1, result.heatmap.last().activity)
        assertEquals(3, result.sessionRanking.first().value)
    }

    @Test
    fun `frequent characters can sort by sessions or chat tokens`() {
        val manySessions = DashboardCharacterItem("a", "多会话", null, chatTokens = 100, sessionCount = 5)
        val manyTokens = DashboardCharacterItem("b", "高用量", null, chatTokens = 2_000, sessionCount = 2)

        assertEquals(
            "多会话",
            sortDashboardCharacters(listOf(manyTokens, manySessions), CharacterRankingMode.SESSIONS).first().name
        )
        assertEquals(
            "高用量",
            sortDashboardCharacters(listOf(manySessions, manyTokens), CharacterRankingMode.TOKENS).first().name
        )
    }
}
