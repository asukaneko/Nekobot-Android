package com.nekobot.app.ui.screens.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.R
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CompanionReasoningDisplayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun displayChoiceIsPersistentAndIsolatedFromOtherCharactersAndProfiles() {
        val profile = "reasoning-display-${UUID.randomUUID()}"
        val prefs = PrefsManager(compose.activity)
        try {
            assertFalse(prefs.companionShowReasoning(profile, "first"))
            prefs.setCompanionShowReasoning(profile, "first", true)
            val reopened = PrefsManager(compose.activity)
            assertTrue(reopened.companionShowReasoning(profile, "first"))
            assertFalse(reopened.companionShowReasoning(profile, "second"))
            assertFalse(reopened.companionShowReasoning("$profile-other", "first"))
            // NONE controls new requests; it must not turn off viewing already saved reasoning.
            prefs.setCompanionReasoningEffort(profile, "first", ReasoningEffort.NONE)
            assertTrue(reopened.companionShowReasoning(profile, "first"))
            prefs.setCompanionShowReasoning(profile, "first", false)
            assertEquals(ReasoningEffort.NONE, reopened.companionReasoningEffort(profile, "first"))
        } finally {
            prefs.clearCompanionRecallSession(profile, "first")
        }
    }

    @Test fun savedReasoningStartsCollapsedAndTheDisplaySwitchDoesNotEraseIt() {
        val enabled = mutableStateOf(false)
        val reasoning = "以前这条回复已经保存的思考"
        compose.setContent {
            NekobotTheme {
                CompanionReasoningSection("old-reply", reasoning, enabled.value)
            }
        }
        compose.onNodeWithTag("companion_reasoning").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("companion_reasoning").assertIsDisplayed()
        compose.onNodeWithText(reasoning).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.companion_inner_thought)).performClick()
        compose.onNodeWithText(reasoning).assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("companion_reasoning").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText(reasoning).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.companion_inner_thought)).performClick()
        compose.onNodeWithText(reasoning).assertIsDisplayed()
    }

    @Test fun anEnabledSwitchDoesNotCreateAnEmptyThoughtPanel() {
        compose.setContent {
            NekobotTheme { CompanionReasoningSection("none-reply", "  ", enabled = true) }
        }
        compose.onNodeWithTag("companion_reasoning").assertDoesNotExist()
    }
}
