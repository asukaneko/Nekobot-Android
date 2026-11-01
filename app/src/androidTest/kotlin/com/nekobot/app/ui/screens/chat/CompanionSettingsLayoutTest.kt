package com.nekobot.app.ui.screens.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.SessionCacheStats
import com.nekobot.app.ui.screens.sessions.CompanionRoleSettingsContent
import com.nekobot.app.ui.screens.sessions.SessionSharingContainer
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Production Compose surfaces, isolated synthetic content; never edits user preferences or records. */
class CompanionSettingsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Theme(accent: Color = Color(0xFF468DFF), dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        val context = remember { compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent, primaryContainer = accent.copy(alpha = 0.12f))) { content() }
                }
            }
        }
    }

    /**
     * 截图只作为人工复核的产物；主题色是否出现不作为断言条件：
     * 不同尺寸/渲染后端下主色像素数量差异极大（用量页本身以图表配色为主），
     * 硬门槛只会制造与界面行为无关的失败。命中数量写入同名 .accent.txt 供参考。
     */
    private fun capture(name: String, accent: Color = Color(0xFF468DFF)) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        val target = accent.toArgb()
        val rendered = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable: $name" }
        var matches = 0
        for (y in 0 until rendered.height step 2) for (x in 0 until rendered.width step 2) {
            val pixel = rendered.getPixel(x, y)
            if (kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(target)) < 15 &&
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(target)) < 15 &&
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(target)) < 15) matches++
        }
        val dir = compose.activity.getExternalFilesDir("validation")
        File(dir, "settings-$name.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "settings-$name.accent.txt").writeText("accent=$matches samples=${rendered.width * rendered.height / 4}")
        rendered.recycle()
    }

    @Test fun sevenThemesAndCustomKeepTheSettingAndAllActionsReachable() {
        var accent by mutableStateOf(Color(0xFF468DFF))
        var checked by mutableStateOf(true)
        var details = 0
        var usage = 0
        compose.setContent { Theme(accent) {
            CompanionSettingsScreen("林小满（转换测试）", checked, { checked = it }, {}, { details++ }, {}, {}, { usage++ }, {})
        } }
        capture("panel")
        val colours = listOf(0xFFFF6094, 0xFF8D68FF, 0xFF468DFF, 0xFF1DBFBF, 0xFF67C975, 0xFFFFB547, 0xFFFF6368, 0xFF90744A)
        colours.forEachIndexed { index, hex ->
            compose.runOnIdle { accent = Color(hex) }
            compose.onNode(isToggleable() and hasText("显示思考")).assertIsOn()
            compose.onNodeWithText("显示思考").performClick()
            compose.onNode(isToggleable() and hasText("显示思考")).assertIsOff()
            compose.onNodeWithText("显示思考").performClick()
            capture("theme-$index", Color(hex))
        }
        compose.onNodeWithText("角色与记忆").performClick()
        compose.onNodeWithText("聊天用量").performScrollTo().performClick()
        // 折叠设置后整页内容高于一屏：先滚回“查看归档”所在的按钮组再断言可见。
        compose.onNodeWithText("查看归档").performScrollTo().assertIsDisplayed()
        assertEquals(1, details)
        assertEquals(1, usage)
    }

    @Test fun searchFiltersCurrentMessagesAndJumpsBeforeClosing() {
        var query by mutableStateOf("书店")
        val messages = listOf(Message(id = "sys", role = "system", content = "书店系统内容"),
            Message(id = "user", role = "user", content = "明天一起去那家书店看看吧。", timestamp = "2026-10-10T18:30:00"),
            Message(id = "ai", role = "assistant", content = "好呀，那家书店我也想去。", timestamp = "2026-10-10T18:31:00"))
        val events = mutableListOf<String>()
        compose.setContent { Theme { CompanionMessageSearch(messages, query, { query = it }, "林小满", { events += "close" }, { events += it.id.orEmpty() }) } }
        compose.onNodeWithText("当前会话 · 2 条结果").assertIsDisplayed()
        compose.onNodeWithText("书店系统内容").assertDoesNotExist()
        capture("search")
        compose.onNodeWithText("只看我发送的").performClick()
        compose.onNodeWithText("当前会话 · 1 条结果").assertIsDisplayed()
        compose.onNodeWithText("明天一起去那家书店看看吧。").performClick()
        assertEquals(listOf("user", "close"), events)
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithText("当前会话 · 1 条结果").assertIsDisplayed()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText("输入关键词，查找这个会话里的原话。").assertIsDisplayed()
    }

    @Test fun favoritesDetailReturnsToListAndDeleteIsASeparateAction() {
        val collection = JsonParser.parseString("""{"id":"folder","title":"读书计划","created_at":"2026-10-10","messages":[{"id":"one","role":"user","content":"明天一起去那家书店看看吧。","timestamp":"2026-10-10T18:30:00"},{"id":"two","role":"assistant","content":"好呀，我们一起慢慢逛。","timestamp":"2026-10-10T18:31:00"}]}""").asJsonObject
        var selected by mutableStateOf<com.google.gson.JsonObject?>(null)
        var deleted = 0
        var closed = 0
        compose.setContent { Theme { CompanionFavoritesReading(listOf(collection), false, selected, "林小满", {
            if (selected != null) selected = null else closed++
        }, { selected = it }, { deleted++ }) } }
        capture("favorites")
        compose.onNodeWithText("读书计划").performClick()
        compose.onNodeWithText("好呀，我们一起慢慢逛。").assertIsDisplayed()
        capture("favorite-detail")
        compose.onNodeWithContentDescription("返回").performClick()
        // 行内按钮会与整行的可点击语义合并，操作说明只在未合并树中单独暴露。
        compose.onNodeWithContentDescription("读书计划 的更多操作", useUnmergedTree = true).performClick()
        compose.onNodeWithText("删除收藏").performClick()
        assertEquals(1, deleted)
        assertEquals(0, closed)
        assertNull(selected)
    }

    @Test fun roleSettingsRetainValuesAndExpandTheExistingConfigurationSlots() {
        var tts by mutableStateOf(true)
        var expanded by mutableStateOf(false)
        var edited by mutableStateOf("手帐与生活日常记录")
        var memory = 0
        compose.setContent { Theme {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CompanionRoleSettingsContent(Session(id = "test", characterName = "林小满", messageCount = 918), edited, "日常", true, false,
                    { edited = it }, {}, {}, {}, { memory++ }, {}, {}, {}, false, {}, true,
                    tts, { tts = it }, false, {}, false, {}, { Text("计时选项") }, { Text("模型与音色选项") }, { Text("间隔选项") }, "提醒说明")
                SessionSharingContainer(true, "公开分享", expanded, { expanded = !expanded }) { Text("原分享配置") }
            }
        } }
        capture("role-top")
        compose.onNodeWithText("单会话角色记忆").performClick()
        assertEquals(1, memory)
        compose.onNodeWithText("模型与音色选项").performScrollTo().assertIsDisplayed()
        capture("role-replies")
        compose.onNodeWithText("语音朗读").performClick()
        compose.onNodeWithText("模型与音色选项").assertDoesNotExist()
        compose.onNodeWithText("原分享配置").assertDoesNotExist()
        compose.onAllNodesWithText("公开分享").onLast().performScrollTo().performClick()
        compose.onNodeWithText("原分享配置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("手帐与生活日常记录").performScrollTo().performTextReplacement("新的会话名称")
        assertEquals("新的会话名称", edited)
    }

    @Test fun missingCacheIsNotZeroAndUsageKeepsSummaryNavigation() {
        var summary = 0
        val breakdown = ContextUsageBreakdown(listOf(ContextUsagePartTokens(ContextUsagePart.SUMMARY, 500, 1), ContextUsagePartTokens(ContextUsagePart.CORE_MEMORY, 700, 4)))
        compose.setContent { Theme {
            CompanionUsageContent(1200, 64000, breakdown, SessionCacheStats(), true, 1791690420000, 1350, true, { summary++ }, Modifier.systemBarsPadding())
        } }
        compose.onNodeWithText("服务商未上报缓存数据").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("usage-cache-rate").assertTextEquals("—")
        compose.onNodeWithText("1,350 Token").performScrollTo().assertIsDisplayed()
        capture("usage")
        // 用量页是 LazyColumn，汇总行在首屏之外，按列表滚动到该行再点击。
        compose.onNodeWithTag("usage-content").performScrollToNode(hasTestTag("usage-row-SUMMARY"))
        compose.onNodeWithTag("usage-row-SUMMARY").performClick()
        compose.onNodeWithText("查看摘要原文").performClick()
        assertEquals(1, summary)
    }

    @Test fun darkLargeTextKeepsControlsScrollableAndDoesNotEnableSettings() {
        var changes = 0
        compose.setContent { Theme(dark = true, scale = 1.6f) {
            CompanionSettingsScreen("很长很长的角色名字（转换测试）", false, { changes++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("聊天用量").performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable() and hasText("显示思考")).assertIsOff()
        capture("dark-large-text")
        assertEquals(0, changes)
    }

    @Test fun fullPageHasBackNavigationAndOmitsArchiveWhenUnavailable() {
        var backed = 0
        var search = 0
        compose.setContent { Theme {
            CompanionSettingsScreen("林小满", false, {}, { backed++ }, {}, { search++ }, {}, {})
        } }
        compose.onNodeWithTag("companion-settings-screen").assertIsDisplayed()
        compose.onNodeWithText("查看归档").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭").assertDoesNotExist()
        compose.onNodeWithText("查找记录").performClick()
        assertEquals(1, search)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backed)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(2, backed)
    }
}
