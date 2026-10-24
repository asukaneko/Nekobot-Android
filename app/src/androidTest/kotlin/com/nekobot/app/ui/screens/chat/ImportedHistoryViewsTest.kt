package com.nekobot.app.ui.screens.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ImportedFavoriteSnapshot
import com.nekobot.app.data.local.ImportedFavoriteSnapshotResult
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Synthetic records only. Production parser/view logic, no repositories, API calls or user files. */
class ImportedHistoryViewsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Theme(scale: Float = 1f, content: @Composable () -> Unit) {
        val context = remember { compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
        CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
            LocalConfiguration provides context.resources.configuration) {
            NekobotTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) { content() }
            }
        }
    }

    private val forwarded = "保留原始转发段落。\n".repeat(80) + "完整转发结尾"
    private val snapshots get() = ImportedFavoriteSnapshotResult(listOf(ImportedFavoriteSnapshot(
        "原来的角色名", "1760000000000", "1759999000000", "[转发消息]", forwarded)))

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "imported-history-$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun currentFavoritesAndImportedSnapshotsRemainSeparateAndBothReadable() {
        val folder = JsonParser.parseString("""{"id":"existing","title":"现有收藏夹","messages":[{"id":"message","role":"user","content":"当前聊天正文"}]}""").asJsonObject
        var selected by mutableStateOf<com.google.gson.JsonObject?>(null)
        var deleted = 0
        compose.setContent { Theme {
            CompanionFavoritesReading(listOf(folder), false, selected, "当前角色", {}, { selected = it }, { deleted++ },
                importedSnapshots = { ImportedFavoriteSnapshotsSection(snapshots) })
        } }
        compose.onNodeWithText("现有收藏夹").assertIsDisplayed()
        capture("favorites-entry")
        compose.onNodeWithText("迁入的收藏快照").performClick()
        compose.onNodeWithText("原来的角色名").performClick()
        compose.onNodeWithText(forwarded).assertExists()
        compose.onNodeWithText("[转发消息]").assertExists()
        capture("favorite-reading")
        compose.onNodeWithText("跳转聊天").assertDoesNotExist()
        compose.onNodeWithText("删除收藏").assertDoesNotExist()
        compose.onNodeWithText("返回快照").performScrollTo().performClick()
        compose.onNodeWithText("阅读全文").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("现有收藏夹").performClick()
        compose.onNodeWithText("当前聊天正文").assertIsDisplayed()
        assertEquals(0, deleted)
    }

    @Test fun largeTextSnapshotContentAndCloseRemainReachable() {
        compose.setContent { Theme(1.6f) {
            Column(Modifier.fillMaxSize().widthIn(max = 320.dp)) { ImportedFavoriteSnapshotsSection(snapshots) }
        } }
        compose.onNodeWithText("迁入的收藏快照").performClick()
        compose.onNodeWithText("阅读全文").performScrollTo().performClick()
        compose.onNodeWithText(forwarded).assertExists()
        capture("favorite-large-reading")
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithText("迁入的收藏快照").assertIsDisplayed()
    }

    @Test fun originalImagePromptOpensInAReadOnlyScrollableReaderAtLargeText() {
        val prompt = "历史图片的原始说明。\n".repeat(80) + "原始提示结尾"
        compose.setContent { Theme(1.6f) { ImportedImageDescriptionButton(prompt) } }
        compose.onNodeWithContentDescription("原始提示与说明").performClick()
        compose.onNodeWithText(prompt).assertExists()
        capture("image-description")
        compose.onNodeWithText("生成图片").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("原始提示与说明").assertIsDisplayed()
    }

    @Test fun missingSnapshotsAndBlankImagePromptDoNotAddDeadActions() {
        compose.setContent { Theme {
            Column {
                ImportedFavoriteSnapshotsSection(ImportedFavoriteSnapshotResult())
                ImportedImageDescriptionButton("")
            }
        } }
        compose.onNodeWithText("迁入的收藏快照").assertDoesNotExist()
        compose.onNodeWithContentDescription("原始提示与说明").assertDoesNotExist()
    }
}
