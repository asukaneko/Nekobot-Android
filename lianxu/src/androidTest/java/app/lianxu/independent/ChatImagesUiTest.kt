package app.lianxu.independent

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class ChatImagesUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
    private lateinit var vm:AppState
    private var permissionRequests=0
    private val saves=AtomicInteger()

    @Test fun savingPictureRequestsPermissionAndOnlySavesAfterGrant() {
        ui.runOnUiThread { vm=ViewModelProvider(ui.activity)[AppState::class.java] }
        ui.waitUntil(15000) { vm.ready }
        val bytes=ByteArrayOutputStream()
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)
        bitmap.recycle()
        val id=runBlocking {
            Store(ui.activity,fixture.database).use { store ->
                val ledger=ChatLedger(store)
                ChatImages.prepare(bytes.toByteArray().inputStream(),ui.activity.cacheDir).use { image ->
                    val turn=ledger.begin("seed-lin","","fixture",image=image)
                    check(ledger.finish(turn.id, turn.attempt, "收到图片"))
                    turn.userMessageId
                }
            }
        }
        runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { vm.refreshChat() } }
        ui.runOnIdle { vm.open("chat","seed-lin") }
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                if (contract is ActivityResultContracts.RequestPermission) {
                    assertEquals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, input)
                    val grant = ++permissionRequests == 2
                    Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, grant) }
                    return
                }
                error("Unexpected activity contract")
            }
        }}
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,
            LocalChatMediaPermissionCheck provides { false }, LocalChatMediaSaver provides { _, ref, source ->
            assertTrue(ref.mime.startsWith("image/")); source.input().use { assertTrue(it.read()>=0) }; saves.incrementAndGet(); Unit
        }){LianxuTheme(false){Chat(vm)}}}}
        fun save() {
            ui.onNodeWithTag("message-image-$id", true).performTouchInput { longClick() }
            ui.onNodeWithTag("chat-menu-save").performClick()
        }
        save()
        ui.onNodeWithTag("chat-action-toast").assertTextEquals("未授予存储权限，无法保存到相册")
        assertEquals(1, permissionRequests)
        assertEquals(0, saves.get())
        save()
        ui.waitUntil(5000) { saves.get() == 1 }
        ui.onNodeWithTag("chat-action-toast").assertTextEquals("已保存到相册")
        assertEquals(2, permissionRequests)
    }

}
