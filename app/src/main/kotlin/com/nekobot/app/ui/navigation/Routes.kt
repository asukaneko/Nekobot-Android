package com.nekobot.app.ui.navigation

import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nekobot.app.R

object Routes {
    const val LOGIN = "login"

    // 底部导航主页面
    const val SESSIONS = "sessions"
    const val CHARACTERS = "characters"
    const val WORLD_BOOKS = "world_books"
    const val TOKENS = "tokens"
    const val MORE = "more"
    const val GLOBAL_SEARCH = "global_search"

    // 详情页
    const val CHAT = "chat/{sessionId}"
    fun chat(sessionId: String) = "chat/$sessionId"

    const val SESSION_DETAIL = "session/{sessionId}"
    fun sessionDetail(id: String) = "session/$id"

    const val CHARACTER_DETAIL = "character/{characterId}"
    fun characterDetail(id: String) = "character/$id"

    const val CHARACTER_VIEW = "character_view/{characterId}"
    fun characterView(id: String) = "character_view/$id"

    const val WORLD_BOOK_DETAIL = "worldbook/{bookId}"
    fun worldBookDetail(id: String) = "worldbook/$id"

    const val AI_CONFIG_CENTER = "ai_config_center"
    const val AI_CONFIG = "ai_config"
    const val AI_MODELS = "ai_models"
    const val AI_FAILOVER = "ai_failover"
    const val LOCAL_AI_MODELS = "local_ai_models"
    const val MODEL_PROXY = "model_proxy"
    const val OAUTH_ACCOUNTS = "oauth_accounts"
    const val SETTINGS = "settings"
    const val SYSTEM_SETTINGS = "system_settings"
    const val SYSTEM_OPERATIONS = "system_operations"
    const val AGENT_MEMORY = "agent_memory"
    const val STATE_HISTORY = "state_history"
    const val MEMORY = "memory"
    const val STYLE_SETTINGS = "style_settings"

    const val WORKSPACE = "workspace/{sessionId}"
    fun workspace(sessionId: String) = "workspace/$sessionId"

    const val STORY_GRAPH = "story_graph/{sessionId}"
    fun storyGraph(sessionId: String) = "story_graph/$sessionId"

    const val WEBDAV_BACKUP = "webdav_backup"
    const val CONFIG_TRANSFER = "config_transfer"
    const val FEATURE_SWITCHES = "feature_switches"
    const val DATA_MAINTENANCE = "data_maintenance"
    /** 路由决策历史 */
    const val ROUTING_HISTORY = "routing_history"
    /** A/B 测试配置 */
    const val AB_TEST_SETTINGS = "ab_test_settings"
    /** 关于页面 */
    const val ABOUT = "about"
    /** 开源许可证页面 */
    const val LICENSE = "license"
    /** 隐私声明页面 */
    const val PRIVACY = "privacy"
    /** 本地模式：DB Profile 管理（导入 nbotcfg / 切换 / 删除） */
    const val DB_PROFILE = "db_profile"

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
    Routes.SESSIONS, Routes.CHARACTERS, Routes.WORLD_BOOKS, Routes.TOKENS, Routes.MORE
)

/** 底栏项目（Composable 场景使用，标签随语言切换）。 */
@Composable
fun bottomItems(): List<BottomItem> = listOf(
    BottomItem(Routes.SESSIONS, stringResource(R.string.nav_sessions), androidx.compose.material.icons.Icons.Filled.Chat),
    BottomItem(Routes.CHARACTERS, stringResource(R.string.nav_characters), androidx.compose.material.icons.Icons.Filled.Person),
    BottomItem(Routes.WORLD_BOOKS, stringResource(R.string.nav_world_books), androidx.compose.material.icons.Icons.Filled.MenuBook),
    BottomItem(Routes.TOKENS, stringResource(R.string.nav_tokens), androidx.compose.material.icons.Icons.Filled.BarChart),
    BottomItem(Routes.MORE, stringResource(R.string.nav_more), androidx.compose.material.icons.Icons.Filled.Apps),
)
