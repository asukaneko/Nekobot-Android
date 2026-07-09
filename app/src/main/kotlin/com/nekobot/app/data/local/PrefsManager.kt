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
    }
}
