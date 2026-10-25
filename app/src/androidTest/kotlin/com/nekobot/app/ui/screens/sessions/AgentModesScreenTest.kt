package com.nekobot.app.ui.screens.sessions

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.theme.NekobotTheme
import com.nekobot.app.ui.theme.BubbleUser
import com.nekobot.app.ui.theme.BubbleUserLight
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.LocalNekoTypeScale
import com.nekobot.app.ui.components.NekoSegmentedControl
import com.nekobot.app.ui.components.ChatBubbleAppearance
import com.nekobot.app.ui.components.chatBubbleAppearance
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class AgentModesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bubbleDefaultsFollowThemeAndKeepUserPinkWithWhiteText() {
        var dark by mutableStateOf(false)
        var assistantOverride by mutableStateOf<String?>(null)
        lateinit var appearance: ChatBubbleAppearance
        compose.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = if (dark)
                androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                val current = chatBubbleAppearance(null, assistantOverride)
                SideEffect { appearance = current }
                Box(Modifier.size(1.dp))
            }
        }
        compose.runOnIdle {
            assertEquals(BubbleUserLight, appearance.userColor)
            assertEquals(Color.White, appearance.userText)
            assertTrue(appearance.assistantColor.luminance() > 0.9f)
            assertTrue(appearance.assistantText.luminance() < 0.1f)
            assertEquals(Color(0xFF1B6D8F), appearance.assistantLink)
            dark = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(BubbleUser, appearance.userColor)
            assertEquals(Color.White, appearance.userText)
            assertTrue(appearance.assistantColor.luminance() < 0.15f)
            assertTrue(appearance.assistantText.luminance() > 0.5f)
            assertEquals(Secondary, appearance.assistantLink)
            assistantOverride = "#FFFFFF"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Color.White, appearance.assistantColor)
            assertEquals(Color.Black, appearance.assistantText)
            assertEquals(Color.White, appearance.userText)
        }
    }
    private fun context() = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
    private fun text(id: Int) = context().getString(id)
    @Composable private fun Theme(content: @Composable () -> Unit) {
        val context = remember { context() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = false) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.statusBarsPadding().navigationBarsPadding()) { content() }
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun legacyDetailsExposeConversionWithoutOldSwitches() {
        var opened = false
        compose.setContent { Theme { Box(Modifier.padding(16.dp)) {
            AgentModeDetailsCard(false, true, { fail("Wrong route") }, { opened = true })
        } } }
        compose.onNodeWithText(text(R.string.agent_legacy_title)).assertIsDisplayed()
        compose.onAllNodes(hasText("继承完整角色能力", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("长久陪伴：", substring = true)).assertCountEquals(0)
        capture("agent-legacy-details")
        compose.onNodeWithText(text(R.string.agent_character_entry)).performClick()
        assertTrue(opened)
    }
    @Test fun independentDetailsOpenOwnMemories() {
        var opened = false
        compose.setContent { Theme { Box(Modifier.padding(16.dp)) {
            AgentModeDetailsCard(true, true, { opened = true }, { fail("Wrong route") })
        } } }
        compose.onNodeWithText(text(R.string.agent_legacy_title)).assertDoesNotExist()
        capture("agent-character-details")
        compose.onNodeWithText(text(R.string.agent_character_memories)).performClick()
        assertTrue(opened)
    }
    @Test fun narrowLargeFontSessionSegmentsRemainReachable() {
        var selected by mutableStateOf(SessionFilter.ALL)
        compose.setContent { Theme {
            CompositionLocalProvider(LocalNekoTypeScale provides 1.5f) {
                Column(Modifier.width(320.dp)) {
                    NekoSegmentedControl(
                        options = listOf(text(R.string.shell_seg_all), text(R.string.shell_seg_character), text(R.string.shell_seg_agent), text(R.string.shell_seg_single)),
                        selectedIndex = sessionSegmentFilters.indexOf(selected),
                        onSelect = { selected = sessionSegmentFilters[it] }
                    )
                }
            }
        } }
        compose.onNodeWithText(text(R.string.shell_seg_single)).assertIsDisplayed().performClick()
        assertEquals(SessionFilter.AGENT_CHARACTERS, selected)
        capture("agent-character-filter-large-text")
        compose.onNodeWithText(text(R.string.shell_seg_agent)).assertIsDisplayed().performClick()
        assertEquals(SessionFilter.AGENT_SESSIONS, selected)
        compose.onNodeWithText(text(R.string.shell_seg_all)).assertIsDisplayed().performClick()
        assertEquals(SessionFilter.ALL, selected)
    }
}
