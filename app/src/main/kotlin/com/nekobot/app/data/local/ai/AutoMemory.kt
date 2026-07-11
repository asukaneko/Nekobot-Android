package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterMemoryEntity
import com.nekobot.app.data.local.db.MemoryDao
import java.time.Instant
import java.util.UUID

/**
 * 完整版自动记忆抽取，对应原仓库 nbot/core/auto_memory.py。
 *
 * 每 6 轮对话触发一次 LLM 记忆抽取，提取 4 类结构化记忆：
 * user_persona / character_persona / important_event / recent_digest。
 *
 * 与 MemoryFS.kt 中的简化版 LocalMemoryService 区别：
 * - 完整的 LLM prompt（角色定位 + 4 类记忆说明 + 语言指令）
 * - 结构化分类存储
 * - 失败回滚缓冲区
 * - 跨会话时间线（简化为 category 标记）
 */
class AutoMemory(
    private val memoryDao: MemoryDao,
    private val aiClient: LocalAiClient,
    private val aiModelProvider: (suspend () -> LocalAiModelEntity?)? = null
) {
    companion object {
        private const val TAG = "AutoMemory"
        private const val MEMORY_TURN_INTERVAL = 6
        private val STRUCTURED_CATEGORIES = setOf("user_persona", "character_persona", "important_event", "recent_digest")
    }

    private val gson = Gson()
    private val turnCounters = mutableMapOf<String, Int>()
    private val turnBuffers = mutableMapOf<String, MutableList<Map<String, String>>>()

    /**
     * 抽取并保存记忆（主入口）。
     *
     * @param ctx 管线上下文
     * @param callbacks 管线回调
     * @param result 管线结果
     * @return 保存的记忆数
     */
    suspend fun extractAndSaveTurnMemories(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        result: PipelineResult
    ): Int {
        // 跳过错误响应
        if (result.error != null) return 0

        // 跳过心跳/标记
        if (ctx.metadata["is_heartbeat"] == true) return 0
        if (ctx.metadata["skip_auto_memory"] == true) return 0

        val userMessage = ctx.chatRequest.content
        val assistantMessage = result.finalContent
        if (userMessage.length < 2 || assistantMessage.length < 2) return 0

        // 构建记忆上下文
        val memoryContext = callbacks.getMemoryContext(ctx)
        val characterId = (memoryContext["character_id"] as? String) ?: ""
        val characterName = (memoryContext["character_name"] as? String) ?: ""
        val targetId = (memoryContext["target_id"] as? String) ?: ctx.chatRequest.userId ?: ""
        val sessionId = (memoryContext["session_id"] as? String) ?: ctx.chatRequest.conversationId

        if (characterId.isEmpty() || targetId.isEmpty()) return 0

        val counterKey = "$characterId:$targetId:$sessionId"
        val count = (turnCounters[counterKey] ?: 0) + 1
        turnCounters[counterKey] = count

        // 累积对话缓冲
        val buffer = turnBuffers.getOrPut(counterKey) { mutableListOf() }
        buffer.add(mapOf("user" to userMessage, "assistant" to assistantMessage))

        if (count < MEMORY_TURN_INTERVAL) return 0

        // 取出缓冲区
        val turns = buffer.toList()

        // 调用 LLM 提取记忆
        val memories = try {
            callMemoryModel(turns, characterName, targetId)
        } catch (e: Exception) {
            Log.w(TAG, "记忆抽取失败: ${e.message}")
            // 失败回滚：放回缓冲区，计数器重置为间隔值（下一轮重试）
            turnBuffers[counterKey] = turns.toMutableList()
            turnCounters[counterKey] = MEMORY_TURN_INTERVAL
            return 0
        }

        if (memories.isEmpty()) {
            turnCounters[counterKey] = 0
            buffer.clear()
            return 0
        }

        turnCounters[counterKey] = 0
        buffer.clear()

        // 保存结构化记忆
        val entities = memories.map { memoryToEntity(it, characterId, targetId) }
        memoryDao.upsertAll(entities)
        Log.d(TAG, "抽取并保存 ${memories.size} 条记忆 (key=$counterKey)")
        return memories.size
    }

    /** 调用 LLM 提取记忆 */
    private suspend fun callMemoryModel(
        turns: List<Map<String, String>>,
        characterName: String,
        userName: String
    ): List<Map<String, Any>> {
        val model = aiModelProvider?.invoke() ?: return emptyList()

        val systemPrompt = buildMemorySystemPrompt(characterName)
        val userPrompt = buildMemoryUserPrompt(turns, characterName, userName)

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val result = aiClient.chatOnce(model, messages)
        if (result.error != null || result.content.isBlank()) return emptyList()

        return parseMemoryResponse(result.content)
    }

    /** 构建记忆抽取 system prompt */
    private fun buildMemorySystemPrompt(characterName: String): String {
        return """你是一个记忆抽取中间件，不是角色扮演角色。

任务：从对话中提取值得长期记忆的信息，返回 JSON 数组。

记忆类别：
1. "user_persona" — 用户的人格特征、偏好、习惯
2. "character_persona" — 角色对用户的态度、关系变化
3. "important_event" — 重要事件、剧情转折
4. "recent_digest" — 近期对话摘要

每个记忆条目格式：
{"category":"类别", "title":"简短标题", "summary":"一句话摘要", "content":"详细内容", "importance":0.0-1.0}

要求：
- 只提取与角色 "$characterName" 相关的有长期价值的信息
- 忽略寒暄和闲聊
- 每类最多 1 条，最多 4 条
- 写摘要不写原始对话转录
- importance 根据信息重要性评估（0.0-1.0）
- 所有字段用中文

只返回 JSON 数组，不要其他文字。"""
    }

    /** 构建记忆抽取 user prompt */
    private fun buildMemoryUserPrompt(
        turns: List<Map<String, String>>,
        characterName: String,
        userName: String
    ): String {
        val turnTexts = turns.mapIndexed { idx, turn ->
            "--- Turn ${idx + 1} ---\n用户 ($userName):\n${turn["user"] ?: ""}\n\n$characterName:\n${turn["assistant"] ?: ""}"
        }.joinToString("\n\n")
        return "请从以下对话中提取记忆：\n\n$turnTexts"
    }

    /** 解析 LLM 返回的记忆 JSON */
    fun parseMemoryResponse(text: String): List<Map<String, Any>> {
        val cleaned = cleanResponseContent(text)
        if (cleaned.isEmpty()) return emptyList()

        // 尝试解析数组
        val arrayStr = if (cleaned.startsWith("[")) {
            cleaned
        } else if (cleaned.startsWith("{")) {
            // 可能是 {memories: [...]} 或单条
            try {
                @Suppress("UNCHECKED_CAST")
                val obj = gson.fromJson(cleaned, Map::class.java) as? Map<String, Any>
                when {
                    obj?.containsKey("memories") == true -> gson.toJson(obj["memories"])
                    obj?.containsKey("items") == true -> gson.toJson(obj["items"])
                    obj?.containsKey("title") == true -> "[$cleaned]"
                    else -> "[$cleaned]"
                }
            } catch (e: Exception) { "[$cleaned]" }
        } else {
            // 正则提取数组
            val match = Regex("""\[[\s\S]*\]""").find(cleaned)
            match?.value ?: return emptyList()
        }

        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson<List<Map<String, Any>>>(arrayStr, type) ?: return emptyList()
            list.mapNotNull { item -> normalizeMemoryItem(item) }.take(8)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 归一化记忆条目 */
    private fun normalizeMemoryItem(item: Map<String, Any>): Map<String, Any>? {
        val category = (item["category"] as? String ?: item["kind"] as? String ?: item["memory_category"] as? String ?: "")
            .trim().lowercase().replace("-", "_").replace(" ", "_")
        val aliased = MEMORY_CATEGORY_ALIASES[category] ?: category
        if (aliased !in STRUCTURED_CATEGORIES) return null

        var title = (item["title"] as? String ?: "").trim()
        val summary = (item["summary"] as? String ?: "").trim()
        var content = (item["content"] as? String ?: "").trim()

        if (title.isEmpty()) title = summary.take(30)
        if (content.isEmpty()) content = summary
        if (title.isEmpty() || content.isEmpty()) return null

        val importance = coerceImportance(item["importance"])

        return mapOf(
            "category" to aliased,
            "title" to title.take(80),
            "summary" to summary.take(200),
            "content" to content.take(2000),
            "importance" to importance,
            "type" to "long"
        )
    }

    /** 钳制 importance 到 [0, 1] */
    private fun coerceImportance(value: Any?): Float {
        return when (value) {
            is Number -> value.toFloat().coerceIn(0f, 1f)
            is String -> value.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
            else -> 0.5f
        }
    }

    /** 记忆类别别名 */
    private val MEMORY_CATEGORY_ALIASES = mapOf(
        "user" to "user_persona",
        "user_profile" to "user_persona",
        "user_preference" to "user_persona",
        "character" to "character_persona",
        "character_profile" to "character_persona",
        "character_attitude" to "character_persona",
        "relationship" to "character_persona",
        "event" to "important_event",
        "events" to "important_event",
        "important_events" to "important_event",
        "plot" to "important_event",
        "digest" to "recent_digest",
        "summary" to "recent_digest",
        "recent_summary" to "recent_digest"
    )

    /** 转换为 Entity */
    private fun memoryToEntity(memory: Map<String, Any>, characterId: String, targetId: String): LocalCharacterMemoryEntity {
        val importance = ((memory["importance"] as? Number)?.toFloat() ?: 0.5f) * 10  // 0-1 → 0-10
        return LocalCharacterMemoryEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            targetId = targetId,
            type = (memory["type"] as? String) ?: "long",
            category = (memory["category"] as? String) ?: "recent_digest",
            title = (memory["title"] as? String) ?: "",
            summary = (memory["summary"] as? String) ?: "",
            content = (memory["content"] as? String) ?: "",
            importance = importance.toInt().coerceIn(1, 10),
            createdAt = Instant.now().toString()
        )
    }
}
