package app.lianxu.independent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ImageRequestBodyTest {
    private val config=ModelConfig("c","合成","custom","https://example.invalid/v1","合成模型",128000,2048)
    @Test fun outputLimitUsesOneFieldInBodyAndWrittenRequest(){
        fun verify(cfg:ModelConfig,expected:String){
            val request=TextChatRequest(listOf(WireMessage("user","hello")),outputLimit=731)
            val body=ImageWire.body(cfg,request,false)
            val bytes=ByteArrayOutputStream()
            ImageWire.write(body,request,bytes)
            for(json in listOf(body,JSONObject(bytes.toString("UTF-8")))){
                assertEquals(listOf(expected),listOf("max_tokens","max_completion_tokens").filter(json::has))
                assertEquals(731,json.getInt(expected))
            }
        }
        for(id in listOf("o1","o3","o4","o1-mini","o3-mini","o4-mini","gpt-5-mini","openai/gpt-5"," OPENAI/GPT-5.2 ")){
            verify(config.copy(modelId=id),"max_completion_tokens")
        }
        for(id in listOf("deepseek-chat","gpt-4o-mini","o10","o30","o4x")) verify(config.copy(modelId=id),"max_tokens")
        for(provider in listOf("openai","mimo")) verify(config.copy(providerId=provider),"max_completion_tokens")
    }
}
