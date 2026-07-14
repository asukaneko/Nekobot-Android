package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
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
}
