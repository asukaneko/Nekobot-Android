package com.nekobot.app.data.local.ai

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.theme.NekobotTheme
import com.nekobot.app.data.local.LocalAgentRecallReader
import com.nekobot.app.data.local.db.*
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.*

/** 合成资料 + 设备内TLS回环响应；验证真实数据库/HTTP/流式/发布边界，不评估外部模型能力。 */
@RunWith(AndroidJUnit4::class)
class CompanionRecallClosingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val gson = Gson()
    private val db get() = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
    private val tools = buildAgentRecallToolDefinitions()
    private val markup = "<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name=\"agent_history_search\">"

    private data class Sample(val key: String, val question: String, val query: String, val source: String, val answer: String)
    private val samples = listOf(
        Sample("meeting", "你记得我们最初在哪里认识的吗？", "第一次认识", "我们第一次认识是在星禾书店门口。", "我记得，是在星禾书店门口。"),
        Sample("preference", "我现在喜欢喝什么茶？", "红茶", "我现在改成喜欢红茶，之前喜欢绿茶。", "你现在喜欢红茶呀。"),
        Sample("promise", "我们之前约好去哪儿？", "周六", "我们约好周六去白沙湾散步。", "我们约好了周六去白沙湾散步。")
    )

    private inner class Fixture(val id: String, val session: LocalSessionEntity, val callbacks: LocalPipelineCallbacks,
        val ctx: PipelineContext, val modelCall: ModelCall, val messages: MutableList<Map<String, Any>>) {
        val sourceId get() = "$id-source"
        val questionId get() = "$id-question"
        suspend fun execute(result: Map<String, Any>): Map<String, Any> {
            @Suppress("UNCHECKED_CAST")
            val calls = result["tool_calls"] as List<Map<String, Any>>
            assertEquals(1, calls.size)
            val call = calls.single()
            val function = call["function"] as? Map<*, *> ?: call
            val name = function["name"] as String
            @Suppress("UNCHECKED_CAST")
            val args = (function["arguments"] as? Map<String, Any>)
                ?: gson.fromJson(function["arguments"] as String, Map::class.java) as Map<String, Any>
            val wireCalls = listOf(mapOf("id" to call["id"]!!, "type" to "function",
                "function" to mapOf("name" to name, "arguments" to gson.toJson(args))))
            messages += mapOf("role" to "assistant", "content" to "", "tool_calls" to wireCalls)
            val raw = callbacks.executeTool(name, args, mapOf(SESSION_ALLOWED_TOOL_NAMES_CONTEXT_KEY to tools.mapNotNull(::toolNameOf)))
            messages += mapOf("role" to "tool", "name" to name, "tool_call_id" to call["id"]!!, "content" to gson.toJson(raw))
            return raw
        }
        suspend fun cleanup() { db.sessionDao().deleteById(id); db.characterDao().deleteById("$id-role") }
    }

    private suspend fun fixture(server: Loopback, source: String, question: String, rounds: Int = 2, tokens: Int = 4000,
        thinking: Boolean = false): Fixture {
        val id = "recall-test-${UUID.randomUUID()}"
        db.characterDao().upsert(LocalCharacterEntity("$id-role", "林小满（合成测试）", createdAt="now", updatedAt="now"))
        val session = LocalSessionEntity(id, "回查收尾合成测试", characterId="$id-role", sessionMode="agent",
            inheritCharacter=true, longConversationEnabled=true, companionMode=true, createdAt="now", updatedAt="now")
        db.sessionDao().upsert(session)
        db.messageDao().upsert(LocalMessageEntity("$id-source", id, "user", source, timestamp="2026-10-01T10:00:00Z", createdAt="2026-10-01 10:00:00"))
        db.messageDao().upsert(LocalMessageEntity("$id-question", id, "user", question, timestamp="2026-10-20T00:00:00Z", createdAt="2026-10-20 00:00:00"))
        ServiceContainer.prefs.saveCompanionRecallSettings(db.dbName, id, CompanionRecallSettings(rounds, tokens))
        if (thinking) ServiceContainer.prefs.setCompanionShowReasoning(db.dbName, id, true)
        val model = LocalAiModelEntity("$id-model", "设备内合成响应", protocol="openai_chat", apiKey="",
            baseUrl="https://127.0.0.1:${server.port}/v1", model="fixture", supportsStream=true,
            maxContextLength=128000, maxTokens=4000, createdAt="now")
        val runtime = if (thinking) CharacterRuntime(profileRepo = object : ProfileRepository {
            override suspend fun getById(id: String) = CharacterProfile(id=id, name="林小满", systemPrompt="你是林小满，按平时的口吻聊天。")
        }) else null
        val callbacks = LocalPipelineCallbacks(db, server.client, model, session, db.characterDao().getById("$id-role"),
            characterRuntime=runtime, characterIdentity=if(thinking) CharacterIdentity(characterId="$id-role", targetId="user", scopeId=id) else null,
            reasoningEffort=if(thinking) com.nekobot.app.data.model.ReasoningEffort.HIGH else com.nekobot.app.data.model.ReasoningEffort.NONE,
            parentMessageId="$id-question", recallReader=LocalAgentRecallReader(db), isSessionToolEnabled={ true })
        val ctx = PipelineContext(ChatRequest.forLocal(id, question))
        val messages = if(thinking) {
            AIPipeline().prepareContext(ctx,callbacks)
            ctx.messages.toMutableList()
        } else {
            callbacks.loadMessages(ctx)
            mutableListOf(mapOf<String, Any>("role" to "system", "content" to "你是林小满，按平时的口吻聊天。"),
                mapOf<String, Any>("role" to "user", "content" to question))
        }
        return Fixture(id, session, callbacks, ctx, callbacks.buildModelCall(ctx, tools), messages)
    }

    private fun tool(name: String, args: Map<String, Any>) = mapOf<String, Any>("role" to "assistant", "content" to "",
        "tool_calls" to listOf(mapOf("id" to "call-${UUID.randomUUID()}", "type" to "function",
            "function" to mapOf("name" to name, "arguments" to gson.toJson(args)))))
    private fun answer(text: String) = mapOf<String, Any>("role" to "assistant", "content" to text)
    private fun sentNote(payload: JsonObject) = payload["messages"].asJsonArray.count {
        it.asJsonObject["role"].asString == "system" && it.asJsonObject["content"]?.asString == CompanionRecallFinal.FINAL_NOTE }

    @Test fun fullDiagnosticExportsActualRequestsAndMissingThoughtWithoutTruncatingPrompts() = runBlocking {
        Loopback().use { server ->
            val f=fixture(server,"我们约好周六散步。","我们的约定是什么？",thinking=true)
            try {
                val longPrompt="synthetic-context ".repeat(9000)+"END-OF-FULL-PROMPT"
                f.messages.add(0,mapOf("role" to "system","content" to longPrompt))
                server.respond={ i,_ -> if(i==0) tool("agent_history_search",mapOf("query" to "散步"))
                    else answer("（我记得这个约定。）周六一起散步呀。") + ("reasoning_content" to "Synthetic native thought.") }
                f.execute(f.modelCall(f.messages,false))
                val result=f.modelCall(f.messages,false)
                f.callbacks.saveAssistantMessage(f.ctx,result + ("id" to "${f.id}-reply"))
                val collector=com.nekobot.app.data.local.diagnostics.ProblemDiagnosticCollector(db,compose.activity)
                val scope=com.nekobot.app.data.local.diagnostics.DiagnosticScope.REPLY
                val basic=collector.collect(scope,f.id,"${f.id}-reply",false,"合成测试")
                assertFalse(basic.files.containsKey("完整提示词.txt"))
                assertFalse(basic.files.values.joinToString().contains("END-OF-FULL-PROMPT"))
                val bundle=collector.collect(scope,f.id,"${f.id}-reply",true,"合成测试")
                val trace=JsonParser.parseString(bundle.files.getValue("完整请求与返回.json")).asJsonObject
                val events=trace["events"].asJsonArray.map { it.asJsonObject }
                val requests=events.filter { it["kind"].asString=="http_request" }
                assertEquals(server.requests.size,requests.size)
                requests.forEachIndexed { i, event -> assertEquals(server.requests[i],event["data"].asJsonObject["payload"]) }
                assertTrue(bundle.files.getValue("完整提示词.txt").contains(longPrompt))
                val prepared=events.first { it["kind"].asString=="pipeline_prepared" }["data"].asJsonObject
                assertTrue(prepared["character_thinking_enabled"].asBoolean)
                assertTrue(prepared["show_reasoning"].asBoolean)
                assertTrue(prepared["prompt_stack"].toString().contains("<think>"))
                val parsed=events.last { it["kind"].asString=="parsed_reply" }["data"].asJsonObject
                assertEquals("",parsed["thinking_content"].asString)
                assertTrue(parsed["content"].asString.startsWith("（"))
                assertEquals(2,events.count { it["kind"].asString=="http_response" })
                val zipped=bundle.zip()
                java.util.zip.ZipInputStream(zipped.inputStream(),Charsets.UTF_8).use { zip ->
                    val read=linkedMapOf<String,String>()
                    while(true) { val entry=zip.nextEntry ?: break; read[entry.name]=zip.readBytes().toString(Charsets.UTF_8); zip.closeEntry() }
                    assertEquals(bundle.files,read)
                }
                File(out(),"full-diagnostic-synthetic.zip").writeBytes(zipped)
                compose.setContent { NekobotTheme {
                    com.nekobot.app.ui.screens.settings.ProblemDiagnosticScreen(f.id,"${f.id}-reply") {}
                } }
                val list=compose.onNodeWithTag("problem-diagnostic-list")
                list.performScrollToNode(hasTestTag("problem-diagnostic-include-text"))
                compose.onNodeWithTag("problem-diagnostic-include-text").performClick()
                list.performScrollToNode(hasTestTag("problem-diagnostic-preview"))
                compose.onNodeWithTag("problem-diagnostic-preview").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("problem-diagnostic-preview-title").fetchSemanticsNodes().isNotEmpty() }
                list.performScrollToNode(hasTestTag("problem-diagnostic-file-完整提示词.txt"))
                compose.onNodeWithTag("problem-diagnostic-file-完整提示词.txt").assertIsDisplayed()
                screenshot("full-diagnostic-preview.png")
                record("full-diagnostic",mapOf("http_requests" to requests.size,"wire_payloads_equal" to true,
                    "long_prompt_characters" to longPrompt.length,"text_truncated" to false,"basic_export_private" to true,
                    "missing_thought_reproduced" to true,"external_model_calls" to 0))
            } finally { f.cleanup() }
        }
    }

    @Test fun fixedMemoryQuestionsSearchReadAndCloseWithoutExtraToolExecution() = runBlocking {
        val observations = mutableListOf<Map<String, Any>>()
        for (sample in samples) Loopback().use { server ->
            val f = fixture(server, sample.source, sample.question)
            try {
                server.respond = { index, _ -> when(index) {
                    0 -> tool("agent_history_search", mapOf("query" to sample.query, "limit" to 8))
                    1 -> tool("agent_history_read", mapOf("message_id" to f.sourceId, "before" to 2, "after" to 2))
                    else -> answer(sample.answer)
                } }
                val found = f.execute(f.modelCall(f.messages, false))
                assertTrue((found["results"] as List<*>).any { (it as Map<*, *>)["message_id"] == f.sourceId })
                val read = f.execute(f.modelCall(f.messages, false))
                assertTrue((read["messages"] as List<*>).any { (it as Map<*, *>)["content"] == sample.source })
                val result = f.modelCall(f.messages, false)
                assertEquals(sample.answer, result["content"])
                assertEquals(3, server.requests.size)
                assertEquals(0, sentNote(server.requests[0])); assertEquals(0, sentNote(server.requests[1]))
                assertEquals(1, sentNote(server.requests[2])); assertFalse(server.requests[2].has("tools"))
                server.requests[2]["messages"].asJsonArray.filter { it.asJsonObject["role"].asString == "tool" }.forEach {
                    val sent = JsonParser.parseString(it.asJsonObject["content"].asString).asJsonObject
                    assertFalse(sent.has("rounds_left")); assertFalse(sent.has("tokens_left"))
                }
                val usage = gson.fromJson(ServiceContainer.prefs.companionRecallUsage(db.dbName, f.id, f.questionId), CompanionRecallUsage::class.java)
                assertEquals(2, usage.rounds)
                f.callbacks.saveAssistantMessage(f.ctx, result + ("id" to "${f.id}-reply"))
                assertEquals(sample.answer, db.messageDao().getById("${f.id}-reply")!!.content)
                observations += mapOf("sample" to sample.key, "model_requests" to server.requests.size, "read_rounds" to usage.rounds,
                    "read_tokens" to usage.tokens, "answer_fixture_matched" to true, "closing_note_count" to 1)
            } finally { f.cleanup() }
        }
        record("fixed-questions", observations)
    }

    @Test fun missingEvidenceClosesWithoutClaimingTheEventNeverHappened() = runBlocking {
        Loopback().use { server ->
            val f = fixture(server, "我们聊过书店和散步。", "我的老家在哪里？", rounds=1)
            try {
                server.respond = { i, _ -> if(i == 0) tool("agent_history_search", mapOf("query" to "出生地"))
                    else answer("这个我还记不清，不能乱说。") }
                val raw = f.execute(f.modelCall(f.messages, false))
                assertTrue((raw["results"] as List<*>).isEmpty())
                val result = f.modelCall(f.messages, false)
                assertTrue(result["content"].toString().contains("记不清"))
                val payload = server.requests.last()
                assertEquals(1, sentNote(payload)); assertFalse(payload.has("tools"))
                assertTrue(payload.toString().contains("不代表这件事没有发生过"))
                record("missing-evidence", mapOf("requests" to server.requests.size, "no_evidence" to true, "closing_note" to true))
            } finally { f.cleanup() }
        }
    }

    @Test fun longOriginalUsesReturnedOffsetsToReachTheEndWithoutRepeatingText() = runBlocking {
        Loopback().use { server ->
            val source = "长原话开头。" + "虚构场景细节。".repeat(700) + "终点答案是蓝色风铃。"
            val f = fixture(server, source, "那段很长的原话最后说了什么？", rounds=10, tokens=16000)
            try {
                server.respond = { i, payload ->
                    if(i == 0) tool("agent_history_read", mapOf("message_id" to f.sourceId, "before" to 0, "after" to 0))
                    else {
                        val raw = JsonParser.parseString(payload["messages"].asJsonArray.last().asJsonObject["content"].asString).asJsonObject
                        val row = raw["messages"].asJsonArray.single().asJsonObject
                        if(row["truncated"].asBoolean) tool("agent_history_read", mapOf("message_id" to f.sourceId, "text_offset" to row["next_text_offset"].asInt))
                        else answer("最后说的是蓝色风铃。")
                    }
                }
                val joined = StringBuilder(); val offsets = mutableListOf<Int>()
                var result = f.modelCall(f.messages, false)
                var reads = 0
                while(result["tool_calls"] != null) {
                    assertTrue("续读应有界", reads++ < 10)
                    val raw = f.execute(result)
                    val row = (raw["messages"] as List<*>).single() as Map<*, *>
                    offsets += (row["text_offset"] as Number).toInt()
                    joined.append(row["content"] as String)
                    result = f.modelCall(f.messages, false)
                }
                assertEquals(source, joined.toString()); assertTrue(offsets.size > 1)
                assertEquals(offsets.distinct().sorted(), offsets)
                assertEquals("最后说的是蓝色风铃。", result["content"])
                record("continuation", mapOf("reads" to reads, "offsets" to offsets, "source_reconstructed_exactly" to true))
            } finally { f.cleanup() }
        }
    }

    @Test fun rawInvocationIsBlockedDuringAndBeforeQuotaExhaustionAndCannotBeSaved() = runBlocking {
        for (exhaust in listOf(false, true)) Loopback().use { server ->
            val f = fixture(server, "我们初次见面在书店。", "你还记得那天吗？", rounds=1)
            try {
                server.respond = { i, _ -> if(exhaust && i == 0) tool("agent_history_search", mapOf("query" to "书店")) else answer(markup) }
                if(exhaust) f.execute(f.modelCall(f.messages, false))
                assertTrue(runCatching { f.modelCall(f.messages, false) }.isFailure)
                val chunks = mutableListOf<String>()
                while(true) {
                    val event = f.callbacks.eventChannel.tryReceive().getOrNull() ?: break
                    if(event is RealtimeEvent.StreamChunk) chunks += event.chunk
                }
                assertFalse(chunks.joinToString("").contains("DSML")); assertFalse(chunks.joinToString("").contains("<｜"))
                assertTrue(runCatching { f.callbacks.saveAssistantMessage(f.ctx, mapOf("id" to "${f.id}-bad", "content" to markup)) }.isFailure)
                assertNull(db.messageDao().getById("${f.id}-bad"))
                assertEquals(if(exhaust) 2 else 1, server.requests.size)
                record("blocked-$exhaust", mapOf("requests" to server.requests.size, "not_published" to true, "not_saved" to true, "implicit_raw_retry" to false))
            } finally { f.cleanup() }
        }
    }

    @Test fun tokenLimitClosesEvenWhenConfiguredRoundsRemain() = runBlocking {
        Loopback().use { server ->
            val f = fixture(server, "一段需要分次读取的合成原话。".repeat(500), "把那段原话翻给我看看。", rounds=3, tokens=256)
            try {
                server.respond = { i, _ -> if(i == 0) tool("agent_history_read", mapOf("message_id" to f.sourceId, "before" to 0, "after" to 0))
                    else answer("这段还没读完，我先说能确定的部分。") }
                val raw = f.execute(f.modelCall(f.messages, false))
                assertEquals("truncated", raw["status"])
                val result = f.modelCall(f.messages, false)
                assertTrue(result["content"].toString().contains("还没读完"))
                val usage = gson.fromJson(ServiceContainer.prefs.companionRecallUsage(db.dbName, f.id, f.questionId), CompanionRecallUsage::class.java)
                assertEquals(1, usage.rounds); assertTrue(usage.tokens <= 256); assertTrue(256 - usage.tokens < 128)
                assertFalse(server.requests.last().has("tools")); assertEquals(1, sentNote(server.requests.last()))
                record("tokens-before-rounds", mapOf("rounds" to usage.rounds, "max_rounds" to 3, "tokens" to usage.tokens,
                    "max_tokens" to 256, "closed_with_rounds_left" to true))
            } finally { f.cleanup() }
        }
    }

    @Test fun boundedStructuredRetryRejectsBadSecondResultInsteadOfSavingAFixedLine() = runBlocking {
        Loopback().use { server ->
            val f = fixture(server, "我们在书店初次见面。", "记得吗？", rounds=1)
            try {
                server.respond = { i, _ -> if(i < 2) tool("agent_history_search", mapOf("query" to "书店")) else answer(markup) }
                f.execute(f.modelCall(f.messages, false))
                assertTrue(runCatching { f.modelCall(f.messages, false) }.isFailure)
                assertEquals(3, server.requests.size)
                assertFalse(server.requests[2].has("tools")); assertEquals(1, sentNote(server.requests[2]))
                assertTrue(db.messageDao().listBySession(f.id).none { it.role == "assistant" })
                record("bounded-retry", mapOf("model_requests" to 3, "actual_tool_executions" to 1, "no_fixed_reply" to true))
            } finally { f.cleanup() }
        }
    }

    @Test fun validClosingReplyAppearsInTheActualChatScreen() = runBlocking {
        Loopback().use { server ->
            val sample = samples.first()
            val f = fixture(server, sample.source, sample.question, rounds=1)
            try {
                server.respond = { i, _ -> if(i == 0) tool("agent_history_search", mapOf("query" to "第一次认识")) else answer(sample.answer) }
                f.execute(f.modelCall(f.messages, false))
                val result = f.modelCall(f.messages, false)
                f.callbacks.saveAssistantMessage(f.ctx, result + ("id" to "${f.id}-reply"))
                val ordered = db.messageDao().listBySession(f.id)
                assertTrue(ordered.indexOfFirst { it.id == f.questionId } < ordered.indexOfFirst { it.id == "${f.id}-reply" })
                compose.setContent { NekobotTheme { com.nekobot.app.ui.screens.chat.ModernChatScreen(f.id, onBack={}) } }
                compose.waitUntil(15000) { compose.onAllNodesWithText(sample.answer, substring=true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(sample.answer, substring=true).assertIsDisplayed()
                compose.waitForIdle()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                android.os.SystemClock.sleep(500)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(out(), "normal-closing-chat.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                screenshot.recycle()
                record("chat-screen", mapOf("saved_and_displayed" to true, "synthetic_only" to true))
            } finally { f.cleanup() }
        }
    }

    @Test fun chineseBodyThoughtAndTwoMonologuesStaySeparateDespiteEnglishNativeReasoning() = runBlocking {
        Loopback().use { server ->
            val native = "Let me check the previous conversation before answering."
            val thought = "我记得他更正过，先翻原话确认一下。"
            val first = "你现在喜欢不加糖的红茶呀。"
            val second = "以前是绿茶，后来你更正过啦。"
            val monologue = "这回可记清楚了。"
            val secondMonologue = "下次给他泡红茶。"
            val body = "$first（内心：$monologue）<||>$second（内心：$secondMonologue）"
            val f = fixture(server,"之前喜欢绿茶，现在喜欢不加糖的红茶。","我现在喜欢什么茶？",rounds=1,thinking=true)
            try {
                assertTrue(f.ctx.usesCharacterThinking())
                server.respond = { i, payload ->
                    record("reply-presentation-request-$i",payload)
                    val system = payload["messages"].asJsonArray.first().asJsonObject["content"].asString
                    // 思考提示词不再放顶部 system，改为请求末尾注入。
                    assertFalse(system.contains("【思考方式】"))
                    assertFalse(system.contains("<think>"))
                    assertFalse(system.contains("先在开头用一对中文圆括号"))
                    val all = payload["messages"].asJsonArray.joinToString("\n") {
                        it.asJsonObject["content"]?.takeUnless { content -> content.isJsonNull }?.asString.orEmpty()
                    }
                    assertTrue(all.contains(CompanionPromptLanguage.bubbleRule))
                    // 2026-10-22 起默认不再教写独白：请求里不应再有独白写法，
                    // 正文里的（内心：…）由客户端渲染层自己识别，与提示词无关。
                    assertEquals(null, f.ctx.characterTurn?.promptStackItems
                        ?.firstOrNull { it.key == "output.inner_monologue" })
                    assertFalse(all.contains("内心独白格式"))
                    assertFalse(all.contains("放在<||>之前"))
                    // 思考提示词出现在请求末尾（最后一条用户消息 + 最后一条 system）。
                    assertTrue(all.contains("【思考方式】"))
                    assertFalse(all.contains("（内心：…）"))
                    if(i==0) tool("agent_history_search",mapOf("query" to "红茶"))
                    else answer("<think>$thought</think>$body") + ("reasoning_content" to native)
                }
                f.execute(f.modelCall(f.messages,false))
                val result=f.modelCall(f.messages,false)
                assertEquals(body,result["content"])
                assertEquals(thought,result["thinking_content"])
                assertEquals(native,result[NATIVE_CHARACTER_THINKING_CONTENT])
                f.callbacks.saveAssistantMessage(f.ctx,result + ("id" to "${f.id}-reply"))
                val saved=requireNotNull(db.messageDao().getById("${f.id}-reply"))
                assertEquals(body,saved.content)
                assertTrue(saved.reasoningContent.orEmpty().contains(thought))
                assertFalse(saved.reasoningContent.orEmpty().contains(native))
                assertFalse(saved.reasoningContent.orEmpty().contains(monologue))
                compose.setContent { NekobotTheme { com.nekobot.app.ui.screens.chat.ModernChatScreen(f.id,onBack={}) } }
                compose.waitUntil(15000) { compose.onAllNodesWithText(second,substring=true).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("companion_reasoning").fetchSemanticsNodes().isNotEmpty() }
                val label=compose.activity.getString(com.nekobot.app.R.string.markdown_inner_monologue)
                compose.onNodeWithText(monologue).assertDoesNotExist()
                compose.onAllNodesWithText(label)[0].assertIsDisplayed()
                compose.onAllNodesWithText(label)[1].assertIsDisplayed()
                compose.onNodeWithText(secondMonologue).assertDoesNotExist()
                screenshot("two-bubbles-collapsed.png")
                val firstBounds=compose.onNodeWithText(first,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                val inlineBounds=compose.onAllNodesWithText(label,useUnmergedTree=true)[0].fetchSemanticsNode().boundsInRoot
                val secondBounds=compose.onNodeWithText(second,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                record("reply-geometry",mapOf("first" to firstBounds.toString(),"inline" to inlineBounds.toString(),"second" to secondBounds.toString()))
                // 作者原版：独白在正文原位置显示，第一条的独白块落在第一条正文之后、第二条正文之前。
                assertTrue(firstBounds.bottom<=inlineBounds.top)
                assertTrue(inlineBounds.bottom<=secondBounds.top)
                compose.onAllNodesWithText(label)[0].performClick()
                compose.onNodeWithText(monologue).assertIsDisplayed()
                compose.onNodeWithText(secondMonologue).assertDoesNotExist()
                compose.onAllNodesWithText(label)[1].performClick()
                compose.onNodeWithText(secondMonologue).assertIsDisplayed()
                val thinkingLabel=compose.activity.getString(com.nekobot.app.R.string.companion_inner_thought)
                compose.onNodeWithTag("companion_reasoning").assertIsDisplayed()
                compose.onNodeWithText(thinkingLabel).performClick()
                compose.onNodeWithText(thought,substring=true).assertIsDisplayed()
                compose.onNodeWithText(native,substring=true).assertDoesNotExist()
                compose.onNodeWithText(monologue).assertIsDisplayed()
                screenshot("thought-and-monologue-expanded.png")
                compose.onAllNodesWithText(label)[0].performClick()
                compose.onNodeWithText(monologue).assertDoesNotExist()
                compose.onNodeWithText(secondMonologue).assertIsDisplayed()
                compose.onNodeWithText(thought,substring=true).assertIsDisplayed()
                record("reply-presentation",mapOf("real_pipeline_prompt" to true,"requests" to server.requests.size,
                    "native_protocol_separate" to true,"tagged_thought_extracted" to true,"two_body_bubbles" to true,
                    "monologue_inline_in_its_own_bubble" to true,"two_independent_monologues" to true,"external_model_calls" to 0))
            } finally { f.cleanup() }
        }
    }

    @Test fun standaloneMiddleMonologueKeepsItsOwnBubble() = runBlocking {
        Loopback().use { server ->
            val first="我们在星禾书店门口认识的。"
            val second="那枚银杏叶书签，我也记得。"
            val monologue="想起那把红伞了。"
            val body="$first<||>（内心：$monologue）<||>$second"
            val f=fixture(server,"我们在星禾书店门口认识。","我们在哪里认识的？",thinking=true)
            try {
                server.respond={ _,_ -> answer("<think>我先想想那次见面。</think>$body") + ("reasoning_content" to "I will recall the meeting.") }
                val result=f.modelCall(f.messages,false)
                f.callbacks.saveAssistantMessage(f.ctx,result + ("id" to "${f.id}-reply"))
                compose.setContent { NekobotTheme { com.nekobot.app.ui.screens.chat.ModernChatScreen(f.id,onBack={}) } }
                compose.waitUntil(15000) { compose.onAllNodesWithText(second,substring=true).fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("companion_reasoning").fetchSemanticsNodes().isNotEmpty() }
                val label=compose.activity.getString(com.nekobot.app.R.string.markdown_inner_monologue)
                screenshot("middle-monologue-collapsed.png")
                // 作者原版：中间那条纯独白留在自己的气泡里，不再挂到上一条正文下面。
                compose.onNodeWithText(first).assertIsDisplayed()
                compose.onNodeWithText(second).assertIsDisplayed()
                compose.onNodeWithText(monologue).assertDoesNotExist()
                compose.onAllNodesWithText(label)[0].assertIsDisplayed()
                compose.onAllNodesWithText(label)[0].performClick()
                compose.onNodeWithText(monologue).assertIsDisplayed()
                screenshot("middle-monologue-inline.png")
                record("middle-monologue",mapOf("separate_bubble" to true,
                    "raw_body_unchanged" to (db.messageDao().getById("${f.id}-reply")?.content==body)))
            } finally { f.cleanup() }
        }
    }

    @Test fun pseudoThoughtUsesTheSameChannelsForJsonAndToolFreeStreaming() = runBlocking {
        for (directStream in listOf(false, true)) Loopback().use { server ->
            val thought = "想起那次见面，我还是很开心。"
            val native = "I should check the context."
            val body = "在书店呀。（内心：那天真好。）<||>你还借了红伞给我。"
            // 实时回调会申请流式；服务端仍可能回 JSON，验证客户端的实际兼容路径。
            server.forceJson = !directStream
            val f = fixture(server, "我们在星禾书店认识。", "在哪里认识的？",
                thinking = true)
            try {
                server.respond = { _, _ -> answer("<think>$thought</think>$body") + ("reasoning_content" to native) }
                if (directStream) {
                    val call = requireNotNull(f.callbacks.buildModelCallStreaming(f.ctx, emptyList()))
                    val chunks = call(f.messages, false)
                    assertEquals(thought, chunks.mapNotNull { it["thinking_content"] }.joinToString(""))
                    assertEquals(body, chunks.mapNotNull { it["content"] }.joinToString(""))
                } else {
                    val result = f.modelCall(f.messages, false)
                    assertEquals(thought, result["thinking_content"])
                    assertEquals(body, result["content"])
                    assertEquals(native, result[NATIVE_CHARACTER_THINKING_CONTENT])
                }
                assertTrue(server.requests.single()["stream"].asBoolean)
                f.callbacks.saveAssistantMessage(f.ctx,mapOf("id" to "${f.id}-reply",
                    "content" to body,"thinking_content" to thought))
                val diagnostic=com.nekobot.app.data.local.diagnostics.ProblemDiagnosticCollector(db,compose.activity)
                    .collect(com.nekobot.app.data.local.diagnostics.DiagnosticScope.REPLY,f.id,"${f.id}-reply",true,"")
                val trace=JsonParser.parseString(diagnostic.files.getValue("完整请求与返回.json")).asJsonObject["events"].asJsonArray
                    .map { it.asJsonObject }
                assertEquals(server.requests.single(),trace.single { it["kind"].asString=="http_request" }["data"].asJsonObject["payload"])
                assertEquals(1,trace.count { it["kind"].asString=="http_response" })
                record(if (directStream) "pseudo-direct-stream" else "pseudo-json",
                    mapOf("chinese_body_thought" to true, "native_not_displayed" to true,
                        "monologue_body_preserved" to true, "external_model_calls" to 0))
            } finally { f.cleanup() }
        }
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(out(),name).outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        shot.recycle()
    }

    private fun out() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        InstrumentationRegistry.getArguments().getString("evidence_dir") ?: "recall-closing").apply { mkdirs() }
    private fun record(name: String, data: Any) { File(out(), "$name.json").writeText(gson.toJson(data)) }

    private inner class Loopback : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        var forceJson = false
        @Volatile var respond: (Int, JsonObject) -> Map<String, Any> = { _, _ -> answer("合成响应") }
        private val executor = Executors.newSingleThreadExecutor()
        private val keys = KeyStore.getInstance("PKCS12").apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open("companion-loopback.p12").use { load(it,"fixturepass".toCharArray()) }
        }
        private val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,"fixturepass".toCharArray()) }
        private val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        private val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }
        private val server = tls.serverSocketFactory.createServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val client = LocalAiClient(okhttp3.OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,tm.trustManagers.single() as X509TrustManager).build())
        private val worker = executor.submit {
            while(!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 15000
                    val input=socket.getInputStream(); val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) { val b=input.read(); check(b>=0); header.append(b.toChar()) }
                    val length=Regex("Content-Length: (\\d+)",RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                    val body=ByteArray(length); var offset=0
                    while(offset<length) { val n=input.read(body,offset,length-offset); check(n>0); offset+=n }
                    val payload=JsonParser.parseString(String(body,Charsets.UTF_8)).asJsonObject
                    val index=requests.size; requests += payload
                    val message=respond(index,payload)
                    val streaming=!forceJson && payload["stream"]?.asBoolean == true
                    val calls=message["tool_calls"] as? List<*>
                    val wire=if(streaming) buildString {
                        message["reasoning_content"]?.toString()?.chunked(3)?.forEach { chunk ->
                            append("data: "+gson.toJson(mapOf("choices" to listOf(mapOf("index" to 0,"delta" to mapOf("reasoning_content" to chunk)))))+"\n\n")
                        }
                        if(calls != null) {
                            @Suppress("UNCHECKED_CAST")
                            val deltas=calls.mapIndexed { i,c -> (c as Map<String,Any>) + ("index" to i) }
                            append("data: "+gson.toJson(mapOf("choices" to listOf(mapOf("index" to 0,"delta" to mapOf("tool_calls" to deltas)))))+"\n\n")
                        } else for(chunk in message["content"].toString().chunked(3))
                            append("data: "+gson.toJson(mapOf("choices" to listOf(mapOf("index" to 0,"delta" to mapOf("content" to chunk)))))+"\n\n")
                        append("data: "+gson.toJson(mapOf("choices" to listOf(mapOf("index" to 0,"delta" to emptyMap<String,Any>(),"finish_reason" to if(calls == null) "stop" else "tool_calls"))))+"\n\ndata: [DONE]\n\n")
                    } else gson.toJson(mapOf("choices" to listOf(mapOf("message" to message,"finish_reason" to if(calls == null) "stop" else "tool_calls"))))
                    val bytes=wire.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: ${if(streaming) "text/event-stream" else "application/json"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().write(bytes); socket.getOutputStream().flush()
                }
            } catch(e: Exception) { if(!server.isClosed) throw e }
        }
        override fun close() { server.close(); executor.shutdownNow() }
    }
}
