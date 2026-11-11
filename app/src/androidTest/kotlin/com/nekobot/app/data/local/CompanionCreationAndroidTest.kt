package com.nekobot.app.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionCreationAndroidTest {
    @Test fun ordinarySessionChoicesExcludeOwnedAndLegacyCompanionCharacters() = runBlocking {
        val db = database()
        try {
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            val ids = listOf("fresh", "ordinary", "owned", "deleted", "legacy")
            ids.forEach { id ->
                db.characterDao().upsert(LocalCharacterEntity(id, "同名角色", createdAt = "now", updatedAt = "now"))
            }
            db.sessionDao().upsert(session("ordinary-chat").copy(characterId = "ordinary"))
            for (id in listOf("owned", "deleted", "legacy")) {
                db.sessionDao().upsert(session("$id-chat", "agent").copy(
                    characterId = id, companionMode = true, inheritCharacter = true, longConversationEnabled = true
                ))
            }
            db.sessionDao().deleteById("deleted-chat")
            db.openHelper.writableDatabase.execSQL("DELETE FROM companion_owners WHERE characterId='legacy'")

            val repo = LocalRepository(db, appContext = ApplicationProvider.getApplicationContext())
            assertEquals(ids.toSet(), repo.listCharacters().map { it.id }.toSet())
            // 删除窗口即释放归属：该角色回到可选状态，不会被永久排除在新建入口之外。
            assertEquals(setOf("fresh", "ordinary", "deleted"), repo.listCharactersForSessionCreation().map { it.id }.toSet())
        } finally { db.close() }
    }

    @Test fun profileFindsItsOwnWindowAndNeverTreatsReservedOwnershipAsOrdinary() = runBlocking {
        val db = database()
        try {
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            db.characterDao().upsert(LocalCharacterEntity("c", "test", createdAt = "now", updatedAt = "now"))
            db.characterDao().upsert(LocalCharacterEntity("other", "test", createdAt = "now", updatedAt = "now"))
            val repo = LocalRepository(db, appContext = ApplicationProvider.getApplicationContext())
            assertNull(repo.companionSessionForCharacter("c"))
            db.sessionDao().upsert(session("main", "agent").copy(companionMode = true, inheritCharacter = true, longConversationEnabled = true))
            assertEquals("main", repo.companionSessionForCharacter("c"))
            assertNull(repo.companionSessionForCharacter("other")) // 同名角色不能共用状态。
            db.openHelper.writableDatabase.execSQL("DELETE FROM companion_owners WHERE characterId='c'")
            assertEquals("main", repo.companionSessionForCharacter("c")) // 兼容旧资料的归属标记缺失。
            db.sessionDao().upsert(requireNotNull(db.sessionDao().getById("main")))
            db.sessionDao().deleteById("main")
            // 删除窗口后归属悬空：资料页必须仍能打开，按未绑定处理而不是抛错。
            assertNull(repo.companionSessionForCharacter("c"))
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java
    ).build()
    private fun session(id: String, mode: String = "character") = LocalSessionEntity(
        id, id, characterId = "c", sessionMode = mode, createdAt = "now", updatedAt = "now"
    )

    @Test fun missingEmptyAndUsedCharactersHaveDistinctActions() = runBlocking {
        val db = database()
        try {
            val check = CompanionCreation(db)
            assertEquals(CompanionEligibility.MissingCharacter, check.eligibility("missing"))
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", createdAt = "now", updatedAt = "now"))
            assertEquals(CompanionEligibility.Available, check.eligibility("c"))
            db.sessionDao().upsert(session("empty"))
            val used = check.eligibility("c") as CompanionEligibility.Used
            assertEquals(listOf("empty"), used.candidates.map { it.id })
            // 页面检查后出现新会话，提交事务仍会拒绝，且不暴露 SQLite 文本。
            try {
                db.withTransaction { check.requireNew("c"); fail("Must recheck at submission") }
            } catch (e: CompanionCreationException) {
                assertEquals(com.nekobot.app.R.string.companion_create_used, e.messageRes)
            }
            assertEquals(1, db.sessionDao().count())
        } finally { db.close() }
    }

    @Test fun groupUseBlocksCreationButCannotBeTransferred() = runBlocking {
        val db = database()
        try {
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", createdAt = "now", updatedAt = "now"))
            db.sessionDao().upsert(session("group", "group").copy(characterId = null, characterIds = "[\"c\"]"))
            assertTrue((CompanionCreation(db).eligibility("c") as CompanionEligibility.Used).candidates.isEmpty())
            db.sessionDao().upsert(session("agent", "agent").copy(inheritCharacter = true))
            assertEquals(listOf("agent"), (CompanionCreation(db).eligibility("c") as CompanionEligibility.Used).candidates.map { it.id })
        } finally { db.close() }
    }

    @Test fun deletingWindowReleasesOwnershipSoTheCharacterCanStartOver() = runBlocking {
        val db = database()
        try {
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", createdAt = "now", updatedAt = "now"))
            db.sessionDao().upsert(session("main", "agent").copy(companionMode = true, inheritCharacter = true, longConversationEnabled = true))
            assertEquals(CompanionEligibility.Existing("main"), CompanionCreation(db).eligibility("c"))
            db.sessionDao().deleteById("main")
            // 归属随窗口一起释放：角色回到普通角色卡，可重新建立窗口。
            assertNull(db.companionMemoryDao().owner("c"))
            assertEquals(CompanionEligibility.Available, CompanionCreation(db).eligibility("c"))
            val repo = LocalRepository(db, appContext = ApplicationProvider.getApplicationContext())
            val again = repo.createSession(com.nekobot.app.data.model.CreateSessionRequest(
                characterId = "c", sessionMode = "agent", companionMode = true))
            assertEquals(CompanionEligibility.Existing(requireNotNull(again.id)), CompanionCreation(db).eligibility("c"))
        } finally { db.close() }
    }

    @Test fun freshCompanionUsesCardStateAndInsertsOneGreetingWhileOrdinaryAgentStaysUnchanged() = runBlocking {
        val db = database()
        try {
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", state = "{\"affection\":72}", firstMessage = "角色卡开场白", createdAt = "now", updatedAt = "now"))
            val repo = LocalRepository(db, appContext = ApplicationProvider.getApplicationContext())
            val created = repo.createSession(com.nekobot.app.data.model.CreateSessionRequest(characterId = "c", sessionMode = "agent", companionMode = true, firstMessage = "自定义开场白"))
            assertEquals(listOf("自定义开场白"), db.messageDao().listBySession(requireNotNull(created.id)).map { it.content })
            val relationship = db.relationshipDao().get("c", "session:${created.id}")!!
            assertEquals(72, com.nekobot.app.data.local.ai.RelationshipState.fromJson(relationship.dataJson).affection)
            db.characterDao().upsert(LocalCharacterEntity("ordinary", "普通", firstMessage = "普通开场白", createdAt = "now", updatedAt = "now"))
            val agent = repo.createSession(com.nekobot.app.data.model.CreateSessionRequest(characterId = "ordinary", sessionMode = "agent"))
            assertTrue(db.messageDao().listBySession(requireNotNull(agent.id)).isEmpty())
        } finally { db.close() }
    }
}
