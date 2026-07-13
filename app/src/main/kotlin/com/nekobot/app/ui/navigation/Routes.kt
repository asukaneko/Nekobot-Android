package com.nekobot.app.ui.navigation

import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person

object Routes {
    const val LOGIN = "login"

    // 底部导航主页面
    const val SESSIONS = "sessions"
    const val CHARACTERS = "characters"
    const val WORLD_BOOKS = "world_books"
    const val TOKENS = "tokens"
    const val MORE = "more"

    // 详情页
    const val CHAT = "chat/{sessionId}"
    fun chat(sessionId: String) = "chat/$sessionId"

    const val SESSION_DETAIL = "session/{sessionId}"
    fun sessionDetail(id: String) = "session/$id"

    const val CHARACTER_DETAIL = "character/{characterId}"
    fun characterDetail(id: String) = "character/$id"

    const val WORLD_BOOK_DETAIL = "worldbook/{bookId}"
    fun worldBookDetail(id: String) = "worldbook/$id"

    const val AI_CONFIG_CENTER = "ai_config_center"
    const val AI_CONFIG = "ai_config"
    const val AI_MODELS = "ai_models"
    const val AI_FAILOVER = "ai_failover"
    const val LOCAL_AI_MODELS = "local_ai_models"
    const val SETTINGS = "settings"
    const val SYSTEM_SETTINGS = "system_settings"
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

    // 扩展功能聚合页 + 12 个模块（仅远程模式）
    const val EXTENSIONS = "extensions"
    const val HOOKS = "hooks"
    const val TASK_CENTER = "task_center"
    const val WORKFLOWS = "workflows"
    const val KNOWLEDGE = "knowledge"
    const val SKILLS = "skills"
    const val TOOLS = "tools"
    const val MCP_SERVERS = "mcp_servers"
    const val CHANNELS = "channels"
    const val MESSAGE_FILTER = "message_filter"
    const val TTS_PLAYGROUND = "tts_playground"
    const val LOGIN_TOKENS = "login_tokens"
    const val API_KEYS = "api_keys"
}

data class BottomItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

val bottomItems = listOf(
    BottomItem(Routes.SESSIONS, "会话", androidx.compose.material.icons.Icons.Filled.Chat),
    BottomItem(Routes.CHARACTERS, "角色", androidx.compose.material.icons.Icons.Filled.Person),
    BottomItem(Routes.WORLD_BOOKS, "世界书", androidx.compose.material.icons.Icons.Filled.MenuBook),
    BottomItem(Routes.TOKENS, "用量", androidx.compose.material.icons.Icons.Filled.BarChart),
    BottomItem(Routes.MORE, "更多", androidx.compose.material.icons.Icons.Filled.Apps),
)
