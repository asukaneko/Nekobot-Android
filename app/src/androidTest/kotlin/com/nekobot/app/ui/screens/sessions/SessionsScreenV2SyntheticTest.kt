package com.nekobot.app.ui.screens.sessions

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.navigation.Routes
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real SessionsScreen and ViewModel; isolated, synthetic local rows and destination placeholders. */
@RunWith(AndroidJUnit4::class)
class SessionsScreenV2SyntheticTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: SessionsViewModel
    private var visible by mutableStateOf(true)
    private var active by mutableStateOf(true)
    private var destination by mutableStateOf<String?>(null)
    private var fallbackBacks = 0
    private var contentInstalled = false

    private data class Fixture(
        val db: NekobotDatabase,
        val rows: Map<SessionPage, List<LocalSessionEntity>>
    ) {
        val count get() = rows.values.sumOf { it.size }
        fun title(page: SessionPage, index: Int = 0) = rows.getValue(page)[index].listTitleOverride!!
    }

    private data class Anchor(val index: Int, val offset: Int, val key: Any?)

    @Test fun categoriesAndEachPagesSearchAndFiltersStayIndependent() = isolated { fixture ->
        launch(fixture)
        val allIds = vm.sessionRowsByPage.value.mapValues { (_, rows) -> rows.map { it.id }.toSet() }
        SessionPage.entries.forEach { page ->
            assertEquals(fixture.rows.getValue(page).map { it.id }.toSet(), allIds[page])
            select(page)
            compose.onNodeWithText(fixture.title(page), useUnmergedTree = true).assertIsDisplayed()
            SessionPage.entries.filterNot { it == page }.forEach { other ->
                compose.onNodeWithText(fixture.title(other), useUnmergedTree = true).assertIsNotDisplayed()
            }
        }
        assertEquals(fixture.count, allIds.values.flatten().toSet().size)
        // Character includes legacy/group rows; inherited Agents stay in Agent, not companion.
        assertTrue(fixture.rows.getValue(SessionPage.CHARACTER)[1].id in allIds.getValue(SessionPage.CHARACTER))
        assertTrue(fixture.rows.getValue(SessionPage.CHARACTER)[2].id in allIds.getValue(SessionPage.CHARACTER))
        assertTrue(fixture.rows.getValue(SessionPage.AGENT)[1].id in allIds.getValue(SessionPage.AGENT))

        setSearch(SessionPage.CHARACTER, "莲花", SessionFilter.FAVORITE, channel = "Local")
        setSearch(SessionPage.AGENT, "纸舟")
        setSearch(SessionPage.COMPANION, "晚灯", SessionFilter.PINNED)
        val searches = mapOf(SessionPage.CHARACTER to "莲花", SessionPage.AGENT to "纸舟", SessionPage.COMPANION to "晚灯")
        SessionPage.entries.forEach { page ->
            select(page)
            assertEquals(searches.getValue(page), vm.pageQueries.value[page].search)
            awaitRows(page, setOf(fixture.rows.getValue(page).first().id))
            compose.onNodeWithText(fixture.title(page), useUnmergedTree = true).assertIsDisplayed()
            capture("search-${page.name.lowercase()}")
        }
        assertEquals(SessionFilter.FAVORITE, vm.pageQueries.value[SessionPage.CHARACTER].filter)
        assertEquals("local", vm.pageQueries.value[SessionPage.CHARACTER].channel)
        assertEquals(SessionFilter.ALL, vm.pageQueries.value[SessionPage.AGENT].filter)
        assertEquals(null, vm.pageQueries.value[SessionPage.AGENT].channel)
        assertEquals(SessionFilter.PINNED, vm.pageQueries.value[SessionPage.COMPANION].filter)
        // Clearing only the companion page must retain the two other pages' queries.
        compose.onNode(hasText(text(R.string.sessions_page_clear_filters)) and hasClickAction() and inViewport()).performClick()
        awaitRows(SessionPage.COMPANION, fixture.rows.getValue(SessionPage.COMPANION).map { it.id }.toSet())
        assertEquals("莲花", vm.pageQueries.value[SessionPage.CHARACTER].search)
        assertEquals("纸舟", vm.pageQueries.value[SessionPage.AGENT].search)
    }

    @Test fun errorsAreConsumedAcrossAllPagesAndDashboardWithoutChangingRows() = isolated { fixture ->
        launch(fixture)
        val originalIds = vm.sessionRowsByPage.value.mapValues { (_, rows) -> rows.map { it.id } }
        SessionPage.entries.forEach { page ->
            select(page)
            compose.runOnIdle { vm.showError("合成错误提示") }
            compose.waitUntil(5_000) { vm.error.value == null }
            compose.onNodeWithTag(tabTag(page)).assertIsSelected()
        }
        openDashboard()
        compose.runOnIdle { vm.showError("合成错误提示") }
        compose.waitUntil(5_000) { vm.error.value == null }
        compose.onNodeWithText(text(R.string.stats_dashboard_title)).assertIsDisplayed()
        systemBack()
        compose.runOnIdle { assertEquals(originalIds, vm.sessionRowsByPage.value.mapValues { (_, rows) -> rows.map { it.id } }) }
    }

    @Test fun pageRoundTripsAndSyntheticChatAndDetailReturnsKeepEachScrollAnchor() = isolated { fixture ->
        launch(fixture)
        val anchors = linkedMapOf<SessionPage, Anchor>()
        SessionPage.entries.forEachIndexed { index, page ->
            select(page)
            verticalList().performScrollToIndex(8 + index * 3)
            compose.waitForIdle()
            anchors[page] = anchor(page)
            assertTrue(anchors.getValue(page).index > 0)
        }
        SessionPage.entries.reversed().forEach { page ->
            select(page)
            assertAnchor(page, anchors.getValue(page))
            capture("scroll-${page.name.lowercase()}")
        }
        select(SessionPage.AGENT)
        val before = anchor(SessionPage.AGENT)
        val row = vm.sessionRowsByPage.value.getValue(SessionPage.AGENT)[before.index]
        compose.onNode(hasText(row.listTitle) and hasClickAction()).performClick()
        awaitDestination("chat/${row.id}")
        returnFromDestination()
        assertAnchor(SessionPage.AGENT, before)
        compose.onNodeWithTag("sessions-page-agent").assertIsSelected()

        val more = hasContentDescription(text(R.string.sessions_more)) and hasAnyAncestor(hasText(row.listTitle))
        compose.onNode(more).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.sessions_detail_menu)).performClick()
        awaitDestination("detail/${row.id}")
        returnFromDestination()
        assertAnchor(SessionPage.AGENT, before)
        SessionPage.entries.filterNot { it == SessionPage.AGENT }.forEach { page ->
            select(page)
            assertAnchor(page, anchors.getValue(page))
        }
    }

    @Test fun dashboardShowsStoredTotalsRestoresScrollAndHonorsActiveBackGate() = isolated { fixture ->
        launch(fixture)
        select(SessionPage.AGENT)
        verticalList().performScrollToIndex(9)
        val before = anchor(SessionPage.AGENT)
        openDashboard()
        compose.waitUntil(15_000) { vm.dashboardData.value.totalSessions == fixture.count }
        compose.onNode(hasText(fixture.count.toString()) and hasAnySibling(hasText(text(R.string.stats_total_sessions))),
            useUnmergedTree = true).assertIsDisplayed()
        capture("dashboard-overview")
        val recent = text(R.string.stats_recent_sessions_title)
        verticalList().performScrollToNode(hasText(recent))
        compose.onNodeWithText(recent).performScrollTo().assertIsDisplayed()
        val recentTop = compose.onNodeWithText(recent).fetchSemanticsNode().boundsInRoot.top
        capture("dashboard-recent")

        // A precomposed inactive SessionsScreen must leave system back to its host.
        compose.runOnIdle { active = false }
        systemBack()
        compose.runOnIdle { assertEquals(1, fallbackBacks) }
        compose.onNodeWithText(text(R.string.stats_dashboard_title)).assertIsDisplayed()
        compose.onNodeWithText(recent).assertIsDisplayed()
        compose.runOnIdle { active = true }
        systemBack()
        compose.onNodeWithTag("sessions-page-agent").assertIsSelected()
        compose.runOnIdle { assertEquals(1, fallbackBacks) }
        assertAnchor(SessionPage.AGENT, before)

        openDashboard()
        compose.onNodeWithText(recent).assertIsDisplayed()
        assertEquals(recentTop, compose.onNodeWithText(recent).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        compose.onNodeWithTag("sessions-page-agent").assertIsSelected()
        assertAnchor(SessionPage.AGENT, before)
    }

    @Test fun smallLargeTextKeepsMenusAndAllThreeCreateActionsReachableOffline() = isolated { fixture ->
        launch(fixture, small = true)
        SessionPage.entries.forEach { page ->
            select(page)
            compose.onNodeWithTag(tabTag(page)).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithContentDescription(text(R.string.sessions_page_search_action, text(page.titleResId)))
                .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            val create = compose.onNodeWithContentDescription(text(R.string.sessions_page_create_action, text(page.titleResId)))
                .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            capture("large-${page.name.lowercase()}")
            create.performClick()
            when (page) {
                SessionPage.CHARACTER -> {
                    compose.onNodeWithText(text(R.string.session_create_title)).assertIsDisplayed()
                    capture("large-character-create", isDialog())
                    closeCreateDialog()
                }
                SessionPage.AGENT -> {
                    compose.waitUntil(15_000) { destination?.startsWith("chat/") == true }
                    val createdId = requireNotNull(destination).removePrefix("chat/")
                    val stored = runBlocking { fixture.db.sessionDao().getById(createdId) }
                    assertEquals("agent", stored?.sessionMode)
                    assertEquals(false, stored?.companionMode)
                    returnFromDestination()
                }
                SessionPage.COMPANION -> {
                    awaitDestination("navigate/${Routes.COMPANION_CREATE}")
                    returnFromDestination()
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.sessions_page_menu))
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        listOf(R.string.stats_dashboard_title, R.string.sessions_refresh, R.string.sessions_import, R.string.sessions_create_group)
            .forEach { compose.onNodeWithText(text(it)).assertIsDisplayed() }
        capture("large-title-menu", isPopup())
        compose.onNodeWithText(text(R.string.sessions_create_group)).performClick()
        capture("large-group-create", isDialog())
        compose.onNodeWithText(text(R.string.sessions_select_characters)).assertIsDisplayed()
        closeCreateDialog()
        compose.onNodeWithTag("sessions-page-companion").assertIsSelected()
    }

    private fun isolated(block: (Fixture) -> Unit) {
        assumeTrue("Synthetic session fixtures require an emulator",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = ServiceContainer.prefs
        val previousProfile = prefs.activeDbName
        val previousMode = prefs.appMode
        val previousUsername = prefs.username
        val previousArchived = prefs.recentSessionsIncludeArchived
        val previousOrder = prefs.statsDashboardWidgetOrder.toList()
        val previousHidden = prefs.statsDashboardHiddenWidgets.toSet()
        // Dashboard metrics call remoteInfo even in local mode. A blank root fails URL validation
        // before any HTTP request; preserve only these two preference keys, never copy credentials.
        val webDav = compose.activity.getSharedPreferences("nekobot_local_webdav", Context.MODE_PRIVATE)
        val hadUrl = webDav.contains("url")
        val previousUrl = webDav.getString("url", null)
        val hadEnabled = webDav.contains("enabled")
        val previousEnabled = webDav.getBoolean("enabled", false)
        val profile = "sessions_v2_ui_${UUID.randomUUID().toString().replace("-", "")}"
        var failure: Throwable? = null
        try {
            assertTrue(webDav.edit().putString("url", "").putBoolean("enabled", false).commit())
            prefs.username = "synthetic-v2-user"
            prefs.recentSessionsIncludeArchived = true
            val widgets = listOf("overview", "channels", "recent_sessions")
            prefs.statsDashboardWidgetOrder = widgets
            prefs.statsDashboardHiddenWidgets = PrefsManager.DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.filterNot(widgets::contains).toSet()
            ServiceContainer.switchLocalDb(profile)
            ServiceContainer.switchAppMode(AppMode.LOCAL)
            block(seed(NekobotDatabase.get(compose.activity, profile)))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            val cleanup: List<() -> Unit> = listOf(
                { if (contentInstalled) { compose.runOnIdle { visible = false }; compose.waitForIdle() } },
                { compose.runOnUiThread { compose.activity.viewModelStore.clear() } },
                { prefs.username = previousUsername; prefs.recentSessionsIncludeArchived = previousArchived },
                { prefs.statsDashboardWidgetOrder = previousOrder; prefs.statsDashboardHiddenWidgets = previousHidden },
                {
                    val edit = webDav.edit()
                    if (hadUrl) edit.putString("url", previousUrl) else edit.remove("url")
                    if (hadEnabled) edit.putBoolean("enabled", previousEnabled) else edit.remove("enabled")
                    assertTrue(edit.commit())
                },
                { ServiceContainer.switchAppMode(previousMode) },
                { ServiceContainer.switchLocalDb(previousProfile) },
                { prefs.removeDbProfile(profile) },
                { NekobotDatabase.deleteProfileFile(compose.activity, profile); Unit }
            )
            cleanup.forEach { action ->
                runCatching(action).exceptionOrNull()?.let { error ->
                    val primary = failure ?: cleanupFailure
                    if (primary == null) cleanupFailure = error else primary.addSuppressed(error)
                }
            }
            if (failure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun seed(db: NekobotDatabase): Fixture = runBlocking {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val rows = SessionPage.entries.associateWith { page ->
            val characterId = "synthetic-v2-character-${page.name}"
            val characterName = "合成${text(page.titleResId)}角色"
            if (page != SessionPage.COMPANION) {
                db.characterDao().upsert(LocalCharacterEntity(characterId, characterName,
                    createdAt = start.toString(), updatedAt = start.toString()))
            }
            (0 until 24).map { index ->
                // The production single-window guard allows only one companion per character.
                // Keep that guard active and give every synthetic companion its own owner.
                val ownerId = if (page == SessionPage.COMPANION) "$characterId-$index" else characterId
                val ownerName = if (page == SessionPage.COMPANION) "$characterName$index" else characterName
                if (page == SessionPage.COMPANION) {
                    db.characterDao().upsert(LocalCharacterEntity(ownerId, ownerName,
                        createdAt = start.toString(), updatedAt = start.toString()))
                }
                val title = "合成${text(page.titleResId)}会话${index.toString().padStart(2, '0')}" +
                    if (page == SessionPage.CHARACTER && index == 0) "莲花" else ""
                val mode = when {
                    page != SessionPage.CHARACTER -> "agent"
                    index == 1 -> ""
                    index == 2 -> "group"
                    else -> "character"
                }
                val time = start.plusSeconds((100 - index) * 60L).toString()
                LocalSessionEntity("synthetic-v2-${page.name}-$index", "合成原会话名-$index",
                    characterId = if (page == SessionPage.AGENT && index == 0) null else ownerId,
                    characterName = if (page == SessionPage.AGENT && index == 0) null else ownerName,
                    senderName = if (page == SessionPage.AGENT && index == 0) "纸舟" else "合成发言者",
                    listTitleOverride = title, sessionMode = mode,
                    groupId = if (mode == "group") "synthetic-v2-group" else null,
                    inheritCharacter = page == SessionPage.COMPANION || (page == SessionPage.AGENT && index == 1),
                    longConversationEnabled = page == SessionPage.COMPANION,
                    companionMode = page == SessionPage.COMPANION,
                    favorite = page == SessionPage.CHARACTER && index == 0,
                    pinned = page == SessionPage.COMPANION && index == 0,
                    lastMessage = if (page == SessionPage.COMPANION && index == 0) "合成末条原话：晚灯" else "合成末条消息 $index",
                    createdAt = time, updatedAt = time).also { db.sessionDao().upsert(it) }
            }
        }
        Fixture(db, rows)
    }

    private fun launch(fixture: Fixture, small: Boolean = false) {
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[SessionsViewModel::class.java] }
        contentInstalled = true
        compose.setContent {
            if (visible) {
                val density = LocalDensity.current
                val context = remember(small) {
                    compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                        setLocale(Locale.SIMPLIFIED_CHINESE)
                        screenWidthDp = if (small) 320 else 390
                        screenHeightDp = if (small) 560 else 640
                        fontScale = if (small) 1.6f else 1f
                    })
                }
                CompositionLocalProvider(
                    LocalContext provides context, LocalResources provides context.resources,
                    LocalConfiguration provides context.resources.configuration,
                    LocalDensity provides Density(density.density, if (small) 1.6f else 1f),
                    LocalActivityResultRegistryOwner provides compose.activity,
                    LocalOnBackPressedDispatcherOwner provides compose.activity
                ) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        Box(Modifier.sizeIn(maxWidth = if (small) 320.dp else 390.dp,
                            maxHeight = if (small) 560.dp else 640.dp).fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing).testTag(ROOT)
                            .background(MaterialTheme.colorScheme.background)) {
                            val state = rememberSaveableStateHolder()
                            BackHandler { fallbackBacks++ }
                            if (destination == null) state.SaveableStateProvider("sessions") {
                                SessionsScreen(onOpenChat = { destination = "chat/$it" },
                                    onOpenDetail = { destination = "detail/$it" },
                                    onNavigate = { destination = "navigate/$it" }, isActive = active)
                            } else {
                                BackHandler { destination = null }
                                Column {
                                    Text("合成离线目的页")
                                    Text(requireNotNull(destination), Modifier.testTag(DESTINATION))
                                    Button(onClick = { destination = null }, modifier = Modifier.testTag(RETURN)) { Text("返回合成会话") }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { vm.sessionRowsByPage.value.values.sumOf { it.size } == fixture.count }
        compose.onNodeWithTag("sessions-page-character").assertIsSelected()
    }

    private fun select(page: SessionPage) {
        compose.onNodeWithTag(tabTag(page)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tabTag(page)).assertIsSelected()
        compose.runOnIdle { assertEquals(page, vm.selectedPage) }
    }

    private fun tabTag(page: SessionPage) = "sessions-page-${page.name.lowercase()}"

    private fun setSearch(page: SessionPage, query: String, filter: SessionFilter = SessionFilter.ALL, channel: String? = null) {
        select(page)
        compose.onNodeWithContentDescription(text(R.string.sessions_page_search_action, text(page.titleResId))).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
        if (channel != null) compose.onNode(hasText(channel) and hasClickAction()).performScrollTo().performClick()
        if (filter != SessionFilter.ALL) compose.onNode(hasText(text(filter.labelResId)) and hasClickAction())
            .performScrollTo().performClick()
        // Dismiss the production sheet through its accessibility action, independent of IME state.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
    }

    private fun awaitRows(page: SessionPage, ids: Set<String>) {
        compose.waitUntil(15_000) { vm.sessionRowsByPage.value[page]?.map { it.id }?.toSet() == ids }
    }

    private fun verticalList(): SemanticsNodeInteraction {
        // HorizontalPager precomposes neighbours; only target the vertical list in this viewport.
        return compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and inViewport())
    }

    private fun inViewport(): SemanticsMatcher {
        val viewport = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        return SemanticsMatcher("intersects the synthetic viewport") { node ->
            val b = node.boundsInRoot
            b.width > 1f && b.height > 1f && b.left < viewport.right && b.right > viewport.left &&
                b.top < viewport.bottom && b.bottom > viewport.top
        }
    }

    private fun anchor(page: SessionPage): Anchor = compose.runOnIdle {
        val state = vm.scrollState(page)
        Anchor(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.layoutInfo.visibleItemsInfo.firstOrNull()?.key)
    }

    private fun assertAnchor(page: SessionPage, expected: Anchor) {
        compose.waitForIdle()
        assertEquals(expected, anchor(page))
    }

    private fun awaitDestination(expected: String) {
        compose.waitUntil(15_000) { destination == expected }
        compose.onNodeWithTag(DESTINATION).assertTextEquals(expected).assertIsDisplayed()
    }

    private fun returnFromDestination() {
        compose.onNodeWithTag(RETURN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tabTag(vm.selectedPage)).assertIsSelected()
    }

    private fun openDashboard() {
        compose.onNodeWithContentDescription(text(R.string.sessions_page_menu)).performClick()
        compose.onNodeWithText(text(R.string.stats_dashboard_title)).performClick()
        compose.onNodeWithText(text(R.string.stats_dashboard_title)).assertIsDisplayed()
    }

    private fun closeCreateDialog() {
        val close = compose.onNode(hasContentDescription(text(R.string.common_close)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertIsEnabled()
        val node = close.fetchSemanticsNode()
        val touch = node.touchBoundsInRoot
        val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
        val tolerance = with(node.layoutInfo.density) { 0.5.dp.toPx() }
        val viewport = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        // Material's close glyph draws at 40dp; the actual interactive target remains 48dp.
        assertTrue(touch.width + tolerance >= minimum && touch.height + tolerance >= minimum)
        assertTrue(touch.left >= viewport.left - tolerance && touch.right <= viewport.right + tolerance &&
            touch.top >= viewport.top - tolerance && touch.bottom <= viewport.bottom + tolerance)
        close.performTouchInput { click() }
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun text(resource: Int, vararg args: Any): String = compose.activity.createConfigurationContext(
        Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
    ).getString(resource, *args)

    private fun capture(name: String, window: SemanticsMatcher? = null) {
        assertTrue(ServiceContainer.prefs.activeDbName.startsWith("sessions_v2_ui_"))
        compose.waitForIdle()
        val bitmap = (window?.let { compose.onNode(it) } ?: compose.onNodeWithTag(ROOT)).captureToImage().asAndroidBitmap()
        try {
            val folder = requireNotNull(compose.activity.getExternalFilesDir("session-tabs-v2"))
            assertTrue(folder.isDirectory || folder.mkdirs())
            File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val ROOT = "sessions-v2-synthetic-root"
        const val DESTINATION = "sessions-v2-synthetic-destination"
        const val RETURN = "sessions-v2-synthetic-return"
    }
}
