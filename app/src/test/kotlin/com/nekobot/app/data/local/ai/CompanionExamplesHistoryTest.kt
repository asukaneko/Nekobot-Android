package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.sqlite.ProgressHandler
import java.sql.DriverManager

/** 执行生产分页 SQL 和读取逻辑；无需 Android 设备或模型服务。 */
class CompanionExamplesHistoryTest {
    private class HistoryDb : AutoCloseable {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        var pages = 0
        var rowsRead = 0
        var largestPage = 0
        private var sequence = 0
        init {
            connection.createStatement().use { s ->
                s.execute("CREATE TABLE local_messages(id TEXT PRIMARY KEY, session_id TEXT, role TEXT, content TEXT, model TEXT, source TEXT, deleted INTEGER, created_at TEXT)")
                s.execute("CREATE INDEX index_local_messages_session_id_created_at ON local_messages(session_id,created_at)")
            }
            connection.autoCommit = false
        }
        fun message(role: String, content: String = "今天聊些什么好呢？我们一起慢慢说。", deleted: Boolean = false,
                    source: String? = null, model: String? = null, date: String = "2026-10-17", session: String = "new"): String {
            val id = "m${++sequence}"
            connection.prepareStatement("INSERT INTO local_messages VALUES(?,?,?,?,?,?,?,?)").use { s ->
                s.setString(1, id); s.setString(2, session); s.setString(3, role); s.setString(4, content)
                s.setString(5, model); s.setString(6, source); s.setInt(7, if (deleted) 1 else 0); s.setString(8, date)
                s.executeUpdate()
            }
            return id
        }
        fun turn(): String { message("user"); return message("assistant") }
        fun read(cursor: LocalMessageCursor, limit: Int, session: String = "new"): List<LocalMessageRow> {
            pages++
            val rows = connection.prepareStatement(CompanionExamplesSql.HISTORY_PAGE).use { s ->
                s.setString(1, session); s.setString(2, cursor.createdAt); s.setLong(3, cursor.rowId); s.setInt(4, limit)
                s.executeQuery().use { r -> buildList {
                    while (r.next()) add(LocalMessageRow(LocalMessageEntity(
                        id = r.getString("id"), sessionId = r.getString("session_id"), role = r.getString("role"),
                        content = r.getString("content"), model = r.getString("model"), source = r.getString("source"),
                        deleted = r.getInt("deleted") != 0, timestamp = r.getString("created_at"), createdAt = r.getString("created_at")
                    ), r.getLong("row_id")))
                } }
            }
            rowsRead += rows.size
            largestPage = maxOf(largestPage, rows.size)
            return rows
        }
        fun history() = CompanionExamplesHistory { cursor, limit -> read(cursor, limit) }
        suspend fun offer() = history().offer(true, CompanionExamplesEntity("new", "waiting"))!!.state
        override fun close() = connection.close()
    }

    @Test fun incompleteFirstHundredDoesNotHideTwentyLaterCompleteRounds() = runBlocking {
        HistoryDb().use { db ->
            repeat(50) { db.message("user"); db.message("assistant", source = "companion_incomplete") }
            repeat(20) { db.turn() }
            assertEquals("offered", db.offer())
            assertEquals(2, db.pages)
            assertEquals(140, db.rowsRead)
        }
    }

    @Test fun nineteenWaitsAndTwentyOrMoreOffers() = runBlocking {
        for (rounds in listOf(0, 19, 20, 21)) HistoryDb().use { db ->
            repeat(rounds) { db.turn() }
            assertEquals(if (rounds < 20) "waiting" else "offered", db.offer())
        }
    }

    @Test fun completePairAcrossPageBoundaryCountsExactlyOnce() = runBlocking {
        HistoryDb().use { db ->
            repeat(99) { db.message("system") }
            repeat(19) { db.turn() }
            assertEquals("waiting", db.offer())
            db.turn()
            assertEquals("offered", db.offer())
        }
    }

    @Test fun missingRepliesAndOrphanAnswersDoNotCreateExtraRounds() = runBlocking {
        HistoryDb().use { db ->
            db.message("assistant") // 没有问题的开场白。
            repeat(18) { db.turn() }
            db.message("user") // 没有回答的问题。
            db.turn()
            db.message("assistant") // 同一轮额外消息不是新轮。
            assertEquals("waiting", db.offer())
            db.turn()
            assertEquals("offered", db.offer())
        }
    }

    @Test fun deletedBlankSystemCommandAndBackgroundRowsRemainPairingBarriers() = runBlocking {
        HistoryDb().use { db ->
            repeat(19) { db.turn() }
            db.message("user", deleted = true); db.message("assistant")
            db.message("user"); db.message("assistant", deleted = true)
            db.message("user"); db.message("system"); db.message("assistant")
            db.message("user", content = " "); db.message("assistant")
            db.message("user"); db.message("assistant", content = "\n")
            db.message("user", model = "local-command"); db.message("assistant")
            for (source in listOf("companion_incomplete", "task_center", "proactive_chat")) {
                db.message("user"); db.message("assistant", source = source)
            }
            assertEquals("waiting", db.offer())
            db.turn()
            assertEquals("offered", db.offer())
        }
    }

    @Test fun legacyAndConvertedWithoutEligibilityNeverReadHistory() = runBlocking {
        val history = CompanionExamplesHistory { _, _ -> error("无资格的会话不应加载历史") }
        for (origin in listOf("old", "converted")) assertNull(origin, history.offer(true, null))
    }

