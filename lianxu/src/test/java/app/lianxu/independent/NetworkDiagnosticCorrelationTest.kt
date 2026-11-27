package app.lianxu.independent

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/** Real HTTPS adapter with synthetic connection objects; no sockets, provider or user data. */
class NetworkDiagnosticCorrelationTest {
    private val config=ModelConfig("fake","合成","custom","https://example.invalid/v1","fake-model",16000,2048)
    private val key="sk-synthetic-diagnostic-secret-123456789"
    private val request=TextChatRequest(listOf(WireMessage("user","synthetic input")),256,timeoutMillis=90000)
    private class Fake(url:URL,val input:InputStream,val type:String="application/json",val badDisconnect:Boolean=false,val onDisconnect:(()->Unit)?=null):HttpsURLConnection(url){
        val workerClosed=CountDownLatch(1)
        val sent=ByteArrayOutputStream()
        override fun getCipherSuite()=""
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun connect(){}
        override fun disconnect(){onDisconnect?.invoke();if(Thread.currentThread().name=="lianxu-text-http")workerClosed.countDown();if(badDisconnect)throw IOException("private disconnect payload")}
        override fun usingProxy()=false
        override fun getOutputStream():OutputStream=sent
        override fun getResponseCode()=200
        override fun getContentType()=type
        override fun getInputStream()=input
    }
    @Test fun memoryNonStreamingReadsNdjsonLines()=verifySummaryWire(false,false)
    @Test fun memoryNonStreamingReadsWholeDocument()=verifySummaryWire(false,true)
    @Test fun memoryStreamingReadsNdjsonLines()=verifySummaryWire(true,false)

    private fun verifySummaryWire(stream:Boolean,document:Boolean)=runBlocking {
        val lines=listOf("""{"id":1}""","""{"id":2}""")
        val text=if(document) "{\n  \"id\": 1\n}" else lines.joinToString("\r\n")
        val field=if(stream)"delta" else "message"
        val choice=org.json.JSONObject().put(field,org.json.JSONObject().put("content",text)).put("finish_reason","stop")
        val json=org.json.JSONObject().put("choices",org.json.JSONArray().put(choice)).toString()
        val response=if(stream)"data: $json\n\ndata: [DONE]\n\n" else json
        val fake=Fake(URL(config.baseUrl),response.byteInputStream(),if(stream)"text/event-stream" else "application/json")
        val client=HttpsTextChatClient(HttpsConnectionFactory{fake})
        val credential=ModelCredential(config.copy(streamReplies=stream),key,"stamp")
        val records=mutableListOf<String>()
        val whole=readMemorySummary(client,credential,request,document,{}){records.add(it)}
        assertEquals(stream,org.json.JSONObject(fake.sent.toString("UTF-8")).getBoolean("stream"))
        if(document){
            assertTrue(records.isEmpty())
            assertEquals(1,org.json.JSONObject(whole).getInt("id"))
        }else{
            assertEquals(lines,records)
            assertEquals(listOf(1,2),records.map{org.json.JSONObject(it).getInt("id")})
        }
    }
}
