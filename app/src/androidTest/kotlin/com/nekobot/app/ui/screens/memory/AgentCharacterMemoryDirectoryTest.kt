package com.nekobot.app.ui.screens.memory

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.screens.capabilities.CapabilitiesScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AgentCharacterMemoryDirectoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(compose.activity.cacheDir,"agent-memory-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun moreEntrySelectsIndependentOwnerAndNeverShowsOtherOwnersMemory() {
        val prefix = "directory-${UUID.randomUUID()}"
        val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        val sessions = (1..3).map { n -> LocalSessionEntity("$prefix-$n", "独立测试窗口$n",
            characterId="$prefix-c$n", sessionMode="agent", inheritCharacter=true,
            longConversationEnabled=n!=3, companionMode=n!=3, createdAt="now", updatedAt="now") }
        try {
            runBlocking {
                sessions.forEachIndexed { index, session ->
                    db.characterDao().upsert(LocalCharacterEntity(session.characterId!!, "测试角色${index+1}", createdAt="now", updatedAt="now"))
                    db.sessionDao().upsert(session)
                }
                val store = CompanionMemoryStore(db)
                store.editFused(sessions[0],"favorite","角色甲独有的蓝色茶杯","user_profile",emptyList(),true,false)
                store.editFused(sessions[1],"favorite","角色乙独有的红色围巾","user_profile",emptyList(),true,false)
            }
            var destination = ""
            compose.setContent {
                var page by remember { mutableStateOf("more") }
                var owner by remember { mutableStateOf("") }
                MaterialTheme { when (page) {
                    "more" -> CapabilitiesScreen({ destination = it; page = it })
                    Routes.AGENT_CHARACTER_MEMORY -> AgentCharacterMemoryDirectoryScreen({ page="more" }, { owner=it; page="detail" })
                    else -> FusedCompanionMemoryScreen(owner,{ page=Routes.AGENT_CHARACTER_MEMORY },{})
                } }
            }
            val title=compose.activity.getString(R.string.agent_character_memory_title)
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(title))
            screenshot("more")
            compose.onNodeWithText(title).performClick()
            assertEquals(Routes.AGENT_CHARACTER_MEMORY, destination)
            compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-character-owner-${sessions[0].id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("agent-character-owner-${sessions[2].id}").assertDoesNotExist()
            compose.onNodeWithTag("agent-character-memory-owners").performScrollToNode(hasTestTag("agent-character-owner-${sessions[0].id}"))
            screenshot("directory")
            compose.onNodeWithTag("agent-character-owner-${sessions[0].id}").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("角色甲独有的蓝色茶杯").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("角色乙独有的红色围巾").assertDoesNotExist()
            screenshot("detail")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("agent-character-owner-${sessions[1].id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("agent-character-memory-owners").performScrollToNode(hasTestTag("agent-character-owner-${sessions[1].id}"))
            compose.onNodeWithTag("agent-character-owner-${sessions[1].id}").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("角色乙独有的红色围巾").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("角色甲独有的蓝色茶杯").assertDoesNotExist()
            runBlocking {
                val store=CompanionMemoryStore(db)
                assertEquals("角色甲独有的蓝色茶杯",store.validatedMemories(store.ensureScope(sessions[0])).single().content)
                assertEquals("角色乙独有的红色围巾",store.validatedMemories(store.ensureScope(sessions[1])).single().content)
            }
        } finally { runBlocking {
            sessions.forEach { db.sessionDao().deleteById(it.id); db.characterDao().deleteById(it.characterId!!) }
        } }
    }
}
