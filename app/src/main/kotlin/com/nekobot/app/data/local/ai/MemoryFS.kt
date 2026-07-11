package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.MemoryDao
import java.time.Instant

/**
 * MemoryFS — 记忆逻辑文件系统（简化版），对应原仓库 nbot/memory/fs.py。
 *
 * 通过 category 字段把记忆条目组织成角色可读的逻辑视图，
 * 用于提示词注入时按类别分组呈现。
 *
 * 简化点：
 * - 不使用文件路径(path)，直接用 category 分类
 * - 不支持跨会话时间线(timeline)和角色生活片段(life_sim)
 * - 依赖 MemoryDao 持久化
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

/** 记忆类别定义 */
val MEMORY_CATEGORY_META = listOf(
    MemoryCategoryMeta("user_persona", "用户人格", 10, true),
    MemoryCategoryMeta("character_persona", "角色人格", 20, true),
    MemoryCategoryMeta("important_event", "重要事件", 30, true),
    MemoryCategoryMeta("recent_digest", "近期摘要", 40, true),
    MemoryCategoryMeta("legacy", "旧版/其他", 90, false)
)

/** 别名 → 标准类别 */
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
 */
class MemoryFS(
    private val memoryDao: MemoryDao
) {
    companion object {
        private const val TAG = "MemoryFS"
        private const val MAX_MEMORY_CHARS = 2000
        private const val MAX_ENTRIES_PER_CATEGORY = 10
    }

    /**
     * 构建提示词上下文文本。
     *
     * @param characterId 角色 ID
     * @param targetId 目标用户 ID
     * @param conversationId 会话 ID（未使用，预留）
     * @return 按类别分组的记忆文本，或空字符串
     */
    suspend fun buildPromptContext(
        characterId: String,
        targetId: String,
        conversationId: String = ""
    ): String {
        val memories = memoryDao.listByCharacterAndTarget(characterId, targetId, 30)
        if (memories.isEmpty()) return ""

        val grouped = memories.groupBy { normalizeCategory(it.category) }
        val parts = mutableListOf<String>()

        for (meta in MEMORY_CATEGORY_META) {
            if (!meta.injectsToPrompt) continue
            val entries = grouped[meta.key] ?: continue
            if (entries.isEmpty()) continue

            val limited = entries.take(MAX_ENTRIES_PER_CATEGORY)
            val text = limited.joinToString("\n") { entry ->
                val title = entry.title.trim()
                val content = entry.content.trim()
                if (title.isNotEmpty()) "• [$title] $content" else "• $content"
            }
            if (text.length > MAX_MEMORY_CHARS) {
                parts.add("【${meta.label}】\n${text.take(MAX_MEMORY_CHARS)}...")
            } else {
                parts.add("【${meta.label}】\n$text")
            }
        }

        return parts.joinToString("\n\n").trim()
    }

    /** 将 entity 的 category 字段标准化 */
    private fun normalizeCategory(category: String): String {
        return normalizeMemoryCategory(if (category.isNotEmpty()) category else "legacy")
    }
}

// ============================================================================
// AutoState — 自动状态调整（简化版）
// ============================================================================

/**
 * 自动状态调整器，对应原仓库 nbot/character/auto_state.py。
 *
 * 在角色运行时 after_turn 之后，根据对话内容做额外的状态微调。
 * 简化版：不使用 AI 模型，仅基于关键词的启发式调整。
 */
object AutoState {

    /** 恢复性关键词（促进精力恢复） */
    private val RESTORATIVE_KEYWORDS = listOf(
        "休息", "睡觉", "补觉", "放松", "吃饭", "喝水", "晚安", "早安"
    )

    /** 高情绪强度关键词 */
    private val HIGH_EMOTION_KEYWORDS = listOf(
        "爱", "恨", "气死", "崩溃", "超开心", "特别", "非常"
    )

    /**
     * 根据对话内容做额外的状态微调。
     *
     * @param state 当前状态（会被原地修改）
     * @param userMessage 用户消息
     * @param assistantMessage 助手回复
     */
    fun adjust(state: CharacterState, userMessage: String, assistantMessage: String) {
        // 恢复性关键词 → 精力恢复
        if (RESTORATIVE_KEYWORDS.any { it in userMessage }) {
            state.energy = (state.energy + 3).coerceAtMost(100)
        }

        // 高情绪强度 → 情绪强度提升
        if (HIGH_EMOTION_KEYWORDS.any { it in userMessage }) {
            state.moodIntensity = (state.moodIntensity + 0.1f).coerceAtMost(1f)
        }

        // 长回复 → 精力消耗
        if (assistantMessage.length > 800) {
            state.energy = (state.energy - 1).coerceAtLeast(0)
        }
    }
}
