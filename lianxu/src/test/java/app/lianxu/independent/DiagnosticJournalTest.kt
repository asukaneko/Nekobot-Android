package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticJournalTest {
    @Test fun tokenCountsAndOrdinaryKeysSurviveCredentialRedaction() {
        val r = DiagnosticRedactor()
        for (key in listOf("max_tokens","prompt_tokens","completion_tokens","total_tokens","context_tokens","token_limit","monkey")) {
            assertEquals("$key: 4096", r.text("$key: 4096"))
            val data=JsonObject().apply { addProperty(key,4096) }
            assertEquals(data,r.clean(data))
        }
        assertFalse(r.text("""error {"mime_type":"image/png","data":"fixture-media"}""").contains("fixture-media"))
        for (key in listOf("api_key","privateKey","access-token","refreshToken","auth_token","bearerToken","credential","openai_api_key","secretKey","token","key")) {
            assertFalse(r.text("$key:leakme").contains("leakme"))
            assertFalse(r.clean(JsonObject().apply { addProperty(key,"leakme") }).toString().contains("leakme"))
        }
        assertFalse(r.text("failure sk-xxx").contains("sk-xxx"))
    }

    private fun directory()=Files.createTempDirectory("diagnostic-test-").toFile()
    private fun rows(bytes:ByteArray)=bytes.toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.map{JsonParser.parseString(it).asJsonObject}.toList()
    private fun cleanup(dir:File){require(dir.name.startsWith("diagnostic-test-"));dir.deleteRecursively()}
    @Test fun writesOnlyRedactedSecretsHeadersUrlsAndErrorStacks(){
        val dir=directory();val journal=DiagnosticJournal(dir,"test")
        try{
            val secret="fake-private-credential-123";journal.redactor.register(secret)
            val error=IllegalStateException("server echoed $secret\nAuthorization: Bearer fake-header-value\nCookie: session=fake-cookie-value\nhttps://user:fake-user-password@example.invalid/api?api_key=fake-query#fake-fragment")
            journal.record("failure","trace-a",mapOf("key" to "unregistered-value","apiKey" to "unregistered-api","response" to error.stackTraceToString(),"nested" to mapOf("password" to "fake-password")))
            assertTrue(journal.flush())
            val disk=dir.listFiles().orEmpty().joinToString{it.readText()}
            listOf(secret,"unregistered-value","unregistered-api","fake-header-value","fake-cookie-value","fake-user-password","fake-query","fake-fragment","fake-password").forEach{assertFalse(it,disk.contains(it))}
            assertTrue(disk.contains("已脱敏"));assertTrue(disk.contains("IllegalStateException"))
        }finally{journal.close();cleanup(dir)}
    }
    @Test fun inlineJsonAndProviderTokensAreRedactedWithoutRegistration(){
        val r=DiagnosticRedactor()
        val value=r.text("""{"api_key":"unregistered-inline","token":"unregistered-token","note":"Bearer fake-bearer-value"} sk-syntheticABCDEFGHIJKLMNOPQRSTUVWXYZ""")
        listOf("unregistered-inline","unregistered-token","fake-bearer-value","sk-syntheticABCDEFGHIJKLMNOPQRSTUVWXYZ").forEach{assertFalse(value.contains(it))}
    }
    @Test fun knownShortAndUrlEncodedSecretsNeverReachDisk(){
        val r=DiagnosticRedactor();r.register("x+y/abc");r.register("xyz")
        val clean=r.text("x+y/abc x%2By%2Fabc xyz")
        assertFalse(clean.contains("x+y/abc"));assertFalse(clean.contains("x%2By%2Fabc"));assertFalse(clean.contains("xyz"))
    }
    @Test fun detailedExportContainsPayloadButSimpleExportOnlyMetadata(){
        val dir=directory();val j=DiagnosticJournal(dir,"15")
        try{j.record("prompt.assembled","trace-a",mapOf("messages" to "合成聊天正文"));val detailed=j.export(true).toString(Charsets.UTF_8);val simple=j.export(false).toString(Charsets.UTF_8)
            assertTrue(detailed.contains("合成聊天正文"));assertFalse(simple.contains("合成聊天正文"));assertTrue(simple.contains("prompt.assembled"));assertTrue(simple.contains("trace-a"))
        }finally{j.close();cleanup(dir)}
    }
    @Test fun rotationAndAgeRetentionAreBoundedAndUnrelatedFilesSurvive(){
        val dir=directory();File(dir,"events-3.jsonl").writeText("old");File(dir,"events-3.jsonl").setLastModified(System.currentTimeMillis()-8L*24*60*60*1000);File(dir,"unrelated.txt").writeText("keep")
        val j=DiagnosticJournal(dir,"test",segmentBytes=2048,segments=3)
        try{repeat(100){i->j.record("sample","trace-$i",mapOf("body" to "value-$i".repeat(40)));if(i%5==0)assertTrue(j.flush())};assertTrue(j.flush())
            val logs=dir.listFiles().orEmpty().filter{it.name.endsWith(".jsonl")};assertTrue(logs.size<=3);assertTrue(logs.sumOf{it.length()}<=6144);assertTrue(File(dir,"unrelated.txt").exists());assertFalse(File(dir,"events-3.jsonl").exists())
            val export=j.export(true).toString(Charsets.UTF_8);assertTrue(export.contains("trace-99"));assertTrue(export.contains("truncated"))
        }finally{j.close();cleanup(dir)}
    }
    @Test fun oldFirstEventExpiresEvenWhenTheSegmentWasRecentlyTouched() {
        val dir=directory()
        val old=System.currentTimeMillis()-8L*24*60*60*1000
        File(dir,"events-0.jsonl").writeText("""{"event":"expired-marker","at":$old}"""+"\n")
        val journal=DiagnosticJournal(dir,"bounded")
        try {
            journal.record("fresh-marker")
            val result=journal.export(true).decodeToString()
            assertTrue(result.contains("fresh-marker"));assertFalse(result.contains("expired-marker"))
            assertFalse(File(dir,"events-0.jsonl").readText().contains("expired-marker"))
        }finally{journal.close();cleanup(dir)}
    }

    @Test fun oversizeDropIsExplicitAndDoesNotGrowDisk(){
        val dir=directory();val j=DiagnosticJournal(dir,"test",queueCapacity=1)
        try{j.record("oversize","trace-a",mapOf("body" to "x".repeat(1024*1024+10)));val result=rows(j.export(true));assertEquals(1L,result.first().get("dropped_events").asLong);assertTrue(result.first().get("flush_complete").asBoolean);assertFalse(result.any{it.get("event")?.asString=="oversize"})}
        finally{j.close();cleanup(dir)}
    }
    @Test fun syntheticCredentialsAreRemovedFromDiskAndBothExports() {
        val dir=directory()
        val journal=DiagnosticJournal(dir,"fixture")
        try {
            val key="fixture-k+/%"
            journal.redactor.register(key)
            val payload=JsonObject().apply {
                addProperty("authorization","marker-auth")
                addProperty("private_key","marker-private")
                addProperty("chat","普通聊天内容")
            }
            val nested=JsonObject().apply { addProperty("encoded",payload.toString()) }
            journal.record("sample-$key","trace-$key",mapOf(
                key to "普通回复", "tool" to nested.toString(), "stack" to IllegalStateException("请求失败",
                    IllegalArgumentException("HTTPS://name:marker-pass@example.invalid/x?t=marker-query")).stackTraceToString(),
                "pem" to "-----BEGIN RSA PRIVATE KEY-----\nmarker-pem\n-----END RSA PRIVATE KEY-----",
                "body" to "data:video/mp4;base64,marker-video", "bytes" to ByteArray(180000){42},
                "cookies" to listOf("marker-cookie"),
                "encoded_form" to java.net.URLEncoder.encode(payload.toString(),"UTF-8"),
                "encoded_blob" to java.util.Base64.getEncoder().encodeToString(payload.toString().toByteArray()),
                "audio" to mapOf("format" to "wav","data" to "marker-sound")
            ))
            assertTrue(journal.flush())
            val disk=dir.listFiles().orEmpty().joinToString { it.readText() }
            val full=journal.export(true).decodeToString()
            listOf(disk,full,journal.export(false).decodeToString()).forEach { output ->
                listOf(key,"marker-auth","marker-private","marker-pass","marker-query","marker-pem","marker-video","marker-cookie","marker-sound",java.util.Base64.getEncoder().encodeToString(payload.toString().toByteArray()))
                    .forEach { assertFalse(it,output.contains(it)) }
            }
            listOf(disk,full).forEach { assertTrue(it.contains("普通聊天内容"));assertTrue(it.contains("IllegalArgumentException"));assertTrue(it.contains("180000字节")) }
            File(dir,"events-1.jsonl").writeText("""{"event":"legacy","data":{"token":"marker-legacy","body":"普通资料"}}""")
            assertFalse(journal.export(true).decodeToString().contains("marker-legacy"))
        } finally { journal.close(); cleanup(dir) }
    }

    @Test fun aRequestCannotResumeCollectionAfterToggle() = runBlocking {
        val dir=directory();val journal=DiagnosticJournal(dir,"fixture")
        Diagnostics.installForTest(journal)
        try {
            Diagnostics.detailed=true
            withContext(Diagnostics.networkContext("synthetic")) {
                val state=requireNotNull(Diagnostics.requestState())
                state.phase("first")
                val prepared=Diagnostics.raw("marker-stale-prepared")
                val preparedTool=Diagnostics.rawJson(mapOf("tool" to "marker-stale-json"))
                val out=state.observe(java.io.ByteArrayOutputStream())
                out.write(ByteArray(13))
                Diagnostics.detailed=false
                out.write(ByteArray(19))
                Diagnostics.detailed=true
                state.phase("marker-stale-phase")
                Diagnostics.event("sample",mapOf("body" to Diagnostics.raw("marker-stale-body"),
                    "old" to prepared, "old_tool" to preparedTool))
            }
            val full=journal.export(true).decodeToString()
            assertFalse(full.contains("marker-stale"))
            assertTrue(full.contains("\"client_write_completed_bytes\":13"))
        } finally { Diagnostics.installForTest(null);journal.close();cleanup(dir) }
    }

    @Test fun measuresBoundedLogWorkAndAccountsForEveryEvent() {
        val dir=directory();val j=DiagnosticJournal(dir,"bench",segmentBytes=16384,segments=3)
        try {
            val start=System.nanoTime()
            repeat(128) { j.record("timing","t",mapOf("reply" to "普通合成回复".repeat(1900))) }
            val enqueueMs=(System.nanoTime()-start)/1_000_000
            val output=rows(j.export(true))
            val bytes=dir.listFiles().orEmpty().sumOf { it.length() }
            val header=output.first()
            assertEquals(128L,header["session_truncated_events"].asLong+header["dropped_events"].asLong)
            assertTrue(header["flush_complete"].asBoolean)
            assertTrue(bytes<=49152)
            println("synthetic_events=128 enqueue_ms=$enqueueMs disk_bytes=$bytes rotations=${header["session_rotations"]}")
        }finally{j.close();cleanup(dir)}
    }

    @Test fun hiddenThoughtFieldsAndTagsAreOmitted(){
        val r=DiagnosticRedactor();val value=JsonObject().apply{addProperty("reasoning_content","private-thought");addProperty("response","visible <think>hidden-analysis</think> final")}
        val clean=r.clean(value).toString();assertFalse(clean.contains("private-thought"));assertFalse(clean.contains("hidden-analysis"));assertTrue(clean.contains("visible"));assertTrue(clean.contains("final"))
    }
    @Test fun coroutineTraceAndStreamAggregationStayLinked()=runBlocking{
        val dir=directory();val j=DiagnosticJournal(dir,"test");Diagnostics.installForTest(j)
        try{
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("unused")
                override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult{repeat(1000){onChunk("合成片段")};return TextChatResult.Failure(ChatFailure.TRUNCATED)}
            })
            withContext(Diagnostics.context("chat","fixed-trace")){Diagnostics.span("chat.operation"){
                withContext(Dispatchers.Default){client.stream(ModelConfig("c","合成","custom","https://example.invalid/v1","m"),"fake-stream-credential",TextChatRequest(listOf(WireMessage("user","合成提示")))){}}
            }}
            val events=rows(j.export(true)).drop(1);assertEquals(1,events.count{it.get("event").asString=="network.request"});assertEquals(1,events.count{it.get("event").asString=="network.response"});assertTrue(events.all{it.get("trace").asString=="fixed-trace"})
            val response=events.first{it.get("event").asString=="network.response"}.getAsJsonObject("data");assertEquals(1000,response.get("chunks").asInt);assertEquals("TRUNCATED",response.get("reason").asString)
            assertTrue(events.all{it.getAsJsonObject("data").get("span").asString.isNotEmpty()})
        }finally{Diagnostics.installForTest(null);j.close();cleanup(dir)}
    }
    @Test fun cancelledRequestAndThrownSecretErrorsAreRecordedSafely()=runBlocking{
        val dir=directory();val j=DiagnosticJournal(dir,"test");Diagnostics.installForTest(j)
        try{
            val entered=CompletableDeferred<Unit>();val client=LoggedTextChatClient(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{entered.complete(Unit);awaitCancellation()}})
            val config=ModelConfig("c","合成","custom","https://example.invalid/v1","m");val req=TextChatRequest(listOf(WireMessage("user","合成")))
            val job=launch(Diagnostics.context("memory","cancel-trace")){client.complete(config,"fake-cancel-credential",req)};entered.await();job.cancelAndJoin()
            val broken=LoggedTextChatClient(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{throw IllegalStateException("server echoed $key")}})
            assertTrue(runCatching{broken.complete(config,"fake-error-credential",req)}.isFailure)
            val text=j.export(true).toString(Charsets.UTF_8);assertTrue(text.contains("network.cancelled"));assertTrue(text.contains("network.failed"));assertFalse(text.contains("fake-cancel-credential"));assertFalse(text.contains("fake-error-credential"))
        }finally{Diagnostics.installForTest(null);j.close();cleanup(dir)}
    }
    @Test fun streamLimitAndFailedPartialChunksCannotExposeCredentialPrefixes()=runBlocking{
        val dir=directory();val j=DiagnosticJournal(dir,"test");Diagnostics.installForTest(j)
        try{
            val key="custom-boundary-credential";val config=ModelConfig("c","合成","custom","https://example.invalid/v1","m");val req=TextChatRequest(listOf(WireMessage("user","合成")))
            for(fail in listOf(false,true)){
                val client=LoggedTextChatClient(object:TextChatClient{
                    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("unused")
                    override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult{
                        if(!fail)onChunk("x".repeat(32760));onChunk(key.take(10));if(!fail)onChunk(key.drop(10))
                        return if(fail)TextChatResult.Failure(ChatFailure.TIMEOUT) else TextChatResult.Success("stream-complete")
                    }
                })
                client.stream(config,key,req){}
            }
            val text=j.export(true).toString(Charsets.UTF_8);assertFalse(text.contains(key));assertFalse(text.contains(key.take(10)));assertTrue(text.contains("stream_text_omitted"))
        }finally{Diagnostics.installForTest(null);j.close();cleanup(dir)}
    }
    @Test fun independentRequestCreatesOneTraceForStartAndResponse()=runBlocking{
        val dir=directory();val j=DiagnosticJournal(dir,"test");Diagnostics.installForTest(j)
        try{
            val client=LoggedTextChatClient(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("合成回复")})
            client.complete(ModelConfig("c","合成","custom","https://example.invalid/v1","m"),"fake-independent-credential",TextChatRequest(listOf(WireMessage("user","合成"))))
            val events=rows(j.export(true)).drop(1);assertEquals(2,events.size);assertEquals(1,events.map{it.get("trace").asString}.distinct().size)
        }finally{Diagnostics.installForTest(null);j.close();cleanup(dir)}
    }
    @Test fun jsonEscapedAndHtmlEscapedSecretVariantsAreRedacted(){
        val dir=directory();val j=DiagnosticJournal(dir,"test")
        try{
            val key="quote\"slash\\equals=synthetic";j.redactor.register(key)
            val escaped=com.google.gson.JsonPrimitive(key).toString().removeSurrounding("\"")
            val html=com.google.gson.Gson().toJson(key).removeSurrounding("\"")
            j.record("response","trace-a",mapOf("body" to "$escaped $html"))
            val text=j.export(true).toString(Charsets.UTF_8);assertFalse(text.contains("equals=synthetic"));assertFalse(text.contains("equals\\\\u003dsynthetic"))
        }finally{j.close();cleanup(dir)}
    }
    @Test fun currentRequestSecretIsRedactedAfterRegistryEviction()=runBlocking{
        val dir=directory();val j=DiagnosticJournal(dir,"test");Diagnostics.installForTest(j)
        try{
            val key="old-active-credential-value";val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{repeat(600){Diagnostics.registerSecret("different-fake-credential-$it")};return TextChatResult.Success("server echoed $key")}
            })
            client.complete(ModelConfig("c","合成","custom","https://example.invalid/v1","m"),key,TextChatRequest(listOf(WireMessage("user","合成"))))
            assertFalse(j.export(true).toString(Charsets.UTF_8).contains(key))
        }finally{Diagnostics.installForTest(null);j.close();cleanup(dir)}
    }
}
