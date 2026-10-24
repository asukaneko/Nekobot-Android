package com.nekobot.app.data.local

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Run on an isolated emulator only; the guard skips physical devices before touching a profile. */
@RunWith(AndroidJUnit4::class)
class PortableSafeImportAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var original: String? = null
    private var profile: String? = null
    private val files = mutableListOf<File>()
    private fun db() = NekobotDatabase.get(context, requireNotNull(profile))
    private val stamp = "2026-01-01T00:00:00Z"

    @Before fun isolate() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"))
        original = ServiceContainer.prefs.activeDbName
        profile = "portable_test_${UUID.randomUUID().toString().replace("-", "")}"
        ServiceContainer.switchLocalDb(requireNotNull(profile))
    }
    @After fun cleanup() {
        original?.let { ServiceContainer.switchLocalDb(it) }
        profile?.let { ServiceContainer.prefs.removeDbProfile(it); NekobotDatabase.deleteProfileFile(context, it) }
        files.forEach { it.delete() }
    }

    private suspend fun characterArchive(media: Boolean = false): Pair<String, ByteArray> {
        val id = UUID.randomUUID().toString()
        db().characterDao().upsert(LocalCharacterEntity(id, "Synthetic import fixture", createdAt = stamp, updatedAt = stamp))
        val out = ByteArrayOutputStream()
        PortableDataArchiveManager(context).export(setOf(PortableDataCategory.CHARACTERS) + if (media) setOf(PortableDataCategory.MEDIA) else emptySet(), "", out, "synthetic")
        db().characterDao().deleteById(id)
        return id to out.toByteArray()
    }
    private suspend fun prepared(bytes: ByteArray) = PortableDataArchiveManager(context).prepareImport(ByteArrayInputStream(bytes), "")
    private fun all(prepared: PreparedPortableImport) = prepared.preview.categories.associate { it.category to it.details.mapTo(linkedSetOf()) { d -> d.key } }

    @Test fun duplicateImportAddsOneCharacterThenWritesNothing() = runBlocking {
        val (id, bytes) = characterArchive()
        val manager = PortableDataArchiveManager(context)
        prepared(bytes).use { archive ->
            val result = manager.commitImport(archive, manager.previewImport(archive, all(archive)))
            assertEquals(1, result.newRows)
        }
        val receipts = db().openHelper.readableDatabase.query("SELECT COUNT(*) FROM portable_import_commits").use { it.moveToFirst(); it.getInt(0) }
        prepared(bytes).use { archive ->
            val result = manager.commitImport(archive, manager.previewImport(archive, all(archive)))
            assertEquals(0, result.importedRows)
            assertEquals(1, result.sameItems)
        }
        assertEquals(listOf(id), db().characterDao().listAll().map { it.id })
        assertEquals(receipts, db().openHelper.readableDatabase.query("SELECT COUNT(*) FROM portable_import_commits").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun concurrentConfirmedPreviewsCannotCreateDuplicateCharacters() = runBlocking {
        val (_, bytes) = characterArchive()
        val a = prepared(bytes); val b = prepared(bytes)
        try {
            val manager = PortableDataArchiveManager(context)
            val pa = manager.previewImport(a, all(a)); val pb = manager.previewImport(b, all(b))
            val results = supervisorScope {
                listOf(async(Dispatchers.Default) { runCatching { manager.commitImport(a, pa) } },
                    async(Dispatchers.Default) { runCatching { manager.commitImport(b, pb) } }).map { it.await() }
            }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, db().characterDao().listAll().size)
        } finally { a.close(); b.close() }
    }

    @Test fun failureAfterActualAttachmentInstallRollsBackDatabaseAndBytes() = runBlocking {
        val target = File(context.filesDir, "portraits/portable-fixture-${UUID.randomUUID()}.txt").apply { parentFile?.mkdirs(); writeText("archive bytes") }
        files += target
        val journal = PortableImportJournal(File(context.filesDir, "portable_import_transactions"), listOf(context.filesDir, context.cacheDir))
        assertEquals(target.canonicalFile, journal.checked(target))
        assertEquals(target.canonicalFile, journal.checked(target.canonicalFile))
        val linkedDirectory = File(context.filesDir, "portable-link-${UUID.randomUUID()}")
        java.nio.file.Files.createSymbolicLink(linkedDirectory.toPath(), context.cacheDir.toPath())
        try {
            try { journal.checked(File(linkedDirectory, "must-not-write")); fail("Child links must remain blocked") }
            catch (_: IllegalArgumentException) { }
        } finally { linkedDirectory.delete() }
        val unrelated = File(context.filesDir, "portraits/portable-unrelated-${UUID.randomUUID()}.txt").apply { writeText("unrelated") }
        files += unrelated
        val (id, bytes) = characterArchive(media = true)
        target.writeText("later local bytes")
        val manager = PortableDataArchiveManager(context)
        prepared(bytes).use { archive ->
            val initial = manager.previewImport(archive, all(archive))
            val choices = PortableImportChoices(files = initial.fileDifferences.mapTo(linkedSetOf()) { it.key })
            val plan = manager.previewImport(archive, all(archive), choices)
            try {
                manager.commitImport(archive, plan) { if (it == "正在提交设置") throw java.io.IOException("synthetic failure after file write") }
                fail("Expected injected failure")
            } catch (_: java.io.IOException) { }
        }
        assertNull(db().characterDao().getById(id))
        assertEquals("later local bytes", target.readText())
        assertEquals("unrelated", unrelated.readText())
        assertEquals(0, db().openHelper.readableDatabase.query("SELECT COUNT(*) FROM portable_import_commits").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun independentMemoryUpdatePreservesChatsNamesOwnershipAndCursor() = runBlocking {
        val character = UUID.randomUUID().toString(); val sessionId = UUID.randomUUID().toString()
        db().characterDao().upsert(LocalCharacterEntity(character, "Synthetic memory fixture", createdAt = stamp, updatedAt = stamp))
        val session = LocalSessionEntity(sessionId, "Synthetic session", characterId = character, companionMode = true,
            sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true, createdAt = stamp, updatedAt = stamp)
        db().sessionDao().upsert(session)
        val source = LocalMessageEntity(UUID.randomUUID().toString(), sessionId, "user", "合成资料：我喜欢茉莉茶", timestamp = stamp, createdAt = stamp)
        db().messageDao().upsert(source)
        val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        val scope = store.ensureScope(session)
        val written = store.fusedTool(session, "agent_memory_update", mapOf("op" to "add", "category" to "user_profile",
            "content" to "喜欢茉莉茶", "evidence" to listOf("S1"), "date_type" to "habit"),
            com.nekobot.app.data.local.ai.FusedMemoryReferences(listOf(source)))
        assertEquals(true, written["success"])
        val out = ByteArrayOutputStream()
        PortableDataArchiveManager(context).export(setOf(PortableDataCategory.CONVERSATIONS), "", out, "synthetic")
        db().openHelper.writableDatabase.execSQL("UPDATE companion_memories SET category='character_profile' WHERE scopeId=?", arrayOf(scope.id))
        // An INSERT OR REPLACE fixture deletes dependent scopes through FK cascades.
        // Simulate the intended local field edit without replacing the session row.
        db().openHelper.writableDatabase.execSQL("UPDATE local_sessions SET name=? WHERE id=?", arrayOf("Later local name", sessionId))
        db().messageDao().upsert(source.copy(id = UUID.randomUUID().toString(), content = "后来新增的合成聊天"))
        val beforeScope = requireNotNull(db().companionMemoryDao().scope(scope.id))
        val selection = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:companion_memories"))
        val manager = PortableDataArchiveManager(context)
        prepared(out.toByteArray()).use { archive ->
            val initial = manager.previewImport(archive, selection)
            assertEquals(1, initial.rows.counts.different)
            manager.commitImport(archive, initial)
            assertEquals("character_profile", db().companionMemoryDao().memories(scope.id).single().category)
            val diff = manager.previewImport(archive, selection).rows.differences.single()
            val chosen = PortableImportChoices(fields = mapOf(diff.key to setOf("category")))
            manager.commitImport(archive, manager.previewImport(archive, selection, chosen))
        }
        assertEquals("user_profile", db().companionMemoryDao().memories(scope.id).single().category)
        assertEquals("Later local name", db().sessionDao().getById(sessionId)?.name)
        assertEquals(2, db().messageDao().listBySession(sessionId).size)
        val afterScope = requireNotNull(db().companionMemoryDao().scope(scope.id))
        assertEquals(beforeScope.baselineRowId, afterScope.baselineRowId)
        assertEquals(beforeScope.archiveRowId, afterScope.archiveRowId)
        assertEquals(sessionId, db().companionMemoryDao().owner(character)?.sessionId)
    }
    @Test fun encryptedSyntheticCredentialsNeedConsentAndTargetsAndNeverActivateAModel() = runBlocking {
        val id = UUID.randomUUID().toString()
        val model = LocalAiModelEntity(id, "Synthetic credentials fixture", "openai_chat",
            apiKey = "synthetic-from-archive", baseUrl = "http://127.0.0.1:9/v1", model = "synthetic-never-call",
            enabled = false, active = false, createdAt = stamp)
        db().aiModelDao().upsert(model)
        val manager = PortableDataArchiveManager(context)
        val out = ByteArrayOutputStream()
        val password = "synthetic-fixture-password"
        manager.export(setOf(PortableDataCategory.AI_CONFIG, PortableDataCategory.CREDENTIALS), password, out, "synthetic")
        db().aiModelDao().deleteById(id)
        manager.prepareImport(ByteArrayInputStream(out.toByteArray()), password).use { source ->
            val selected = mapOf(PortableDataCategory.CREDENTIALS to setOf("credentials_bundle"))
            assertTrue(manager.previewImport(source, selected).issues.any { it.contains("单独确认") })
            val consent = PortableImportChoices(restoreCredentials = true)
            val missing = manager.previewImport(source, selected, consent)
            assertTrue(missing.issues.any { it.contains("缺少对应模型或账号配置") })
            assertTrue(missing.dependencyOptions.any { it.detail.key == "table:local_ai_models" })
            db().aiModelDao().upsert(model.copy(apiKey = "synthetic-later-local", enabled = true, active = true))
            val result = manager.commitImport(source, manager.previewImport(source, selected, consent))
            assertEquals(1, result.credentialsRestored)
            val restored = requireNotNull(db().aiModelDao().getById(id))
            assertEquals("synthetic-from-archive", restored.apiKey)
            assertFalse(restored.enabled)
            assertFalse(restored.active)
        }
    }
    @Test fun sourceOnlyWriteInvalidatesOnlyItsMemoryContextAndRepeatsWithoutWrites() = runBlocking {
        val character = UUID.randomUUID().toString(); val sessionId = UUID.randomUUID().toString()
        db().characterDao().upsert(LocalCharacterEntity(character, "Synthetic source fixture", createdAt = stamp, updatedAt = stamp))
        val session = LocalSessionEntity(sessionId, "Synthetic source session", characterId = character, companionMode = true,
            sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true, createdAt = stamp, updatedAt = stamp)
        db().sessionDao().upsert(session)
        val message = LocalMessageEntity(UUID.randomUUID().toString(), sessionId, "user", "合成资料：我喜欢茉莉茶。", timestamp = stamp, createdAt = stamp)
        db().messageDao().upsert(message)
        val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        val scope = store.ensureScope(session)
        val memory = CompanionMemoryEntity(UUID.randomUUID().toString(), scope.id, "synthetic-tea", 1, "fact", "玩家", "喜欢茉莉茶", "active",
            provenance = "imported_verified", createdAt = stamp, updatedAt = stamp)
        val source = CompanionSourceEntity(memory.id, message.id, com.nekobot.app.data.local.ai.CompanionMemoryPolicy.fingerprint(message), "我喜欢茉莉茶", "2026-01-01", "user")
        db().companionMemoryDao().saveMemory(memory)
        db().companionMemoryDao().saveSources(listOf(source))
        val out = ByteArrayOutputStream()
        val manager = PortableDataArchiveManager(context)
        manager.export(setOf(PortableDataCategory.CONVERSATIONS), "", out, "synthetic")
        db().openHelper.writableDatabase.execSQL("DELETE FROM companion_sources WHERE memoryId=?", arrayOf(memory.id))
        val cache = CompanionContextMaterialEntity(scope.id, message.id, 1, scope.revision, "fixture", message.id, "synthetic cached material", "fixture", 0, "fixture")
        db().companionMemoryDao().saveContextMaterial(cache)
        db().companionMemoryDao().saveContextMaterial(cache.copy(scopeId = "unrelated-synthetic-scope"))
        val beforeRevision = requireNotNull(db().companionMemoryDao().scope(scope.id)).revision
        val selection = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:companion_memories", "table:companion_sources"))
        prepared(out.toByteArray()).use { archive ->
            val plan = manager.previewImport(archive, selection)
            assertTrue(plan.issues.toString(), plan.issues.isEmpty())
            assertEquals(1, manager.commitImport(archive, plan).importedRows)
        }
        assertTrue(requireNotNull(db().companionMemoryDao().scope(scope.id)).revision > beforeRevision)
        assertTrue(db().companionMemoryDao().contextMaterials(scope.id).isEmpty())
        assertEquals(1, db().companionMemoryDao().contextMaterials("unrelated-synthetic-scope").size)
        val reopenedStore = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        assertEquals(listOf(memory.id), reopenedStore.validatedMemories(reopenedStore.ensureScope(session)).map { it.id })
        assertEquals(listOf(source), db().companionMemoryDao().sources(memory.id))
        prepared(out.toByteArray()).use { archive ->
            assertEquals(0, manager.commitImport(archive, manager.previewImport(archive, selection)).importedRows)
        }
    }
    private data class ChangedMessageEvidenceFixture(
        val session: LocalSessionEntity,
        val localMessage: LocalMessageEntity,
        val incomingMessage: LocalMessageEntity,
        val localMemory: CompanionMemoryEntity,
        val incomingMemory: CompanionMemoryEntity,
        val localSource: CompanionSourceEntity,
        val incomingSource: CompanionSourceEntity,
        val archive: ByteArray
    )
    private suspend fun changedMessageEvidenceFixture(): ChangedMessageEvidenceFixture {
        val character = UUID.randomUUID().toString(); val sessionId = UUID.randomUUID().toString()
        db().characterDao().upsert(LocalCharacterEntity(character, "Synthetic replacement fixture", createdAt = stamp, updatedAt = stamp))
        val session = LocalSessionEntity(sessionId, "Synthetic replacement session", characterId = character, companionMode = true,
            sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true, createdAt = stamp, updatedAt = stamp)
        db().sessionDao().upsert(session)
        val incomingMessage = LocalMessageEntity(UUID.randomUUID().toString(), sessionId, "user", "合成资料B：我喜欢桂花茶。",
            timestamp = stamp, createdAt = stamp)
        db().messageDao().upsert(incomingMessage)
        val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        val scope = store.ensureScope(session)
        val incomingMemory = CompanionMemoryEntity(UUID.randomUUID().toString(), scope.id, "synthetic-replacement-tea", 1,
            "fact", "玩家", "喜欢桂花茶", "active", current = true, valid = true,
            provenance = "imported_verified", createdAt = stamp, updatedAt = stamp)
        val incomingSource = CompanionSourceEntity(incomingMemory.id, incomingMessage.id,
            com.nekobot.app.data.local.ai.CompanionMemoryPolicy.fingerprint(incomingMessage), "我喜欢桂花茶", "2026-01-01", "user")
        db().companionMemoryDao().saveMemory(incomingMemory)
        db().companionMemoryDao().saveSources(listOf(incomingSource))
        val out = ByteArrayOutputStream()
        PortableDataArchiveManager(context).export(setOf(PortableDataCategory.CONVERSATIONS), "", out, "synthetic")
        val localMessage = incomingMessage.copy(content = "合成资料A：我喜欢茉莉茶。")
        val localMemory = incomingMemory.copy(content = "喜欢茉莉茶")
        val localSource = incomingSource.copy(
            fingerprint = com.nekobot.app.data.local.ai.CompanionMemoryPolicy.fingerprint(localMessage), quote = "我喜欢茉莉茶")
        // UPDATE preserves message identity and exercises the real invalidation trigger before restoring coherent local A evidence.
        db().openHelper.writableDatabase.execSQL("UPDATE local_messages SET content=? WHERE id=?", arrayOf(localMessage.content, localMessage.id))
        db().companionMemoryDao().saveMemory(localMemory)
        db().companionMemoryDao().saveSources(listOf(localSource))
        return ChangedMessageEvidenceFixture(session, localMessage, incomingMessage, localMemory, incomingMemory,
            localSource, incomingSource, out.toByteArray())
    }
    @Test fun replacingMessageFactAndProofKeepsAlreadyCurrentValidMemoryUsableAndRepeatsWithoutWrites() = runBlocking {
        val fixture = changedMessageEvidenceFixture()
        val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        assertEquals(fixture.localMessage, db().messageDao().listBySession(fixture.session.id).single())
        assertEquals(fixture.localMemory, db().companionMemoryDao().memories(fixture.localMemory.scopeId).single())
        assertEquals(listOf(fixture.localSource), db().companionMemoryDao().sources(fixture.localMemory.id))
        assertEquals(listOf(fixture.localMemory), store.validatedMemories(store.ensureScope(fixture.session)))
        val manager = PortableDataArchiveManager(context)
        val selected = mapOf(PortableDataCategory.CONVERSATIONS to
            setOf("table:local_messages", "table:companion_memories", "table:companion_sources"))
        prepared(fixture.archive).use { archive ->
            val initial = manager.previewImport(archive, selected, detailLimit = Int.MAX_VALUE)
            assertEquals(3, initial.rows.counts.different)
            val memoryDifference = initial.rows.differences.single { it.table == "companion_memories" }
            assertTrue(memoryDifference.fields.any { it.column == "content" })
            // Both versions are already valid/current. These flags are absent from the user's selectable differences.
            assertFalse(memoryDifference.fields.any { it.column == "valid" || it.column == "current" })
            val choices = PortableImportChoices(fields = initial.rows.differences.associate { row ->
                row.key to row.fields.mapTo(linkedSetOf()) { it.column }
            })
            val plan = manager.previewImport(archive, selected, choices)
            assertTrue(plan.issues.toString(), plan.issues.isEmpty())
            assertEquals(3, manager.commitImport(archive, plan).updatedRows)
        }
        assertEquals(fixture.incomingMessage, db().messageDao().listBySession(fixture.session.id).single())
        val saved = db().companionMemoryDao().versions(fixture.incomingMemory.scopeId, fixture.incomingMemory.factKey).single()
        assertTrue(saved.valid); assertTrue(saved.current); assertEquals(fixture.incomingMemory, saved)
        assertEquals(listOf(fixture.incomingSource), db().companionMemoryDao().sources(saved.id))
        val reopenedStore = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        assertEquals(listOf(saved), reopenedStore.validatedMemories(reopenedStore.ensureScope(fixture.session)))
        prepared(fixture.archive).use { archive ->
            val plan = manager.previewImport(archive, selected)
            assertTrue(plan.issues.toString(), plan.issues.isEmpty())
            assertEquals(0, plan.rows.counts.different)
            assertEquals(0, manager.commitImport(archive, plan).importedRows)
        }
        assertEquals(listOf(saved), reopenedStore.validatedMemories(reopenedStore.ensureScope(fixture.session)))
    }
    @Test fun replacingOnlyTheMessageWithoutNewProofStillInvalidatesTheOldMemory() = runBlocking {
        val fixture = changedMessageEvidenceFixture()
        val store = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        assertEquals(listOf(fixture.localMemory), store.validatedMemories(store.ensureScope(fixture.session)))
        val manager = PortableDataArchiveManager(context)
        val selected = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:local_messages"))
        prepared(fixture.archive).use { archive ->
            val initial = manager.previewImport(archive, selected, detailLimit = Int.MAX_VALUE)
            assertEquals(1, initial.rows.counts.different)
            val choices = PortableImportChoices(fields = initial.rows.differences.associate { row ->
                row.key to row.fields.mapTo(linkedSetOf()) { it.column }
            })
            val plan = manager.previewImport(archive, selected, choices)
            assertTrue(plan.issues.toString(), plan.issues.isEmpty())
            assertEquals(1, manager.commitImport(archive, plan).updatedRows)
        }
        assertEquals(fixture.incomingMessage, db().messageDao().listBySession(fixture.session.id).single())
        val saved = db().companionMemoryDao().versions(fixture.localMemory.scopeId, fixture.localMemory.factKey).single()
        assertEquals(fixture.localMemory.content, saved.content)
        assertFalse(saved.valid); assertFalse(saved.current)
        assertEquals(listOf(fixture.localSource), db().companionMemoryDao().sources(saved.id))
        val reopenedStore = com.nekobot.app.data.local.ai.CompanionMemoryStore(db())
        assertTrue(reopenedStore.validatedMemories(reopenedStore.ensureScope(fixture.session)).isEmpty())
        prepared(fixture.archive).use { archive ->
            assertEquals(0, manager.commitImport(archive, manager.previewImport(archive, selected)).importedRows)
        }
        assertFalse(db().companionMemoryDao().versions(saved.scopeId, saved.factKey).single().valid)
    }
    @Test fun selectingOneSettingPreservesExactLocalFontAndBackgroundUris() = runBlocking {
        val prefs = ServiceContainer.prefs
        val oldFont = prefs.customFontPath; val oldBackground = prefs.customChatBackgroundPath
        val oldRecent = prefs.recentSessionsIncludeArchived
        try {
            prefs.customFontPath = "content://synthetic.fixture/archive-font"
            prefs.customChatBackgroundPath = "content://synthetic.fixture/archive-background"
            prefs.recentSessionsIncludeArchived = !oldRecent
            val out = ByteArrayOutputStream()
            val manager = PortableDataArchiveManager(context)
            manager.export(setOf(PortableDataCategory.APP_SETTINGS), "", out, "synthetic")
            prefs.customFontPath = "content://synthetic.fixture/later-local-font"
            prefs.customChatBackgroundPath = "content://synthetic.fixture/later-local-background"
            prefs.recentSessionsIncludeArchived = oldRecent
            prepared(out.toByteArray()).use { source ->
                val selected = mapOf(PortableDataCategory.APP_SETTINGS to setOf("app_settings"))
                val chosen = PortableImportChoices(settings = setOf("recent_sessions_include_archived"))
                val result = manager.commitImport(source, manager.previewImport(source, selected, chosen))
                assertEquals(1, result.settingsUpdated)
            }
            assertEquals(!oldRecent, prefs.recentSessionsIncludeArchived)
            assertEquals("content://synthetic.fixture/later-local-font", prefs.customFontPath)
            assertEquals("content://synthetic.fixture/later-local-background", prefs.customChatBackgroundPath)
        } finally {
            prefs.customFontPath = oldFont; prefs.customChatBackgroundPath = oldBackground
            prefs.recentSessionsIncludeArchived = oldRecent
        }
    }
    @Test fun selectingFontWithoutItsFileIsBlockedBeforeClearingTheLocalFont() = runBlocking {
        val prefs = ServiceContainer.prefs
        val oldFont = prefs.customFontPath
        try {
            prefs.customFontPath = "content://synthetic.fixture/missing-${UUID.randomUUID()}.ttf"
            val out = ByteArrayOutputStream()
            val manager = PortableDataArchiveManager(context)
            manager.export(setOf(PortableDataCategory.APP_SETTINGS), "", out, "synthetic")
            prefs.customFontPath = "content://synthetic.fixture/later-local-font"
            prepared(out.toByteArray()).use { source ->
                val plan = manager.previewImport(source, mapOf(PortableDataCategory.APP_SETTINGS to setOf("app_settings")),
                    PortableImportChoices(settings = setOf("custom_font_file")))
                assertTrue(plan.issues.any { it.contains("缺少对应文件") })
            }
            assertEquals("content://synthetic.fixture/later-local-font", prefs.customFontPath)
        } finally { prefs.customFontPath = oldFont }
    }
}
