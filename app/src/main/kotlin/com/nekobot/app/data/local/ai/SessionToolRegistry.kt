package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Agent 会话工具集选择。
 *
 * 将本地可执行的工具按“大类”分类，UI 先选择大类，再点进大类细化到单个工具。
 * 选中结果是“当前会话 Agent 可以使用哪些工具”，默认全部启用（保持既有行为）。
 *
 * 每个会话的选中结果持久化到 SharedPreferences（键 `session_toolset_<sessionId>`），
 * 与 /yolo 不同，工具集选择跨进程保留，便于用户长期控制当前会话的能力边界。
 */
object SessionToolCatalog {

    /** 工具大类：id 用于持久化，titleRes 仅供 UI 层映射字符串资源。 */
    data class Category(
        val id: String,
        val toolIds: List<String>
    )

    val categories: List<Category> = listOf(
        // 基础查询 / 网络检索
        Category(
            id = "basic",
            toolIds = listOf(
                "get_weather",
                "search_web",
                "http_get",
                "get_date_time",
                "download_file"
            )
        ),
        // 浏览器使用
        Category(id = "browser", toolIds = listOf("browser_use")),
        // Linux 沙盒命令与文件
        Category(
            id = "linux",
            toolIds = listOf(
                "exec_command",
                "file_read",
                "file_write",
                "file_edit"
            )
        ),
        // 会话工作区
        Category(
            id = "workspace",
            toolIds = listOf(
                "workspace_create_file",
                "workspace_read_file",
                "workspace_edit_file",
                "workspace_delete_file",
                "workspace_list_files",
                "workspace_send_file",
                "workspace_parse_file",
                "workspace_extract_epub",
                "workspace_file_info",
                "workspace_skill_copy"
            )
        ),
        // 图片能力
        Category(
            id = "image",
            toolIds = listOf(
                "understand_image",
                "generate_image",
                "read_image"
            )
        ),
        // 记忆
        Category(
            id = "memory",
            toolIds = listOf(
                "save_to_memory",
                "read_memory",
                "agent_memory_read",
                "agent_memory_update"
            )
        ),
        // 任务与提问
        Category(
            id = "task",
            toolIds = listOf(
                "todo_write",
                "ask_user_question",
                "get_session_thinking_history",
                "send_message"
            )
        ),
        // 插件与技能
        Category(
            id = "plugin",
            toolIds = listOf(
                "plugin_use",
                "skill_list",
                "skill_view",
                "skill_read",
                "skill_get_info"
            )
        ),
        // Android 系统操作
        Category(
            id = "android",
            toolIds = listOf(
                "android_help",
                "android_device_info",
                "android_battery_status",
                "android_clipboard_read",
                "android_clipboard_write",
                "android_open_url",
                "android_list_apps",
                "android_open_app",
                "android_open_settings",
                "android_create_calendar_event",
                "android_set_alarm",
                "android_volume",
                "android_accessibility_status",
                "android_ui_tree",
                "android_ui_click",
                "android_ui_set_text",
                "android_ui_scroll",
                "android_ui_tap",
                "android_ui_swipe",
                "android_ui_ime_action",
                "android_ui_paste",
                "android_wait_for_idle",
                "android_global_action",
                "android_screenshot",
                "android_step",
                "android_notifications",
                "android_notification_action",
                "android_media_control"
            )
        ),
        // 数据管理（数据库工具）
        Category(
            id = "db",
            toolIds = listOf(
                "db_list_characters", "db_get_character", "db_create_character",
                "db_update_character", "db_delete_character",
                "db_list_world_books", "db_get_world_book", "db_create_world_book",
                "db_update_world_book", "db_delete_world_book",
                "db_upsert_world_book_entry", "db_delete_world_book_entry",
                "db_token_stats", "db_token_rankings", "db_session_token_usage",
                "db_list_memories", "db_save_memory", "db_delete_memory",
                "db_list_state_history", "db_get_latest_state",
                "db_list_hooks", "db_create_hook", "db_update_hook",
                "db_delete_hook", "db_toggle_hook",
                "db_list_workflows", "db_create_workflow", "db_update_workflow",
                "db_delete_workflow", "db_list_tasks", "db_create_task",
                "db_update_task", "db_delete_task",
                "db_list_skills", "db_create_skill", "db_update_skill",
                "db_delete_skill", "db_toggle_skill",
                "db_list_ai_models", "db_get_ai_model", "db_create_ai_model",
                "db_update_ai_model", "db_delete_ai_model", "db_set_active_model",
                "db_get_ai_config", "db_update_ai_config"
            )
        )
    )

    /** 所有已归类工具的 id 集合（用于判断某个工具是否属于工具集可管理范围）。 */
    val ALL_TOOL_IDS: Set<String> =
        categories.flatMap { it.toolIds }.toSet()

