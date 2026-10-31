package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Room persistence; no model, network, phone or user data. */
class CompanionCoreAdvisoryTest {
    @Test fun manualGrowthAndCoreSelectionCanExceedSuggestionAndRetainUserChoice() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
        try {
            val s = setup(db)
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(s)
            repeat(3) { i -> store.editFused(s, "core-$i", "合成重要习惯$i".repeat(100), "user_profile", emptyList(), true, true) }
            var rows = db.companionMemoryDao().memories(scope.id).filter { it.current }
            assertTrue(CompanionCoreMemory.tokens(CompanionCoreMemory.rows(rows)) > CompanionCoreMemory.maintenanceTokens)
            val before = CompanionCoreMemory.tokens(CompanionCoreMemory.rows(rows))
            store.editFused(s, "core-0", "合成重要习惯0".repeat(170), "user_profile", emptyList(), true, true)
            store.editFused(s, "optional", "合成其他长期习惯".repeat(100), "user_profile", emptyList(), false, false)
            store.setCore(s, "optional", true)
            rows = db.companionMemoryDao().memories(scope.id).filter { it.current }
            assertTrue(CompanionCoreMemory.tokens(CompanionCoreMemory.rows(rows)) > before)
            assertEquals(4, CompanionCoreMemory.rows(rows).size)
            assertEquals("合成重要习惯0".repeat(170), rows.single { it.factKey == "core-0" }.content)
            store.setCore(s, "optional", false)
            assertFalse(CompanionCoreMemory.contains(db.companionMemoryDao().memories(scope.id).single { it.current && it.factKey == "optional" }))
        } finally { db.close() }
    }

    @Test fun modelCoreCandidateIsNotSilentlyDemotedWhenSuggestionIsExceeded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
        try {
            val s = setup(db)
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(s)
            repeat(3) { i -> db.companionMemoryDao().saveMemory(CompanionMemoryEntity("existing-$i", scope.id, "existing-$i", 1,
                "fact", "合成用户", "合成长期偏好".repeat(130), "active", alwaysProvide = true, manual = true, createdAt = "now", updatedAt = "now")) }
            val source = LocalMessageEntity("synthetic-new", s.id, "user", "以后一直叫我小舟。", timestamp = "2026-01-01T00:00:00Z", createdAt = "2026-01-01T00:00:00Z")
            db.messageDao().upsert(source)
            val response = Gson().toJson(mapOf("op" to "add", "kind" to "core", "category" to "user_profile",
                "content" to "常用称呼是小舟。", "evidence" to listOf("S1"), "date_type" to "habit")) + "\n{\"done\":true}"
            store.convertBatch(s, listOf(source), emptyList(), emptyList(), 12000, generate = { _, _ -> response })
            val rows = db.companionMemoryDao().memories(scope.id).filter { it.current }
            val added = rows.single { it.content == "常用称呼是小舟。" }
            assertEquals("core", added.kind)
            assertTrue(CompanionCoreMemory.contains(added))
            assertEquals(4, rows.size)
        } finally { db.close() }
    }

    private suspend fun setup(db: NekobotDatabase): LocalSessionEntity {
        FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
        db.characterDao().upsert(LocalCharacterEntity("c", "合成角色", createdAt = "now", updatedAt = "now"))
        return LocalSessionEntity("s", "合成核心", characterId = "c", sessionMode = "agent", companionMode = true,
            inheritCharacter = true, longConversationEnabled = true, createdAt = "now", updatedAt = "now").also { db.sessionDao().upsert(it) }
    }
}
