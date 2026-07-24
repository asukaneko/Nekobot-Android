package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.AiModelRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LocalTtsChainTest {

    @Test
    fun openAiTtsUsesSpeechEndpointAndModelDefaults() = runBlocking {
        var capturedUrl = ""
        var capturedBody = ""
        val expected = byteArrayOf(1, 2, 3, 4)
        val client = client { request ->
            capturedUrl = request.url.toString()
            capturedBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            response(request, expected, "audio/mpeg")
        }
        val model = ttsModel(
            ttsProvider = "openai",
            baseUrl = "https://api.example.com/v1",
            model = "gpt-custom",
            ttsModel = "gpt-4o-mini-tts",
            ttsVoice = "alloy"
        )

        val bytes = LocalAiClient(client).synthesizeSpeech(model, "你好")

        assertArrayEquals(expected, bytes)
        assertEquals("https://api.example.com/v1/audio/speech", capturedUrl)
        val json = JsonParser.parseString(capturedBody).asJsonObject
        assertEquals("gpt-4o-mini-tts", json.get("model").asString)
        assertEquals("你好", json.get("input").asString)
        assertEquals("alloy", json.get("voice").asString)
    }

    @Test
    fun xiaomiTtsDecodesChatCompletionAudio() = runBlocking {
        val expected = "xiaomi-audio".toByteArray()
        var apiKeyHeader = ""
        var capturedBody = ""
        val client = client { request ->
            apiKeyHeader = request.header("api-key").orEmpty()
            capturedBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            val json = """
                {"choices":[{"message":{"audio":{"data":"${Base64.getEncoder().encodeToString(expected)}"}}}]}
            """.trimIndent()
            response(request, json.toByteArray(), "application/json")
        }
        val model = ttsModel(
            ttsProvider = "xiaomi",
            baseUrl = "https://api.xiaomimimo.com/v1",
            model = "mimo-v2.5",
            ttsModel = "mimo-v2.5-tts",
            ttsVoice = "冰糖",
            ttsUser = "请用欢快的语气"
        )

        val bytes = LocalAiClient(client).synthesizeSpeech(model, "你好")

        assertArrayEquals(expected, bytes)
        assertEquals("test-key", apiKeyHeader)
        val body = JsonParser.parseString(capturedBody).asJsonObject
        assertEquals("mimo-v2.5-tts", body.get("model").asString)
        assertEquals("冰糖", body.getAsJsonObject("audio").get("voice").asString)
        assertEquals(2, body.getAsJsonArray("messages").size())
    }

    @Test
    fun doubaoTtsCollectsChunkedBase64Audio() = runBlocking {
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        val raw = buildString {
            appendLine("""{"code":0,"data":"${Base64.getEncoder().encodeToString(first)}"}""")
            appendLine("""{"code":0,"data":"${Base64.getEncoder().encodeToString(second)}"}""")
            appendLine("""{"code":20000000}""")
        }
        var resourceId = ""
        val client = client { request ->
            resourceId = request.header("X-Api-Resource-Id").orEmpty()
            response(request, raw.toByteArray(), "application/json")
        }
        val model = ttsModel(
            ttsProvider = "doubao",
            baseUrl = "https://unused.example",
            model = "seed-tts",
            ttsVoice = "zh_female_shuangkuaisisi_moon_bigtts",
            ttsResourceId = "seed-tts-2.0"
        )

        val bytes = LocalAiClient(client).synthesizeSpeech(model, "你好")

        assertArrayEquals(first + second, bytes)
        assertEquals("seed-tts-2.0", resourceId)
    }

    @Test
    fun remoteRequestUsesOriginalRepositoryFieldNames() {
        val json = Gson().toJson(
            AiModelRequest(
                name = "TTS",
                protocol = "openai_compatible",
                purpose = "tts",
                ttsProvider = "xiaomi",
                ttsModel = "mimo-v2.5-tts",
                sttLanguage = "zh"
            )
        )

        assertTrue(json.contains("\"provider_type\":\"openai_compatible\""))
        assertTrue(json.contains("\"tts_provider\":\"xiaomi\""))
        assertTrue(json.contains("\"tts_model\":\"mimo-v2.5-tts\""))
        assertTrue(json.contains("\"stt_language\":\"zh\""))
        assertFalse(json.contains("\"protocol\""))
    }

    private fun client(handler: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun response(
        request: okhttp3.Request,
        body: ByteArray,
        contentType: String
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", contentType)
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()

    private fun ttsModel(
        ttsProvider: String,
        baseUrl: String,
        model: String,
        ttsModel: String = "",
        ttsVoice: String,
        ttsUser: String = "",
        ttsResourceId: String = ""
    ): LocalAiModelEntity = LocalAiModelEntity(
        id = "tts-id",
        name = "TTS",
        protocol = "openai_chat",
        provider = ttsProvider,
        apiKey = "test-key",
        baseUrl = baseUrl,
        model = model,
        purpose = "tts",
        ttsProvider = ttsProvider,
        ttsModel = ttsModel,
        ttsVoice = ttsVoice,
        ttsUser = ttsUser,
        ttsResourceId = ttsResourceId,
        createdAt = "2026-07-24"
    )
}
