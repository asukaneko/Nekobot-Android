package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 会话工具集选择。
 *
 * 将本地可执行的工具按“大类”分类，UI 先选择大类，再点进大类细化到单个工具。
 * 选中结果是“当前会话 Agent 可以使用哪些工具”，默认全部启用（保持既有行为）。
 *
 * 每个会话的选中结果持久化到 SharedPreferences（键 `session_toolset_<sessionId>`），
 * 与 /yolo 不同，工具集选择跨进程保留，便于用户长期控制当前会话的能力边界。
 *
 * 目录里只允许出现**真正可执行**的工具 id：曾经出现过“有开关但永不注入/永不执行”的
 * 死条目，用户关掉它没有任何效果，反而误以为已经限制了能力边界。一致性由
 * `SessionToolCatalogConsistencyTest` 守住。
 */
object SessionToolCatalog {

    /** 运行期发现的动态工具（MCP 服务器工具）所属大类 id。 */
    const val MCP_CATEGORY_ID = "mcp"

    /** MCP 工具 id 前缀（见 LocalMcpRuntime 的工具命名规则）。 */
    const val MCP_TOOL_PREFIX = "mcp__"

    /** 工具大类：id 用于持久化，titleRes 仅供 UI 层映射字符串资源。 */
    data class Category(
        val id: String,
        val toolIds: List<String>
    )

