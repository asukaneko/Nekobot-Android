package com.nekobot.app.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic UI fixtures only. Screenshots are written to the isolated test app's cache. */
class SafePortableImportPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "safe-import-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun normalButton(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(160.dp)
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Button must stay within 200dp", bounds.right - bounds.left <= 200.dp)
    }

    @Test fun emptyPageShowsFileChoiceAndNormalSizedButton() {
        val vm = SafePortableImportViewModel()
        compose.setContent { NekobotTheme(darkTheme = false) {
            Surface { Column(Modifier.fillMaxSize().padding(16.dp)) { SafePortableImportPanel(vm = vm) } }
        } }
        normalButton("safe-import-choose")
        compose.onNodeWithTag("safe-import-execute").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.safe_import_independent_memory)).assertDoesNotExist()
        screenshot("empty-light")
    }

    @Test fun actualContentsAllowIndependentMemoryWithoutChatAndSupportBothThemes() {
        val vm = SafePortableImportViewModel()
        val staging = File.createTempFile("portable-ui-", ".tmp", compose.activity.cacheDir).apply { delete(); mkdir() }
        val zip = File(staging, "synthetic.zip").apply { writeBytes(byteArrayOf()) }
        val details = listOf(PortableCategoryDetail("table:local_messages", 1), PortableCategoryDetail("table:companion_memories", 2))
        val source = PortableArchivePreview("synthetic", "fixture", false,
            listOf(PortableCategorySummary(PortableDataCategory.CONVERSATIONS, 3, 0, details)))
        val prepared = PreparedPortableImport(source, "synthetic",
            PortableDataArchiveManager.ResolvedArchive(source, zip, listOf(zip)), emptyMap(), emptyList(), staging, "")
        val dark = mutableStateOf(false)
        try {
            vm.state.value = SafeImportUiState(prepared = prepared, step = SafeImportStep.CONTENTS,
                selected = mapOf(PortableDataCategory.CONVERSATIONS to details.mapTo(linkedSetOf()) { it.key }))
            compose.setContent { NekobotTheme(darkTheme = dark.value) {
                Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
                    SafePortableImportPanel(vm = vm)
                } }
            } }
            compose.onNodeWithTag("safe-import-conversations-memory").assertIsOn()
            compose.onNodeWithTag("safe-import-conversations-data").performScrollTo().performClick()
            compose.onNodeWithTag("safe-import-conversations-data").assertIsOff()
            compose.onNodeWithTag("safe-import-conversations-memory").assertIsOn()
            compose.runOnIdle {
                assertEquals(setOf("table:companion_memories"), vm.state.value.selected[PortableDataCategory.CONVERSATIONS])
            }
            compose.onNodeWithTag("safe-import-execute").assertIsDisplayed()
            normalButton("safe-import-execute")
            compose.onNodeWithTag("safe-import-characters-data").assertDoesNotExist()
            compose.onNodeWithTag("safe-import-extensions-data").assertDoesNotExist()
            screenshot("memory-only-light")
            compose.runOnIdle { dark.value = true }
            compose.waitForIdle()
            screenshot("memory-only-dark")
        } finally { prepared.close() }
    }
}
