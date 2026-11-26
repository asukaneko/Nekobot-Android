package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic records through the real entry/query/detail; no provider or production data. */
class LearningRecordsUiTest {
    private val calls=AtomicInteger()
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
    private lateinit var vm:AppState
    private fun start(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
    }
    private fun awaitTag(tag:String){ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun shot(name:String){
        ui.waitForIdle();val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrument.targetContext.getExternalFilesDir(null),"evidence-learning-view").apply{mkdirs()}
        instrument.uiAutomation.waitForIdle(250,5000)
        val bitmap=instrument.uiAutomation.takeScreenshot()
        val file=File(dir,"$name.png")
        try{
            file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let{output->
                File(output).mkdirs()
                file.copyTo(File(output,file.name),overwrite=true)
            }
        }finally{bitmap.recycle()}
    }
    @Test fun learningActionsPersistAndSwipingMovesBetweenWorldTabsWithoutModelCalls(){
        start()
        val record=runBlocking{
            val seed=LearningRecordsFixture(vm.processing.store)
            seed.now=System.currentTimeMillis()
            seed.proposeHabit("seed-lin")
            vm.refreshProcessing()
            vm.companion.learningRecords("seed-lin").single()
        }
        val item="learning-item-${record.state.id}"
        val before=runBlocking{vm.processing.store.messages()}
        ui.runOnIdle{vm.open("detail","seed-lin")}
        ui.onNodeWithTag("role-learning-open").performScrollTo().performClick()
        awaitTag(item)
        ui.onNodeWithTag("inner-tab-1").assertIsSelected()
        for(kind in listOf("interest","habit","viewpoint","all")){
            ui.onNodeWithTag("learning-filter-$kind").performScrollTo().performClick().assertIsSelected()
        }
        ui.onNodeWithTag("learning-list").performTouchInput{swipe(Offset(width*.80f,height*.78f),Offset(width*.20f,height*.78f),380)}
        awaitTag("memory-timeline");ui.onNodeWithTag("inner-tab-2").assertIsSelected()
        ui.onNodeWithTag("memory-browser").performTouchInput{swipe(Offset(width*.20f,height*.72f),Offset(width*.80f,height*.72f),380)}
        awaitTag(item)
        ui.onNodeWithTag(item).performScrollTo().performClick()
        fun act(tag:String){awaitTag(tag);ui.onNodeWithTag(tag).performScrollTo().performClick()}
        act("learning-pin")
        ui.waitUntil(8000){runBlocking{vm.companion.learningRecords("seed-lin").single().state.pinned}}
        act("learning-pause")
        ui.waitUntil(8000){runBlocking{vm.companion.learningRecords("seed-lin").single().state.state=="paused"}}
        ui.onNodeWithTag("detail-back").performClick();awaitTag("learning-list")
        ui.onNodeWithTag(item).assertDoesNotExist()
        ui.onNodeWithTag("learning-archived").performClick();ui.onNodeWithTag(item).performScrollTo().performClick()
        act("learning-pause");ui.waitUntil(8000){runBlocking{vm.companion.learningRecords("seed-lin").single().state.state!="paused"}}
        act("learning-delete");ui.onNodeWithTag("learning-delete-confirm").performClick()
        ui.waitUntil(8000){runBlocking{vm.companion.learningRecords("seed-lin").isEmpty()}}
        assertEquals(before,runBlocking{vm.processing.store.messages()});assertEquals(0,calls.get())
    }
    @Test fun resumeReturnsAFadedRecordToTheActiveList()=reactivateFaded("learning-pause")
    @Test fun pinReturnsAFadedRecordToTheActiveList()=reactivateFaded("learning-pin")
    private fun reactivateFaded(control:String){
        start()
        val state=runBlocking{
            val seed=LearningRecordsFixture(vm.processing.store).apply{now=System.currentTimeMillis()-35*RelationshipProjector.DAY}
            seed.proposeHabit("seed-lin")
            vm.companion.learningRecords("seed-lin").single().state
        }
        assertEquals("faded",state.state)
        val item="learning-item-${state.id}"
        ui.runOnIdle{vm.open("role-learning","seed-lin")}
        awaitTag("learning-empty")
        ui.onNodeWithTag("learning-archived").performClick()
        ui.onNodeWithTag(item).performScrollTo().performClick()
        ui.onNodeWithText("重新启用").assertExists()
        ui.onNodeWithTag(control).performScrollTo().performClick()
        ui.waitUntil(8000){runBlocking{vm.companion.learningRecords("seed-lin").single().state.state!="faded"}}
        ui.onNodeWithTag("detail-back").performClick()
        ui.onNodeWithText("还没有收起的记录").assertExists()
        ui.onNodeWithTag("learning-archived").performClick()
        ui.onNodeWithTag(item).assertIsDisplayed()
        assertEquals(control=="learning-pin",runBlocking{vm.companion.learningRecords("seed-lin").single().state.pinned})
        assertEquals(0,calls.get())
    }
    @Test fun roleEntrySearchDetailAndHistoryAreReadOnly(){
        start()
        val records=runBlocking{
            val seed=LearningRecordsFixture(vm.processing.store).apply{now=System.currentTimeMillis()+60_000}
            val first=seed.proposeHabit("seed-lin")
            seed.reviseHabit(first,"seed-lin")
            seed.event("seed-lin","散步时慢一点，就能看见路边的小花"){wire,context->
                val evidence=org.json.JSONArray().put(seed.evidence(context.current.turnId))
                wire.put("learning",seed.learning("","propose",evidence,"散步时，你喜欢慢下来看看路边的小花")
                    .put("kind","viewpoint").put("scope","一起散步时"))
            }
            vm.refreshProcessing()
            vm.companion.learningRecords("seed-lin")
        }
        val record=records.single{it.state.kind=="habit"}
        val before=runBlocking{vm.processing.store.messages()}
        ui.runOnIdle{vm.open("detail","seed-lin")}
        ui.onNodeWithTag("role-learning-open").performScrollTo().performClick()
        awaitTag("learning-item-${record.state.id}")
        shot("01-learning-list")
        ui.onNodeWithTag("learning-filter-interest").performClick()
        ui.onNodeWithText("没有找到匹配的记录").assertExists()
        ui.onNodeWithTag("learning-filter-all").performClick()
        ui.onNodeWithTag("learning-open-search").performClick()
        ui.onNodeWithTag("learning-search").performTextInput("不会匹配的合成词")
        ui.onNodeWithText("没有找到匹配的记录").assertExists()
        ui.onNodeWithTag("learning-search").performTextClearance()
        ui.onNodeWithTag("learning-search").performTextInput(record.state.scope)
        ui.runOnIdle{WindowInsetsControllerCompat(ui.activity.window,ui.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        ui.onNodeWithTag("learning-item-${record.state.id}").performScrollTo().performClick()
        ui.onNodeWithTag("learning-detail").assertExists()
        ui.onNodeWithText("${record.state.familiarity} · 聊天时会参考").assertExists()
        ui.onNodeWithTag("learning-evidence").assertDoesNotExist()
        ui.onNodeWithTag("learning-history").assertDoesNotExist()
        shot("02-learning-detail")
        ui.onNodeWithTag("learning-origin-toggle").performScrollTo().performClick()
        ui.onNodeWithText(record.evidence.first().text).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("补充调整",substring=true).performScrollTo().assertIsDisplayed()
        shot("03-learning-origin")
        ui.onNodeWithTag("learning-history").assertExists()
        ui.onNodeWithText("查看这次依据",substring=true).assertDoesNotExist()
        ui.onNodeWithTag("detail-back").performClick()
        ui.onNodeWithTag("learning-search").assertTextContains(record.state.scope)
        ui.onNodeWithTag("learning-back").performClick()
        ui.onNodeWithTag("role-learning-open").assertExists()
        assertEquals(0,calls.get())
        assertEquals(records,runBlocking{vm.companion.learningRecords("seed-lin")})
        assertEquals(before,runBlocking{vm.processing.store.messages()})
    }
    @Test fun settingsRoleStartsInMemoryAndBackFromMoodReturnsToDirectory(){
        start()
        ui.onNodeWithTag("tab-2").performClick()
        ui.onNodeWithTag("settings-processing").performScrollTo().performClick()
        ui.onNodeWithTag("settings-memory-rules").assertExists()
        ui.onNodeWithTag("processing-friend-seed-lin").performScrollTo().performClick()
        ui.onNodeWithTag("inner-tab-2").assertIsSelected()
        ui.onNodeWithTag("inner-tab-0").performClick();awaitTag("mood-hero")
        ui.waitUntil(8000){ui.onAllNodesWithTag("memory-browser").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("inner-tab-0").assertIsSelected()
        ui.onNodeWithTag("mood-hero").assertIsDisplayed()
        shot("04-mood-hero")
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("processing-friend-seed-lin").assertExists()
        assertEquals(0,calls.get())
    }
    @Test fun roleSwitchAndWithdrawnSourceDoNotLeaveStaleLearning(){
        start()
        val first=runBlocking{
            val seed=LearningRecordsFixture(vm.processing.store).apply{now=System.currentTimeMillis()}
            seed.proposeHabit("seed-lin").also{vm.refreshProcessing()}
        }
        val id=runBlocking{vm.companion.learningRecords("seed-lin").single().state.id}
        ui.runOnIdle{vm.open("role-learning","seed-lin")}
        awaitTag("learning-item-$id")
        ui.onNodeWithTag("learning-item-$id").performClick()
        runBlocking{vm.companion.forget("seed-lin",first.userMessageId);vm.refreshProcessing()}
        awaitTag("learning-detail-missing")
        ui.onNodeWithTag("learning-evidence").assertDoesNotExist()
        ui.runOnIdle{vm.open("role-learning","seed-an")}
        awaitTag("learning-empty")
        ui.onNodeWithText("时安").assertExists()
        ui.onNodeWithText("还没有学习记录").assertExists()
        ui.onNodeWithTag("learning-item-$id").assertDoesNotExist()
        assertEquals(0,calls.get())
    }
}