    private val staticCategories: List<Category> = listOf(
        // 基础查询 / 网络检索
        Category(
            id = "basic",
            toolIds = listOf(
                "get_weather",
                "search_web",
                "http_get",
                "web_fetch",
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
                "shell_job",
                "file_read",
                "file_write",
                "file_edit",
                "grep",
                "glob"
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
                "workspace_file_info"
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
                "agent_memory_read",
                "agent_memory_update"
            )
        ),
        // 任务与提问
        Category(
            id = "task",
            toolIds = listOf(
                "todo_write",
                "todo_read",
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
        ),
        // 子代理委派
        Category(
            id = "subagent",
            toolIds = listOf(
                "subagent",
                "subagent_list",
                "subagent_get",
                "subagent_kill"
            )
        )
    )

    /** 运行期注册的动态工具：大类 id → 工具 id 列表（MCP 工具随服务器配置变化）。 */
    private val dynamicCategories = ConcurrentHashMap<String, List<String>>()

    /**
     * 注册运行期发现的工具（当前用于 MCP）。重复注册幂等；
     * 注册后这些工具会出现在会话工具集面板中，用户可以按大类或单个关闭。
     */
    fun registerDynamicTools(categoryId: String, toolIds: Collection<String>) {
        val normalized = toolIds.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return
        dynamicCategories.compute(categoryId) { _, previous ->
            ((previous ?: emptyList()) + normalized).distinct().sorted()
        }
    }

    /** 动态大类 id 集合（含 MCP 大类本身，即使尚未枚举出任何工具）。 */
    fun dynamicCategoryIds(): Set<String> = dynamicCategories.keys + MCP_CATEGORY_ID

    private fun dynamicCategoryList(): List<Category> =
        dynamicCategories.entries
            .sortedBy { it.key }
            .map { Category(it.key, it.value) }

    /** 全部大类（静态 + 运行期动态），顺序即 UI 展示顺序。 */
    val categories: List<Category>
        get() = staticCategories + dynamicCategoryList()

    /** 静态大类中的工具 id（供一致性校验与单测使用）。 */
    val staticToolIds: Set<String>
        get() = staticCategories.flatMap { it.toolIds }.toSet()

    /** 所有已归类工具的 id 集合（用于判断某个工具是否属于工具集可管理范围）。 */
    val ALL_TOOL_IDS: Set<String>
        get() = categories.flatMap { it.toolIds }.toSet()

    /** 所有大类 id（顺序即展示顺序）。 */
    val allCategoryIds: List<String>
        get() = categories.map { it.id }

    /**
     * 按 id 取大类。动态大类（MCP）即使当前还没枚举出工具也返回空目录，
     * 这样“关闭 MCP 大类”在服务器尚未连接时同样能记录用户意图。
     */
    fun categoryById(id: String): Category? =
        categories.firstOrNull { it.id == id }
            ?: Category(id, emptyList()).takeIf { id in dynamicCategoryIds() }

    /**
     * 返回某个工具所属的大类 id；不属于任何大类时返回 null。
     *
     * 未注册但带 MCP 前缀的工具同样归入 MCP 大类：即使某次运行漏注册，
     * 它也不会绕过会话工具集的管辖。
     */
    fun categoryIdOf(toolId: String): String? {
        categories.firstOrNull { toolId in it.toolIds }?.let { return it.id }
        return MCP_CATEGORY_ID.takeIf { toolId.startsWith(MCP_TOOL_PREFIX) }
    }

    /** 该工具是否受会话工具集管理（未归类工具不受管理，始终注入）。 */
    fun isManagedTool(toolId: String): Boolean = categoryIdOf(toolId) != null
}

/**
 * 按会话持久化的工具集选择。
 *
 * [load] 在进程启动时注入持久化来源（无 Android 依赖，便于单元测试）；
 * 会话没有保存记录时，回退到「新会话默认工具集」[loadDefaultEnabled]；
 * 两者都没有时视为“全部启用”。
 *
 * 动态工具（MCP）语义：[loadTouchedCategories] 记录用户显式改动过的大类。
 * 未被改动过的动态大类默认启用——否则“用户在 MCP 之前自定义过工具集”会导致
 * 后续新增的 MCP 工具被静默禁用。会话级与默认级改动过的大类取并集判断。
 */
class SessionToolRegistry(
    private val loadEnabled: (sessionId: String) -> Set<String>?,
    private val saveEnabled: (sessionId: String, enabled: Set<String>) -> Unit,
    private val clearEnabled: (sessionId: String) -> Unit = { },
    private val loadTouchedCategories: (sessionId: String) -> Set<String>? = { null },
    private val saveTouchedCategories: (sessionId: String, touched: Set<String>) -> Unit = { _, _ -> },
    /** 「新会话默认工具集」读取；null 表示未设置默认（等同全部启用）。 */
    private val loadDefaultEnabled: () -> Set<String>? = { null },
    /** 「新会话默认工具集」中被显式改动过的大类 id。 */
    private val loadDefaultTouchedCategories: () -> Set<String>? = { null }
) {

    /**
     * 读取某会话当前启用的工具 id；会话未自定义时回退到「新会话默认工具集」，
     * 两者都没有记录时返回 null 表示“默认全部启用”。
     */
    fun enabledToolIds(sessionId: String): Set<String>? =
        loadEnabled(sessionId) ?: loadDefaultEnabled()

    /** 是否已为某会话单独自定制（保存过会话级记录）。 */
    fun isCustomized(sessionId: String): Boolean = loadEnabled(sessionId) != null

    /** 全局是否设置了「新会话默认工具集」。 */
    fun hasDefault(): Boolean = loadDefaultEnabled() != null

    /**
     * 用户显式改动过的大类 id（会话级 + 默认级并集）。
     * 只有动态大类依赖它，用于“默认启用”判断。
     */
    fun touchedCategoryIds(sessionId: String): Set<String> =
        loadTouchedCategories(sessionId).orEmpty() + loadDefaultTouchedCategories().orEmpty()

    /** 动态大类是否仍处于“默认启用”状态。 */
    private fun isDynamicCategoryDefaultOn(sessionId: String, categoryId: String): Boolean =
        categoryId in SessionToolCatalog.dynamicCategoryIds() &&
            categoryId !in touchedCategoryIds(sessionId)

    /**
     * 实际生效的启用工具集合：会话未自定义时用「新会话默认工具集」，
     * 仍未设置默认时返回全部归类工具；已自定义时返回保存的集合，
     * 并补上仍处于默认启用状态的动态大类工具。
     */
    fun effectiveEnabledToolIds(sessionId: String): Set<String> {
        val saved = loadEnabled(sessionId) ?: loadDefaultEnabled()
            ?: return SessionToolCatalog.ALL_TOOL_IDS
        val defaultOnDynamic = SessionToolCatalog.categories
            .filter { isDynamicCategoryDefaultOn(sessionId, it.id) }
            .flatMap { it.toolIds }
        return if (defaultOnDynamic.isEmpty()) saved else saved + defaultOnDynamic
    }

    fun allToolIds(): Set<String> = SessionToolCatalog.ALL_TOOL_IDS

    /** 判断某大类是否整体启用（大类内全部工具都启用）。 */
    fun isCategoryEnabled(sessionId: String, categoryId: String): Boolean {
        val enabled = effectiveEnabledToolIds(sessionId)
        val category = SessionToolCatalog.categoryById(categoryId) ?: return false
        return category.toolIds.all { it in enabled }
    }

    /** 判断单个工具是否启用。 */
    fun isToolEnabled(sessionId: String, toolId: String): Boolean {
        if (SessionToolCatalog.categoryIdOf(toolId) in SessionToolCatalog.dynamicCategoryIds() &&
            SessionToolCatalog.categoryIdOf(toolId)?.let { isDynamicCategoryDefaultOn(sessionId, it) } == true
        ) {
            return true
        }
        return toolId in effectiveEnabledToolIds(sessionId)
    }

    /** 切换大类整体启用/禁用。 */
    fun setCategoryEnabled(sessionId: String, categoryId: String, enabled: Boolean) {
        val category = SessionToolCatalog.categoryById(categoryId) ?: return
        val current = effectiveEnabledToolIds(sessionId).toMutableSet()
        if (enabled) current.addAll(category.toolIds)
        else current.removeAll(category.toolIds.toSet())
        persist(sessionId, current)
        markCategoryTouched(sessionId, categoryId)
    }

    /** 切换单个工具启用/禁用。 */
    fun setToolEnabled(sessionId: String, toolId: String, enabled: Boolean) {
        val current = effectiveEnabledToolIds(sessionId).toMutableSet()
        if (enabled) current.add(toolId)
        else current.remove(toolId)
        persist(sessionId, current)
        SessionToolCatalog.categoryIdOf(toolId)?.let { markCategoryTouched(sessionId, it) }
    }

    /**
     * 清除某会话的自定义记录：有「新会话默认工具集」时恢复为默认，
     * 否则等价于全部启用。
     */
    fun resetToAll(sessionId: String) {
        clearEnabled(sessionId)
        saveTouchedCategories(sessionId, emptySet())
    }

    /**
     * 过滤工具定义列表：只保留“未归类（始终可用）”或“当前会话已启用”的工具。
     * 传入的是 OpenAI function-calling 定义列表，返回同结构、仅可能缩小的列表。
     *
     * 动态大类（MCP）在用户未显式改动前始终保留，避免用户自定义过工具集之后
     * 新增的 MCP 工具被静默丢弃。
     */
    fun filterDefinitions(
        sessionId: String,
        definitions: List<Map<String, Any>>
    ): List<Map<String, Any>> {
        val enabled = effectiveEnabledToolIds(sessionId)
        return definitions.filter { definition ->
            val name = toolNameOf(definition) ?: return@filter true
            val categoryId = SessionToolCatalog.categoryIdOf(name) ?: return@filter true
            if (isDynamicCategoryDefaultOn(sessionId, categoryId)) return@filter true
            name in enabled
        }
    }

    private fun markCategoryTouched(sessionId: String, categoryId: String) {
        val touched = touchedCategoryIds(sessionId).toMutableSet()
        if (touched.add(categoryId)) saveTouchedCategories(sessionId, touched)
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

/**
 * 工具集面板的兜底展示名：MCP 工具 id 形如 `mcp__<服务器id>__<工具名>`，
 * 直接展示内部 id 对用户没有意义，这里剥掉前缀只留工具名。
 */
internal fun toolDisplayFallbackName(toolId: String): String {
    if (!toolId.startsWith(SessionToolCatalog.MCP_TOOL_PREFIX)) return toolId
    val rest = toolId.removePrefix(SessionToolCatalog.MCP_TOOL_PREFIX)
    return rest.substringAfter("__", rest).ifBlank { toolId }
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