    /** 所有大类 id（顺序即展示顺序）。 */
    val allCategoryIds: List<String> = categories.map { it.id }

    fun categoryById(id: String): Category? = categories.firstOrNull { it.id == id }

    /** 返回某个工具所属的大类 id；不属于任何大类时返回 null。 */
    fun categoryIdOf(toolId: String): String? =
        categories.firstOrNull { toolId in it.toolIds }?.id
}

/**
 * 按会话持久化的工具集选择。
 *
 * [load] 在进程启动时注入持久化来源（无 Android 依赖，便于单元测试）；
 * 会话没有保存记录时视为“全部启用”。
 */
class SessionToolRegistry(
    private val loadEnabled: (sessionId: String) -> Set<String>?,
    private val saveEnabled: (sessionId: String, enabled: Set<String>) -> Unit,
    private val clearEnabled: (sessionId: String) -> Unit = { }
) {

    /** 读取某会话当前启用的工具 id；null 表示“未自定义，默认全部启用”。 */
    fun enabledToolIds(sessionId: String): Set<String>? = loadEnabled(sessionId)

    /** 是否已为用户自定制（保存过记录）。 */
    fun isCustomized(sessionId: String): Boolean = loadEnabled(sessionId) != null

    /**
     * 实际生效的启用工具集合：未自定义时返回全部归类工具；
     * 已自定义时返回保存的集合（即当前启用的工具，其余归类工具视为关闭）。
     */
    fun effectiveEnabledToolIds(sessionId: String): Set<String> {
        return loadEnabled(sessionId) ?: SessionToolCatalog.ALL_TOOL_IDS
    }

    fun allToolIds(): Set<String> = SessionToolCatalog.ALL_TOOL_IDS

    /** 判断某大类是否整体启用（大类内全部工具都启用）。 */
    fun isCategoryEnabled(sessionId: String, categoryId: String): Boolean {
        val enabled = effectiveEnabledToolIds(sessionId)
        val category = SessionToolCatalog.categoryById(categoryId) ?: return false
        return category.toolIds.all { it in enabled }
    }

    /** 判断单个工具是否启用。 */
    fun isToolEnabled(sessionId: String, toolId: String): Boolean =
        toolId in effectiveEnabledToolIds(sessionId)

    /** 切换大类整体启用/禁用。 */
    fun setCategoryEnabled(sessionId: String, categoryId: String, enabled: Boolean) {
        val category = SessionToolCatalog.categoryById(categoryId) ?: return
        val current = effectiveEnabledToolIds(sessionId).toMutableSet()
        if (enabled) current.addAll(category.toolIds)
        else current.removeAll(category.toolIds.toSet())
        persist(sessionId, current)
    }

    /** 切换单个工具启用/禁用。 */
    fun setToolEnabled(sessionId: String, toolId: String, enabled: Boolean) {
        val current = effectiveEnabledToolIds(sessionId).toMutableSet()
        if (enabled) current.add(toolId)
        else current.remove(toolId)
        persist(sessionId, current)
    }

    /** 恢复会话工具集为全部启用（等价于删除自定义记录）。 */
    fun resetToAll(sessionId: String) {
        clearEnabled(sessionId)
    }

    /**
     * 过滤工具定义列表：只保留“未归类（始终可用）”或“当前会话已启用”的工具。
     * 传入的是 OpenAI function-calling 定义列表，返回同结构、仅可能缩小的列表。
     */
    fun filterDefinitions(
        sessionId: String,
        definitions: List<Map<String, Any>>
    ): List<Map<String, Any>> {
        val enabled = effectiveEnabledToolIds(sessionId)
        if (enabled == SessionToolCatalog.ALL_TOOL_IDS) return definitions
        return definitions.filter { definition ->
            val name = toolNameOf(definition)
            name == null || name !in SessionToolCatalog.ALL_TOOL_IDS || name in enabled
        }
    }

    private fun persist(sessionId: String, enabled: Set<String>) {
        saveEnabled(sessionId, enabled)
    }
}

/** 从 OpenAI function-calling 定义中提取工具名。 */
internal fun toolNameOf(definition: Map<String, Any>): String? {
    @Suppress("UNCHECKED_CAST")
    (definition["function"] as? Map<String, Any>)?.get("name")?.toString()
        ?.let { return it }
    return definition["name"]?.toString()
}

/** 用 Gson 将工具 id 集合序列化为 JSON 字符串数组。 */
internal fun encodeToolSet(ids: Set<String>): String = Gson().toJson(ids.toList())

/** 从 JSON 字符串数组反序列化工具 id 集合。 */
internal fun decodeToolSet(raw: String?): Set<String>? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        Gson().fromJson<List<String>>(raw, type).toSet()
    }.getOrNull()
}