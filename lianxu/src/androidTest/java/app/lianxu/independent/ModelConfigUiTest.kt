package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelConfigUiTest {
    private fun host(id:String, purpose:String) = HostModelSnapshot(id,id,"https://example.invalid/v1",id,
        "synthetic",purpose,true,16000,2048)
    private val chat = host("host-chat", "chat")
    private val vector = host("host-vector", "embedding")
    @get:Rule(order=0) val isolated = MockChatFixture(hostModels=HostModelBridge { listOf(chat, vector) })
    @get:Rule(order=1) val ui = createAndroidComposeRule<LianxuActivity>()

    @Test fun overviewOpensReviewSelectionAndReturnsToTheSameTab(){
        ui.waitUntil(15000){ui.onAllNodesWithTag("tab-2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-2").performClick()
        ui.onNodeWithTag("settings-models").performClick()
        val vault = ModelVault(InstrumentationRegistry.getInstrumentation().targetContext, isolated.namespace)
        val b = chat.config()
        val opener=ui.onNodeWithTag("config-open-purposes")
        opener.performClick()
        opener.assertDoesNotExist()
        ui.onNodeWithTag("purpose-select-aux-vision").assertDoesNotExist()
        ui.onNodeWithTag("purpose-select-memory").performScrollTo().assertTextContains("沿用聊天模型")
        val review=ui.onNodeWithTag("purpose-select-review")
        review.performScrollTo().assertTextContains("沿用聊天模型").performClick()
        ui.onNodeWithTag("purpose-option-review-${vector.config().id}").assertDoesNotExist()
        ui.onNodeWithTag("purpose-option-review-${b.id}").assertTextContains("来自 NekoBot", substring=true).performClick()
        ui.waitUntil(10000){vault.purposeId(ModelPurpose.REVIEW)==b.id}
        ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("config-tab-chat").assertIsSelected()
        opener.performClick()
        review.performScrollTo().assertTextContains(b.modelId,substring=true)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        opener.assertIsDisplayed()
        assertEquals(b.id,vault.purposeId(ModelPurpose.REVIEW))
    }
}
