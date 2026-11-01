package com.nekobot.app.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PortableDataArchiveManagerTest {
    @Test fun relationshipTextHistoryAndLegacyValuesSurvivePortableRestore() = runBlocking {
        var db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val stamp = "2026-10-11T02:00:00Z"
        val characterId = "$mergeSessionId-character"
        db.sessionDao().upsert(LocalSessionEntity(mergeSessionId, "文字关系备份", characterId = characterId, companionMode = true,
            sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true, createdAt = stamp, updatedAt = stamp))
        val words = "{\"description\":\"愿意分享日常，但对承诺仍有保留\"}"
        val oldWords = "{\"origin\":\"旧版记录\"}"
        val state = com.nekobot.app.data.local.db.CompanionRelationshipEntity(mergeSessionId, characterId, version = 20,
            baselineJson = "{\"trust\":50}", moodIntensity = 0.35f, narrativeJson = words, legacySnapshotJson = oldWords)
        db.companionRelationshipDao().save(state)
        val event = com.nekobot.app.data.local.db.CompanionRelationshipEventEntity("$mergeSessionId-event", mergeSessionId, "promise", 20,
            "兑现约定", "按时完成", "reliability", "{}", "[]", 1, impactsJson = "{\"trust\":\"增加了可靠性\"}")
        db.companionRelationshipDao().append(listOf(event))
        val assessment = com.nekobot.app.data.local.db.CompanionRelationshipAssessmentEntity("$mergeSessionId-assessment", mergeSessionId, 20, words, 1)
        db.companionRelationshipDao().appendAssessment(assessment)
        val output = ByteArrayOutputStream()
        manager.export(setOf(PortableDataCategory.CONVERSATIONS), "", output, "test")
        db.sessionDao().deleteById(mergeSessionId)
        manager.import(ByteArrayInputStream(output.toByteArray()), "", setOf(PortableDataCategory.CONVERSATIONS))
        db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val restored = requireNotNull(db.companionRelationshipDao().state(mergeSessionId))
        assertEquals(words, restored.narrativeJson); assertEquals(oldWords, restored.legacySnapshotJson)
        assertEquals(state.baselineJson, restored.baselineJson); assertEquals(state.moodIntensity, restored.moodIntensity)
        assertEquals(listOf(assessment), db.companionRelationshipDao().assessments(mergeSessionId))
        assertEquals(event.impactsJson, db.companionRelationshipDao().currentEvents(mergeSessionId).single().impactsJson)
        // 旧状态合并回来后，历史的最高编号仍可作为下次评估的下限。
        db.companionRelationshipDao().save(restored.copy(version = 2))
        assertEquals(20L, db.companionRelationshipDao().latestRevision(mergeSessionId))
    }

    @Test fun transferredHistoryAndUnverifiedExpirySurvivePortableRestoreWithoutHistoricalReprocessing() = runBlocking {
        var db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val character = "transfer-portable-${UUID.randomUUID()}"
        val source = LocalSessionEntity(mergeSessionId, "迁入备份测试", characterId = character, createdAt = "old", updatedAt = "old")
        var targetId: String? = null
        var targetCharacter: String? = null
        var jobId: String? = null
        try {
            db.characterDao().upsert(com.nekobot.app.data.local.db.LocalCharacterEntity(character, "旧角色", createdAt = "old", updatedAt = "old"))
            db.sessionDao().upsert(source)
            db.messageDao().upsert(LocalMessageEntity("$mergeSessionId-raw", source.id, "user", "原有聊天", timestamp = "2026-01-01", createdAt = "2026-01-01"))
            val memory = com.nekobot.app.data.local.db.LocalCharacterMemoryEntity("$mergeSessionId-fact", character, "local-user", "long", "user_persona", "饮食", "", "喜欢茉莉茶", 5, createdAt = "old")
            db.memoryDao().upsert(memory)
            db.memoryDao().upsert(memory.copy(id = "$mergeSessionId-expired", content = "已经过期的安排", expiresAt = "2000-01-01T00:00:00Z"))
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id); jobId = preview.jobId
            targetId = service.execute(preview.jobId, "迁入副本")
            val target = requireNotNull(db.sessionDao().getById(targetId)); targetCharacter = target.characterId
            val output = ByteArrayOutputStream()
            manager.export(setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS), "", output, "test")
            db.sessionDao().deleteById(target.id)
            db.openHelper.writableDatabase.execSQL("DELETE FROM companion_transfer_jobs WHERE id=?", arrayOf(preview.jobId))
            manager.import(ByteArrayInputStream(output.toByteArray()), "", setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS))
            db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
            assertEquals(null, db.companionTransferDao().get(preview.jobId))
            val restored = requireNotNull(db.sessionDao().getById(target.id))
            val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db)
            val scope = store.ensureScope(restored)
            val fact = store.validatedMemories(scope).single()
            assertEquals("喜欢茉莉茶", fact.content)
            assertEquals("imported_unverified", fact.provenance)
            assertFalse(fact.manual)
            assertTrue(db.companionMemoryDao().pending(scope.id).isEmpty())
            assertEquals(0, store.process(restored, { _, _ -> error("不得重新整理迁入历史") }, 24000))
            db.messageDao().upsert(LocalMessageEntity("$mergeSessionId-new", target.id, "user", "备份后的新聊天", timestamp = "2026-10-09", createdAt = "2026-10-09"))
            manager.import(ByteArrayInputStream(output.toByteArray()), "", setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS))
            db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
            assertEquals(listOf("$mergeSessionId-new"), db.companionMemoryDao().pending(scope.id).map { it.id })
            val expired = db.companionMemoryDao().memories(scope.id).single { it.content == "已经过期的安排" }
            assertEquals("2000-01-01T00:00:00Z", expired.expiresAt)
        } finally {
            db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
            targetId?.let { db.sessionDao().deleteById(it) }
            targetCharacter?.let { db.characterDao().deleteById(it) }
            db.characterDao().deleteById(character)
            jobId?.let { db.openHelper.writableDatabase.execSQL("DELETE FROM companion_transfer_jobs WHERE id=?", arrayOf(it)) }
        }
    }

    @Test fun fusedModeBackupRestoresOwnershipFactsSourcesAndProgressWithoutRequestCache() = runBlocking {
        var db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val characterId = "fused-portable-${UUID.randomUUID()}"
        val row = LocalSessionEntity(mergeSessionId,"Portable fused fixture",characterId=characterId,
            sessionMode="agent",inheritCharacter=true,longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now")
        db.sessionDao().upsert(row)
        val initialStore = com.nekobot.app.data.local.ai.CompanionMemoryStore(db)
        val scope = initialStore.ensureScope(row)
        val source = LocalMessageEntity("$mergeSessionId-source",row.id,"user","我怕辣",timestamp="2026-10-08",createdAt="2026-10-08")
        db.messageDao().upsert(source)
        initialStore.fusedTool(row,"agent_memory_update",mapOf("key" to "diet","operation" to "add","category" to "user_profile",
            "content" to "用户怕辣","quote" to source.content),source.id)
        db.companionMemoryDao().saveReceipts(listOf(com.nekobot.app.data.local.db.CompanionReceiptEntity(scope.id,source.id,
            com.nekobot.app.data.local.ai.CompanionMemoryPolicy.fingerprint(source))))
        val output=ByteArrayOutputStream()
        manager.export(setOf(PortableDataCategory.CONVERSATIONS),"",output,"test")
        db.sessionDao().deleteById(row.id)
        manager.import(ByteArrayInputStream(output.toByteArray()),"",setOf(PortableDataCategory.CONVERSATIONS))
        // 导入结束时数据页会重建本地仓库并重新打开数据库；继续使用旧连接只会拿到已关闭的实例。
        db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db)
        val restored=requireNotNull(db.sessionDao().getById(row.id))
        assertTrue(restored.companionMode)
        assertEquals(row.id,db.companionMemoryDao().owner(characterId)?.sessionId)
        val restoredScope=store.ensureScope(restored)
        val fact=store.validatedMemories(restoredScope).single { it.current }
        assertEquals("用户怕辣",fact.content)
        assertEquals(source.id,db.companionMemoryDao().sources(fact.id).single().messageId)
        assertTrue(db.companionMemoryDao().pending(restoredScope.id).isEmpty())
        assertTrue(db.companionMemoryDao().contextMaterials(restoredScope.id).isEmpty())
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var manager: PortableDataArchiveManager
    private lateinit var workspaceRoot: File
    private lateinit var memoryFile: File
    private var originalMemory: ByteArray? = null
    private var originalRecentSessionsIncludeArchived: Boolean = false
    private lateinit var mergeSessionId: String

    @Before
    fun setUp() {
        manager = PortableDataArchiveManager(context)
        memoryFile = com.nekobot.app.data.local.ai.GlobalAgentMemoryStore.memoryFileFor(
            context,
            com.nekobot.app.ServiceContainer.prefs.activeDbName
        )
        originalMemory = memoryFile.takeIf(File::isFile)?.readBytes()
        originalRecentSessionsIncludeArchived = com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived
        mergeSessionId = "portable-merge-${UUID.randomUUID()}"
        workspaceRoot = File(context.filesDir, "workspace/$mergeSessionId")
        workspaceRoot.deleteRecursively()
        workspaceRoot.mkdirs()
        memoryFile.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
        originalMemory?.let(memoryFile::writeBytes) ?: memoryFile.delete()
        com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived =
            originalRecentSessionsIncludeArchived
        runBlocking {
            NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
                .sessionDao()
                .deleteById(mergeSessionId)
        }
    }

    @Test
    fun encryptedArchiveCanPreviewAndSelectivelyRestoreFiles() = runBlocking {
        // v0.8.3 exports only workspaces belonging to this profile's sessions.
        val db = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        db.sessionDao().upsert(LocalSessionEntity(mergeSessionId, "Portable files", createdAt = "now", updatedAt = "now"))
        File(workspaceRoot, "notes/todo.txt").apply {
            parentFile?.mkdirs()
            writeText("portable workspace")
        }
        memoryFile.writeText("portable memory")
        val output = ByteArrayOutputStream()

        manager.export(
            selected = setOf(PortableDataCategory.WORKSPACE, PortableDataCategory.GLOBAL_MEMORY),
            password = "portable-password",
            output = output,
            appVersion = "test"
        )

        val archive = output.toByteArray()
        val preview = manager.inspect(ByteArrayInputStream(archive), "portable-password")
        assertTrue(preview.encrypted)
        assertEquals(
            setOf(PortableDataCategory.WORKSPACE, PortableDataCategory.GLOBAL_MEMORY),
            preview.categories.mapTo(linkedSetOf()) { it.category }
        )

        workspaceRoot.deleteRecursively()
        memoryFile.delete()
        manager.import(
            input = ByteArrayInputStream(archive),
            password = "portable-password",
            selected = setOf(PortableDataCategory.GLOBAL_MEMORY)
        )
        assertEquals("portable memory", memoryFile.readText())
        assertFalse(File(workspaceRoot, "notes/todo.txt").exists())

        manager.import(
            input = ByteArrayInputStream(archive),
            password = "portable-password",
            selected = setOf(PortableDataCategory.WORKSPACE)
        )
        assertEquals("portable workspace", File(workspaceRoot, "notes/todo.txt").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun credentialsRequirePassword(): Unit = runBlocking {
        manager.export(
            selected = setOf(PortableDataCategory.CREDENTIALS),
            password = "",
            output = ByteArrayOutputStream(),
            appVersion = "test"
        )
    }

    @Test
    fun appSettingsUseTheirDedicatedRestorePath() = runBlocking {
        val expected = !originalRecentSessionsIncludeArchived
        com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived = expected
        val output = ByteArrayOutputStream()
        manager.export(
            selected = setOf(PortableDataCategory.APP_SETTINGS),
            password = "",
            output = output,
            appVersion = "test"
        )

        com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived = !expected
        val result = manager.import(
            input = ByteArrayInputStream(output.toByteArray()),
            password = "",
            selected = setOf(PortableDataCategory.APP_SETTINGS)
        )

        assertEquals(expected, com.nekobot.app.ServiceContainer.prefs.recentSessionsIncludeArchived)
        assertEquals(1, result.importedRows)
    }

    @Test
    fun mergingParentRowsKeepsNewerChildRows() = runBlocking {
        val database = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
        val timestamp = "2026-01-01T00:00:00Z"
        database.sessionDao().upsert(
            LocalSessionEntity(
                id = mergeSessionId,
                name = "portable merge test",
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        database.messageDao().upsert(
            LocalMessageEntity(
                id = "$mergeSessionId-before",
                sessionId = mergeSessionId,
                role = "user",
                content = "before export",
                timestamp = timestamp,
                createdAt = timestamp
            )
        )
        val output = ByteArrayOutputStream()
        manager.export(
            selected = setOf(PortableDataCategory.CONVERSATIONS),
            password = "",
            output = output,
            appVersion = "test"
        )

        database.messageDao().upsert(
            LocalMessageEntity(
                id = "$mergeSessionId-after",
                sessionId = mergeSessionId,
                role = "user",
                content = "created after export",
                timestamp = timestamp,
                createdAt = timestamp
            )
        )
        manager.import(
            input = ByteArrayInputStream(output.toByteArray()),
            password = "",
            selected = setOf(PortableDataCategory.CONVERSATIONS)
        )

        val restored = NekobotDatabase.get(context, com.nekobot.app.ServiceContainer.prefs.activeDbName)
            .messageDao()
            .listBySession(mergeSessionId)
            .map { it.id }
            .toSet()
        assertTrue("$mergeSessionId-before" in restored)
        assertTrue("$mergeSessionId-after" in restored)
    }
}
