package com.nekobot.app.ui.screens.sessions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import com.nekobot.app.ui.theme.NekobotTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 仅合成空页；检查生产页签和横滑边界，不接触用户数据库或模型。 */
@OptIn(ExperimentalFoundationApi::class)
class SessionPageTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var outer: PagerState
    private lateinit var inner: PagerState

    private fun content(initialPage: Int = 0, fontScale: Float = 1f) {
        compose.setContent {
            NekobotTheme(darkTheme = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    outer = rememberPagerState(initialPage = 1, pageCount = { 3 })
                    inner = rememberPagerState(initialPage = initialPage, pageCount = { SessionPage.entries.size })
                    val scope = rememberCoroutineScope()
                    val boundary = rememberSessionPageSwipeBoundary()
                    HorizontalPager(outer, Modifier.fillMaxSize()) { mainPage ->
                        if (mainPage == 1) Column(Modifier.fillMaxSize()) {
                            SessionPageTabs(SessionPage.entries[inner.currentPage]) { page ->
                                scope.launch { inner.animateScrollToPage(page.ordinal) }
                            }
                            HorizontalPager(inner, Modifier.weight(1f).fillMaxWidth()
                                .testTag("session-content-pages").nestedScroll(boundary)) { page ->
                                Box(Modifier.fillMaxSize()) { Text("synthetic-page-$page") }
                            }
                        } else Box(Modifier.fillMaxSize()) { Text("outer-page-$mainPage") }
                    }
                }
            }
        }
    }

    @Test fun tabsAreSelectableAtLargeFontAndKeepMainNavigation() {
        content(fontScale = 1.6f)
        compose.onNodeWithTag("sessions-page-character").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("sessions-page-companion").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sessions-page-companion").assertIsSelected()
        compose.runOnIdle { assertEquals(2, inner.currentPage); assertEquals(1, outer.currentPage) }
    }

    @Test fun contentSwipeMovesBetweenAllThreePages() {
        content()
        compose.onNodeWithTag("session-content-pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, inner.currentPage); assertEquals(1, outer.currentPage) }
        compose.onNodeWithTag("session-content-pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, inner.currentPage); assertEquals(1, outer.currentPage) }
        compose.onNodeWithTag("session-content-pages").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, inner.currentPage); assertEquals(1, outer.currentPage) }
    }

    @Test fun firstAndLastPageFlingsDoNotEscapeToOuterPager() {
        content()
        repeat(2) {
            compose.onNodeWithTag("session-content-pages").performTouchInput { swipeRight() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(0, inner.currentPage); assertEquals(1, outer.currentPage) }
        compose.onNodeWithTag("sessions-page-companion").performClick()
        compose.waitForIdle()
        repeat(2) {
            compose.onNodeWithTag("session-content-pages").performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(2, inner.currentPage); assertEquals(1, outer.currentPage) }
    }
}
