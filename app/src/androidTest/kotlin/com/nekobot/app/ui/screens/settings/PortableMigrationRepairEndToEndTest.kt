package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.ai.CompanionBudgets
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.ai.CompanionHybridSearch
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.*
import com.nekobot.app.ui.theme.NekobotTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

/** Real archive/Room/VM/UI path. Every record and screenshot is synthetic; never run on a phone. */
class PortableMigrationRepairEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalProfile: String? = null
    private var profile: String? = null
    private var originalConfiguration: Configuration? = null
    private var vm: SafePortableImportViewModel? = null
    private var uiPrepared: PreparedPortableImport? = null
    private val evidence = linkedMapOf<String, Any>(
        "fixture" to "synthetic-only-sixteen-core-seven-source-one-reasoning",
        "test_class" to "${javaClass.name}",
        "network_calls" to 0,
        "source_archive" to "exported-from-this-isolated-test-profile"
    )
    private val selected = mapOf(PortableDataCategory.CONVERSATIONS to setOf(
        "table:companion_memories", "table:companion_sources", "table:local_messages"
    ))
    private val stamp = "2026-01-01T00:00:00Z"
    private fun db() = NekobotDatabase.get(context, requireNotNull(profile))
    private fun manager() = PortableDataArchiveManager(context)

    @Before fun isolateSyntheticProfile() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"))
        originalProfile = ServiceContainer.prefs.activeDbName
        profile = "migration_e2e_${UUID.randomUUID().toString().replace("-", "")}"
        ServiceContainer.switchLocalDb(requireNotNull(profile))
        compose.runOnUiThread {
            val resources = compose.activity.resources
            originalConfiguration = Configuration(resources.configuration)
            val chinese = Configuration(resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            @Suppress("DEPRECATION") resources.updateConfiguration(chinese, resources.displayMetrics)
        }
    }

    @After fun closeSyntheticProfile() {
        vm?.let { model -> compose.runOnIdle { ViewModelStore().apply { put("migration-e2e", model); clear() } } }
        uiPrepared?.close()
        originalConfiguration?.let { config -> compose.runOnUiThread {
            val resources = compose.activity.resources
            @Suppress("DEPRECATION") resources.updateConfiguration(config, resources.displayMetrics)
        } }
        originalProfile?.let { ServiceContainer.switchLocalDb(it) }
        profile?.let {
            ServiceContainer.prefs.removeDbProfile(it)
            NekobotDatabase.deleteProfileFile(context, it)
        }
        // Keep only synthetic cache screenshots and the JSON receipt for the test runner to collect.
    }

    private data class Fixture(
        val session: LocalSessionEntity,
        val scope: CompanionScopeEntity,
        val memories: List<CompanionMemoryEntity>,
        val originalMessages: List<LocalMessageEntity>,
        val assistant: LocalMessageEntity,
        val laterMessage: LocalMessageEntity,
        val archive: ByteArray
    )

    private suspend fun seedAndExport(): Fixture {
        val characterId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        db().characterDao().upsert(LocalCharacterEntity(characterId, "合成角色（测试）", createdAt = stamp, updatedAt = stamp))
        val session = LocalSessionEntity(sessionId, "合成迁移验收会话", characterId = characterId,
            companionMode = true, sessionMode = "agent", inheritCharacter = true,
            longConversationEnabled = true, createdAt = stamp, updatedAt = stamp)
        db().sessionDao().upsert(session)
        val sourceMessages = (1..7).map { n -> LocalMessageEntity(UUID.randomUUID().toString(), sessionId,
            "user", "合成资料${n.toString().padStart(2, '0')}：我喜欢茉莉茶$n。", timestamp = stamp, createdAt = stamp) }
        sourceMessages.forEach { db().messageDao().upsert(it) }
        val assistant = LocalMessageEntity(UUID.randomUUID().toString(), sessionId, "assistant",
            "合成回复：这是用于迁移测试的记录。", reasoningContent = "合成思考原文：保留标点、换行。\n第二行也要逐字一致。",
            timestamp = "2026-01-01T00:00:01Z", createdAt = "2026-01-01T00:00:01Z")
        db().messageDao().upsert(assistant)
        val store = CompanionMemoryStore(db(), CompanionHybridSearch(db(), backend = { null }))
        val initialScope = store.ensureScope(session)
        val scope = initialScope.copy(baselineRowId = db().companionMemoryDao().lastRowId(sessionId),
            archiveRowId = requireNotNull(db().messageDao().cursorOf(sourceMessages[2].id)).rowId,
            archiveMessageId = sourceMessages[2].id, embeddingModelId = "")
        db().companionMemoryDao().saveScope(scope)
        val memories = (1..16).map { n -> CompanionMemoryEntity(UUID.randomUUID().toString(), scope.id,
            "synthetic-repair-${n.toString().padStart(2, '0')}", 1, "fact", "合成参与者",
            if (n <= 7) sourceMessages[n - 1].content else "合成资料${n.toString().padStart(2, '0')}：这是独立保留的旧记忆$n。",
            "active", alwaysProvide = true, category = "user_profile", tagsJson = if (n <= 7) "[\"synthetic\"]" else "[\"synthetic\",\"原话来源未验证\"]",
            provenance = if (n <= 7) "imported_verified" else "imported_unverified", createdAt = stamp, updatedAt = stamp) }
        memories.forEach { db().companionMemoryDao().saveMemory(it) }
        db().companionMemoryDao().saveSources(sourceMessages.mapIndexed { i, message -> CompanionSourceEntity(
            memories[i].id, message.id, CompanionMemoryPolicy.fingerprint(message), message.content, "2026-01-01", "user") })
        val output = ByteArrayOutputStream()
        manager().export(setOf(PortableDataCategory.CONVERSATIONS), "", output, "synthetic-e2e")
        val later = LocalMessageEntity(UUID.randomUUID().toString(), sessionId, "user", "后来新增的合成聊天，必须保留。",
            timestamp = "2026-01-02T00:00:00Z", createdAt = "2026-01-02T00:00:00Z")
        return Fixture(session, scope, memories, sourceMessages + assistant, assistant, later, output.toByteArray())
    }

    /** Simulate the old local import, using UPDATE so no session/message REPLACE cascades occur. */
    private suspend fun restoreLegacyBaseline(fixture: Fixture): CompanionScopeEntity {
        fixture.memories.forEach { memory ->
            db().companionMemoryDao().eraseMemorySources(memory.id)
            db().companionMemoryDao().saveMemory(memory.copy(content = "[PINNED]${memory.content}",
                provenance = "imported_unverified", tagsJson = "[\"synthetic\",\"原话来源未验证\"]"))
        }
        db().openHelper.writableDatabase.execSQL("UPDATE local_messages SET reasoning_content=NULL WHERE id=?",
            arrayOf(fixture.assistant.id))
        if (db().messageDao().getById(fixture.laterMessage.id) == null) db().messageDao().upsert(fixture.laterMessage)
        val current = requireNotNull(db().companionMemoryDao().scope(fixture.scope.id))
        val localScope = current.copy(baselineRowId = db().companionMemoryDao().lastRowId(fixture.session.id),
            archiveRowId = requireNotNull(db().messageDao().cursorOf(fixture.originalMessages[5].id)).rowId,
            archiveMessageId = fixture.originalMessages[5].id)
        db().companionMemoryDao().saveScope(localScope)
        assertEquals(16, prefixCount(fixture))
        assertNull(db().messageDao().getById(fixture.assistant.id)?.reasoningContent)
        assertTrue(localScope.baselineRowId > 0)
        assertTrue(localScope.archiveRowId > 0)
        return localScope
    }

    private suspend fun prepare(fixture: Fixture) = manager().prepareImport(ByteArrayInputStream(fixture.archive), "")
    private suspend fun prefixCount(fixture: Fixture) = db().companionMemoryDao().memories(fixture.scope.id).count { it.content.startsWith("[PINNED]") }
    private fun receiptCount() = db().openHelper.readableDatabase.query("SELECT COUNT(*) FROM portable_import_commits").use { it.moveToFirst(); it.getInt(0) }
    private fun relationshipCounts(): Map<String, Int> {
        val sql = db().openHelper.readableDatabase
        val names = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'companion_relationship_%'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return names.associateWith { name ->
            require(name.matches(Regex("[a-z_]+")))
            sql.query("SELECT COUNT(*) FROM $name").use { it.moveToFirst(); it.getInt(0) }
        }
    }

    private fun record(phase: String, values: Map<String, Any>) {
        evidence[phase] = values
        File(context.cacheDir, "imported-history-repair-e2e.json")
            .writeText(GsonBuilder().setPrettyPrinting().create().toJson(evidence))
    }

    private fun ready() {
        compose.waitUntil(30_000) { vm?.state?.value?.busy != true }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, "imported-history-repair-$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Test fun oldSevenRowScopeLeavesNineWhileRealUiRepairsAllSixteenAndReasoningWithoutLosingLocalData() = runBlocking {
        try {
            val fixture = seedAndExport()
            restoreLegacyBaseline(fixture)

            // Reproduce the former sourceConflictKeys-only choice against the real planner and DB.
            prepare(fixture).use { archive ->
                val initial = manager().previewImport(archive, selected, detailLimit = Int.MAX_VALUE)
                assertEquals(7, initial.rows.sourceConflictMemoryCount)
                assertEquals(7, initial.rows.sourceConflictKeys.size)
                assertEquals(16, initial.rows.tableCounts["companion_memories"]?.different)
                assertEquals(1, initial.rows.tableCounts["local_messages"]?.different)
                var oldChoice = PortableImportChoices()
                initial.rows.differences.filter { it.key in initial.rows.sourceConflictKeys }.forEach { row ->
                    oldChoice = oldChoice.withRecordChoice(row, true, initial.rows.sourceFieldChoices[row.memoryId].orEmpty())
                }
                val oldPlan = manager().previewImport(archive, selected, oldChoice, Int.MAX_VALUE)
                assertTrue(oldPlan.issues.toString(), oldPlan.issues.isEmpty())
                manager().commitImport(archive, oldPlan)
                assertEquals(9, prefixCount(fixture))
                assertNull(db().messageDao().getById(fixture.assistant.id)?.reasoningContent)
                record("old_seven_row_commit", mapOf("source_conflicts" to 7, "core_total" to 16,
                    "prefixes_remaining" to 9, "reasoning_restored" to false, "real_manager_commit" to true))
            }

            // Start again from the same synthetic old state, with a newly prepared archive instance.
            val localScope = restoreLegacyBaseline(fixture)
            val beforeMemories = db().companionMemoryDao().memories(fixture.scope.id).sortedBy { it.id }
            val beforeMessages = db().messageDao().listBySession(fixture.session.id).sortedBy { it.id }
            val beforeRelationships = relationshipCounts()
            assertTrue(beforeRelationships.values.all { it == 0 })
            val beforeReceipts = receiptCount()
            val archive = prepare(fixture).also { uiPrepared = it }
            val initial = manager().previewImport(archive, selected)
            assertEquals(7, initial.rows.sourceConflictKeys.size)
            assertEquals(17, initial.rows.sourceReviewKeys.size)
            compose.runOnUiThread {
                vm = SafePortableImportViewModel().also { model -> model.state.value = SafeImportUiState(
                    prepared = archive, selected = selected, plan = initial, planValid = true, step = SafeImportStep.CONFIRM) }
            }
            compose.setContent { NekobotTheme {
                Surface(Modifier.safeDrawingPadding()) { SafePortableImportPanel(vm = requireNotNull(vm)) }
            } }
            compose.onNodeWithTag("safe-import-scroll").performScrollToNode(hasTestTag("import-review-related"))
            compose.onNodeWithTag("import-review-related").performClick()
            ready()
            compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick().assertIsSelected()
            screenshot("choices")
            compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).performClick()
            ready()
            val previewState = requireNotNull(vm).state.value
            val previewPlan = requireNotNull(previewState.plan)
            assertNull(previewState.error)
            assertTrue(previewState.planValid)
            assertTrue(previewPlan.issues.toString(), previewPlan.issues.isEmpty())
            assertEquals(16, previewPlan.rows.tableCounts["companion_memories"]?.updated)
            assertEquals(1, previewPlan.rows.tableCounts["local_messages"]?.updated)
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithText(compose.activity.getString(R.string.import_review_preview)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.import_review_start)).assertIsDisplayed()
            assertEquals(beforeMemories, db().companionMemoryDao().memories(fixture.scope.id).sortedBy { it.id })
            assertEquals(beforeMessages, db().messageDao().listBySession(fixture.session.id).sortedBy { it.id })
            assertEquals(localScope, db().companionMemoryDao().scope(fixture.scope.id))
            assertEquals(beforeRelationships, relationshipCounts())
            assertEquals(beforeReceipts, receiptCount())
            assertEquals(16, prefixCount(fixture))
            record("ui_next_preview_only", mapOf("selected_memory_updates" to 16, "selected_reasoning_updates" to 1,
                "prefixes_still_present" to 16, "database_unchanged" to true, "import_receipts_unchanged" to true))
            screenshot("preview")

            compose.onNodeWithTag("safe-import-execute").performClick()
            ready()
            val resultState = requireNotNull(vm).state.value
            assertNull(resultState.error)
            assertNotNull(resultState.result)
            assertEquals(SafeImportStep.RESULT, resultState.step)
            val restored = db().companionMemoryDao().memories(fixture.scope.id)
            assertEquals(16, CompanionCoreMemory.rows(restored).size)
            assertEquals(0, prefixCount(fixture))
            assertEquals(7, restored.count { it.provenance == "imported_verified" })
            assertEquals(9, restored.count { it.provenance == "imported_unverified" })
            assertEquals(fixture.memories.sortedBy { it.id }, restored.sortedBy { it.id })
            assertEquals(7, restored.sumOf { db().companionMemoryDao().sources(it.id).size })
            fixture.originalMessages.forEach { expected -> assertEquals(expected, db().messageDao().getById(expected.id)) }
            assertEquals(fixture.laterMessage, db().messageDao().getById(fixture.laterMessage.id))
            assertEquals(9, db().messageDao().listBySession(fixture.session.id).size)
            val afterScope = requireNotNull(db().companionMemoryDao().scope(fixture.scope.id))
            assertEquals(localScope.id, afterScope.id)
            assertEquals(localScope.profile, afterScope.profile)
            assertEquals(localScope.sessionId, afterScope.sessionId)
            assertEquals(localScope.characterId, afterScope.characterId)
            assertEquals(localScope.baselineRowId, afterScope.baselineRowId)
            assertEquals(localScope.archiveRowId, afterScope.archiveRowId)
            assertEquals(localScope.archiveMessageId, afterScope.archiveMessageId)
            assertEquals(fixture.session.id, db().companionMemoryDao().owner(fixture.session.characterId!!)?.sessionId)
            assertEquals(beforeRelationships, relationshipCounts())
            // The real local recall store reads the repaired core, with an explicitly offline backend.
            val store = CompanionMemoryStore(db(), CompanionHybridSearch(db(), backend = { null }))
            val recall = store.fusedRecall(fixture.session, "茉莉茶", CompanionBudgets(1000, 1000, 1000, 1000, 1000)).first
            fixture.memories.forEach { assertTrue("Repaired memory missing from core recall: ${it.factKey}", recall.contains(it.content)) }
            assertFalse(recall.contains("[PINNED]"))
            record("ui_start_committed", mapOf("core_total" to 16, "prefixes_remaining" to 0,
                "verified" to 7, "unverified" to 9, "source_rows" to 7, "reasoning_exact" to true,
                "original_messages_unchanged_except_restored_reasoning" to true, "later_chat_preserved" to true,
                "scope_owner_and_nonzero_cursors_preserved" to true, "relationship_rows_added" to 0,
                "repaired_core_recalled_offline" to true, "real_vm_and_panel" to true))
            screenshot("result")

            val finalMemories = restored.sortedBy { it.id }
            val finalMessages = db().messageDao().listBySession(fixture.session.id).sortedBy { it.id }
            val finalSources = restored.flatMap { db().companionMemoryDao().sources(it.id) }.sortedBy { it.memoryId }
            val receiptsBeforeRepeat = receiptCount()
            prepare(fixture).use { repeated ->
                val plan = manager().previewImport(repeated, selected)
                assertTrue(plan.issues.toString(), plan.issues.isEmpty())
                assertEquals(0, plan.rows.counts.different)
                val repeat = manager().commitImport(repeated, plan)
                assertEquals(0, repeat.importedRows)
                assertEquals(0, repeat.newRows)
                assertEquals(0, repeat.updatedRows)
            }
            assertEquals(finalMemories, db().companionMemoryDao().memories(fixture.scope.id).sortedBy { it.id })
            assertEquals(finalMessages, db().messageDao().listBySession(fixture.session.id).sortedBy { it.id })
            assertEquals(finalSources, restored.flatMap { db().companionMemoryDao().sources(it.id) }.sortedBy { it.memoryId })
            assertEquals(afterScope, db().companionMemoryDao().scope(fixture.scope.id))
            assertEquals(receiptsBeforeRepeat, receiptCount())
            assertEquals(beforeRelationships, relationshipCounts())
            record("repeat_same_archive", mapOf("business_rows_written" to 0, "new_receipts" to 0,
                "all_business_records_unchanged" to true))
            record("outcome", mapOf("status" to "passed", "expected_tests" to 1))
        } catch (failure: Throwable) {
            record("outcome", mapOf("status" to "failed", "error_type" to failure.javaClass.name,
                "error" to (failure.message ?: "No message")))
            throw failure
        }
    }
}
