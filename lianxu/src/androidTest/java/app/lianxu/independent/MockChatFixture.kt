package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import kotlinx.coroutines.runBlocking

import org.json.JSONArray
import org.json.JSONObject

/** Only for fixtures testing the final reply: exercise C1 with valid synthetic wire payloads.
 * Tests of extraction/appraisal itself keep their own clients and failure injection. */
internal fun syntheticCompanionStage(request:TextChatRequest):TextChatResult? {
    val system=request.messages.filter{it.role=="system"}.joinToString{it.content}
    if("[companion.interpret.v2]" in system)return TextChatResult.Success(JSONObject()
        .put("quote",request.latestUserText()).put("memory","")
        .put("emotion","unknown").put("valence",0).put("relationship","none")
        .put("control","none").put("targets",JSONArray()).put("reconsider",JSONObject.NULL)
        .put("claimKind","interaction").put("controlIntent","none").put("requestQuote","").toString())
    if("[companion.appraise.v1]" in system)return TextChatResult.Success(RoleModelPayload.json(
        JSONObject(request.messages.last{it.role=="user"}.content).getJSONObject("current_source").getLong("turnId")))
    return null
}

/** The user's own words in the latest user message; thinking language is a separate system message. */
internal fun TextChatRequest.latestUserText()=messages.last{it.role=="user"}.content

/** Protocol C: true when this request already carries a successful record (raw tool result or the compact note). */
internal fun TextChatRequest.recordTaken()=messages.any{(it.role=="tool"&&it.name==CompanionRecord.NAME&&runCatching{JSONObject(it.content).optBoolean("success")}.getOrDefault(false))||it.content.startsWith(CompanionRecord.receiptNote)}

/** The current source turn: the record tool guide names it as 「当前来源 N」 (older contracts ended with a current_source JSON line). */
internal fun TextChatRequest.currentSourceTurn():Long {
    Regex("当前来源[ =：]*(\\d+)").find(tools.toString())?.let{return it.groupValues[1].toLong()}
    messages.firstOrNull{it.content.startsWith("[这一轮的笔记")}?.let{note->
        Regex("这条消息的编号是 (\\d+)，版本").find(note.content)?.let{return it.groupValues[1].toLong()}
    }
    val contract=requireNotNull(messages.firstOrNull{"[companion.reply.v1]" in it.content}){"no reply contract"}
    return JSONObject(contract.content.substringAfterLast('\n')).getJSONObject("current_source").getLong("turnId")
}

/** A well-behaved model fills 关系输入 whenever the record tool asks for it; ordinary chat is 普通信息 with nothing changed. */
internal fun TextChatRequest.withRelationInput(payload:JSONObject,source:Long):JSONObject {
    if("关系输入" in tools.toString()&&!payload.has("关系输入"))payload.put("关系输入",JSONObject(RelationInputContract.example(source)))
    return payload
}

/** Final-reply fixtures only emulate a record round when the request actually offers that tool. */
internal fun syntheticFinalReply(request:TextChatRequest,result:TextChatResult):TextChatResult {
    if(request.messages.none{"[companion.reply.v1]" in it.content})return result
    if(request.tools.none{toolNameOf(it)==CompanionRecord.NAME})return result
    if(result !is TextChatResult.Success||result.toolCalls.isNotEmpty())return result
    // The record receipt was already returned; this round is the visible plain-text answer.
    if(request.recordTaken())return result
    val source=request.currentSourceTurn()
    val payload=request.withRelationInput(JSONObject(RoleModelPayload.json(source)),source)
    // The experience guide now travels with the record tool, not the reply prompt.
    if("[companion.joint_experience.v1]" in request.tools.toString()){
        payload.put("experience",JSONObject().put("quote",request.latestUserText()).put("memory","")
            .put("emotion","unknown").put("valence",0).put("relationship","none").put("control","none").put("targets",JSONArray())
            .put("reconsider",JSONObject.NULL).put("claimKind","interaction").put("controlIntent","none").put("requestQuote",""))
    }
    return TextChatResult.Success("",listOf(ChatToolCall("synthetic-record",CompanionRecord.NAME,payload.toString())))
}

/** Old seeded roles exist only in explicit synthetic fixtures, never in a fresh production database. */
internal fun seedLegacyTestRoles(context:android.content.Context,database:String)=runBlocking {
    val store=Store(context,database)
    try {
        store.add(listOf(Friend("seed-lin","林间","合成旧角色甲"),Friend("seed-an","时安","合成旧角色乙")),true)
    }finally{store.close()}
}

/** Instrumentation-only data/client injection; no credentials or fake reply in the production default. */
class MockChatFixture(private val clientFactory:(()->TextChatClient)?=null,private val hostModels:HostModelBridge=HostModelBridge { emptyList() }):ExternalResource(){
    private val id=System.nanoTime().toString()
    val namespace="mock-chat-$id"
    val database="mock-chat-$id.db"
    private lateinit var previousDatabase:String
    private lateinit var previousNamespace:String
    private lateinit var previousClient:()->TextChatClient
    override fun before(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        previousDatabase=AppServices.databaseName;previousNamespace=AppServices.vaultNamespace;previousClient=AppServices.createTextClient
        AppServices.databaseName=database;AppServices.vaultNamespace=namespace
        LianxuModule.initialize(context.applicationContext as android.app.Application,
            hostModels, HostRoleBridge { null },
            { object : HostPrivacyGate {
                @androidx.compose.runtime.Composable override fun Content(content: @androidx.compose.runtime.Composable () -> Unit) = content()
                override fun returningToHost() {}
            } }, {})
        seedLegacyTestRoles(context,database)
        runBlocking { val store=Store(context,database); try {
            val code=InstalledBuild.read(context).versionCode
            if(LocalReleaseNotes.forVersion(code)!=null)LocalUpdateRepository(store).acknowledge(code)
        } finally { store.close() } }
        ModelVault(context,namespace).apply{
            save(ModelConfig("fixture","合成测试服务","custom","https://example.invalid/v1","synthetic-model"),"synthetic-key-only")
            setPurpose(ModelPurpose.CHAT,"fixture")
        }
        AppServices.createTextClient=clientFactory ?: { object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest): TextChatResult =
                error("Unexpected model request in UI fixture")
        } }
    }
    override fun after(){
        AppServices.createTextClient=previousClient;AppServices.databaseName=previousDatabase;AppServices.vaultNamespace=previousNamespace
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ModelVault(context,namespace).let{v->v.list().forEach{v.delete(it.id)}}
        LianxuModule.roles.store.close()
        context.deleteDatabase(database)
    }
}
