package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class ModelConfigUiTest {
    private fun host(id:String, purpose:String) = HostModelSnapshot(id,id,"https://example.invalid/v1",id,
        "synthetic",purpose,true,16000,2048)
    private val chat = host("host-chat", "chat")
    private val vector = host("host-vector", "embedding")
    private val defaultVector = host("default-vector", "embedding")
    @get:Rule(order=0) val isolated = MockChatFixture(hostModels=HostModelBridge { listOf(chat, defaultVector, vector) })
    @get:Rule(order=1) val ui = createAndroidComposeRule<LianxuActivity>()

    private fun openSettings() {
        ui.waitUntil(15000){ui.onAllNodesWithTag("tab-2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-2").performClick()
    }

    @Test fun reviewSheetLabelsChatMirrorAndExcludesEmbeddingMirror() {
        openSettings()
        ui.onNodeWithTag("settings-purposes").performClick()
        val review=ui.onNodeWithTag("purpose-select-review")
        review.performScrollTo().assertTextContains("沿用聊天模型").performClick()
        ui.onNodeWithTag("purpose-option-review-${vector.config().id}").assertDoesNotExist()
        ui.onNodeWithTag("purpose-option-review-${chat.config().id}").assertTextContains("来自 NekoBot", substring=true).performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText(chat.config().purposeLabel()).fetchSemanticsNodes().isNotEmpty()}
        review.assertTextContains(chat.config().purposeLabel())
        ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("settings-purposes").assertIsDisplayed()
    }

    @Test fun hostRowsAllowSelectionAndKeepModelEditingDisabled() {
        openSettings()
        ui.onNodeWithTag("settings-models").performClick()
        val chatId=chat.config().id
        ui.onNodeWithTag("config-edit-$chatId").assertIsNotEnabled().assertTextContains("来自 NekoBot",substring=true)
        ui.onNodeWithTag("config-use-chat-fixture").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("config-use-chat-$chatId").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("config-use-chat-$chatId").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("config-current-chat-$chatId").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("config-current-chat-$chatId").assertExists()
        ui.onNodeWithTag("config-tab-embedding").performClick()
        val vectorId=vector.config().id
        ui.onNodeWithTag("config-edit-$vectorId").assertTextContains("来自 NekoBot",substring=true).performClick()
        val vault=ModelVault(ui.activity,isolated.namespace)
        ui.waitUntil(10000){vault.purposeId(ModelPurpose.EMBEDDING)==vectorId}
        ui.onNodeWithTag("config-edit-$vectorId").assertTextContains("默认")
    }
}
