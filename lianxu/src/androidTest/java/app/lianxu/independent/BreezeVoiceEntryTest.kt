package app.lianxu.independent

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection

class BreezeVoiceEntryTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    @Test fun helpLinksOpenTheApprovedPagesWithoutLaunchingABrowser() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened.add(uri) }
        }
        val account = BreezeVoiceAccount(app)
        ui.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                LianxuTheme {
                    CompositionLocalProvider(LocalUriHandler provides handler) {
                        BreezeVoiceConnectSheet(account, false) {}
                    }
                }
            }
        }
        ui.onNodeWithTag("voice-key-help").performScrollTo().performClick()
        ui.onNodeWithTag("voice-site-help").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(listOf("https://breezeblue.ai/app/developer/api-keys", "https://breezeblue.ai/zh"), opened) }
    }

    private fun clickText(text:String)=ui.onNodeWithText(text).performScrollTo().performClick()
    private fun waitText(text:String)=ui.waitUntil(12000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun shot(file:String){
        ui.waitForIdle()
        val image=inst.uiAutomation.takeScreenshot()
        try{File(app.cacheDir,"entry-$file.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}
        finally{image.recycle()}
    }
    @Test fun settingsBindingAndStudioFromApp(){
        // The module instrumentation APK owns this synthetic voice account.
        check(app.packageName == "app.lianxu.independent.test")
        val fixtureKey = "synthetic-entry-fixture"
        val resolver=app.contentResolver
        val audio=inst.context.assets.open("voice/modulated-tone.mp3").use{it.readBytes()}
        val uri=requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.Audio.Media.DISPLAY_NAME,"lianxu-voice-entry-test.mp3");put(MediaStore.Audio.Media.MIME_TYPE,"audio/mpeg")
        }))
        val old=AppServices.voiceConnections
        val oldProbe=VoiceConnectProbe.reachable
        val paths=CopyOnWriteArrayList<String>()
        val ttsLines=CopyOnWriteArrayList<String>()
        val remixSources=CopyOnWriteArrayList<String>()
        val emptyCatalog=java.util.concurrent.atomic.AtomicBoolean(false)
        // 验证失败后的密钥/网络分类由真实网络探测决定，测试里固定成「服务器可达」。
        VoiceConnectProbe.reachable={ true }
        try{
            resolver.openOutputStream(uri)!!.use{it.write(audio)}
            AppServices.voiceConnections=HttpsConnectionFactory { url->
                val path=url.path.removePrefix("/v1/")
                check(url.host=="api.breeze.blue")
                paths+=path
                object:HttpsURLConnection(url){
                    override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
                    override fun getCipherSuite()="fixture"
                    override fun getLocalCertificates():Array<Certificate>?=null
                    override fun getServerCertificates():Array<Certificate> = emptyArray()
                    override fun getResponseCode()=if(path=="models" && paths.count{it=="models"}==1)401 else 200
                    override fun getContentType()=if(path.startsWith("text-to-speech/")||path.endsWith("/stream")) "audio/mpeg" else "application/json"
                    val sent=ByteArrayOutputStream()
                    override fun getOutputStream()=sent
                    override fun getInputStream()=when(path){
                        "models"->"""[{"model_id":"breeze-tts-2"}]""".byteInputStream()
                        "voices"->if(emptyCatalog.get()) """{"voices":[],"has_more":false}""".byteInputStream() else """{"voices":[{"voice_id":"a","name":"测试音色甲"},{"voice_id":"b","name":"测试音色乙"}],"has_more":false}""".byteInputStream()
                        "voice-previews/design"->"""{"previews":[{"generated_voice_id":"design"}]}""".byteInputStream()
                        "voice-previews/clone"->"""{"generated_voice_id":"clone","requires_verification":true}""".byteInputStream()
                        "voice-previews/remix"->{ remixSources += org.json.JSONObject(sent.toString("UTF-8")).getString("voice_id"); """{"generation_job_id":"sample"}""".byteInputStream() }
                        "voice-previews/remix/sample"->"""{"generated_voice_id":"remixed","status":"completed"}""".byteInputStream()
                        "voice-previews/remixed/save"->"""{"voice_id":"new"}""".byteInputStream()
                        "text-to-speech/a"->{ ttsLines += org.json.JSONObject(sent.toString("UTF-8")).getString("text"); audio.inputStream() }
                        "voice-previews/remixed/stream"->audio.inputStream()
                        else->error("Unexpected fixture route")
                    }
                }
            }
            lateinit var vm:AppState
            ui.activityRule.scenario.onActivity {vm=ViewModelProvider(it)[AppState::class.java]}
            ui.waitUntil(15000){vm.ready}
            val account=BreezeVoiceAccount(app)
            ui.onNodeWithTag("tab-2").performClick()
            ui.onNodeWithTag("settings-models").performClick()
            ui.onNodeWithTag("config-tab-voice").performClick()
            ui.onNodeWithTag("voice-action-breezeblue").assertExists()
            ui.onNodeWithTag("voice-action-breezeblue").performClick()
            ui.onNodeWithTag("voice-verify").assertIsNotEnabled()
            ui.onNodeWithTag("voice-connect-guide").assertExists()
            ui.onNodeWithTag("voice-key-help").assertExists()
            ui.onNodeWithTag("voice-site-help").assertExists()
            val input=ui.onNodeWithTag("voice-key-input")
            input.performTextInput("simulate-failure")
            ui.onNodeWithTag("voice-verify").performScrollTo().assertIsEnabled().performClick()
            waitText("密钥无效，检查后再试")
            input.performTextReplacement(fixtureKey)
            ui.onNodeWithTag("voice-verify").performScrollTo().assertIsEnabled().performClick()
            waitText("已连接")
            ui.onNodeWithTag("voice-connect-guide").assertDoesNotExist()
            ui.onNodeWithTag("voice-key-help").assertExists()
            ui.onNodeWithTag("voice-site-help").assertExists()
            ui.onNodeWithTag("voice-cancel").performClick()
            ui.waitForIdle()
            ui.onNodeWithText("API 语音").assertIsDisplayed()
            ui.onNodeWithText("音色管理").assertIsDisplayed()
            shot("config")
            fun role(id:String){
                ui.runOnIdle{vm.open("chat",id)}
                ui.onNodeWithTag("chat-role-detail").performClick()
                ui.onNodeWithTag("role-voice-open").performScrollTo().performClick()
                ui.onNodeWithTag("voice-allow").assertIsOff()
            }
            role("seed-lin")
            ui.onNodeWithTag("voice-allow").performClick()
            ui.onNodeWithTag("voice-choose").performClick()
            ui.onNodeWithText("测试音色甲").performClick()
            shot("role")
            role("seed-an")
            ui.onNodeWithTag("voice-choose").performClick()
            ui.onNodeWithText("测试音色乙").performClick()
            assertEquals("a",account.selected("seed-lin"));assertEquals("b",account.selected("seed-an"))
            assertTrue(account.enabled("seed-lin"));assertFalse(account.enabled("seed-an"))
            ui.runOnIdle{vm.open("home");vm.tab=2}
            ui.onNodeWithTag("settings-toolbox").performClick()
            ui.onNodeWithTag("tool-card-voice").assertDoesNotExist()
            assertEquals(listOf("video","contact","memory","image"),
                ToolboxPreferences.order("""["video","voice","contact","memory","image"]"""))
            ui.onNodeWithTag("back").performClick()
            ui.onNodeWithTag("settings-models").performClick()
            ui.onNodeWithTag("config-tab-voice").performClick()
            ui.onNodeWithTag("voice-studio-open").performClick()
            waitText("测试音色甲")
            ui.onNodeWithTag("back").performClick()
            ui.onNodeWithTag("config-tab-voice").assertIsSelected()
            ui.onNodeWithTag("voice-studio-open").performClick()
            waitText("测试音色甲")
            shot("library")

            ui.onNodeWithTag("voice-library-refresh").assertIsEnabled()
            fun pull(empty: Boolean, expected: String) {
                val before = paths.count { it == "voices" }
                emptyCatalog.set(empty)
                ui.onNodeWithTag("voice-library-list").performTouchInput { swipeDown() }
                waitText(expected)
                assertEquals(before + 1, paths.count { it == "voices" })
            }
            pull(true, "还没有保存的音色")
            pull(false, "测试音色甲")
            fun openLine() = ui.onNodeWithTag("voice-audition-line-open").performClick()
            fun completeLine() = ui.onNodeWithText("完成").performClick()
            fun audition(expected: String) {
                val before = ttsLines.size
                ui.onNodeWithContentDescription("试听测试音色甲").performClick()
                ui.waitUntil(12000) { ttsLines.size == before + 1 }
                assertEquals(expected, ttsLines.last())
                waitText("正在试听… 轻触停止")
                ui.onNodeWithContentDescription("停止试听测试音色甲").performClick()
            }
            val custom = "今天风很轻，想和你散散步。"
            openLine()
            ui.onNodeWithTag("voice-audition-line").performTextReplacement(custom)
            completeLine()
            ui.onNodeWithText("试听句：「$custom」").assertExists()
            openLine()
            ui.onNodeWithText(custom).assertExists()
            shot("line")
            completeLine()
            assertTrue(ttsLines.isEmpty())
            openLine()
            ui.onNodeWithTag("voice-audition-line").performTextReplacement("🌙".repeat(301))
            ui.onNodeWithTag("voice-audition-line").assertTextEquals("🌙".repeat(300))
            // Back can close the keyboard first. Dismissing the sheet must discard the draft.
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            if (ui.onAllNodesWithTag("voice-audition-line").fetchSemanticsNodes().isNotEmpty()) {
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.waitForIdle()
            }
            ui.onNodeWithTag("voice-audition-line").assertDoesNotExist()
            assertTrue(ttsLines.isEmpty())
            audition(custom)
            openLine()
            ui.onNodeWithText("恢复默认").performClick()
            completeLine()
            audition(BreezeVoiceStudio.DEFAULT_LINE)
            openLine()
            ui.onNodeWithTag("voice-audition-line").performTextReplacement("   ")
            completeLine()
            audition(BreezeVoiceStudio.DEFAULT_LINE)
            fun create(tag: String) {
                ui.onNodeWithTag("voice-library-list").performScrollToNode(hasTestTag(tag))
                ui.onNodeWithTag(tag).performClick()
            }
            create("voice-create-design")
            ui.onNodeWithText("描述想要的声音").performTextInput("轻柔的测试声音")
            clickText("生成预览");waitText("音色预览")
            ui.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            create("voice-create-clone")
            val filter=IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);addDataType("audio/*")}
            val monitor=inst.addMonitor(filter,Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri)),true)
            try {clickText("选择本地录音");waitText("本地试听")}finally{inst.removeMonitor(monitor)}
            ui.onNodeWithText("上传并生成预览").assertIsNotEnabled()
            clickText("本地试听");clickText("我有权使用这段录音");clickText("上传并生成预览")
            shot("consent")
            ui.onNodeWithText("取消").performClick()
            assertFalse("voice-previews/clone" in paths)
            clickText("上传并生成预览");ui.onNodeWithText("确认上传到 BreezeBlue").performClick()
            waitText("服务商要求验证此音色，完成验证后再保存。")
            ui.onNodeWithText("另存为新音色").assertIsNotEnabled()
            ui.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            ui.onAllNodesWithText("微调")[1].performClick()
            ui.onNodeWithText("原音色 · 测试音色乙").assertIsDisplayed()
            assertTrue(remixSources.isEmpty())
            ui.onNodeWithText("描述想要的声音").performTextInput("更柔和一点")
            clickText("生成预览");waitText("音色预览")
            clickText("试听预览");waitText("停止播放")
            ui.onNodeWithText("新音色名称").performTextInput("重塑样例")
            clickText("另存为新音色");waitText("已另存为新音色")
            assertEquals(listOf("b"),remixSources.toList())
            assertEquals(3,ttsLines.size)
            assertEquals(1,paths.count{it.endsWith("/save")});assertEquals(1,paths.count{it=="voice-previews/clone"})
            assertEquals("a",account.selected("seed-lin"))
        } finally {
            AppServices.voiceConnections = old
            VoiceConnectProbe.reachable = oldProbe
            ModelVault(app, "breezeblue-speech").delete("tts-${stableDigest(fixtureKey)}")
            check(app.getSharedPreferences("breezeblue-roles", android.content.Context.MODE_PRIVATE).edit().clear().commit())
            resolver.delete(uri, null, null)
        }
    }
}
