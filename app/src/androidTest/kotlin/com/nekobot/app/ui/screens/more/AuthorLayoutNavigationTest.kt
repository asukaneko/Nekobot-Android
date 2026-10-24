package com.nekobot.app.ui.screens.more

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.screens.settings.SettingsScreen
import com.nekobot.app.ui.theme.NekobotTheme
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Current author-style lists, isolated synthetic profile, never a real server/model. */
class AuthorLayoutNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var restore: (() -> Unit)? = null
    @Before fun isolate() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = ServiceContainer.prefs
        val oldProfile = prefs.activeDbName
        val oldMode = prefs.appMode
        val oldServer = prefs.serverUrl
        val oldLanguage = prefs.language
        val oldScale = prefs.fontScale
        val oldFollow = prefs.followSystemFontScale
        val oldOptimize = prefs.messageImagePromptOptimizeEnabled
        val profile = "author_layout_${UUID.randomUUID()}"
        restore = {
            ServiceContainer.switchLocalDb(oldProfile)
            ServiceContainer.switchAppMode(oldMode)
            prefs.serverUrl = oldServer
            prefs.language = oldLanguage
            prefs.fontScale = oldScale
            prefs.followSystemFontScale = oldFollow
            prefs.messageImagePromptOptimizeEnabled = oldOptimize
            ServiceContainer.rebuildNetwork()
            ServiceContainer.refreshLocale()
            prefs.removeDbProfile(profile)
            NekobotDatabase.deleteProfileFile(compose.activity, profile)
        }
        prefs.serverUrl = "http://127.0.0.1:1"
        prefs.language = PrefsManager.LANGUAGE_ZH
        prefs.fontScale = 1f
        prefs.followSystemFontScale = false
        ServiceContainer.rebuildNetwork()
        ServiceContainer.refreshLocale()
        ServiceContainer.switchLocalDb(profile)
        ServiceContainer.switchAppMode(AppMode.LOCAL)
    }
    @After fun cleanup() { restore?.invoke() }

    private fun label(id: Int) = ServiceContainer.getString(id)
    private fun showList(settings: Boolean, visits: MutableList<String> = mutableListOf(), logout: () -> Unit = {}) {
        compose.setContent {
            val context = remember {
                compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
            }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                NekobotTheme {
                    val nav = rememberNavController()
                    NavHost(nav, "list") {
                        composable("list") {
                            val go: (String) -> Unit = { visits += it; nav.navigate("destination/$it") }
                            if (settings) SettingsScreen(logout, go, {}) else MoreScreen(go, logout)
                        }
                        composable("destination/{route}") {
                            Button({ nav.popBackStack() }, Modifier.testTag("author-list-return")) { Text("Back") }
                        }
                    }
                }
            }
        }
    }
    private fun moreRow(id: Int): SemanticsNodeInteraction {
        compose.onNodeWithTag("more-list").performScrollToNode(hasText(label(id)))
        return compose.onNodeWithText(label(id))
    }

    @Test fun localMoreKeepsAllExistingDestinationsWithoutCategoryIndex() {
        val visits = mutableListOf<String>()
        showList(false, visits)
        val routes = listOf(
            R.string.more_style_settings to Routes.STYLE_SETTINGS,
            R.string.single_role_relationship_page_skins to Routes.RELATIONSHIP_BACKGROUND,
            R.string.more_ai_config_center to Routes.AI_CONFIG_CENTER,
            R.string.more_role_memory to Routes.MEMORY,
            R.string.agent_character_memory_title to Routes.AGENT_CHARACTER_MEMORY,
            R.string.more_global_agent_memory to Routes.AGENT_MEMORY,
            R.string.global_search_title to Routes.GLOBAL_SEARCH,
            R.string.more_state_history to Routes.STATE_HISTORY,
            R.string.more_stickers to Routes.STICKERS,
            R.string.more_extensions to Routes.EXTENSIONS,
            R.string.more_system_operations to Routes.SYSTEM_OPERATIONS,
            R.string.more_db_management to Routes.DB_PROFILE,
            R.string.more_settings to Routes.SETTINGS
        )
        routes.forEach { (title, route) ->
            moreRow(title).performClick()
            compose.onNodeWithTag("author-list-return").assertIsDisplayed().performClick()
            assertEquals(route, visits.last())
            compose.onNodeWithText(label(title)).assertIsDisplayed()
        }
        assertEquals(routes.map { it.second }, visits)
        compose.onNodeWithText(label(R.string.more_category_memory)).assertDoesNotExist()
    }

    @Test fun serverMoreRetainsLogoutAndHidesLocalOnlyEntries() {
        ServiceContainer.switchAppMode(AppMode.SERVER)
        var logoutCalls = 0
        showList(false, logout = { logoutCalls++ })
        compose.onNodeWithTag("more-list").performScrollToIndex(1)
        compose.onNodeWithText(label(R.string.agent_character_memory_title)).assertDoesNotExist()
        compose.onNodeWithTag("more-list").performScrollToIndex(3)
        compose.onNodeWithText(label(R.string.more_db_management)).assertDoesNotExist()
        moreRow(R.string.more_logout).performClick()
        assertEquals(1, logoutCalls)
    }

    @Test fun localSettingsRetainsToolsAndUsesOneToggleTarget() {
        val visits = mutableListOf<String>()
        ServiceContainer.prefs.messageImagePromptOptimizeEnabled = false
        showList(true, visits)
        compose.onNodeWithText(label(R.string.settings_run_mode)).assertIsDisplayed()
        val optimize = compose.onNodeWithText(label(R.string.settings_message_image_prompt_optimize))
        optimize.performScrollTo().assertIsOff().performClick().assertIsOn()
        assertTrue(ServiceContainer.prefs.messageImagePromptOptimizeEnabled)
        optimize.performClick().assertIsOff()
        assertFalse(ServiceContainer.prefs.messageImagePromptOptimizeEnabled)
        val routes = listOf(
            R.string.settings_local_ai_config to Routes.LOCAL_AI_MODELS,
            R.string.settings_data_portability to Routes.DATA_PORTABILITY,
            R.string.settings_data_maintenance to Routes.DATA_MAINTENANCE,
            R.string.settings_webdav_backup to Routes.WEBDAV_BACKUP,
            R.string.agent_settings_title to Routes.AGENT_SETTINGS,
            R.string.settings_local_logs to Routes.LOGS,
            R.string.diagnostic_center_title to Routes.DIAGNOSTIC_CENTER,
            R.string.settings_about to "about"
        )
        routes.forEach { (title, route) ->
            compose.onNode(hasText(label(title)) and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("author-list-return").assertIsDisplayed().performClick()
            assertEquals(route, visits.last())
        }
        assertEquals(routes.map { it.second }, visits)
    }

    @Test fun serverSettingsRetainsConfigurationAndAccountActions() {
        ServiceContainer.switchAppMode(AppMode.SERVER)
        val visits = mutableListOf<String>()
        showList(true, visits)
        listOf(R.string.settings_server_address, R.string.settings_save_address, R.string.settings_reload_config,
            R.string.settings_logout, R.string.settings_language).forEach {
            compose.onNodeWithText(label(it)).performScrollTo().assertIsDisplayed()
        }
        listOf(R.string.settings_system_settings to "system_settings",
            R.string.settings_feature_switches to "feature_switches",
            R.string.settings_config_transfer to "config_transfer").forEach { (title, route) ->
            compose.onNodeWithText(label(title)).performScrollTo().performClick()
            compose.onNodeWithTag("author-list-return").assertIsDisplayed().performClick()
            assertEquals(route, visits.last())
        }
        compose.onNodeWithText(label(R.string.settings_message_image_prompt_optimize)).assertDoesNotExist()
    }

    @Test fun settingsListRestoresSavedScrollWithoutSavingNavigationStateAsAny() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NekobotTheme { SettingsScreen({}, {}, {}) } }
        val about = compose.activity.getString(R.string.settings_about)
        val language = compose.activity.getString(R.string.settings_language)
        compose.onNodeWithText(about).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(about).assertIsDisplayed()
        compose.onNodeWithText(language).performScrollTo().assertIsDisplayed()
    }
}
