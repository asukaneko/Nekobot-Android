package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.nekobot.app.data.local.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Delayed operations never touch a database or call a model. */
class PortableImportReviewStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prepared: PreparedPortableImport
    private lateinit var vm: SafePortableImportViewModel
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val selected = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:companion_memories"))
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun gate() = CompletableDeferred<Unit>().also(gates::add)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < until) { instrumentation.waitForIdleSync(); SystemClock.sleep(10) }
        assertTrue("Synthetic operation timed out", condition())
    }
    @Before fun prepare() {
        val staging = File.createTempFile("portable-review-", ".tmp", context.cacheDir).apply { delete(); mkdirs() }
        val zip = File(staging, "synthetic.zip").apply { writeBytes(byteArrayOf()) }
        val source = PortableArchivePreview("synthetic", "fixture", false, emptyList())
        prepared = PreparedPortableImport(source, "synthetic", PortableDataArchiveManager.ResolvedArchive(source, zip, listOf(zip)), emptyMap(), emptyList(), staging, "")
    }
    @After fun close() {
        gates.forEach { it.complete(Unit) }
        if (::vm.isInitialized) main { ViewModelStore().apply { put("review", vm); clear() } }
        instrumentation.waitForIdleSync()
        prepared.close()
    }
    private fun plan(choices: PortableImportChoices = PortableImportChoices(), updated: Int = 0) = PortableImportPreview(
        rows = PortableImportRowPlan("rows", PortableImportCounts(different = 1, updated = updated), emptyList(), emptyList(), 0),
        fileCounts = PortableImportCounts(), fileDifferences = emptyList(), settingDifferences = emptyList(), storyDifferent = false,
        issues = emptyList(), dependencyOptions = emptyList(), fingerprint = "synthetic-$updated", preparedId = prepared.id,
        selected = selected, choices = choices, fileBefore = emptyMap(), fileAfter = emptyMap(), settingsBefore = JsonObject(),
        settingsIncoming = null, useStory = false, storySessionIds = emptySet(), credentialFingerprint = "", credentialTargets = emptyMap())
    private fun start(operations: SafeImportOperations = object : SafeImportOperations {}) {
        main {
            vm = SafePortableImportViewModel(operations)
            vm.state.value = SafeImportUiState(prepared = prepared, selected = selected, plan = plan(), planValid = true, step = SafeImportStep.CONFIRM)
        }
    }
    @Test fun sameValuesAndEmptyFieldEntriesKeepTheValidPreviewWithoutRecomputing() {
        var previews = 0
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview { previews++; return plan(choices) }
        })
        val before = vm.state.value.plan
        main {
            vm.select(PortableDataCategory.CONVERSATIONS, setOf("table:companion_memories"), true)
            vm.choices(PortableImportChoices(fields = mapOf("memory" to emptySet())))
            vm.saveChoices(context, PortableImportChoices(fields = mapOf("memory" to emptySet())))
        }
        assertTrue(vm.state.value.planValid); assertSame(before, vm.state.value.plan); assertEquals(0, previews)
    }
    @Test fun changingADraftThenChangingItBackDoesNotInvalidateOrWrite() {
        start()
        val chosen = PortableImportChoices(fields = mapOf("memory" to setOf("content")))
        val reverted = chosen.copy(fields = mapOf("memory" to emptySet()))
        main { vm.saveChoices(context, reverted) }
        assertTrue(vm.state.value.planValid); assertEquals(PortableImportChoices(), vm.state.value.choices)
    }
    @Test fun savingAChangedChoiceRecomputesAutomaticallyAndDoesNotCommit() {
        val release = gate(); var commits = 0; var previews = 0
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview { previews++; release.await(); return plan(choices, 1) }
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview, progress: (String) -> Unit): PortableImportResult { commits++; return PortableImportResult(0, 0, 0) }
        })
        val choices = PortableImportChoices(fields = mapOf("memory" to setOf("content")))
        main { vm.saveChoices(context, choices); vm.commit(context) }
        assertTrue(vm.state.value.busy); assertFalse(vm.state.value.planValid); assertEquals(0, commits)
        release.complete(Unit); await { vm.state.value.planValid }
        assertEquals(1, previews); assertEquals(1, vm.state.value.plan!!.rows.counts.updated); assertEquals(choices, vm.state.value.choices)
    }
    @Test fun aFailedRefreshCannotSubmitThePreviousPlan() {
        var commits = 0
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview { error("Synthetic read failure") }
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview, progress: (String) -> Unit): PortableImportResult { commits++; return PortableImportResult(0, 0, 0) }
        })
        main { vm.saveChoices(context, PortableImportChoices(files = setOf("synthetic-file"))) }
        await { !vm.state.value.busy }
        main { vm.commit(context) }
        assertFalse(vm.state.value.planValid); assertEquals("Synthetic read failure", vm.state.value.error); assertEquals(0, commits)
    }
    @Test fun cancelledOlderPreviewCannotReplaceTheNewerSuccessfulChoice() {
        val old = gate(); var calls = 0
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview {
                calls++; if (calls == 1) return withContext(NonCancellable) { old.await(); plan(choices, 0) }
                return plan(choices, 1)
            }
        })
        main { vm.preview(context) }; await { calls == 1 }
        val choices = PortableImportChoices(files = setOf("new-choice"))
        main { vm.cancel(); vm.saveChoices(context, choices) }
        await { vm.state.value.planValid }
        old.complete(Unit); instrumentation.waitForIdleSync(); SystemClock.sleep(100); instrumentation.waitForIdleSync()
        assertTrue(vm.state.value.planValid); assertEquals(1, vm.state.value.plan!!.rows.counts.updated); assertEquals(choices, vm.state.value.choices); assertNull(vm.state.value.error)
    }
    @Test fun lateFailureFromAnOlderCancelledPreviewCannotInvalidateTheNewResult() {
        val old = gate(); var calls = 0
        start(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview {
                calls++; if (calls == 1) return withContext(NonCancellable) { old.await(); error("Old synthetic failure") }
                return plan(choices, 1)
            }
        })
        main { vm.preview(context) }; await { calls == 1 }
        main { vm.cancel(); vm.preview(context) }; await { vm.state.value.planValid }
        old.complete(Unit); instrumentation.waitForIdleSync(); SystemClock.sleep(100); instrumentation.waitForIdleSync()
        assertTrue(vm.state.value.planValid); assertFalse(vm.state.value.busy); assertNull(vm.state.value.error)
    }
    @Test fun duplicateFinalSubmitCallsExecuteOnlyOnceAndStayConsumedAfterCompletion() {
        val release = gate(); var commits = 0
        start(object : SafeImportOperations {
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview, progress: (String) -> Unit): PortableImportResult { commits++; release.await(); return PortableImportResult(0, 0, 0) }
        })
        main { vm.commit(context); vm.commit(context) }; await { commits == 1 }
        assertFalse(vm.state.value.planValid)
        release.complete(Unit); await { vm.state.value.result != null }
        main { vm.commit(context) }; assertEquals(1, commits)
    }
    @Test fun cancellingAWriteKeepsTheBusyGuardUntilRecoveryFinishes() {
        val release = gate(); var commits = 0; var previews = 0
        start(object : SafeImportOperations {
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview, progress: (String) -> Unit): PortableImportResult = withContext(NonCancellable) { commits++; release.await(); PortableImportResult(0, 0, 0) }
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview { previews++; return plan() }
        })
        main { vm.commit(context) }; await { commits == 1 }
        main { vm.cancel(); vm.preview(context) }
        assertTrue(vm.state.value.busy); assertEquals(0, previews)
        release.complete(Unit); await { !vm.state.value.busy }
    }
    @Test fun returningToContentsPreservesTheValidPlanWithoutWriting() {
        start()
        val before = vm.state.value.plan
        main { assertTrue(vm.back()) }
        assertEquals(SafeImportStep.CONTENTS, vm.state.value.step); assertTrue(vm.state.value.planValid); assertSame(before, vm.state.value.plan)
    }
    @Test fun aPlanThatDoesNotMatchTheCurrentChoicesCannotBeSubmitted() {
        var commits = 0
        start(object : SafeImportOperations {
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview, progress: (String) -> Unit): PortableImportResult { commits++; return PortableImportResult(0, 0, 0) }
        })
        main { vm.state.value = vm.state.value.copy(choices = PortableImportChoices(files = setOf("unreviewed"))); vm.commit(context) }
        assertEquals(0, commits)
    }
}
