package app.lianxu.independent

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Uses a dedicated synthetic database/vault; never touches the installed user's preferences. */
class SettingsHierarchyTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<LianxuActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="directory-test-${System.nanoTime()}"
    private val vault get()=ModelVault(context,namespace)
    private val a=ModelConfig("directory-a","常用合成服务","deepseek","https://api.deepseek.com","synthetic-daily-model")
    private val b=ModelConfig("directory-b","备用合成服务","silicon-cn","https://api.siliconflow.cn/v1","synthetic-backup-model")
    private lateinit var models:ModelConfigState
    private lateinit var app:AppState
    private var originalCompact:String?=null
    private val calls=AtomicInteger()
    @Before fun setup(){
        val store=Store(context,fixture.database);originalCompact=runBlocking{store.preference("compact","__absent__")};store.close()
        vault.save(a,"synthetic-key-only");vault.save(b,"synthetic-key-only");vault.setDefault(a.id)
        ui.activityRule.scenario.onActivity {activity->
            models=ViewModelProvider(activity,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = ModelConfigState(activity.application,object:ModelDirectoryClient {
                    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult {calls.incrementAndGet();error("Unexpected network request in settings navigation")}
                },namespace) as T
            })[ModelConfigState::class.java]
            app=ViewModelProvider(activity)[AppState::class.java]
        }
        ui.waitUntil(15000){models.configs.size==2&&app.ready}
        if(runBlocking{LocalUpdateRepository(app.processing.store).pending(InstalledBuild.read(context).versionCode)}!=null){ui.waitUntil(10000){ui.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("知道了").performClick();ui.waitUntil(10000){ui.onAllNodesWithText("知道了").fetchSemanticsNodes().isEmpty()}}
        ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
    @After fun cleanup(){
        ui.runOnIdle{if(::models.isInitialized)models.clearResult()}
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        if(::app.isInitialized)ui.waitUntil(10000){!app.busy}
        originalCompact?.let{original->
            val store=Store(context,fixture.database)
            if(original=="__absent__")store.writableDatabase.delete("preferences","key=?",arrayOf("compact"))
            else runBlocking{store.setPreference("compact",original)}
            store.close()
        }
        vault.list().forEach{vault.delete(it.id)};assertEquals(0,calls.get())
    }
    private fun hideKeyboard(){ui.activityRule.scenario.onActivity{activity->(activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0);activity.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideKeyboard();android.os.SystemClock.sleep(350)
        val dir=File(context.getExternalFilesDir(null),"evidence-unified-ui/settings").apply{mkdirs()}
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file=File(dir,"$name.png")
        try{
            file.outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
            if(name in setOf("01-settings-home","02-settings-purposes","03-models-chat-list","04-role-detail")){
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let{file.copyTo(File(it,file.name),overwrite=true)}
            }
        }finally{image.recycle()}
    }
    @Test fun navigationRowsStillOpenFromTheirRightEdgeAndReturn(){
        fun right(tag:String){ui.onNodeWithTag(tag).performScrollTo().assertHasClickAction().performTouchInput{click(androidx.compose.ui.geometry.Offset(width-4f,height*.5f))}}
        fun back(){InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ui.waitForIdle()}
        shot("01-settings-home")
        right("settings-models");ui.onNodeWithTag("config-overview").assertIsDisplayed();shot("03-models-chat-list")
        right("config-edit-${b.id}");ui.onNodeWithTag("config-editor").assertIsDisplayed();back()
        back();right("settings-purposes");shot("02-settings-purposes");right("purpose-select-chat")
        ui.onNodeWithTag("purpose-clear-chat").assertIsDisplayed();back();shot("arrows-model-purposes");back()
        right("settings-memory-rules");ui.onNodeWithTag("memory-rules-page").assertIsDisplayed()
        right("rules-review-open");ui.onNodeWithTag("rules-review-sheet").assertIsDisplayed();back();shot("arrows-memory-rules");back()
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("tab-1").performClick();ui.onNodeWithTag("friend-row-seed-lin").performClick()
        right("role-processing-open");ui.onNodeWithTag("memory-browser").assertIsDisplayed();back()
        shot("04-role-detail");back();ui.onNodeWithTag("tab-2").performClick()
        right("settings-role-archive");shot("arrows-backup");right("backup-role-entry")
        ui.onNodeWithTag("archive-export").assertIsDisplayed();back()
        ui.waitUntil(5000){ui.activity.hasWindowFocus() && ui.onAllNodesWithTag("archive-export").fetchSemanticsNodes().isEmpty()}
        back()
        ui.waitUntil(5000){ui.onAllNodesWithTag("settings-home").fetchSemanticsNodes().isNotEmpty()}
        right("settings-updates");ui.onNodeWithTag("updates-current-toggle").assertIsDisplayed();back()
        right("settings-info");ui.onNodeWithTag("settings-info-page").assertIsDisplayed();back()
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
        assertEquals(a.id,vault.defaultId())
    }
    @Test fun darkDirectoryBadgesContrastWithTheirGlaze(){
        ui.activityRule.scenario.onActivity { activity->
            activity.setContent{LianxuTheme(dark=true){SettingsDirectory(app,models)}}
        }
        for(icon in listOf("model","purposes","memory","tools","archive","updates","about")){
            val node=ui.onNodeWithTag("badge-$icon",useUnmergedTree=true)
            node.performScrollTo()
            val pixels=node.captureToImage().toPixelMap()
            val ground=pixels[pixels.width/2,3].luminance()
            val brightest=(0 until pixels.width).maxOf{x->(0 until pixels.height).maxOf{y->pixels[x,y].luminance()}}
            assertTrue(icon,ground<.15f && (brightest+.05f)/(ground+.05f)>3f)
        }
    }
    @Test fun directoryHomeAndSecondaryRowsWithRealRotation(){
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(a.modelId,substring=true)
        ui.onNodeWithTag("config-name").assertDoesNotExist();ui.onNodeWithTag("compact-switch").assertDoesNotExist()
        ui.onNodeWithText("紧凑列表").assertDoesNotExist();shot("01-settings-home-real-app")
        ui.onNodeWithTag("settings-display").assertDoesNotExist();ui.onNodeWithTag("settings-display-page").assertDoesNotExist()
        ui.onNodeWithText("显示与列表").assertDoesNotExist()
        ui.onNodeWithTag("compact-switch").assertDoesNotExist()
        ui.onNodeWithText("紧凑列表").assertDoesNotExist()
        val preserved=Store(context,fixture.database).let{store->try{runBlocking{store.preference("compact","__absent__")}}finally{store.close()}}
        assertEquals(originalCompact,preserved)
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("settings-home").assertIsDisplayed();ui.onNodeWithTag("settings-display").assertDoesNotExist();ui.onNodeWithTag("compact-switch").assertDoesNotExist()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        shot("03-settings-after-rotation-real-app")
        ui.onNodeWithTag("settings-info").performScrollTo().performClick();ui.onNodeWithTag("settings-info-page").assertIsDisplayed()
        ui.onNodeWithTag("settings-version").assertTextEquals(InstalledBuild.read(context).versionLabel)
        ui.onNodeWithTag("compact-switch").assertDoesNotExist();shot("04-data-version-secondary-real-app")
        ui.onNodeWithTag("settings-back").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        listOf(0,3,1,2).forEach{i->ui.onNodeWithTag("tab-$i").performClick();ui.onNodeWithTag("tab-$i").assertIsSelected()}
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
    @Test fun compactOptionRemovedWithoutDeletingLegacyPreference(){
        Store(context,fixture.database).let{store->try{runBlocking{store.setPreference("compact","true")}}finally{store.close()}}
        ui.onNodeWithTag("settings-display").assertDoesNotExist()
        ui.onNodeWithTag("settings-info").performScrollTo().performClick()
        ui.onNodeWithTag("settings-info-page").assertIsDisplayed()
        ui.onNodeWithTag("compact-switch").assertDoesNotExist();ui.onNodeWithText("紧凑列表").assertDoesNotExist()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.onNodeWithTag("settings-info-page").assertIsDisplayed();ui.onNodeWithTag("compact-switch").assertDoesNotExist()
        ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        shot("03-about-after-rotation-real-app")
        val preserved=Store(context,fixture.database).let{store->try{runBlocking{store.preference("compact","__absent__")}}finally{store.close()}}
        assertEquals("true",preserved)
        ui.onNodeWithContentDescription("返回设置").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        shot("01-settings-home-real-app")
    }
    @Test fun modelDirectoryDefaultSummaryAndEditCancel(){
        ui.onNodeWithTag("settings-models").performTouchInput{doubleClick()}
        ui.onNodeWithTag("config-overview").assertIsDisplayed();ui.onNodeWithTag("config-editor").assertDoesNotExist()
        shot("02-model-secondary-real-app")
        ui.onNodeWithTag("config-edit-${b.id}").performClick()
        ui.onNodeWithTag("config-default-${b.id}").performScrollTo().performClick()
        ui.waitUntil(10000){models.defaultId==b.id&&!models.saving}
        ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("config-back").performClick()
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(b.modelId,substring=true)
        ui.onNodeWithTag("settings-models").performClick();ui.onNodeWithTag("config-edit-${b.id}").performClick()
        ui.onNodeWithTag("config-name").performTextReplacement("未保存合成草稿")
        hideKeyboard();ui.onNodeWithTag("config-back").performClick();ui.onNodeWithTag("config-back").performClick()
        assertEquals(b.name,vault.list().single{it.id==b.id}.name)
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(b.name,substring=true)
        ui.activityRule.scenario.recreate();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-model-summary",useUnmergedTree=true).assertTextContains(b.modelId,substring=true)
    }
    @Test fun repeatedDirectoryClickAndSystemBackReturnOnce(){
        ui.onNodeWithTag("settings-models").performTouchInput{doubleClick()}
        ui.onNodeWithTag("config-overview").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle();ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-info").performScrollTo().performTouchInput{doubleClick()}
        ui.onNodeWithTag("settings-info-page").assertIsDisplayed()
        ui.onNodeWithTag("settings-back").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
    }
    @Test fun removingEmptyDisplayPageKeepsOtherSettingsDestinations(){
        ui.onNodeWithTag("settings-display").assertDoesNotExist()
        ui.onNodeWithTag("settings-memory-rules").performClick()
        ui.onNodeWithTag("memory-rules-page").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-role-archive").performScrollTo().performClick()
        ui.onNodeWithTag("role-archive-page").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-updates").performScrollTo().performClick()
        ui.onNodeWithTag("updates-current-toggle").assertTextContains(InstalledBuild.read(context).versionName,substring=true)
        ui.onNodeWithTag("settings-back").performClick()
        ui.onNodeWithTag("settings-home").assertIsDisplayed()
        ui.onNodeWithTag("settings-models").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("settings-display").assertDoesNotExist()
    }
}
