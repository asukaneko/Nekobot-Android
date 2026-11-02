package com.nekobot.app.ui.screens.sessions

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.RelationshipBackground
import com.nekobot.app.ui.components.NekoSegmentedControl
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** 使用合成数据验证实际组件，不访问用户会话或模型。 */
class SessionJournalLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val context = remember {
            compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            })
        }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = false, content = content)
        }
    }

    @Test fun twoLineRowsEnterSelectionThroughLongPressWithoutOpeningChat() {
        var opened = false
        var selectionMode by mutableStateOf(false)
        var selectedId by mutableStateOf<String?>(null)
        val rows = (0..3).map { index -> SessionListRow(
            key = "demo-$index", id = "demo-$index", name = "Demo session $index",
            displayName = "Demo session $index", portraitUrl = null,
            characterLabel = "Demo character $index", updatedAt = "2026-01-01",
            lastMessage = "Demo preview $index", messageCount = 900 + index,
            pinned = false, favorite = false, archived = false
        ) }
        compose.setContent { Theme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                    Text("Demo sessions", style = MaterialTheme.typography.headlineSmall)
                    NekoSegmentedControl(listOf("Demo all", "Demo characters", "Demo agents"), 0, {})
                    rows.forEachIndexed { index, row ->
                        SessionItem(row, first = index == 0, last = index == rows.lastIndex,
                            onClick = { opened = true },
                            onLongPress = { selectionMode = true; selectedId = row.id },
                            selectionMode = selectionMode, selected = selectedId == row.id,
                            onToggleSelection = { selectedId = if (selectedId == row.id) null else row.id })
                    }
                }
            }
        } }
        compose.onNodeWithText(rows.first().listTitle, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(rows.first().listTitle).performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(selectionMode); assertEquals(rows.first().id, selectedId); assertFalse(opened)
        }
        compose.onNodeWithText(rows.first().listTitle).performClick()
        compose.runOnIdle { assertNull(selectedId); assertFalse(opened) }
    }

    @Test fun sevenSkinsRenderAndFeelingTabsKeepTheirContent() {
        var scene by mutableStateOf(RelationshipBackground.COAST)
        val values = listOf(JournalFeeling("Demo mood", "Demo mood value", {}),
            JournalFeeling("Demo intensity", "Demo intensity value", {}),
            JournalFeeling("Demo energy", "Demo energy value", {}))
        compose.setContent { Theme { RelationshipSkinTheme(scene) {
            RelationshipJournalBackdrop(scene) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Demo relationship", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
                    RelationshipPaper {
                        JournalHeading("Demo feelings")
                        RelationshipFeelingDeck("demo", values)
                    }
                    JournalAction("Demo settings", "Demo options", Icons.Filled.Tune, {})
                }
            }
        } } }
        RelationshipBackground.entries.forEach { background ->
            compose.runOnIdle { scene = background }
            compose.onNodeWithTag("relationship_feeling_0").assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("relationship_scene_${background.id}_ready").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithTag("relationship_feeling_1").performClick()
        compose.onNodeWithText("Demo intensity value").assertIsDisplayed()
    }
}
