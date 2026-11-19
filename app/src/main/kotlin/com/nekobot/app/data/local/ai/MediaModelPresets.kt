package com.nekobot.app.data.local.ai

/** 按用途提供原生模型预设，避免把对话模型名带入语音或生图请求。 */
internal data class MediaModelPreset(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val voice: String = "default",
    val format: String = "mp3"
)

internal fun mediaModelPresets(purpose: String): List<MediaModelPreset> = when (purpose) {
    "tts" -> listOf(
        MediaModelPreset("openai", "gpt-4o-mini-tts", "https://api.openai.com/v1", "alloy"),
        MediaModelPreset("google", "gemini-2.5-flash-preview-tts", "https://generativelanguage.googleapis.com/v1beta", "Kore", "wav"),
        MediaModelPreset("qwen", "qwen3-tts-flash", "https://dashscope.aliyuncs.com/api/v1", "Cherry"),
        MediaModelPreset("zhipu", "glm-tts", "https://open.bigmodel.cn/api/paas/v4", "tongtong", "wav"),
        MediaModelPreset("minimax", "speech-2.8-hd", "https://api.minimaxi.com/v1", "male-qn-qingse"),
        MediaModelPreset("doubao", "seed-tts-2.0", "https://openspeech.bytedance.com", "zh_female_shuangkuaisisi_moon_bigtts"),
        MediaModelPreset("xiaomi", "mimo-v2.5-tts", "https://api.xiaomimimo.com/v1", "mimo_default"),
        MediaModelPreset("siliconflow", "FunAudioLLM/CosyVoice2-0.5B", "https://api.siliconflow.cn/v1", "alex"),
        MediaModelPreset("elevenlabs", "eleven_multilingual_v2", "https://api.elevenlabs.io/v1", "JBFqnCBsd6RMkjVDRZzb"),
        MediaModelPreset("deepgram", "aura-2-thalia-en", "https://api.deepgram.com/v1", "aura-2-thalia-en"),
        MediaModelPreset("deepseek", DeepSeekVoiceTools.TTS_MODEL_PLACEHOLDER, DeepSeekVoiceTools.DEFAULT_BASE_URL, DeepSeekVoiceTools.TTS_DEFAULT_VOICE, "wav")
    )
    "stt" -> listOf(
        MediaModelPreset("openai", "gpt-4o-mini-transcribe", "https://api.openai.com/v1"),
        MediaModelPreset("google", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta"),
        MediaModelPreset("qwen", "qwen3-asr-flash", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        MediaModelPreset("zhipu", "glm-asr-2512", "https://open.bigmodel.cn/api/paas/v4"),
        MediaModelPreset("xiaomi", "mimo-v2.5-asr", "https://api.xiaomimimo.com/v1"),
        MediaModelPreset("siliconflow", "FunAudioLLM/SenseVoiceSmall", "https://api.siliconflow.cn/v1"),
        MediaModelPreset("groq", "whisper-large-v3-turbo", "https://api.groq.com/openai/v1"),
        MediaModelPreset("elevenlabs", "scribe_v2", "https://api.elevenlabs.io/v1"),
        MediaModelPreset("deepgram", "nova-3", "https://api.deepgram.com/v1"),
        MediaModelPreset("deepseek", DeepSeekVoiceTools.ASR_MODEL_PLACEHOLDER, DeepSeekVoiceTools.DEFAULT_BASE_URL)
    )
    "image_generation" -> listOf(
        MediaModelPreset("openai", "gpt-image-1", "https://api.openai.com/v1"),
        MediaModelPreset("google", "gemini-3.1-flash-image", "https://generativelanguage.googleapis.com/v1beta"),
        MediaModelPreset("qwen", "qwen-image-3.0", "https://dashscope.aliyuncs.com/api/v1"),
        MediaModelPreset("doubao", "doubao-seedream-4-0-250828", "https://ark.cn-beijing.volces.com/api/v3"),
        MediaModelPreset("minimax", "image-01", "https://api.minimaxi.com/v1"),
        MediaModelPreset("zhipu", "cogview-4", "https://open.bigmodel.cn/api/paas/v4"),
        MediaModelPreset("siliconflow", "Kwai-Kolors/Kolors", "https://api.siliconflow.cn/v1"),
        MediaModelPreset("grok", "grok-imagine-image-2.0", "https://api.x.ai/v1"),
        MediaModelPreset("stability", "core", "https://api.stability.ai/v2beta")
    )
    "live" -> listOf(
        MediaModelPreset("openai", "gpt-realtime", "https://api.openai.com/v1", "marin"),
        MediaModelPreset("google", REALTIME_GEMINI_DEFAULT_MODEL, "https://generativelanguage.googleapis.com/v1beta", "Kore"),
        MediaModelPreset("qwen", REALTIME_QWEN_DEFAULT_MODEL, REALTIME_QWEN_DEFAULT_BASE_URL, REALTIME_QWEN_DEFAULT_VOICE),
        MediaModelPreset("zhipu", REALTIME_GLM_DEFAULT_MODEL, REALTIME_GLM_DEFAULT_BASE_URL, REALTIME_GLM_DEFAULT_VOICE),
        MediaModelPreset("grok", REALTIME_GROK_DEFAULT_MODEL, "https://api.x.ai/v1", "eve"),
        MediaModelPreset("stepfun", REALTIME_STEPFUN_DEFAULT_MODEL, REALTIME_STEPFUN_DEFAULT_BASE_URL, REALTIME_STEPFUN_DEFAULT_VOICE)
    )
    else -> emptyList()
}
