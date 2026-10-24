package com.nekobot.app.ui.screens.memory

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/** Production page, Room source lookup, source dialog and core list; synthetic data only. */
class MemoryReviewClarityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun partialSourcesAndCoreSuggestionUseOneStatusAndPreserveRawData() = page(false, 1f, "light")
    @Test fun largeTextInDarkThemeKeepsSourceAndCoreActionsReachable() = page(true, 1.6f, "dark-large")

    private fun page(dark: Boolean, scale: Float, suffix: String) {
        val id = "clarity-${UUID.randomUUID()}"
        val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        val localized = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val s = LocalSessionEntity(id, "合成提示验收", characterId = "$id-role", sessionMode = "agent", companionMode = true,
            inheritCharacter = true, longConversationEnabled = true, createdAt = "now", updatedAt = "now")
        val quote = "我喜欢清香的茶。"
        val originalTags = "[\"爱语原有记忆\",\"原话来源未验证\",\"茶饮\"]"
        var located: String? = null
        try {
            runBlocking {
                db.characterDao().upsert(LocalCharacterEntity("$id-role", "合成角色", createdAt = "now", updatedAt = "now"))
                db.sessionDao().upsert(s)
                val scope = CompanionMemoryStore(db).ensureScope(s)
                val message = LocalMessageEntity("$id-source", id, "user", quote, timestamp = "2026-01-01T10:00:00Z", createdAt = "2026-01-01T10:00:00Z")
                db.messageDao().upsert(message)
                db.companionMemoryDao().saveMemory(CompanionMemoryEntity("$id-memory", scope.id, "00-partial", 1,
                    "fact", "合成用户", "偏爱茶饮，并且总在周末去书店。", "active", tagsJson = originalTags,
                    provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED, createdAt = "now", updatedAt = "now"))
                db.companionMemoryDao().saveSources(listOf(CompanionSourceEntity("$id-memory", message.id, CompanionMemoryPolicy.fingerprint(message), quote, "2026-01-01", "user")))
                repeat(3) { i -> db.companionMemoryDao().saveMemory(CompanionMemoryEntity("$id-core-$i", scope.id, "core-$i", 1,
                    "fact", "合成用户", "合成稳定习惯$i".repeat(130), "active", alwaysProvide = true, manual = true, createdAt = "now", updatedAt = "now")) }
                assertEquals(setOf("$id-memory"), ServiceContainer.localRepository.companionMemoriesWithSources(id))
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                    LocalConfiguration provides localized.resources.configuration, LocalDensity provides Density(density.density, scale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { FusedCompanionMemoryScreen(id, {}, { located = it }) }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("部分内容待核实").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("fused-memory-list")
            list.performScrollToNode(hasText("部分内容待核实"))
            compose.onAllNodesWithText("部分内容待核实").assertCountEquals(1)
            compose.onAllNodesWithText("来自迁入资料").assertCountEquals(1)
            compose.onNodeWithText("#原话来源未验证").assertDoesNotExist()
            compose.onNodeWithText("#爱语原有记忆").assertDoesNotExist()
            compose.onNodeWithText("#茶饮").assertExists()
            val sourceButton = hasText("查看原话") and hasAnyAncestor(hasTestTag("companion-memory-$id-memory"))
            list.performScrollToNode(sourceButton)
            compose.waitUntil(5000) { compose.onAllNodes(sourceButton and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(sourceButton).assertIsDisplayed()
            capture("$suffix-card")
            compose.onNode(sourceButton).performClick()
            val dialogList = hasScrollToIndexAction() and hasAnyAncestor(isDialog())
            compose.onNode(dialogList).performScrollToNode(hasText("已关联原话 · 部分内容待核实"))
            compose.onNodeWithText("已关联原话 · 部分内容待核实").assertIsDisplayed()
            capture("$suffix-sources")
            val locate = localized.getString(R.string.single_role_go_to_this_message)
            compose.onNode(dialogList).performScrollToNode(hasText(locate))
            compose.onNodeWithText(locate).performClick()
            assertEquals("$id-source", located)
            list.performScrollToIndex(0)
            compose.onNodeWithContentDescription(localized.getString(R.string.single_role_view_core_memories)).performClick()
            val count = localized.getString(R.string.single_role_1_s_core_memories, 3)
            compose.waitUntil(5000) { compose.onAllNodesWithText(count).fetchSemanticsNodes().isNotEmpty() }
            val advisory = localized.getString(R.string.single_role_core_memories_exceed_the_target_size_and_will_still_be_included_i)
            compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasText(advisory))
            compose.onNodeWithText(advisory).assertIsDisplayed()
            assertTrue(advisory.contains("可继续保留或新增"))
            capture("$suffix-core")
            runBlocking {
                val scope = CompanionMemoryStore(db).ensureScope(s)
                val rows = db.companionMemoryDao().memories(scope.id)
                val saved = rows.single { it.id == "$id-memory" }
                assertEquals(originalTags, saved.tagsJson)
                assertEquals(CompanionImportPolicy.IMPORTED_UNVERIFIED, saved.provenance)
                assertEquals(3, CompanionCoreMemory.rows(rows).size)
                assertTrue(CompanionCoreMemory.tokens(CompanionCoreMemory.rows(rows)) > 1000)
                assertEquals(quote, db.companionMemoryDao().sources(saved.id).single().quote)
            }
        } finally { runBlocking { db.sessionDao().deleteById(id); db.characterDao().deleteById("$id-role") } }
    }

    private fun capture(name: String) {
        val dir = File(compose.activity.getExternalFilesDir(null), "memory-clarity").apply { mkdirs() }
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
