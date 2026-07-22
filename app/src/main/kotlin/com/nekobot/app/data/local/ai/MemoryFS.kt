package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.MemoryDao
import java.time.Instant

/**
 * MemoryFS — 记忆逻辑文件系统，对应原仓库 nbot/memory/fs.py。
 *
 * 通过 category + memory_path 把记忆条目组织成角色可读的逻辑视图，
 * 用于提示词注入时按类别分组呈现。
 *
 * 与原仓库对齐：
 * - 7 类记忆：user_persona / character_persona / important_event / timeline / life_sim / recent_digest / legacy
 * - 会话隔离：important_event / life_sim 按 conversationId 隔离
 * - timeline 跨会话分桶注入：每会话取最新 2 条 + 全局最新 10 条，排除当前会话
 * - life_sim 注入：倒序取最新 5 条
 * - 注入顺序：user_persona → character_persona → timeline → events/life_sim → recent_digest
 *
 * 简化点（与原仓库差异）：
 * - 不使用文件路径(path)持久化，直接用 category + conversationId + targetId 字段
 * - life_sim 生成模块（静默心跳）不在 MemoryFS 中，由外部模块调用 write 接口
 * - 不支持 plot / world_events / diary 类别（原仓库也只用于注入，非 auto_memory 抽取）
 */

private val mfsGson = Gson()

/**
 * 记忆类别元数据。
 */
data class MemoryCategoryMeta(
    val key: String,
    val label: String,
    val order: Int,
    val injectsToPrompt: Boolean
)

/** 记忆类别定义（对齐原仓库 fs.py 的 _MEMORY_CATEGORY_META） */
val MEMORY_CATEGORY_META = listOf(
    MemoryCategoryMeta("user_persona", "用户人格", 10, true),
    MemoryCategoryMeta("character_persona", "角色人格", 20, true),
    MemoryCategoryMeta("important_event", "重要事件", 30, true),
    MemoryCategoryMeta("timeline", "跨会话时间线", 35, true),
    MemoryCategoryMeta("life_sim", "角色生活片段", 38, true),
    MemoryCategoryMeta("recent_digest", "近期摘要", 40, true),
    MemoryCategoryMeta("legacy", "旧版/其他", 90, false)
)

/** 别名 → 标准类别（对齐原仓库 fs.py 的 _MEMORY_CATEGORY_ALIASES） */
val MEMORY_CATEGORY_ALIASES = mapOf(
    "user" to "user_persona",
    "user_profile" to "user_persona",
    "user_preference" to "user_persona",
    "user_preferences" to "user_persona",
    "persona_user" to "user_persona",
    "character" to "character_persona",
    "character_profile" to "character_persona",
    "character_attitude" to "character_persona",
    "persona_character" to "character_persona",
    "relationship" to "character_persona",
    "event" to "important_event",
    "events" to "important_event",
    "important_events" to "important_event",
    "plot" to "important_event",
    "plot_summary" to "important_event",
    "world_event" to "important_event",
    "timeline_event" to "timeline",
    "timeline_event_other" to "timeline",
    "life_event" to "life_sim",
    "life_sim_event" to "life_sim",
    "heartbeat_life" to "life_sim",
    "digest" to "recent_digest",
    "summary" to "recent_digest",
    "recent_summary" to "recent_digest",
    "dialogue_digest" to "recent_digest",
    "diary" to "recent_digest"
)

/** 标准化记忆类别 */
fun normalizeMemoryCategory(value: String?): String {
    val normalized = (value ?: "").trim().lowercase()
        .replace("-", "_")
        .replace(" ", "_")
    val aliased = MEMORY_CATEGORY_ALIASES[normalized] ?: normalized
    return if (MEMORY_CATEGORY_META.any { it.key == aliased }) aliased else ""
}

/**
 * 记忆逻辑文件系统。
 *
 * 从 MemoryDao 加载记忆并按类别组织为提示词文本。
 * 注入逻辑对齐原仓库 fs.py 的 build_prompt_context：
 * 1. user_persona → character_persona
 * 2. timeline（跨会话分桶：每会话 2 条 + 全局 10 条，排除当前会话）
 * 3. events + life_sim（按 conversationId 隔离，life_sim 倒序取最新 5 条）
 * 4. recent_digest
 */
