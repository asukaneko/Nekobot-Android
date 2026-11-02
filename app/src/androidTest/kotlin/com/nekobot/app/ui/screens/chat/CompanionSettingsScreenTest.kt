package com.nekobot.app.ui.screens.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.ui.screens.sessions.SessionDetailScreen
import com.nekobot.app.ui.theme.NekobotTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/** Exercises real screen / ViewModel / repository bindings using only uniquely named emulator fixtures. */
class CompanionSettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchApplicationWindow() {
        compose.activity.runOnUiThread {
            // MainActivity uses edge-to-edge and the manifest's adjustResize policy.
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val context = remember { compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalActivityResultRegistryOwner provides compose.activity, LocalOnBackPressedDispatcherOwner provides compose.activity) {
            NekobotTheme(darkTheme = false, content = content)
        }
    }

    private fun seed(companion: Boolean): Pair<String, String> = runBlocking {
        check(ServiceContainer.prefs.isLocalMode) { "This fixture requires the emulator's existing local mode" }
        val id = "settings-ui-${UUID.randomUUID()}"
        val characterId = "$id-character"
        val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        db.characterDao().upsert(LocalCharacterEntity(characterId, "林小满（界面验证）", createdAt = "2026-10-11T20:00:00", updatedAt = "2026-10-11T20:00:00"))
        db.sessionDao().upsert(LocalSessionEntity(id, "原会话名称", characterId = characterId, characterName = "林小满（界面验证）",
            sessionMode = if (companion) "agent" else "character", companionMode = companion, inheritCharacter = companion,
            longConversationEnabled = companion, pinned = true, tags = "日常", delayReplyEnabled = true, delayReplyDelaySeconds = 7,
            ttsConfig = """{"enabled":true,"model_id":"preserved-model","voice":"preserved-voice"}""",
            proactiveChat = """{"enabled":false,"interval_minutes":40}""",
            createdAt = "2026-10-11T20:00:00", updatedAt = "2026-10-11T20:00:00"))
        id to characterId
    }

    private fun cleanup(ids: Pair<String, String>) = runBlocking {
        check(ids.first.startsWith("settings-ui-") && ids.second == "${ids.first}-character")
        ServiceContainer.localRepository.deleteSession(ids.first)
        ServiceContainer.localRepository.deleteCharacter(ids.second)
        NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName).openHelper.writableDatabase.execSQL(
            "DELETE FROM companion_owners WHERE characterId=? AND sessionId=?", arrayOf(ids.second, ids.first))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "settings-screen-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun realRoleScreenSavesNameAndPreservesExistingReplyConfiguration() {
        val ids = seed(true)
        var saved by mutableStateOf(false)
        try {
            compose.setContent { Theme { SessionDetailScreen(ids.first, { saved = true }) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("单会话角色记忆").fetchSemanticsNodes().isNotEmpty() }
            capture("role-top")
            compose.onNode(hasSetTextAction() and hasText("原会话名称")).performScrollTo().performTextReplacement("改后的会话名称")
            compose.onNodeWithText("语音朗读").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("preserved-model").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            capture("role-config")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { saved }
            val session = runBlocking { requireNotNull(ServiceContainer.localRepository.getSession(ids.first)) }
            assertEquals("改后的会话名称", session.name)
            assertTrue(session.pinned == true)
            assertTrue(session.delayReplyEnabled == true)
            assertEquals(7, session.delayReplyDelaySeconds)
            assertEquals("preserved-model", session.ttsConfig!!.asJsonObject.get("model_id").asString)
            assertEquals("preserved-voice", session.ttsConfig!!.asJsonObject.get("voice").asString)
            assertFalse(session.proactiveChat!!.asJsonObject.get("enabled").asBoolean)
        } finally { cleanup(ids) }
    }

