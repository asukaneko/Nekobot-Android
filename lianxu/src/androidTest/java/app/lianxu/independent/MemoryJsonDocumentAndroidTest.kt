package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Fictional data and a fake transport; the documented provider identity selects framing only. */
class MemoryJsonDocumentAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault
    private lateinit var scope:CoroutineScope
    private lateinit var database:String
    private val config=ModelConfig("json","Synthetic","custom","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",64000,2048)
    @Before fun setup()=runBlocking{
        database="json-document-${System.nanoTime()}.db";store=Store(context,database);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        vault=ModelVault(context,"json-document-${System.nanoTime()}");vault.save(config,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,config.id)
        scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val ledger=ChatLedger(store);val turn=ledger.begin("a","I made a blue cup.",config.id);assertTrue(ledger.finish(turn.id,turn.attempt,"Synthetic reply"))
    }
    @After fun close()=runBlocking{scope.coroutineContext.job.cancelAndJoin();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(database);Unit}
    private fun operation(id:String="one",target:String="N1")=JSONObject().put("id",id).put("op","ADD").put("target",target).put("category","EXPERIENCE_MEMORY").put("about","USER").put("text","我记得对方做了一只蓝色杯子。").put("core",false).put("date_expression",JSONObject.NULL).put("evidence",JSONArray().put("S1"))
    private fun document(data:JSONObject,operations:JSONArray=JSONArray().put(operation()))=JSONObject().put("batch_id",data.getString("batch_id")).put("character_id","a").put("operations",operations).put("operation_count",operations.length()).put("complete",true).put("end_marker",data.getString("batch_id"))
    @Test fun nonStreamingDocumentCommitsToDatabase()=verifyNonStreamingCommit(true)
    @Test fun nonStreamingNdjsonCommitsToDatabase()=verifyNonStreamingCommit(false)

    private fun verifyNonStreamingCommit(jsonDocument:Boolean)=runBlocking {
        val selected=if(jsonDocument) config.copy(streamReplies=false) else
            config.copy(baseUrl="https://example.invalid/v1",modelId="custom-summary",streamReplies=false)
        vault.save(selected,"synthetic-key-only")
        var calls=0
        val client=object:TextChatClient {
            override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult=
                error("Unexpected streaming summary")
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                calls++
                assertEquals(jsonDocument,request.jsonObject)
                val root=document(JSONObject(request.messages.last().content))
                if(jsonDocument) return TextChatResult.Success(root.toString(2))
                root.remove("operations")
                val row=JSONObject().put("type","operation").put("batch_id",root.getString("batch_id"))
                    .put("character_id","a").put("operation",operation())
                return TextChatResult.Success(row.toString()+"\r\n"+root.put("type","complete"))
            }
        }
        val result=MemoryEngine(scope,repo,ModelAccess(vault),client,{}).ensureRoom("a",forceOne=true)
        assertTrue(result.code,result.success)
        assertEquals(1,calls)
        assertEquals(1,repo.memories("a").size)
        assertTrue(repo.recentSources("a").isEmpty())
        assertEquals(2,store.messages().size)
    }
}
