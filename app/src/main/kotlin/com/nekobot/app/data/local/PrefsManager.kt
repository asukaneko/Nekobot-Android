package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地持久化：服务器地址、登录 token、主题偏好等。
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) {
            // 规范化：去掉末尾斜杠
            val normalized = if (value.endsWith("/")) value.dropLast(1) else value
            prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    /** 字体类型：system / serif / monospace / rounded */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, FONT_FAMILY_SYSTEM) ?: FONT_FAMILY_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_FONT_FAMILY, value).apply()
        }

    /** 字体缩放因子：0.85f / 1.0f / 1.15f / 1.3f */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()
        }

    /** 字体颜色覆盖：null 表示跟随主题，否则为颜色 hex 值如 "#FF6B6B" */
    var fontColorOverride: String?
        get() = prefs.getString(KEY_FONT_COLOR, null)
        set(value) {
            prefs.edit().putString(KEY_FONT_COLOR, value).apply()
        }

    val isLoggedIn: Boolean
        get() = !token.isNullOrEmpty() && serverUrl.isNotEmpty()

    fun clearAuth() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val PREF_NAME = "nekobot_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USERNAME = "username"
        private const val DEFAULT_SERVER = "http://localhost:5000"

        // 样式相关 KEY
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_COLOR = "font_color"

        // 字体类型可选值
        const val FONT_FAMILY_SYSTEM = "system"
        const val FONT_FAMILY_SERIF = "serif"
        const val FONT_FAMILY_MONOSPACE = "monospace"
        const val FONT_FAMILY_ROUNDED = "rounded"
    }
}
