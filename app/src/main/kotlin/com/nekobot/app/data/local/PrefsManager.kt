package com.nekobot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.security.SecurePreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

enum class LivePipelineMode {
    CLASSIC,
    REALTIME;

    companion object {
        fun fromStorage(value: String?): LivePipelineMode =
            entries.firstOrNull { it.name == value } ?: CLASSIC
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
    private val securePrefs = SecurePreferenceStore(context)
    private val gson = Gson()

    var serverUrl: String
        get() = normalizeServerUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER)
        set(value) {
            // 规范化：去掉末尾斜杠 + 自动为 IPv6 主机加方括号
            prefs.edit().putString(KEY_SERVER_URL, normalizeServerUrl(value)).apply()
        }

    var token: String?
        get() = securePrefs.getString(KEY_TOKEN, prefs)
        set(value) {
            securePrefs.putString(KEY_TOKEN, value)
            prefs.edit().remove(KEY_TOKEN).apply()
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
        val raw = securePrefs.getString(KEY_LOGIN_RECORDS, prefs) ?: return emptyList()
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
        securePrefs.putString(KEY_LOGIN_RECORDS, gson.toJson(toSave))
        prefs.edit().remove(KEY_LOGIN_RECORDS).apply()
    }

    /** 删除指定登录记录 */
    fun removeLoginRecord(server: String, user: String) {
        val normalized = normalizeServerUrl(server)
        val remaining = listLoginRecords().filterNot { it.serverUrl == normalized && it.username == user }
        securePrefs.putString(KEY_LOGIN_RECORDS, gson.toJson(remaining))
        prefs.edit().remove(KEY_LOGIN_RECORDS).apply()
    }

    /** 清空所有登录记录 */
    fun clearLoginRecords() {
        securePrefs.remove(KEY_LOGIN_RECORDS, prefs)
    }

    /** 运行模式：本地 / 服务器 */
    var appMode: AppMode
        get() {
            val raw = prefs.getString(KEY_APP_MODE, AppMode.LOCAL.name) ?: AppMode.LOCAL.name
            return runCatching { AppMode.valueOf(raw) }.getOrDefault(AppMode.LOCAL)
        }
        set(value) {
            prefs.edit().putString(KEY_APP_MODE, value.name).apply()
        }

    val isLocalMode: Boolean get() = appMode.isLocal

