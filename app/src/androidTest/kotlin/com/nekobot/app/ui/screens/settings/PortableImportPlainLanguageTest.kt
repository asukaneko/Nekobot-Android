package com.nekobot.app.ui.screens.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** All previews and screenshots use synthetic content; no real archives, chat or network. */
class PortableImportPlainLanguageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var prepared: PreparedPortableImport
    private lateinit var vm: SafePortableImportViewModel
    private val commits = AtomicInteger()
    private val previews = AtomicInteger()
    private val release = CompletableDeferred<Unit>()
    private val selected = mapOf(PortableDataCategory.CONVERSATIONS to setOf("table:companion_memories", "table:companion_sources"),
        PortableDataCategory.CHARACTERS to setOf("table:local_characters"))
    private val related = setOf("row-686")

    @Before fun prepare() {
        compose.runOnUiThread {
            val resources = compose.activity.resources
            val configuration = android.content.res.Configuration(resources.configuration)
            configuration.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        val staging = File.createTempFile("plain-import-", ".tmp", compose.activity.cacheDir).apply { delete(); mkdirs() }
        val zip = File(staging, "synthetic.zip").apply { writeBytes(byteArrayOf()) }
        val source = PortableArchivePreview("synthetic", "fixture", false, emptyList())
        prepared = PreparedPortableImport(source, "synthetic", PortableDataArchiveManager.ResolvedArchive(source, zip, listOf(zip)),
            emptyMap(), emptyList(), staging, "")
    }
    @After fun close() {
        release.complete(Unit)
        if (::vm.isInitialized) compose.runOnIdle { ViewModelStore().apply { put("plain", vm); clear() } }
        prepared.close()
    }
    private fun plan(choices: PortableImportChoices = PortableImportChoices(), limit: Int = 50, sameNames: Int = 0): PortableImportPreview {
        val kept = "memory-686" in choices.keepMemorySources
        val updated = (0..687).count { choices.fields["row-$it"].orEmpty().isNotEmpty() }
        val resolved = kept || choices.fields["row-686"].orEmpty().containsAll(setOf("content", "subject", "provenance"))
        val issue = if (sameNames > 0) "发现同名但ID不同的角色；请明确允许新增独立副本，或取消该角色"
            else "记忆正文、主体或归属与新来源不属于同一版本，请同时选择对应记忆字段，或取消本次来源导入"
        val differences = (0 until minOf(688, limit)).map { i ->
            PortableImportRowDifference("row-$i", if (i == 687) "local_characters" else "companion_memories", "合成记忆 $i",
                if (i == 686) listOf(PortableImportFieldDifference("content", "喜欢旧版合成茶", "喜欢新版合成茶"),
                    PortableImportFieldDifference("subject", "旧称呼", "新称呼"), PortableImportFieldDifference("provenance", "old", "new"))
                else listOf(PortableImportFieldDifference("provenance", "old", "new")), memoryId = "memory-$i".takeIf { i < 687 })
        }
        val memoryUpdated = choices.fields.keys.count { it != "row-687" && choices.fields[it].orEmpty().isNotEmpty() }
        return PortableImportPreview(
            rows = PortableImportRowPlan("rows-$updated-$kept", PortableImportCounts(added = if (kept) 2881 else 2882, different = 688, updated = updated),
                differences, if (resolved) emptyList() else listOf(issue), 0, sameNameCopies = sameNames,
                tableCounts = mapOf("companion_sources" to PortableImportCounts(added = if (kept) 2881 else 2882),
                    "companion_memories" to PortableImportCounts(different = 687, updated = memoryUpdated),
                    "local_characters" to PortableImportCounts(different = 1, updated = if ("row-687" in choices.fields) 1 else 0)),
                sourceConflictKeys = if (resolved || sameNames > 0) emptySet() else related,
                sourceConflictMemoryCount = if (resolved || sameNames > 0) 0 else 1, skippedSourceRows = if (kept) 1 else 0,
                recordDifferenceKeys = (0..687).mapTo(linkedSetOf()) { "row-$it" }),
            fileCounts = PortableImportCounts(), fileDifferences = emptyList(), settingDifferences = emptyList(), storyDifferent = false,
            issues = if (resolved) emptyList() else listOf(issue), dependencyOptions = emptyList(), fingerprint = "preview-$updated-$kept",
            preparedId = prepared.id, selected = selected, choices = choices, fileBefore = emptyMap(), fileAfter = emptyMap(),
            settingsBefore = JsonObject(), settingsIncoming = null, useStory = false, storySessionIds = emptySet(),
            credentialFingerprint = "", credentialTargets = emptyMap())
    }
    private fun start(large: Boolean = false, delayed: Boolean = false) {
        compose.runOnUiThread {
            vm = SafePortableImportViewModel(object : SafeImportOperations {
                override suspend fun preview(context: Context, prepared: PreparedPortableImport,
                    selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview {
                    previews.incrementAndGet(); if (delayed) release.await(); return plan(choices, limit)
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
    }
    private fun awaitReady() = compose.waitUntil(15000) { !vm.state.value.busy }
    private fun openRelated() {
        compose.onNodeWithTag("safe-import-scroll").performScrollToNode(hasTestTag("import-review-related"))
        compose.onNodeWithTag("import-review-related").performClick()
        awaitReady()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(150)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
            File(compose.activity.cacheDir, "import-review-$name.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun screenshotCountsIdentifySourcesMemoriesAndCharactersAndNoDuplicateOption() {
        start()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_sources)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_added, 2882)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_kept, 687)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_kept, 1)).assertExists()
        screenshot("summary")
        compose.onNodeWithTag("safe-import-scroll").performScrollToNode(hasTestTag("import-source-conflict"))
        compose.onNodeWithTag("import-review-same-name").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_source_message, 1)).assertExists()
        assertEquals(0, commits.get())
    }
    @Test fun sourceConflictDialogHasPlainExplanationAndDirectActionWithoutDuplicateConsent() {
        var reviews = 0
        compose.setContent { NekobotTheme {
            PortableImportIssueDialog(plan(), PortableImportChoices(), false, {}, {}, { reviews++ }, {}, { _, _ -> })
        } }
        compose.onNodeWithTag("import-review-same-name").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_source_message, 1)).assertExists()
        screenshot("issue")
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_compare)).performClick()
        assertEquals(1, reviews)
    }
    @Test fun directActionIncludesAllChangesButPagesTheirDisplayAndDoesNotChooseForUser() {
        start()
        openRelated()
        compose.onNodeWithText("合成记忆 0").assertExists()
        compose.onNodeWithText("合成记忆 686").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_related_scope, 688)).assertExists()
        assertEquals(688, vm.state.value.plan!!.rows.sourceReviewKeys.size)
        compose.onNodeWithTag("safe-difference-memory-0-keep").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).assertIsNotEnabled()
        assertTrue(vm.state.value.choices.fields.isEmpty()); assertEquals(0, commits.get())
        screenshot("related")
    }
    @Test fun keepingAllRecordsSkipsMatchingNewSourcesWithoutWriting() {
        start()
        openRelated()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().performClick(); awaitReady()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).performClick(); awaitReady()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_skipped_sources, 1)).performScrollTo().assertIsDisplayed()
        assertTrue(vm.state.value.plan!!.issues.isEmpty())
        assertEquals(50, vm.state.value.plan!!.rows.differences.size)
        assertEquals((0..686).mapTo(linkedSetOf()) { "memory-$it" }, vm.state.value.choices.keepMemorySources)
        assertTrue(vm.state.value.choices.fields.isEmpty()); assertEquals(0, commits.get())
        screenshot("kept")
    }
    @Test fun anIndividualOverrideAfterKeepingAllLeavesTheOther687ChangesKept() {
        start()
        openRelated()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-memory-0-replace").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).performClick(); awaitReady()
        assertEquals(setOf("row-0"), vm.state.value.choices.fields.keys)
        assertEquals(686, vm.state.value.choices.keepMemorySources.size)
        assertEquals(1, vm.state.value.plan!!.rows.counts.updated)
        assertEquals(50, vm.state.value.plan!!.rows.differences.size)
        assertTrue(vm.state.value.plan!!.issues.isEmpty()); assertEquals(0, commits.get())
        screenshot("updated")
    }
    @Test fun cancellingACompletedRelatedBulkChoiceRestoresOriginalPreviewAndDoesNotWrite() {
        start()
        val before = vm.state.value
        openRelated()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().performClick(); awaitReady()
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(before, vm.state.value) }
        assertEquals(0, commits.get())
    }
    @Test fun repeatedSourceReviewRequestsWhileLoadingCannotStartASecondReadOrImport() {
        start(delayed = true)
        compose.onNodeWithTag("safe-import-scroll").performScrollToNode(hasTestTag("import-review-related"))
        compose.onNodeWithTag("import-review-related").performClick()
        compose.waitUntil(5000) { previews.get() == 1 }
        compose.runOnIdle { assertFalse(vm.beginSourceDifferenceReview(compose.activity)); vm.commit(compose.activity) }
        assertEquals(1, previews.get()); assertEquals(0, commits.get())
        release.complete(Unit); awaitReady()
    }
    @Test fun realDuplicateCharactersAloneShowTheExplicitIndependentCopyChoice() {
        val p = plan(sameNames = 1)
        compose.setContent { NekobotTheme {
            PortableImportIssueDialog(p, PortableImportChoices(), false, {}, {}, {}, {}, { _, _ -> })
        } }
        compose.onNodeWithTag("import-review-same-name").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_review_duplicate_consent, 1)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        screenshot("duplicate")
    }
    @Test fun smallViewportLargeFontKeepsDirectReviewBothDecisionsAndSaveReachable() {
        start(large = true)
        openRelated()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-memory-0-keep").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("safe-difference-memory-0-replace").performScrollTo().assertIsDisplayed()
        val visibleBounds = compose.onNodeWithTag("safe-difference-memory-0-replace").fetchSemanticsNode().boundsInRoot
        assertTrue("Choice must have a visible touch target after scrolling", visibleBounds.height >= 48f * compose.activity.resources.displayMetrics.density - 1f)
        screenshot("small-large-text")
        compose.onNodeWithTag("safe-difference-memory-0-keep").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).assertIsDisplayed().performClick(); awaitReady()
        assertEquals(0, commits.get())
    }
}
