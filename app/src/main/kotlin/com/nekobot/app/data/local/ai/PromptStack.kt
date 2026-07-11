package com.nekobot.app.data.local.ai

/**
 * 动态提示词栈，对应原仓库 nbot/character/prompt_stack.py。
 *
 * 允许任意模块在任意阶段注册提示词注入项，最终统一合成为本轮顶部 system prompt。
 *
 * 核心原则：
 * - 动态注入只在本轮请求生效，不写入历史消息
 * - 按优先级排序合成（数值越小越靠前）
 * - 可调试、可观察
 */
class PromptStack {

    /** 单条提示词注入项 */
    data class PromptInjection(
        val key: String,
        val content: String,
        val priority: Int = 100,
        val role: String = "system",      // system / developer
        val scope: String = "turn",       // global / session / turn
        val enabled: Boolean = true
    )

    /** 已注册的注入项列表 */
    private val items: MutableList<PromptInjection> = mutableListOf()

    /** 优先级常量 */
    object Priority {
        const val SAFETY = 10
        /** 气泡分隔提示词（高优先级，紧随安全规则） */
        const val BUBBLE_SPLIT = 15
        const val BEHAVIOR = 20
        const val CHARACTER_PROFILE = 30
        const val CHARACTER_STATE = 40
        const val CHARACTER_RELATIONSHIP = 50
        const val REACTION_PLAN = 55
        const val CHARACTER_MEMORIES = 60
        const val WORLD_BOOK = 65
        const val KNOWLEDGE_RAG = 70
        const val TOOL_INSTRUCTIONS = 80
    }

    /**
     * 注册一条提示词注入项。同 key 则替换。
     *
     * @param key 注入项标识，用于调试和去重
     * @param content 注入内容
     * @param priority 优先级，数值越小越靠前
     * @param role 消息角色（system / developer）
     * @param scope 作用域（global / session / turn）
     */
    fun add(
        key: String,
        content: String,
        priority: Int = 100,
        role: String = "system",
        scope: String = "turn"
    ) {
        if (content.isBlank()) return

        val trimmed = content.trim()
        // 同 key 则替换
        val existingIdx = items.indexOfFirst { it.key == key }
        val injection = PromptInjection(key, trimmed, priority, role, scope)
        if (existingIdx >= 0) {
            items[existingIdx] = injection
        } else {
            items.add(injection)
        }
    }

    /** 移除指定 key 的注入项，返回是否移除成功 */
    fun remove(key: String): Boolean {
        return items.removeAll { it.key == key }
    }

    /** 获取指定 key 的注入项 */
    fun get(key: String): PromptInjection? = items.firstOrNull { it.key == key }

    /**
     * 合成所有注入项为最终的 system prompt。
     *
     * @param basePrompt 基础系统提示词（通常来自角色卡编译结果）
     * @return 合成后的完整系统提示词
     */
    fun render(basePrompt: String = ""): String {
        val parts = mutableListOf<String>()

        val cleanedBase = stripDynamicPromptSections(basePrompt)
        if (cleanedBase.isNotBlank()) {
            parts.add(cleanedBase.trim())
        }

        items
            .filter { it.enabled && it.content.isNotBlank() }
            .sortedBy { it.priority }
            .forEach { parts.add("## ${it.key}\n${it.content.trim()}") }

        return parts.joinToString("\n\n").trim()
    }

    /** 返回调试信息，展示本轮所有注入项 */
    fun renderDebug(): List<Map<String, Any>> {
        return items
            .sortedBy { it.priority }
            .map { item ->
                mapOf(
                    "key" to item.key,
                    "content" to (if (item.content.length > 200) item.content.take(200) + "..." else item.content),
                    "priority" to item.priority,
                    "role" to item.role,
                    "scope" to item.scope,
                    "enabled" to item.enabled
                )
            }
    }

    /** 清除指定作用域的所有注入项，返回清除数量 */
    fun clearScope(scope: String): Int {
        val before = items.size
        items.removeAll { it.scope == scope }
        return before - items.size
    }

    /** 返回所有已注册的 key 列表 */
    val keys: List<String> get() = items.map { it.key }

    // ==================== 动态 section 清理 ====================

    /** 动态 section 的 key 列表，用于从持久化的 base_prompt 中剥离 */
    private val dynamicSectionKeys = listOf(
        "character.runtime_state",
        "character.relationship",
        "character.reaction_plan",
        "character.memories",
        "character.memories_legacy",
        "character.image_capability",
        "character.personality_evolution",
        "output.bubble_split",
        "output.inner_monologue",
        "real_time.continuity",
        "character.circadian",
        "character.timeline",
        "character.life_sim",
        "plot.real_time_sync",
        "knowledge.rag",
        "world_book",
        "memory_fs.context",
        "memory_fs_context"
    )

    /** 旧版内联 section 标记 */
    private val legacyRuntimeSectionMarkers = listOf(
        "【角色当前状态】",
        "【与用户的关系】",
        "与用户的初始关系"
    )

    /**
     * 从持久化的 base_prompt 中移除运行时 PromptStack section。
     * 兼容两种标题风格：`## {key}` 和 `[{key}]`。
     */
    private fun stripDynamicPromptSections(prompt: String): String {
        if (prompt.isBlank()) return ""
        var cleaned = prompt

        for (key in dynamicSectionKeys) {
            val escapedKey = Regex.escape(key)
            // ## {key} 风格
            cleaned = cleaned.replace(
                Regex("(?:\\n{2,}|\\A)## $escapedKey\\n.*?(?=\\n{2,}(?:## |\\[)|\\Z)", RegexOption.DOT_MATCHES_ALL),
                "\n\n"
            )
            // [{key}] 风格
            cleaned = cleaned.replace(
                Regex("(?:\\n{2,}|\\A)\\[$escapedKey\\]\\n.*?(?=\\n{2,}(?:## |\\[)|\\Z)", RegexOption.DOT_MATCHES_ALL),
                "\n\n"
            )
        }

        // 旧版编译 profile 内嵌的初始运行时值
        cleaned = cleaned.replace(
            Regex("(?:\\n{2,}|\\A)【角色当前状态】\\n.*?(?=\\n{2,}(?:## |【)|\\Z)", RegexOption.DOT_MATCHES_ALL),
            "\n\n"
        )
        cleaned = stripLegacyInlineSections(cleaned)
        return cleaned.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun stripLegacyInlineSections(prompt: String): String {
        val lines = prompt.split("\n")
        val kept = mutableListOf<String>()
        var skipping = false

        for (line in lines) {
            val stripped = line.trim()
            if (legacyRuntimeSectionMarkers.any { it in stripped }) {
                skipping = true
                continue
            }
            if (skipping && (stripped.startsWith("## ") || stripped.startsWith("【"))) {
                skipping = false
            }
            if (!skipping) kept.add(line)
        }
        return kept.joinToString("\n")
    }

    companion object {
        /**
         * 从消息列表中分离 system prompt 和历史消息。
         *
         * @param messages 完整消息列表，每项为 Map（含 role/content）
         * @return Pair(systemPromptText, historyMessages)
         */
        fun splitSystemPrompt(messages: List<Map<String, Any>>): Pair<String, List<Map<String, Any>>> {
            val systemParts = mutableListOf<String>()
            val history = mutableListOf<Map<String, Any>>()

            for (msg in messages) {
                val role = msg["role"] as? String ?: ""
                if (role == "system") {
                    val content = msg["content"] as? String ?: ""
                    if (content.isNotEmpty()) systemParts.add(content)
                } else {
                    history.add(msg)
                }
            }
            return systemParts.joinToString("\n\n").trim() to history
        }
    }
}
