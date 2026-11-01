package com.nekobot.app.ui.screens.memory

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.data.local.*
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import kotlinx.coroutines.runBlocking
import com.nekobot.app.data.local.ai.CompanionRecallSettings
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class CompanionRetrievalSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Composable private fun Theme(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        val context = remember { compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration,
            LocalActivityResultRegistryOwner provides compose.activity) {
            NekobotTheme(darkTheme = dark) { CompanionMemoryTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, scale), content = content)
            } }
        }
    }
    private val option = CompanionEmbeddingOption("model", "测试向量模型", "本地测试", "fixture-embedding")
    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(500)
        val i = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bmp = requireNotNull(i.uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        bmp.recycle()
    }
    @Test fun choosingModelDoesNotEnableAndSaveIncludesDraftAndOnlyCurrentRoleLimit() {
        var result: List<Any?>? = null
        compose.setContent { Theme { CompanionRecallSettingsScreen("s", "林小满", null, CompanionRecallSettings(), false, null, {},
            { a,b,_,c,d,_ -> result = listOf(a,b,c,d) }, CompanionEmbeddingSettings(false,null,listOf(option)), {}) } }
        compose.onNodeWithTag("companion-vector-switch").assertIsOff()
        capture("memory-off")
        compose.onNodeWithText("选择已有模型").performClick()
        compose.onNodeWithText(option.name).performClick()
        compose.onNodeWithTag("companion-vector-switch").assertIsOff()
        compose.onNodeWithTag("companion-vector-switch").performClick()
        compose.onNodeWithText("当前使用关键词＋向量").assertIsDisplayed()
        capture("memory-on")
        compose.onNodeWithText("最多回查").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("101")
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("8")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("保存设置").performClick()
        // 资料上限默认 4000 Token，单次回查额度不随“最多回查”轮数变化。
        assertEquals(listOf(8,4000,true,"model"), result)
    }
    @Test fun noModelRequiresSelectionAndAddingDoesNotTurnOnTheSwitch() {
        var adds = 0
        var modelAdded by mutableStateOf<String?>(null)
        var settings by mutableStateOf(CompanionEmbeddingSettings(false,null,emptyList()))
        compose.setContent { Theme { CompanionRecallSettingsScreen("s","林小满",null,CompanionRecallSettings(),false,null,{},
            {_,_,_,_,_,_->},settings,{adds++},modelAdded) } }
        compose.onNodeWithTag("companion-vector-switch").performClick()
        compose.onNodeWithText("还没有可用的向量模型。").assertIsDisplayed()
        compose.onAllNodesWithText("添加向量模型").onLast().performClick()
        assertEquals(1,adds)
        compose.runOnIdle { settings = CompanionEmbeddingSettings(false,null,listOf(option)); modelAdded = option.id }
        compose.onNodeWithText(option.name+"\n"+option.model).assertIsDisplayed()
        compose.onNodeWithTag("companion-vector-switch").assertIsOff()
    }
    @Test fun missingModelCanBeTurnedOffAndLargeTypeCanReachSave() {
        var enabled: Boolean? = null
        compose.setContent { Theme(dark=true,scale=1.6f) { CompanionRecallSettingsScreen("s","很长的角色名称测试",null,
            CompanionRecallSettings(),false,null,{}, {_,_,_,v,_,_->enabled=v}, CompanionEmbeddingSettings(true,"gone",emptyList()),{}) } }
        compose.onNodeWithText("保存设置").assertIsNotEnabled()
        compose.onNodeWithTag("companion-vector-switch").performScrollTo().performClick()
        compose.onNodeWithText("添加向量模型").performScrollTo().assertIsDisplayed()
        capture("memory-dark-large")
        compose.onNodeWithText("保存设置").performClick()
        assertEquals(false,enabled)
    }

    @Test fun realAddEditorSavesEmbeddingConfigWithoutActivatingAChatModel() {
        val repo = ServiceContainer.localRepository
        val name = "offline-editor-${java.util.UUID.randomUUID()}"
        val added = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val before = runBlocking { repo.listAiModels().filter { it.active }.map { it.id }.toSet() }
        try {
            // 模型弹窗需要真实 Activity；使用该 Activity 的语言，避免配置 Context 与 Dialog 语言不同。
            compose.setContent { NekobotTheme { CompanionMemoryTheme {
                CompanionEmbeddingModelEditor(repo, {}, { added.set(it) })
            } } }
            File(compose.activity.getExternalFilesDir("validation"), "embedding-editor-tree.txt")
                .writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).onLast().printToString())
            fun fill(labelId: Int, value: String) {
                val label = compose.activity.getString(labelId)
                compose.onNode(hasSetTextAction() and (hasText(label) or hasAnyDescendant(hasText(label))))
                    .performScrollTo().performTextReplacement(value)
            }
            // 编辑器按“基本 / 连接 / 选项”分步展示，字段只在当前步骤存在。
            fill(R.string.aimodel_editor_field_config_name, name)
            compose.onNodeWithTag("model-setup-next").performClick()
            fill(R.string.aimodel_editor_field_api_key, "offline-fixture-not-a-secret")
            fill(R.string.aimodel_editor_field_base_url, "https://example.invalid/v1")
            fill(R.string.aimodel_editor_field_model, "fixture-embedding")
            capture("embedding-editor")
            compose.onNodeWithTag("model-setup-next").performClick()
            compose.onNodeWithTag("model-setup-next").performClick()
            compose.waitUntil(5000) { added.get() != null }
            val saved = runBlocking { repo.listAiModels().single { it.id == added.get() } }
            assertEquals("embedding",saved.purpose)
            assertFalse(saved.active)
            assertEquals(before,runBlocking { repo.listAiModels().filter { it.active }.map { it.id }.toSet() })
        } finally {
            runBlocking { repo.listAiModels().filter { it.name == name }.forEach { repo.deleteAiModel(it.id) } }
        }
    }
}
