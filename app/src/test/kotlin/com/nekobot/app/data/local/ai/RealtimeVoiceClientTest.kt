package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceClientTest {

    @Test
    fun websocketUrlAppendsRealtimePathAndModel() {
        assertEquals(
            "wss://api.openai.com/v1/realtime?model=gpt-realtime",
            buildRealtimeWebSocketUrl(
                baseUrl = "https://api.openai.com/v1/",
                appendRealtimePath = true,
                model = "gpt-realtime"
            )
        )
    }

    @Test
    fun websocketUrlPreservesCustomQueryWithoutDuplicatingModel() {
        assertEquals(
            "wss://proxy.example/v1/realtime?region=cn&model=gpt-realtime-2025-08-28",
            buildRealtimeWebSocketUrl(
                baseUrl = "wss://proxy.example/v1/realtime?region=cn&model=old",
                appendRealtimePath = true,
                model = "gpt-realtime-2025-08-28"
            )
        )
    }

    @Test
    fun sessionUpdateRequestsAudioWithTranscriptAndPcm24k() {
        val event = buildRealtimeSessionUpdate(
            config = RealtimeModelConfig(
                id = "live",
                name = "Live",
                apiKey = "test",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-realtime",
                voice = "marin",
                transcriptionModel = "gpt-4o-mini-transcribe",
                language = "zh"
            ),
            instructions = "保持角色设定"
        )
        val session = event.getAsJsonObject("session")
        val audio = session.getAsJsonObject("audio")
        val input = audio.getAsJsonObject("input")
        val output = audio.getAsJsonObject("output")

        assertEquals("session.update", event.get("type").asString)
        assertEquals("realtime", session.get("type").asString)
        assertEquals("audio", session.getAsJsonArray("output_modalities")[0].asString)
        assertEquals("audio/pcm", input.getAsJsonObject("format").get("type").asString)
        assertEquals(24_000, input.getAsJsonObject("format").get("rate").asInt)
        assertTrue(input.get("turn_detection").isJsonNull)
        assertEquals("gpt-4o-mini-transcribe", input.getAsJsonObject("transcription").get("model").asString)
        assertEquals("marin", output.get("voice").asString)
    }

    @Test
    fun qwenProviderDetectionFlagsConfig() {
        assertTrue(
            RealtimeModelConfig(
                id = "live", name = "Qwen Live", apiKey = "test",
                baseUrl = "https://dashscope.aliyuncs.com/api-ws/v1",
                model = REALTIME_QWEN_DEFAULT_MODEL,
                provider = "qwen"
            ).isQwenRealtime
        )
        assertTrue(
            RealtimeModelConfig(
                id = "live", name = "Qwen Live", apiKey = "test",
                baseUrl = "https://dashscope.aliyuncs.com/api-ws/v1",
                model = REALTIME_QWEN_DEFAULT_MODEL,
                provider = null
            ).isQwenRealtime
        )
        assertFalse(
            RealtimeModelConfig(
                id = "live", name = "OpenAI Live", apiKey = "test",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-realtime",
                provider = "openai"
            ).isQwenRealtime
        )
    }

    @Test
    fun seedAndGlmProviderDetectionFlagsConfigs() {
        val seed = RealtimeModelConfig(
            id = "live",
            name = "Seed Live",
            apiKey = "test",
            baseUrl = REALTIME_SEED_DEFAULT_BASE_URL,
            model = REALTIME_SEED_DEFAULT_MODEL,
            provider = "doubao"
        )
        val glm = RealtimeModelConfig(
            id = "live",
            name = "GLM Live",
            apiKey = "test",
            baseUrl = REALTIME_GLM_DEFAULT_BASE_URL,
            model = REALTIME_GLM_DEFAULT_MODEL,
            provider = "zhipu"
        )

        assertTrue(seed.isSeedRealtime)
        assertFalse(seed.isQwenRealtime)
        assertTrue(glm.isGlmRealtime)
        assertFalse(glm.isQwenRealtime)
    }

    @Test
    fun sessionUpdateFallsBackToQwenVoiceAndTranscriptionWhenBlank() {
        val event = buildRealtimeSessionUpdate(
            config = RealtimeModelConfig(
                id = "live",
                name = "Qwen Live",
                apiKey = "test",
                baseUrl = "https://dashscope.aliyuncs.com/api-ws/v1",
                model = REALTIME_QWEN_DEFAULT_MODEL,
                voice = "",
                transcriptionModel = "",
                language = "zh",
                provider = "qwen"
            ),
            instructions = "保持角色设定"
        )
        val session = event.getAsJsonObject("session")

        assertEquals("session.update", event.get("type").asString)
        assertEquals(REALTIME_QWEN_DEFAULT_MODEL, session.get("model").asString)
        assertEquals(REALTIME_QWEN_DEFAULT_VOICE, session.get("voice").asString)
        assertEquals("pcm16", session.get("input_audio_format").asString)
        assertEquals("pcm24", session.get("output_audio_format").asString)
        assertEquals(
            REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL,
            session.getAsJsonObject("input_audio_transcription").get("model").asString
        )
        assertTrue(session.get("turn_detection").isJsonNull)
        val modalities = session.getAsJsonArray("modalities")
        assertEquals("text", modalities[0].asString)
        assertEquals("audio", modalities[1].asString)
    }

    @Test
    fun qwenSessionUpdateIncludesOpenAiFormatAgentTools() {
        val tools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "get_weather",
                    "description" to "查询天气",
                    "parameters" to mapOf("type" to "object")
                )
            )
        )
        val event = buildRealtimeSessionUpdate(
            config = RealtimeModelConfig(
                id = "live",
                name = "Qwen Live",
                apiKey = "test",
                baseUrl = REALTIME_QWEN_DEFAULT_BASE_URL,
                model = REALTIME_QWEN_DEFAULT_MODEL,
                provider = "qwen"
            ),
            instructions = "使用工具完成任务",
            tools = tools
        )

        val registered = event.getAsJsonObject("session").getAsJsonArray("tools")
        assertEquals("function", registered[0].asJsonObject.get("type").asString)
        assertEquals(
            "get_weather",
            registered[0].asJsonObject.getAsJsonObject("function").get("name").asString
        )
    }

    @Test
    fun qwenFunctionOutputPreservesCallIdAndJsonResult() {
        val event = buildQwenFunctionCallOutput(
            callId = "call_weather",
            output = mapOf("success" to true, "temperature" to 25)
        )

        val item = event.getAsJsonObject("item")
        assertEquals("conversation.item.create", event.get("type").asString)
        assertEquals("function_call_output", item.get("type").asString)
        assertEquals("call_weather", item.get("call_id").asString)
        assertTrue(item.get("output").asString.contains("\"temperature\":25"))
    }

    @Test
    fun websocketUrlCorrectsCompatibleModeToApiWsForQwenRealtime() {
        // Qwen 预设默认 baseurl 是 compatible-mode/v1（文本对话端点），Realtime 需要自动纠正成 api-ws/v1
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$REALTIME_QWEN_DEFAULT_MODEL",
            buildRealtimeWebSocketUrl(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                appendRealtimePath = true,
                model = REALTIME_QWEN_DEFAULT_MODEL,
                qwenRealtime = true
            )
        )
    }

    @Test
    fun websocketUrlKeepsApiWsBaseAndAppendsModelForQwenRealtime() {
        // 正确的 api-ws/v1 baseurl 保持不变，并带 ?model= 查询参数
        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=$REALTIME_QWEN_DEFAULT_MODEL",
            buildRealtimeWebSocketUrl(
                baseUrl = "https://dashscope.aliyuncs.com/api-ws/v1",
                appendRealtimePath = true,
                model = REALTIME_QWEN_DEFAULT_MODEL,
                qwenRealtime = true
            )
        )
    }

    @Test
    fun websocketUrlStillAppendsModelQueryForOpenAIRealtime() {
        assertEquals(
            "wss://api.openai.com/v1/realtime?model=gpt-realtime",
            buildRealtimeWebSocketUrl(
                baseUrl = "https://api.openai.com/v1",
                appendRealtimePath = true,
                model = "gpt-realtime",
                qwenRealtime = false
            )
        )
    }

    @Test
    fun websocketUrlsUseArkAndZhipuRealtimeEndpoints() {
        assertEquals(
            "wss://ark.cn-beijing.volces.com/api/v3/realtime?model=$REALTIME_SEED_DEFAULT_MODEL",
            buildRealtimeWebSocketUrl(
                baseUrl = REALTIME_SEED_DEFAULT_BASE_URL,
                appendRealtimePath = true,
                model = REALTIME_SEED_DEFAULT_MODEL
            )
        )
        assertEquals(
            "wss://open.bigmodel.cn/api/paas/v4/realtime?model=$REALTIME_GLM_DEFAULT_MODEL",
            buildRealtimeWebSocketUrl(
                baseUrl = REALTIME_GLM_DEFAULT_BASE_URL,
                appendRealtimePath = true,
                model = REALTIME_GLM_DEFAULT_MODEL
            )
        )
    }

    @Test
    fun seedAndGlmUseFlatRealtimeSessionAndResponsePayloads() {
        val configs = listOf(
            RealtimeModelConfig(
                id = "seed",
                name = "Seed Live",
                apiKey = "test",
                baseUrl = REALTIME_SEED_DEFAULT_BASE_URL,
                model = REALTIME_SEED_DEFAULT_MODEL,
                voice = "",
                transcriptionModel = "",
                provider = "doubao"
            ) to Pair(REALTIME_SEED_DEFAULT_VOICE, REALTIME_SEED_DEFAULT_TRANSCRIPTION_MODEL),
            RealtimeModelConfig(
                id = "glm",
                name = "GLM Live",
                apiKey = "test",
                baseUrl = REALTIME_GLM_DEFAULT_BASE_URL,
                model = REALTIME_GLM_DEFAULT_MODEL,
                voice = "",
                transcriptionModel = "",
                provider = "zhipu"
            ) to Pair(REALTIME_GLM_DEFAULT_VOICE, REALTIME_GLM_DEFAULT_TRANSCRIPTION_MODEL)
        )

        configs.forEach { (config, defaults) ->
            val event = buildRealtimeSessionUpdate(config, "保持角色设定")
            val session = event.getAsJsonObject("session")
            val response = buildRealtimeResponseCreate(config).getAsJsonObject("response")

            assertEquals("session.update", event.get("type").asString)
            assertEquals(config.model, session.get("model").asString)
            assertEquals("pcm16", session.get("input_audio_format").asString)
            assertEquals("pcm16", session.get("output_audio_format").asString)
            assertEquals(defaults.first, session.get("voice").asString)
            assertEquals(
                defaults.second,
                session.getAsJsonObject("input_audio_transcription").get("model").asString
            )
            assertEquals("audio", response.getAsJsonArray("modalities")[1].asString)
        }
    }

    @Test
    fun contextEventsUseInputTextForUserAndOutputTextForAssistant() {
        val events = buildRealtimeContextEvents(
            listOf(
                RealtimeContextMessage("user", "你好"),
                RealtimeContextMessage("assistant", "晚上好")
            )
        )

        val userItem = events[0].getAsJsonObject("item")
        val assistantItem = events[1].getAsJsonObject("item")
        assertEquals("input_text", userItem.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
        assertEquals("output_text", assistantItem.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
    }

    @Test
    fun contextTrimmingKeepsNewestTurns() {
        val trimmed = trimRealtimeContext(
            listOf(
                RealtimeContextMessage("user", "old"),
                RealtimeContextMessage("assistant", "newest")
            ),
            maxChars = 6
        )

        assertEquals(1, trimmed.size)
        assertEquals("newest", trimmed.single().content)
        assertFalse(trimmed.any { it.content == "old" })
    }
}
