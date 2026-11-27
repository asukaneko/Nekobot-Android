package app.lianxu.independent

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class LearningRecordsStatesUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun rememberedCardAndDetailUseDarkPaper(){
        val state=RoleLearningState("one","interest","散步时","你喜欢看看路边的小花","active",SourceRef("r",7,1),pinned=true)
        val record=LearningRecord(state,null,emptyList(),emptyList())
        ui.setContent{LianxuTheme(dark=true){LearningRecordsContent(Friend("r","林间",""),listOf(record),false,{},{})}}
        val card=ui.onNodeWithTag("learning-item-one")
        fun assertDarkPaper(node:SemanticsNodeInteraction){
            val image=node.captureToImage().toPixelMap()
            assertTrue(image[image.width/2,6].luminance()<.2f)
        }
        assertDarkPaper(card)
        card.performClick()
        assertDarkPaper(ui.onNodeWithTag("learning-detail"))
        ui.onNodeWithText("已一直记住 · 聊天时会参考").assertIsDisplayed()
        ui.onNodeWithTag("learning-pin").assertIsOn()
    }
    @Test fun loadingFailureRetryAndEmptyAreDistinct(){
        val records=mutableStateOf<List<LearningRecord>?>(null)
        val failed=mutableStateOf(false)
        var retries=0
        ui.setContent{LianxuTheme{LearningRecordsContent(Friend("test","合成角色",""),records.value,failed.value,{retries++;failed.value=false},{})}}
        ui.onNodeWithTag("learning-loading").assertExists()
        ui.onNodeWithTag("learning-empty").assertDoesNotExist()
        ui.runOnIdle{failed.value=true}
        ui.onNodeWithTag("learning-error").assertExists()
        ui.onNodeWithTag("learning-empty").assertDoesNotExist()
        ui.onNodeWithTag("learning-retry").performClick()
        assertEquals(1,retries)
        ui.onNodeWithTag("learning-loading").assertExists()
        ui.runOnIdle{records.value=emptyList()}
        ui.onNodeWithTag("learning-empty").assertExists()
        ui.onNodeWithTag("learning-error").assertDoesNotExist()
    }
}
