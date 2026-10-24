package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListPresentationTest {

    @Test fun companionListUsesCurrentRoleAvatarAndDoesNotChangeOrdinaryPortraits() {
        val role = CharacterPreset(id = "role", avatar = "/new-avatar.png", portrait = "/portrait.png")
        val single = Session(id = "single", characterId = "role", sessionMode = "agent", companionMode = true,
            characterAvatar = "/old-avatar.png", portrait = "/portrait.png")
        val ordinary = single.copy(id = "ordinary", sessionMode = "character", companionMode = false)
        val rows = buildSessionListRows(listOf(single, ordinary), listOf(role))
        assertEquals("/new-avatar.png", rows[0].portraitUrl)
        assertEquals("/portrait.png", rows[1].portraitUrl)
        val fallback = buildSessionListRows(listOf(single.copy(characterAvatar = "fas fa-cat")), emptyList())
        assertEquals("/portrait.png", fallback.single().portraitUrl)
        val legacy = buildSessionListRows(listOf(single.copy(sessionMode = null)), listOf(role))
        assertEquals("/new-avatar.png", legacy.single().portraitUrl)
    }

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
    fun quickSessionFilters_exposesIndependentAgentCharacters() {
        assertEquals(
            listOf(
                SessionFilter.CHARACTER_SESSIONS,
                SessionFilter.AGENT_SESSIONS,
                SessionFilter.AGENT_CHARACTERS,
                SessionFilter.FAVORITE,
                SessionFilter.ARCHIVED
            ),
            QUICK_SESSION_FILTERS
        )
    }

    @Test
    fun agentCharacterCountsDoNotMixWithLegacyAgentAndExcludeArchives() {
        val sessions = listOf(
            Session(id = "ordinary", sessionMode = "agent"),
            Session(id = "legacy", sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true),
            Session(id = "companion", sessionMode = "agent", companionMode = true),
            Session(id = "archived", sessionMode = "agent", companionMode = true, archived = true),
            Session(id = "automatic", sessionMode = "agent", companionMode = true, isArchive = true)
        )
        assertEquals(SessionOverview(total = 4, agentSessions = 2, agentCharacterSessions = 1, archived = 1), buildSessionOverview(sessions))
        assertEquals(listOf("companion", "archived", "automatic"), sessions.filter(Session::isAgentCharacterSession).map { it.id })
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

    @Test
    fun listTitle_usesPeopleForRolesAndTasksForAgentEvenWithInheritedCharacter() {
        val characters = listOf(CharacterPreset(id = "lin", name = "林小满"))
        val rows = buildSessionListRows(listOf(
            Session(id = "role", name = "手帐记录", characterId = "lin", messageCount = 918),
            Session(id = "companion", name = "唯一会话", characterId = "lin", sessionMode = "agent", companionMode = true, inheritCharacter = false),
            Session(id = "agent", name = "九月日报整理", characterId = "lin", sessionMode = "agent", inheritCharacter = true),
            Session(id = "fallback", name = "无绑定角色")
        ), characters)
        assertEquals("林小满", rows[0].listTitle)
        assertEquals("林小满", rows[1].listTitle)
        assertEquals("九月日报整理", rows[2].listTitle)
        assertEquals("无绑定角色", rows[3].listTitle)
        // Presentation does not replace the stored title or drop the message total.
        assertEquals("手帐记录", rows[0].name)
        assertEquals(918, rows[0].messageCount)
    }

    @Test
    fun explicitRenameWinsWithoutPromotingOldAutomaticTitlesOrChangingOtherRows() {
        val character = CharacterPreset(id = "lin", name = "林小满")
        val rows = buildSessionListRows(listOf(
            Session(id = "renamed", name = "新的自动标题", characterId = "lin", listTitleOverride = "午后读书会"),
            Session(id = "other", name = "旧的自动标题", characterId = "lin"),
            Session(id = "companion", name = "唯一会话", characterId = "lin", sessionMode = "agent", companionMode = true,
                listTitleOverride = "小满的聊天"),
            Session(id = "agent", name = "整理报告", sessionMode = "agent")
        ), listOf(character))
        assertEquals("午后读书会", rows[0].listTitle)
        assertEquals("新的自动标题", rows[0].name)
        assertEquals("林小满", rows[1].listTitle)
        assertEquals("小满的聊天", rows[2].listTitle)
        assertEquals("整理报告", rows[3].listTitle)
        assertEquals("林小满", rows[0].characterLabel)
    }

    @Test
    fun buildSessionListRows_mapsTagsTrimDeduplicatesAndIgnoresBlank() {
        val sessions = listOf(
            Session(id = "tagged", tags = listOf("  省钱  ", "喵", "  ", "喵")),
            Session(id = "blank", tags = emptyList()),
            Session(id = "null-tags", tags = null)
        )

        val rows = buildSessionListRows(sessions, emptyList()) { it }

        assertEquals(listOf("省钱", "喵"), rows[0].tags)
        assertTrue(rows[1].tags.isEmpty())
        assertTrue(rows[2].tags.isEmpty())
    }
}
