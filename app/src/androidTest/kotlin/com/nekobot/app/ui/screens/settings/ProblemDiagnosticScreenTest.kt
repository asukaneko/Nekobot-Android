package com.nekobot.app.ui.screens.settings

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.*
import com.nekobot.app.data.local.diagnostics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

class ProblemDiagnosticScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "diagnostic-${UUID.randomUUID()}"
    private val question = "$id-question"
    private val answer = "$id-answer"
    private val history = """[{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"agent_recall_search","arguments":"{\"query\":\"PRIVATE-QUERY\",\"api_key\":\"small-private-key\"}"}}]},{"role":"tool","tool_call_id":"c1","content":"{\"success\":true,\"status\":\"found\",\"rounds_left\":0,\"tokens_left\":800,\"results\":[{\"content\":\"PRIVATE-MATERIAL\"}]}"}]"""
    private fun db() = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
    private suspend fun seed() {
        db().sessionDao().upsert(LocalSessionEntity(id, "合成诊断测试", createdAt="2026-10-19T21:00:00",updatedAt="2026-10-19T21:00:00"))
        db().messageDao().upsert(LocalMessageEntity(question,id,"user","PRIVATE-QUESTION",timestamp="1",createdAt="2026-10-19T21:00:00"))
        db().messageDao().upsert(LocalMessageEntity(answer,id,"assistant","PRIVATE-ANSWER",timestamp="2",createdAt="2026-10-19T21:00:01",toolCallHistory=history))
        ProblemDiagnostics.record(id,db().dbName,id,question,"reply","recall_context",mapOf("question_ref" to diagnosticRef("PRIVATE-QUESTION")))
        ProblemDiagnostics.record(id,db().dbName,id,question,"reply","recall_prepare",mapOf("rounds" to 3,"max_rounds" to 3,"exhausted" to true,"reason" to "rounds"))
        ProblemDiagnostics.record(id,db().dbName,id,question,"reply","reply_saved",mapOf("reply_ref" to diagnosticRef(answer),
            "body_ref" to diagnosticRef("PRIVATE-ANSWER"),"history_ref" to diagnosticRef(history),"variant" to 0,"status" to "completed"))
    }
    @Test fun collectorExcludesTextByDefaultAndOptionalTextIsScopedAndFiltered() = runBlocking {
        seed()
        try {
            val collector=ServiceContainer.localRepository.problemDiagnosticCollector()
            val basic=collector.collect(DiagnosticScope.REPLY,id,answer,false,"")
            val text=basic.files.values.joinToString()
            listOf("PRIVATE-QUESTION","PRIVATE-ANSWER","PRIVATE-QUERY","PRIVATE-MATERIAL","small-private-key").forEach { assertFalse(it,text.contains(it)) }
            assertTrue(text.contains("rounds")); assertTrue(text.contains("found"))
            assertFalse(basic.files.containsKey("本次资料.txt"))
            val detailed=collector.collect(DiagnosticScope.REPLY,id,answer,true,"").files.getValue("本次资料.txt")
            listOf("PRIVATE-QUESTION","PRIVATE-ANSWER","PRIVATE-QUERY","PRIVATE-MATERIAL").forEach { assertTrue(it,detailed.contains(it)) }
            assertFalse(detailed.contains("small-private-key"))
            val zip=File(compose.activity.cacheDir,"$id.zip")
            try { zip.writeBytes(basic.zip()); ZipFile(zip).use { assertEquals(3,it.size()); assertNotNull(it.getEntry("诊断记录.json")) } }
            finally { zip.delete() }
            assertTrue(runCatching { collector.collect(DiagnosticScope.REPLY,"different-session",answer,true,"") }.isFailure)
        } finally { db().sessionDao().deleteById(id) }
    }
    @Test fun regeneratedCandidateDoesNotBorrowOriginalToolHistory() = runBlocking {
        seed()
        try {
            val reply=db().messageDao().getById(answer)!!
            db().messageDao().upsert(reply.copy(content="new reply",variantIndex=1))
            ProblemDiagnostics.record("$id-new",db().dbName,id,question,"reply","reply_saved",mapOf("reply_ref" to diagnosticRef(answer),
                "body_ref" to diagnosticRef("new reply"),"history_ref" to diagnosticRef("different-history"),"variant" to 1,"status" to "completed"))
            val bundle=ServiceContainer.localRepository.problemDiagnosticCollector().collect(DiagnosticScope.REPLY,id,answer,true,"")
            assertFalse(bundle.files.values.joinToString().contains("PRIVATE-MATERIAL"))
            assertTrue(bundle.missing.any { it.contains("其他候选") })
        } finally { db().sessionDao().deleteById(id) }
    }
    @Test fun unfinishedRunIsBoundToTheReplyAndKeepsPendingCalls() = runBlocking {
        seed()
        try {
            db().agentRunDao().upsert(LocalAgentRunEntity(id,"$id-run",question,"PRIVATE-PROMPT",null,"paused","tools",null,1,
                null,null,answer,"now","now"))
            val rows=com.google.gson.JsonParser.parseString(history).asJsonArray
            rows.forEach { row -> db().agentToolMessageDao().insert(LocalAgentToolMessageEntity(sessionId=id,runId="$id-run",
                role=row.asJsonObject["role"].asString,payload=row.toString(),createdAt="now")) }
            db().agentToolMessageDao().insert(LocalAgentToolMessageEntity(sessionId=id,runId="$id-run",role="assistant",createdAt="now",
                payload="""{"role":"assistant","tool_calls":[{"id":"pending","function":{"name":"agent_history_read","arguments":"{}"}}]}"""))
            db().agentToolMessageDao().insert(LocalAgentToolMessageEntity(sessionId=id,runId="unrelated-run",role="tool",createdAt="now",
                payload="""{"role":"tool","content":"PRIVATE-UNRELATED"}"""))
            val collector=ServiceContainer.localRepository.problemDiagnosticCollector()
            val basic=collector.collect(DiagnosticScope.REPLY,id,answer,false,"")
            val json=com.google.gson.JsonParser.parseString(basic.files.getValue("诊断记录.json")).asJsonObject
            assertEquals("matched_unfinished_run",json["tool_history_source"].asString)
            assertTrue(json["tool_calls"].asJsonArray.any { it.asJsonObject["state"].asString=="pending" })
            val details=collector.collect(DiagnosticScope.REPLY,id,answer,true,"").files.values.joinToString()
            assertTrue(details.contains("PRIVATE-MATERIAL"));assertFalse(details.contains("PRIVATE-UNRELATED"));assertFalse(details.contains("PRIVATE-PROMPT"))
        } finally { db().agentRunDao().deleteBySession(id);db().sessionDao().deleteById(id) }
    }
    @Test fun lightPagePreviewsBasicAndExplicitlyIncludedMaterial() = page(false,1f)
    @Test fun darkLargeTextKeepsPreviewAndSaveReachable() = page(true,1.6f)
    @Test fun importReportPreservesKeptVersusUpdatedCounts() = runBlocking {
        ProblemDiagnostics.record("$id-import",db().dbName,"","","import","import_result",
            mapOf("stage" to "commit","status" to "completed","kept" to 1,"updated_rows" to 0,"committed" to true))
        val report=ServiceContainer.localRepository.problemDiagnosticCollector().collect(DiagnosticScope.IMPORT,null,null,false,"")
        val json=com.google.gson.JsonParser.parseString(report.files.getValue("诊断记录.json")).asJsonObject
        val values=json["operation"].asJsonObject["events"].asJsonArray.last().asJsonObject["values"].asJsonObject
        assertEquals(1,values["kept"].asInt);assertEquals(0,values["updated_rows"].asInt)
        assertFalse(report.files.containsKey("本次资料.txt"))
    }
    @Test fun chatLongPressMoreOpensTheSelectedReplyDiagnostic() {
        runBlocking { seed() }
        try {
            compose.setContent { MaterialTheme { com.nekobot.app.ui.screens.chat.ChatScreen(id,onBack={}) } }
            compose.waitUntil(15000) { compose.onAllNodesWithText("PRIVATE-ANSWER",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("PRIVATE-ANSWER",substring=true).performTouchInput { longClick() }
            compose.onNodeWithTag("message-action-more").performClick()
            compose.onNodeWithTag("message-action-diagnostic").assertIsDisplayed()
            capture("chat-menu")
            compose.onNodeWithTag("message-action-diagnostic").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("problem-diagnostic-form-title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("problem-diagnostic-list").performScrollToNode(hasTestTag("problem-diagnostic-include-text"))
            compose.onNodeWithTag("problem-diagnostic-include-text").assertIsOff()
            capture("chat-entry")
        } finally { runBlocking { db().sessionDao().deleteById(id) } }
    }
    private fun page(dark:Boolean,scale:Float) {
        runBlocking { seed() }
        try {
            val localized=compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
            compose.setContent {
                CompositionLocalProvider(LocalContext provides localized, LocalActivityResultRegistryOwner provides compose.activity,
                    LocalDensity provides Density(compose.activity.resources.displayMetrics.density,scale)) {
                    MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) { ProblemDiagnosticScreen(id,answer,onBack={}) }
                }
            }
            val list=compose.onNodeWithTag("problem-diagnostic-list")
            list.performScrollToNode(hasTestTag("problem-diagnostic-include-text"))
            compose.onNodeWithTag("problem-diagnostic-include-text").assertIsOff()
            list.performScrollToIndex(0)
            capture("${if(dark) "dark-large" else "light"}-form")
            list.performScrollToNode(hasTestTag("problem-diagnostic-preview"))
            compose.onNodeWithTag("problem-diagnostic-preview").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("problem-diagnostic-preview-title").fetchSemanticsNodes().isNotEmpty() }
            list.performScrollToNode(hasTestTag("problem-diagnostic-file-诊断记录.json"))
            compose.onNodeWithTag("problem-diagnostic-file-诊断记录.json").assertIsDisplayed()
            list.performScrollToNode(hasTestTag("problem-diagnostic-save"))
            compose.onNodeWithTag("problem-diagnostic-save").assertIsDisplayed()
            capture("${if(dark) "dark-large" else "light"}-preview")
            if (!dark) {
                compose.onNodeWithTag("problem-diagnostic-save").performClick()
                val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                val deadline=android.os.SystemClock.elapsedRealtime()+10000
                while (android.os.SystemClock.elapsedRealtime()<deadline && automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true)
                    android.os.SystemClock.sleep(50)
                assertTrue("Android file picker did not open",automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitUntil(5000) { compose.onAllNodesWithTag("problem-diagnostic-busy").fetchSemanticsNodes().isEmpty() }
                list.performScrollToNode(hasTestTag("problem-diagnostic-save"))
                compose.onNodeWithTag("problem-diagnostic-save").assertIsEnabled()
            }
            list.performScrollToNode(hasText(localized.getString(com.nekobot.app.R.string.problem_diag_adjust)))
            compose.onNodeWithText(localized.getString(com.nekobot.app.R.string.problem_diag_adjust)).performClick()
            list.performScrollToNode(hasTestTag("problem-diagnostic-include-text"))
            compose.onNodeWithTag("problem-diagnostic-include-text").performClick()
            list.performScrollToNode(hasTestTag("problem-diagnostic-preview"))
            compose.onNodeWithTag("problem-diagnostic-preview").performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithTag("problem-diagnostic-preview-title").fetchSemanticsNodes().isNotEmpty() }
            list.performScrollToNode(hasTestTag("problem-diagnostic-file-本次资料.txt"))
            compose.onNodeWithTag("problem-diagnostic-file-本次资料.txt").performClick()
            list.performScrollToNode(hasText("PRIVATE-ANSWER",substring=true))
            compose.onNodeWithText("PRIVATE-ANSWER",substring=true).assertExists()
            capture("${if(dark) "dark-large" else "light"}-details")
            if (!dark) {
                list.performScrollToNode(hasTestTag("problem-diagnostic-save"))
                compose.onNodeWithTag("problem-diagnostic-save").performClick()
                val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                var clicked=false
                val deadline=android.os.SystemClock.elapsedRealtime()+10000
                while (!clicked && android.os.SystemClock.elapsedRealtime()<deadline) {
                    val root=automation.rootInActiveWindow
                    if(root?.packageName?.toString()?.contains("documentsui")==true) {
                        val buttons=listOf("Save","保存").flatMap { root.findAccessibilityNodeInfosByText(it).orEmpty() }
                        val button=buttons.firstOrNull { it.isEnabled && (it.text?.toString()?.lowercase(Locale.ROOT) in setOf("save","保存") || it.contentDescription?.toString()?.lowercase(Locale.ROOT) in setOf("save","保存")) }
                        if(button!=null) clicked=if(button.isClickable) button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            else button.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
                    }
                    if(!clicked) android.os.SystemClock.sleep(50)
                }
                assertTrue("Android save action was unavailable",clicked)
                compose.waitUntil(10000) { compose.onAllNodesWithTag("problem-diagnostic-save").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
                compose.onNodeWithTag("problem-diagnostic-save").assertIsEnabled()
            }
        } finally { runBlocking { db().sessionDao().deleteById(id) } }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir=File(compose.activity.getExternalFilesDir(null),"problem-diagnostic").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
