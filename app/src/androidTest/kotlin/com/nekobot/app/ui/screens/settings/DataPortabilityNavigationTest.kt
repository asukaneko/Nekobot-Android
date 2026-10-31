package com.nekobot.app.ui.screens.settings

import android.net.Uri
import android.os.Build
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.runBlocking

/** Only an isolated emulator and synthetic profiles; no model calls or real user files. */
class DataPortabilityNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var original: String? = null
    private var profile: String? = null
    private var oldScale = 1f
    private var oldFollow = false
    private var prepared: PreparedPortableImport? = null
    private val outputFiles = mutableListOf<File>()
    private lateinit var importVm: SafePortableImportViewModel
    private lateinit var exportVm: DataPortabilityViewModel
    private val registry = object : ActivityResultRegistry() {
        var calls = 0
        var request = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            calls++; request = requestCode
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    @Before fun isolate() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        original = ServiceContainer.prefs.activeDbName
        oldScale = ServiceContainer.prefs.fontScale; oldFollow = ServiceContainer.prefs.followSystemFontScale
        profile = "portable_nav_${UUID.randomUUID().toString().replace("-", "")}"
        ServiceContainer.switchLocalDb(requireNotNull(profile))
        importVm = ViewModelProvider(compose.activity)[SafePortableImportViewModel::class.java]
        exportVm = ViewModelProvider(compose.activity)[DataPortabilityViewModel::class.java]
        compose.activity.runOnUiThread {
            val resources = compose.activity.resources
            @Suppress("DEPRECATION")
            resources.updateConfiguration(Configuration(resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }, resources.displayMetrics)
        }
    }
    @After fun cleanup() {
        prepared?.close()
        outputFiles.forEach { it.delete() }
        ServiceContainer.prefs.fontScale = oldScale; ServiceContainer.prefs.followSystemFontScale = oldFollow
        original?.let { ServiceContainer.switchLocalDb(it) }
        profile?.let { ServiceContainer.prefs.removeDbProfile(it); NekobotDatabase.deleteProfileFile(compose.activity, it) }
    }
    private fun show(small: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                NekobotTheme(darkTheme = false) {
                    if (small) Box(Modifier.requiredSize(320.dp, 560.dp).testTag("portable-small-root")) { DataPortabilityScreen(onBack = {}) }
                    else DataPortabilityScreen(onBack = {})
                }
            }
        }
        compose.waitUntil(15000) { exportVm.current.value.size == PortableDataCategory.entries.size }
    }
    private fun fixture(step: SafeImportStep = SafeImportStep.CONTENTS) {
        val staging = File.createTempFile("portable-nav-", ".tmp", compose.activity.cacheDir).apply { delete(); mkdir() }
        val zip = File(staging, "synthetic.zip").apply { writeBytes(byteArrayOf()) }
        val details = listOf(PortableCategoryDetail("table:local_messages", 2), PortableCategoryDetail("table:companion_memories", 1))
        val preview = PortableArchivePreview("synthetic", "fixture", false, listOf(PortableCategorySummary(PortableDataCategory.CONVERSATIONS, 3, 0, details)))
        prepared = PreparedPortableImport(preview, "synthetic", PortableDataArchiveManager.ResolvedArchive(preview, zip, listOf(zip)), emptyMap(), emptyList(), staging, "")
        importVm.state.value = SafeImportUiState(prepared = prepared, step = step, sourceUri = Uri.fromFile(zip),
            fileName = "synthetic.zip", passwordInput = "synthetic-only-password",
            selected = mapOf(PortableDataCategory.CONVERSATIONS to details.mapTo(linkedSetOf()) { it.key }))
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "portable-tabs-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun defaultPageHasUnifiedNameAndOnlyImportTask() {
        show()
        assertEquals(compose.activity.getString(R.string.portability_title), compose.activity.getString(R.string.settings_data_portability))
        compose.onNodeWithText(compose.activity.getString(R.string.portability_title)).assertIsDisplayed()
        compose.onNodeWithTag("portability-tab-import").assertIsSelected()
        compose.onNodeWithTag("portability-export-action").assertDoesNotExist()
        compose.onNodeWithTag("safe-import-choose").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(160.dp)
        screenshot("file")
    }

    @Test fun tabsRetainIndependentSelectionsFileAndPasswordWithoutShowingBothTasks() {
        fixture(); show()
        compose.onNodeWithTag("safe-import-conversations-data").performScrollTo().performClick()
        compose.onNodeWithTag("safe-import-conversations-memory").assertIsOn()
        compose.onNodeWithTag("portability-tab-export").performClick()
        compose.onNodeWithTag("safe-import-conversations-memory").assertDoesNotExist()
        compose.runOnIdle { exportVm.exportUi.selection = setOf(PortableDataCategory.APP_SETTINGS.id); exportVm.exportUi.password = "synthetic-export-password" }
        compose.onNodeWithTag("portability-tab-import").performClick()
        compose.onNodeWithTag("safe-import-conversations-data").assertIsOff()
        compose.onNodeWithTag("safe-import-conversations-memory").assertIsOn()
        compose.runOnIdle {
            assertEquals("synthetic.zip", importVm.state.value.fileName)
            assertEquals("synthetic-only-password", importVm.state.value.passwordInput)
            assertEquals("synthetic-export-password", exportVm.exportUi.password)
            assertEquals(setOf(PortableDataCategory.APP_SETTINGS.id), exportVm.exportUi.selection)
        }
        screenshot("contents")
    }

    @Test fun filePickerOpensOnceAndCancelPreservesThePreparedFile() {
        fixture(SafeImportStep.FILE); show()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_change_file)).performScrollTo().performClick()
        compose.onNodeWithTag("safe-import-choose").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, registry.calls)
        compose.runOnIdle { registry.dispatchResult<Uri?>(registry.request, null) }
        compose.onNodeWithTag("safe-import-choose").assertIsEnabled().performClick()
        compose.onNodeWithTag("safe-import-conversations-memory").assertIsOn()
        compose.runOnIdle { assertSame(prepared, importVm.state.value.prepared); assertEquals("synthetic-only-password", importVm.state.value.passwordInput) }
    }

    @Test fun returningToFileKeepsSelectionsAndDoesNotWriteData() {
        fixture(); show()
        compose.onNodeWithTag("safe-import-conversations-data").performScrollTo().performClick()
        compose.onNodeWithTag("portability-back").performClick()
        compose.onNodeWithTag("safe-import-step-file").assertIsDisplayed()
        compose.onNodeWithTag("safe-import-conversations-memory").assertDoesNotExist()
        compose.onNodeWithTag("safe-import-choose").performClick()
        compose.onNodeWithTag("safe-import-conversations-data").assertIsOff()
        compose.onNodeWithTag("safe-import-conversations-memory").assertIsOn()
        compose.runOnIdle { assertNull(importVm.state.value.result); assertFalse(importVm.state.value.busy) }
    }

    @Test fun exportStillOffersAllFourteenCategoriesOnSmallLargeTextScreen() {
        ServiceContainer.prefs.fontScale = 1.6f; ServiceContainer.prefs.followSystemFontScale = false
        show(small = true)
        compose.onNodeWithTag("portability-tab-export").performClick()
        PortableDataCategory.entries.forEach { category ->
            compose.onNodeWithTag("portability-export-scroll").performScrollToNode(hasTestTag("portability-export-${category.id}"))
            compose.onNodeWithTag("portability-export-${category.id}").assertIsDisplayed()
        }
        val button = compose.onNodeWithTag("portability-export-action")
        button.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue(button.getUnclippedBoundsInRoot().bottom <= compose.onNodeWithTag("portable-small-root").getUnclippedBoundsInRoot().bottom)
        assertTrue(compose.onNodeWithTag("portability-export-scroll").getUnclippedBoundsInRoot().bottom <= button.getUnclippedBoundsInRoot().top)
        screenshot("small-large-text")
    }

    @Test fun credentialPasswordCannotBeCollapsedAndExportPickerCannotOpenTwice() {
        show()
        compose.onNodeWithTag("portability-tab-export").performClick()
        compose.onNodeWithTag("portability-export-scroll").performScrollToNode(hasTestTag("portability-export-credentials"))
        compose.onNodeWithTag("portability-export-credentials").performClick()
        compose.onNodeWithTag("portability-export-scroll").performScrollToNode(hasTestTag("portability-export-password"))
        compose.onNodeWithTag("portability-export-password").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_password_required)).assertIsDisplayed()
        compose.runOnIdle { exportVm.exportUi.selection = setOf(PortableDataCategory.APP_SETTINGS.id); exportVm.exportUi.password = "synthetic-export-password" }
        compose.onNodeWithTag("portability-export-action").performTouchInput { click(); click() }
        assertEquals(1, registry.calls)
        compose.runOnIdle {
            assertEquals(setOf(PortableDataCategory.APP_SETTINGS), exportVm.exportUi.pending)
            assertEquals("synthetic-export-password", exportVm.exportUi.pendingPassword)
            registry.dispatchResult<Uri?>(registry.request, null)
        }
        compose.onNodeWithTag("portability-export-action").assertIsEnabled()
        compose.runOnIdle { assertTrue(exportVm.exportUi.pending.isEmpty()); assertEquals("", exportVm.exportUi.pendingPassword); assertEquals("synthetic-export-password", exportVm.exportUi.password) }
        compose.runOnIdle { exportVm.exportUi.password = "" }
        compose.onNodeWithTag("portability-export-scroll").performScrollToNode(hasTestTag("portability-export-conversations"))
        screenshot("export")
    }

    @Test fun exportUsesTheConfirmedCategoryAndPasswordThroughTheRealArchiveWriter() {
        show()
        compose.onNodeWithTag("portability-tab-export").performClick()
        val password = "synthetic-export-password"
        compose.runOnIdle { exportVm.exportUi.selection = setOf(PortableDataCategory.APP_SETTINGS.id); exportVm.exportUi.password = password }
        val output = File.createTempFile("portable-ui-export-", ".nekodata", compose.activity.cacheDir).also { outputFiles += it }
        compose.onNodeWithTag("portability-export-action").performClick()
        compose.runOnIdle { registry.dispatchResult<Uri?>(registry.request, Uri.fromFile(output)) }
        compose.waitUntil(30000) { !exportVm.loading.value && output.length() > 0 }
        assertNull(exportVm.error.value)
        runBlocking {
            output.inputStream().use { input -> PortableDataArchiveManager(compose.activity).prepareImport(input, password).use { archive ->
                assertTrue(archive.preview.encrypted)
                assertEquals(setOf(PortableDataCategory.APP_SETTINGS), archive.preview.categories.map { it.category }.toSet())
            } }
        }
        assertEquals(setOf(PortableDataCategory.APP_SETTINGS.id), exportVm.exportUi.selection)
        assertTrue(exportVm.exportUi.pending.isEmpty())
    }
}
