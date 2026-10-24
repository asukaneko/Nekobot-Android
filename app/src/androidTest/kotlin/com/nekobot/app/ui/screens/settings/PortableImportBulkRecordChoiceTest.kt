package com.nekobot.app.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Choices stay local until Next; synthetic data only. */
class PortableImportBulkRecordChoiceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun plan() = PortableImportPreview(
        rows = PortableImportRowPlan("rows", PortableImportCounts(different = 125),
            listOf(PortableImportRowDifference("row-0", "companion_memories", "Synthetic record",
                listOf(PortableImportFieldDifference("content", "Synthetic local value", "Synthetic imported value")))), emptyList(), 0),
        fileCounts = PortableImportCounts(), fileDifferences = emptyList(), settingDifferences = emptyList(), storyDifferent = false,
        issues = emptyList(), dependencyOptions = emptyList(), fingerprint = "synthetic", preparedId = "synthetic",
        selected = emptyMap(), choices = PortableImportChoices(), fileBefore = emptyMap(), fileAfter = emptyMap(),
        settingsBefore = JsonObject(), settingsIncoming = null, useStory = false, storySessionIds = emptySet(),
        credentialFingerprint = "", credentialTargets = emptyMap())

    @Test fun bulkSelectionHasNoConfirmationAndDoesNotLeaveTheWindowUntilNext() {
        var calls = 0; var saved: PortableImportChoices? = null
        val choices = PortableImportChoices(files = setOf("explicit-file"), settings = setOf("explicit-setting"))
        val plan = plan()
        compose.setContent { NekobotTheme {
            PortableImportDifferences(plan, choices, false, {}, {}, {},
                onBulkRecords = { draft, incoming, _ -> calls++; saved = draft; assertTrue(incoming) })
        } }
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performClick().assertIsSelected()
        assertEquals(0, calls)
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).performClick()
        assertEquals(1, calls)
        assertEquals(choices.copy(fields = mapOf("row-0" to setOf("content"))), saved)
    }

    @Test fun keepCanBeAdjustedIndividuallyBeforeNextWithoutChangingOtherChoices() {
        var received: PortableImportChoices? = null; var individual = emptySet<String>()
        val choices = PortableImportChoices(files = setOf("explicit-file"), restoreCredentials = true)
        val plan = plan()
        compose.setContent { NekobotTheme {
            PortableImportDifferences(plan, choices, false, {}, {}, {},
                onBulkRecords = { draft, incoming, rows -> assertFalse(incoming); received = draft; individual = rows })
        } }
        compose.onNodeWithTag("safe-difference-bulk-record-keep").performClick()
        compose.onNodeWithTag("safe-difference-row-0-content-replace").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).performClick()
        assertEquals(choices.copy(fields = mapOf("row-0" to setOf("content"))), received)
        assertEquals(setOf("row-0"), individual)
    }

    @Test fun busyStateDisablesChoicesAndNextAndErrorsAreVisibleInCurrentWindow() {
        val busy = mutableStateOf(false); var calls = 0
        val plan = plan()
        compose.setContent { NekobotTheme {
            PortableImportDifferences(plan, PortableImportChoices(), busy.value, {}, {}, {},
                onBulkRecords = { _, _, _ -> calls++ }, error = "Synthetic preview error")
        } }
        compose.onNodeWithTag("import-review-error").assertIsDisplayed()
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").performClick()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("safe-difference-bulk-record-incoming").assertIsNotEnabled()
        compose.onNodeWithTag("safe-difference-bulk-record-keep").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.portability_ui_next)).assertIsNotEnabled()
        assertEquals(0, calls)
    }
}
