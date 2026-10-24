package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic previews exercise pagination and cancellation without a database or import writes. */
class PortableImportBulkRecordStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val total = 125
    private val selected = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:companion_memories"))
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private lateinit var prepared: PreparedPortableImport
    private lateinit var vm: SafePortableImportViewModel
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun gate() = CompletableDeferred<Unit>().also(gates::add)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < until) { instrumentation.waitForIdleSync(); SystemClock.sleep(10) }
        assertTrue("Synthetic bulk operation timed out", condition())
    }
    @Before fun prepare() {
        val staging = File.createTempFile("portable-bulk-record-", ".tmp", context.cacheDir).apply { delete(); mkdirs() }
        val zip = File(staging, "synthetic.zip").apply { writeBytes(byteArrayOf()) }
        val source = PortableArchivePreview("synthetic", "fixture", false, emptyList())
        prepared = PreparedPortableImport(source, "synthetic", PortableDataArchiveManager.ResolvedArchive(source, zip, listOf(zip)),
            emptyMap(), emptyList(), staging, "")
    }
    @After fun close() {
        gates.forEach { it.complete(Unit) }
        if (::vm.isInitialized) main { ViewModelStore().apply { put("bulk-record", vm); clear() } }
        instrumentation.waitForIdleSync()
        prepared.close()
    }
    private fun plan(choices: PortableImportChoices = PortableImportChoices(), limit: Int = 50): PortableImportPreview {
        val updated = (0 until total).count { choices.fields["row-$it"].orEmpty().isNotEmpty() }
        val differences = (0 until minOf(total, limit)).map { index ->
            PortableImportRowDifference("row-$index", "companion_memories", "Synthetic record $index",
                listOf(PortableImportFieldDifference("sourceMessageIds", "local", "imported"),
                    PortableImportFieldDifference("evidenceStatus", "local", "imported")))
        }
        return PortableImportPreview(
            rows = PortableImportRowPlan("rows", PortableImportCounts(different = total, updated = updated), differences, emptyList(), 0),
            fileCounts = PortableImportCounts(different = 1), fileDifferences = emptyList(),
            settingDifferences = emptyList(), storyDifferent = true, issues = emptyList(), dependencyOptions = emptyList(),
            fingerprint = "synthetic-$updated-$limit", preparedId = prepared.id, selected = selected, choices = choices,
            fileBefore = emptyMap(), fileAfter = emptyMap(), settingsBefore = JsonObject(), settingsIncoming = null,
            useStory = false, storySessionIds = emptySet(), credentialFingerprint = "", credentialTargets = emptyMap())
    }
    private fun start(operations: SafeImportOperations, choices: PortableImportChoices = PortableImportChoices()) {
        main {
            vm = SafePortableImportViewModel(operations)
            vm.state.value = SafeImportUiState(prepared = prepared, selected = selected, choices = choices,
                plan = plan(choices), planValid = true, step = SafeImportStep.CONFIRM)
        }
    }
    @Test fun incomingIncludesUnloadedRecordsPreservesOtherDraftChoicesAndAllowsIndividualOverride() {
        val limits = Collections.synchronizedList(mutableListOf<Int>())
        val commits = AtomicInteger()
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview { limits += limit; return plan(choices, limit) }
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
                progress: (String) -> Unit): PortableImportResult { commits.incrementAndGet(); return PortableImportResult(0, 0, 0) }
        })
        val draft = PortableImportChoices(fields = mapOf("unrelated" to setOf("description")),
            files = setOf("already-chosen-file", "plot_story"), settings = setOf("already-chosen-setting"), allowSameName = true)
        main { vm.chooseAllRecordFields(context, draft, true); vm.commit(context) }
        await { !vm.state.value.busy }
        assertTrue(vm.state.value.planValid)
        assertEquals(listOf(Int.MAX_VALUE, 50), limits.toList())
        assertEquals(50, vm.state.value.plan!!.rows.differences.size)
        assertEquals(total, vm.state.value.plan!!.rows.counts.updated)
        assertEquals(setOf("sourceMessageIds", "evidenceStatus"), vm.state.value.choices.fields["row-124"])
        assertEquals(draft.files, vm.state.value.choices.files); assertEquals(draft.settings, vm.state.value.choices.settings)
        assertEquals(draft.restoreCredentials, vm.state.value.choices.restoreCredentials)
        assertEquals(draft.allowSameName, vm.state.value.choices.allowSameName)
        assertEquals(draft.fields["unrelated"], vm.state.value.choices.fields["unrelated"])
        assertEquals(0, commits.get())
        val bulk = vm.state.value.choices
        val individual = bulk.copy(fields = bulk.fields + ("row-0" to setOf("evidenceStatus")))
        main { vm.saveChoices(context, individual) }; await { !vm.state.value.busy }
        assertEquals(setOf("evidenceStatus"), vm.state.value.choices.fields["row-0"])
        assertEquals(bulk.fields["row-124"], vm.state.value.choices.fields["row-124"])
        assertEquals(0, commits.get())
    }
    @Test fun keepClearsAllDifferingRecordFieldsAndRestoresTheCurrentPageSize() {
        val limits = Collections.synchronizedList(mutableListOf<Int>())
        val choices = PortableImportChoices(fields = (0 until total).associate { "row-$it" to setOf("sourceMessageIds", "evidenceStatus") } +
            ("unrelated" to setOf("description")), files = setOf("explicit-file"), settings = setOf("explicit-setting"), restoreCredentials = true)
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview { limits += limit; return plan(choices, limit) }
        }, choices)
        main { vm.preview(context, more = true) }; await { !vm.state.value.busy }
        assertEquals(100, vm.state.value.plan!!.rows.differences.size)
        main { vm.chooseAllRecordFields(context, choices, false) }; await { !vm.state.value.busy }
        assertEquals(listOf(100, Int.MAX_VALUE, 100), limits.toList())
        assertEquals(100, vm.state.value.plan!!.rows.differences.size)
        assertEquals(0, vm.state.value.plan!!.rows.counts.updated)
        assertEquals(mapOf("unrelated" to setOf("description")), vm.state.value.choices.fields)
        assertEquals(choices.copy(fields = vm.state.value.choices.fields), vm.state.value.choices)
    }
    @Test fun duplicateBulkRequestsAndFinalImportAreBlockedWhileTheCompleteReadIsBusy() {
        val release = gate(); val calls = AtomicInteger(); val commits = AtomicInteger()
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview {
                calls.incrementAndGet(); release.await(); return plan(choices, limit)
            }
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
                progress: (String) -> Unit): PortableImportResult { commits.incrementAndGet(); return PortableImportResult(0, 0, 0) }
        })
        main { vm.chooseAllRecordFields(context, PortableImportChoices(), true) }; await { calls.get() == 1 }
        main { vm.chooseAllRecordFields(context, PortableImportChoices(), false); vm.commit(context) }
        assertTrue(vm.state.value.busy); assertFalse(vm.state.value.planValid)
        assertEquals(1, calls.get()); assertEquals(0, commits.get())
        release.complete(Unit); await { !vm.state.value.busy }
        assertEquals(2, calls.get()); assertEquals(total, vm.state.value.plan!!.rows.counts.updated); assertEquals(0, commits.get())
    }
    private fun cancelDelayedBulk(delayedLimit: Int) {
        val release = gate(); val entered = AtomicBoolean(); val returned = AtomicBoolean()
        val choices = PortableImportChoices(files = setOf("saved-file"))
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview {
                if (limit == delayedLimit && entered.compareAndSet(false, true)) return withContext(NonCancellable) {
                    release.await(); returned.set(true); plan(choices, limit)
                }
                return plan(choices, limit)
            }
        }, choices)
        val before = vm.state.value
        main { vm.chooseAllRecordFields(context, choices.copy(settings = setOf("unsaved-setting")), true) }
        await { entered.get() }
        main { vm.cancel() }
        assertEquals(before, vm.state.value)
        val newer = choices.copy(files = setOf("newer-file"))
        main { vm.saveChoices(context, newer) }; await { vm.state.value.planValid }
        release.complete(Unit); await { returned.get() }
        instrumentation.waitForIdleSync()
        assertEquals(newer, vm.state.value.choices); assertTrue(vm.state.value.planValid)
        assertEquals(0, vm.state.value.plan!!.rows.counts.updated); assertNull(vm.state.value.error)
    }
    @Test fun cancelDuringCompleteReadRestoresTheReviewAndCannotOverwriteANewerChoice() = cancelDelayedBulk(Int.MAX_VALUE)
    @Test fun cancelDuringRepreviewDoesNotPublishTheBulkDraft() = cancelDelayedBulk(50)
    @Test fun failedRepreviewDoesNotPublishTheBulkDraftOrPermitCommit() {
        val commits = AtomicInteger()
        val choices = PortableImportChoices(files = setOf("saved-file"))
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview {
                if (limit != Int.MAX_VALUE) error("Synthetic bulk refresh failure")
                return plan(choices, limit)
            }
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
                progress: (String) -> Unit): PortableImportResult { commits.incrementAndGet(); return PortableImportResult(0, 0, 0) }
        }, choices)
        val before = vm.state.value.plan
        main { vm.chooseAllRecordFields(context, choices, true) }; await { !vm.state.value.busy }
        main { vm.commit(context) }
        assertSame(before, vm.state.value.plan); assertEquals(choices, vm.state.value.choices)
        assertFalse(vm.state.value.planValid); assertEquals("Synthetic bulk refresh failure", vm.state.value.error)
        assertEquals(0, commits.get())
    }
    @Test fun incompleteFullPreviewCannotSilentlyChooseOnlyTheVisibleRecords() {
        val calls = AtomicInteger()
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview { calls.incrementAndGet(); return plan(choices, 50) }
        })
        main { vm.chooseAllRecordFields(context, PortableImportChoices(), true) }; await { !vm.state.value.busy }
        assertEquals(1, calls.get()); assertTrue(vm.state.value.choices.fields.isEmpty()); assertFalse(vm.state.value.planValid)
        assertEquals(context.getString(R.string.import_bulk_record_changed), vm.state.value.error)
    }
    @Test fun cancellingTheWindowAfterSuccessfulBulkAndMoreRestoresItsOpeningChoicesPlanAndPageSize() {
        val commits = AtomicInteger()
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview = plan(choices, limit)
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
                progress: (String) -> Unit): PortableImportResult { commits.incrementAndGet(); return PortableImportResult(0, 0, 0) }
        }, PortableImportChoices(files = setOf("saved-file"), settings = setOf("saved-setting")))
        val before = vm.state.value
        main { assertTrue(vm.beginDifferenceReview()); vm.chooseAllRecordFields(context,
            before.choices.copy(files = setOf("unsaved-file"), settings = setOf("unsaved-setting")), true) }
        await { !vm.state.value.busy }
        assertEquals(total, vm.state.value.plan!!.rows.counts.updated)
        main { vm.preview(context, more = true) }; await { !vm.state.value.busy }
        assertEquals(100, vm.state.value.plan!!.rows.differences.size)
        main { vm.commit(context); vm.discardDifferenceReview() }
        assertEquals(0, commits.get()); assertEquals(before, vm.state.value); assertSame(before.plan, vm.state.value.plan)
        main { vm.preview(context) }; await { !vm.state.value.busy }
        assertEquals(50, vm.state.value.plan!!.rows.differences.size); assertEquals(before.choices, vm.state.value.choices)
    }
    @Test fun savingTheWindowKeepsBulkAndIndividualChangesButStillRequiresFinalImport() {
        val commits = AtomicInteger()
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                choices: PortableImportChoices, limit: Int): PortableImportPreview = plan(choices, limit)
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
                progress: (String) -> Unit): PortableImportResult { commits.incrementAndGet(); return PortableImportResult(0, 0, 0) }
        })
        main { assertTrue(vm.beginDifferenceReview()); vm.chooseAllRecordFields(context, PortableImportChoices(), true) }
        await { !vm.state.value.busy }
        val bulk = vm.state.value.choices
        val individual = bulk.copy(fields = bulk.fields + ("row-0" to setOf("evidenceStatus")))
        main { vm.commit(context); assertTrue(vm.saveDifferenceReview(context, individual)) }
        await { !vm.state.value.busy }
        main { vm.discardDifferenceReview() }
        assertEquals(individual, vm.state.value.choices); assertTrue(vm.state.value.planValid); assertEquals(0, commits.get())
        main { vm.commit(context) }; await { vm.state.value.result != null }
        assertEquals(1, commits.get())
    }
}
