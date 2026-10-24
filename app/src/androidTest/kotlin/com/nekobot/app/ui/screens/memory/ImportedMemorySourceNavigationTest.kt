package com.nekobot.app.ui.screens.memory

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic data only. Both screens use the active database and the production repository reader. */
@RunWith(AndroidJUnit4::class)
class ImportedMemorySourceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var fixtureVisible by mutableStateOf(true)
    private var contentInstalled = false

    private data class Fixture(
        val session: LocalSessionEntity,
        val memory: CompanionMemoryEntity,
        val message: LocalMessageEntity,
        val quote: String
    )

    @Test fun importedVerifiedCoreMemoryShowsExactQuoteLocatesStoredMessageAndReturns() {
        withSyntheticProfile { fixture ->
            launch(fixture, small = false)
            inspectSourceAndReturn(fixture, "normal", systemBack = false)
        }
    }

    @Test fun smallViewportAndLargeFontKeepSourceAndLocationActionsReachable() {
        withSyntheticProfile { fixture ->
            launch(fixture, small = true)
            inspectSourceAndReturn(fixture, "small-large-font", systemBack = true)
        }
    }

    private fun withSyntheticProfile(block: (Fixture) -> Unit) {
        // Skip physical devices before changing a profile or creating fixture data.
        assumeTrue("Synthetic navigation fixtures require an emulator",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = ServiceContainer.prefs
        val previousProfile = prefs.activeDbName
        val previousMode = prefs.appMode
        val profile = "imported_source_navigation_${UUID.randomUUID().toString().replace("-", "")}"
        var failure: Throwable? = null
        try {
            // Bind the empty profile before enabling local mode, so no previous data is rendered.
            ServiceContainer.switchLocalDb(profile)
            ServiceContainer.switchAppMode(AppMode.LOCAL)
            assertEquals(profile, prefs.activeDbName)
            block(seedFixture(NekobotDatabase.get(compose.activity, profile)))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Attempt every restoration even if a UI assertion or an earlier cleanup step fails.
            var cleanupFailure: Throwable? = null
            val cleanup: List<() -> Unit> = listOf(
                {
                    if (contentInstalled) {
                        compose.runOnIdle { fixtureVisible = false }
                        compose.waitForIdle()
                    }
                },
                { compose.runOnUiThread { compose.activity.viewModelStore.clear() } },
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

    private fun seedFixture(db: NekobotDatabase): Fixture = runBlocking {
        val id = "synthetic-source-${UUID.randomUUID()}"
        val stamp = Instant.parse("2026-01-01T00:00:00Z")
        val characterId = "$id-character"
        db.characterDao().upsert(LocalCharacterEntity(characterId, "合成来源角色",
            createdAt = stamp.toString(), updatedAt = stamp.toString()))
        val session = LocalSessionEntity(id, "合成来源定位会话", characterId = characterId,
            characterName = "合成来源角色", sessionMode = "agent", inheritCharacter = true,
            longConversationEnabled = true, companionMode = true,
            createdAt = stamp.toString(), updatedAt = stamp.toString())
        db.sessionDao().upsert(session)
        val quote = "我只喝无糖茉莉茶。\n这是合成来源，保留原句标点。"
        val original = LocalMessageEntity("$id-original", id, "user",
            "合成原话开始。\n$quote\n合成原话结束。",
            timestamp = stamp.plusSeconds(20 * 60L).toString(),
            createdAt = stamp.plusSeconds(20 * 60L).toString())
        // Enough surrounding rows to require ChatLocationScreen's initial anchor positioning.
        (0..40).forEach { index ->
            val time = stamp.plusSeconds(index * 60L).toString()
            db.messageDao().upsert(if (index == 20) original else LocalMessageEntity(
                "$id-context-$index", id, if (index % 2 == 0) "user" else "assistant",
                "合成上下文消息 $index", timestamp = time, createdAt = time))
        }
        val scope = CompanionMemoryStore(db).ensureScope(session)
        val memory = CompanionMemoryEntity("$id-memory", scope.id, "synthetic-tea", 1,
            "core", "合成用户", "合成记忆：饮品只选无糖茉莉茶。", "active",
            category = "user_profile", alwaysProvide = false, manual = false,
            provenance = "imported_verified", createdAt = stamp.toString(), updatedAt = stamp.toString())
        val source = CompanionSourceEntity(memory.id, original.id,
            CompanionMemoryPolicy.fingerprint(original), quote, "2026-01-01", "user")
        db.companionMemoryDao().saveMemory(memory)
        db.companionMemoryDao().saveSources(listOf(source))

        // Exercise the same validation and source lookup used by the memory screen.
        val repository = ServiceContainer.localRepository
        val loaded = repository.listCompanionMemories(id).single()
        assertEquals(memory, loaded)
        assertEquals("imported_verified", loaded.provenance)
        assertFalse(loaded.manual)
        assertTrue(CompanionCoreMemory.contains(loaded))
        assertEquals(listOf(source), repository.fusedMemorySources(id, memory.id))
        Fixture(session, memory, original, quote)
    }

    private fun launch(fixture: Fixture, small: Boolean) {
        contentInstalled = true
        compose.setContent {
            if (fixtureVisible) {
                val density = LocalDensity.current
                val context = remember(small) {
                    compose.activity.createConfigurationContext(
                        Configuration(compose.activity.resources.configuration).apply {
                            setLocale(Locale.SIMPLIFIED_CHINESE)
                            fontScale = if (small) 1.6f else 1f
                            if (small) { screenWidthDp = 320; screenHeightDp = 560 }
                        }
                    )
                }
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalResources provides context.resources,
                    LocalConfiguration provides context.resources.configuration,
                    LocalDensity provides Density(density.density, if (small) 1.6f else 1f)
                ) {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        val viewport = if (small) Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp)
                            else Modifier
                        Box(viewport.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag(ROOT_TAG)
                            .background(MaterialTheme.colorScheme.background)) {
                            val savedState = rememberSaveableStateHolder()
                            var locatedMessageId by rememberSaveable { mutableStateOf<String?>(null) }
                            val messageId = locatedMessageId
                            if (messageId == null) {
                                savedState.SaveableStateProvider("memory") {
                                    FusedCompanionMemoryScreen(fixture.session.id, {},
                                        onLocate = { locatedMessageId = it })
                                }
                            } else {
                                BackHandler { locatedMessageId = null }
                                // No injected ViewModel or read callbacks: use the real DB-backed reader.
                                ChatLocationScreen(fixture.session.id, messageId,
                                    onBack = { locatedMessageId = null }, onLatest = {})
                            }
                        }
                    }
                }
            }
        }
    }

    private fun inspectSourceAndReturn(fixture: Fixture, name: String, systemBack: Boolean) {
        val coreEntry = hasContentDescription(text(R.string.single_role_view_core_memories))
        awaitMemoryReady()
        compose.onNodeWithTag("fused-memory-list").performScrollToNode(coreEntry)
        compose.onNode(coreEntry).performClick()
        val viewSource = hasText(text(R.string.single_role_view_original_messages)) and hasClickAction()
        compose.onNodeWithTag("fused-memory-list").performScrollToNode(viewSource)
        val sourceButton = compose.onNode(viewSource).performScrollTo().assertIsDisplayed()
        assertReachableTouchTarget(sourceButton, compose.onNodeWithTag(ROOT_TAG))
        compose.onNodeWithText(fixture.memory.content).assertExists()
        val sourceBounds = sourceButton.getUnclippedBoundsInRoot()
        val viewportBounds = compose.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        assertTrue("The source button must fit inside the memory viewport",
            sourceBounds.top >= viewportBounds.top && sourceBounds.bottom <= viewportBounds.bottom &&
                sourceBounds.left >= viewportBounds.left && sourceBounds.right <= viewportBounds.right)
        compose.onNodeWithText(text(R.string.single_role_manual_memory)).assertDoesNotExist()
        captureSynthetic("$name-memory-source-action")
        sourceButton.performTouchInput { click() }

        val dialogList = hasScrollAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(dialogList).performScrollToNode(hasText(fixture.quote))
        compose.onNodeWithText(fixture.quote).assertTextEquals(fixture.quote).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.single_role_you_entered_this_memory_manually_it_has_no_linked_chat_sources))
            .assertDoesNotExist()
        captureSynthetic("$name-source-quote", dialog = true)
        val locate = hasText(text(R.string.single_role_go_to_this_message)) and hasClickAction()
        compose.onNode(dialogList).performScrollToNode(locate)
        val locateButton = compose.onNode(locate).performScrollTo().assertIsDisplayed()
        assertReachableTouchTarget(locateButton, compose.onNode(isDialog()))
        captureSynthetic("$name-source-location-action", dialog = true)
        locateButton.performTouchInput { click() }

        // Do not scroll here: the real location screen must reveal the stored message itself.
        val sourceTag = "source-message:${fixture.message.id}"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(sourceTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(sourceTag).assertIsDisplayed().assertTextEquals(fixture.message.content)
        compose.onNodeWithText(text(R.string.chat_location_target)).assertIsDisplayed()
        compose.onNodeWithText(fixture.session.name).assertIsDisplayed()
        captureSynthetic("$name-location")
        if (systemBack) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        else compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        awaitMemoryReady()
        compose.onNodeWithTag("fused-memory-list").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasText(fixture.memory.content))
        compose.onNodeWithText(fixture.memory.content).assertIsDisplayed()
        compose.onNodeWithTag(sourceTag).assertDoesNotExist()
    }

    private fun assertReachableTouchTarget(button: SemanticsNodeInteraction, viewport: SemanticsNodeInteraction) {
        val node = button.assertIsDisplayed().assertIsEnabled().assertHasClickAction().fetchSemanticsNode()
        // Material 3 TextButton can draw at 40dp while exposing a 48dp minimum touch target.
        // Measure the hit bounds rather than weakening the minimum or asserting the visual height.
        val touch = node.touchBoundsInRoot
        val minimum = with(node.layoutInfo.density) { 48.dp.toPx() }
        val tolerance = with(node.layoutInfo.density) { 0.5.dp.toPx() }
        assertTrue("Touch width ${touch.width}px must be at least ${minimum}px", touch.width + tolerance >= minimum)
        assertTrue("Touch height ${touch.height}px must be at least ${minimum}px", touch.height + tolerance >= minimum)
        val visible = viewport.fetchSemanticsNode().boundsInRoot
        assertTrue("The complete touch target must fit inside the visible viewport",
            touch.left >= visible.left - tolerance && touch.right <= visible.right + tolerance &&
                touch.top >= visible.top - tolerance && touch.bottom <= visible.bottom + tolerance)
    }

    private fun awaitMemoryReady() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(text(R.string.memory_add_title)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(resource: Int): String = compose.activity.createConfigurationContext(
        Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
    ).getString(resource)

    private fun captureSynthetic(name: String, dialog: Boolean = false) {
        assertTrue(ServiceContainer.prefs.activeDbName.startsWith("imported_source_navigation_"))
        compose.waitForIdle()
        // Capture only the synthetic Compose window, never the emulator's other windows or system UI.
        val node = if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag(ROOT_TAG)
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            val folder = requireNotNull(compose.activity.getExternalFilesDir("imported-memory-source-navigation"))
            assertTrue(folder.isDirectory || folder.mkdirs())
            File(folder, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val ROOT_TAG = "imported-memory-source-navigation-root"
    }
}
