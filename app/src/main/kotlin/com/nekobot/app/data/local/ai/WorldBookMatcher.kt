package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity

/**
 * 世界书关键词多源召回匹配器，对应原仓库 nbot/character/world_book_matcher.py。
 *
 * 支持 4 源召回：用户消息 / 助手最近回复 / 历史上下文 / 场景状态触发。
 * 常驻条目（always_on）直接入选。按得分排序裁剪。
 */

// ============================================================================
// 数据类
// ============================================================================

/** 召回上下文 */
data class WorldBookRecallContext(
    val latestUserMessage: String = "",
    val recentMessages: List<Map<String, String>> = emptyList(),
    val assistantRecentText: String = "",
    val historyText: String = "",
    val scene: Map<String, Any> = emptyMap(),
    val activeEntryIds: Set<String> = emptySet(),
    val characterId: String = "",
    val targetId: String = "",
    val scopeId: String = ""
)

/** 召回配置 */
data class WorldBookRecallConfig(
    val recentMessageLimit: Int = 6,
    val maxHistoryChars: Int = 2000,
    val maxTotalChars: Int = 3000,
    val maxEntries: Int = 8,
    val maxAlwaysChars: Int = 800,
    val maxSceneChars: Int = 1000,
    val maxKeywordChars: Int = 1200,
    val maxAssistantTriggeredEntries: Int = 3,
    val minAssistantPriority: Int = 20,
    val enableAssistantTrigger: Boolean = true,
    val enableHistoryTrigger: Boolean = true,
    val enableSceneTrigger: Boolean = true,
    val enableCooldown: Boolean = true
)

/** 单条匹配结果 */
data class WorldBookMatchResult(
    val entry: LocalWorldBookEntryEntity,
    val triggerSources: List<String>,
    val matchedKeywords: List<String>,
    val score: Int
)

// ============================================================================
// 权重与优先级
// ============================================================================

/** 源权重 */
private val SOURCE_WEIGHTS = mapOf(
    "always" to 100,
    "user" to 50,
    "scene_state" to 45,
    "assistant_recent" to 30,
    "history" to 20
)

/** 条目类型优先级 */
private val ENTRY_TYPE_PRIORITY = mapOf(
    "relationship" to 90,
    "rule" to 80,
    "location" to 70,
    "event" to 60,
    "npc" to 50,
    "faction" to 45,
    "lore" to 40,
    "style" to 35,
    "secret" to 30
)

// ============================================================================
// 匹配器
// ============================================================================

object WorldBookMatcher {

    /**
     * V2 多源召回主入口。
     *
     * @param context 召回上下文
     * @param worldBooks 世界书列表
     * @param entriesByBook 每本世界书的条目列表
     * @param characterId 当前角色 ID
     * @param config 召回配置
     * @return 匹配结果列表（按 score 降序）
     */
    fun matchEntriesV2(
        context: WorldBookRecallContext,
        worldBooks: List<LocalWorldBookEntity>,
        entriesByBook: Map<String, List<LocalWorldBookEntryEntity>>,
        characterId: String = "",
        config: WorldBookRecallConfig = WorldBookRecallConfig()
    ): List<WorldBookMatchResult> {
        if (worldBooks.isEmpty()) return emptyList()

        val results = mutableListOf<WorldBookMatchResult>()
        val assistantText = if (context.assistantRecentText.isNotEmpty()) {
            context.assistantRecentText
        } else {
            extractAssistantRecentText(context.recentMessages, config.recentMessageLimit)
        }
        val historyText = if (context.historyText.isNotEmpty()) {
            context.historyText
        } else {
            extractHistoryText(context.recentMessages, config.maxHistoryChars)
        }
        val sceneText = extractSceneText(context.scene)

        var assistantTriggeredCount = 0

        for (book in worldBooks) {
            if (!book.enabled) continue
            // 角色过滤
            val bookCharId = book.characterId
            if (!bookCharId.isNullOrBlank() && characterId.isNotEmpty()) {
                if (!characterMatches(characterId, bookCharId)) continue
            }

            val entries = entriesByBook[book.id] ?: continue
            for (entry in entries) {
                if (!entry.enabled) continue

                val triggerSources = mutableListOf<String>()
                val matchedKeywords = mutableListOf<String>()
                var score = 0

                // 常驻条目
                if (entry.constant) {
                    triggerSources.add("always")
                    score += SOURCE_WEIGHTS["always"] ?: 0
                }

                val hasKeywords = !entry.keys.isNullOrBlank()
                val hasStateTriggers = false  // Android 简化：暂不支持 state_triggers

                if (!entry.constant && !hasKeywords && !hasStateTriggers) continue

                // 1. 用户消息源
                if (hasKeywords) {
                    val userHits = checkMatchKeywords(context.latestUserMessage, entry)
                    if (userHits.isNotEmpty() && satisfiesMatchMode(userHits, entry)) {
                        triggerSources.add("user")
                        score += SOURCE_WEIGHTS["user"] ?: 0
                        matchedKeywords.addAll(userHits)
                    }
                }

                // 2. 助手最近回复源
                if (config.enableAssistantTrigger && hasKeywords &&
                    assistantTriggeredCount < config.maxAssistantTriggeredEntries &&
                    entry.priority >= config.minAssistantPriority
                ) {
                    val assistantHits = checkMatchKeywords(assistantText, entry)
                    if (assistantHits.isNotEmpty() && satisfiesMatchMode(assistantHits, entry)) {
                        triggerSources.add("assistant_recent")
                        score += SOURCE_WEIGHTS["assistant_recent"] ?: 0
                        matchedKeywords.addAll(assistantHits)
                        assistantTriggeredCount++
                    }
                }

                // 3. 历史上下文源
                if (config.enableHistoryTrigger && hasKeywords) {
                    val historyHits = checkMatchKeywords(historyText, entry)
                    if (historyHits.isNotEmpty() && satisfiesMatchMode(historyHits, entry)) {
                        triggerSources.add("history")
                        score += SOURCE_WEIGHTS["history"] ?: 0
                        matchedKeywords.addAll(historyHits)
                    }
                }

                // 4. 场景状态触发源
                if (config.enableSceneTrigger && hasStateTriggers && sceneText.isNotEmpty()) {
                    if (checkStateTriggers(context.scene, entry)) {
                        triggerSources.add("scene_state")
                        score += SOURCE_WEIGHTS["scene_state"] ?: 0
                    }
                }

                if (triggerSources.isEmpty()) continue

                score += entry.priority

                // 关键词去重
                val dedupedKeywords = matchedKeywords.distinct()

                results.add(WorldBookMatchResult(
                    entry = entry,
                    triggerSources = triggerSources,
                    matchedKeywords = dedupedKeywords,
                    score = score
                ))
            }
        }

        // 排序：score 降序 → priority 降序 → entry_type 优先级降序 → content 长度升序
        val sorted = results.sortedWith(
            compareByDescending<WorldBookMatchResult> { it.score }
                .thenByDescending { it.entry.priority }
                .thenBy { (it.entry.content ?: "").length }
        )

        return sorted.take(config.maxEntries)
    }

