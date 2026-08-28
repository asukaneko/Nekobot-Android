package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.LocalLogger
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
    private val aiModelProvider: (suspend () -> LocalAiModelEntity?)? = null,
    private val failoverExecutor: LocalChatFailoverExecutor? = null,
    /**
     * 二级 LLM 调用 token 记账回调
     * 参数：source, model（配置名）, actualModel（实际模型标识，用于排行榜聚合）, inputTokens, outputTokens
     */
    private val onTokenUsage: ((String, String, String, Int, Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AutoMemory"
        private const val MEMORY_TURN_INTERVAL = 6
        private const val MAX_LLM_RETRIES = 3
        private val STRUCTURED_CATEGORIES = setOf("user_persona", "character_persona", "important_event", "recent_digest")
        // 截断策略（对齐原仓库 fs.py 的 _MAX_xxx 常量）
        private const val MAX_TIMELINE_STORE = 80           // timeline 文件持久化上限
        private const val MAX_EVENTS_PER_CONVERSATION = 30  // 单会话 important_event 条数上限
        private const val MAX_ENTRIES_PER_PATH = 10         // user_persona / character_persona 单 path 条数上限
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

        // 构建记忆上下文
        val memoryContext = callbacks.getMemoryContext(ctx)
        val characterId = (memoryContext["character_id"] as? String) ?: ""
        val characterName = (memoryContext["character_name"] as? String) ?: ""
        val targetId = (memoryContext["target_id"] as? String) ?: ctx.chatRequest.userId ?: ""
        val sessionId = (memoryContext["session_id"] as? String) ?: ctx.chatRequest.conversationId
        // 从 ctx.metadata 读取本会话玩家身份描述（由 LocalRepository.chatWithPipeline 注入），
        // 传给 LLM 让其按此描述称呼玩家，避免在记忆条目里写"用户"泛称。
        val userPersona = (ctx.metadata["user_persona"] as? String).orEmpty()

        return extractAndSave(
            characterId = characterId,
            characterName = characterName,
            targetId = targetId,
            sessionId = sessionId,
            userMessage = ctx.chatRequest.content,
            assistantMessage = result.finalContent,
            userPersona = userPersona
        )
    }

    /**
     * 记忆抽取核心逻辑（原始参数入口）。
     *
     * 供管线入口 [extractAndSaveTurnMemories] 与运行时 [LocalMemoryService] 共享，
     * 统一缓冲累积 / 触发间隔 / 失败回滚 / 分类归一化行为。
     *
     * @return 本轮保存的记忆条数（未触发或无产出时为 0）
     */
    suspend fun extractAndSave(
        characterId: String,
        characterName: String,
        targetId: String,
        sessionId: String,
        userMessage: String,
        assistantMessage: String,
        userPersona: String = ""
    ): Int {
        if (userMessage.length < 2 || assistantMessage.length < 2) return 0
        if (characterId.isEmpty() || targetId.isEmpty()) {
            LocalLogger.w(TAG, "记忆抽取跳过：characterId 或 targetId 为空 (characterId=$characterId targetId=$targetId)")
            return 0
        }

        // counterKey 采用与 AutoState 一致的 scope（characterId:sessionId:targetId）
        val counterKey = "$characterId:$sessionId:$targetId"
        val count = (turnCounters[counterKey] ?: 0) + 1
        turnCounters[counterKey] = count

        // 累积对话缓冲
        val buffer = turnBuffers.getOrPut(counterKey) { mutableListOf() }
        buffer.add(mapOf("user" to userMessage, "assistant" to assistantMessage))

        if (count < MEMORY_TURN_INTERVAL) {
            LocalLogger.i(TAG, "记忆抽取累积中 $count/$MEMORY_TURN_INTERVAL (key=$counterKey)")
            return 0
        }

        LocalLogger.i(TAG, "记忆抽取触发：$count 轮已达间隔 (key=$counterKey)")

        // 取出缓冲区
        val turns = buffer.toList()

        // 调用 LLM 提取记忆（含 3 次重试 + 附当前已有记忆供 LLM 取舍）
        // userPersona 为本会话配置的玩家身份描述（含玩家姓名/背景），传给 LLM 让其按此称呼玩家，
        // 避免在记忆条目里写"用户"泛称。注意：senderName 是 AI 扮演的角色名，不是玩家名。
        val memories = try {
            callMemoryModel(turns, characterName, targetId, userPersona, characterId, sessionId)
        } catch (e: Exception) {
            LocalLogger.w(TAG, "记忆抽取失败", e)
            // 失败回滚：放回缓冲区，计数器重置为间隔值（下一轮重试）
            turnBuffers[counterKey] = turns.toMutableList()
            turnCounters[counterKey] = MEMORY_TURN_INTERVAL
            return 0
        }

        if (memories.isEmpty()) {
            LocalLogger.w(TAG, "记忆抽取无产出（LLM 未返回有效记忆或解析失败）(key=$counterKey)")
            turnCounters[counterKey] = 0
            buffer.clear()
            return 0
        }

        turnCounters[counterKey] = 0
        buffer.clear()

        // 保存结构化记忆（按 category 分发到不同 path，区分 append/replace，同步派生 timeline）
        try {
            val savedCount = saveMemoriesByCategory(memories, characterId, targetId, sessionId)
            LocalLogger.i(TAG, "抽取并保存 $savedCount 条记忆 (key=$counterKey)")
            return savedCount
        } catch (e: Exception) {
            LocalLogger.w(TAG, "记忆落盘失败", e)
            // 落盘失败：放回缓冲区下一轮重试
            turnBuffers[counterKey] = turns.toMutableList()
            turnCounters[counterKey] = MEMORY_TURN_INTERVAL
            return 0
        }
    }

    /**
     * 按 category + action 分发保存记忆（对齐原仓库 _save_structured_memories_to_memory_fs）。
     *
     * action 字段由 LLM 决定，但 user_persona / recent_digest 固定为单槽替换：
     * - "append"：新增一条（适用于 important_event 和全新的 character_persona 信息）
     * - "replace"：替换该类别下的旧记忆（适用于 user_persona、persona 演化、recent_digest 刷新）
     *
     * 重要：important_event 同步派生 timeline 条目（跨会话时间线）
     */
    private suspend fun saveMemoriesByCategory(
        memories: List<Map<String, Any>>,
        characterId: String,
        targetId: String,
        sessionId: String
    ): Int {
        val now = Instant.now().toString()
        val convId = sessionId.takeIf { it.isNotBlank() } ?: "general"
        var saved = 0
        val timelineEntries = mutableListOf<LocalCharacterMemoryEntity>()

        for (memory in memories) {
            val category = (memory["category"] as? String) ?: "recent_digest"
            val action = (memory["action"] as? String)?.lowercase() ?: defaultActionForCategory(category)
            val path = buildMemoryPath(category, characterId, targetId, convId)
            when {
                isSingleSlotMemoryCategory(category) -> {
                    // 玩家人格和近期摘要都是单槽记忆；忽略 LLM 的 append，始终只保留最新一条。
                    val entity = memoryToEntityWithPath(memory, characterId, targetId, path, convId, now, version = 1)
                    memoryDao.replaceByCharacterTargetAndCategory(entity)
                    saved++
                }
                category == "important_event" -> {
                    // append：直接插入新行，后续 trim 截断
                    val entity = memoryToEntityWithPath(memory, characterId, targetId, path, convId, now, version = nextVersionForPath(path))
                    memoryDao.upsert(entity)
                    saved++
                    // 同步派生 timeline 条目（对齐原仓库 _append_to_timeline）
                    val timelineEntity = buildTimelineEntry(memory, characterId, convId, now)
                    timelineEntries.add(timelineEntity)
                }
                category == "character_persona" -> {
                    if (action == "replace") {
                        // LLM 决定 replace：删除同 path 旧值，插入新值
                        memoryDao.deleteByPath(path)
                        val entity = memoryToEntityWithPath(memory, characterId, targetId, path, convId, now, version = 1)
                        memoryDao.upsert(entity)
                    } else {
                        // append：累积到同 path，trim 保留最新 N 条
                        val entity = memoryToEntityWithPath(memory, characterId, targetId, path, convId, now, version = nextVersionForPath(path))
                        memoryDao.upsert(entity)
                    }
                    saved++
                }
                else -> {
                    // 未知 category 走 legacy path（按 targetId 隔离）
                    val legacyPath = "characters/$characterId/users/$targetId/legacy.md"
                    val entity = memoryToEntityWithPath(memory, characterId, targetId, legacyPath, convId, now, version = nextVersionForPath(legacyPath))
                    memoryDao.upsert(entity)
                    saved++
                }
            }
        }

        // 批量写入 timeline 派生条目
        if (timelineEntries.isNotEmpty()) {
            memoryDao.upsertAll(timelineEntries)
            // 截断 timeline：保留最新 80 条（对齐原仓库 _MAX_TIMELINE_STORE）
            memoryDao.trimByCharacterAndCategory(characterId, "timeline", keep = MAX_TIMELINE_STORE)
            LocalLogger.i(TAG, "timeline 派生 ${timelineEntries.size} 条，已截断至 $MAX_TIMELINE_STORE 条")
        }

        // 截断 important_event 单会话文件（保留最新 30 条）
        val eventsPath = buildMemoryPath("important_event", characterId, targetId, convId)
        memoryDao.trimByPath(eventsPath, keep = MAX_EVENTS_PER_CONVERSATION)

        // character_persona 仍可 append，保留最新 10 条；user_persona 已在写入时原子替换为 1 条。
        memoryDao.trimByPath(buildMemoryPath("character_persona", characterId, targetId, convId), keep = MAX_ENTRIES_PER_PATH)

        return saved
    }

    /** 构造记忆逻辑路径（对齐原仓库 fs.py 的 path_xxx 辅助方法） */
    private fun buildMemoryPath(category: String, characterId: String, targetId: String, conversationId: String): String {
        return when (category) {
            "user_persona" -> "characters/$characterId/users/$targetId/user_persona.md"
            "character_persona" -> "characters/$characterId/users/$targetId/character_persona.md"
            "important_event" -> "characters/$characterId/events/$conversationId.md"
            "timeline" -> "characters/$characterId/timeline.md"
            "life_sim" -> "characters/$characterId/life_sim/$conversationId.md"
            "recent_digest" -> "characters/$characterId/users/$targetId/recent_digest.md"
            else -> "characters/$characterId/users/$targetId/legacy.md"
        }
    }

    /** 当 LLM 未返回 action 字段时的默认策略 */
    private fun defaultActionForCategory(category: String): String {
        return when (category) {
            "important_event" -> "append"
            "user_persona", "recent_digest" -> "replace"
            "character_persona" -> "append"  // 默认 append，LLM 可主动返回 replace
            else -> "append"
        }
    }

    /** 获取同 path 的下一个 version 号 */
    private suspend fun nextVersionForPath(path: String): Int {
        val existing = memoryDao.listByPath(path)
        return (existing.maxOfOrNull { it.version } ?: 0) + 1
    }

    /**
     * 构造 timeline 派生条目（对齐原仓库 _append_to_timeline）。
     * 格式：title + content 首行摘要，带 conversationId 标记以便注入时分桶。
     */
    private fun buildTimelineEntry(
        memory: Map<String, Any>,
        characterId: String,
        conversationId: String,
        now: String
    ): LocalCharacterMemoryEntity {
        val title = (memory["title"] as? String) ?: ""
        val content = (memory["content"] as? String) ?: ""
        val importance = ((memory["importance"] as? Number)?.toFloat() ?: 0.8f) * 10
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        // timeline 条目格式对齐原仓库：[YYYY-MM-DD HH:MM] [conv:session_id] title: content首行
        val timelineContent = "[$ts] [conv:$conversationId] $title: ${content.take(120)}"
        return LocalCharacterMemoryEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            targetId = "timeline",  // timeline 是全局跨会话的，targetId 用 "timeline" 标记
            type = "long",
            category = "timeline",
            title = title,
            summary = title,
            content = timelineContent,
            importance = importance.toInt().coerceIn(1, 10),
            createdAt = now,
            memoryPath = buildMemoryPath("timeline", characterId, "timeline", conversationId),
            version = 1,
            updatedAt = now,
            conversationId = conversationId
        )
    }

    /** 转换为 Entity（带 path/version/updatedAt/conversationId） */
    private suspend fun memoryToEntityWithPath(
        memory: Map<String, Any>,
        characterId: String,
        targetId: String,
        path: String,
        conversationId: String,
        now: String,
        version: Int
    ): LocalCharacterMemoryEntity {
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
            createdAt = now,
            memoryPath = path,
            version = version,
            updatedAt = now,
            conversationId = conversationId
        )
    }

    /**
     * 调用 LLM 提取记忆（含 3 次重试）。
     *
     * @param turns 本轮对话缓冲
     * @param characterName 角色名
     * @param targetId 玩家 ID（用于读取已有记忆路径）
     * @param userPersona 玩家身份描述（含姓名/背景，注入 prompt 让 LLM 据此称呼玩家）
     * @param characterId 角色 ID（用于读取当前已有记忆）
     * @param sessionId 会话 ID（用于读取/隔离记忆）
     */
    private suspend fun callMemoryModel(
        turns: List<Map<String, String>>,
        characterName: String,
        targetId: String,
        userPersona: String,
        characterId: String,
        sessionId: String
    ): List<Map<String, Any>> {
        val fallbackModel = if (failoverExecutor == null) aiModelProvider?.invoke() else null
        if (failoverExecutor == null && fallbackModel == null) {
            LocalLogger.w(TAG, "记忆抽取跳过：未配置激活的 AI 模型（aiModelProvider 返回 null）")
            return emptyList()
        }

        // 读取当前已有记忆，供 LLM 决定 append/replace（按 targetId 查记忆路径）
        val existingMemories = readExistingMemoriesForPrompt(characterId, targetId, sessionId)

        val systemPrompt = buildMemorySystemPrompt(characterName, userPersona)
        val userPrompt = buildMemoryUserPrompt(turns, characterName, userPersona, existingMemories)

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        LocalLogger.i(TAG, "调用记忆抽取 LLM: turns=${turns.size} existing=${existingMemories.size}")

        // 3 次重试
        repeat(MAX_LLM_RETRIES) { attempt ->
            try {
                val execution = failoverExecutor?.execute(messages)
                val result = execution?.value ?: aiClient.chatOnce(fallbackModel!!, messages)
                val usedModel = execution?.model ?: fallbackModel!!
                // 记账二级 LLM 调用 token（与主对话区分，source=memory）
                if (result.usage.isNotEmpty()) {
                    onTokenUsage?.invoke(
                        "memory", usedModel.name, usedModel.model,
                        result.usage["prompt"] ?: 0,
                        result.usage["completion"] ?: 0
                    )
                }
                if (result.error != null) {
                    LocalLogger.w(TAG, "记忆抽取 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次失败")
                    return@repeat
                }
                if (result.content.isBlank()) {
                    LocalLogger.w(TAG, "记忆抽取 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次返回空内容")
                    return@repeat
                }

                LocalLogger.i(TAG, "记忆抽取 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次成功，返回 ${result.content.length} 字符，开始解析")
                val parsed = parseMemoryResponse(result.content)
                if (parsed.isEmpty()) {
                    LocalLogger.w(TAG, "记忆抽取 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次解析失败，将重试")
                    return@repeat
                }
                return parsed
            } catch (e: Exception) {
                LocalLogger.w(TAG, "记忆抽取 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次异常", e)
            }
        }
        LocalLogger.w(TAG, "记忆抽取 LLM $MAX_LLM_RETRIES 次重试均失败")
        return emptyList()
    }

    /**
     * 读取当前已有记忆，供 LLM 在 prompt 中参考以决定 append/replace。
     *
     * 只读取需要"取舍"的 3 类（user_persona / character_persona / recent_digest），
     * important_event 始终 append，无需读取。
     */
    private suspend fun readExistingMemoriesForPrompt(
        characterId: String,
        targetId: String,
        sessionId: String
    ): List<ExistingMemoryView> {
        val convId = sessionId.takeIf { it.isNotBlank() } ?: "general"
        val result = mutableListOf<ExistingMemoryView>()
        try {
            for (category in listOf("user_persona", "character_persona", "recent_digest")) {
                val path = buildMemoryPath(category, characterId, targetId, convId)
                val entries = memoryDao.listByPath(path).let {
                    if (category == "user_persona") it.take(1) else it
                }
                if (entries.isNotEmpty()) {
                    result.add(ExistingMemoryView(category, entries.map { it.content }))
                }
            }
        } catch (e: Exception) {
            LocalLogger.w(TAG, "读取已有记忆失败（不影响主流程）")
        }
        return result
    }

    /** 已有记忆的简化视图（供 prompt 展示） */
    private data class ExistingMemoryView(val category: String, val contents: List<String>)

    /** 构建记忆抽取 system prompt（强制生成 4 类 + action 字段） */
    private fun buildMemorySystemPrompt(characterName: String, userPersona: String): String {
        // userPersona 为本会话配置的玩家身份描述（含姓名/背景），引导 LLM 从中识别玩家姓名
        // 并在记忆条目中用该姓名指代玩家，避免写"用户"泛称。
        // 注意：senderName 是 AI 扮演的角色名，不是玩家名，不能用作玩家标签。
        val personaSection = if (userPersona.isNotBlank()) {
            """玩家身份描述如下（请从中识别玩家姓名，并在记忆条目中用该姓名指代玩家）：
$userPersona"""
        } else {
            "本会话未提供玩家身份描述，记忆条目中请用「玩家」指代玩家。"
        }
        return """你是一个记忆抽取中间件，不是角色扮演角色。

任务：从对话中提取值得长期记忆的信息，返回 JSON 数组。

$personaSection

必须尽量覆盖以下 4 个类别（如果对话中确实没有相关信息，对应类别可省略）：
1. "user_persona" — 玩家的人格特征、偏好、习惯、身份信息
2. "character_persona" — 角色对玩家的态度、关系变化、情感进展
3. "important_event" — 重要事件、剧情转折、关键互动
4. "recent_digest" — 本轮对话的摘要（一两句话概括发生了什么）

每个记忆条目格式：
{"category":"类别", "action":"append或replace", "title":"简短标题", "summary":"一句话摘要", "content":"详细内容", "importance":0.0-1.0}

action 字段说明：
- "append"：新增一条记忆（适用于全新的信息、新发生的事件）
- "replace"：替换该类别下的所有旧记忆（适用于信息更新、persona 演化、recent_digest 摘要刷新）

action 决策规则：
- important_event：始终用 "append"
- recent_digest：始终用 "replace"（每轮覆盖旧摘要）
- user_persona：始终用 "replace"，将已有信息与本轮新信息整合成一条完整的最新玩家人格，禁止拆成多条或 append
- character_persona：如果新信息与已有记忆冲突或需要更新演化，用 "replace"；如果是全新的独立信息，用 "append"

要求：
- 只提取与角色 "$characterName" 相关的有长期价值的信息
- 写记忆时禁止使用「用户」泛称指代玩家；若玩家身份描述中给出了姓名，必须用该姓名，否则用「玩家」
- 忽略寒暄和闲聊
- 尽量覆盖前 4 个类别，每类 1 条（重要的可多条）
- 写摘要不写原始对话转录
- importance 根据信息重要性评估（0.0-1.0）
- 所有字段用${AiOutputLanguage.languageName()}

只返回 JSON 数组，不要其他文字。"""
    }

    /** 构建记忆抽取 user prompt（附当前已有记忆供 LLM 取舍） */
    private fun buildMemoryUserPrompt(
        turns: List<Map<String, String>>,
        characterName: String,
        userPersona: String,
        existingMemories: List<ExistingMemoryView>
    ): String {
        // turn 标签用中性「玩家」，避免"用户"字样；LLM 会按 system prompt 中的玩家身份描述识别姓名
        val turnTexts = turns.mapIndexed { idx, turn ->
            "--- Turn ${idx + 1} ---\n玩家:\n${turn["user"] ?: ""}\n\n$characterName:\n${turn["assistant"] ?: ""}"
        }.joinToString("\n\n")

        val parts = mutableListOf("请从以下对话中提取记忆（在记忆条目中按玩家身份描述里的姓名指代玩家，不要写「用户」）：\n\n$turnTexts")

        // 附上当前已有记忆，让 LLM 决定 append/replace
        if (existingMemories.isNotEmpty()) {
            val existingText = existingMemories.joinToString("\n\n") { view ->
                val label = when (view.category) {
                    "user_persona" -> "玩家人格（当前）"
                    "character_persona" -> "角色人格（当前）"
                    "recent_digest" -> "近期摘要（当前）"
                    else -> view.category
                }
                val body = view.contents.joinToString("\n---\n") { it }
                "[$label]\n$body"
            }
            parts.add("\n\n=== 当前已有记忆（供参考，决定 append 还是 replace）===\n$existingText")
        }

        return parts.joinToString("\n")
    }

    /** 解析 LLM 返回的记忆 JSON */
    fun parseMemoryResponse(text: String): List<Map<String, Any>> {
        val cleaned = cleanResponseContent(text)
        if (cleaned.isEmpty()) {
            LocalLogger.w(TAG, "记忆抽取解析：cleanResponseContent 后为空（原始长度=${text.length}）")
            return emptyList()
        }

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
            if (match == null) {
                LocalLogger.w(TAG, "记忆抽取解析：无法识别为 JSON 数组或对象（原始长度=${text.length}）")
                return emptyList()
            }
            match.value
        }

        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            @Suppress("UNCHECKED_CAST")
            val list = gson.fromJson<List<Map<String, Any>>>(arrayStr, type) ?: run {
                LocalLogger.w(TAG, "记忆抽取解析：Gson 返回 null（候选 JSON 长度=${arrayStr.length}）")
                return emptyList()
            }
            val normalized = list.mapNotNull { item -> normalizeMemoryItem(item) }
            if (normalized.isEmpty() && list.isNotEmpty()) {
                LocalLogger.w(TAG, "记忆抽取解析：LLM 返回 ${list.size} 条但归一化后全部被丢弃")
            }
            normalized.take(8)
        } catch (e: Exception) {
            LocalLogger.w(TAG, "记忆抽取解析 JSON 失败（候选 JSON 长度=${arrayStr.length}）", e)
            emptyList()
        }
    }

    /** 归一化记忆条目 */
    private fun normalizeMemoryItem(item: Map<String, Any>): Map<String, Any>? {
        val category = (item["category"] as? String ?: item["kind"] as? String ?: item["memory_category"] as? String ?: "")
            .trim().lowercase().replace("-", "_").replace(" ", "_")
        val aliased = MEMORY_CATEGORY_ALIASES[category] ?: category
        if (aliased !in STRUCTURED_CATEGORIES) {
            LocalLogger.w(TAG, "记忆条目被丢弃：分类不在允许范围")
            return null
        }

        var title = (item["title"] as? String ?: "").trim()
        val summary = (item["summary"] as? String ?: "").trim()
        var content = (item["content"] as? String ?: "").trim()

        if (title.isEmpty()) title = summary.take(30)
        if (content.isEmpty()) content = summary
        if (title.isEmpty() || content.isEmpty()) {
            LocalLogger.w(TAG, "记忆条目被丢弃：标题或内容为空（内容长度=${content.length}）")
            return null
        }

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

    /** 转换为 Entity（旧版兼容，无 path/version 信息） */
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
