package com.nekobot.app.data.local.db

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.SessionListStore
import com.nekobot.app.data.local.TemporarySession
import com.nekobot.app.data.model.SessionListQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime

class SessionSummaryAndroidTest {
    private fun database(): NekobotDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), NekobotDatabase::class.java
    ).build()

    private suspend fun withDatabase(block: suspend (NekobotDatabase) -> Unit) {
        val db = database()
        try { block(db) } finally { db.close() }
    }

    @Test fun summariesKeepListFieldsAndNeverLoadPromptSnapshots() = runBlocking {
        withDatabase { db ->
            val activeId = TemporarySession.newId()
            db.sessionDao().upsert(LocalSessionEntity("demo", "Demo", createdAt = "old", updatedAt = "old",
                customPrompts = "[]", promptStackDebug = "x".repeat(32_000),
                composedSystemPrompt = "x".repeat(32_000), characterIds = "[\"demo-character\"]",
                tags = "demo,example", listTitleOverride = "Demo title"))
            db.sessionDao().upsert(LocalSessionEntity("temp_previous", "Old", createdAt = "old", updatedAt = "old"))
            db.sessionDao().upsert(LocalSessionEntity(activeId, "Current", createdAt = "old", updatedAt = "old"))
            val sessions = SessionListStore(db.sessionDao()).summaries()
            assertEquals(setOf("demo", activeId), sessions.map { it.id }.toSet())
            val summary = sessions.first { it.id == "demo" }
            assertNull(summary.customPrompts); assertNull(summary.promptStackDebug); assertNull(summary.composedSystemPrompt)
            assertEquals(listOf("demo-character"), summary.characterIds)
            assertEquals(listOf("demo", "example"), summary.tags)
            assertEquals("Demo title", summary.displayName)
        }
    }

    @Test fun searchMatchesUnicodeCaseAndTextBeyondTheDisplayedPreviewBeforePaging() = runBlocking {
        withDatabase { db ->
            repeat(4) { index ->
                db.sessionDao().upsert(LocalSessionEntity("demo-$index", "Demo", createdAt = "old", updatedAt = "old",
                    lastMessage = "x".repeat(800) + " İSTANBUL"))
            }
            val page = SessionListStore(db.sessionDao()).page(SessionListQuery(search = "istanbul", limit = 2, offset = 1))
            assertEquals(4, page.totalCount); assertTrue(page.hasMore)
            assertEquals(listOf("demo-1", "demo-2"), page.sessions.map { it.id })
        }
    }

    @Test fun largeListQueryRecordsRepeatableSummaryAndFullReadBaselines() = runBlocking {
        withDatabase { db ->
            db.withTransaction {
                repeat(500) { index -> db.sessionDao().upsert(LocalSessionEntity(
                    "demo-%04d".format(index), "Demo $index", createdAt = "old", updatedAt = "old",
                    promptStackDebug = "x".repeat(8_192), composedSystemPrompt = "x".repeat(8_192)
                )) }
            }
            val store = SessionListStore(db.sessionDao())
            store.page(SessionListQuery())
            val pageTimes = LongArray(5) { measureNanoTime { store.page(SessionListQuery()) } }.sorted()
            val fullTimes = LongArray(5) { measureNanoTime { db.sessionDao().listAll() } }.sorted()
            val page = store.page(SessionListQuery())
            assertEquals(80, page.sessions.size); assertEquals(500, page.totalCount); assertTrue(page.hasMore)
            Log.i("NekoPerformance", "session_page_median_ms=${pageTimes[2] / 1_000_000.0}; full_read_median_ms=${fullTimes[2] / 1_000_000.0}")
        }
    }
}
