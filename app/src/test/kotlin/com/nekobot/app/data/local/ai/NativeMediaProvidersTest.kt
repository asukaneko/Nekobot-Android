package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NativeMediaProvidersTest {
    @Test fun miniMaxDecodesHexAudioAndSendsNativeSampleRate() = runBlocking<Unit> {
        val ai = client { request ->
            val payload = json(request)
            assertEquals("hex", payload.get("output_format").asString)
            assertEquals(32000, payload.getAsJsonObject("audio_setting").get("sample_rate").asInt)
            assertEquals(0, payload.getAsJsonObject("voice_setting").get("pitch").asInt)
            response(request, """{"base_resp":{"status_code":0},"data":{"audio":"494433ff"}}""")
        }
        assertArrayEquals(byteArrayOf(73, 68, 51, -1), ai.synthesizeSpeech(model("minimax", "speech-2.8-hd").copy(baseUrl = "https://api.minimaxi.com/v1"), "你好"))
    }

    @Test fun elevenLabsSpeechUsesNativeAuthVoiceAndModel() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("https://api.elevenlabs.io/v1/text-to-speech/custom-voice?output_format=mp3_44100_128", request.url.toString())
            assertEquals("key", request.header("xi-api-key"))
            val body = json(request)
            assertEquals("eleven_multilingual_v2", body.get("model_id").asString)
            assertEquals("你好", body.get("text").asString)
            response(request, "audio", "audio/mpeg")
        }
        assertArrayEquals("audio".toByteArray(), ai.synthesizeSpeech(model("elevenlabs", "eleven_multilingual_v2"), "你好", voice = "custom-voice"))
    }

    @Test fun elevenLabsPcmIsWrappedAsWav() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("pcm_24000", request.url.queryParameter("output_format"))
            response(request, "pcm", "audio/pcm")
        }
        val result = ai.synthesizeSpeech(model("elevenlabs", "eleven_multilingual_v2").copy(ttsFormat = "wav"), "hi")
        assertEquals("RIFF", result.take(4).toByteArray().toString(Charsets.US_ASCII))
        assertEquals(47, result.size)
    }

    @Test fun elevenLabsTranscriptionUploadsFileAndLanguageCode() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("https://api.elevenlabs.io/v1/speech-to-text", request.url.toString())
            val body = body(request)
            assertTrue(body.contains("name=\"model_id\"")); assertTrue(body.contains("scribe_v2"))
            assertTrue(body.contains("name=\"language_code\"")); assertTrue(body.contains("zh"))
            assertTrue(body.contains("filename=\"record.wav\""))
            response(request, """{"text":"你好"}""")
        }
        assertEquals("你好", ai.transcribeSpeech(model("elevenlabs", "scribe_v2"), byteArrayOf(1, 2), "record.wav", "zh").content)
    }

    @Test fun deepgramParsesNestedTranscriptAndSendsRawAudio() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("/v1/listen", request.url.encodedPath)
            assertEquals("nova-3", request.url.queryParameter("model"))
            assertEquals("true", request.url.queryParameter("detect_language"))
            assertEquals("Token key", request.header("Authorization"))
            assertEquals("audio/wav", request.body!!.contentType().toString())
            assertEquals("audio", body(request))
            response(request, """{"results":{"channels":[{"alternatives":[{"transcript":"hello"}]}]}}""")
        }
        assertEquals("hello", ai.transcribeSpeech(model("deepgram", "nova-3"), "audio".toByteArray(), "record.wav", "auto").content)
    }

    @Test fun deepgramSpeechSelectsVoiceInQuery() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("/v1/speak", request.url.encodedPath)
            assertEquals("aura-2-thalia-en", request.url.queryParameter("model"))
            assertEquals("Token key", request.header("Authorization"))
            assertEquals("hello", json(request).get("text").asString)
            response(request, "audio", "audio/mpeg")
        }
        assertArrayEquals("audio".toByteArray(), ai.synthesizeSpeech(model("deepgram", "aura-2-thalia-en"), "hello"))
    }

    @Test fun siliconFlowSpeechPrefixesBuiltinVoiceAndMapsVolumeToGain() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("https://api.siliconflow.cn/v1/audio/speech", request.url.toString())
            val body = json(request)
            assertEquals("FunAudioLLM/CosyVoice2-0.5B:bella", body.get("voice").asString)
            assertEquals(0.0, body.get("gain").asDouble, 0.001)
            assertFalse(body.has("volume"))
            response(request, "audio", "audio/mpeg")
        }
        ai.synthesizeSpeech(model("silicon_flow", "FunAudioLLM/CosyVoice2-0.5B"), "hello", voice = "bella")
    }

    @Test fun groqAndGlmUseCorrectTranscriptionDefaults() = runBlocking<Unit> {
        for ((provider, url) in mapOf("groq" to "https://api.groq.com/openai/v1/audio/transcriptions", "zhipu" to "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions")) {
            val ai = client { request ->
                assertEquals(url, request.url.toString())
                assertFalse(body(request).contains("name=\"language\""))
                if (provider == "zhipu") assertTrue(body(request).contains("name=\"stream\""))
                response(request, """{"text":"hello"}""")
            }
            assertEquals("hello", ai.transcribeSpeech(model(provider, ""), byteArrayOf(1, 2), "audio.wav", "auto").content)
        }
    }

    @Test fun siliconFlowImagesUseNativeSizeAndHonorCount() = runBlocking<Unit> {
        var requests = 0
        val ai = client { request ->
            requests++
            assertEquals("https://api.siliconflow.cn/v1/images/generations", request.url.toString())
            assertEquals("960x1280", json(request).get("image_size").asString)
            assertFalse(json(request).has("size"))
            response(request, """{"images":[{"url":"https://example.com/image.png"}]}""")
        }
        assertEquals(2, ai.generateImage("", "key", "Kwai-Kolors/Kolors", "cat", size = "960x1280", n = 2, provider = "siliconflow").size)
        assertEquals(2, requests)
    }

    @Test fun siliconFlowEditUsesDataUriWithoutUnsupportedSize() = runBlocking<Unit> {
        val ai = client { request ->
            assertTrue(json(request).get("image").asString.startsWith("data:image/png;base64,"))
            assertFalse(json(request).has("image_size"))
            response(request, """{"images":[{"url":"https://example.com/image.png"}]}""")
        }
        ai.generateImage("", "key", "Qwen/Qwen-Image-Edit-2509", "cat", provider = "siliconflow", referenceImage = ImageGenerationReference(byteArrayOf(1, 2)))
    }

    @Test fun grokImageEditUsesJsonAndAspectRatio() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("https://api.x.ai/v1/images/edits", request.url.toString())
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            val payload = json(request)
            assertEquals("16:9", payload.get("aspect_ratio").asString)
            assertEquals("image_url", payload.getAsJsonObject("image").get("type").asString)
            response(request, """{"data":[{"b64_json":"AQI="}]}""")
        }
        val images = ai.generateImage("", "key", "grok-imagine-image-2.0", "cat", size = "1536x864", provider = "xai", referenceImage = ImageGenerationReference(byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), images.single().bytes)
    }

    @Test fun stabilityUsesMultipartAndBinaryImage() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("https://api.stability.ai/v2beta/stable-image/generate/core", request.url.toString())
            assertEquals("image/*", request.header("Accept"))
            assertTrue(body(request).contains("name=\"aspect_ratio\"")); assertTrue(body(request).contains("16:9"))
            response(request, "image", "image/png")
        }
        assertArrayEquals("image".toByteArray(), ai.generateImage("", "key", "core", "cat", size = "1536x864", provider = "stability").single().bytes)
    }

    @Test fun nativeErrorsRemainEligibleForFailover() = runBlocking<Unit> {
        val ai = client { request -> response(request, "quota exceeded", code = 429) }
        try {
            ai.synthesizeSpeech(model("elevenlabs", "eleven_multilingual_v2"), "hello")
            fail("Expected HTTP failure")
        } catch (error: FailoverHttpException) {
            assertTrue(error.message.orEmpty().contains("429"))
        }
    }

    @Test fun mediaPresetsAndVoicesKeepNativeProviderIdentity() {
        assertTrue(mediaModelPresets("live").any { it.provider == "google" && it.model.contains("live") })
        assertEquals("scribe_v2", mediaModelPresets("stt").single { it.provider == "elevenlabs" }.model)
        assertEquals("stella", mediaModelPresets("tts").single { it.provider == "deepseek" }.voice)
        assertEquals("wav", mediaModelPresets("tts").single { it.provider == "deepseek" }.format)
        assertEquals(
            "https://api.deepseek.com/v1",
            mediaModelPresets("stt").single { it.provider == "deepseek" }.baseUrl
        )
        assertEquals("siliconflow", TtsVoiceCatalog.voices("siliconflow").first().provider)
        assertFalse(MultimodalProviderSupport.supportsImageReference("siliconflow", "Kwai-Kolors/Kolors"))
        assertFalse(MultimodalProviderSupport.supportsImageReference("stability", "core"))
        assertEquals("1280x720", siliconFlowImageSize("Kwai-Kolors/Kolors", "1792x1024"))
        assertEquals("1328x1328", siliconFlowImageSize("Qwen/Qwen-Image", "1024x1024"))
        assertEquals("5:4", stabilityAspectRatio("1024x768"))
    }

    @Test fun glmImagesUseNativePathAndHonorCount() = runBlocking<Unit> {
        var count = 0
        val ai = client { request ->
            count++
            assertEquals("https://open.bigmodel.cn/api/paas/v4/images/generations", request.url.toString())
            response(request, """{"data":[{"url":"https://example.com/image.png"}]}""")
        }
        assertEquals(2, ai.generateImage("", "key", "cogview-4", "cat", n = 2, provider = "zhipu").size)
        assertEquals(2, count)
    }

    @Test fun seedreamReferenceAndCountReachNativeImageEndpoint() = runBlocking<Unit> {
        var count = 0
        val ai = client { request ->
            count++
            assertEquals("https://ark.cn-beijing.volces.com/api/v3/images/generations", request.url.toString())
            assertTrue(json(request).get("image").asString.startsWith("data:image/png;base64,"))
            response(request, """{"data":[{"url":"https://example.com/image.png"}]}""")
        }
        assertEquals(2, ai.generateImage("", "key", "doubao-seedream-4-0-250828", "cat", n = 2, provider = "doubao", referenceImage = ImageGenerationReference(byteArrayOf(1, 2))).size)
        assertEquals(2, count)
    }

    @Test fun qwenAsrUsesLanguageOptionsAndConvertsNativeBase() = runBlocking<Unit> {
        val ai = client { request ->
            assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", request.url.toString())
            assertEquals("zh", json(request).getAsJsonObject("asr_options").get("language").asString)
            response(request, """{"choices":[{"message":{"content":"你好"}}]}""")
        }
        assertEquals("你好", ai.transcribeSpeech(model("qwen", "qwen3-asr-flash").copy(baseUrl = "https://dashscope.aliyuncs.com/api/v1"), byteArrayOf(1, 2), "audio.wav", "zh").content)
    }

    private fun model(provider: String, name: String) = LocalAiModelEntity(
        id = "media", name = "Media", protocol = "openai_chat", provider = provider,
        apiKey = "key", baseUrl = "", model = name, ttsProvider = MultimodalProviderSupport.normalize(provider), createdAt = "2026-10-14"
    )
    private fun client(handler: (Request) -> Response) = LocalAiClient(OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build())
    private fun body(request: Request): String = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private fun json(request: Request) = JsonParser.parseString(body(request)).asJsonObject
    private fun response(request: Request, body: String, type: String = "application/json", code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()
}
