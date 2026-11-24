package app.lianxu.independent

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReasoningContinuationAndroidTest {
    @Test fun jsonAndEventStreamToolResponsesKeepNativeReasoningOnTheWire() = runBlocking {
        val config = ModelConfig("id", "model", "deepseek", "https://api.deepseek.com", "deepseek-reasoner")
        val message = """{"reasoning_content":"native part","tool_calls":[{"index":0,"id":"call","type":"function","function":{"name":"lookup","arguments":"{}"}}]}"""
        val request = TextChatRequest(listOf(WireMessage("user", "hello")),
            tools = listOf(mapOf("type" to "function", "function" to mapOf("name" to "lookup"))))
        for (stream in listOf(false, true)) {
            val response = if (stream)
                "data: " + """{"choices":[{"delta":$message,"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
            else """{"choices":[{"message":$message,"finish_reason":"tool_calls"}]}"""
            val client = HttpsTextChatClient(factory = HttpsConnectionFactory { url ->
                FixtureConnection(url, response, stream)
            })
            val reply = if (stream) client.streamEvents(config, "fixture-key", request) {}
                else client.complete(config, "fixture-key", request)
            assertTrue(reply.toString(), reply is TextChatResult.Success)
            reply as TextChatResult.Success
            val next = request.copy(messages = request.messages +
                WireMessage("assistant", reply.text, reply.toolCalls, continuation = reply.continuation) +
                WireMessage("tool", "ok", toolCallId = "call", name = "lookup"), jsonObject = true, tools = emptyList())
            val out = ByteArrayOutputStream()
            ImageWire.write(ImageWire.body(config, next, stream), next, out)
            val body = JSONObject(out.toString("UTF-8"))
            assertEquals("native part", body.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"))
            assertFalse(body.has("response_format"))
        }
    }

    private class FixtureConnection(url: URL, val response: String, val stream: Boolean) : HttpsURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_OK
        override fun getContentType() = if (stream) "text/event-stream" else "application/json"
        override fun getInputStream() = response.byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates() = emptyArray<java.security.cert.Certificate>()
    }
}