    // ------------------------------------------------------------------
    // 辅助函数
    // ------------------------------------------------------------------

    /** 检查角色是否匹配（简化版：直接 ID 匹配 + 逗号分隔列表） */
    private fun characterMatches(characterId: String, bookCharacterIds: String): Boolean {
        if (bookCharacterIds.isBlank()) return true
        val ids = bookCharacterIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return characterId in ids
    }

    /** 关键词命中检测 */
    private fun checkMatchKeywords(text: String, entry: LocalWorldBookEntryEntity): List<String> {
        if (text.isEmpty()) return emptyList()
        val keysJson = entry.keys ?: return emptyList()
        if (keysJson.isBlank()) return emptyList()

        val keys = parseKeys(keysJson)
        if (keys.isEmpty()) return emptyList()

        val textToSearch = if (entry.caseSensitive) text else text.lowercase()
        return keys.filter { key ->
            val k = if (entry.caseSensitive) key else key.lowercase()
            k.isNotEmpty() && k in textToSearch
        }
    }

    /** match_mode 满足判定 */
    private fun satisfiesMatchMode(matchedKeywords: List<String>, entry: LocalWorldBookEntryEntity): Boolean {
        // Android 简化：selective 模式需至少命中 1 个，非 selective 也至少 1 个
        return matchedKeywords.isNotEmpty()
    }

    /** 场景状态触发器检测（简化版） */
    private fun checkStateTriggers(scene: Map<String, Any>, entry: LocalWorldBookEntryEntity): Boolean {
        // Android 暂不支持 state_triggers
        return false
    }

    /** 解析 keys JSON */
    private fun parseKeys(keysJson: String): List<String> {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            com.google.gson.Gson().fromJson(keysJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 抽取助手最近回复文本 */
    private fun extractAssistantRecentText(recentMessages: List<Map<String, String>>, limit: Int): String {
        return recentMessages.takeLast(limit)
            .filter { it["role"] == "assistant" }
            .joinToString(" ") { it["content"] ?: "" }
    }

    /** 抽取历史上下文文本 */
    private fun extractHistoryText(recentMessages: List<Map<String, String>>, maxChars: Int): String {
        val text = recentMessages.joinToString(" ") { "${it["role"] ?: ""}: ${it["content"] ?: ""}" }
        return if (text.length > maxChars) text.take(maxChars) else text
    }

    /** 抽取场景文本 */
    private fun extractSceneText(scene: Map<String, Any>): String {
        return scene.entries.joinToString(" ") { "${it.key}: ${it.value}" }
    }
}

// ============================================================================
// 世界书注入器
// ============================================================================

/**
 * 世界书条目注入 PromptStack，对应原仓库 nbot/character/world_book_injector.py。
 */
object WorldBookInjector {

    private const val MAX_ENTRY_CHARS = 2000
    private const val MAX_TOTAL_CHARS = 3000

    /**
     * 将匹配的世界书条目注入到 PromptStack。
     *
     * @param stack 提示词栈
     * @param results 匹配结果列表（已按 score 排序）
     * @param maxTotalChars 总字符上限
     */
    fun injectWorldBook(
        stack: PromptStack,
        results: List<WorldBookMatchResult>,
        maxTotalChars: Int = MAX_TOTAL_CHARS
    ) {
        if (results.isEmpty()) return

        val sections = mutableListOf<String>()
        var totalChars = 0

        for (result in results) {
            val content = (result.entry.content ?: "").trim()
            if (content.isEmpty()) continue

            val truncatedContent = if (content.length > MAX_ENTRY_CHARS) {
                content.take(MAX_ENTRY_CHARS) + "..."
            } else content

            val entryName = (result.entry.comment ?: "").trim()
            val section = if (entryName.isNotEmpty()) {
                "【$entryName】\n$truncatedContent"
            } else {
                truncatedContent
            }

            if (totalChars + section.length > maxTotalChars) continue

            sections.add(section)
            totalChars += section.length
        }

        if (sections.isEmpty()) return

        val finalContent = "以下是在当前对话中触发的世界观设定：\n\n${sections.joinToString("\n\n")}"
        stack.add("world_book", finalContent, priority = PromptStack.Priority.WORLD_BOOK, scope = "turn")
    }
}
