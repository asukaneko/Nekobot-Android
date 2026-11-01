package com.nekobot.app.ui.screens.memory

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.UUID

class FusedCompanionMemoryScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    /** 界面文案固定用简体中文，避免测试结果跟随模拟器系统语言变化。 */
    private fun chineseContext() = compose.activity.createConfigurationContext(
        Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
    @Composable private fun Screen(id: String) {
        val context = remember { chineseContext() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            MaterialTheme { FusedCompanionMemoryScreen(id, {}, {}) }
        }
    }
    @Test fun memoryPageShowsSeparateControlsAndSavesManualRecord() {
        val id="fused-ui-${UUID.randomUUID()}"
        val db=NekobotDatabase.get(compose.activity,ServiceContainer.prefs.activeDbName)
        val session=LocalSessionEntity(id,"Synthetic UI",characterId="$id-character",sessionMode="agent",
            inheritCharacter=true,longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now")
        try {
            runBlocking { db.sessionDao().upsert(session); CompanionMemoryStore(db).ensureScope(session) }
            compose.setContent { Screen(id) }
            compose.onNodeWithText(chineseContext().getString(R.string.agent_character_memory_title)).assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("新增记忆") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("新增记忆").performClick()
            compose.onNodeWithText("记忆正文").performTextInput("难过时希望先被倾听")
            // A database refresh from ongoing chat must not discard a user's unsaved edit.
            runBlocking { db.messageDao().upsert(LocalMessageEntity("$id-message",id,"user","后台消息",timestamp="now",createdAt="now")) }
            compose.waitForIdle()
            compose.onNodeWithText("添加一条记忆").assertIsDisplayed()
            compose.onNode(hasText("只允许我修改") and isToggleable()).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("设为核心记忆") and isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("保存记忆",useUnmergedTree=true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("添加一条记忆").fetchSemanticsNodes().isEmpty() }
            runBlocking {
                val store=CompanionMemoryStore(db); val fact=store.validatedMemories(store.ensureScope(session)).single()
                assertTrue(fact.alwaysProvide); assertFalse(fact.locked); assertTrue(fact.manual)
            }
            // The database save completes before the asynchronous list refresh is drawn.
            compose.waitUntil(5000) { compose.onAllNodesWithText("难过时希望先被倾听").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasContentDescription("更多记忆操作"))
            compose.onNodeWithContentDescription("更多记忆操作").performClick()
            compose.onNodeWithText("修改记录").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("当前内容").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("修改记录").assertIsDisplayed()
        } finally { runBlocking { db.sessionDao().deleteById(id) } }
    }

    @Test fun importedUnverifiedMemorySourceIsNotLabelledAsManualEntry() {
        val id = "imported-ui-${UUID.randomUUID()}"
        val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        val session = LocalSessionEntity(id, "来源说明测试", characterId = "$id-character", sessionMode = "agent",
            inheritCharacter = true, longConversationEnabled = true, companionMode = true, createdAt = "now", updatedAt = "now")
        try {
            runBlocking {
                db.sessionDao().upsert(session)
                val scope = CompanionMemoryStore(db).ensureScope(session)
                db.companionMemoryDao().saveMemory(com.nekobot.app.data.local.db.CompanionMemoryEntity(
                    "$id-memory", scope.id, "tea", 1, "fact", "玩家", "喜欢茉莉茶", "active",
                    provenance = "imported_unverified", createdAt = "old", updatedAt = "old"))
            }
            compose.setContent { Screen(id) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("喜欢茉莉茶").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasText("查看原话"))
            compose.onNodeWithText("查看原话").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("这条记忆来自旧资料，原话来源还没有核验。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("这条记忆由你手动填写，没有附带聊天来源。").assertDoesNotExist()
        } finally { runBlocking { db.sessionDao().deleteById(id) } }
    }
}
