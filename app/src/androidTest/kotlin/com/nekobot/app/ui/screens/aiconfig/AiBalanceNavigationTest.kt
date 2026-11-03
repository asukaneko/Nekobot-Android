package com.nekobot.app.ui.screens.aiconfig

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.theme.NekobotTheme
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Navigation only, in an empty emulator profile; server reads use loopback, never a real account. */
class AiBalanceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var restore: (() -> Unit)? = null

    @Before fun isolate() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val previousProfile = ServiceContainer.prefs.activeDbName
        val previousMode = ServiceContainer.prefs.appMode
        val previousServer = ServiceContainer.prefs.serverUrl
        val profile = "balance_navigation_${UUID.randomUUID()}"
        restore = {
            ServiceContainer.switchLocalDb(previousProfile)
            ServiceContainer.switchAppMode(previousMode)
            ServiceContainer.prefs.serverUrl = previousServer
            ServiceContainer.rebuildNetwork()
            ServiceContainer.prefs.removeDbProfile(profile)
            NekobotDatabase.deleteProfileFile(compose.activity, profile)
        }
        ServiceContainer.switchLocalDb(profile)
        // Loading the server balance screen reads its configured key/model list.
        // A closed loopback port exercises its empty/error state without external traffic.
        ServiceContainer.prefs.serverUrl = "http://127.0.0.1:1"
        ServiceContainer.rebuildNetwork()
    }
    @After fun cleanup() { restore?.invoke() }

    @Test fun localCenterOpensBalanceAndReturns() = checkNavigation(AppMode.LOCAL)
    @Test fun serverCenterOpensLocalBalanceHistoryAndReturns() = checkNavigation(AppMode.SERVER)

    private fun checkNavigation(mode: AppMode) {
        ServiceContainer.switchAppMode(mode)
        compose.setContent {
            NekobotTheme {
                val nav = rememberNavController()
                NavHost(nav, Routes.AI_CONFIG_CENTER) {
                    composable(Routes.AI_CONFIG_CENTER) {
                        AiConfigCenterScreen(onBack = {}, onNavigate = { nav.navigate(it) })
                    }
                    composable(Routes.AI_BALANCE) { AiBalanceScreen { nav.popBackStack() } }
                }
            }
        }
        repeat(2) {
            compose.onNodeWithText(compose.activity.getString(R.string.ai_balance_title)).performScrollTo().performClick()
            val emptyTitle = compose.activity.getString(R.string.ai_balance_empty_title)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(emptyTitle))
            compose.onNodeWithText(emptyTitle).assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_back)).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.aiconfig_center_title)).assertIsDisplayed()
        }
    }
}
