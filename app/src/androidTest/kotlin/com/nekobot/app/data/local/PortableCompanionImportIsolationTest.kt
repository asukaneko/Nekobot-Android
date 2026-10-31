package com.nekobot.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PortableCompanionImportIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profile = "portable-isolation-${UUID.randomUUID()}"
    private lateinit var previousProfile: String
    private lateinit var manager: PortableDataArchiveManager
    private fun db() = NekobotDatabase.get(context, profile)

    @Before fun setUp() {
        previousProfile = ServiceContainer.prefs.activeDbName
        ServiceContainer.switchLocalDb(profile)
        manager = PortableDataArchiveManager(context)
    }

    @After fun tearDown() {
        ServiceContainer.switchLocalDb(previousProfile)
        ServiceContainer.prefs.removeDbProfile(profile)
        NekobotDatabase.deleteProfileFile(context, profile)
    }

    private suspend fun session(id: String) {
        db().characterDao().upsert(LocalCharacterEntity("c-$id", id, createdAt = "old", updatedAt = "old"))
        db().sessionDao().upsert(LocalSessionEntity(id, id, characterId = "c-$id", sessionMode = "agent",
            inheritCharacter = true, longConversationEnabled = true, companionMode = true,
            createdAt = "old", updatedAt = "old"))
        db().messageDao().upsert(LocalMessageEntity("$id-source", id, "user", "我们按时去过读书会。",
            timestamp = "2026-10-01T01:00:00Z", createdAt = "2026-10-01T01:00:00Z"))
    }

    private suspend fun relationship(id: String): CompanionRelationshipEntity {
        val state = CompanionRelationshipEntity(id, "c-$id", version = 17, enabled = true, automatic = true,
            interval = 7, modelId = "unconfigured-fixture-model", cursorId = "$id-source",
            baselineJson = "{\"trust\":64}", mood = "轻松", energy = "精神不错", feelingReason = "刚聊过读书会",
            feelingSources = "[\"$id-source\"]", updatedAt = 12345, notice = "原有提示",
            moodIntensity = 0.2f, narrativeJson = "{\"trust\":\"愿意分享日常\"}",
            legacySnapshotJson = "{\"origin\":\"旧版记录\"}", historyScopeVersion = 1)
        db().companionRelationshipDao().save(state)
        db().companionRelationshipDao().appendAssessment(CompanionRelationshipAssessmentEntity(
            "$id-assessment", id, 17, requireNotNull(state.narrativeJson), 12345))
        db().companionRelationshipDao().append(listOf(CompanionRelationshipEventEntity(
            "$id-event", id, "reading", 17, "一起读书", "如约参加", "reliability", "{}", "[]", 12345)))
        return state
    }

    private suspend fun scope(id: String): CompanionScopeEntity {
        val cursor = requireNotNull(db().messageDao().cursorOf("$id-source")).rowId
        val value = CompanionScopeEntity("scope-$id", db().dbName, id, "c-$id", baselineRowId = cursor,
            archiveRowId = cursor, archiveMessageId = "$id-source", historyAccess = true, revision = 29,
            error = "原有整理提示", updatedAt = "old")
        db().companionMemoryDao().saveScope(value)
        db().companionMemoryDao().saveContextMaterial(CompanionContextMaterialEntity(value.id,
            "$id-source", cursor, value.revision, "fixture-configuration", "$id-source", "已有辅助上下文",
            "2026-10-01 09:00", 12345, "关键词", "{}"))
        return value
    }

    private suspend fun archive(): ByteArray = ByteArrayOutputStream().use { output ->
        manager.export(setOf(PortableDataCategory.CONVERSATIONS), "", output, "test")
        output.toByteArray()
    }

    @Test fun importingOneConversationKeepsUnrelatedRelationshipsAndMemoryProgress() = runBlocking {
        session("imported")
        val importedState = relationship("imported")
        val importedScope = scope("imported")
        val backup = archive()
        // Created after export: neither relationship nor memory scope is present in this archive.
        val foreign = listOf("shen", "test").map { id ->
            session(id)
            Triple(id, relationship(id), scope(id))
        }
        val assessments = foreign.associate { it.first to db().companionRelationshipDao().assessments(it.first) }
        val events = foreign.associate { it.first to db().companionRelationshipDao().currentEvents(it.first) }
        val contexts = foreign.associate { it.first to db().companionMemoryDao().contextMaterials(it.third.id) }
        repeat(2) {
            manager.import(ByteArrayInputStream(backup), "", setOf(PortableDataCategory.CONVERSATIONS))
            // An identical import is now a true no-op, including the selected owner's state and cursor.
            assertEquals(importedState, db().companionRelationshipDao().state("imported"))
            assertEquals(importedScope, db().companionMemoryDao().scope(importedScope.id))
            for ((id, state, memoryScope) in foreign) {
                assertEquals("未导入角色的关系状态被改动：$id", state, db().companionRelationshipDao().state(id))
                assertEquals("未导入角色的记忆进度被改动：$id", memoryScope, db().companionMemoryDao().scope(memoryScope.id))
                assertEquals(assessments[id], db().companionRelationshipDao().assessments(id))
                assertEquals(events[id], db().companionRelationshipDao().currentEvents(id))
                assertEquals(contexts[id], db().companionMemoryDao().contextMaterials(memoryScope.id))
            }
        }
    }

    @Test fun archiveWithoutCompanionRowsKeepsExistingRelationshipAndMemoryProgress() = runBlocking {
        session("imported")
        val backup = archive()
        // An old conversation-only archive has no relationship or independent memory rows to restore.
        val state = relationship("imported")
        val memoryScope = scope("imported")
        manager.import(ByteArrayInputStream(backup), "", setOf(PortableDataCategory.CONVERSATIONS))
        assertEquals(state, db().companionRelationshipDao().state("imported"))
        val restoredScope = requireNotNull(db().companionMemoryDao().scope(memoryScope.id))
        // 本次导入的原话写入可以使同会话缓存失效，但不能清零其既有整理进度。
        assertEquals(memoryScope.copy(revision = restoredScope.revision), restoredScope)
        assertTrue(restoredScope.revision >= memoryScope.revision)
    }
}
