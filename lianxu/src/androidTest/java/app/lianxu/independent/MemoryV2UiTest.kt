package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class MemoryV2UiTest {
    @get:Rule(order=0)val mock=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<LianxuActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun app():AppState{lateinit var v:AppState;ui.activityRule.scenario.onActivity{v=ViewModelProvider(it)[AppState::class.java]};return v}
    private fun hideIme(){ui.activityRule.scenario.onActivity{a->(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideIme();android.os.SystemClock.sleep(400);val dir=File(context.getExternalFilesDir(null),"evidence-memory-013").apply{mkdirs()};val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun bothMemoryTabsSwipeAndKeepIndependentScrollsAndEditorDraft(){
        ui.waitUntil(15000){app().ready};val v=app();val repo=v.processing
        runBlocking{repo.tx{db->repeat(12){index->for(manual in listOf(false,true))repo.saveMemory(db,StoredMemory("swipe-$manual-$index","seed-lin",MemoryCategory.STATE_FACT,"合成${if(manual)"人工"else"角色"}记忆$index",manual=manual,about=MemoryAbout.USER,createdAt=System.currentTimeMillis()+index))}};v.refreshProcessing()}
        val before=runBlocking{repo.memories("seed-lin")}
        ui.runOnIdle{v.open("processing-role","seed-lin")};ui.waitUntil(15000){ui.onAllNodesWithTag("memory-tab-hers").fetchSemanticsNodes().isNotEmpty()}
        fun swipe(left:Boolean){ui.onNodeWithTag("memory-browser").performTouchInput{swipe(Offset(width*(if(left).8f else .2f),height*.65f),Offset(width*(if(left).2f else .8f),height*.65f),400)}}
        fun scroll()=ui.onNodeWithTag("memory-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        ui.onNodeWithTag("memory-timeline").assertIsDisplayed()
        ui.onNodeWithTag("memory-tab-hers").assertIsSelected()
        ui.onNodeWithTag("memory-list").performScrollToIndex(6);val hers=scroll();assertTrue(hers>0)
        ui.onNodeWithTag("memory-tab-mine").performClick()
        ui.onNodeWithTag("memory-list").performScrollToIndex(7);val mine=scroll();assertTrue(mine>0)
        ui.onNodeWithTag("memory-tab-hers").performClick();assertEquals(hers,scroll(),2f)
        swipe(false);ui.onNodeWithTag("inner-tab-1").assertIsSelected()
        ui.onNodeWithTag("inner-tab-2").performClick();ui.onNodeWithTag("memory-tab-mine").performClick()
        assertEquals(mine,scroll(),2f)
        ui.onNodeWithTag("memory-list").performScrollToIndex(0);ui.onNodeWithTag("memory-add").performClick();ui.onNodeWithTag("memory-text").performTextInput("滑屏不能丢失的合成草稿")
        ui.onNodeWithTag("memory-text").performTouchInput{swipeLeft()};ui.onNodeWithTag("memory-text").assertTextContains("滑屏不能丢失的合成草稿");hideIme();ui.onNodeWithText("取消").performClick()
        ui.onNodeWithTag("memory-tab-mine").assertIsSelected();assertEquals(before,runBlocking{repo.memories("seed-lin")})
    }
    @Test fun verifiedTimelineLinkOpensTheOtherDetailAndLockPersists(){
        ui.waitUntil(15000){app().ready};val vm=app();val role="seed-lin";val repo=vm.processing
        runBlocking {
            val ledger=ChatLedger(repo.store);val t=ledger.begin(role,"雨天一起看电影","test")
            check(ledger.finish(t.id,1,"记得那天"));val src=repo.sources(role).last()
            val first=StoredMemory("film",role,MemoryCategory.STATE_FACT,"一起看电影",core=true,about=MemoryAbout.BOTH,
                evidence=listOf(MemoryEvidence(src.source,"user",src.user.text,src.user.text,src.user.created,src.zone)))
            val other=first.copy(id="rain",text="那天一直下雨",core=false,locked=false)
            repo.tx{db->repo.saveMemory(db,first);repo.saveMemory(db,other)}
            repo.store.setPreference(MemoryAssociations.key(role),"""[{"character":"$role","source":"film","source_version":1,"target":"rain","target_version":1,"kind":"same_event","reason":"同一天","quote":"一起看电影","target_quote":"一直下雨","batch":"synthetic"}]""")
            vm.refreshProcessing()
        }
        ui.runOnIdle{vm.open("detail",role)}
        ui.onNodeWithTag("role-processing-open").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("memory-link-film-rain").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("memory-link-film-rain").performClick()
        ui.onNodeWithText("那天一直下雨").assertIsDisplayed()
        ui.onNodeWithTag("memory-detail-menu").performClick();ui.onNodeWithTag("memory-lock-toggle").performClick()
        ui.waitUntil(10000){runBlocking{repo.memories(role).first{it.id=="rain"}.locked}}
        ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("memory-timeline").assertExists()
        ui.onNodeWithText("更多").performClick();ui.onNodeWithTag("memory-maintenance").performClick()
        ui.onNodeWithTag("memory-task-status").assertExists();ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("memory-timeline").assertExists();ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("role-processing-open").assertExists()
    }

}
