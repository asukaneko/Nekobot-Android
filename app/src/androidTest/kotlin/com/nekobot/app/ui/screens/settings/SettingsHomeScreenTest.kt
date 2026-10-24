package com.nekobot.app.ui.screens.settings

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import com.nekobot.app.R
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.theme.NekobotTheme
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Isolated emulator and synthetic settings only. Server actions are callbacks, with no API calls. */
class SettingsHomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var restore: (() -> Unit)? = null

    @Before fun isolate() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = ServiceContainer.prefs
        val previousProfile = prefs.activeDbName
        val previousMode = prefs.appMode
        val previousLanguage = prefs.language
        val previousLock = prefs.appLockEnabled
        val previousScale = prefs.fontScale
        val previousFollow = prefs.followSystemFontScale
        val profile = "settings_home_${UUID.randomUUID().toString().replace("-", "")}"
        restore = {
            prefs.appLockEnabled = previousLock
            prefs.language = previousLanguage
            prefs.fontScale = previousScale
            prefs.followSystemFontScale = previousFollow
            ServiceContainer.switchLocalDb(previousProfile)
            ServiceContainer.switchAppMode(previousMode)
            prefs.removeDbProfile(profile)
            NekobotDatabase.deleteProfileFile(compose.activity, profile)
        }
        ServiceContainer.switchAppMode(AppMode.LOCAL)
        ServiceContainer.switchLocalDb(profile)
        prefs.language = PrefsManager.LANGUAGE_ZH
        prefs.appLockEnabled = false
        prefs.fontScale = 1f
        prefs.followSystemFontScale = false
    }

    @After fun cleanup() { restore?.invoke() }

    @Composable private fun Theme(dark: Boolean = false, content: @Composable () -> Unit) {
        val context = remember {
            compose.activity.createConfigurationContext(
                Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            )
        }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
            LocalActivityResultRegistryOwner provides compose.activity
        ) {
            NekobotTheme(darkTheme = dark, content = content)
        }
    }

    @Composable private fun Home(
        mode: AppMode,
        onNavigate: (String) -> Unit = {},
        lockAvailable: Boolean = true,
        onLock: (Boolean) -> Unit = {}
    ) {
        var latest by remember { mutableStateOf(false) }
        var archived by remember { mutableStateOf(true) }
        SettingsHomeContent(
            scrollState = rememberScrollState(),
            appMode = mode,
            language = "简体中文",
            version = "synthetic",
            openLatestSessionOnLaunch = latest,
            recentSessionsIncludeArchived = archived,
            appLockEnabled = false,
            appLockAvailable = lockAvailable,
            onOpenLatestSessionChange = { latest = it },
            onIncludeArchivedChange = { archived = it },
            onAppLockChange = onLock,
            onLanguageClick = {},
            onConnectionClick = {},
            onNavigate = onNavigate
        )
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("settings-test-root").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "settings-home-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun appSettingsKeepPreferencesPermissionsAndServerFeatureSwitchesOnly() {
        val visited = mutableListOf<String>()
        var mode by mutableStateOf(AppMode.LOCAL)
        compose.setContent { Theme { Box(Modifier.testTag("settings-test-root")) { Home(mode, visited::add) } } }
        val common = listOf("system_operations", "about")
        common.forEach { compose.onNodeWithTag("settings-home-$it").performScrollTo().performClick() }
        assertEquals(common, visited)
        compose.onNodeWithTag("settings-home-feature_switches").assertDoesNotExist()
        listOf("local_ai_models", "data_portability", "webdav_backup", "data_maintenance", "agent_settings", "logs", "diagnostic_center", "system_settings", "config_transfer", "image-prompt").forEach {
            compose.onNodeWithTag("settings-home-$it").assertDoesNotExist()
        }
        compose.runOnIdle { mode = AppMode.SERVER; visited.clear() }
        (common + "feature_switches").forEach { compose.onNodeWithTag("settings-home-$it").performScrollTo().performClick() }
        assertEquals(common + "feature_switches", visited)
        compose.onNodeWithTag("settings-connection-server-url").assertDoesNotExist()
        compose.onNodeWithTag("settings-connection-reload").assertDoesNotExist()
        screenshot("normal-server")
    }

    @Test fun largeTextAndSmallWidthKeepNavigationAndWholeRowSwitchesReachable() {
        ServiceContainer.prefs.fontScale = 1.6f
        compose.setContent {
            Theme(dark = true) {
                Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings-test-root").background(MaterialTheme.colorScheme.background)) { Home(AppMode.LOCAL) }
            }
        }
        compose.onNodeWithTag("settings-home-open-latest").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("settings-home-include-archived").performScrollTo().assertIsOn().performClick().assertIsOff()
        screenshot("large-dark-common")
        compose.onNodeWithTag("settings-home-system_operations").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val bounds = compose.onNodeWithTag("settings-home-system_operations").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("settings-test-root").getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        screenshot("large-dark-permissions")
        compose.onNodeWithTag("settings-home-about").performScrollTo().assertIsDisplayed()
        screenshot("large-dark-about")
    }

    @Test fun pendingFingerprintAuthenticationDisablesTheSingleSwitchTarget() {
        var calls = 0
        compose.setContent { Theme { Home(AppMode.LOCAL, lockAvailable = false, onLock = { calls++ }) } }
        compose.onNodeWithTag("settings-home-app-lock").performScrollTo().assertIsNotEnabled()
            .performTouchInput { click() }
        assertEquals(0, calls)
    }

    @Test fun connectionPageKeepsServerFormActionsAndModeChoiceWithSyntheticCallbacks() {
        var mode by mutableStateOf(AppMode.SERVER)
        var url by mutableStateOf("http://synthetic.invalid")
        var saves = 0
        var reloads = 0
        var logouts = 0
        ServiceContainer.prefs.fontScale = 1.6f
        compose.setContent {
            Theme {
                Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("settings-test-root").background(MaterialTheme.colorScheme.background)) {
                    SettingsConnectionContent(
                        rememberScrollState(), mode, url, "合成测试账号", false,
                        { mode = it }, { url = it }, { saves++ }, { reloads++ }, { logouts++ }
                    )
                }
            }
        }
        compose.onNodeWithTag("settings-connection-server-url").performScrollTo()
            .performTextReplacement("https://synthetic.invalid")
        compose.onNodeWithTag("settings-connection-save-url").performScrollTo().performClick()
        assertEquals(1, saves)
        assertEquals("https://synthetic.invalid", url)
        screenshot("large-server-form")
        compose.onNodeWithTag("settings-connection-reload").performScrollTo().performClick()
        compose.onNodeWithTag("settings-connection-logout").performScrollTo().performClick()
        assertEquals(1, reloads)
        assertEquals(1, logouts)
        screenshot("large-server-actions")
        compose.onNodeWithTag("settings-connection-mode").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertEquals(AppMode.LOCAL, mode)
        compose.onNodeWithTag("settings-connection-server-url").assertDoesNotExist()
        compose.onNodeWithTag("settings-connection-logout").assertDoesNotExist()
    }

    @Test fun errorsAreConsumedOnTheAuthorListIncludingAfterScroll() {
        lateinit var screenModel: SettingsViewModel
        compose.runOnUiThread { screenModel = ViewModelProvider(compose.activity)[SettingsViewModel::class.java] }
        compose.setContent { Theme { SettingsScreen({}, {}, {}) } }
        compose.runOnIdle { screenModel.showError("Synthetic settings failure") }
        compose.waitUntil(5_000) { screenModel.error.value == null }
        compose.onNodeWithText(ServiceContainer.getString(R.string.settings_about)).performScrollTo()
        compose.runOnIdle { screenModel.showError("Synthetic scrolled settings failure") }
        compose.waitUntil(5_000) { screenModel.error.value == null }
        compose.onNodeWithText(ServiceContainer.getString(R.string.settings_about)).assertIsDisplayed()
    }

    @Test fun authorListDestinationBackRestoresScrollAndAllowsReentry() {
        val visited = mutableListOf<String>()
        compose.setContent {
            Theme {
                Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp).fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, "settings") {
                        composable("settings") { SettingsScreen({}, { visited += it; nav.navigate("test/$it") }, {}) }
                        composable("test/{route}") {
                            Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("settings-test-return")) { Text("Back") }
                        }
                    }
                }
            }
        }
        val about = ServiceContainer.getString(R.string.settings_about)
        compose.onNodeWithText(about).performScrollTo()
        fun listScroll() = compose.onNodeWithTag("settings-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = listScroll()
        assertTrue(before > 0f)
        compose.onNodeWithText(about).performTouchInput { click(); advanceEventTime(1); click() }
        compose.onNodeWithTag("settings-test-return").assertIsDisplayed()
        assertEquals(listOf("about"), visited)
        compose.onNodeWithTag("settings-test-return").performClick()
        compose.onNodeWithText(about).assertIsDisplayed()
        assertEquals(before, listScroll(), 1f)
        compose.onNodeWithText(about).performClick()
        compose.onNodeWithTag("settings-test-return").assertIsDisplayed()
        assertEquals(listOf("about", "about"), visited)
    }

    @Test fun maintenanceStillOpensTheExistingImportAndExportTabs() {
        compose.setContent {
            Theme {
                Box(Modifier.testTag("settings-test-root")) {
                    val nav = rememberNavController()
                    NavHost(nav, "maintenance") {
                        composable("maintenance") {
                            com.nekobot.app.ui.screens.more.MoreCategoryContent(
                                com.nekobot.app.ui.screens.more.MoreCategory.MAINTENANCE, AppMode.LOCAL,
                                { nav.navigate("test/$it") }, {})
                        }
                        composable("test/data_portability") { DataPortabilityScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
        compose.onNodeWithTag("more-home-data_portability").performScrollTo().performClick()
        compose.onNodeWithTag("portability-tab-import").assertIsDisplayed()
        compose.onNodeWithTag("safe-import-choose").assertIsDisplayed()
        screenshot("combined-import")
        compose.onNodeWithTag("portability-tab-export").performClick()
        compose.onNodeWithTag("portability-export-action").assertIsDisplayed()
        screenshot("combined-export")
        compose.onNodeWithTag("portability-back").performClick()
        compose.onNodeWithTag("more-home-data_portability").assertIsDisplayed()
    }

}
