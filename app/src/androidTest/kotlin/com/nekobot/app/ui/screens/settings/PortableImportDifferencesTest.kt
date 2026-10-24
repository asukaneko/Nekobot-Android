package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.db.*
import com.nekobot.app.ui.theme.NekobotTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.Locale

/** Real archive + SQLite + attachment conflicts, using an isolated synthetic profile only. */
class PortableImportDifferencesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var original: String? = null
    private var profile: String? = null
    private lateinit var vm: SafePortableImportViewModel
    private lateinit var archive: File
    private lateinit var workspace: File
    private lateinit var attachment: File
    private lateinit var characterId: String
    private val currentName = "软件中保留的合成角色"
    private val currentText = "软件里后来修改的合成说明，默认应当保留。"
    private val incomingText = "资料包里的合成说明，只有明确选择覆盖并最终确认后才使用。"
    private fun db() = NekobotDatabase.get(compose.activity, requireNotNull(profile))
    @Before fun setup() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        original = ServiceContainer.prefs.activeDbName
        profile = "portable_diff_${UUID.randomUUID().toString().replace("-", "")}"
        ServiceContainer.switchLocalDb(requireNotNull(profile))
        val sessionId = UUID.randomUUID().toString()
        characterId = UUID.randomUUID().toString()
        workspace = File(compose.activity.filesDir, "workspace/$sessionId").apply { mkdirs() }
        attachment = File(workspace, "合成迁入说明.txt").apply { writeText(incomingText) }
        val stamp = "2026-01-01T00:00:00Z"
        runBlocking {
            db().characterDao().upsert(LocalCharacterEntity(characterId, "资料包里的合成角色", createdAt = stamp, updatedAt = stamp))
            db().sessionDao().upsert(LocalSessionEntity(sessionId, "合成春日会话", characterId = characterId, createdAt = stamp, updatedAt = stamp))
            val out = ByteArrayOutputStream()
            PortableDataArchiveManager(compose.activity).export(setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS, PortableDataCategory.WORKSPACE), "", out, "synthetic")
            archive = File(compose.activity.cacheDir, "portable-difference-${UUID.randomUUID()}.zip").apply { writeBytes(out.toByteArray()) }
            db().openHelper.writableDatabase.execSQL("UPDATE local_characters SET name=? WHERE id=?", arrayOf(currentName, characterId))
        }
        attachment.writeText(currentText)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = compose.activity.resources
            val configuration = Configuration(resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        vm = ViewModelProvider(compose.activity)[SafePortableImportViewModel::class.java]
    }
    @After fun cleanup() {
        if (::vm.isInitialized) vm.state.value.prepared?.close()
        original?.let { ServiceContainer.switchLocalDb(it) }
        profile?.let { ServiceContainer.prefs.removeDbProfile(it); NekobotDatabase.deleteProfileFile(compose.activity, it) }
        if (::archive.isInitialized) archive.delete()
        if (::workspace.isInitialized) workspace.deleteRecursively()
    }
    private fun launch(small: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (small) 1.6f else density.fontScale)) {
                NekobotTheme(darkTheme = false) { Surface {
                    if (small) Box(Modifier.requiredSize(320.dp, 560.dp)) { DataPortabilityScreen(onBack = {}) }
                    else DataPortabilityScreen(onBack = {})
                } }
            }
        }
        compose.runOnIdle { vm.inspect(compose.activity, Uri.fromFile(archive), "") }
        compose.waitUntil(30000) { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        compose.onNodeWithTag("safe-import-execute").performClick()
        awaitPreview()
        assertEquals(1, vm.state.value.plan!!.fileCounts.different)
        assertTrue(vm.state.value.plan!!.issues.isEmpty())
    }
    private fun awaitPreview() {
        compose.waitUntil(30000) { !vm.state.value.busy && (vm.state.value.planValid || vm.state.value.error != null) }
        assertNull(vm.state.value.error); assertTrue(vm.state.value.planValid)
    }
    private fun openDifferences() {
        val label = compose.activity.getString(R.string.safe_import_changes)
        // LazyColumn does not compose the off-screen action on a small, large-text viewport.
        compose.onNodeWithTag("safe-import-scroll").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithTag("safe-difference-row-0-name-keep").performScrollTo().assertIsSelected()
    }
    private fun dialogAction(resource: Int) = compose.onNode(hasText(compose.activity.getString(resource)) and hasClickAction() and hasAnyAncestor(isDialog()))
    private fun unchanged() {
        assertEquals(currentText, attachment.readText())
        runBlocking { assertEquals(currentName, db().characterDao().getById(characterId)?.name) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 2000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(compose.activity.cacheDir, "portable-difference-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun openingClosingCancellingAndRevertingDraftChoicesKeepTheOriginalValidPreview() {
        launch()
        val before = vm.state.value.plan
        openDifferences()
        dialogAction(R.string.common_cancel).performClick()
        assertSame(before, vm.state.value.plan); assertTrue(vm.state.value.planValid)
        openDifferences()
        compose.onNodeWithTag("safe-difference-row-0-name-replace").performScrollTo().performClick()
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.common_close)) and hasAnyAncestor(isDialog())).performClick()
        assertTrue(vm.state.value.choices.fields.isEmpty()); assertSame(before, vm.state.value.plan); unchanged()
        openDifferences()
        compose.onNodeWithTag("safe-difference-row-0-name-replace").performScrollTo().performClick()
        compose.onNodeWithTag("safe-difference-row-0-name-keep").performScrollTo().performClick()
        dialogAction(R.string.portability_ui_next).performClick()
        assertSame(before, vm.state.value.plan); assertTrue(vm.state.value.planValid); unchanged()
    }
    @Test fun cancellingTheWindowAfterCompletedBulkRestoresTheOpeningSelectionAndPreview() {
        launch()
        val before = vm.state.value
        openDifferences()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performScrollTo().performClick()
        assertEquals(0, vm.state.value.plan!!.rows.counts.updated)
        assertTrue(vm.state.value.choices.fields.isEmpty()); unchanged()
        dialogAction(R.string.common_cancel).performClick()
        assertEquals(before.choices, vm.state.value.choices); assertSame(before.plan, vm.state.value.plan)
        assertTrue(vm.state.value.planValid); unchanged()
        openDifferences()
        compose.onNodeWithTag("safe-difference-row-0-name-keep").assertIsSelected()
        dialogAction(R.string.common_cancel).performClick()
    }
    @Test fun savingAFileReplacementRefreshesSummaryButOnlyFinalConfirmationWrites() {
        launch(); openDifferences()
        screenshot("field-comparison")
        compose.onNodeWithTag("safe-difference-file-0-keep").performScrollTo().assertIsSelected()
        compose.onNodeWithText("合成迁入说明.txt").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_difference_session, "合成春日会话"), substring = true).assertExists()
        val difference = vm.state.value.plan!!.fileDifferences.single()
        assertEquals(attachment.length(), difference.currentBytes)
        assertEquals(incomingText.toByteArray().size.toLong(), difference.incomingBytes)
        assertEquals("合成春日会话", difference.owner.session)
        assertNotEquals(difference.currentHash, difference.incomingHash)
        compose.onNodeWithTag("safe-difference-file-0-replace").performScrollTo().performClick()
        screenshot("file-comparison")
        dialogAction(R.string.portability_ui_next).performClick(); awaitPreview()
        assertEquals(1, vm.state.value.plan!!.fileCounts.updated); assertEquals(0, vm.state.value.plan!!.rows.counts.updated)
        unchanged()
        compose.onNodeWithTag("portability-back").performClick()
        compose.onNodeWithTag("safe-import-execute").performClick(); awaitPreview()
        assertEquals(setOf(difference.key), vm.state.value.choices.files)
        screenshot("valid-summary")
        unchanged()
        compose.onNodeWithTag("safe-import-execute").performClick()
        compose.waitUntil(30000) { vm.state.value.result != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals(incomingText, attachment.readText())
        runBlocking { assertEquals(currentName, db().characterDao().getById(characterId)?.name); db().openHelper.readableDatabase.query("SELECT COUNT(*) FROM local_characters").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) } }
        assertEquals(1, vm.state.value.result!!.importedFiles); assertEquals(0, vm.state.value.result!!.updatedRows)
        screenshot("result")
    }
    @Test fun defaultKeepDoesNotReplaceTheConflictingFileOrTheLaterCharacterName() {
        launch()
        compose.onNodeWithTag("safe-import-execute").performClick()
        compose.waitUntil(30000) { vm.state.value.result != null || vm.state.value.error != null }
        assertNull(vm.state.value.error); unchanged()
        assertEquals(0, vm.state.value.result!!.importedFiles); assertEquals(0, vm.state.value.result!!.updatedRows)
        assertTrue(vm.state.value.result!!.keptItems >= 2)
    }
    @Test fun fileNamesAndBothExplicitOptionsStayReachableWithSmallScreenAndLargeText() {
        launch(small = true); openDifferences()
        compose.onNodeWithTag("safe-difference-file-0-keep").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("safe-difference-file-0-replace").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed().performClick()
        screenshot("small-large-text")
        dialogAction(R.string.portability_ui_next).assertIsDisplayed().performClick(); awaitPreview()
        unchanged()
    }
    @Test fun recalculationHidesTheOldCountersAndFailureCannotOpenFinalConfirmation() {
        val manager = PortableDataArchiveManager(compose.activity)
        val prepared = runBlocking { archive.inputStream().use { manager.prepareImport(it, "") } }
        val selected = prepared.preview.categories.associate { summary -> summary.category to summary.details.filter { it.key != "plot_story" }.mapTo(linkedSetOf()) { it.key } }
        val plan = runBlocking { manager.previewImport(prepared, selected, PortableImportChoices()) }
        val release = CompletableDeferred<Unit>()
        var commits = 0
        vm = SafePortableImportViewModel(object : SafeImportOperations {
            override suspend fun preview(context: Context, prepared: PreparedPortableImport, selected: Map<PortableDataCategory, Set<String>>, choices: PortableImportChoices, limit: Int): PortableImportPreview { release.await(); error("合成核对读取失败") }
            override suspend fun commit(context: Context, prepared: PreparedPortableImport, plan: PortableImportPreview, progress: (String) -> Unit): PortableImportResult { commits++; return PortableImportResult(0, 0, 0) }
        })
        compose.runOnIdle { vm.state.value = SafeImportUiState(prepared = prepared, selected = selected,
            plan = plan.copy(rows = plan.rows.copy(counts = PortableImportCounts(added = 98765),
                tableCounts = mapOf("local_characters" to PortableImportCounts(added = 98765)))), planValid = true, step = SafeImportStep.CONFIRM) }
        compose.setContent { NekobotTheme { SafePortableImportPanel(vm = vm) } }
        compose.onNodeWithText("98765", substring = true).assertExists()
        compose.runOnIdle { vm.saveChoices(compose.activity, PortableImportChoices(files = setOf(plan.fileDifferences.single().key))) }
        compose.onNodeWithText("98765", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("safe-import-summary-pending").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { vm.commit(compose.activity) }
        release.complete(Unit)
        compose.waitUntil(10000) { !vm.state.value.busy }
        assertFalse(vm.state.value.planValid); assertEquals(0, commits); unchanged()
        compose.onNodeWithText(compose.activity.getString(R.string.safe_import_refresh)).assertIsDisplayed()
        compose.onNodeWithTag("safe-import-execute").performClick()
        compose.waitUntil(10000) { !vm.state.value.busy }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(0, commits); unchanged()
    }
}
