package com.nekobot.app.ui.screens.chat

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.R
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class CompanionFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    /** 界面文案固定用简体中文，避免测试结果跟随模拟器系统语言变化。 */
    private fun chineseContext() = compose.activity.createConfigurationContext(
        Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })

    @Composable private fun Theme(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        val context = remember { chineseContext() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(600)
        val bmp = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }; bmp.recycle()
    }
    @Test fun quietEntryOnlyOpensOnTapAndRoutesActionsWithoutSending() {
        var wrote = 0; var copied = 0
        val contact = compose.activity.getString(R.string.companion_feedback_email).ifBlank { "feedback@example.invalid" }
        compose.setContent { Theme { CompanionFeedbackEntry(email=contact,onWrite={wrote++},onCopy={copied++}) } }
        compose.onNodeWithText(contact).assertDoesNotExist()
        compose.onNodeWithText("测试反馈与建议").performClick()
        compose.onNodeWithText(contact).assertIsDisplayed()
        capture("feedback-open")
        compose.onNodeWithText("写邮件").performScrollTo().performClick()
        compose.onNodeWithText("复制邮箱").performClick()
        assertEquals(1,wrote); assertEquals(1,copied)
        compose.onNodeWithContentDescription("关闭反馈").performScrollTo().performClick()
        compose.onNodeWithText(contact).assertDoesNotExist()
        val draft = companionFeedbackIntent("feedback@example.invalid", androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources)
        assertEquals(Intent.ACTION_SENDTO,draft.action)
        assertEquals("mailto",draft.data?.scheme)
        assertTrue(draft.dataString.orEmpty().startsWith("mailto:feedback@example.invalid?"))
        assertNull(draft.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }
    @Test fun actualSettingsPageKeepsEntrySmallAndWorksInDarkLargeText() {
        compose.setContent { Theme(dark = true, scale = 1.6f) {
            CompanionSettingsScreen("林小满",false,{}, {},{},{},{},{})
        } }
        // 本地构建有联系入口；通用贡献包没有私人联系方式。
        if (compose.activity.getString(R.string.companion_feedback_email).isBlank()) return
        compose.onNodeWithText("测试反馈与建议").performScrollTo().assertIsDisplayed()
        capture("feedback-quiet-dark")
        compose.onNodeWithText("测试反馈与建议").performClick()
        compose.onNodeWithText("复制邮箱").performScrollTo().assertIsDisplayed()
        capture("feedback-dark-large")
        compose.onNodeWithContentDescription("关闭反馈").performScrollTo().performClick()
        compose.onNodeWithText("测试反馈与建议").assertIsDisplayed()
    }
}
