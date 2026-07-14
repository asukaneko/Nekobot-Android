package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

/**
 * 运行模式：本地模式直连 AI API + Room 存储；服务器模式走后端。
 */
enum class AppMode {
    SERVER,
    LOCAL;

    val isLocal: Boolean get() = this == LOCAL
}

enum class ChatInputLayoutMode {
    MERGED,
    SEPARATE;

    companion object {
        fun fromStorage(value: String?): ChatInputLayoutMode =
            entries.firstOrNull { it.name == value } ?: MERGED
    }
}

/**
 * 单条登录记录：服务器地址 + 用户名 + token（密码不保存，快速登录靠 token 复用）。
 * 若 token 已失效，后端会返回 401，届时用户需重新输入密码登录。
 */
data class LoginRecord(
    val serverUrl: String,
    val username: String,
    val token: String,
    val savedAt: Long = System.currentTimeMillis()
)

/**
 * 本地持久化：服务器地址、登录 token、主题偏好等。
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

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

    // ==================== 登录记录（多账号） ====================

    /**
     * 读取所有已保存的登录记录（按保存时间倒序）。
     * 记录以 serverUrl + username 作为唯一键，重复登录会覆盖旧记录。
     */
    fun listLoginRecords(): List<LoginRecord> {
        val raw = prefs.getString(KEY_LOGIN_RECORDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<JsonObject>>() {}.type
            val list: List<JsonObject> = gson.fromJson(raw, type)
            list.mapNotNull { obj ->
                try {
                    LoginRecord(
                        serverUrl = obj.get("serverUrl")?.asString ?: return@mapNotNull null,
                        username = obj.get("username")?.asString ?: return@mapNotNull null,
                        token = obj.get("token")?.asString ?: return@mapNotNull null,
                        savedAt = obj.get("savedAt")?.asLong ?: 0L
                    )
                } catch (_: Exception) { null }
            }.sortedByDescending { it.savedAt }
        } catch (_: Exception) { emptyList() }
    }

    /** 保存/更新一条登录记录（同 serverUrl+username 覆盖） */
    fun saveLoginRecord(server: String, user: String, tkn: String) {
        val normalized = if (server.endsWith("/")) server.dropLast(1) else server
        val current = listLoginRecords().toMutableList()
        // 去重：同 server + user 视为同一条
        current.removeAll { it.serverUrl == normalized && it.username == user }
        current.add(LoginRecord(normalized, user, tkn))
        // 限制最多 10 条
        val toSave = current.sortedByDescending { it.savedAt }.take(10)
        prefs.edit().putString(KEY_LOGIN_RECORDS, gson.toJson(toSave)).apply()
    }

    /** 删除指定登录记录 */
    fun removeLoginRecord(server: String, user: String) {
        val normalized = if (server.endsWith("/")) server.dropLast(1) else server
        val remaining = listLoginRecords().filterNot { it.serverUrl == normalized && it.username == user }
        prefs.edit().putString(KEY_LOGIN_RECORDS, gson.toJson(remaining)).apply()
    }

    /** 清空所有登录记录 */
    fun clearLoginRecords() {
        prefs.edit().remove(KEY_LOGIN_RECORDS).apply()
    }

    /** 运行模式：本地 / 服务器 */
    var appMode: AppMode
        get() {
            val raw = prefs.getString(KEY_APP_MODE, AppMode.SERVER.name) ?: AppMode.SERVER.name
            return runCatching { AppMode.valueOf(raw) }.getOrDefault(AppMode.SERVER)
        }
        set(value) {
            prefs.edit().putString(KEY_APP_MODE, value.name).apply()
        }

    val isLocalMode: Boolean get() = appMode.isLocal

    /** 聊天底部输入栏的全局布局偏好。 */
    var chatInputLayoutMode: ChatInputLayoutMode
        get() = ChatInputLayoutMode.fromStorage(prefs.getString(KEY_CHAT_INPUT_LAYOUT, null))
        set(value) {
            prefs.edit().putString(KEY_CHAT_INPUT_LAYOUT, value.name).apply()
        }

    /** 字体类型：system / serif / monospace / rounded / custom */
    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, FONT_FAMILY_SYSTEM) ?: FONT_FAMILY_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_FONT_FAMILY, value).apply()
        }

    /** 自定义字体文件绝对路径（fontFamily == "custom" 时生效），null 表示未上传 */
    var customFontPath: String?
        get() = prefs.getString(KEY_CUSTOM_FONT_PATH, null)
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_FONT_PATH, value).apply()
        }

    /** 自定义字体显示名（用于设置页展示），null 表示未上传 */
    var customFontName: String?
        get() = prefs.getString(KEY_CUSTOM_FONT_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_FONT_NAME, value).apply()
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

    /** 主题色覆盖：null 表示使用默认紫色，否则为颜色 hex 值如 "#4D96FF" */
    var themeColorOverride: String?
        get() = prefs.getString(KEY_THEME_COLOR, null)
        set(value) {
            prefs.edit().putString(KEY_THEME_COLOR, value).apply()
        }

    val isLoggedIn: Boolean
        get() = when (appMode) {
            AppMode.LOCAL -> true  // 本地模式无需登录
            AppMode.SERVER -> !token.isNullOrEmpty() && serverUrl.isNotEmpty()
        }

    fun clearAuth() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    // ==================== 会话通知提醒 ====================

    /** 获取指定会话的通知提醒开关 */
    fun isSessionNotificationEnabled(sessionId: String): Boolean {
        return prefs.getBoolean("notif_$sessionId", false)
    }

    /** 设置指定会话的通知提醒开关 */
    fun setSessionNotificationEnabled(sessionId: String, enabled: Boolean) {
        prefs.edit().putBoolean("notif_$sessionId", enabled).apply()
    }

    companion object {
        private const val PREF_NAME = "nekobot_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_LOGIN_RECORDS = "login_records"
        private const val KEY_CHAT_INPUT_LAYOUT = "chat_input_layout"
        private const val DEFAULT_SERVER = "http://localhost:5000"

        // 样式相关 KEY
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_COLOR = "font_color"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        const val KEY_CUSTOM_FONT_NAME = "custom_font_name"

        // 字体类型可选值
        const val FONT_FAMILY_SYSTEM = "system"
        const val FONT_FAMILY_SERIF = "serif"
        const val FONT_FAMILY_MONOSPACE = "monospace"
        const val FONT_FAMILY_ROUNDED = "rounded"
        const val FONT_FAMILY_CUSTOM = "custom"
    }
}
