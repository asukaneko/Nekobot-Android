package com.nekobot.app.ui.screens.memory

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionImportPolicy
import com.nekobot.app.data.local.ai.CompanionMemorySourceView
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** The production source reader with synthetic in-memory rows, no DB or model calls. */
class ImportedPartialMemorySourcesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun partialSourcesKeepUnverifiedWarningAndOriginalMessageAction() = inspect(scale = 1f, suffix = "normal")

    @Test fun largeTextKeepsPartialWarningQuoteAndOriginalMessageActionReachable() = inspect(scale = 1.6f, suffix = "large")

    private fun inspect(scale: Float, suffix: String) {
        val context = compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val memory = CompanionMemoryEntity("synthetic-memory", "synthetic-scope", "synthetic-tea", 1,
            "core", "合成用户", "合成记忆：偏爱茶饮，并且总在周末去书店。", "active",
            provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED,
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z")
        val quote = "我喜欢清香的茶。这里是合成原话，保留原句标点。"
        val source = CompanionSourceEntity(memory.id, "synthetic-message", "synthetic-fingerprint", quote, "2026-01-01", "user")
        val located = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    CompanionMemorySources(listOf(CompanionMemorySourceView(source, quote,
                        originalDeleted = false, snapshot = false)), memory, "合成角色", {}, { located += it })
                }
            }
        }
        val title = "已关联原话 · 部分内容待核实"
        val message = "以下原话支持记忆中的部分内容，其他细节仍待核实。"
        reveal(title)
        reveal(message)
        capture("$suffix-warning")
        reveal(quote)
        val locate = context.getString(R.string.single_role_go_to_this_message)
        reveal(locate)
        capture("$suffix-quote")
        compose.onNodeWithText(locate).performClick()
        assertEquals(listOf(source.messageId), located)
        assertEquals(CompanionImportPolicy.IMPORTED_UNVERIFIED, memory.provenance)
        assertEquals(quote, source.quote)
    }

    private fun reveal(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "imported-history-partial-sources-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
