package com.nekobot.app.ui.screens.sessions

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class CompanionCreateScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val character = CharacterPreset(id = "c", name = "姜晚星")
    private val conversionModel = CompanionConversionModel("test-model", "我配置的模型", "测试服务商", "chat-model")
    private fun chineseContext() = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
    private fun text(id: Int) = chineseContext().getString(id)
    @Composable private fun TestTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
        val context = remember { chineseContext() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = darkTheme, content = content)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Surface pixels can lag behind an already updated semantics tree by one frame.
        android.os.SystemClock.sleep(250)
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(compose.activity.getExternalFilesDir("validation"), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 软键盘是系统行为，CI 慢机可能十几秒才弹出；只用于决定是否补拍键盘截图。 */
    private fun waitForSoftKeyboard(timeoutMs: Long = 30_000): Boolean = runCatching {
        compose.waitUntil(timeoutMs) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
    }.isSuccess
    @Composable private fun content(state: CompanionCreateState, onPreview: (String) -> Unit = {}, onCreate: (String, String) -> Unit = {_,_->}, onTransfer: (String) -> Unit = {}, onOpen: (String) -> Unit = {}) {
        CompanionCreateContent(state, {}, {}, onPreview, onCreate, onTransfer, onOpen, {}, {}, {}, {}, {}, {})
    }

    @Test fun freshRoleKeepsSettingsCollapsedAndCreatesWithEditableGreeting() {
        var request: Pair<String, String>? = null
        compose.setContent { TestTheme(darkTheme = false) { content(CompanionCreateState(listOf(character), "c", CompanionEligibility.Available), onCreate = { name, greeting -> request = name to greeting }) } }
        // 会话名称常驻可见；问候语等进阶设置默认折叠。
        compose.onNodeWithText(text(R.string.companion_optional_greeting)).assertDoesNotExist()
        compose.onNodeWithText("姜晚星").assertIsDisplayed()
        capture("companion-create-light")
        compose.onNodeWithText(text(R.string.companion_more_settings)).performClick()
        // 创建页内容在 LazyColumn 中，performScrollTo 不适用；先滚到目标项再输入。
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText(text(R.string.companion_optional_name)))
        compose.onNodeWithText(text(R.string.companion_optional_name)).performClick().performTextInput("晚星的新会话")
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText(text(R.string.companion_optional_greeting)))
        compose.onNodeWithText(text(R.string.companion_optional_greeting)).performClick().performTextInput("欢迎回来")
        // CI 模拟器负载高时软键盘可能十几秒才弹出；等不到只跳过键盘截图，布局断言照常执行。
        if (waitForSoftKeyboard()) capture("companion-create-keyboard")
        compose.onNodeWithTag("companion_primary").assertIsDisplayed().performClick()
        assertEquals("晚星的新会话" to "欢迎回来", request)
    }

    @Test fun usedRoleSelectsSessionAndExistingRoleOpensInsteadOfCreating() {
        var state by mutableStateOf(CompanionCreateState(listOf(character), "c", CompanionEligibility.Used(listOf(LocalSessionEntity("s", "我们的旧会话", characterId = "c", createdAt = "old", updatedAt = "old")))))
        var selected: String? = null
        compose.setContent { TestTheme(darkTheme = false) { content(state, onPreview = { selected = it }, onOpen = { selected = it }) } }
        compose.onNodeWithTag("companion_primary").performClick()
        compose.onNodeWithText("我们的旧会话").performScrollTo().performClick()
        assertEquals("s", selected); capture("companion-create-used")
        compose.runOnIdle { state = state.copy(eligibility = CompanionEligibility.Existing("owned")) }
        compose.onNodeWithText(text(R.string.companion_open_existing)).performClick()
        assertEquals("owned", selected)
    }

    @Test fun previewDarkNarrowLargeTextRemainsScrollableAndPrimaryStaysVisible() {
        val preview = CompanionTransferPreview("job", "名字很长的姜晚星（Agent）", "我们聊了很久的旧会话", 62000, 14, 764, 8,
            listOf("旧记忆来源未验证时会清楚标注，原会话保留。"), 30, emptyList(), 3, 100000000)
        var name: String? = null
        compose.setContent { TestTheme(darkTheme = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(340.dp).fillMaxHeight()) { content(CompanionCreateState(preview = preview,
                    models = listOf(conversionModel), conversionModelId = conversionModel.id), onTransfer = { name = it }) }
            }
        } }
        compose.onNodeWithTag("companion_primary").assertIsDisplayed()
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText("旧记忆来源未验证时会清楚标注，原会话保留。"))
        capture("companion-preview-dark-large")
        compose.onNodeWithTag("companion_primary").performClick()
        assertEquals(preview.name, name)
    }

    @Test fun missingFilesDisableTransferAndRawErrorIsCollapsed() {
        val preview = CompanionTransferPreview("job", "晚星（Agent）", "旧会话", 2, 0, 1, 0, emptyList(), 0, listOf("附件无法读取：note.txt"), 0, 100)
        compose.setContent { TestTheme(darkTheme = false) { content(CompanionCreateState(preview = preview, error = "请先修复缺失附件", details = "SQLITE_INTERNAL_TEST_DETAIL")) } }
        compose.onNodeWithTag("companion_primary").assertIsNotEnabled()
        compose.onNodeWithText("SQLITE_INTERNAL_TEST_DETAIL").assertDoesNotExist()
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText("请先修复缺失附件"))
        capture("companion-preview-failure")
        compose.onNodeWithText(text(R.string.companion_technical_details)).performScrollTo().performClick()
        compose.onNodeWithText("SQLITE_INTERNAL_TEST_DETAIL").assertExists()
    }

    @Test fun progressCanBeCancelledAndCompletedResultOpensTheSavedTarget() {
        val preview = CompanionTransferPreview("job", "晚星（Agent）", "旧会话", 62000, 0, 764, 0, emptyList(), 0, emptyList(), 0, 100)
        var state by mutableStateOf(CompanionCreateState(preview = preview, busy = true, progress = CompanionTransferProgress("正在复制原话", 20000, 62000)))
        var cancelled = false; var opened: String? = null
        compose.setContent { TestTheme(darkTheme = false) {
            CompanionCreateContent(state, {}, {}, {}, {_,_->}, {}, { opened = it }, { cancelled = true }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("companion_primary").assertIsNotEnabled()
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText("正在复制原话 20000 / 62000"))
        capture("companion-transfer-progress")
        compose.onNodeWithText(text(R.string.common_cancel)).performScrollTo().performClick()
        assertTrue(cancelled)
        compose.runOnIdle { state = state.copy(busy = false, completed = "target", progress = null) }
        capture("companion-transfer-complete")
        compose.onNodeWithTag("companion_primary").performClick()
        assertEquals("target", opened)
    }

    @Test fun conversionNeedsExplicitModelAndRuleViewerShowsActualPrompt() {
        val preview = CompanionTransferPreview("j", "晚星（Agent）", "旧会话", 1200, 0, 24, 0, emptyList(), 0, emptyList(), 0, 100)
        var state by mutableStateOf(CompanionCreateState(preview = preview, models = listOf(conversionModel)))
        var configuration by mutableStateOf(false)
        var rules by mutableStateOf(false)
        compose.setContent { TestTheme(darkTheme = false) {
            CompanionCreateContent(state, {}, {}, {}, {_,_->}, {}, {}, {}, {}, {}, {}, {}, {},
                onConfigure = { configuration = true }, onRules = { rules = true })
            if (configuration) CompanionConversionModelPicker(state.models, state.conversionModelId,
                { state = state.copy(conversionModelId = it); configuration = false }, { configuration = false }, {})
            if (rules) CompanionConversionRulesSheet { rules = false }
        } }
        compose.onNodeWithTag("companion_primary").assertIsNotEnabled()
        compose.onNodeWithTag("companion_configure").performClick()
        compose.onNodeWithTag("conversion_model_test-model").assertIsDisplayed()
        capture("conversion-model-picker")
        compose.onNodeWithTag("conversion_model_test-model").performClick()
        compose.onNodeWithTag("companion_primary").assertIsEnabled()
        capture("conversion-ready")
        compose.onNodeWithTag("companion_rules").performScrollTo().performClick()
        compose.onNodeWithText(com.nekobot.app.data.local.ai.CompanionConversionRules.explanation).assertExists()
        capture("conversion-rules")
        compose.onNodeWithTag("conversion_rules_scroll").performScrollToNode(hasTestTag("conversion_rule_format"))
        compose.onNodeWithTag("conversion_rule_format").performClick()
        compose.onNodeWithTag("conversion_rules_scroll").performScrollToNode(hasText(com.nekobot.app.data.local.ai.CompanionConversionRules.format))
        compose.onNodeWithText(com.nekobot.app.data.local.ai.CompanionConversionRules.format).assertExists()
        compose.onNodeWithTag("conversion_rules_scroll").performScrollToNode(hasText(com.nekobot.app.data.local.ai.CompanionConversionRules.outputReminder))
        compose.onNodeWithText(com.nekobot.app.data.local.ai.CompanionConversionRules.outputReminder).assertExists()
    }

    @Test fun providerRefusalShowsReadableReasonAndKeepsTechnicalCodeCollapsed() {
        val failure = CompanionConversionBlockedException("content_filter")
        compose.setContent { TestTheme(darkTheme = false) { content(CompanionCreateState(error = failure.message, details = failure.technicalDetail)) } }
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText(failure.message!!))
        compose.onNodeWithText(failure.message!!).assertIsDisplayed()
        compose.onNodeWithText(failure.technicalDetail).assertDoesNotExist()
        capture("conversion-provider-refusal")
        compose.onNodeWithText(text(R.string.companion_technical_details)).performScrollTo().performClick()
        compose.onNodeWithText(failure.technicalDetail).assertExists()
    }
}