    @Test fun realUsageUsesCompanionLayoutEvenBeforeANewRequestExists() {
        val ids = seed(true)
        try {
            compose.setContent { Theme { ContextAnalysisScreen(ids.first, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("聊天用量").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("本会话累计缓存命中").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("服务商未上报缓存数据").assertIsDisplayed()
            capture("usage-empty")
        } finally { cleanup(ids) }
    }

    @Test fun ordinaryRoleStillUsesTheOriginalDetailScreen() {
        val ids = seed(false)
        try {
            compose.setContent { Theme { SessionDetailScreen(ids.first, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("原会话名称").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("会话详情").assertIsDisplayed()
            compose.onNodeWithText("角色与记忆").assertDoesNotExist()
            compose.onNodeWithText("单会话角色记忆").assertDoesNotExist()
        } finally { cleanup(ids) }
    }

    @Test fun settingsIsAFullPageAndChildrenReturnToItWithoutLosingChatPosition() {
        val ids = seed(true)
        try {
            runBlocking {
                val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
                db.messageDao().upsertAll((0 until 32).map { index ->
                    val time = "2026-10-11T19:${index.toString().padStart(2, '0')}:00"
                    LocalMessageEntity("${ids.first}-message-$index", ids.first,
                        if (index % 2 == 0) "user" else "assistant", "布局验证原话$index", timestamp = time, createdAt = time)
                })
                ServiceContainer.localRepository.setCompanionShowReasoning(ids.first, false)
            }
            compose.setContent { Theme {
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "chat/${ids.first}") {
                    composable("chat/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) {
                        ModernChatScreen(ids.first, {}, onOpenSessionDetail = { navigation.navigate("role") },
                            onOpenContextAnalysis = { navigation.navigate("usage") })
                    }
                    composable("role") { SessionDetailScreen(ids.first, { navigation.popBackStack() }) }
                    composable("usage") { ContextAnalysisScreen(ids.first, { navigation.popBackStack() }) }
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("布局验证原话31").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("布局验证原话14"))
            compose.onNodeWithText("布局验证原话14").assertIsDisplayed()
            val originalPosition = compose.onNodeWithText("布局验证原话14").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithTag("companion-settings-screen").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("查看归档").assertDoesNotExist()
            compose.onNode(isToggleable() and hasText("显示思考")).assertIsOff()
            capture("full-page")
            compose.onNodeWithText("显示思考").performClick()
            assertTrue(ServiceContainer.localRepository.companionShowReasoning(ids.first))

            compose.onNodeWithText("角色与记忆").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("单会话角色记忆").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("companion-settings-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isToggleable() and hasText("显示思考")).assertIsOn()

            compose.onNodeWithText("查找记录").performClick()
            compose.onNodeWithText("查找聊天记录").assertIsDisplayed()
            compose.onNode(hasContentDescription("返回") and hasAnyAncestor(hasTestTag("companion-reading-dialog"))).performClick()
            compose.onNodeWithTag("companion-settings-screen").assertIsDisplayed()
            compose.onNodeWithText("收藏消息").performClick()
            compose.onNode(hasContentDescription("返回") and hasAnyAncestor(hasTestTag("companion-reading-dialog"))).performClick()
            compose.onNodeWithTag("companion-settings-screen").assertIsDisplayed()
            compose.onNodeWithText("聊天用量").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("本会话累计缓存命中").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("companion-settings-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("companion-settings-screen").assertDoesNotExist()
            compose.onNodeWithText("布局验证原话14").assertIsDisplayed()
            assertEquals(originalPosition, compose.onNodeWithText("布局验证原话14").fetchSemanticsNode().boundsInRoot.top, 2f)

            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("查找记录").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("布局验证原话14")
            compose.onNode(hasText("布局验证原话14") and !hasSetTextAction() and hasAnyAncestor(hasTestTag("companion-reading-dialog"))).performClick()
            compose.onNodeWithTag("companion-settings-screen").assertDoesNotExist()
            compose.onNodeWithText("布局验证原话14").assertIsDisplayed()
            capture("chat-return")
        } finally {
            cleanup(ids)
        }
    }
}
