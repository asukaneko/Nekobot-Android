package com.nekobot.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nekobot.app.R

object Routes {
    const val QUICK_SETUP = "quick_setup"
    const val LOGIN = "login"

    // 底部导航主页面（4 个一级入口）
    const val SESSIONS = "sessions"
    /** 内容库：角色 + 世界书，页内分段切换。`tab` 指定初始分段（0 角色 / 1 世界书）。 */
    const val LIBRARY = "library?tab={tab}"
    fun library(tab: Int? = null): String = if (tab == null) "library" else "library?tab=$tab"
    /** 能力：智能与模型、Agent、记忆、扩展、用量入口。 */
    const val CAPABILITIES = "capabilities"
    /** 我的：外观偏好、数据、设置、关于。 */
    const val PROFILE = "profile"

    // 兼容旧入口（全局搜索、桌面快捷方式等仍可能指向原来的单页路由）
    const val CHARACTERS = "characters"
    const val WORLD_BOOKS = "world_books"
    const val TOKENS = "tokens"
    const val MORE = "more"
    const val GLOBAL_SEARCH = "global_search"

    // 详情页
    const val CHAT = "chat/{sessionId}"
    fun chat(sessionId: String) = "chat/$sessionId"

    const val CONTEXT_ANALYSIS = "context_analysis/{sessionId}"
    fun contextAnalysis(sessionId: String) = "context_analysis/$sessionId"

    const val SESSION_DETAIL = "session/{sessionId}"
    fun sessionDetail(id: String) = "session/$id"

    const val CHARACTER_DETAIL = "character/{characterId}"
    fun characterDetail(id: String) = "character/$id"

    const val CHARACTER_VIEW = "character_view/{characterId}"
    fun characterView(id: String) = "character_view/$id"

    const val WORLD_BOOK_DETAIL = "worldbook/{bookId}"
    fun worldBookDetail(id: String) = "worldbook/$id"

    /** 世界书命中调试（列表页顶部栏入口，作用于当前角色可见的全部世界书）。 */
    const val WORLD_BOOK_MATCH_DEBUG = "worldbook_match_debug"

    const val AI_CONFIG_CENTER = "ai_config_center"
    const val AI_CONFIG = "ai_config"
    const val AI_MODELS = "ai_models"
    const val AI_MODEL_EDITOR = "ai_model_editor/{local}?modelId={modelId}"
    fun aiModelEditor(local: Boolean, modelId: String? = null): String =
        "ai_model_editor/$local" + (modelId?.let { "?modelId=${android.net.Uri.encode(it)}" } ?: "")
    const val AI_FAILOVER = "ai_failover"
    const val LOCAL_AI_MODELS = "local_ai_models"
    const val MODEL_PROXY = "model_proxy"
    const val OAUTH_ACCOUNTS = "oauth_accounts"
    /** AI 模型余额查询 */
    const val AI_BALANCE = "ai_balance"
    const val SETTINGS = "settings"
    const val SYSTEM_SETTINGS = "system_settings"
    const val SYSTEM_OPERATIONS = "system_operations"
    const val AGENT_MEMORY = "agent_memory"
    const val STATE_HISTORY = "state_history"
    const val MEMORY = "memory"
    const val AGENT_CHARACTER_MEMORY = "agent_character_memory"
    const val COMPANION_CREATE = "companion_create"
    const val AGENT_CHARACTER_MEMORY_DETAIL = "agent_character_memory/{sessionId}"
    fun agentCharacterMemory(sessionId: String) = "agent_character_memory/${android.net.Uri.encode(sessionId)}"
    const val EXPERIENCE_ARCHIVE = "experience_archive/{sessionId}"
    fun experienceArchive(sessionId: String) = "experience_archive/$sessionId"
    const val EXPERIENCE_SOURCE = "experience_source/{sessionId}/{archiveId}"
    fun experienceSource(sessionId: String, archiveId: String) =
        "experience_source/${android.net.Uri.encode(sessionId)}/${android.net.Uri.encode(archiveId)}"
    const val CHAT_LOCATION = "chat_location/{sessionId}/{messageId}?archiveId={archiveId}"
    fun chatLocation(sessionId: String, messageId: String, archiveId: String? = null): String {
        val route = "chat_location/${android.net.Uri.encode(sessionId)}/${android.net.Uri.encode(messageId)}"
        return if (archiveId == null) route else "$route?archiveId=${android.net.Uri.encode(archiveId)}"
    }
    const val STYLE_SETTINGS = "style_settings"
    const val RELATIONSHIP_BACKGROUND = "relationship_background"

    const val WORKSPACE = "workspace/{sessionId}"
    fun workspace(sessionId: String) = "workspace/$sessionId"

    const val STORY_GRAPH = "story_graph/{sessionId}"
    fun storyGraph(sessionId: String) = "story_graph/$sessionId"