    /** 首次快速配置是否已经完成。已有模式或登录信息的用户不会再次看到引导。 */
    var quickSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_QUICK_SETUP_COMPLETED, hasExistingConfiguration())
        set(value) {
            prefs.edit().putBoolean(KEY_QUICK_SETUP_COMPLETED, value).apply()
        }

    private fun hasExistingConfiguration(): Boolean =
        prefs.contains(KEY_APP_MODE) ||
            prefs.contains(KEY_SERVER_URL) ||
            prefs.contains(KEY_USERNAME) ||
            !token.isNullOrBlank() ||
            listLoginRecords().isNotEmpty()

    /** Live 对话链路：传统 STT→LLM→TTS，或 Realtime 原生语音→语音。 */
    var livePipelineMode: LivePipelineMode
        get() = LivePipelineMode.fromStorage(prefs.getString(KEY_LIVE_PIPELINE_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_LIVE_PIPELINE_MODE, value.name).apply()
        }

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

    /** 首页“最近会话”是否包含手动归档的会话。 */
    private val _recentSessionsIncludeArchived = MutableStateFlow(
        prefs.getBoolean(KEY_RECENT_SESSIONS_INCLUDE_ARCHIVED, false)
    )
    val recentSessionsIncludeArchivedFlow: StateFlow<Boolean> =
        _recentSessionsIncludeArchived.asStateFlow()

    var recentSessionsIncludeArchived: Boolean
        get() = _recentSessionsIncludeArchived.value
        set(value) {
            _recentSessionsIncludeArchived.value = value
            prefs.edit().putBoolean(KEY_RECENT_SESSIONS_INCLUDE_ARCHIVED, value).apply()
        }

    private val _openLatestSessionOnLaunch = MutableStateFlow(
        prefs.getBoolean(KEY_OPEN_LATEST_SESSION_ON_LAUNCH, false)
    )
    val openLatestSessionOnLaunchFlow: StateFlow<Boolean> =
        _openLatestSessionOnLaunch.asStateFlow()

    var openLatestSessionOnLaunch: Boolean
        get() = _openLatestSessionOnLaunch.value
        set(value) {
            _openLatestSessionOnLaunch.value = value
            prefs.edit().putBoolean(KEY_OPEN_LATEST_SESSION_ON_LAUNCH, value).apply()
        }

    /** 根据费用、延迟、上下文和任务复杂度动态调整本地聊天模型顺序。 */
    var smartRoutingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_ROUTING_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SMART_ROUTING_ENABLED, value).apply()
        }

    /** 每日模型费用预算（美元）；0 表示不限制。 */
    var smartRoutingDailyBudgetUsd: Double
        get() = prefs.getString(KEY_SMART_ROUTING_DAILY_BUDGET, "0")
            ?.toDoubleOrNull()
            ?.coerceAtLeast(0.0)
            ?: 0.0
        set(value) {
            prefs.edit()
                .putString(KEY_SMART_ROUTING_DAILY_BUDGET, value.coerceAtLeast(0.0).toString())
                .apply()
        }

    var smartRoutingBudgetAlertState: String
        get() = prefs.getString(KEY_SMART_ROUTING_BUDGET_ALERT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SMART_ROUTING_BUDGET_ALERT, value).apply()
        }

    // ==================== RAG 检索配置 ====================

    /** 语义检索权重（0.0~1.0） */
    var ragSemanticWeight: Float
        get() = prefs.getFloat(KEY_RAG_SEMANTIC_WEIGHT, 0.88f)
        set(value) = prefs.edit().putFloat(KEY_RAG_SEMANTIC_WEIGHT, value.coerceIn(0f, 1f)).apply()

    /** 最终返回结果数 */
    var ragTopK: Int
        get() = prefs.getInt(KEY_RAG_TOP_K, 5)
        set(value) = prefs.edit().putInt(KEY_RAG_TOP_K, value.coerceIn(1, 20)).apply()

    /** MMR 多样性系数 */
    var ragMmrLambda: Float
        get() = prefs.getFloat(KEY_RAG_MMR_LAMBDA, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_RAG_MMR_LAMBDA, value.coerceIn(0f, 1f)).apply()

    /** 是否启用重排 */
    var ragRerankEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAG_RERANK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RAG_RERANK_ENABLED, value).apply()

    /** 最低得分阈值 */
    var ragScoreThreshold: Float
        get() = prefs.getFloat(KEY_RAG_SCORE_THRESHOLD, 0.01f)
        set(value) = prefs.edit().putFloat(KEY_RAG_SCORE_THRESHOLD, value.coerceAtLeast(0f)).apply()

    /** 是否启用引用标注 */
    var ragCitationEnabled: Boolean
        get() = prefs.getBoolean(KEY_RAG_CITATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RAG_CITATION_ENABLED, value).apply()

    /** 读取完整 RAG 配置 */
    fun getRagConfig() = com.nekobot.app.data.local.knowledge.RagConfig(
        semanticWeight = ragSemanticWeight,
        topK = ragTopK,
        mmrLambda = ragMmrLambda,
        rerankEnabled = ragRerankEnabled,
        scoreThreshold = ragScoreThreshold,
        citationEnabled = ragCitationEnabled
    )

    // ==================== A/B 测试配置 ====================

    var abTestEnabled: Boolean
        get() = prefs.getBoolean(KEY_AB_TEST_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AB_TEST_ENABLED, value).apply()

    var abTestSplitRatio: Float
        get() = prefs.getFloat(KEY_AB_TEST_SPLIT_RATIO, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_AB_TEST_SPLIT_RATIO, value.coerceIn(0f, 1f)).apply()

    var abTestControlModelId: String?
        get() = prefs.getString(KEY_AB_TEST_CONTROL_MODEL, null)
        set(value) = prefs.edit().putString(KEY_AB_TEST_CONTROL_MODEL, value).apply()

    var abTestExperimentModelId: String?
        get() = prefs.getString(KEY_AB_TEST_EXPERIMENT_MODEL, null)
        set(value) = prefs.edit().putString(KEY_AB_TEST_EXPERIMENT_MODEL, value).apply()

    var abTestName: String
        get() = prefs.getString(KEY_AB_TEST_NAME, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_AB_TEST_NAME, value).apply()

    fun getAbTestConfig() = com.nekobot.app.data.local.ai.AbTestConfig(
        enabled = abTestEnabled,
        splitRatio = abTestSplitRatio,
        controlModelId = abTestControlModelId,
        experimentModelId = abTestExperimentModelId,
        testName = abTestName
    )

    // ==================== 无障碍配置 ====================

    /** 是否跟随系统字号设置 */
    var followSystemFontScale: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SYSTEM_FONT, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM_FONT, value).apply()

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
        get() {
            val savedHidden = prefs.getStringSet(KEY_STATS_DASHBOARD_HIDDEN, null)
            val savedVersion = prefs.getInt(KEY_STATS_DASHBOARD_HIDDEN_VERSION, 0)
            val validWidgets = DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.toSet()

            // 新用户：按默认隐藏列表初始化
            if (savedHidden == null) {
                val initialHidden = DEFAULT_HIDDEN_WIDGETS.filter { it in validWidgets }.toSet()
                prefs.edit()
                    .putStringSet(KEY_STATS_DASHBOARD_HIDDEN, initialHidden)
                    .putInt(KEY_STATS_DASHBOARD_HIDDEN_VERSION, STATS_DASHBOARD_WIDGET_VERSION)
                    .apply()
                return initialHidden
            }

            val hidden = savedHidden.filterTo(linkedSetOf()) { it in validWidgets }

            // 老用户升级：将新增的小组件自动加入隐藏列表
            if (savedVersion < STATS_DASHBOARD_WIDGET_VERSION) {
                val newWidgets = (savedVersion + 1..STATS_DASHBOARD_WIDGET_VERSION)
                    .flatMap { WIDGETS_ADDED_IN_VERSION[it].orEmpty() }
                    .filter { it in validWidgets }
                hidden.addAll(newWidgets)
                prefs.edit()
                    .putStringSet(KEY_STATS_DASHBOARD_HIDDEN, hidden)
                    .putInt(KEY_STATS_DASHBOARD_HIDDEN_VERSION, STATS_DASHBOARD_WIDGET_VERSION)
                    .apply()
            }

            return hidden
        }
        set(value) {
            val validWidgets = DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.toSet()
            prefs.edit()
                .putStringSet(
                    KEY_STATS_DASHBOARD_HIDDEN,
                    value.filterTo(linkedSetOf()) { it in validWidgets }
                )
                .putInt(KEY_STATS_DASHBOARD_HIDDEN_VERSION, STATS_DASHBOARD_WIDGET_VERSION)
                .apply()
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

    /** 世界书列表视图模式：LIST / GRID，持久化用户选择 */
    var worldBookViewMode: String
        get() = prefs.getString(KEY_WORLD_BOOK_VIEW_MODE, "LIST") ?: "LIST"
        set(value) {
            prefs.edit().putString(KEY_WORLD_BOOK_VIEW_MODE, if (value == "GRID") "GRID" else "LIST").apply()
        }

    /** 成就收藏视图模式：STANDARD / GRID_2X2，持久化用户选择。 */
    var achievementViewMode: String
        get() = prefs.getString(KEY_ACHIEVEMENT_VIEW_MODE, "STANDARD") ?: "STANDARD"
        set(value) {
            val normalized = if (value == "GRID_2X2") "GRID_2X2" else "STANDARD"
            prefs.edit().putString(KEY_ACHIEVEMENT_VIEW_MODE, normalized).apply()
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

    /** 字体缩放因子：以 16sp 正文字号为 1.0，可通过样式页按 1sp 精确调整。 */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()
        }

    /** 聊天背景来源：关闭 / 跟随当前会话立绘 / 自定义图片。 */
    var chatBackgroundMode: String
        get() = prefs.getString(KEY_CHAT_BACKGROUND_MODE, CHAT_BACKGROUND_NONE)
            ?.takeIf { it in CHAT_BACKGROUND_MODES }
            ?: CHAT_BACKGROUND_NONE
        set(value) {
            val normalized = value.takeIf { it in CHAT_BACKGROUND_MODES } ?: CHAT_BACKGROUND_NONE
            prefs.edit().putString(KEY_CHAT_BACKGROUND_MODE, normalized).apply()
        }

    /** 已复制到应用私有目录的自定义聊天背景路径。 */
    var customChatBackgroundPath: String?
        get() = prefs.getString(KEY_CUSTOM_CHAT_BACKGROUND_PATH, null)
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_CHAT_BACKGROUND_PATH, value).apply()
        }

    /** 自定义聊天背景原始文件名，仅用于设置页展示。 */
    var customChatBackgroundName: String?
        get() = prefs.getString(KEY_CUSTOM_CHAT_BACKGROUND_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_CHAT_BACKGROUND_NAME, value).apply()
        }

    /** 聊天背景显示强度，限制在 10%~60%，避免图片影响消息可读性。 */
    var chatBackgroundOpacity: Float
        get() = prefs.getFloat(KEY_CHAT_BACKGROUND_OPACITY, DEFAULT_CHAT_BACKGROUND_OPACITY)
            .coerceIn(MIN_CHAT_BACKGROUND_OPACITY, MAX_CHAT_BACKGROUND_OPACITY)
        set(value) {
            prefs.edit().putFloat(
                KEY_CHAT_BACKGROUND_OPACITY,
                value.coerceIn(MIN_CHAT_BACKGROUND_OPACITY, MAX_CHAT_BACKGROUND_OPACITY)
            ).apply()
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
        securePrefs.remove(KEY_TOKEN, prefs)
    }

    /** 应用启动时主动迁移旧版本明文凭据，避免必须先打开对应页面才完成迁移。 */
    fun migrateSensitivePreferences() {
        token
        listLoginRecords()
        lastNbotcfgPassword
        wenku8Cookie
    }

    // ==================== nbotcfg 导入密码记忆 ====================

    /** 上次使用的 nbotcfg 导出/导入密码（用于自动填充，避免每次重新输入）。 */
    var lastNbotcfgPassword: String
        get() = securePrefs.getString(KEY_LAST_NBOTCFG_PWD, prefs).orEmpty()
        set(value) {
            securePrefs.putString(KEY_LAST_NBOTCFG_PWD, value.takeIf(String::isNotEmpty))
            prefs.edit().remove(KEY_LAST_NBOTCFG_PWD).apply()
        }

    // ==================== wenku8 轻小说 Cookie ====================

    /**
     * wenku8.net 登录 Cookie，用于 `/findbook`、`/hotnovel` 等轻小说命令。
     * 由 `/set_wenku_cookie` 命令写入；未设置时这些命令会提示先配置。
     */
    var wenku8Cookie: String
        get() = securePrefs.getString(KEY_WENKU8_COOKIE, prefs).orEmpty()
        set(value) {
            securePrefs.putString(KEY_WENKU8_COOKIE, value.takeIf(String::isNotEmpty))
            prefs.edit().remove(KEY_WENKU8_COOKIE).apply()
        }

    /** Exa MCP 网页搜索使用的可选 API Key，留空时按匿名方式请求。 */
    var exaApiKey: String
        get() = securePrefs.getString(KEY_EXA_API_KEY, prefs).orEmpty()
        set(value) {
            securePrefs.putString(KEY_EXA_API_KEY, value.takeIf(String::isNotEmpty))
            prefs.edit().remove(KEY_EXA_API_KEY).apply()
        }

    /**
     * wenku8 自定义 User-Agent。
     *
     * CloudFlare 的 cf_clearance 绑定获取时的 IP + UA，需保持一致。
     * 由 `/set_wenku_cookie <Cookie> || <UA>` 或内置 WebView 登录页写入。
     */
    var wenku8UserAgent: String
        get() = prefs.getString(KEY_WENKU8_USER_AGENT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_WENKU8_USER_AGENT, value).apply()
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

    /** 获取全局思考强度；新会话和已有会话统一使用此值。 */
    fun getReasoningEffort(): com.nekobot.app.data.model.ReasoningEffort =
        com.nekobot.app.data.model.ReasoningEffort.fromValue(
            prefs.getString(KEY_REASONING_EFFORT, null)
        )

    /** 持久化全局思考强度。 */
    fun setReasoningEffort(effort: com.nekobot.app.data.model.ReasoningEffort) {
        prefs.edit().putString(KEY_REASONING_EFFORT, effort.wireValue).apply()
    }

    /** 兼容旧调用方：思考强度已从会话级改为全局。 */
    @Deprecated("思考强度现在是全局设置")
    fun getSessionReasoningEffort(@Suppress("UNUSED_PARAMETER") sessionId: String) =
        getReasoningEffort()

    /** 兼容旧调用方：思考强度已从会话级改为全局。 */
    @Deprecated("思考强度现在是全局设置")
    fun setSessionReasoningEffort(
        @Suppress("UNUSED_PARAMETER") sessionId: String,
        effort: com.nekobot.app.data.model.ReasoningEffort
    ) = setReasoningEffort(effort)

    /** 读取会话自动命名进度；没有旧记录时返回 null，由命名器从当前消息数恢复。 */
    fun getSessionAutoNamingState(sessionId: String): Pair<Boolean, Int>? {
        val countKey = "session_auto_name_count_$sessionId"
        if (!prefs.contains(countKey)) return null
        return prefs.getBoolean("session_auto_named_$sessionId", false) to
            prefs.getInt(countKey, 0).coerceAtLeast(0)
    }

    /** 持久化自动命名进度，避免进程或页面重建后只完成首次命名。 */
    fun setSessionAutoNamingState(sessionId: String, autoNamed: Boolean, messageCount: Int) {
        prefs.edit()
            .putBoolean("session_auto_named_$sessionId", autoNamed)
            .putInt("session_auto_name_count_$sessionId", messageCount.coerceAtLeast(0))
            .apply()
    }

    companion object {
        private const val PREF_NAME = "nekobot_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_QUICK_SETUP_COMPLETED = "quick_setup_completed"
        private const val KEY_LOGIN_RECORDS = "login_records"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_CHAT_INPUT_LAYOUT = "chat_input_layout"
        private const val KEY_LIVE_PIPELINE_MODE = "live_pipeline_mode"
        private const val KEY_RECENT_SESSIONS_INCLUDE_ARCHIVED = "recent_sessions_include_archived"
        private const val KEY_OPEN_LATEST_SESSION_ON_LAUNCH = "open_latest_session_on_launch"
        private const val KEY_SMART_ROUTING_ENABLED = "smart_routing_enabled"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_SMART_ROUTING_DAILY_BUDGET = "smart_routing_daily_budget"
        private const val KEY_SMART_ROUTING_BUDGET_ALERT = "smart_routing_budget_alert"
        private const val KEY_RAG_SEMANTIC_WEIGHT = "rag_semantic_weight"
        private const val KEY_RAG_TOP_K = "rag_top_k"
        private const val KEY_RAG_MMR_LAMBDA = "rag_mmr_lambda"
        private const val KEY_RAG_RERANK_ENABLED = "rag_rerank_enabled"
        private const val KEY_RAG_SCORE_THRESHOLD = "rag_score_threshold"
        private const val KEY_RAG_CITATION_ENABLED = "rag_citation_enabled"
        private const val KEY_AB_TEST_ENABLED = "ab_test_enabled"
        private const val KEY_AB_TEST_SPLIT_RATIO = "ab_test_split_ratio"
        private const val KEY_AB_TEST_CONTROL_MODEL = "ab_test_control_model"
        private const val KEY_AB_TEST_EXPERIMENT_MODEL = "ab_test_experiment_model"
        private const val KEY_AB_TEST_NAME = "ab_test_name"
        private const val KEY_FOLLOW_SYSTEM_FONT = "follow_system_font"
        private const val KEY_STATS_DASHBOARD_ORDER = "stats_dashboard_widget_order"
        private const val KEY_STATS_DASHBOARD_HIDDEN = "stats_dashboard_hidden_widgets"
        private const val KEY_STATS_DASHBOARD_HIDDEN_VERSION = "stats_dashboard_hidden_version"
        private const val KEY_STATS_CHARACTER_RANKING_MODE = "stats_character_ranking_mode"
        private const val KEY_CHARACTER_VIEW_MODE = "character_view_mode"
        private const val KEY_WORLD_BOOK_VIEW_MODE = "world_book_view_mode"
        private const val KEY_ACHIEVEMENT_VIEW_MODE = "achievement_view_mode"
        private const val DEFAULT_SERVER = "https://localhost:5000"

        // 样式相关 KEY
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_COLOR = "font_color"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        const val KEY_CHAT_BACKGROUND_MODE = "chat_background_mode"
        const val KEY_CUSTOM_CHAT_BACKGROUND_PATH = "custom_chat_background_path"
        const val KEY_CUSTOM_CHAT_BACKGROUND_NAME = "custom_chat_background_name"
        const val KEY_CHAT_BACKGROUND_OPACITY = "chat_background_opacity"

        // 字体类型可选值
        const val FONT_FAMILY_SYSTEM = "system"
        const val FONT_FAMILY_SERIF = "serif"
        const val FONT_FAMILY_MONOSPACE = "monospace"
        const val FONT_FAMILY_ROUNDED = "rounded"
        const val FONT_FAMILY_CUSTOM = "custom"

        // 精确字号范围；Typography 以 16sp 正文为基准按比例缩放其他文本样式。
        const val DEFAULT_BODY_FONT_SP = 16f
        const val MIN_BODY_FONT_SP = 12f
        const val MAX_BODY_FONT_SP = 24f

        // 聊天背景来源可选值
        const val CHAT_BACKGROUND_NONE = "none"
        const val CHAT_BACKGROUND_PORTRAIT = "portrait"
        const val CHAT_BACKGROUND_CUSTOM = "custom"
        val CHAT_BACKGROUND_MODES = setOf(
            CHAT_BACKGROUND_NONE,
            CHAT_BACKGROUND_PORTRAIT,
            CHAT_BACKGROUND_CUSTOM
        )
        const val DEFAULT_CHAT_BACKGROUND_OPACITY = 0.30f
        const val MIN_CHAT_BACKGROUND_OPACITY = 0.10f
        const val MAX_CHAT_BACKGROUND_OPACITY = 0.60f

        /** 将历史 fontScale 转成设置页展示的正文 sp。 */
        fun fontScaleToBodySp(scale: Float): Float =
            (scale * DEFAULT_BODY_FONT_SP).coerceIn(MIN_BODY_FONT_SP, MAX_BODY_FONT_SP)

        /** 将用户选择的正文 sp 转回全局 Typography 使用的缩放倍率。 */
        fun bodySpToFontScale(bodySp: Float): Float =
            bodySp.coerceIn(MIN_BODY_FONT_SP, MAX_BODY_FONT_SP) / DEFAULT_BODY_FONT_SP

        /** 根据背景模式选择原始图片路径；路径解析由 UI 层统一处理。 */
        fun selectChatBackgroundPath(
            mode: String,
            portraitPath: String?,
            customPath: String?
        ): String? = when (mode) {
            CHAT_BACKGROUND_PORTRAIT -> portraitPath?.takeIf { it.isNotBlank() }
            CHAT_BACKGROUND_CUSTOM -> customPath?.takeIf { it.isNotBlank() }
            else -> null
        }

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
            "channels",
            "recent_sessions",
            "token_ratio",
            "week_comparison",
            "quick_actions",
            "character_discovery",
            "webdav_status",
            "local_log_preview",
            "achievements"
        )

        /**
         * 负一屏小组件版本号。
         * 每次新增小组件时递增，并在 [WIDGETS_ADDED_IN_VERSION] 中登记，
         * 使老用户升级后新组件默认隐藏，新用户按 [DEFAULT_HIDDEN_WIDGETS] 默认隐藏。
         */
        const val STATS_DASHBOARD_WIDGET_VERSION = 3

        /**
         * 每个版本新增的小组件 ID。用于 hidden 状态迁移。
         */
        val WIDGETS_ADDED_IN_VERSION = mapOf(
            1 to listOf("recent_sessions", "token_ratio", "week_comparison", "quick_actions"),
            2 to listOf("character_discovery"),
            3 to listOf("webdav_status", "local_log_preview", "achievements")
        )

        /**
         * 默认隐藏的小组件。新用户首次读取时使用。
         */
        val DEFAULT_HIDDEN_WIDGETS = WIDGETS_ADDED_IN_VERSION.values.flatten().toSet()

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
        private const val KEY_EXA_API_KEY = "exa_api_key"

        // wenku8 轻小说 Cookie
        const val KEY_WENKU8_COOKIE = "wenku8_cookie"

        // wenku8 自定义 User-Agent
        const val KEY_WENKU8_USER_AGENT = "wenku8_user_agent"

        /**
         * 规范化服务器地址：
         * - 去掉首尾空白与末尾斜杠
         * - 自动补全缺失的 https:// scheme（防止 Retrofit baseUrl 解析崩溃并默认使用加密传输）
         * - 自动为未加方括号的 IPv6 主机添加方括号（RFC 3986 要求）
         *
         * 例如：
         *   ::1:5000               → https://[::1]:5000
         *   192.168.1.1:5000       → https://192.168.1.1:5000
         *   example.com            → https://example.com
         *   http://::1:5000         → https://[::1]:5000
         *   http://2001:db8::1:5000 → https://[2001:db8::1]:5000
         *   http://[::1]:5000       → https://[::1]:5000
         *   http://192.168.1.1:5000 → https://192.168.1.1:5000
         *   http://example.com      → https://example.com
         *
         * 启发式：authority 含 2 个及以上冒号视为 IPv6；
         * 最后一个冒号后为纯数字端口（2~5 位，1 位视为 IPv6 地址段如 ::1），
         * 且去掉端口后的 host 仍含冒号，视为 IPv6 + 端口；否则视为无端口 IPv6。
         */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            // 已包含方括号，无需处理 IPv6；但可能仍缺 scheme
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true)) {
                "https://${trimmed.substringAfter("://")}"
            } else if (trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                "https://$trimmed"
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
