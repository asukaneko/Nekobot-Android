package com.nekobot.app.data.local

import androidx.sqlite.db.SupportSQLiteProgram
import com.nekobot.app.data.model.SessionListQuery
import org.junit.Assert.*
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class SessionSummaryQueryTest {
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use { it.execute("""
            CREATE TABLE local_sessions (
                id TEXT PRIMARY KEY, name TEXT, character_id TEXT, character_ids TEXT,
                character_name TEXT, character_avatar TEXT, sender_name TEXT, sender_avatar TEXT,
                portrait TEXT, tags TEXT, favorite INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0,
                archived INTEGER DEFAULT 0, created_at TEXT DEFAULT '', updated_at TEXT DEFAULT '',
                last_message TEXT, message_count INTEGER DEFAULT 0, is_public INTEGER DEFAULT 0,
                session_mode TEXT DEFAULT 'character', inherit_character INTEGER DEFAULT 0,
                companion_mode INTEGER DEFAULT 0, list_title_override TEXT
            )
        """.trimIndent()) }
    }

    private fun Connection.add(id: String, mode: String = "character", archived: Boolean = false,
        pinned: Boolean = false, companion: Boolean = false, message: String = "demo") {
        prepareStatement("INSERT INTO local_sessions(id,name,session_mode,archived,pinned,companion_mode,last_message) VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1, id); it.setString(2, id); it.setString(3, mode)
            it.setBoolean(4, archived); it.setBoolean(5, pinned); it.setBoolean(6, companion)
            it.setString(7, message); it.executeUpdate()
        }
    }

    private fun Connection.ids(query: SessionListQuery, activeIds: List<String> = emptyList()): List<String> {
        val sql = buildSessionSummaryQuery(query, activeIds)
        return prepareStatement(sql.sql).use { statement ->
            sql.bindTo(object : SupportSQLiteProgram {
                override fun bindNull(index: Int) = statement.setObject(index, null)
                override fun bindLong(index: Int, value: Long) = statement.setLong(index, value)
                override fun bindDouble(index: Int, value: Double) = statement.setDouble(index, value)
                override fun bindString(index: Int, value: String) = statement.setString(index, value)
                override fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
                override fun clearBindings() = statement.clearParameters()
                override fun close() = Unit
            })
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("id")) } }
        }
    }

    @Test fun stablePagingSortsPinnedAndArchivedBeforeApplyingLimit() {
        database().use { db ->
            db.add("c"); db.add("b"); db.add("a", pinned = true); db.add("d", archived = true, pinned = true)
            // 额外读取一条用来判断是否还有下一页。
            assertEquals(listOf("a", "b", "c"), db.ids(SessionListQuery(limit = 2)))
            assertEquals(listOf("c", "d"), db.ids(SessionListQuery(limit = 2, offset = 2)))
            assertEquals(listOf("a", "b", "c"), db.ids(SessionListQuery(includeArchived = false)))
        }
    }

    @Test fun excludesOldTemporarySessionsAndSeparatesAgentKinds() {
        database().use { db ->
            db.add("temp_previous"); db.add("temp_current")
            db.add("agent", "agent"); db.add("companion", "agent", companion = true)
            db.add("archived-companion", "agent", archived = true, companion = true)
            assertFalse("temp_previous" in db.ids(SessionListQuery(), listOf("temp_current")))
            assertTrue("temp_current" in db.ids(SessionListQuery(), listOf("temp_current")))
            assertEquals(listOf("agent"), db.ids(SessionListQuery(filter = "AGENT_SESSIONS")))
            assertEquals(listOf("companion"), db.ids(SessionListQuery(filter = "AGENT_CHARACTERS")))
        }
    }

    @Test fun characterFilterBindsQuotesAndWildcardsAsLiteralText() {
        database().use { db ->
            db.add("match"); db.add("other")
            val id = "demo_%'suffix"
            db.prepareStatement("UPDATE local_sessions SET character_id=? WHERE id='match'").use {
                it.setString(1, id); it.executeUpdate()
            }
            assertEquals(listOf("match"), db.ids(SessionListQuery(filter = "BY_CHARACTER", characterId = id)))
            assertTrue(db.ids(SessionListQuery(filter = "BY_CHARACTER", characterId = "' OR 1=1 --")).isEmpty())
            assertTrue(db.ids(SessionListQuery(channel = "web")).isEmpty())
        }
    }
}
