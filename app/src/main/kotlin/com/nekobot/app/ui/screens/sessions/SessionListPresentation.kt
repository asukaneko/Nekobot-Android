package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.Session

data class SessionOverview(
    val total: Int = 0,
    val pinned: Int = 0,
    val favorite: Int = 0,
    val archived: Int = 0
)

val QUICK_SESSION_FILTERS = listOf(
    SessionFilter.ALL,
    SessionFilter.PINNED,
    SessionFilter.FAVORITE,
    SessionFilter.ARCHIVED
)

fun buildSessionOverview(sessions: List<Session>): SessionOverview {
    val visible = sessions.filter { it.isArchive != true }
    return SessionOverview(
        total = visible.size,
        pinned = visible.count { it.pinned == true },
        favorite = visible.count { it.favorite == true },
        archived = visible.count { it.archived == true }
    )
}
