package com.nekobot.app.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperienceSourceReaderTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
    private fun message(id: String, session: String = "s", role: String = "assistant") = LocalMessageEntity(
        id = id, sessionId = session, role = role, content = "原话 $id",
        timestamp = "2026-10-02 18:02:00", createdAt = "2026-10-02 18:02:00"
    )
    private suspend fun session(db: NekobotDatabase, id: String = "s") {
        db.sessionDao().upsert(LocalSessionEntity(id = id, name = "原会话 $id", createdAt = "now", updatedAt = "now"))
    }
    private suspend fun archive(db: NekobotDatabase, ids: List<String>) {
        db.experienceArchiveDao().saveGenerated(LocalExperienceArchiveEntity(
            id = "episode", sessionId = "s", startMessageId = ids.first(), endMessageId = ids.last(),
            sourceStartedAt = "2026-10-02 18:02:00", sourceEndedAt = "2026-10-02 18:02:00",
            summary = "经历摘要", sourceFingerprint = "fingerprint", createdAt = "now", updatedAt = "now"
        ), ids)
    }

    @Test fun refreshingLoadedSourcesRevalidatesTextMembershipAndOwnershipBeyondSqliteBindLimit() = runBlocking {
        val db = database()
        try {
            session(db); session(db, "other")
            val rows = (1..1300).map { message("m$it") }
            db.messageDao().upsertAll(rows)
            archive(db, rows.map { it.id })
            val reader = ExperienceSourceReader(db)
            val loaded = reader.sourcePage("s", "episode")!!.copy(messages = rows, hasMore = false)
            db.messageDao().updateContent("m1250", "修改后的原话")
            db.messageDao().updateDeleted("m200", true)
            db.messageDao().upsert(message("m400", "other"))
            db.experienceArchiveDao().replaceSources("episode", rows.map { it.id }.filter { it != "m300" })
            val fresh = reader.refreshSource(loaded)!!
            assertEquals((1..1300).filter { it !in setOf(200, 300, 400) }.map { "m$it" }, fresh.messages.map { it.id })
            assertEquals("修改后的原话", fresh.messages.single { it.id == "m1250" }.content)
            assertEquals(1299, fresh.sourceCount)
            assertEquals(1297, fresh.availableCount)
            assertFalse(fresh.hasMore)
            assertEquals(60, reader.sourcePage("s", "episode")!!.messages.size)
        } finally { db.close() }
    }

    @Test fun refreshingSourcesKeepsPaginationUsableAfterAllLoadedOriginalsDisappear() = runBlocking {
        val db = database()
        try {
            session(db)
            val rows = (1..130).map { message("m$it") }
            db.messageDao().upsertAll(rows)
            archive(db, rows.map { it.id })
            val reader = ExperienceSourceReader(db)
            val loaded = reader.sourcePage("s", "episode")!!
            db.experienceArchiveDao().replaceSources("episode", (61..130).map { "m$it" })
            val remapped = reader.refreshSource(loaded)!!
            assertEquals((61..120).map { "m$it" }, remapped.messages.map { it.id })
            assertEquals(70, remapped.availableCount)
            assertTrue(remapped.hasMore)
            db.withTransaction { (61..120).forEach { db.messageDao().updateDeleted("m$it", true) } }
            val deleted = reader.refreshSource(remapped)!!
            assertEquals((121..130).map { "m$it" }, deleted.messages.map { it.id })
            assertEquals(10, deleted.availableCount)
            assertFalse(deleted.hasMore)
            db.withTransaction { (121..130).forEach { db.messageDao().updateDeleted("m$it", true) } }
            val empty = reader.refreshSource(deleted)!!
            assertTrue(empty.messages.isEmpty())
            assertEquals(0, empty.availableCount)
            assertFalse(empty.hasMore)
        } finally { db.close() }
    }

    @Test fun refreshingHistoryKeepsBrowsedRangeAndDropsHiddenRowsAndChangedHighlights() = runBlocking {
        val db = database()
        try {
            session(db)
            val rows = (1..250).map { message("m$it") }
            db.messageDao().upsertAll(rows)
            archive(db, rows.map { it.id })
            val reader = ExperienceSourceReader(db)
            val browsed = reader.historyWindow("s", "m30", "episode")!!.copy(messages = rows.subList(119, 200))
            db.messageDao().updateContent("m180", "已编辑的历史原话")
            db.messageDao().updateDeleted("m150", true)
            db.experienceArchiveDao().replaceSources("episode", rows.map { it.id }.filter { it != "m190" })
            val fresh = reader.refreshHistory(browsed)!!
            assertEquals((120..200).filter { it != 150 }.map { "m$it" }, fresh.messages.map { it.id })
            assertEquals("已编辑的历史原话", fresh.messages.single { it.id == "m180" }.content)
            assertTrue(fresh.hasOlder && fresh.hasNewer)
            assertFalse("m190" in fresh.sourceMessageIds)
            assertTrue(fresh.messages.any { it.id == "m190" })
            db.messageDao().updateDeleted("m30", true)
            assertNull(reader.refreshHistory(fresh))
        } finally { db.close() }
    }

    @Test fun readerObservesEditsDeletesAndSourceMappingChangesWithoutReadingAllMessages() = runBlocking {
        val db = database()
        try {
            session(db)
            db.messageDao().upsertAll(listOf(message("a"), message("b")))
            archive(db, listOf("a", "b"))
            val signals = AtomicInteger()
            val watcher = launch { ExperienceSourceReader(db).changes().collect { signals.incrementAndGet() } }
            suspend fun awaitSignals(count: Int) = withTimeout(5000) { while (signals.get() < count) delay(10) }
            awaitSignals(1)
            var previous = signals.get()
            db.messageDao().updateContent("a", "编辑")
            awaitSignals(previous + 1)
            previous = signals.get()
            db.messageDao().updateDeleted("b", true)
            awaitSignals(previous + 1)
            previous = signals.get()
            db.experienceArchiveDao().replaceSources("episode", listOf("a"))
            awaitSignals(previous + 1)
            watcher.cancelAndJoin()
        } finally { db.close() }
    }

    @Test fun sourcesUseExactMembershipAndDoNotDiscloseOtherSessionsOrHiddenRows() = runBlocking {
        val db = database()
        try {
            session(db); session(db, "other")
            db.messageDao().upsertAll(listOf(message("u", role = "user"), message("gap"), message("a"),
                message("foreign", "other"), message("system", role = "system"), message("deleted").copy(deleted = true)))
            archive(db, listOf("u", "foreign", "system", "deleted", "missing", "a"))
            val reader = ExperienceSourceReader(db)
            val page = reader.sourcePage("s", "episode")!!
            assertEquals(listOf("u", "a"), page.messages.map { it.id })
            assertEquals(6, page.sourceCount)
            assertEquals(2, page.availableCount)
            assertFalse(page.hasMore)
            assertNull(reader.sourcePage("other", "episode"))
            assertNull(reader.sourcePage("s", "episode", "foreign"))
            assertEquals(0, db.experienceArchiveDao().availableSourceCount("other", "episode"))
        } finally { db.close() }
    }

    @Test fun sameTimestampPagesKeepAllAssistantBubblesInOriginalOrder() = runBlocking {
        val db = database()
        try {
            session(db)
            val rows = (1..145).map { message("m$it", role = if (it == 1) "user" else "assistant") }
            db.messageDao().upsertAll(rows)
            val ids = rows.map { it.id }.filter { it != "m70" }
            archive(db, ids)
            val reader = ExperienceSourceReader(db)
            val result = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = reader.sourcePage("s", "episode", cursor, 17)!!
                result += page.messages.map { it.id }
                cursor = page.messages.lastOrNull()?.id
                if (!page.hasMore) break
            } while (true)
            assertEquals(ids, result)
            assertEquals(result.size, result.distinct().size)
            assertEquals(144, db.experienceArchiveDao().sourceCount("episode"))
        } finally { db.close() }
    }

    @Test fun deletedPageCursorStillAdvancesAndEditsAreShownWithStaleStatus() = runBlocking {
        val db = database()
        try {
            session(db)
            db.messageDao().upsertAll((1..5).map { message("m$it") })
            archive(db, (1..5).map { "m$it" })
            val reader = ExperienceSourceReader(db)
            val first = reader.sourcePage("s", "episode", pageSize = 2)!!
            assertEquals(listOf("m1", "m2"), first.messages.map { it.id })
            db.messageDao().updateDeleted("m2", true)
            db.messageDao().updateContent("m3", "用户修改后的当前原话")
            db.experienceArchiveDao().markStaleByMessage("s", "m3")
            val next = reader.sourcePage("s", "episode", "m2", 2)!!
            assertEquals(listOf("m3", "m4"), next.messages.map { it.id })
            assertEquals("用户修改后的当前原话", next.messages.first().content)
            assertEquals("stale", next.archive.status)
            assertEquals(4, next.availableCount)
            db.messageDao().deleteById("m4")
            assertEquals(3, reader.sourcePage("s", "episode")!!.availableCount)
            assertNull(reader.sourcePage("s", "episode", "m4"))
        } finally { db.close() }
    }

    @Test fun historyAnchorIsStrictlyScopedAndDeletedTargetsCannotBeLocated() = runBlocking {
        val db = database()
        try {
            session(db); session(db, "other")
            db.messageDao().upsertAll(listOf(message("u", role = "user"), message("foreign", "other"), message("system", role = "system")))
            val reader = ExperienceSourceReader(db)
            assertNull(reader.historyWindow("s", "foreign"))
            assertNull(reader.historyWindow("s", "system"))
            assertNull(reader.historyPage("s", "foreign", true))
            assertEquals(listOf("u"), reader.historyWindow("s", "u")!!.messages.map { it.id })
            db.messageDao().updateDeleted("u", true)
            assertNull(reader.historyWindow("s", "u"))
            db.sessionDao().deleteById("s")
            assertNull(reader.historyWindow("s", "u"))
        } finally { db.close() }
    }

    @Test fun historyCanPageBothDirectionsWithoutGapsAtIdenticalTimestamps() = runBlocking {
        val db = database()
        try {
            session(db)
            val rows = (1..250).map { message("m$it") }
            db.messageDao().upsertAll(rows)
            val reader = ExperienceSourceReader(db)
            val middle = reader.historyWindow("s", "m125")!!
            assertEquals((65..185).map { "m$it" }, middle.messages.map { it.id })
            assertTrue(middle.hasOlder); assertTrue(middle.hasNewer)
            val before = reader.historyPage("s", "m65", true)!!
            val after = reader.historyPage("s", "m185", false)!!
            assertEquals((5..64).map { "m$it" }, before.messages.map { it.id })
            assertEquals((186..245).map { "m$it" }, after.messages.map { it.id })
            assertTrue(before.hasMore); assertTrue(after.hasMore)
            assertEquals((1..4).map { "m$it" }, reader.historyPage("s", "m5", true)!!.messages.map { it.id })
            assertFalse(reader.historyPage("s", "m245", false)!!.hasMore)
        } finally { db.close() }
    }

    @Test fun sixtyTwoThousandMessagesAreReadInBoundedWindowsWithoutChangingData() = runBlocking {
        val db = database()
        try {
            session(db)
            for (start in 1..62_000 step 500) {
                db.messageDao().upsertAll((start..minOf(start + 499, 62_000)).map { message("m$it") })
            }
            archive(db, (61_974..62_000).map { "m$it" })
            val reader = ExperienceSourceReader(db)
            val sources = reader.sourcePage("s", "episode")!!
            assertEquals(27, sources.messages.size)
            assertEquals("m61974", sources.messages.first().id)
            val old = reader.historyWindow("s", "m500")!!
            assertEquals(121, old.messages.size)
            assertEquals("m440", old.messages.first().id)
            assertEquals("m560", old.messages.last().id)
            assertEquals(62_000, db.messageDao().countVisibleFinalBySession("s"))
            assertEquals("ready", db.experienceArchiveDao().getById("episode")!!.status)
        } finally { db.close() }
    }

    @Test fun importedSessionsReadTheirOwnRoleNamesWithoutRewritingOriginalSender() = runBlocking {
        val db = database()
        try {
            session(db)
            db.messageDao().upsert(message("a"))
            archive(db, listOf("a"))
            val base = db.sessionDao().getById("s")!!
            val reader = ExperienceSourceReader(db)
            db.characterDao().upsert(LocalCharacterEntity(id = "c", name = "苏雨", createdAt = "now", updatedAt = "now"))
            db.sessionDao().update(base.copy(characterId = "c", characterName = " ", senderName = "旧称呼"))
            assertEquals("苏雨", reader.sourcePage("s", "episode")!!.assistantName)
            assertEquals("苏雨", reader.historyWindow("s", "a")!!.assistantName)
            db.sessionDao().update(base.copy(characterId = "c", characterName = "林夜"))
            assertEquals("林夜", reader.historyWindow("s", "a")!!.assistantName)
            db.sessionDao().update(base.copy(senderName = "另一个角色"))
            assertEquals("另一个角色", reader.sourcePage("s", "episode")!!.assistantName)
            db.sessionDao().update(base)
            assertNull(reader.historyWindow("s", "a")!!.assistantName)
            assertNull(db.messageDao().getById("a")!!.sender)
            assertEquals("assistant", db.messageDao().getById("a")!!.role)
            assertFalse(db.sessionDao().getById("s")!!.inheritCharacter)
        } finally { db.close() }
    }

    @Test fun segmentHighlightUsesExactSourcesInEachBoundedPageAndRejectsForeignArchive() = runBlocking {
        val db = database()
        try {
            session(db); session(db, "other")
            db.messageDao().upsertAll((1..250).map { message("m$it") } + message("foreign", "other"))
            val ids = (65..240).filter { it != 150 }.map { "m$it" }
            archive(db, ids + "foreign")
            val reader = ExperienceSourceReader(db)
            val window = reader.historyWindow("s", "m125", "episode")!!
            assertEquals("episode", window.archiveId)
            assertEquals((65..185).filter { it != 150 }.map { "m$it" }.toSet(), window.sourceMessageIds)
            assertTrue(window.messages.any { it.id == "m150" })
            assertFalse("m150" in window.sourceMessageIds)
            assertNull(reader.historyWindow("s", "m150", "episode"))
            assertNull(reader.historyWindow("other", "foreign", "episode"))
            assertNull(reader.historyPage("other", "foreign", false, "episode"))
            val next = reader.historyPage("s", "m185", false, "episode")!!
            assertEquals((186..240).map { "m$it" }.toSet(), next.sourceMessageIds)
            assertEquals(60, next.messages.size)
            db.messageDao().updateDeleted("m200", true)
            assertFalse("m200" in reader.historyPage("s", "m185", false, "episode")!!.sourceMessageIds)
            assertEquals(1, db.experienceArchiveDao().listBySession("s", 10).size)
        } finally { db.close() }
    }
}
