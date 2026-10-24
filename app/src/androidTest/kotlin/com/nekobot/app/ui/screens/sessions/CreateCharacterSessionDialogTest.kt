package com.nekobot.app.ui.screens.sessions

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.R
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

class CreateCharacterSessionDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchApplicationWindow() {
        compose.activity.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    private val characters = listOf(
        CharacterPreset(id = "first", name = "林小满", description = "喜欢散步与收集明信片。", tags = listOf("日常"), firstMessage = "今天想去哪里走走？"),
        CharacterPreset(id = "second", name = "林小满", description = "在图书馆工作的朋友。", tags = listOf("书店"), firstMessage = "找到你想读的书了吗？")
    )

    @Composable private fun Content(
        state: SessionCreationUiState = SessionCreationUiState(characters),
        dark: Boolean = false,
        largeText: Boolean = false,
        onCreate: (CreateSessionRequest) -> Unit = {},
        onLoad: () -> Unit = {}
    ) {
        val context = LocalContext.current
        // 用 LocalConfiguration 读取配置，避免直接读 LocalContext 的 resources 后错过重组。
        val currentConfiguration = LocalConfiguration.current
        val localized = remember(currentConfiguration) {
            context.createConfigurationContext(Configuration(currentConfiguration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        }
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides localized.resources.configuration,
            LocalResources provides localized.resources,
            LocalDensity provides Density(density.density, if (largeText) 1.5f else 1f)
        ) {
            NekobotTheme(darkTheme = dark) {
                CreateCharacterSessionDialog(state, true, "tester", {}, onCreate, onLoad)
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun searchByTagSelectsTheCorrectRoleWithTheSameName() {
        var request: CreateSessionRequest? = null
        compose.setContent { Content(onCreate = { request = it }) }
        capture("create-character-picker-light")
        compose.onNodeWithTag("session_character_search").performTextInput("书店")
        compose.onNodeWithTag("session_character_search").performImeAction()
        compose.onNodeWithTag("session_character_first").assertDoesNotExist()
        compose.onNodeWithTag("session_character_second").performClick()
        compose.onNodeWithText("找到你想读的书了吗？").assertIsDisplayed()
        capture("create-character-selected-light")
        compose.onNodeWithTag("session_create_submit").assertIsDisplayed().performClick()
        assertEquals("second", request?.characterId)
        assertEquals("找到你想读的书了吗？", request?.firstMessage)
        assertEquals("tester", request?.userId)
    }

    @Test fun explicitlyClearedOpeningAndCustomNameSurviveChangingCharacters() {
        var request: CreateSessionRequest? = null
        compose.setContent { Content(onCreate = { request = it }) }
        compose.onNodeWithTag("session_character_first").performClick()
        compose.onNodeWithTag("session_create_name").performTextInput("周末散步")
        compose.onNodeWithTag("session_create_name").performImeAction()
        compose.onNodeWithTag("session_create_content").performScrollToNode(hasText("开场白"))
        compose.onNodeWithText("开场白").performClick()
        compose.onNodeWithTag("session_create_content").performScrollToNode(hasTestTag("session_create_greeting"))
        compose.onNodeWithTag("session_create_greeting").performTextClearance()
        compose.onNodeWithTag("session_create_content").performScrollToNode(hasTestTag("session_change_character"))
        compose.onNodeWithTag("session_change_character").performClick()
        compose.onNodeWithTag("session_create_content").performScrollToNode(hasTestTag("session_character_second"))
        compose.onNodeWithTag("session_character_second").performClick()
        compose.onNodeWithTag("session_create_submit").performClick()
        assertEquals("second", request?.characterId)
        assertEquals("周末散步", request?.name)
        assertEquals("", request?.firstMessage)
    }

    @Test fun darkLargeTextAndKeyboardKeepTheCreateButtonVisible() {
        val longCharacter = characters.first().copy(name = "林小满与她很长很长的角色名字", firstMessage = "很长的开场白。".repeat(300))
        compose.setContent { Content(SessionCreationUiState(listOf(longCharacter)), dark = true, largeText = true) }
        compose.onNodeWithTag("session_character_first").performClick()
        compose.onNodeWithTag("session_create_submit").assertIsDisplayed()
        capture("create-character-dark-large-text")
        compose.onNodeWithTag("session_create_content").performScrollToNode(hasText("开场白"))
        compose.onNodeWithText("开场白").performClick()
        compose.onNodeWithTag("session_create_content").performScrollToNode(hasTestTag("session_create_greeting"))
        compose.onNodeWithTag("session_create_greeting").performTextInput("自定义")
        compose.waitUntil(10000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("session_create_submit").assertIsDisplayed()
        if (compose.activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            compose.onNodeWithText("新建角色会话").assertIsDisplayed()
        }
        capture("create-character-keyboard")
    }

    @Test fun loadingFailureCanRetryAndCreatingDisablesSubmission() {
        var state by mutableStateOf(SessionCreationUiState(loading = true))
        var loads = 0
        compose.setContent { Content(state = state, onLoad = { loads++ }) }
        compose.onNodeWithTag("session_create_submit").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, error = "角色加载失败") }
        compose.onNodeWithText("重试").performClick()
        assertEquals(2, loads)
        compose.runOnIdle { state = SessionCreationUiState(creating = true) }
        compose.onNodeWithTag("session_create_submit").assertIsNotEnabled()
        compose.runOnIdle { state = SessionCreationUiState() }
        compose.onNodeWithText("不使用角色").performClick()
        compose.onNodeWithTag("session_create_submit").assertIsEnabled()
    }
}
