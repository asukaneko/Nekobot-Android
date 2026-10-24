package com.nekobot.app.data.local.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class CompanionMemoryV2TriggerTest {
    private val fixture = UpstreamMigrationTest(63)
    private fun database(fused: Boolean, block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val schema = fixture.schema(64)
            fixture.createSchema(c, schema)
            fixture.insert(c, schema, "local_sessions", mapOf("id" to "s", "companion_mode" to if (fused) 1 else 0,
                "session_mode" to "agent", "character_id" to "fictional", "inherit_character" to 1, "long_conversation_enabled" to 1))
            fixture.insert(c, schema, "local_sessions", mapOf("id" to "other"))
            fixture.insert(c, schema, "companion_scopes", mapOf("id" to "scope", "sessionId" to "s", "characterId" to "fictional"))
            fixture.insert(c, schema, "local_messages", mapOf("id" to "u", "session_id" to "s", "role" to "user", "content" to "完整虚构原消息"))
            fixture.insert(c, schema, "companion_memories", mapOf("id" to "m", "scopeId" to "scope", "factKey" to "k", "current" to 1, "valid" to 1))
            fixture.insert(c, schema, "companion_sources", mapOf("memoryId" to "m", "messageId" to "u", "quote" to "片段"))
            fixture.insert(c, schema, "companion_receipts", mapOf("scopeId" to "scope", "messageId" to "u"))
            CompanionSchema.installTriggers(fixture.sqliteBridge(c))
            CompanionSchema.installTriggers(fixture.sqliteBridge(c))
            block(c)
        }
    }
    @Test fun fusedContentAndSoftDeletePreserveValidityAndFullSourceSnapshot() {
        for (change in listOf("content='新消息'", "deleted=1")) database(true) { c ->
            c.createStatement().use { it.execute("UPDATE local_messages SET $change WHERE id='u'") }
            assertEquals("1", fixture.scalar(c, "SELECT current AND valid FROM companion_memories WHERE id='m'"))
            assertEquals("完整虚构原消息", fixture.scalar(c, "SELECT messageSnapshot FROM companion_sources"))
            assertEquals("1", fixture.scalar(c, "SELECT count(*) FROM companion_receipts"))
        }
    }
    @Test fun oldCompanionContentAndDeleteStillInvalidateSourcesAndReceipts() {
        for (change in listOf("content='新消息'", "deleted=1")) database(false) { c ->
            c.createStatement().use { it.execute("UPDATE local_messages SET $change WHERE id='u'") }
            assertEquals("0", fixture.scalar(c, "SELECT valid FROM companion_memories WHERE id='m'"))
            assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_receipts"))
        }
    }
    @Test fun roleOwnerAndBothTimeChangesStillInvalidateFusedMemory() {
        for (change in listOf("role='assistant'", "session_id='other'", "created_at='2026-10-24'", "timestamp='2026-10-24'")) database(true) { c ->
            c.createStatement().use { it.execute("UPDATE local_messages SET $change WHERE id='u'") }
            assertEquals(change, "0", fixture.scalar(c, "SELECT valid FROM companion_memories WHERE id='m'"))
            assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_receipts"))
        }
    }
    @Test fun physicalDeleteRetainsSafetyInvalidationForBothModes() {
        for (fused in listOf(true, false)) database(fused) { c ->
            c.createStatement().use { it.execute("DELETE FROM local_messages WHERE id='u'") }
            assertEquals("0", fixture.scalar(c, "SELECT valid FROM companion_memories WHERE id='m'"))
            assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_sources"))
        }
    }
}
