package app.lianxu.independent

import android.content.ClipboardManager
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatMessageMenuUiTest {
    private val requests=CopyOnWriteArrayList<TextChatRequest>()
    @get:Rule(order=0) val fixture=MockChatFixture({
        object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                requests+=request
                return syntheticCompanionStage(request) ?: syntheticFinalReply(request,TextChatResult.Success("我们明天再聊。"))
            }
        }
    })
    @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
    private lateinit var vm:AppState
    private val segment="晚饭记得趁热吃。"

    private fun open(dark:Boolean):Long {
        ui.runOnUiThread { vm=ViewModelProvider(ui.activity)[AppState::class.java] }
        ui.waitUntil(15000) { vm.ready }
        val id=runBlocking {
            Store(ui.activity,fixture.database).use { store ->
                val ledger=ChatLedger(store)
                val t=ledger.begin("seed-lin","今天回家吃饭","fixture")
                check(ledger.finish(t.id,1,"我买了些水果。\n\n"+segment))
                requireNotNull(ledger.all().single { it.id==t.id }.assistantMessageId)
            }
        }
        runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { vm.refreshChat() } }
        ui.runOnUiThread {
            vm.open("chat","seed-lin")
            ui.activity.setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,if(dark) 1.6f else 1f)) {
                    Box(if(dark) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        LianxuTheme(dark) { Chat(vm) }
                    }
                }
            }
        }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("bubble-$id-1",true).fetchSemanticsNodes().isNotEmpty() }
        return id
    }
    private fun hold(id:Long) {
        ui.onNodeWithTag("bubble-$id-1",true).performScrollTo().performTouchInput { longClick() }
    }
    @Test fun selectedSegmentCopiesAndQuotesThroughSendAndJump() {
        val id=open(false)
        val history=ui.onNodeWithTag("chat-history")
        val originalBounds=history.getUnclippedBoundsInRoot()
        hold(id)
        ui.onNodeWithTag("chat-menu-copy").performClick()
        ui.onNodeWithTag("chat-action-toast").assertTextEquals("已复制")
        assertEquals(originalBounds, history.getUnclippedBoundsInRoot())
        ui.runOnIdle {
            val clip=ui.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertEquals(segment,clip.getItemAt(0).text.toString())
        }
        hold(id)
        ui.onNodeWithTag("chat-menu-quote").performClick()
        ui.onNodeWithTag("chat-composer-quote").assertIsDisplayed()
        ui.onNodeWithText("回复 林间："+segment).assertIsDisplayed()
        ui.onNodeWithTag("chat-composer-quote-clear").performClick()
        ui.onNodeWithTag("chat-composer-quote").assertDoesNotExist()
        hold(id)
        ui.onNodeWithTag("chat-menu-quote").performClick()
        val reply="我已经吃过了"
        ui.onNodeWithTag("message-input").performTextInput(reply)
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(20000) { vm.messages.any { it.mine && it.text==reply } && !vm.chatSending }
        val sent=vm.messages.single { it.mine && it.text==reply }
        assertTrue(requests.flatMap { it.messages }.any { it.role=="user" && it.content=="[引用你之前说的话：「$segment」]\n"+reply })
        ui.onNodeWithTag("chat-composer-quote").assertDoesNotExist()
        ui.onNodeWithTag("message-quote-${sent.id}").performScrollTo().performClick()
        ui.waitUntil(3000) { ui.onAllNodesWithTag("chat-turn-highlight",true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("message-$id").assertIsDisplayed()

        ui.onNodeWithTag("bubble-${sent.id}-0", true).performScrollTo().performTouchInput { longClick() }
        ui.onNodeWithTag("chat-menu-quote").performClick()
        ui.onNodeWithText("回复 我："+reply).assertIsDisplayed()
        ui.onNodeWithTag("message-quote-${sent.id}").assertIsDisplayed()
        ui.waitUntil(3000) { ui.onAllNodesWithTag("chat-turn-highlight", true).fetchSemanticsNodes().isEmpty() }
        val screenshot = ui.onRoot().captureToImage().asAndroidBitmap()
        File(ui.activity.getExternalFilesDir(null), "batch10-quote.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
    @Test fun narrowDarkLargeTextKeepsQuoteAndDismissReachable() {
        val id=open(true)
        hold(id)
        ui.onNodeWithTag("chat-menu-copy").assertIsDisplayed()
        ui.onNodeWithTag("chat-menu-quote").assertIsDisplayed().performClick()
        ui.onNodeWithTag("chat-composer-quote-clear").assertIsDisplayed().performClick()
        ui.onNodeWithTag("chat-composer-quote").assertDoesNotExist()
    }
}
