package com.nekobot.app.data.local.db

import androidx.room.RoomDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.sql.Connection
import java.sql.DriverManager

/** Synthetic data only; run the exact production path chosen by Room against real SQLite. */
@RunWith(Parameterized::class)
class CompanionHistoryUpgradeTest(private val startVersion: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "v{0} to v64")
        fun versions() = (53..63).map { arrayOf(it) }
    }

    private val fixture = UpstreamMigrationTest(startVersion)

    @Test fun upgradePreservesHistoryEvidenceAndProtection() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val schema = fixture.schema(startVersion)
            fixture.createSchema(c, schema)
            fun insert(table: String, values: Map<String, Any>) = fixture.insert(c, schema, table, values)
            insert("local_characters", mapOf("id" to "character"))
            insert("local_sessions", mapOf("id" to "session", "character_id" to "character",
                "session_mode" to "agent", "inherit_character" to 1, "long_conversation_enabled" to 1,
                "companion_mode" to 1))
            insert("local_sessions", mapOf("id" to "ordinary", "companion_mode" to 0))
            insert("companion_scopes", mapOf("id" to "scope", "sessionId" to "session", "characterId" to "character"))
            insert("companion_scopes", mapOf("id" to "ordinary-scope", "sessionId" to "ordinary"))
            insert("local_messages", mapOf("id" to "source", "session_id" to "session",
                "role" to "user", "content" to "Synthetic original message", "created_at" to "2026-01-02"))
            fun memory(id: String, key: String = id, version: Int = 1, current: Int = 1,
                valid: Int = 1, manual: Int = 0, locked: Int = 0, status: String = "active",
                scope: String = "scope") {
                insert("companion_memories", mapOf("id" to id, "scopeId" to scope, "factKey" to key,
                    "version" to version, "current" to current, "valid" to valid, "manual" to manual,
                    "locked" to locked, "status" to status, "content" to "Synthetic $id",
                    "createdAt" to "2026-01-01", "updatedAt" to "2026-01-02"))
                insert("companion_sources", mapOf("memoryId" to id, "messageId" to "source",
                    "fingerprint" to "synthetic-fingerprint", "quote" to "Synthetic quote $id", "role" to "user"))
                for (type in listOf("fact", "memory")) {
                    insert("companion_vectors", mapOf("scopeId" to scope, "itemType" to type,
                        "itemId" to id, "modelKey" to "synthetic-model", "vectorJson" to "[0.25]"))
                }
            }
            for (manual in 0..1) {
                val key = "replaced-$manual"
                memory("old-$manual", key, current = 0, manual = manual)
                memory("new-$manual", key, version = 2)
            }
            memory("duplicate-current", "duplicate")
            memory("duplicate-new", "duplicate", version = 2, status = "completed")
            memory("cancel-old", "cancel", current = 0)
            memory("cancel-new", "cancel", version = 2, status = "cancelled")
            memory("retracted-alone", status = "retracted", manual = 1)
            memory("before-retraction", "retract", current = 0)
            memory("retraction", "retract", version = 2, current = 0, status = "retracted")
            memory("invalid-old", "invalid-source", current = 0, valid = 0)
            memory("valid-new", "invalid-source", version = 2)
            memory("locked", "protected", locked = 1, manual = 1)
            memory("conflict", "protected", version = 2, status = "conflict")
            memory("locked-conflict", "protected", version = 3, status = "locked_conflict")
            memory("protected-new", "protected", version = 4)
            memory("no-successor", manual = 1)
            memory("invalid-successor-old", "invalid-successor")
            memory("invalid-successor", "invalid-successor", version = 2, valid = 0)
            memory("uncertain-successor-old", "uncertain")
            memory("uncertain-successor", "uncertain", version = 2, status = "conflict")
            memory("invalid-retraction-old", "invalid-retraction")
            memory("invalid-retraction", "invalid-retraction", version = 2, valid = 0, status = "retracted")
            memory("ordinary-old", "ordinary", current = 0, scope = "ordinary-scope")
            memory("ordinary-new", "ordinary", version = 2, scope = "ordinary-scope")
            insert("companion_clarifications", mapOf("scopeId" to "scope", "factKey" to "replaced-1",
                "memoryId" to "old-1", "question" to "Synthetic clarification"))
            // Real older installations have these triggers; preserving rows must not fire DELETE/valid invalidation.
            CompanionSchema.installTriggers(fixture.sqliteBridge(c))
            val count = fixture.scalar(c, "SELECT count(*) FROM companion_memories")
            val vectors = fixture.scalar(c, "SELECT count(*) FROM companion_vectors")
            val path = RoomDatabase.MigrationContainer().apply {
                addMigrations(*NekobotDatabase.ALL_MIGRATIONS)
            }.findMigrationPath(startVersion, 64)
            assertNotNull(path)
            c.autoCommit = false
            path!!.forEach { it.migrate(fixture.sqliteBridge(c)) }
            c.commit()
            c.autoCommit = true

            fixture.assertSchema(c)
            assertEquals(count, fixture.scalar(c, "SELECT count(*) FROM companion_memories"))
            assertEquals(count, fixture.scalar(c, "SELECT count(*) FROM companion_sources"))
            assertEquals(vectors, fixture.scalar(c, "SELECT count(*) FROM companion_vectors"))
            assertEquals("Synthetic original message", fixture.scalar(c, "SELECT content FROM local_messages WHERE id='source'"))
            assertEquals("Synthetic quote old-1", fixture.scalar(c, "SELECT quote FROM companion_sources WHERE memoryId='old-1'"))
            assertEquals("1", fixture.scalar(c, "SELECT count(*) FROM companion_clarifications WHERE memoryId='old-1'"))
            assertEquals("1", value(c, "old-1", "manual"))
            assertEquals("0", value(c, "invalid-old", "valid"))
            for (id in listOf("old-0", "old-1", "duplicate-current", "cancel-old",
                "retracted-alone", "before-retraction", "retraction", "invalid-old")) {
                assertEquals("$id retained", "Synthetic $id", value(c, id, "content"))
                assertEquals("$id is historical", "0", value(c, id, "current"))
                assertEquals("$id is voided", "1", value(c, id, "voided"))
                assertEquals("$id keeps timestamp", "2026-01-02", value(c, id, "updatedAt"))
            }
            assertEquals("retract", value(c, "retracted-alone", "voidedReason"))
            assertNull(value(c, "retracted-alone", "supersededById"))
            assertEquals("new-1", value(c, "old-1", "supersededById"))
            assertEquals("retraction", value(c, "before-retraction", "supersededById"))
            for (id in listOf("new-0", "new-1", "duplicate-new", "cancel-new", "valid-new",
                "locked", "conflict", "locked-conflict", "protected-new", "no-successor",
                "invalid-successor-old", "uncertain-successor-old", "invalid-retraction-old")) {
                assertEquals("$id stays current", "1", value(c, id, "current"))
                assertEquals("$id is not voided", "0", value(c, id, "voided"))
            }
            assertEquals("1", value(c, "locked", "locked"))
            assertEquals("conflict", value(c, "conflict", "status"))
            assertEquals("locked_conflict", value(c, "locked-conflict", "status"))
            assertEquals("0", value(c, "ordinary-old", "voided"))
            assertEquals("0", value(c, "invalid-retraction", "valid"))
            assertEquals("0", value(c, "invalid-retraction", "voided"))
            assertEquals("ok", fixture.scalar(c, "PRAGMA integrity_check"))
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA foreign_key_check").use { assertFalse(it.next()) }
            }
        }
    }

    private fun value(c: Connection, id: String, column: String): String? =
        fixture.scalar(c, "SELECT $column FROM companion_memories WHERE id='$id'")
}