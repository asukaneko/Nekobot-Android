package com.nekobot.app.ui.screens.more

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgLight
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceLight
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.BgSurfaceVariantLight
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceLight
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.OnSurfaceVariantLight
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.PrimaryLight
import com.nekobot.app.ui.theme.buildTypography
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic display parameters and callbacks only; no preferences, database or API writes. */
class MoreHomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun emulatorOnly() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
    }

    @Composable private fun Theme(
        scale: Float = 1f,
        dark: Boolean = false,
        locale: Locale = Locale.SIMPLIFIED_CHINESE,
        content: @Composable () -> Unit
    ) {
        val context = remember(locale) {
            compose.activity.createConfigurationContext(
                Configuration(compose.activity.resources.configuration).apply {
                    setLocale(locale)
                }
            )
        }
        val colors = if (dark) darkColorScheme(
            primary = Primary, background = BgDark, surface = BgSurface,
            surfaceVariant = BgSurfaceVariant, onSurface = OnSurface,
            onBackground = OnSurface, onSurfaceVariant = OnSurfaceVariant
        ) else lightColorScheme(
            primary = PrimaryLight, background = BgLight, surface = BgSurfaceLight,
            surfaceVariant = BgSurfaceVariantLight, onSurface = OnSurfaceLight,
            onBackground = OnSurfaceLight, onSurfaceVariant = OnSurfaceVariantLight
        )
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
            LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)
        ) {
            MaterialTheme(
                colorScheme = colors,
                typography = buildTypography(fontFamily = "default", fontScale = scale),
                content = content
            )
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("more-test-root").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "more-home-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private val homeRoutes = listOf(
        Routes.GLOBAL_SEARCH, Routes.MORE_MEMORY, Routes.MORE_APPEARANCE,
        Routes.MORE_AI, Routes.SETTINGS, Routes.MORE_MAINTENANCE
    )

    private fun destinations(category: MoreCategory, mode: AppMode): List<String> = when (category) {
        MoreCategory.MEMORY -> listOf(Routes.MEMORY) +
            (if (mode == AppMode.LOCAL) listOf(Routes.AGENT_CHARACTER_MEMORY) else emptyList()) +
            listOf(Routes.AGENT_MEMORY, Routes.STATE_HISTORY)
        MoreCategory.APPEARANCE -> listOf(Routes.STYLE_SETTINGS, Routes.RELATIONSHIP_BACKGROUND, Routes.STICKERS)
        MoreCategory.AI -> listOf(Routes.AI_CONFIG_CENTER, Routes.AGENT_SETTINGS, Routes.EXTENSIONS)
        MoreCategory.MAINTENANCE -> listOf(Routes.DATA_PORTABILITY, Routes.WEBDAV_BACKUP) +
            (if (mode == AppMode.SERVER) listOf(Routes.CONFIG_TRANSFER) else emptyList()) +
            listOf(Routes.LOGS, Routes.DIAGNOSTIC_CENTER, Routes.DATA_MAINTENANCE) +
            listOf(if (mode == AppMode.LOCAL) Routes.DB_PROFILE else Routes.SYSTEM_SETTINGS)
        MoreCategory.APP -> emptyList()
    }

    @Test fun homeShowsSearchAndExactlyFiveCategoryEntrances() {
        val visited = mutableListOf<String>()
        compose.setContent { Theme { Box(Modifier.testTag("more-test-root")) { MoreHomeContent(visited::add) } } }
        homeRoutes.forEach { route ->
            compose.onNodeWithTag("more-home-$route").performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(homeRoutes, visited)
        listOf(Routes.AI_CONFIG_CENTER, Routes.MEMORY, Routes.EXTENSIONS, Routes.SYSTEM_OPERATIONS, Routes.DB_PROFILE).forEach {
            compose.onNodeWithTag("more-home-$it").assertDoesNotExist()
        }
        compose.onNodeWithTag("more-home-${Routes.GLOBAL_SEARCH}").performScrollTo()
        screenshot("normal-home")
    }

    @Test fun categoriesKeepEveryApplicableOriginalDestinationAndModeVisibility() {
        val visited = mutableListOf<String>()
        var category by mutableStateOf(MoreCategory.MEMORY)
        var mode by mutableStateOf(AppMode.LOCAL)
        var optimize by mutableStateOf(false)
        compose.setContent {
            Theme {
                Box(Modifier.testTag("more-test-root")) {
                    MoreCategoryContent(category, mode, visited::add, {}, imagePromptOptimizeEnabled = optimize,
                        onImagePromptOptimizeChange = { optimize = it })
                }
            }
        }
        listOf(AppMode.LOCAL, AppMode.SERVER).forEach { currentMode ->
            MoreCategory.entries.filter { it != MoreCategory.APP }.forEach { currentCategory ->
                compose.runOnIdle { mode = currentMode; category = currentCategory; visited.clear() }
                destinations(currentCategory, currentMode).forEach { route ->
                    compose.onNodeWithTag("more-home-$route").performScrollTo().assertIsDisplayed().performClick()
                }
                assertEquals(destinations(currentCategory, currentMode), visited)
                if (currentCategory == MoreCategory.AI) {
                    if (currentMode == AppMode.LOCAL) {
                        compose.onNodeWithTag("more-ai-image-prompt").performScrollTo().performClick()
                        assertTrue(optimize)
                    } else compose.onNodeWithTag("more-ai-image-prompt").assertDoesNotExist()
                    compose.onNodeWithTag("more-home-${Routes.LOCAL_AI_MODELS}").assertDoesNotExist()
                }
                if (currentMode == AppMode.SERVER) {
                    compose.onNodeWithTag("more-home-${Routes.AGENT_CHARACTER_MEMORY}").assertDoesNotExist()
                    compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").assertDoesNotExist()
                } else {
                    compose.onNodeWithTag("more-home-${Routes.CONFIG_TRANSFER}").assertDoesNotExist()
                    compose.onNodeWithTag("more-home-${Routes.SYSTEM_SETTINGS}").assertDoesNotExist()
                }
                screenshot("normal-${currentMode.name.lowercase()}-${currentCategory.name.lowercase()}")
            }
        }
    }

    @Test fun smallScreenLargeTextKeepsHomeAndEveryCategoryRowReachable() {
        var category by mutableStateOf<MoreCategory?>(null)
        compose.setContent {
            Theme(scale = 1.6f, dark = true) {
                Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp).fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing).testTag("more-test-root")
                    .background(MaterialTheme.colorScheme.background)) {
                    category?.let { MoreCategoryContent(it, AppMode.LOCAL, {}, {}) }
                        ?: MoreHomeContent({})
                }
            }
        }
        fun check(route: String) {
            val row = compose.onNodeWithTag("more-home-$route").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = row.getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("more-test-root").getUnclippedBoundsInRoot()
            assertTrue("$route is clipped", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        homeRoutes.forEach(::check)
        screenshot("large-dark-home-bottom")
        compose.onNodeWithTag("more-home-${Routes.GLOBAL_SEARCH}").performScrollTo()
        screenshot("large-dark-home-top")
        MoreCategory.entries.filter { it != MoreCategory.APP }.forEach { current ->
            compose.runOnIdle { category = current }
            destinations(current, AppMode.LOCAL).forEach(::check)
            screenshot("large-dark-${current.name.lowercase()}")
        }
    }

    @Test fun largeEnglishLabelsWrapWithinTheSmallViewport() {
        compose.setContent {
            Theme(scale = 1.6f, locale = Locale.ENGLISH) {
                Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp).fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing).testTag("more-test-root")) {
                    MoreHomeContent({})
                }
            }
        }
        homeRoutes.forEach { route ->
            val bounds = compose.onNodeWithTag("more-home-$route").performScrollTo()
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("more-test-root").getUnclippedBoundsInRoot()
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        screenshot("large-english-home")
    }

    @Test fun destinationAndCategoryBackRestoreBothScrollPositionsAndAllowReentry() {
        val visited = mutableListOf<String>()
        compose.setContent {
            Theme {
                Box(Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp).fillMaxSize().testTag("more-test-root")) {
                    val nav = rememberNavController()
                    NavHost(nav, Routes.MORE) {
                        composable(Routes.MORE) { MoreHomeContent(rememberMoreNavigateOnce({ nav.navigate(it) })) }
                        composable(Routes.MORE_MAINTENANCE) {
                            MoreCategoryContent(MoreCategory.MAINTENANCE, AppMode.LOCAL,
                                rememberMoreNavigateOnce({ visited += it; nav.navigate("synthetic-destination") }),
                                { nav.popBackStack() })
                        }
                        composable("synthetic-destination") {
                            Column {
                                Text("合成测试目的页")
                                Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("more-test-return")) { Text("返回") }
                            }
                        }
                    }
                }
            }
        }
        fun scroll(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("more-home-${Routes.MORE_MAINTENANCE}").performScrollTo()
        val homeBefore = scroll("more-home-scroll")
        assertTrue(homeBefore > 0f)
        compose.onNodeWithTag("more-home-${Routes.MORE_MAINTENANCE}").performClick()
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").performScrollTo()
        val categoryBefore = scroll("more-category-scroll")
        assertTrue(categoryBefore > 0f)
        repeat(2) {
            compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").performClick()
            compose.onNodeWithTag("more-test-return").performClick()
            compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").assertIsDisplayed()
            assertEquals(categoryBefore, scroll("more-category-scroll"), 1f)
        }
        assertEquals(listOf(Routes.DB_PROFILE, Routes.DB_PROFILE), visited)
        compose.onNodeWithTag("more-category-back").performClick()
        compose.onNodeWithTag("more-home-${Routes.MORE_MAINTENANCE}").assertIsDisplayed()
        assertEquals(homeBefore, scroll("more-home-scroll"), 1f)
        compose.onNodeWithTag("more-home-${Routes.MORE_MAINTENANCE}").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("more-home-${Routes.MORE_MAINTENANCE}").assertIsDisplayed()
    }

    @Test fun rapidRepeatedNavigationWaitsForThePageToResume() {
        val visited = mutableListOf<String>()
        lateinit var owner: SyntheticLifecycleOwner
        compose.runOnUiThread {
            owner = SyntheticLifecycleOwner().apply {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }
        compose.setContent { Theme { MoreHomeContent(rememberMoreNavigateOnce(visited::add, owner)) } }
        compose.onNodeWithTag("more-home-${Routes.SETTINGS}").performScrollTo().performTouchInput { click(); advanceEventTime(1); click() }
        assertEquals(listOf(Routes.SETTINGS), visited)
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.onNodeWithTag("more-home-${Routes.SETTINGS}").performClick()
        assertEquals(listOf(Routes.SETTINGS), visited)
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.onNodeWithTag("more-home-${Routes.SETTINGS}").performClick()
        assertEquals(listOf(Routes.SETTINGS, Routes.SETTINGS), visited)
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
    }

    private class SyntheticLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
