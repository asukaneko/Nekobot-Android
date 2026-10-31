package com.nekobot.app.data.local.db

import com.nekobot.app.data.local.ai.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** File-backed, synthetic SQLite: receipts survive close/reopen, while raw chat and evidence remain intact. */
class FusedSummaryProgressPersistenceTest {
    private val fixture = UpstreamMigrationTest(63)
    private val scope = CompanionScopeEntity("scope", "test", "s", "fictional", 0, updatedAt = "now")
    private fun rows() = (1..8).flatMap { turn ->
        listOf("user", "assistant").map { role ->
            LocalMessageEntity("$turn-$role", "s", role, "500", timestamp = "2026-10-23", createdAt = "2026-10-23")
        }
    } + LocalMessageEntity("current", "s", "user", "50", timestamp = "2026-10-23", createdAt = "2026-10-23")

    private fun save(c: Connection, receipts: List<CompanionReceiptEntity>) {
        c.prepareStatement("INSERT OR REPLACE INTO companion_receipts(scopeId,messageId,fingerprint) VALUES(?,?,?)").use { statement ->
            for (receipt in receipts) {
                statement.setString(1, receipt.scopeId); statement.setString(2, receipt.messageId)
                statement.setString(3, receipt.fingerprint); statement.executeUpdate()
            }
        }
    }
    private fun load(c: Connection): List<CompanionReceiptEntity> = c.createStatement().use { s ->
        s.executeQuery("SELECT scopeId,messageId,fingerprint FROM companion_receipts").use { rs ->
            buildList { while (rs.next()) add(CompanionReceiptEntity(rs.getString(1), rs.getString(2), rs.getString(3))) }
        }
    }
    private fun plan(rows: List<LocalMessageEntity>, receipts: List<CompanionReceiptEntity>) =
        FusedCompanionPolicy.windowPlan(rows, FusedSummaryProgress.summarizedIds(scope, rows, receipts),
            currentMessageId = "current", count = { it.sumOf { row -> row.content.toInt() } })

    @Test fun reopenKeepsProcessedTurnsOutWithoutDeletingOriginalsOrCrossingScope() {
        val file = File.createTempFile("fictional-rolling-", ".db")
        val rows = rows()
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                val schema = fixture.schema(64)
                fixture.createSchema(c, schema)
                fun insert(table: String, values: Map<String, Any>) = fixture.insert(c, schema, table, values)
                insert("local_characters", mapOf("id" to "fictional"))
                insert("local_sessions", mapOf("id" to "s", "character_id" to "fictional",
                    "companion_mode" to 1, "session_mode" to "agent", "inherit_character" to 1,
                    "long_conversation_enabled" to 1))
                insert("companion_scopes", mapOf("id" to scope.id, "profile" to scope.profile,
                    "sessionId" to scope.sessionId, "characterId" to scope.characterId))
                for (row in rows) insert("local_messages", mapOf("id" to row.id, "session_id" to row.sessionId,
                    "role" to row.role, "content" to row.content, "created_at" to row.createdAt,
                    "timestamp" to row.timestamp))
                insert("companion_memories", mapOf("id" to "memory", "scopeId" to scope.id,
                    "factKey" to "fact", "content" to "Synthetic retained memory", "status" to "active"))
                insert("companion_sources", mapOf("memoryId" to "memory", "messageId" to rows.first().id,
                    "fingerprint" to CompanionMemoryPolicy.fingerprint(rows.first()), "quote" to "Synthetic quote"))
                CompanionSchema.installTriggers(fixture.sqliteBridge(c))
                val batch = plan(rows, emptyList()).batch
                c.autoCommit = false
                save(c, FusedSummaryProgress.receipts(scope, batch, batch))
                c.commit()
            }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                val receipts = load(c)
                val next = plan(rows, receipts)
                assertEquals(6050, next.window.sumOf { it.content.toInt() })
                assertEquals(rows.drop(4), next.window)
                assertFalse(next.needsSummary)
                assertEquals("17", fixture.scalar(c, "SELECT count(*) FROM local_messages"))
                assertEquals("1", fixture.scalar(c, "SELECT count(*) FROM companion_sources"))
                assertEquals("Synthetic quote", fixture.scalar(c, "SELECT quote FROM companion_sources"))
                assertEquals("Synthetic retained memory", fixture.scalar(c, "SELECT content FROM companion_memories"))
                val other = scope.copy(id = "other-profile-scope", profile = "other")
                assertTrue(FusedSummaryProgress.summarizedIds(other, rows, receipts).isEmpty())
                val otherSession = scope.copy(id = "other-session-scope", sessionId = "different")
                assertTrue(FusedSummaryProgress.summarizedIds(otherSession,
                    rows.map { it.copy(sessionId = "different") }, receipts).isEmpty())
                val edited = rows.mapIndexed { index, row -> if (index == 0) row.copy(content = "new") else row }
                assertFalse(rows.first().id in FusedSummaryProgress.summarizedIds(scope, edited, receipts))
            }
        } finally { file.delete() }
    }

    @Test fun failedReceiptTransactionLeavesExactlyTheSameBatchPending() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            fixture.createSchema(c, fixture.schema(64))
            val rows = rows()
            val batch = plan(rows, emptyList()).batch
            c.autoCommit = false
            save(c, FusedSummaryProgress.receipts(scope, batch, batch))
            c.rollback() // Simulate a storage/commit failure: production uses one Room transaction.
            c.autoCommit = true
            assertTrue(load(c).isEmpty())
            assertEquals(batch, plan(rows, load(c)).batch)
        }
    }

    @Test fun changedOrMissingSourceCannotProducePartialReceipts() {
        val target = rows().take(4)
        for (fresh in listOf(target.drop(1), target.mapIndexed { index, row ->
            if (index == 0) row.copy(content = "changed") else row
        }, target.map { it.copy(sessionId = "other") })) {
            try { FusedSummaryProgress.receipts(scope, target, fresh); fail() }
            catch (e: FusedSummaryFailure) { assertEquals(FusedSummaryFailure.Reason.SOURCE_CHANGED, e.reason) }
        }
        assertEquals(4, FusedSummaryProgress.receipts(scope, target, target).size)
    }
}
