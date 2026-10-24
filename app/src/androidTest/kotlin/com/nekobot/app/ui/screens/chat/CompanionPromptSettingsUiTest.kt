package com.nekobot.app.ui.screens.chat

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nekobot.app.data.local.ai.CompanionPromptSettings
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.ai.CompanionPromptLanguage
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking

class CompanionPromptSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun window() {
        compose.activity.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val context = remember {
            compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            })
        }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalActivityResultRegistryOwner provides compose.activity, LocalOnBackPressedDispatcherOwner provides compose.activity) {
            NekobotTheme(darkTheme = false, content = content)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        android.os.SystemClock.sleep(350)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "prompts-$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun editingReplacesOneFieldAndResetDoesNotSaveUntilConfirmed() {
        var settings by mutableStateOf(CompanionPromptSettings("原说话", "原思考", false))
        var saves = 0
        var personas = 0
        compose.setContent { Theme {
            CompanionPromptSettingsContent(settings, "林小满（虚构测试）", ReasoningEffort.HIGH, {}, { personas++ }, {},
                onSave = { thinking, draft ->
                    saves++
                    settings = if (thinking) settings.copy(thinkingPrompt = draft) else settings.copy(speakingPrompt = draft)
                })
        } }
        compose.onAllNodesWithTag("companion-thinking-entry").assertCountEquals(1)
        compose.onNodeWithTag("companion-persona-entry").assertIsDisplayed()
        capture("list")
        compose.onNodeWithTag("character-thinking-prompt-entry").assertDoesNotExist()
        compose.onNodeWithTag("companion-persona-entry").performClick()
        assertEquals(1, personas)
        compose.onNodeWithTag("companion-speaking-entry").performClick()
        compose.onNodeWithTag("companion-prompt-editor").performClick()
        compose.onNodeWithTag("companion-prompt-editor").performTextReplacement("自定义说话全文\n第二行")
        capture("speaking-keyboard")
        compose.onNodeWithTag("companion-prompt-save").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals("自定义说话全文\n第二行", settings.speakingPrompt)
        assertEquals("原思考", settings.thinkingPrompt)
        assertFalse(settings.thinkingEnabled)
        compose.onNodeWithTag("companion-speaking-entry").performClick()
        compose.onNodeWithTag("companion-prompt-reset").performClick()
        compose.onNodeWithTag("companion-prompt-editor").assertTextEquals(CompanionPromptLanguage.speakingRule)
        assertEquals(1, saves)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        assertEquals(1, saves)
        assertEquals("自定义说话全文\n第二行", settings.speakingPrompt)
        compose.onNodeWithTag("companion-thinking-entry").performClick()
        compose.onNodeWithTag("companion-prompt-reset").performClick()
        capture("thinking")
        compose.onNodeWithTag("companion-prompt-save").performClick()
        compose.waitForIdle()
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT, settings.thinkingPrompt)
        assertFalse(settings.thinkingEnabled)
        assertEquals(2, saves)
    }

    @Test fun failedSaveKeepsDraftAndNoneDoesNotPreventPreparingPrompt() {
        var attempts = 0
        var result = ""
        compose.setContent { Theme {
            CompanionPromptSettingsContent(CompanionPromptSettings(), "测试角色", ReasoningEffort.NONE, {}, {}, {},
                onSave = { thinking, draft ->
                    assertTrue(thinking)
                    attempts++
                    if (attempts == 1) error("synthetic storage failure")
                    result = draft
                })
        } }
        compose.onNodeWithTag("companion-thinking-entry").performClick()
        compose.onNodeWithTag("companion-prompt-editor").performTextReplacement("保留草稿")
        compose.onNodeWithTag("companion-prompt-save").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("companion-prompt-save-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("companion-prompt-editor").assertTextEquals("保留草稿")
        compose.onNodeWithTag("companion-prompt-save").performClick()
        compose.waitForIdle()
        assertEquals("保留草稿", result)
        assertEquals(2, attempts)
    }

    @Test fun realSettingsEntrySavesToCurrentRoleAndReusesOriginalPersonaEditor() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"))
        assumeTrue(ServiceContainer.prefs.isLocalMode)
        val originalProfile = ServiceContainer.prefs.activeDbName
        val profile = "prompt-ui-${UUID.randomUUID()}"
        try {
            ServiceContainer.switchLocalDb(profile)
            val db = NekobotDatabase.get(compose.activity, profile)
            runBlocking {
                db.characterDao().upsert(LocalCharacterEntity("role", "林小满（虚构测试）", basicInfo = "原有书店店员设定",
                    createdAt = "before", updatedAt = "before"))
                db.characterDao().upsert(LocalCharacterEntity("role-other", "其他虚构角色", createdAt = "before", updatedAt = "before"))
                for (id in listOf("current", "other")) db.sessionDao().upsert(LocalSessionEntity(id, "虚构会话", characterId = if (id == "current") "role" else "role-other",
                    companionMode = true, inheritCharacter = true, longConversationEnabled = true,
                    sessionMode = "agent", companionSpeakingPrompt = "", companionThinkingPrompt = "保留旧思考",
                    companionThinkingEnabled = false, createdAt = "before", updatedAt = "before"))
            }
            compose.setContent { Theme {
                CompanionSettingsScreen("林小满（虚构测试）", true, {}, {}, {}, {}, {}, {},
                    reasoningEffort = ReasoningEffort.HIGH, sessionId = "current", characterId = "role")
            } }
            compose.onNodeWithTag("companion-prompts-entry").performScrollTo().assertIsDisplayed()
            capture("settings-entry")
            compose.onNodeWithTag("character-thinking-prompt-entry").assertDoesNotExist()
            compose.onNodeWithTag("companion-prompts-entry").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("companion-persona-entry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("companion-speaking-entry").performClick()
            compose.onNodeWithTag("companion-prompt-editor").performTextReplacement("当前角色自定义说话")
            compose.onNodeWithTag("companion-prompt-save").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("companion-persona-entry").fetchSemanticsNodes().isNotEmpty() }
            runBlocking {
                assertEquals("当前角色自定义说话", db.sessionDao().getById("current")!!.companionSpeakingPrompt)
                assertEquals("", db.sessionDao().getById("other")!!.companionSpeakingPrompt)
                assertEquals("保留旧思考", db.sessionDao().getById("current")!!.companionThinkingPrompt)
                assertFalse(db.sessionDao().getById("current")!!.companionThinkingEnabled!!)
            }
            compose.onNodeWithTag("companion-persona-entry").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("原有书店店员设定").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("原有书店店员设定").performScrollTo().assertIsDisplayed()
            capture("original-persona")
        } finally {
            compose.runOnIdle { compose.activity.finish() }
            ServiceContainer.switchLocalDb(originalProfile)
            ServiceContainer.prefs.removeDbProfile(profile)
            NekobotDatabase.deleteProfileFile(compose.activity, profile)
        }
    }
}
