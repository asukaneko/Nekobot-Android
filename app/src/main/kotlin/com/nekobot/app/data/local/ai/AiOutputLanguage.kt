package com.nekobot.app.data.local.ai

import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.data.local.PrefsManager
import java.util.Locale

/**
 * AI 生成内容的输出语言策略。
 *
 * 应用内所有由辅助任务发起的 AI 生成内容（会话标题、角色状态历程、记忆摘要、
 * 剧情选项、角色生活片段、角色灵感、世界书条目、工作流等）的输出语言，
 * 统一跟随设置页当前选择的语言；选择"跟随系统"时解析系统语言。
 * 主对话回复不受此策略影响，仍由角色卡与对话上下文决定。
 */
object AiOutputLanguage {

    /** 支持的语言代码集合（与 LocaleHelper 保持一致）。 */
    private val SUPPORTED_TAGS = setOf(
        PrefsManager.LANGUAGE_ZH,
        PrefsManager.LANGUAGE_EN,
        PrefsManager.LANGUAGE_JA,
        PrefsManager.LANGUAGE_KO
    )

    /** 当前生效的语言代码（zh/en/ja/ko），供需要语言代码的生成接口使用。 */
    fun languageTag(): String {
        val tag = runCatching {
            val pref = ServiceContainer.prefs.language
            val context = ServiceContainer.appContext
            if (context != null) {
                LocaleHelper.getEffectiveLocale(context, pref).language
            } else {
                Locale.getDefault().language
            }
        }.getOrDefault(Locale.getDefault().language)
        return normalizeTag(tag)
    }

    /** 语言代码对应的语言名称（直接写进提示词，要求模型用该语言输出）。 */
    fun languageName(): String = when (languageTag()) {
        PrefsManager.LANGUAGE_EN -> "English"
        PrefsManager.LANGUAGE_JA -> "日本語"
        PrefsManager.LANGUAGE_KO -> "한국어"
        else -> "简体中文"
    }

    /**
     * 输出语言约束指令：附加到辅助生成的 system prompt 末尾，
     * 覆盖提示词模板中固化的语言假设（如"所有字段用中文"）。
     */
    fun directive(): String =
        "【输出语言】所有生成内容（包括所有字段值，如标题、名称、摘要、理由等）" +
            "必须使用${languageName()}书写，不要混用其他语言。"

    /** 归一化语言代码：仅保留受支持的语言，未知语言回落到英语（与 LocaleHelper 一致）。 */
    private fun normalizeTag(tag: String): String {
        val lower = tag.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return if (lower in SUPPORTED_TAGS) lower else PrefsManager.LANGUAGE_EN
    }
}
