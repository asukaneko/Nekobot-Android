package com.nekobot.app.ui.screens.sessions

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.theme.NekobotTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * 真实会话详情与列表：会话列表已改为左滑抽屉与多选，重命名入口在会话详情的“会话名称”。
 * 保存后列表标题必须是用户输入的标题，而不是角色名。
 */
class SessionRenameScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profile = "rename-ui-${UUID.randomUUID()}"
    private lateinit var previousProfile: String

    @Before fun prepareProfile() {
        check(ServiceContainer.prefs.isLocalMode)
        previousProfile = ServiceContainer.prefs.activeDbName
        ServiceContainer.switchLocalDb(profile)
    }

    @After fun restoreProfile() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        ServiceContainer.switchLocalDb(previousProfile)
        ServiceContainer.prefs.removeDbProfile(profile)
        NekobotDatabase.deleteProfileFile(compose.activity, profile)
    }

    @Test fun companionRenameUpdatesTheVisibleTitle() = renameCase(companion = true)
    @Test fun characterRenameUpdatesTheVisibleTitle() = renameCase(companion = false)

    private fun renameCase(companion: Boolean) {
        val id = "renamed-session"
        val characterId = "bound-character"
        val db = NekobotDatabase.get(compose.activity, profile)
        runBlocking {
            db.characterDao().upsert(LocalCharacterEntity(characterId, "林小满", createdAt = "old", updatedAt = "old"))
            db.sessionDao().upsert(LocalSessionEntity(id, "原会话名", characterId = characterId, characterName = "林小满",
                sessionMode = if (companion) "agent" else "character", companionMode = companion,
                inheritCharacter = companion, longConversationEnabled = companion,
                createdAt = "2026-10-12", updatedAt = "2026-10-12"))
        }
        val context = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        val page = mutableStateOf("detail")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
                LocalActivityResultRegistryOwner provides compose.activity, LocalOnBackPressedDispatcherOwner provides compose.activity) {
                NekobotTheme(darkTheme = false) {
                    if (page.value == "detail") SessionDetailScreen(id, onBack = { page.value = "list" })
                    else SessionsScreen(onOpenChat = {})
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("原会话名")).fetchSemanticsNodes().isNotEmpty()
        }
        // 详情页是懒加载列表，performScrollTo 不适用；直接按语义写入名称后点顶部“保存”
        // （单会话角色是文字按钮，普通会话是图标按钮，两种都覆盖）。
        compose.onNode(hasSetTextAction() and hasText("原会话名")).performTextReplacement("午后读书会")
        compose.onAllNodes(hasText("保存") or hasContentDescription("保存")).onFirst().performClick()
        compose.waitUntil(15_000) { runBlocking { db.sessionDao().getById(id)?.listTitleOverride == "午后读书会" } }
        // 保存成功后返回列表：列表标题必须是用户输入的名称，角色卡名称与绑定关系不变。
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("午后读书会") and !hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("午后读书会") and !hasSetTextAction(), useUnmergedTree = true).onFirst().assertIsDisplayed()
        assertEquals("林小满", runBlocking { db.characterDao().getById(characterId)?.name })
        assertEquals(characterId, runBlocking { db.sessionDao().getById(id)?.characterId })
    }
}
