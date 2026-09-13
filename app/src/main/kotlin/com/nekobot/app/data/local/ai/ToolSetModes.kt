package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 工具集模式：一套命名好的「启用哪些工具」的预设。
 *
 * 内置模式（极简 / 标准 / 安卓 / 角色卡 / 全能）由 [ToolSetModeCatalog.builtinModes]
 * 按 [SessionToolCatalog] 的大类实时推导，因此新增工具会自动进入相应模式，
 * 不需要在每个模式里手写工具 id（只有少数刻意挑选的集合才写死 id）。
 *
 * 自定义模式由用户命名并持久化到 SharedPreferences（见 PrefsManager）。
 *
 * [includeDynamic] 表示该模式是否包含运行期动态大类（MCP）：关闭时不仅当前已知的
 * MCP 工具不启用，之后新接入的 MCP 服务器工具也不会被静默放行。
 */
data class ToolSetMode(
    val id: String,
    /** 自定义模式的名称；内置模式为空，由 UI 映射到字符串资源。 */
    val name: String,
    /** 启用的静态工具 id 集合（不含运行期动态工具）。 */
    val toolIds: Set<String>,
    val includeDynamic: Boolean,
    val builtin: Boolean
)

/** 自定义模式的持久化记录。 */
data class CustomToolSetModeRecord(
    val id: String,
    val name: String,
    val toolIds: List<String>,
    val includeDynamic: Boolean = true
)

object ToolSetModeCatalog {

    const val ALL_MODE_ID = "all"
    const val MINIMAL_MODE_ID = "minimal"
    const val STANDARD_MODE_ID = "standard"
    const val ANDROID_MODE_ID = "android"
    const val CHARACTER_MODE_ID = "character"

    /** 自定义模式 id 前缀。 */
    const val CUSTOM_MODE_PREFIX = "custom:"

    private fun categoryTools(categoryId: String): Set<String> =
        SessionToolCatalog.categoryById(categoryId)?.toolIds?.toSet().orEmpty()

    private fun categoriesTools(vararg categoryIds: String): Set<String> =
        categoryIds.flatMap { categoryTools(it) }.toSet()

    /** 极简：只保留时间、待办、提问与记忆，不联网、不碰文件与设备。 */
    private val MINIMAL_TOOLS: Set<String> = setOf(
        "get_date_time",
        "ask_user_question",
        "todo_write",
        "todo_read",
        "agent_memory_read",
        "agent_memory_update"
    )

    /**
     * 标准：日常使用。在全能的基础上排除安卓自动化、数据管理工具，
     * 以及 Linux 沙盒里的命令执行（保留文件读写 / 检索），MCP 工具照常放行。
     */
    private val STANDARD_TOOLS: Set<String> = SessionToolCatalog.staticToolIds -
        categoriesTools("android", "db") -
        setOf("exec_command", "shell_job")

    /**
     * 角色卡：整理角色卡 / 世界书等本地数据用。
     * 在标准的基础上补上「数据管理」大类（角色卡、世界书、记忆、状态等增删改查），
     * 但依然不含安卓自动化与命令执行。
     */
    private val CHARACTER_TOOLS: Set<String> = STANDARD_TOOLS + categoriesTools("db")

    /** 安卓：手机自动化用，屏幕操作 / 应用 / 通知 / 媒体 + 联网查询 + 任务与图片。 */
    private val ANDROID_TOOLS: Set<String> = categoriesTools(
        "android", "task", "memory", "image"
    ) + setOf("get_date_time", "search_web", "web_fetch", "http_get")

    /** 内置模式，顺序即 UI 展示顺序。 */
    fun builtinModes(): List<ToolSetMode> = listOf(
        ToolSetMode(MINIMAL_MODE_ID, "", MINIMAL_TOOLS, includeDynamic = false, builtin = true),
        ToolSetMode(STANDARD_MODE_ID, "", STANDARD_TOOLS, includeDynamic = true, builtin = true),
        ToolSetMode(CHARACTER_MODE_ID, "", CHARACTER_TOOLS, includeDynamic = true, builtin = true),
        ToolSetMode(ANDROID_MODE_ID, "", ANDROID_TOOLS, includeDynamic = false, builtin = true),
        ToolSetMode(ALL_MODE_ID, "", SessionToolCatalog.staticToolIds, includeDynamic = true, builtin = true)
    )

    /** 把持久化记录还原成模式。 */
    fun customMode(record: CustomToolSetModeRecord): ToolSetMode =
        ToolSetMode(
            id = record.id,
            name = record.name,
            toolIds = record.toolIds.toSet(),
            includeDynamic = record.includeDynamic,
            builtin = false
        )

    /**
     * 在模式列表中找出与给定启用状态完全一致的模式。
     *
     * @param enabledStatic 当前启用的静态工具集合
     * @param dynamicOn 动态大类（MCP）是否处于默认放行状态
     * @return 匹配到的模式 id；没有匹配返回 null（表示“自定义”）
     */
    fun matchModeId(
        modes: List<ToolSetMode>,
        enabledStatic: Set<String>,
        dynamicOn: Boolean
    ): String? = modes
        .firstOrNull { it.includeDynamic == dynamicOn && it.toolIds == enabledStatic }
        ?.id

    /** 生成一个新的自定义模式 id。 */
    fun newCustomModeId(): String =
        CUSTOM_MODE_PREFIX + UUID.randomUUID().toString().replace("-", "").take(8)

    /** 序列化自定义模式列表（JSON 数组）。 */
    fun encodeCustomModes(records: List<CustomToolSetModeRecord>): String =
        Gson().toJson(records)

    /** 反序列化自定义模式列表；数据损坏时返回空列表而不是抛异常。 */
    fun decodeCustomModes(raw: String?): List<CustomToolSetModeRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<CustomToolSetModeRecord>>() {}.type
            Gson().fromJson<List<CustomToolSetModeRecord>>(raw, type)
                ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
