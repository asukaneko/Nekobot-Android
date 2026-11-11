package com.nekobot.app.data.local.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 删除单角色窗口必须同时释放归属。
 *
 * 归属行指向已删除的会话时，角色页会读不到窗口而打不开，该角色也再建不了会话。
 */
class CompanionOwnerReleaseTest {
    private val fixture = UpstreamMigrationTest(63)

    private fun companion(id: String, characterId: String) = mapOf(
        "id" to id, "character_id" to characterId, "session_mode" to "agent",
        "inherit_character" to 1, "long_conversation_enabled" to 1, "companion_mode" to 1
    )

    private fun database(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val schema = fixture.schema(64)
            fixture.createSchema(connection, schema)
            FusedCompanionSchema.installTriggers(fixture.sqliteBridge(connection))
            fixture.insert(connection, schema, "local_sessions", companion("window", "role"))
            fixture.insert(connection, schema, "local_sessions", companion("other-window", "other-role"))
            fixture.insert(connection, schema, "local_sessions", mapOf("id" to "ordinary", "session_mode" to "chat"))
            block(connection)
        }
    }

    @Test fun creatingWindowRegistersOwnership() = database { connection ->
        assertEquals("window", fixture.scalar(connection, "SELECT sessionId FROM companion_owners WHERE characterId='role'"))
    }

    @Test fun deletingWindowReleasesOnlyItsOwnOwnership() = database { connection ->
        connection.createStatement().use { it.execute("DELETE FROM local_sessions WHERE id='window'") }
        assertEquals("0", fixture.scalar(connection, "SELECT count(*) FROM companion_owners WHERE characterId='role'"))
        assertEquals("other-window",
            fixture.scalar(connection, "SELECT sessionId FROM companion_owners WHERE characterId='other-role'"))
    }

    @Test fun deletingOrdinarySessionKeepsCompanionOwnership() = database { connection ->
        connection.createStatement().use { it.execute("DELETE FROM local_sessions WHERE id='ordinary'") }
        assertEquals("window", fixture.scalar(connection, "SELECT sessionId FROM companion_owners WHERE characterId='role'"))
    }

    @Test fun deletingWindowAlsoClearsScopeProgress() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val schema = fixture.schema(64)
            fixture.createSchema(connection, schema)
            FusedCompanionSchema.installTriggers(fixture.sqliteBridge(connection))
            CompanionSchema.installTriggers(fixture.sqliteBridge(connection))
            fixture.insert(connection, schema, "local_sessions", companion("window", "role"))
            fixture.insert(connection, schema, "companion_scopes", mapOf(
                "id" to "scope", "sessionId" to "window", "characterId" to "role",
                "profile" to "profile", "baselineRowId" to 0, "archiveRowId" to 0,
                "revision" to 0, "historyAccess" to 0, "updatedAt" to "now"))
            fixture.insert(connection, schema, "companion_applied_ops",
                mapOf("scopeId" to "scope", "opKey" to "op", "createdAt" to "now"))
            fixture.insert(connection, schema, "companion_locked_reviews", mapOf(
                "id" to "review", "scopeId" to "scope", "sessionId" to "window", "createdAt" to "now"))
            connection.createStatement().use { it.execute("DELETE FROM local_sessions WHERE id='window'") }
            for (table in listOf("companion_applied_ops", "companion_locked_reviews", "companion_scopes")) {
                assertEquals(table, "0", fixture.scalar(connection, "SELECT count(*) FROM $table"))
            }
        }
    }
}
