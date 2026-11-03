package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CompanionImportEvidenceAndroidTest {
    @Test fun verifiedImportedMemoryRequiresUnchangedSourceAndKeepsCoreFlag() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
        try {
            val stamp = "2026-01-01T00:00:00Z"
            val session = LocalSessionEntity("source-session", "合成来源测试", characterId = "character", sessionMode = "agent",
                inheritCharacter = true, longConversationEnabled = true, companionMode = true, createdAt = stamp, updatedAt = stamp)
            db.sessionDao().upsert(session)
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(session)
            val message = LocalMessageEntity("source-message", session.id, "user", "合成原话：我喜欢茉莉茶。", timestamp = stamp, createdAt = stamp)
            db.messageDao().upsert(message)
            val memory = CompanionMemoryEntity("verified-memory", scope.id, "tea", 1, "fact", "玩家", "喜欢茉莉茶", "active",
                alwaysProvide = true, provenance = CompanionImportPolicy.IMPORTED_VERIFIED, createdAt = stamp, updatedAt = stamp)
            db.companionMemoryDao().saveMemory(memory)
            assertTrue(store.validatedMemories(store.ensureScope(session)).isEmpty())
            db.companionMemoryDao().saveSources(listOf(CompanionSourceEntity(memory.id, message.id,
                CompanionMemoryPolicy.fingerprint(message), "我喜欢茉莉茶", "2026-01-01", "user")))
            assertEquals(listOf(memory), store.validatedMemories(store.ensureScope(session)))
            assertTrue(CompanionCoreMemory.contains(memory))
            db.messageDao().upsert(message.copy(content = "后来修改了合成原话。"))
            assertTrue(store.validatedMemories(store.ensureScope(session)).isEmpty())
            assertTrue(requireNotNull(db.companionMemoryDao().versions(scope.id, "tea").singleOrNull()).alwaysProvide)
        } finally { db.close() }
    }
    @Test fun unverifiedTextIsIndependentLabeledAndExpiryRetiresCachedContext() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
        try {
            val session = LocalSessionEntity("s", "角色", characterId = "c", sessionMode = "agent",
                inheritCharacter = true, longConversationEnabled = true, companionMode = true, createdAt = "now", updatedAt = "now")
            db.sessionDao().upsert(session)
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(session)
            val memory = CompanionMemoryEntity("m", scope.id, "diet", 1, "fact", "玩家", "独立保存的饮食偏好", "active",
                alwaysProvide = true, provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED, createdAt = "old", updatedAt = "old")
            db.companionMemoryDao().saveMemory(memory)
            assertEquals(listOf(memory), store.validatedMemories(scope))
            assertTrue(db.companionMemoryDao().sources("m").isEmpty())
            val first = LocalMessageEntity("u1", "s", "user", "你好", timestamp = "2026-10-09T00:00:00Z", createdAt = "2026-10-09T00:00:00Z")
            db.messageDao().upsert(first)
            val context = FusedCompanionContext(db)
            val initial = context.build(session, "u1", emptyList(), "test", emptyList(), mapOf("content" to "你好"), "", 24000, 1000)
            assertTrue(initial.toString().contains("原话来源未验证"))
            // 有效期经过无需原话编辑或 revision 变化；旧辅助缓存也不能继续携带旧正文。
            db.companionMemoryDao().saveMemory(memory.copy(expiresAt = Instant.now().minusSeconds(1).toString()))
            val second = first.copy(id = "u2", createdAt = "2026-10-09T01:00:00Z")
            db.messageDao().upsert(second)
            val rebuilt = context.build(session, "u2", listOf(first), "test", emptyList(), mapOf("content" to "继续"), "", 24000, 1000)
            assertFalse(rebuilt.toString().contains(memory.content))
            assertTrue(store.validatedMemories(scope).isEmpty())
            assertNotNull(db.companionMemoryDao().versions(scope.id, "diet").single().expiresAt)
        } finally { db.close() }
    }
}
