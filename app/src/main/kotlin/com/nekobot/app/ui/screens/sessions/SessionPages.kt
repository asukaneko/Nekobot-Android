package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.R
import com.nekobot.app.data.model.Session

/** 固定分页独立于收藏、归档等筛选；群聊及旧版无模式会话仍可在角色页找到。 */
enum class SessionPage(val titleResId: Int) {
    CHARACTER(R.string.sessions_page_character),
    AGENT(R.string.sessions_page_agent),
    COMPANION(R.string.sessions_page_companion)
}

internal fun Session.sessionPage(): SessionPage = when {
    companionMode == true -> SessionPage.COMPANION
    sessionMode.equals("agent", ignoreCase = true) -> SessionPage.AGENT
    else -> SessionPage.CHARACTER
}

data class SessionPageQuery(
    val search: String = "",
    val filter: SessionFilter = SessionFilter.ALL,
    val channel: String? = null,
    val characterId: String? = null
) {
    val isActive: Boolean
        get() = search.isNotBlank() || filter != SessionFilter.ALL || channel != null || characterId != null
}

/** 更新一页不触碰其他页；随 ViewModel 保留，从聊天或统计页面返回时不丢条件。 */
data class SessionPageQueries(private val pages: Map<SessionPage, SessionPageQuery> = emptyMap()) {
    operator fun get(page: SessionPage): SessionPageQuery = pages[page] ?: SessionPageQuery()

    fun update(page: SessionPage, change: (SessionPageQuery) -> SessionPageQuery): SessionPageQueries =
        copy(pages = pages + (page to change(get(page))))
}

internal val SESSION_PAGE_FILTERS = listOf(
    SessionFilter.ALL,
    SessionFilter.UNARCHIVED,
    SessionFilter.ARCHIVED,
    SessionFilter.FAVORITE,
    SessionFilter.PINNED,
    SessionFilter.PUBLIC,
    SessionFilter.BY_CHARACTER
)

/** 先限定真实模式，再搜索/筛选，避免归档或收藏筛选把其他分页的会话带进来。 */
internal fun selectSessionsForPage(
    sessions: List<Session>,
    page: SessionPage,
    query: SessionPageQuery,
    includeArchived: Boolean,
    characterNames: Map<String, String> = emptyMap()
): List<Session> {
    val search = query.search.trim()
    return sessions.asSequence()
        .filter { it.isArchive != true && it.sessionPage() == page }
        .filter { query.filter != SessionFilter.ALL || includeArchived || it.archived != true }
        .filter { query.channel == null || (it.type?.lowercase()?.trim() ?: "web") == query.channel }
        .filter { session ->
            when (query.filter) {
                SessionFilter.ARCHIVED -> session.archived == true
                SessionFilter.UNARCHIVED -> session.archived != true
                SessionFilter.FAVORITE -> session.favorite == true
                SessionFilter.PINNED -> session.pinned == true
                SessionFilter.PUBLIC -> session.isPublic == true
                SessionFilter.BY_CHARACTER -> query.characterId.isNullOrBlank() ||
                    session.characterId == query.characterId || session.characterIds?.contains(query.characterId) == true
                // 旧筛选值只作兼容，不能改变固定分页归属。
                else -> true
            }
        }
        .filter { session ->
            search.isEmpty() || session.displayName.contains(search, ignoreCase = true) ||
                session.characterName?.contains(search, ignoreCase = true) == true ||
                session.senderName?.contains(search, ignoreCase = true) == true ||
                // 列表会从角色表回退标题，旧会话缺少冗余名字时同样能按所见名字找到。
                session.characterId?.let { characterNames[it]?.contains(search, ignoreCase = true) } == true ||
                session.characterIds.orEmpty().any { characterNames[it]?.contains(search, ignoreCase = true) == true } ||
                session.lastMessage?.contains(search, ignoreCase = true) == true
        }
        .sortedWith(
            compareByDescending<Session> { it.archived != true }
                .thenByDescending { it.pinned == true }
                .thenByDescending { it.updatedAt ?: it.createdAt ?: "" }
        )
        .toList()
}
