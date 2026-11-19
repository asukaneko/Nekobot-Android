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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class LocalMultimodalProviderTest {

    @Test
    fun deepSeekSpeechPostsTextToToolsEndpoint() = runBlocking {
        val audio = "RIFF....WAVEfmt ".toByteArray()
        var capturedUrl = ""
        var capturedBody = ""
        var authorization = ""
        val client = client { request ->
            capturedUrl = request.url.toString()
            authorization = request.header("Authorization").orEmpty()
            capturedBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            response(request, String(audio, Charsets.US_ASCII), "audio/wav")
        }
        val model = model(provider = "deepseek", baseUrl = "https://api.deepseek.com/v1", ttsProvider = "deepseek")

        val bytes = LocalAiClient(client).synthesizeSpeech(model, "你好，我是 DeepSeek。", voice = "tide")

        assertEquals("https://api.deepseek.com/v1/tools/tts", capturedUrl)
        assertEquals("Bearer key", authorization)
        val json = JsonParser.parseString(capturedBody).asJsonObject
        assertEquals("你好，我是 DeepSeek。", json.get("text").asString)
        assertEquals("tide", json.get("voice_id").asString)
        assertEquals("wav", json.get("format").asString)
        assertTrue(json.get("filter_markdown").asBoolean)
        assertArrayEquals(audio, bytes)
    }

    @Test
    fun deepSeekSpeechWrapsRawPcmAsWav() = runBlocking {
        val client = client { request -> response(request, "pcm", "audio/pcm") }
        val model = model(provider = "deepseek", baseUrl = "https://api.deepseek.com", ttsProvider = "deepseek")

        val bytes = LocalAiClient(client).synthesizeSpeech(model, "你好")

        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(24_000, ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun deepSeekSttUploadsFileFieldToToolsEndpoint() = runBlocking {
        var capturedUrl = ""
        var capturedBody = ""
        val client = client { request ->
            capturedUrl = request.url.toString()
            capturedBody = request.body!!.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }
            response(request, """{"request_id":"abc","text":"上海今天天气怎么样？","duration_ms":3200}""", "application/json")
        }
        val model = model(provider = "deepseek", baseUrl = "https://api.deepseek.com/v1", sttProvider = "deepseek")

        val result = LocalAiClient(client).transcribeSpeech(model, silentWav(), "record.m4a", "zh")

        assertEquals("上海今天天气怎么样？", result.content)
        assertEquals("https://api.deepseek.com/v1/tools/asr", capturedUrl)
        assertTrue(capturedBody.contains("name=\"file\""))
        assertTrue(capturedBody.contains("filename=\"audio.wav\""))
        assertTrue(!capturedBody.contains("name=\"model\""))
        assertTrue(!capturedBody.contains("name=\"language\""))
    }

    @Test
    fun deepSeekSttRejectsNonAudioPayloads() = runBlocking {
        var calls = 0
        val client = client { request -> calls++; response(request, """{"text":"x"}""", "application/json") }
        val model = model(provider = "deepseek", baseUrl = "https://api.deepseek.com/v1", sttProvider = "deepseek")
        try {
            LocalAiClient(client).transcribeSpeech(model, byteArrayOf(1, 2, 3), "record.m4a", "zh")
            org.junit.Assert.fail("Unsupported audio must fail before calling the service")
        } catch (_: Exception) {
        }
        assertEquals(0, calls)
    }

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

    @Test
    fun geminiImageHonorsMultipleImageCount() = runBlocking {
        var calls = 0
        val client = client { request ->
            calls++
            response(request, """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AQID"}}]}}]}""", "application/json")
        }
        val images = LocalAiClient(client).generateImage("", "key", "gemini-image", "a cat", n = 3, provider = "gemini")
        assertEquals(3, calls)
        assertEquals(3, images.size)
    }

    @Test
    fun unsupportedImageReferencesAreNotSilentlyDropped() = runBlocking {
        var calls = 0
        val client = client { request -> calls++; response(request, "{}", "application/json") }
        try {
            LocalAiClient(client).generateImage("https://example.com", "key", "image-01", "a cat", provider = "minimax",
                referenceImage = ImageGenerationReference(byteArrayOf(1, 2, 3)))
            org.junit.Assert.fail("Unsupported references must be reported")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    /** 16kHz 单声道 16bit 静音 WAV：DeepSeek ASR 只接受该格式，避免触发解码器。 */
    private fun silentWav(frames: Int = 1_600): ByteArray {
        val pcm = ByteArray(frames * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcm.size)
        }.array()
        return header + pcm
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
