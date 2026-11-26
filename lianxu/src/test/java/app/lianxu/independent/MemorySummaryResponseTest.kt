package app.lianxu.independent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemorySummaryResponseTest {
    private val config=ModelConfig("test","test","custom","https://example.invalid","m",streamReplies=false)
    private val request=TextChatRequest(listOf(WireMessage("user","summary")))

    private fun rejected(expected:String, result:TextChatResult, voidBeforeReturn:Boolean=false)=runBlocking {
        val attempts=MemoryAttempts()
        val attempt=attempts.begin("role","batch",1,emptyMap(),0)
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                if(voidBeforeReturn) attempts.void(attempt)
                return result
            }
            override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult=
                error("Unexpected stream")
        }
        try{
            readMemorySummary(client,ModelCredential(config,"test-key","stamp"),request,false,
                {rejectUnless(attempts.live(attempt),"LATE_DISCARDED")}){error("Rejected data reached parser")}
            fail("Expected rejection")
        }catch(e:ProcessingRejected){assertEquals(expected,e.code)}
    }
    @Test fun failureKeepsTransportReason()=rejected("MODEL_NETWORK",TextChatResult.Failure(ChatFailure.NETWORK))
    @Test fun oversizedResponseNeverReachesParser()=rejected("FORMAT",TextChatResult.Success("x".repeat(256*1024+1)))
    @Test fun lateSuccessNeverReachesParser()=rejected("LATE_DISCARDED",TextChatResult.Success("record\n"),true)
    @Test fun lateFailureIsDiscardedBeforeErrorHandling()=rejected("LATE_DISCARDED",TextChatResult.Failure(ChatFailure.NETWORK),true)
}
