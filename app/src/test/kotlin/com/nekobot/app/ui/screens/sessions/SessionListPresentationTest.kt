package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListPresentationTest {

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
            SessionOverview(total = 4, pinned = 1, favorite = 1, archived = 1),
            buildSessionOverview(sessions)
        )
    }

    @Test
    fun quickSessionFilters_exposesTheFourPersistentShortcutsInDisplayOrder() {
        assertEquals(
            listOf(
                SessionFilter.ALL,
                SessionFilter.PINNED,
                SessionFilter.FAVORITE,
                SessionFilter.ARCHIVED
            ),
            QUICK_SESSION_FILTERS
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
}