    @Test fun importedDisabledAndEveryNonWaitingStateNeverReadHistory() = runBlocking {
        val history = CompanionExamplesHistory { _, _ -> error("已停用或处理过的会话不应重新计数") }
        for (state in listOf("offered", "declined", "selecting", "active", "failed", "disabled")) {
            val row = CompanionExamplesEntity("imported", state, optedIn = true)
            assertEquals(row, history.offer(true, row))
        }
    }

    @Test fun ordinarySessionsNeverAcquireCompanionInvitation() = runBlocking {
        val history = CompanionExamplesHistory { _, _ -> error("普通会话不应加载示例历史") }
        val row = CompanionExamplesEntity("ordinary", "waiting")
        assertEquals(row, history.offer(false, row))
    }

    @Test fun sqlPagesKeepSameTimestampRowsDeletionMarkersAndSessionBoundary() {
        HistoryDb().use { db ->
            repeat(120) { db.message("system", date = "2026-10-18", deleted = it == 20) }
            repeat(120) { db.message("system", date = "2026-10-17") }
            repeat(40) { db.message("assistant", session = "other") }
            val all = mutableListOf<LocalMessageRow>()
            var cursor = LocalMessageCursor("", Long.MIN_VALUE)
            do {
                val page = db.read(cursor, 100)
                all += page
                page.lastOrNull()?.let { cursor = LocalMessageCursor(it.message.createdAt, it.rowId) }
            } while (page.size == 100)
            assertEquals(240, all.size)
            assertEquals(240, all.map { it.message.id }.distinct().size)
            assertEquals(1, all.count { it.message.deleted })
            assertTrue(all.all { it.message.sessionId == "new" })
            assertEquals(all.sortedWith(compareBy({ it.message.createdAt }, { it.rowId })), all)
        }
    }

    @Test fun invitationStopsAfterThresholdWithoutLoadingLongTail() = runBlocking {
        HistoryDb().use { db ->
            repeat(20) { db.turn() }
            repeat(62000) { db.message("system") }
            assertEquals("offered", db.offer())
            assertEquals(1, db.pages)
            assertEquals(100, db.rowsRead)
        }
    }

    @Test fun longSameTimestampInvalidPrefixUsesBoundedPagesAndIndexedSeeks() = runBlocking {
        HistoryDb().use { db ->
            repeat(62000) { db.message("system") }
            repeat(20) { db.turn() }
            var vmSteps = 0
            // 对同秒62000条断点限制SQL指令量，防止OFFSET/重扫前缀退化成平方级。
            ProgressHandler.setHandler(db.connection, 1000, object : ProgressHandler() {
                override fun progress(): Int { vmSteps += 1000; return if (vmSteps > 20_000_000) 1 else 0 }
            })
            try {
                assertEquals("offered", db.offer())
                assertEquals(621, db.pages)
                assertEquals(62040, db.rowsRead)
                assertEquals(100, db.largestPage)
                println("62040同秒消息分页：${db.pages}页，单页最多${db.largestPage}条，SQL指令约$vmSteps")
            } finally { ProgressHandler.clearHandler(db.connection) }
        }
    }

    @Test fun selectionAlsoFindsValidExamplesBeyondFirstHundred() = runBlocking {
        HistoryDb().use { db ->
            repeat(100) { db.message("system") }
            val ids = listOf(db.turn(), db.turn())
            assertEquals(ids, db.history().candidates().map { it.id })
        }
    }

    @Test fun correctionImmediatelyAfterPageBoundaryStillRejectsCandidate() = runBlocking {
        HistoryDb().use { db ->
            repeat(98) { db.message("system") }
            val corrected = db.turn()
            db.message("user", "不是，我说的是明天的晚饭安排")
            val replacement = db.message("assistant")
            val other = db.turn()
            val ids = db.history().candidates().map { it.id }
            assertFalse(ids.contains(corrected))
            assertEquals(setOf(replacement, other), ids.toSet())
        }
    }

    @Test fun selectionRetainsOnlyBoundedCandidatePoolAndStopsReading() = runBlocking {
        HistoryDb().use { db ->
            repeat(2500) { db.turn() }
            assertEquals(15, db.history().candidates().size)
            assertEquals(2, db.pages)
            assertEquals(200, db.rowsRead)
        }
    }

    @Test fun selectionRecheckExcludesDeletedSourceAfterInitialRead() = runBlocking {
        HistoryDb().use { db ->
            val removed = db.turn(); db.turn(); db.turn()
            assertTrue(db.history().candidates().any { it.id == removed })
            db.connection.prepareStatement("UPDATE local_messages SET deleted=1 WHERE id=?").use { s ->
                s.setString(1, removed); s.executeUpdate()
            }
            assertFalse(db.history().candidates().any { it.id == removed })
        }
    }

    @Test fun paginationPlanUsesExistingIndexForBothRangesWithoutSortingWholeHistory() {
        HistoryDb().use { db ->
            val details = db.connection.prepareStatement("EXPLAIN QUERY PLAN " + CompanionExamplesSql.HISTORY_PAGE).use { s ->
                s.setString(1, "new"); s.setString(2, "2026-10-17"); s.setLong(3, 100); s.setInt(4, 100)
                s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString("detail")) } }
            }
            assertEquals(details.toString(), 2, details.count { it.contains("USING INDEX index_local_messages_session_id_created_at") })
            assertFalse(details.toString(), details.any { it.contains("TEMP B-TREE") || it.contains("SCAN local_messages") })
        }
    }
}
