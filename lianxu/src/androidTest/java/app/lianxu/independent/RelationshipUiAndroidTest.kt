package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RelationshipUiAndroidTest {
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No network")}})
    @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
    private lateinit var vm:AppState
    private fun swipeTab(left:Boolean){ui.onNodeWithTag("relationship-page").performTouchInput{swipe(Offset(width*(if(left).8f else .2f),height*.65f),Offset(width*(if(left).2f else .8f),height*.65f),400)}}
    @Test fun threePanelsKeepOriginalButtonsAndSwipeDirectionsBoundariesAndEdges(){
        seed();enrich();ui.runOnIdle{vm.open("role-relationship","seed-lin")}
        ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-tab-0").fetchSemanticsNodes().isNotEmpty()}
        val before=runBlocking{vm.companion.relationshipAudit("seed-lin")}
        fun selected(index:Int){ui.onNodeWithTag("relationship-tab-$index").assertIsDisplayed();(0..2).filter{it!=index}.forEach{ui.onNodeWithTag("relationship-tab-$it").assertDoesNotExist()}}
        selected(0)
        for(index in listOf(1,2,0)){ui.onNodeWithTag("relationship-tab-button-$index").performClick();selected(index)}
        swipeTab(true);ui.onNodeWithTag("inner-tab-1").assertIsSelected()
        ui.onNodeWithTag("learning-page").performTouchInput{swipe(Offset(width*.80f,height*.8f),Offset(width*.20f,height*.8f),380)}
        ui.onNodeWithTag("inner-tab-2").assertIsSelected()
        ui.onNodeWithTag("inner-tab-0").performClick();selected(0)
        assertEquals(before,runBlocking{vm.companion.relationshipAudit("seed-lin")})
    }
    @Test fun scrollingLongDetailAndSwitchingRoleKeepContentAndRoleIsolation(){
        seed();repeat(5){enrich()};ui.runOnIdle{vm.open("role-relationship","seed-lin")}
        ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-tab-0").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(10000){ui.onAllNodesWithText("周末一起去公园",substring=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("relationship-all").performScrollTo().assertIsDisplayed()
        shot("swipe-before-view-all")
        ui.onNodeWithTag("relationship-all").performClick()
        try{ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-detail-scroll").fetchSemanticsNodes().isNotEmpty()}}catch(t:Throwable){shot("swipe-detail-failure");println(ui.onRoot().printToString());throw t}
        val scroller=ui.onNodeWithTag("relationship-detail-scroll");scroller.performTouchInput{swipeUp()}
        val range=scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange];assertTrue(range.maxValue()>0&&range.value()>0)
        shot("swipe-long-detail-scrolled")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("relationship-tab-0").assertIsDisplayed();ui.onNodeWithTag("relationship-tab-button-1").performClick();ui.onNodeWithTag("relationship-tab-1").assertIsDisplayed()
        ui.runOnIdle{vm.open("role-relationship","seed-an")};ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-tab-0").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("relationship-tab-0").assertIsDisplayed();ui.onNodeWithText("还没有让她感觉变了的事").assertIsDisplayed()
        ui.runOnIdle{vm.open("role-relationship","seed-lin")};ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-all").fetchSemanticsNodes().isNotEmpty()};shot("swipe-moments-role-returned")
    }
    private fun shot(name:String){
        ui.waitForIdle();android.os.SystemClock.sleep(700)
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val dir=java.io.File(i.targetContext.getExternalFilesDir(null),"evidence-ui1").apply{mkdirs()}
        val bitmap=i.uiAutomation.takeScreenshot()
        java.io.File(dir,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun seed(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        runBlocking{
            val core=vm.companion;val ledger=ChatLedger(vm.processing.store)
            val turn=ledger.begin("seed-lin","合成失约原话","synthetic");val ref=core.sourceFor(turn);val snapshot=core.snapshot("seed-lin");val now=System.currentTimeMillis()
            val p=ExperienceProposal(relationInput=RelationInput(ref,now,now/RelationshipProjector.DAY,"关于关系",v=.9),relationAttempted=true,timingReferenceAt=now,parameterProposal=RelationParameterProposal(ref,mapOf("tone" to "温柔简洁"),"说得温柔简洁些",now))
            assertTrue(core.accept(turn,p,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"))
            ProcessingRepository(vm.processing.store).tx{core.finish(it,turn,snapshot,p)}
            vm.refreshProcessing()
        }
    }
    @Test fun disputingANoteWithNoRelationshipEventClearsPaperAndPrompt(){
        seed()
        val core=vm.companion
        runBlocking{
            val ledger=ChatLedger(vm.processing.store)
            val t=ledger.begin("seed-lin","还想再见面","synthetic");val snap=core.snapshot("seed-lin")
            val p=ExperienceProposal(innerNote=ReviewInnerNote("下次还想见他",System.currentTimeMillis()))
            assertTrue(core.accept(t,p,snap){true});assertTrue(ledger.finish(t.id,1,"我也想"))
            vm.processing.tx{core.finish(it,t,snap,p)}
            vm.refreshProcessing()
        }
        ui.runOnIdle{vm.open("role-relationship","seed-lin")}
        await("下次还想见他");ui.onNodeWithTag("inner-note-correct").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("mood-inner-note").fetchSemanticsNodes().isEmpty()}
        runBlocking{val view=core.relationship("seed-lin");assertNull(view.innerNote);assertFalse(view.modelMaterial.contains("下次还想见他"))}
    }
    private fun enrich()=runBlocking {
        val core=vm.companion;val ledger=ChatLedger(vm.processing.store)
        suspend fun row(text:String,make:(SourceRef,Long)->RelationInput):SourceRef {
            val turn=ledger.begin("seed-lin",text,"synthetic");val ref=core.sourceFor(turn);val snapshot=core.snapshot("seed-lin");val now=System.currentTimeMillis()
            val p=ExperienceProposal(relationInput=make(ref,now),relationAttempted=true,timingReferenceAt=now,relationIssues=listOf(RelationIssue("合成字段","合成未计入")))
            assertTrue(core.accept(turn,p,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"));ProcessingRepository(vm.processing.store).tx{core.finish(it,turn,snapshot,p)};return ref
        }
        val old=row("那次没赶上电影"){ref,at->RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",v=.6)}
        row("那次其实是临时加班，解释清楚了"){ref,at->RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",explains=old,formatVersion=2,releaseKind="有原因",explanationPolicy=1)}
        row("周末一起去公园"){ref,at->RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",promise=true,p=.3)}
        vm.refreshProcessing()
    }
    private fun await(text:String){ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun relationshipRendersPendingBigAndFooter(){seed();enrich();ui.runOnIdle{vm.open("detail","seed-lin")};ui.onNodeWithTag("role-relationship-open").performScrollTo();shot("detail-entry");ui.runOnIdle{vm.open("role-relationship","seed-lin")};ui.waitUntil(10000){ui.onAllNodesWithText("心结 ·",substring=true).fetchSemanticsNodes().isNotEmpty()};shot("relationship-1");ui.onNodeWithText("心结 ·",substring=true).performClick();ui.onNodeWithText("你说：「合成失约原话」").assertIsDisplayed();assertTrue(ui.onAllNodesWithText("她一直记着",substring=true).fetchSemanticsNodes().isEmpty());assertTrue(ui.onAllNodesWithText("跟她说开就会好一些",substring=true).fetchSemanticsNodes().isEmpty());shot("relationship-ledger");ui.onNodeWithText("这些感受来自相处中的点滴，只供她参考").assertIsDisplayed();shot("relationship-2")}

}
