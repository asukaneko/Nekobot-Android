package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.LocalAgentRecallReader
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class UnifiedCompanionContextAndroidTest {
    @Test fun retainedIdsFollowSelectedOwnersAndOnlyCurrentVersionReachesEveryMemoryEntryPoint() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            db.characterDao().upsert(LocalCharacterEntity("c", "林小满测试", createdAt = "now", updatedAt = "now"))
            val session = LocalSessionEntity("s", "隔离测试", characterId = "c", sessionMode = "agent",
                inheritCharacter = true, longConversationEnabled = true, companionMode = true, createdAt = "now", updatedAt = "now")
            db.sessionDao().upsert(session)
            val store = CompanionMemoryStore(db)
            store.editFused(session, "tea", "我记得他喝茶不加糖", "user_profile", emptyList(), false, false)
            val old = store.validatedMemories(store.ensureScope(session)).single()
            suspend fun say(id: String, content: String, role: String = "user"): LocalMessageEntity {
                val row = LocalMessageEntity(id, "s", role, content, createdAt = "2026-10-21T10:00:00+08:00", timestamp = "2026-10-21T10:00:00+08:00")
                db.messageDao().upsert(row)
                return row
            }
            val now = ZonedDateTime.parse("2026-10-21T10:10:00+08:00[Asia/Shanghai]")
            suspend fun build(row: LocalMessageEntity, history: List<LocalMessageEntity>, dynamic: String = "") =
                FusedCompanionContext(db).build(session, row.id, history, "固定设定", emptyList(),
                    mapOf("role" to "user", "content" to row.content), dynamic, 32000, 4000, now)
            val first = say("35", "喝茶")
            val original = build(first, emptyList(), "正在书店上班")
            assertEquals(setOf(old.id), CompanionReferences.visibleIds(original))
            val answer = say("a35", "嗯嗯", "assistant")
            val next = say("80", "xyzzy-unrelated")
            val retained = build(next, listOf(first, answer), "今天休息")
            assertEquals(setOf(old.id), CompanionReferences.visibleIds(retained))
            assertEquals(1, retained.count { it.containsKey(CompanionReferences.META) })
            assertTrue(retained.any { it["content"].toString().contains("今天休息") })
            assertFalse(retained.any { it["content"].toString().contains("正在书店上班") })
            val nextCache = db.companionMemoryDao().contextMaterials(store.ensureScope(session).id).single { it.messageId == next.id }
            assertEquals(emptyList<String>(), CompanionReferences.ids(nextCache.content))
            val dropped = build(next, emptyList())
            assertTrue(CompanionReferences.visibleIds(dropped).isEmpty())
            val again = say("81", "喝茶")
            assertEquals(setOf(old.id), CompanionReferences.visibleIds(build(again, listOf(next))))
            store.editFused(session, "tea", "我记得他现在喝茶加糖", "user_profile", emptyList(), false, false)
            val scope = store.ensureScope(session)
            val current = store.validatedMemories(scope).single { it.current }
            assertNotEquals(old.id, current.id)
            assertFalse(db.companionMemoryDao().versions(scope.id, "tea").single { it.id == old.id }.current)
            val updated = build(again, listOf(first, answer, next))
            assertEquals(setOf(current.id), CompanionReferences.visibleIds(updated))
            assertFalse(updated.any { it["content"].toString().contains(old.content) })
            val tool = store.fusedTool(session, "agent_memory_read", mapOf("query" to "喝茶"),
                FusedMemoryReferences(listOf(again), listOf(current)))
            assertFalse(tool.toString().contains(old.content))
            val reader = LocalAgentRecallReader(db)
            val search = AgentRecallToolHandler(session.id, reader, currentOnly = true)
                .execute("agent_recall_search", mapOf("query" to "喝茶", "include_history" to true))
            assertFalse(search.toString().contains(old.content))
            assertTrue(search.toString().contains(current.content))
            assertEquals(first.content, db.messageDao().getById(first.id)?.content)
        } finally {
            db.close()
        }
    }
}
