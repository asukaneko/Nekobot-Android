package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListPresentationTest {

    @Test
    fun formatSessionListDate_compactsIsoTimestampWithoutTruncatingDate() {
        assertEquals("2026-07-27", formatSessionListDate("2026-07-27T18:36:42.123+08:00"))
        assertEquals("2026-07-27", formatSessionListDate("2026-07-27 18:36:42"))
        assertEquals("昨天", formatSessionListDate("昨天"))
        assertEquals(null, formatSessionListDate("  "))
    }

    @Test
    fun buildSessionOverview_excludesAutomaticArchivesAndCountsVisibleStatuses() {
        val sessions = listOf(
            Session(id = "normal"),
            Session(id = "pinned", pinned = true),
            Session(id = "favorite", favorite = true),
            Session(id = "manually-archived", archived = true),
            Session(id = "automatic-archive", isArchive = true)
        )

        assertEquals(
            SessionOverview(total = 4, characterSessions = 3, pinned = 1, favorite = 1, archived = 1),
            buildSessionOverview(sessions)
        )
    }

    @Test
    fun quickSessionFilters_exposesTheFourPersistentShortcutsInDisplayOrder() {
        assertEquals(
            listOf(
                SessionFilter.CHARACTER_SESSIONS,
                SessionFilter.AGENT_SESSIONS,
                SessionFilter.FAVORITE,
                SessionFilter.ARCHIVED
            ),
            QUICK_SESSION_FILTERS
        )
    }

    @Test
    fun buildSessionOverview_countsCharacterAndAgentSessionsSeparately() {
        val sessions = listOf(
            Session(id = "legacy-character"),
            Session(id = "character", sessionMode = "character"),
            Session(id = "agent", sessionMode = "agent"),
            Session(id = "group", sessionMode = "group"),
            Session(id = "automatic-archive", sessionMode = "agent", isArchive = true)
        )

        assertEquals(
            SessionOverview(total = 4, characterSessions = 2, agentSessions = 1),
            buildSessionOverview(sessions)
        )
    }

    @Test
    fun buildSessionListRows_resolvesCharacterMetadataAndStableKeysOnce() {
        val characters = listOf(
            CharacterPreset(id = "alice", name = "爱丽丝", portrait = "/alice.png"),
            CharacterPreset(id = "bob", name = "鲍勃")
        )
        val sessions = listOf(
            Session(id = "single", characterId = "alice", senderName = "AI"),
            Session(id = "remote", senderName = "远程角色"),
            Session(
                id = "group",
                sessionMode = "group",
                characterIds = listOf("alice", "bob", "alice")
            ),
            Session(id = "agent", sessionMode = "agent", characterId = "alice"),
            Session(name = "无 ID"),
            Session(name = "无 ID")
        )

        val rows = buildSessionListRows(sessions, characters) { "resolved:$it" }

        assertEquals("爱丽丝", rows[0].characterLabel)
        assertEquals("resolved:/alice.png", rows[0].portraitUrl)
        assertEquals("远程角色", rows[1].characterLabel)
        assertEquals("爱丽丝、鲍勃", rows[2].characterLabel)
        assertNull(rows[3].characterLabel)
        assertEquals(6, rows.map { it.key }.distinct().size)
    }

    @Test
    fun buildSessionListRows_flagsAgentAndGroupSessionsForIconFallback() {
        val characters = listOf(
            CharacterPreset(id = "alice", name = "爱丽丝", portrait = "/alice.png")
        )
        val sessions = listOf(
            Session(id = "single", characterId = "alice"),
            Session(id = "group", sessionMode = "group", characterIds = listOf("alice")),
            Session(id = "agent", sessionMode = "agent", characterId = "alice"),
            Session(id = "group-by-ids", characterIds = listOf("alice"))
        )

        val rows = buildSessionListRows(sessions, characters) { it }

        // Agent 会话使用独立的 Agent 图标。
        assertEquals(true, rows[2].isAgentSession)
        // 普通会话不标记为群聊
        assertEquals(false, rows[0].isGroupSession)
        // session_mode == "group" 标记为群聊
        assertEquals(true, rows[1].isGroupSession)
        // agent 模式不标记为群聊
        assertEquals(false, rows[2].isGroupSession)
        // 仅有 characterIds 也标记为群聊
        assertEquals(true, rows[3].isGroupSession)
    }
}
