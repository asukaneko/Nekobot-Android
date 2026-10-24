package com.nekobot.app.ui.screens.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Seven synthetic memories with existing source differences exercise the former count-check failure. */
class PortableImportNextFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var prepared: PreparedPortableImport
    private lateinit var vm: SafePortableImportViewModel
    private val reads = AtomicInteger()
    private val commits = AtomicInteger()
    private var memoryCount = 7
    private var includeReasoning = false
    private val selected = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:companion_memories", "table:companion_sources"))

    private fun plan(choices: PortableImportChoices = PortableImportChoices(), limit: Int = 50): PortableImportPreview {
        val memories = (0 until memoryCount).map { i -> PortableImportRowDifference("memory-$i", "companion_memories", "合成记忆 ${i + 1}",
            listOf(PortableImportFieldDifference("content", "[PINNED] 合成内容 $i", "合成内容 $i")), memoryId = "id-$i") }
        val sources = (0..6).filter { "id-$it" !in choices.keepMemorySources }.map { i ->
            PortableImportRowDifference("source-$i", "companion_sources", "合成出处 ${i + 1}", listOf(PortableImportFieldDifference("quote", "old", "new"))) }
        val reasoning = if (includeReasoning) listOf(PortableImportRowDifference("history-reasoning", "local_messages", "合成历史思考",
            listOf(PortableImportFieldDifference("reasoning_content", "", "合成思考原文")))) else emptyList()
        val differences = memories + sources + reasoning
        val unresolved = memories.take(7).filter { it.memoryId !in choices.keepMemorySources && "content" !in choices.fields[it.key].orEmpty() }
        val issue = if (unresolved.isEmpty()) emptyList() else listOf("记忆正文、主体或归属与新来源不属于同一版本")
        val updated = memories.count { "content" in choices.fields[it.key].orEmpty() }
        val sourceUpdated = sources.count { "quote" in choices.fields[it.key].orEmpty() }
        val reasoningUpdated = if ("reasoning_content" in choices.fields["history-reasoning"].orEmpty()) 1 else 0
        return PortableImportPreview(PortableImportRowPlan("rows-${choices.hashCode()}",
            PortableImportCounts(different = differences.size, updated = updated + sourceUpdated + reasoningUpdated), differences.take(limit), issue, 0,
            tableCounts = mapOf("companion_memories" to PortableImportCounts(different = memoryCount, updated = updated),
                "companion_sources" to PortableImportCounts(different = sources.size, updated = sourceUpdated)) +
                if (includeReasoning) mapOf("local_messages" to PortableImportCounts(different = reasoning.size, updated = reasoningUpdated)) else emptyMap(),
            sourceConflictKeys = unresolved.mapTo(linkedSetOf()) { it.key }, sourceConflictMemoryCount = unresolved.size,
            skippedSourceRows = choices.keepMemorySources.size,
            sourceFieldChoices = (0..6).associate { "id-$it" to mapOf("source-$it" to setOf("quote")) },
            recordDifferenceKeys = differences.mapTo(linkedSetOf()) { it.key }),
            PortableImportCounts(), emptyList(), emptyList(), false, issue, emptyList(), "preview-${choices.hashCode()}", prepared.id,
            selected, choices, emptyMap(), emptyMap(), JsonObject(), null, false, emptySet(), "", emptyMap())
    }
    private fun start(large: Boolean = false, fail: Boolean = false) {
        compose.runOnUiThread {
            val resources = compose.activity.resources
            val config = android.content.res.Configuration(resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
            @Suppress("DEPRECATION") resources.updateConfiguration(config, resources.displayMetrics)
            val staging = File.createTempFile("next-import-", ".tmp", compose.activity.cacheDir).apply { delete(); mkdirs() }
            val zip = File(staging, "synthetic.zip").apply { writeBytes(byteArrayOf()) }
            val source = PortableArchivePreview("synthetic", "fixture", false, emptyList())
            prepared = PreparedPortableImport(source, "synthetic", PortableDataArchiveManager.ResolvedArchive(source, zip, listOf(zip)), emptyMap(), emptyList(), staging, "")
            vm = SafePortableImportViewModel(object : SafeImportOperations {
                override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>,
                    choices: PortableImportChoices, limit: Int): PortableImportPreview {
                    if (reads.incrementAndGet() > 1 && fail) error("合成核对失败，请重试")
                    return plan(choices, limit)
                }
                override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview,
                    progress: (String) -> Unit): PortableImportResult { commits.incrementAndGet(); return PortableImportResult(0, 0, 0) }
            })
            vm.state.value = SafeImportUiState(prepared = prepared, selected = selected, plan = plan(), planValid = true, step = SafeImportStep.CONFIRM)
        }
        compose.setContent { NekobotTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.6f else density.fontScale)) {
                Surface(Modifier.safeDrawingPadding()) { SafePortableImportPanel(vm = vm) }
            }
        } }
        compose.onNodeWithTag("safe-import-scroll").performScrollToNode(hasTestTag("import-review-related"))
        compose.onNodeWithTag("import-review-related").performClick(); ready()
    }
    private fun ready() = compose.waitUntil(15000) { !vm.state.value.busy }
    private fun next() { compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).performClick(); ready() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "import-next-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun updateFlow(large: Boolean) {
        start(large)
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick().assertIsSelected()
        assertEquals(1, reads.get()); compose.onAllNodes(isDialog()).assertCountEquals(1)
        screenshot(if (large) "large-choices" else "choices")
        next()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertNull(vm.state.value.error); assertTrue(vm.state.value.plan!!.issues.isEmpty())
        assertEquals(7, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        assertEquals(0, commits.get())
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_preview)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_start)).assertIsDisplayed()
        screenshot(if (large) "large-preview" else "preview")
        compose.onNodeWithTag("safe-import-execute").performClick(); ready()
        assertEquals(1, commits.get()); assertNotNull(vm.state.value.result)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun sevenUpdatesGoToPreviewThenOneStartActionImports() = updateFlow(false)
    @Test fun sevenUpdatesAndFinalActionRemainReachableWithLargeText() = updateFlow(true)
    @Test fun sevenSourceConflictsIncludeAllSixteenCoreTextRepairsInOneChoice() {
        memoryCount = 16
        start()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        next()
        assertTrue(vm.state.value.plan!!.issues.isEmpty())
        assertEquals(16, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        (0 until 16).forEach { assertEquals(setOf("content"), vm.state.value.choices.fields["memory-$it"]) }
        assertEquals(0, commits.get())
    }
    @Test fun aTextOnlyRepairCanStillBeKeptAfterChoosingAllSixteenUpdates() {
        memoryCount = 16
        start()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-memory-15-keep").performScrollTo().performClick()
        next()
        assertTrue(vm.state.value.plan!!.issues.isEmpty())
        assertEquals(15, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        assertEquals(setOf("id-15"), vm.state.value.choices.keepMemorySources)
        assertEquals(0, commits.get())
    }
    @Test fun theSameFileChoiceIncludesNativeReasoningRepairsAlongsideMemoryRepairs() {
        memoryCount = 16; includeReasoning = true
        start()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        next()
        assertTrue(vm.state.value.plan!!.issues.isEmpty())
        assertEquals(setOf("reasoning_content"), vm.state.value.choices.fields["history-reasoning"])
        assertEquals(16, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        assertEquals(1, vm.state.value.plan!!.rows.tableCounts["local_messages"]!!.updated)
        assertEquals(0, commits.get())
    }
    @Test fun bulkChoiceIncludesTextRepairsBeyondTheFirstFiftyDisplayedRecords() {
        memoryCount = 70
        start()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        next()
        assertTrue(vm.state.value.plan!!.issues.isEmpty())
        assertEquals(70, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        assertEquals(setOf("content"), vm.state.value.choices.fields["memory-69"])
        assertEquals(0, commits.get())
    }
    @Test fun keepingSevenMemoriesGoesStraightToPreviewWithoutWriting() {
        start(); compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().performClick(); next()
        assertEquals(7, vm.state.value.choices.keepMemorySources.size)
        assertTrue(vm.state.value.plan!!.issues.isEmpty()); assertEquals(0, commits.get())
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun individualKeepAfterBulkUpdateDoesNotMisidentifyTheDraftAsChangedData() {
        start(); compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-memory-0-keep").performScrollTo().performClick(); next()
        assertNull(vm.state.value.error); assertTrue(vm.state.value.planValid)
        assertEquals(setOf("id-0"), vm.state.value.choices.keepMemorySources)
        assertEquals(6, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        assertTrue(vm.state.value.plan!!.issues.isEmpty()); assertEquals(0, commits.get())
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun keepingOneDraftThenUpdatingAllDoesNotTriggerTheOldCountGuard() {
        start()
        compose.runOnIdle {
            val plan = vm.state.value.plan!!
            val row = plan.rows.differences.first { it.memoryId != null }
            val draft = vm.state.value.choices.withRecordChoice(row, false, plan.rows.sourceFieldChoices[row.memoryId].orEmpty())
            vm.chooseAllRecordFields(compose.activity, draft, true, plan.rows.sourceConflictKeys)
        }
        ready()
        assertNull(vm.state.value.error); assertTrue(vm.state.value.planValid)
        assertTrue(vm.state.value.choices.keepMemorySources.isEmpty())
        assertEquals(7, vm.state.value.plan!!.rows.tableCounts["companion_memories"]!!.updated)
        assertTrue(vm.state.value.plan!!.issues.isEmpty()); assertEquals(0, commits.get())
    }
    @Test fun failedNextKeepsTheErrorVisibleInTheComparisonAndDoesNotImport() {
        start(fail = true); compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-memory-6-keep").performScrollTo().performClick(); next()
        compose.onNodeWithTag("import-review-error").assertIsDisplayed()
        assertFalse(vm.state.value.planValid); assertEquals(0, commits.get())
        screenshot("error")
    }
    @After fun close() {
        if (::vm.isInitialized) compose.runOnIdle { ViewModelStore().apply { put("next", vm); clear() } }
        if (::prepared.isInitialized) prepared.close()
    }
}
