package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPagesTest {
    private val sessions = listOf(
        Session(id = "legacy", name = "旧会话"),
        Session(id = "character", name = "角色", sessionMode = "character"),
        Session(id = "group", name = "群聊", sessionMode = "group", characterIds = listOf("one", "two")),
        Session(id = "agent", name = "助手", sessionMode = "agent"),
        Session(id = "inherited", name = "继承角色的Agent", sessionMode = "AGENT", inheritCharacter = true),
        Session(id = "companion", name = "专属", sessionMode = "agent", companionMode = true),
        Session(id = "automatic-archive", sessionMode = "agent", companionMode = true, isArchive = true)
    )

    @Test fun pagesAreDisjointAndKeepLegacyAndGroupChatsReachable() {
        val pages = SessionPage.entries.map { page -> selectSessionsForPage(sessions, page, SessionPageQuery(), false).map { it.id }.toSet() }
        assertEquals(setOf("legacy", "character", "group"), pages[0])
        assertEquals(setOf("agent", "inherited"), pages[1])
        assertEquals(setOf("companion"), pages[2])
        assertEquals(6, pages.flatten().size)
        assertEquals(6, pages.flatten().distinct().size)
    }

    @Test fun fixedCompanionFlagWinsOverLegacyModeMetadata() {
        assertEquals(SessionPage.COMPANION, Session(companionMode = true).sessionPage())
        assertEquals(SessionPage.AGENT, Session(sessionMode = "agent", longConversationEnabled = true).sessionPage())
    }

    @Test fun archivedFavoriteAndPinnedFiltersNeverBringInAnotherPage() {
        val rows = SessionPage.entries.map { page ->
            Session(id = page.name, sessionMode = if (page == SessionPage.CHARACTER) "character" else "agent",
                companionMode = page == SessionPage.COMPANION, archived = true, favorite = true, pinned = true)
        }
        listOf(SessionFilter.ARCHIVED, SessionFilter.FAVORITE, SessionFilter.PINNED).forEach { filter ->
            SessionPage.entries.forEach { page ->
                assertEquals(listOf(page.name), selectSessionsForPage(rows, page, SessionPageQuery(filter = filter), false).map { it.id })
            }
        }
    }

    @Test fun eachPageRetainsItsSearchChannelAndCharacterSelection() {
        val original = SessionPageQueries()
            .update(SessionPage.CHARACTER) { it.copy(search = "青禾", channel = "qq", filter = SessionFilter.BY_CHARACTER, characterId = "one") }
            .update(SessionPage.AGENT) { it.copy(search = "日报", filter = SessionFilter.PINNED) }
            .update(SessionPage.COMPANION) { it.copy(search = "周末", filter = SessionFilter.ARCHIVED) }
        val cleared = original.update(SessionPage.AGENT) { SessionPageQuery() }
        assertEquals(original[SessionPage.CHARACTER], cleared[SessionPage.CHARACTER])
        assertEquals(original[SessionPage.COMPANION], cleared[SessionPage.COMPANION])
        assertFalse(cleared[SessionPage.AGENT].isActive)
        assertTrue(original[SessionPage.AGENT].isActive)
    }

    @Test fun searchMatchesRenamedTitleAndSenderButRemainsInCurrentPage() {
        val rows = listOf(
            Session(id = "renamed", name = "旧名", listTitleOverride = "小满的周末", sessionMode = "character"),
            Session(id = "sender", name = "旧名", senderName = "周末朋友", sessionMode = "character"),
            Session(id = "other-page", name = "周末", sessionMode = "agent")
        )
        assertEquals(setOf("renamed", "sender"), selectSessionsForPage(rows, SessionPage.CHARACTER, SessionPageQuery(search = " 周末 "), false).map { it.id }.toSet())
    }

    @Test fun searchFindsVisibleRoleTitleWhenOldSessionOnlyHasCharacterId() {
        val old = Session(id = "old", name = "初次相识", characterId = "role", senderName = "AI")
        assertEquals(listOf("old"), selectSessionsForPage(listOf(old), SessionPage.CHARACTER,
            SessionPageQuery(search = "青禾"), false, mapOf("role" to "青禾")).map { it.id })
    }

    @Test fun archiveVisibilityAndSortingPreservePinnedAndRecentOrder() {
        val rows = listOf(
            Session(id = "old-pinned", pinned = true, updatedAt = "2026-10-01"),
            Session(id = "new", updatedAt = "2026-10-18"),
            Session(id = "old", createdAt = "2026-10-02"),
            Session(id = "archived", pinned = true, archived = true, updatedAt = "2026-10-18")
        )
        assertEquals(listOf("old-pinned", "new", "old"), selectSessionsForPage(rows, SessionPage.CHARACTER, SessionPageQuery(), false).map { it.id })
        assertEquals(listOf("old-pinned", "new", "old", "archived"), selectSessionsForPage(rows, SessionPage.CHARACTER, SessionPageQuery(), true).map { it.id })
    }

    @Test fun channelAndGroupCharacterFiltersStillApplyInsidePage() {
        val rows = listOf(
            Session(id = "web", characterId = "one"),
            Session(id = "qq-group", type = "QQ", sessionMode = "group", characterIds = listOf("one", "two")),
            Session(id = "qq-other", type = "qq", characterId = "three")
        )
        assertEquals(listOf("qq-group"), selectSessionsForPage(rows, SessionPage.CHARACTER,
            SessionPageQuery(filter = SessionFilter.BY_CHARACTER, channel = "qq", characterId = "two"), false).map { it.id })
    }

    @Test fun pageFiltersDoNotOfferAnotherModeAsAnOverlappingCategory() {
        assertEquals(listOf(SessionFilter.ALL, SessionFilter.UNARCHIVED, SessionFilter.ARCHIVED, SessionFilter.FAVORITE,
            SessionFilter.PINNED, SessionFilter.PUBLIC, SessionFilter.BY_CHARACTER), SESSION_PAGE_FILTERS)
    }
}