class MemoryFS(
    private val memoryDao: MemoryDao
) {
    companion object {
        private const val TAG = "MemoryFS"
        private const val MAX_MEMORY_CHARS = 2000
        private const val MAX_ENTRIES_PER_CATEGORY = 10

        // timeline 注入策略（对齐原仓库 fs.py）
        private const val TIMELINE_PER_CONVERSATION = 2
        private const val TIMELINE_MAX_TOTAL = 10
        // life_sim 注入策略
        private const val MAX_LIFE_SIM_INJECT = 5
    }

    /**
     * 构建提示词上下文文本（对齐原仓库 fs.py:522-577 build_prompt_context）。
     *
     * @param characterId 角色 ID
     * @param targetId 目标用户 ID
     * @param conversationId 当前会话 ID（用于排除 timeline 当前会话条目 + 隔离 events/life_sim）
     * @return 按类别分组的记忆文本，或空字符串
     */
    suspend fun buildPromptContext(
        characterId: String,
        targetId: String,
        conversationId: String = ""
    ): String {
        val parts = mutableListOf<String>()

        try {
            // 1. user_persona
            // user_persona 是单槽记忆；兼容升级前已产生的重复数据，注入时只取最新一条。
            val userPersona = memoryDao.listByCategory(characterId, targetId, "user_persona", 1)
            if (userPersona.isNotEmpty()) {
                val text = formatMemoryList(userPersona)
                if (text.isNotBlank()) parts.add("【用户人格】\n$text")
            }

            // 2. character_persona
            val charPersona = memoryDao.listByCategory(characterId, targetId, "character_persona", MAX_ENTRIES_PER_CATEGORY)
            if (charPersona.isNotEmpty()) {
                val text = formatMemoryList(charPersona)
                if (text.isNotBlank()) parts.add("【角色人格】\n$text")
            }

            // 3. timeline（跨会话，排除当前会话，每会话 2 条 + 全局 10 条）
            if (conversationId.isNotBlank()) {
                val timelineText = buildTimelineText(characterId, conversationId)
                if (timelineText.isNotBlank()) parts.add(timelineText)
            }

            // 4. events + life_sim（按 conversationId 隔离）
            if (conversationId.isNotBlank()) {
                // important_event：当前会话
                val events = memoryDao.listByCharacterCategoryAndConversation(
                    characterId, "important_event", conversationId, MAX_ENTRIES_PER_CATEGORY
                )
                if (events.isNotEmpty()) {
                    val text = formatMemoryList(events)
                    if (text.isNotBlank()) parts.add("【本会话事件】\n$text")
                }

                // life_sim：当前会话，倒序取最新 5 条
                val lifeSim = memoryDao.listByCharacterCategoryAndConversation(
                    characterId, "life_sim", conversationId, MAX_LIFE_SIM_INJECT
                )
                if (lifeSim.isNotEmpty()) {
                    val text = formatLifeSimText(lifeSim)
                    if (text.isNotBlank()) parts.add(text)
                }
            }

            // 5. recent_digest
            val digest = memoryDao.listByCategory(characterId, targetId, "recent_digest", 3)
            if (digest.isNotEmpty()) {
                val text = formatMemoryList(digest)
                if (text.isNotBlank()) parts.add("【近期摘要】\n$text")
            }
        } catch (e: Exception) {
            LocalLogger.w(TAG, "构建记忆提示词上下文失败: ${e.message}", e)
        }

        return parts.joinToString("\n\n").trim().take(MAX_MEMORY_CHARS * 3)
    }

    /**
     * 构造 timeline 跨会话注入文本（对齐原仓库 fs.py:194-231 format_timeline_for_prompt）。
     *
     * - 排除当前会话条目（避免与 events 段重复）
     * - 按 conversationId 分桶，每桶取最新 2 条
     * - 全局取最新 10 条
     * - 时间正序输出
     */
    private suspend fun buildTimelineText(characterId: String, currentConversationId: String): String {
        val allTimeline = memoryDao.listByCharacterAndCategory(characterId, "timeline", limit = 80)
        if (allTimeline.isEmpty()) return ""

        // 排除当前会话
        val filtered = allTimeline.filter { it.conversationId != currentConversationId }
        if (filtered.isEmpty()) return ""

        // 按 conversationId 分桶，每桶取最新 2 条
        val byConv = filtered.groupBy { it.conversationId ?: "unknown" }
            .mapValues { (_, entries) -> entries.take(TIMELINE_PER_CONVERSATION) }
            .values
            .flatten()
            .sortedBy { it.updatedAt ?: it.createdAt }

        // 全局取最新 10 条（按时间正序输出，但只保留最新 10 条）
        val finalList = if (byConv.size > TIMELINE_MAX_TOTAL) {
            byConv.takeLast(TIMELINE_MAX_TOTAL)
        } else {
            byConv
        }

        if (finalList.isEmpty()) return ""

        val lines = finalList.map { entry ->
            val content = entry.content.trim()
            "- $content"
        }
        return "【跨会话时间线】（与其他会话发生过的事）\n${lines.joinToString("\n")}"
    }

    /**
     * 构造 life_sim 注入文本（对齐原仓库 fs.py:242-287 format_life_sim_for_prompt）。
     *
     * 倒序取最新 5 条 + header 说明"用户不在场时"的活动。
     */
    private fun formatLifeSimText(entries: List<com.nekobot.app.data.local.db.LocalCharacterMemoryEntity>): String {
        val sorted = entries.sortedByDescending { it.updatedAt ?: it.createdAt }.take(MAX_LIFE_SIM_INJECT)
        val lines = sorted.map { entry ->
            val title = entry.title.trim()
            val content = entry.content.trim()
            if (title.isNotEmpty()) "- [$title] $content" else "- $content"
        }
        return "【角色生活片段】（用户不在场时的独处活动）\n${lines.joinToString("\n")}"
    }

    /** 格式化普通记忆列表为 `• [title] content` 形式 */
    private fun formatMemoryList(entries: List<com.nekobot.app.data.local.db.LocalCharacterMemoryEntity>): String {
        return entries.joinToString("\n") { entry ->
            val title = entry.title.trim()
            val content = entry.content.trim()
            if (title.isNotEmpty()) "• [$title] $content" else "• $content"
        }
    }

    /** 将 entity 的 category 字段标准化（用于导入旧数据兼容） */
    private fun normalizeCategory(category: String): String {
        return normalizeMemoryCategory(if (category.isNotEmpty()) category else "legacy")
    }
}
