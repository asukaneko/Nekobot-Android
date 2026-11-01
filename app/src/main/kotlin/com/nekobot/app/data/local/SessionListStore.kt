package com.nekobot.app.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import com.nekobot.app.data.local.db.SessionDao
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.SessionListPage
import com.nekobot.app.data.model.SessionListQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 将列表查询与完整会话读取分开，筛选先于分页执行。 */
internal class SessionListStore(private val dao: SessionDao) {
    suspend fun summaries(): List<Session> = withContext(Dispatchers.IO) {
        dao.listSummaries(buildSessionSummaryQuery(activeTemporaryIds = TemporarySession.activeIds()))
            .map { it.toSession() }
    }

    suspend fun page(query: SessionListQuery): SessionListPage = withContext(Dispatchers.IO) {
        // SQLite 的 lower 仅处理 ASCII；搜索保留 Kotlin 的 Unicode 忽略大小写语义，
        // 只读取经过其他筛选的轻量候选，不能先截断摘要或分页再匹配。
        if (query.search.isNotBlank()) {
            val candidates = dao.listSummaries(buildSessionSummaryQuery(
                query.copy(search = "", limit = Int.MAX_VALUE - 1, offset = 0),
                TemporarySession.activeIds(), fullPreview = true
            )).map { it.toSession() }
            val search = query.search.trim()
            val matches = candidates.filter {
                it.displayName.contains(search, ignoreCase = true) ||
                    it.characterName?.contains(search, ignoreCase = true) == true ||
                    it.lastMessage?.contains(search, ignoreCase = true) == true
            }
            val rows = matches.drop(query.offset.coerceAtLeast(0))
            val limit = query.limit.coerceIn(1, Int.MAX_VALUE - 1)
            return@withContext SessionListPage(rows.take(limit), rows.size > limit, matches.size)
        }
        val rows = dao.listSummaries(buildSessionSummaryQuery(query, TemporarySession.activeIds()))
        val limit = query.limit.coerceIn(1, Int.MAX_VALUE - 1)
        val count = dao.countSummaries(buildSessionSummaryQuery(query, TemporarySession.activeIds(), countOnly = true))
        SessionListPage(rows.take(limit).map { it.toSession() }, rows.size > limit, count)
    }
}

internal fun buildSessionSummaryQuery(
    query: SessionListQuery? = null,
    activeTemporaryIds: List<String> = emptyList(),
    countOnly: Boolean = false,
    fullPreview: Boolean = false
): SimpleSQLiteQuery {
    val args = mutableListOf<Any>()
    val conditions = mutableListOf<String>()
    conditions += if (activeTemporaryIds.isEmpty()) "id NOT GLOB 'temp_*'" else {
        args.addAll(activeTemporaryIds)
        "(id NOT GLOB 'temp_*' OR id IN (${activeTemporaryIds.joinToString(",") { "?" }}))"
    }
    if (query != null) {
        if (query.channel != null && query.channel != "local") conditions += "0"
        if ((!query.includeArchived && query.filter == "ALL") ||
            query.filter in listOf("CHARACTER_SESSIONS", "AGENT_SESSIONS", "AGENT_CHARACTERS", "UNARCHIVED")) {
            conditions += "archived = 0"
        }
        when (query.filter) {
            "CHARACTER_SESSIONS" -> conditions += "(session_mode = '' OR session_mode = 'character' COLLATE NOCASE)"
            "AGENT_SESSIONS" -> conditions += "session_mode = 'agent' COLLATE NOCASE AND companion_mode = 0"
            "AGENT_CHARACTERS" -> conditions += "session_mode = 'agent' COLLATE NOCASE AND companion_mode = 1"
            "ARCHIVED" -> conditions += "archived = 1"
            "FAVORITE" -> conditions += "favorite = 1"
            "PINNED" -> conditions += "pinned = 1"
            "PUBLIC" -> conditions += "is_public = 1"
            "BY_CHARACTER" -> query.characterId?.takeIf(String::isNotBlank)?.let { id ->
                conditions += "(character_id = ? OR instr(COALESCE(character_ids, ''), ?) > 0)"
                args += id
                args += sessionQueryGson.toJson(id)
            }
        }

    }
    val sql = buildString {
        val preview = if (fullPreview) "last_message" else "substr(last_message, 1, 512) AS last_message"
        append(if (countOnly) "SELECT COUNT(*) FROM local_sessions WHERE " else "SELECT id, name, character_id, character_ids, character_name, character_avatar, sender_name, sender_avatar, portrait, tags, favorite, pinned, archived, created_at, updated_at, $preview, message_count, is_public, session_mode, inherit_character, companion_mode, list_title_override FROM local_sessions WHERE ")
        append(conditions.joinToString(" AND ") { "($it)" })
        if (!countOnly) append(" ORDER BY archived ASC, pinned DESC, updated_at DESC, id ASC")
        if (query != null && !countOnly) {
            append(" LIMIT ? OFFSET ?")
            args += query.limit.coerceIn(1, Int.MAX_VALUE - 1) + 1
            args += query.offset.coerceAtLeast(0)
        }
    }
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}

private val sessionQueryGson = com.google.gson.Gson()
