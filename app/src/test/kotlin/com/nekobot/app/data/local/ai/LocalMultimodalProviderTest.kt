package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LocalMultimodalProviderTest {

    @Test
    fun qwenTtsReadsNestedAudioResponse() = runBlocking {
        val expected = "qwen-audio".toByteArray()
        val client = client { request ->
            assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", request.url.toString())
            response(request, """{"output":{"audio":{"data":"${Base64.getEncoder().encodeToString(expected)}"}}}""", "application/json")
        }
        val model = model(
            provider = "qwen",
            ttsProvider = "qwen",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ttsModel = "qwen3-tts-flash",
            ttsVoice = "Cherry"
        )

        assertArrayEquals(expected, LocalAiClient(client).synthesizeSpeech(model, "你好"))
    }

    @Test
    fun qwenSttUsesConfiguredSttModelAndCompatibleEndpoint() = runBlocking {
        var requestBody = ""
        val client = client { request ->
            requestBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            response(request, """{"choices":[{"message":{"content":"识别结果"}}]}""", "application/json")
        }
        val model = model(
            provider = "openai",
            sttProvider = "qwen",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            sttModel = "qwen3-asr-flash"
        )

        val result = LocalAiClient(client).transcribeSpeech(model, byteArrayOf(1, 2, 3), "sample.wav", "zh")

        assertEquals("识别结果", result.content)
        val json = JsonParser.parseString(requestBody).asJsonObject
        assertEquals("qwen3-asr-flash", json.get("model").asString)
        assertTrue(requestBody.contains("input_audio"))
    }

    @Test
    fun geminiImageReadsInlineDataResponse() = runBlocking {
        val expected = byteArrayOf(9, 8, 7)
        val client = client { request ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image:generateContent", request.url.toString())
            response(
                request,
                """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString(expected)}"}}]}}]}""",
                "application/json"
            )
        }

        val images = LocalAiClient(client).generateImage(
            baseUrl = "",
            apiKey = "key",
            modelName = "gemini-3.1-flash-image",
            prompt = "a cat",
            provider = "gemini"
        )

        assertEquals(1, images.size)
        assertArrayEquals(expected, images.single().bytes)
    }

    @Test
    fun miniMaxImageReadsImageBase64Array() = runBlocking {
        val expected = byteArrayOf(1, 4, 9)
        val client = client { request ->
            assertEquals("https://api.minimaxi.com/v1/image_generation", request.url.toString())
            response(
                request,
                """{"data":{"image_base64":["${Base64.getEncoder().encodeToString(expected)}"]}}""",
                "application/json"
            )
        }

        val images = LocalAiClient(client).generateImage(
            baseUrl = "https://api.minimaxi.com/v1",
            apiKey = "key",
            modelName = "image-01",
            prompt = "a cat",
            provider = "minimax"
        )

        assertArrayEquals(expected, images.single().bytes)
    }

    @Test
    fun qwenImage3UsesMultimodalEndpointAndReadsChoiceImage() = runBlocking {
        var requestBody = ""
        val client = client { request ->
            assertEquals(
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                request.url.toString()
            )
            requestBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            response(
                request,
                """{"output":{"choices":[{"message":{"content":[{"image":"https://example.com/image.png"}]}}]}}""",
                "application/json"
            )
        }

        val images = LocalAiClient(client).generateImage(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey = "key",
            modelName = "qwen-image-3.0",
            prompt = "a cat",
            provider = "qwen"
        )

        val json = JsonParser.parseString(requestBody).asJsonObject
        assertEquals("qwen-image-3.0", json.get("model").asString)
        assertEquals(
            "a cat",
            json.getAsJsonObject("input")
                .getAsJsonArray("messages")[0]
                .asJsonObject
                .getAsJsonArray("content")[0]
                .asJsonObject
                .get("text")
                .asString
        )
        assertTrue(json.getAsJsonObject("parameters").get("prompt_extend").asBoolean)
        assertEquals("https://example.com/image.png", images.single().url)
    }

    private fun model(
        provider: String,
        baseUrl: String,
        ttsProvider: String = "",
        sttProvider: String = "",
        ttsModel: String = "",
        ttsVoice: String = "default",
        sttModel: String = ""
    ) = LocalAiModelEntity(
        id = "multimodal-id",
        name = "Multimodal",
        protocol = "openai_chat",
        provider = provider,
        apiKey = "key",
        baseUrl = baseUrl,
        model = "fallback-model",
        purpose = "tts",
        ttsProvider = ttsProvider,
        ttsModel = ttsModel,
        ttsVoice = ttsVoice,
        sttProvider = sttProvider,
        sttModel = sttModel,
        createdAt = "2026-08-14"
    )

    private fun client(handler: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun response(request: okhttp3.Request, body: String, contentType: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", contentType)
            .body(body.toByteArray().toResponseBody(contentType.toMediaType()))
            .build()
}
