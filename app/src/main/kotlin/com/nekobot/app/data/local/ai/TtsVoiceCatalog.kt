package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.TtsVoice

/** 与原仓库的提供商音色目录保持一致，避免把 OpenAI 音色传给原生 TTS。 */
internal object TtsVoiceCatalog {
    fun normalize(provider: String): String = when (provider.trim().lowercase()) {
        "xiaomi", "mimo" -> "xiaomi"
        "doubao", "volcengine", "bytedance" -> "doubao"
        "gemini", "google", "google_gemini", "gemini_native" -> "gemini"
        "glm", "zhipu", "bigmodel" -> "glm"
        "qwen", "dashscope", "tongyi" -> "qwen"
        "minimax" -> "minimax"
        "siliconflow", "silicon_flow" -> "siliconflow"
        "elevenlabs", "eleven_labs" -> "elevenlabs"
        "deepgram" -> "deepgram"
        "deepseek" -> "deepseek"
        else -> "openai"
    }

    fun voices(provider: String, configuredVoice: String = ""): List<TtsVoice> {
        val normalized = normalize(provider)
        val builtins = when (normalized) {
            "deepseek" -> DeepSeekVoiceTools.TTS_VOICES.map { id ->
                TtsVoice(id = id, name = if (id == "stella") "海星·女声" else "暗潮·男声")
            }
            "siliconflow" -> SILICONFLOW_VOICES.map { TtsVoice(id = it, name = it) }
            "elevenlabs" -> listOf(TtsVoice(id = "JBFqnCBsd6RMkjVDRZzb", name = "George"))
            "deepgram" -> listOf("thalia", "andromeda", "helena", "apollo", "arcas", "orion")
                .map { TtsVoice(id = "aura-2-$it-en", name = it.replaceFirstChar(Char::uppercase)) }
            "xiaomi" -> listOf(
                TtsVoice(id = "mimo_default", name = "MiMo默认"),
                TtsVoice(id = "冰糖", name = "冰糖"),
                TtsVoice(id = "茉莉", name = "茉莉"),
                TtsVoice(id = "苏打", name = "苏打"),
                TtsVoice(id = "白桦", name = "白桦"),
                TtsVoice(id = "Mia", name = "Mia"),
                TtsVoice(id = "Chloe", name = "Chloe"),
                TtsVoice(id = "Milo", name = "Milo"),
                TtsVoice(id = "Dean", name = "Dean")
            )
            "doubao" -> listOf(
                TtsVoice(id = "zh_female_shuangkuaisisi_moon_bigtts", name = "爽快思思"),
                TtsVoice(id = "zh_male_bvlazysheep", name = "懒羊羊"),
                TtsVoice(id = "zh_male_ahu_conversation_wvae_bigtts", name = "阿虎"),
                TtsVoice(id = "zh_female_vv_uranus_bigtts", name = "VV"),
                TtsVoice(id = "zh_female_cancan_mars_bigtts", name = "灿灿"),
                TtsVoice(id = "zh_male_chongchong_mars_bigtts", name = "冲冲"),
                TtsVoice(id = "zh_female_dandan_mars_bigtts", name = "旦旦"),
                TtsVoice(id = "zh_male_dongbeihaoran_mars_bigtts", name = "浩然"),
                TtsVoice(id = "zh_female_gufeng_mars_bigtts", name = "古风"),
                TtsVoice(id = "zh_male_haoyu_mars_bigtts", name = "浩宇"),
                TtsVoice(id = "zh_female_shuangkuang_mars_bigtts", name = "爽快"),
                TtsVoice(id = "zh_male_tianyuan_mars_bigtts", name = "田园"),
                TtsVoice(id = "zh_female_wanwan_mars_bigtts", name = "婉婉"),
                TtsVoice(id = "zh_female_xinrui_mars_bigtts", name = "心蕊"),
                TtsVoice(id = "zh_male_yunxi_mars_bigtts", name = "云希"),
                TtsVoice(id = "zh_female_yunxia_mars_bigtts", name = "云霞")
            )
            "gemini" -> listOf(
                TtsVoice(id = "Kore", name = "Kore"),
                TtsVoice(id = "Puck", name = "Puck"),
                TtsVoice(id = "Charon", name = "Charon"),
                TtsVoice(id = "Aoede", name = "Aoede"),
                TtsVoice(id = "Fenrir", name = "Fenrir"),
                TtsVoice(id = "Leda", name = "Leda"),
                TtsVoice(id = "Orus", name = "Orus"),
                TtsVoice(id = "Zephyr", name = "Zephyr")
            )
            "glm" -> listOf(
                TtsVoice(id = "tongtong", name = "彤彤"),
                TtsVoice(id = "xiaoyuan", name = "小元"),
                TtsVoice(id = "hanhan", name = "涵涵"),
                TtsVoice(id = "zhiyu", name = "知鱼"),
                TtsVoice(id = "xiaobing", name = "晓兵"),
                TtsVoice(id = "jiemo", name = "介默")
            )
            "minimax" -> listOf(
                TtsVoice(id = "male-qn-qingse", name = "青涩 (男)"),
                TtsVoice(id = "male-qn-jingying", name = "精英 (男)"),
                TtsVoice(id = "female-shaonv", name = "少女 (女)"),
                TtsVoice(id = "female-chengshu", name = "成熟 (女)"),
                TtsVoice(id = "female-tianmei", name = "甜美 (女)"),
                TtsVoice(id = "audiobook_male_1", name = "播音男"),
                TtsVoice(id = "audiobook_male_2", name = "书卷男"),
                TtsVoice(id = "audiobook_female_1", name = "播音女"),
                TtsVoice(id = "audiobook_female_2", name = "书卷女")
            )
            "qwen" -> listOf(
                TtsVoice(id = "Cherry", name = "Cherry"),
                TtsVoice(id = "Serena", name = "Serena"),
                TtsVoice(id = "Ethan", name = "Ethan"),
                TtsVoice(id = "Elias", name = "Elias"),
                TtsVoice(id = "Luna", name = "Luna"),
                TtsVoice(id = "Carson", name = "Carson")
            )
            else -> listOf(
                TtsVoice(id = "alloy", name = "Alloy"),
                TtsVoice(id = "echo", name = "Echo"),
                TtsVoice(id = "fable", name = "Fable"),
                TtsVoice(id = "onyx", name = "Onyx"),
                TtsVoice(id = "nova", name = "Nova"),
                TtsVoice(id = "shimmer", name = "Shimmer"),
                TtsVoice(id = "coral", name = "Coral"),
                TtsVoice(id = "verse", name = "Verse"),
                TtsVoice(id = "ballad", name = "Ballad"),
                TtsVoice(id = "ash", name = "Ash"),
                TtsVoice(id = "sage", name = "Sage"),
                TtsVoice(id = "marin", name = "Marin"),
                TtsVoice(id = "cedar", name = "Cedar")
            )
        }.map { it.copy(provider = normalized) }
        val configured = configuredVoice.takeIf { it.isNotBlank() && it != "default" }
        return if (configured != null && builtins.none { it.id == configured }) {
            listOf(TtsVoice(id = configured, name = configured, provider = normalized)) + builtins
        } else builtins
    }
}
