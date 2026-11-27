package app.lianxu.independent

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** The real chat renders persisted thinking/tool positions, not a reconstructed preview page. */
class ChatTimelineUiTest {
    private val calls=AtomicInteger()
    @get:Rule(order=0)val fixture=MockChatFixture({object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();error("No model call in timeline UI fixture")}}})
    @get:Rule(order=1)val ui=createAndroidComposeRule<LianxuActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private lateinit var vm:AppState
    private lateinit var turn:ChatTurn
    private var reply=0L
    private lateinit var originalJournal:List<ChatToolActivity>
    private val recalled="合成记录：这条内容只用于检查展开功能。"
    private fun folder()=File(context.getExternalFilesDir(null),"timeline-evidence").apply{mkdirs()}
    private fun shot(name: String) {
        ui.mainClock.advanceTimeBy(600)
        ui.waitForIdle()
        SystemClock.sleep(180)
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(folder(), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
    private fun exercise(large:Boolean){
        val first=if(large)(1..45).joinToString("\n"){"合成长思考第${it}行，只检查排版。"}else"合成思考甲：先核对这一条记录。"
        val second="合成思考乙：再检查工具结果。"
        ui.runOnIdle{vm=ViewModelProvider(ui.activity)[AppState::class.java]};ui.waitUntil(15000){vm.ready}
        runBlocking{val store=Store(context,fixture.database);try{
            val repo=ProcessingRepository(store);val m=repo.manualSave("seed-lin",null,recalled,MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER)
            val found=listOf(mapOf<String,Any>("id" to m.id,"source_type" to "companion_memory","content" to repo.describe(m).take(600)))
            val refs=RecallContentCapture(repo,"seed-lin").capture(found,mapOf("status" to "found","results" to found),listOf(RecallContentOrigin("memory",m.id,m.version,"full")))
            val tools=listOf(
                ChatToolActivity("timeline-hit",1,"让我想一想","success",kind=ChatToolActivity.READ,tool="agent_memory_read",reasoningAt=first.length,reasoningKnown=true,recallGroup="same-response",recallRefs=refs),
                ChatToolActivity("timeline-error",1,"让我想一想","failed",detail="合成错误详情：该次操作未完成。",kind=ChatToolActivity.READ,tool="agent_memory_read",reasoningAt=first.length,reasoningKnown=true,recallGroup="same-response"),
                ChatToolActivity("timeline-record-failed",1,"合成记录","failed",detail="合成记录失败，原始事件必须保留。",kind=ChatToolActivity.WRITE,tool=CompanionRecord.NAME,reasoningAt=first.length+second.length,reasoningKnown=true),
                ChatToolActivity("timeline-record-saved",1,"合成记录成功","success",kind=ChatToolActivity.WRITE,tool=CompanionRecord.NAME,reasoningAt=first.length+second.length,reasoningKnown=true))
            val ledger=ChatLedger(store);turn=ledger.begin("seed-lin","请核对这几条合成记录。","fixture")
            assertTrue(ledger.finishWithMemory(turn.id,1,"合成回复：这里只验收界面与事件顺序。",onCompleted={db->tools.forEach{ChatToolJournal.put(db,turn.id,it)}},displayReasoning=first+second))
            reply=ledger.all().single{it.id==turn.id}.assistantMessageId!!;originalJournal=ChatToolJournal.read(store.readableDatabase,turn.id)
        }finally{store.close()}}
        ui.runOnIdle{vm.foreground()};ui.waitUntil(15000){vm.messages.any{it.id==reply}}
        val density=ui.density.density;val font=if(large)1.5f else 1f;val prefix=if(large)"dark-large"else"light"
        ui.runOnUiThread {
            vm.open("chat", "seed-lin")
            val activity = ui.activity
            activity.setContent {
                LianxuTheme(large) {
                    // The fixture forces a dark theme independently of the device's night mode.
                    if (large) DisposableEffect(activity) {
                        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        onDispose { activity.enableEdgeToEdge() }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                        Surface(
                            Modifier.widthIn(max = (if (large) 320 else 393).dp).fillMaxWidth().fillMaxHeight(),
                            color = Chrome,
                        ) {
                            Box(Modifier.safeDrawingPadding().imePadding()) { Chat(vm) }
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("chat-history").performScrollToKey("group-$reply")
        val owner="message-$reply"
        listOf(0,1).forEach { ui.onNodeWithTag("thinking-toggle-$owner-$it")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起")) }
        ui.onNodeWithTag("thinking-text-$owner-0").assertDoesNotExist();ui.onNodeWithTag("thinking-text-$owner-1").assertDoesNotExist()
        val ids=listOf("thinking-$owner-0","tool-timeline-hit","tool-timeline-error","thinking-$owner-1","tool-timeline-record-failed","tool-timeline-record-saved")
        val nodes=ids.map{ui.onNodeWithTag("timeline-node-$it").fetchSemanticsNode().boundsInRoot}
        assertTrue(nodes.zipWithNext().all{(a,b)->a.bottom<=b.top+1});assertTrue(nodes.all{kotlin.math.abs(it.left-nodes.first().left)<1})
        ui.onNodeWithTag("tool-result-timeline-record-failed").assertExists();ui.onNodeWithTag("tool-result-timeline-record-saved").assertExists()
        ui.onNodeWithText("继续思考").assertDoesNotExist()
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        val fraction = try {
            val x=(nodes.first().left+8*density).roundToInt()
            val bgX=(x+13*density).roundToInt()
            val centers=nodes.map{(it.top+(5+9*font)*density).roundToInt()}
            val gap=(10*density).roundToInt()
            var line=0
            var samples=0
            for ((from,to) in centers.zipWithNext()) {
                for (y in from+gap..to-gap) {
                    val a=bitmap.getPixel(x,y)
                    val b=bitmap.getPixel(bgX,y)
                    if ((0..2).sumOf{shift->kotlin.math.abs(((a ushr (shift*8))and 255)-((b ushr (shift*8))and 255))}>20) line++
                    samples++
                }
            }
            val visibleFraction=line.toFloat()/samples
            assertTrue("节点间的轨道可见比例 $line/$samples", samples>0 && visibleFraction>.90f)
            for (index in listOf(1,2,4,5)) {
                val pixel=bitmap.getPixel(x,centers[index])
                val green=android.graphics.Color.green(pixel)
                val red=android.graphics.Color.red(pixel)
                if(index==1 || index==5) assertTrue("成功节点应为柔绿",green>red)
                else assertTrue("失败节点应为柔赭红",red>green)
            }
            assertEquals("折叠思考圆环中间留白",bitmap.getPixel(bgX,centers.first()),bitmap.getPixel(x,centers.first()))
            visibleFraction
        } finally {
            bitmap.recycle()
        }
        File(folder(),"$prefix-metrics.json").writeText(JSONObject().put("actualToolEvents",originalJournal.size).put("thinkingSegments",2).put("timelineNodes",nodes.size).put("railPaintFraction",fraction).put("fontScale",font).put("nodes",nodes.map{it.toString()}).toString(2));shot("$prefix-collapsed-chat")
        ui.onNodeWithTag("recall-toggle-timeline-hit").performClick();ui.waitUntil(10000){ui.onAllNodesWithText(recalled).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("recall-toggle-timeline-error").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起"))
        ui.onNodeWithTag("recall-toggle-timeline-error").performScrollTo().performClick();ui.onNodeWithText("合成错误详情：该次操作未完成。").assertExists()
        ui.onNodeWithTag("recall-toggle-timeline-hit").performScrollTo().performClick();ui.onNodeWithTag("recall-results-timeline-error").assertExists();shot("$prefix-independent-tools")
        ui.onNodeWithTag("recall-toggle-timeline-error").performScrollTo().performClick()
        ui.onNodeWithTag("thinking-toggle-$owner-0").performScrollTo().performClick();ui.onNodeWithTag("thinking-text-$owner-0").assertExists();ui.onNodeWithTag("thinking-text-$owner-1").assertDoesNotExist()
        if(large){val text=ui.onNodeWithTag("thinking-text-$owner-0");text.performTouchInput{swipeUp()};val range=text.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange);assertTrue(range!=null&&range.maxValue()>0&&range.value()>0)}
        shot("$prefix-thinking-expanded");ui.onNodeWithTag("thinking-toggle-$owner-0").performScrollTo().performClick()
        ui.onNodeWithTag("message-input").performClick();ui.runOnUiThread{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(ui.activity.window.decorView,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
        ui.waitUntil(5000){ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true};SystemClock.sleep(350);ui.onNodeWithTag("chat-history").performScrollToKey("group-$reply");shot("$prefix-keyboard")
        ui.runOnUiThread{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0)}
        val store=Store(context,fixture.database);try{assertEquals(originalJournal,ChatToolJournal.read(store.readableDatabase,turn.id))}finally{store.close()};assertEquals(0,calls.get())
    }
    @Test fun lightTimelineKeepsEveryActualEventAndIndependentToggles()=exercise(false)
    @Test fun darkLargeFontLongThinkingKeyboardAndRailRemainAligned()=exercise(true)
}
