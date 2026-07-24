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
            // 规范化：去掉末尾斜杠 + 自动为 IPv6 主机加方括号
            prefs.edit().putString(KEY_SERVER_URL, normalizeServerUrl(value)).apply()
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
        val normalized = normalizeServerUrl(server)
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
        val normalized = normalizeServerUrl(server)
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

    /** 隐私锁：应用每次重新进入前必须通过系统生物识别。 */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()
        }

    /** 聊天底部输入栏的全局布局偏好。 */
    var chatInputLayoutMode: ChatInputLayoutMode
        get() = ChatInputLayoutMode.fromStorage(prefs.getString(KEY_CHAT_INPUT_LAYOUT, null))
        set(value) {
            prefs.edit().putString(KEY_CHAT_INPUT_LAYOUT, value.name).apply()
        }

    /** 负一屏统计组件顺序；显示状态单独保存在 [statsDashboardHiddenWidgets]。 */
    var statsDashboardWidgetOrder: List<String>
        get() {
            val saved = prefs.getString(KEY_STATS_DASHBOARD_ORDER, null)
                ?.let { raw ->
                    runCatching {
                        val type = object : TypeToken<List<String>>() {}.type
                        gson.fromJson<List<String>>(raw, type)
                    }.getOrNull()
                }
                .orEmpty()
                .filter { it in DEFAULT_STATS_DASHBOARD_WIDGET_ORDER }
                .distinct()
            if (saved == LEGACY_DEFAULT_STATS_DASHBOARD_WIDGET_ORDER) {
                return DEFAULT_STATS_DASHBOARD_WIDGET_ORDER
            }
            return saved + DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.filterNot(saved::contains)
        }
        set(value) {
            val normalized = value
                .filter { it in DEFAULT_STATS_DASHBOARD_WIDGET_ORDER }
                .distinct() + DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.filterNot(value::contains)
            prefs.edit().putString(KEY_STATS_DASHBOARD_ORDER, gson.toJson(normalized)).apply()
        }

    var statsDashboardHiddenWidgets: Set<String>
        get() = prefs.getStringSet(KEY_STATS_DASHBOARD_HIDDEN, emptySet())
            .orEmpty()
            .filterTo(linkedSetOf()) { it in DEFAULT_STATS_DASHBOARD_WIDGET_ORDER }
        set(value) {
            prefs.edit().putStringSet(
                KEY_STATS_DASHBOARD_HIDDEN,
                value.filterTo(linkedSetOf()) { it in DEFAULT_STATS_DASHBOARD_WIDGET_ORDER }
            ).apply()
        }

    /** 高频角色组件排序：SESSIONS / TOKENS。 */
    var statsCharacterRankingMode: String
        get() = prefs.getString(KEY_STATS_CHARACTER_RANKING_MODE, "TOKENS") ?: "TOKENS"
        set(value) {
            prefs.edit().putString(
                KEY_STATS_CHARACTER_RANKING_MODE,
                if (value == "SESSIONS") "SESSIONS" else "TOKENS"
            ).apply()
        }

    /** 角色列表视图模式：LIST / GRID，持久化用户选择 */
    var characterViewMode: String
        get() = prefs.getString(KEY_CHARACTER_VIEW_MODE, "LIST") ?: "LIST"
        set(value) {
            prefs.edit().putString(KEY_CHARACTER_VIEW_MODE, value).apply()
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

    /** 主题色覆盖：null 表示使用默认粉色，否则为颜色 hex 值如 "#8B6CFF" */
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

    // ==================== nbotcfg 导入密码记忆 ====================

    /** 上次使用的 nbotcfg 导出/导入密码（用于自动填充，避免每次重新输入）。 */
    var lastNbotcfgPassword: String
        get() = prefs.getString(KEY_LAST_NBOTCFG_PWD, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_NBOTCFG_PWD, value).apply()
        }

    // ==================== 本地 DB Profile 切换 ====================

    /**
     * 本地 db profile：用于支持多 db 切换。
     * - name: db 文件名（不含扩展，如 "nekobot_local"）
     * - displayName: 显示名
     * - source: 来源（local / imported）
     * - createdAt: 创建时间戳
     */
    data class DbProfile(
        val name: String,
        val displayName: String,
        val source: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 当前激活的 db profile 名（不含扩展名）。默认 "nekobot_local"。 */
    var activeDbName: String
        get() = prefs.getString(KEY_ACTIVE_DB_NAME, DEFAULT_DB_NAME) ?: DEFAULT_DB_NAME
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_DB_NAME, value).apply()
        }

    /** 列出所有已记录的 db profile。 */
    fun listDbProfiles(): List<DbProfile> {
        val raw = prefs.getString(KEY_DB_PROFILES, null) ?: return listOf(
            DbProfile(DEFAULT_DB_NAME, "默认本地数据库", "local", 0L)
        )
        return try {
            val type = object : TypeToken<List<JsonObject>>() {}.type
            val list: List<JsonObject> = gson.fromJson(raw, type)
            val profiles = list.mapNotNull { obj ->
                try {
                    DbProfile(
                        name = obj.get("name")?.asString ?: return@mapNotNull null,
                        displayName = obj.get("displayName")?.asString ?: obj.get("name")?.asString ?: "",
                        source = obj.get("source")?.asString ?: "local",
                        createdAt = obj.get("createdAt")?.asLong ?: 0L
                    )
                } catch (_: Exception) { null }
            }.toMutableList()
            // 始终确保默认 profile 存在
            if (profiles.none { it.name == DEFAULT_DB_NAME }) {
                profiles.add(0, DbProfile(DEFAULT_DB_NAME, "默认本地数据库", "local", 0L))
            }
            profiles
        } catch (_: Exception) {
            listOf(DbProfile(DEFAULT_DB_NAME, "默认本地数据库", "local", 0L))
        }
    }

    /** 保存/新增一个 db profile（按 name 去重）。 */
    fun saveDbProfile(profile: DbProfile) {
        val current = listDbProfiles().toMutableList()
        current.removeAll { it.name == profile.name }
        current.add(profile)
        prefs.edit().putString(KEY_DB_PROFILES, gson.toJson(current)).apply()
    }

    /** 删除指定 db profile（默认 profile 不可删除）。 */
    fun removeDbProfile(name: String) {
        if (name == DEFAULT_DB_NAME) return
        val remaining = listDbProfiles().filterNot { it.name == name }
        prefs.edit().putString(KEY_DB_PROFILES, gson.toJson(remaining)).apply()
        if (activeDbName == name) {
            activeDbName = DEFAULT_DB_NAME
        }
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

    // ==================== 会话输入框草稿缓存 ====================

    /** 获取指定会话的输入框草稿（退出会话后保留） */
    fun getChatInputDraft(sessionId: String): String {
        return prefs.getString("chat_draft_$sessionId", "") ?: ""
    }

    /** 保存指定会话的输入框草稿 */
    fun setChatInputDraft(sessionId: String, text: String) {
        prefs.edit().putString("chat_draft_$sessionId", text).apply()
    }

    /** 清除指定会话的输入框草稿 */
    fun clearChatInputDraft(sessionId: String) {
        prefs.edit().remove("chat_draft_$sessionId").apply()
    }

    companion object {
        private const val PREF_NAME = "nekobot_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_LOGIN_RECORDS = "login_records"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_CHAT_INPUT_LAYOUT = "chat_input_layout"
        private const val KEY_STATS_DASHBOARD_ORDER = "stats_dashboard_widget_order"
        private const val KEY_STATS_DASHBOARD_HIDDEN = "stats_dashboard_hidden_widgets"
        private const val KEY_STATS_CHARACTER_RANKING_MODE = "stats_character_ranking_mode"
        private const val KEY_CHARACTER_VIEW_MODE = "character_view_mode"
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

        // DB Profile 相关 KEY
        const val KEY_ACTIVE_DB_NAME = "active_db_name"
        const val KEY_DB_PROFILES = "db_profiles"
        const val DEFAULT_DB_NAME = "nekobot_local"

        val DEFAULT_STATS_DASHBOARD_WIDGET_ORDER = listOf(
            "banner",
            "overview",
            "heatmap",
            "frequent_characters",
            "trend",
            "session_ranking",
            "model_ranking",
            "channels"
        )

        private val LEGACY_DEFAULT_STATS_DASHBOARD_WIDGET_ORDER = listOf(
            "banner",
            "overview",
            "frequent_characters",
            "heatmap",
            "trend",
            "session_ranking",
            "model_ranking",
            "channels"
        )

        // nbotcfg 导入密码记忆
        const val KEY_LAST_NBOTCFG_PWD = "last_nbotcfg_password"

        /**
         * 规范化服务器地址：
         * - 去掉首尾空白与末尾斜杠
         * - 自动补全缺失的 http:// scheme（防止 Retrofit baseUrl 解析崩溃）
         * - 自动为未加方括号的 IPv6 主机添加方括号（RFC 3986 要求）
         *
         * 例如：
         *   ::1:5000               → http://[::1]:5000
         *   192.168.1.1:5000       → http://192.168.1.1:5000
         *   example.com            → http://example.com
         *   http://::1:5000         → http://[::1]:5000
         *   http://2001:db8::1:5000 → http://[2001:db8::1]:5000
         *   http://[::1]:5000       → 保持不变
         *   http://192.168.1.1:5000 → 保持不变
         *   http://example.com      → 保持不变
         *
         * 启发式：authority 含 2 个及以上冒号视为 IPv6；
         * 最后一个冒号后为纯数字端口（2~5 位，1 位视为 IPv6 地址段如 ::1），
         * 且去掉端口后的 host 仍含冒号，视为 IPv6 + 端口；否则视为无端口 IPv6。
         */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            // 已包含方括号，无需处理 IPv6；但可能仍缺 scheme
            val withScheme = if (Regex("^(https?)://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                trimmed
            } else {
                "http://$trimmed"
            }
            if (withScheme.contains("[")) return withScheme
            // 提取 scheme:// 后的部分
            val schemeMatch = Regex("^(https?)://(.+)$", RegexOption.IGNORE_CASE).find(withScheme)
                ?: return withScheme
            val scheme = schemeMatch.groupValues[1].lowercase()
            val rest = schemeMatch.groupValues[2]
            // 分离 authority（host[:port]）和 path
            val slashIdx = rest.indexOf('/')
            val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
            val pathPart = if (slashIdx >= 0) rest.substring(slashIdx) else ""
            // authority 中冒号数量：普通 host:port 最多 1 个；IPv6 至少 2 个
            val colonCount = authority.count { it == ':' }
            if (colonCount <= 1) return withScheme
            // IPv6 地址（可能带端口）
            val lastColonIdx = authority.lastIndexOf(':')
            val afterLastColon = authority.substring(lastColonIdx + 1)
            // 端口要求 2~5 位数字；1 位数字（如 ::1 末段）视为 IPv6 地址段
            val isPort = afterLastColon.matches(Regex("^\\d{2,5}$"))
            return if (isPort) {
                val host = authority.substring(0, lastColonIdx)
                // host 仍含冒号 → IPv6 + 端口
                if (host.contains(':')) "$scheme://[$host]:$afterLastColon$pathPart" else withScheme
            } else {
                // 整个 authority 是 IPv6 地址，无端口
                "$scheme://[$authority]$pathPart"
            }
        }

        // 语言偏好：system（跟随系统）/ zh / en / ja / ko
        const val KEY_LANGUAGE = "language"
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_JA = "ja"
        const val LANGUAGE_KO = "ko"
    }

    /** 当前语言偏好：system / zh / en / ja / ko */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value).apply()
        }
}
