package com.nekobot.app.data.local

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 语言切换辅助工具。
 *
 * 支持三种模式：跟随系统（system）、简体中文（zh）、英语（en）。
 * - API 26+：通过 [Context.createConfigurationContext] 包装上下文，使资源按选定语言加载。
 * - 跟随系统时，若系统语言非中文则回落到英语。
 */
object LocaleHelper {

    /** 根据偏好返回实际生效的 [Locale]。 */
    fun getEffectiveLocale(context: Context, languageTag: String): Locale {
        return when (languageTag) {
            PrefsManager.LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
            PrefsManager.LANGUAGE_EN -> Locale.ENGLISH
            else -> {
                // 跟随系统：系统语言以 zh 开头用中文，否则用英文
                val sys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.resources.configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    context.resources.configuration.locale
                }
                if (sys.language.equals("zh", ignoreCase = true)) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
            }
        }
    }

    /** 用选定的 [Locale] 包装上下文，返回带新配置的 [Context]。 */
    fun wrap(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return context.createConfigurationContext(config)
    }

    /** 便捷方法：读取 PrefsManager 中的语言偏好并包装上下文。 */
    fun wrap(context: Context): Context {
        val prefs = PrefsManager(context.applicationContext)
        val locale = getEffectiveLocale(context, prefs.language)
        return wrap(context, locale)
    }
}
