package com.nekobot.app.data.local.db

import org.junit.Assert.*
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** 用真实SQLite运行生产迁移，并核对Room导出的完整结构。 */
class CompanionMemoryV2MigrationTest {
    private val fixture = UpstreamMigrationTest(63)

    private fun migrated(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val old = fixture.schema(63)
            fixture.createSchema(c, old)
            fixture.insert(c, old, "local_sessions", mapOf("id" to "session"))
            fixture.insert(c, old, "companion_scopes", mapOf("id" to "scope", "sessionId" to "session"))
            fixture.insert(c, old, "companion_memories", mapOf("id" to "current", "scopeId" to "scope", "factKey" to "job", "version" to 2,
                "current" to 1, "valid" to 1, "manual" to 1, "locked" to 1, "content" to "虚构角色在杭州工作"))
            fixture.insert(c, old, "companion_memories", mapOf("id" to "old", "scopeId" to "scope", "factKey" to "job", "version" to 1,
                "status" to "retracted", "current" to 0, "updatedAt" to "2026-10-22T10:00:00"))
            fixture.insert(c, old, "companion_sources", mapOf("memoryId" to "current", "messageId" to "source", "quote" to "旧来源片段"))
            fixture.insert(c, old, "companion_clarifications", mapOf("scopeId" to "scope", "factKey" to "job", "memoryId" to "current", "question" to "旧待核对项"))
            NekobotDatabase.MIGRATION_63_64.migrate(fixture.sqliteBridge(c))
            block(c)
        }
    }

    @Test fun migrationPreservesProtectedMemoryQuoteAndClarification() = migrated { c ->
        fixture.assertSchema(c)
        assertEquals("2", fixture.scalar(c, "SELECT count(*) FROM companion_memories"))
        assertEquals("虚构角色在杭州工作", fixture.scalar(c, "SELECT content FROM companion_memories WHERE id='current'"))
        assertEquals("1", fixture.scalar(c, "SELECT locked AND manual AND current AND valid FROM companion_memories WHERE id='current'"))
        assertEquals("0", fixture.scalar(c, "SELECT voided+pendingConfirm+uncertainInjectedTurns FROM companion_memories WHERE id='current'"))
        assertEquals("旧来源片段", fixture.scalar(c, "SELECT quote FROM companion_sources"))
        assertNull(fixture.scalar(c, "SELECT messageSnapshot FROM companion_sources"))
        assertEquals("1", fixture.scalar(c, "SELECT count(*) FROM companion_clarifications"))
        assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_locked_reviews"))
    }

    @Test fun retractedVersionsBecomeVoidedWithoutDeletion() = migrated { c ->
        assertEquals("1", fixture.scalar(c, "SELECT voided FROM companion_memories WHERE id='old'"))
        assertEquals("retract", fixture.scalar(c, "SELECT voidedReason FROM companion_memories WHERE id='old'"))
        assertEquals("2026-10-22T10:00:00", fixture.scalar(c, "SELECT voidedAt FROM companion_memories WHERE id='old'"))
    }

    @Test fun scopeDeletionCleansOperationReceiptsAndReviewQueue() = migrated { c ->
        c.createStatement().use { s ->
            s.execute("INSERT INTO companion_applied_ops VALUES('scope','op','current','now')")
            s.execute("INSERT INTO companion_locked_reviews VALUES('r','scope','session','current','new','now',NULL,NULL)")
            s.execute("DELETE FROM companion_scopes WHERE id='scope'")
        }
        assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_applied_ops"))
        assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_locked_reviews"))
    }

    @Test fun sessionDeletionAlsoCleansUnboundReviewsAndOldColumnInsertsUseDefaults() = migrated { c ->
        c.createStatement().use { s ->
            s.execute("INSERT INTO companion_locked_reviews VALUES('r','missing','session','current','new','now',NULL,NULL)")
            s.execute("DELETE FROM local_sessions WHERE id='session'")
        }
        assertEquals("0", fixture.scalar(c, "SELECT count(*) FROM companion_locked_reviews"))
        assertEquals("ok", fixture.scalar(c, "PRAGMA integrity_check"))
    }
}