    const val WEBDAV_BACKUP = "webdav_backup"
    const val CONFIG_TRANSFER = "config_transfer"
    const val DATA_PORTABILITY = "data_portability"
    const val FEATURE_SWITCHES = "feature_switches"
    const val DATA_MAINTENANCE = "data_maintenance"
    /** 本地模式：会话工作区文件管理（查看 / 删除残留工作区文件） */
    const val WORKSPACE_MANAGER = "workspace_manager"
    /** Agent 设置 */
    const val AGENT_SETTINGS = "agent_settings"
    /** browser_use 浏览器工具配置（User-Agent / 视口 / 脚本与图片加载） */
    const val BROWSER_SETTINGS = "browser_settings"
    /** Linux 沙盒管理（apk / pip / npm 镜像源与 rootfs 重置） */
    const val SANDBOX_MANAGEMENT = "sandbox_management"
    const val SANDBOX_ENVIRONMENT = "sandbox_environment?sessionId={sessionId}"
    fun sandboxEnvironment(sessionId: String? = null): String = "sandbox_environment" +
        (sessionId?.let { "?sessionId=${android.net.Uri.encode(it)}" } ?: "")
    /** 路由决策历史 */
    const val ROUTING_HISTORY = "routing_history"
    /** A/B 测试配置 */
    const val AB_TEST_SETTINGS = "ab_test_settings"
    /** 日志查看（等级筛选 + 关键词搜索） */
    const val LOGS = "logs"
    /** 关于页面 */
    const val ABOUT = "about"
    const val DEVELOPER_OPTIONS = "developer_options"
    const val PERFORMANCE_MONITOR = "performance_monitor"
    /** 开源许可证页面 */
    const val LICENSE = "license"
    /** 隐私声明页面 */
    const val PRIVACY = "privacy"
    /** 本地模式：DB Profile 管理（导入 nbotcfg / 切换 / 删除） */
    const val DB_PROFILE = "db_profile"
    /** 自定义表情包管理（导入 / 重命名 / 删除） */
    const val STICKERS = "stickers"

    // 扩展功能聚合页 + 12 个模块（仅远程模式）
    const val EXTENSIONS = "extensions"
    const val ACHIEVEMENTS = "achievements"
    const val HOOKS = "hooks"
    const val TASK_CENTER = "task_center"
    const val WORKFLOWS = "workflows"
    const val KNOWLEDGE = "knowledge"
    /** RAG 检索设置页面 */
    const val RAG_SETTINGS = "rag_settings"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill_detail/{skillId}"
    fun skillDetail(skillName: String): String {
        val encoded = java.net.URLEncoder.encode(skillName, "UTF-8")
        return "skill_detail/$encoded"
    }
    const val TOOLS = "tools"
    const val MCP_SERVERS = "mcp_servers"
    const val CHANNELS = "channels"
    const val MESSAGE_FILTER = "message_filter"
    const val TTS_PLAYGROUND = "tts_playground"
    const val IMAGE_GENERATION_PLAYGROUND = "image_generation_playground"
    const val LOGIN_TOKENS = "login_tokens"
    const val API_KEYS = "api_keys"
    const val PLUGINS = "plugins"

    /** 插件页面宿主；sessionId / args 为空表示非会话命令触发。 */
    const val PLUGIN_PAGE =
        "plugin_page/{pluginId}/{pageId}?sessionId={sessionId}&args={args}&progressParent={progressParent}"
    fun pluginPage(
        pluginId: String,
        pageId: String,
        sessionId: String? = null,
        args: String? = null,
        progressParentId: String? = null
    ): String {
        val encodedPlugin = java.net.URLEncoder.encode(pluginId, "UTF-8")
        val encodedPage = java.net.URLEncoder.encode(pageId, "UTF-8")
        val params = buildList {
            if (!sessionId.isNullOrBlank()) {
                add("sessionId=${java.net.URLEncoder.encode(sessionId, "UTF-8")}")
            }
            if (!args.isNullOrBlank()) {
                add("args=${java.net.URLEncoder.encode(args, "UTF-8")}")
            }
            if (!progressParentId.isNullOrBlank()) {
                add("progressParent=${java.net.URLEncoder.encode(progressParentId, "UTF-8")}")
            }
        }
        val base = "plugin_page/$encodedPlugin/$encodedPage"
        return if (params.isEmpty()) base else "$base?" + params.joinToString("&")
    }

    // wenku8 内置浏览器登录页（自动提取 Cookie + UA）
    const val WENKU_LOGIN = "wenku_login"

    /** 运行诊断中心 */
    const val DIAGNOSTIC_CENTER = "diagnostic_center"
}

data class BottomItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/** 底栏路由顺序（非 Composable 场景使用，不含本地化标签）。 */
val bottomRoutes = listOf(
    Routes.SESSIONS, Routes.LIBRARY, Routes.CAPABILITIES, Routes.PROFILE
)

/** 底栏项目（Composable 场景使用，标签随语言切换）。 */
@Composable
fun bottomItems(): List<BottomItem> = listOf(
    BottomItem(Routes.SESSIONS, stringResource(R.string.nav_sessions), Icons.Outlined.ChatBubbleOutline),
    BottomItem(Routes.LIBRARY, stringResource(R.string.nav_library), Icons.Outlined.MenuBook),
    BottomItem(Routes.CAPABILITIES, stringResource(R.string.nav_capabilities), Icons.Outlined.AutoAwesome),
    BottomItem(Routes.PROFILE, stringResource(R.string.nav_profile), Icons.Outlined.PersonOutline),
)
