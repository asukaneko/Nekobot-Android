package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.ai.LocalAiResult
import com.nekobot.app.data.local.ai.LocalPromptBuilder
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.ApiResult
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 本地模式仓库：直接操作 Room + 调用 AI 接口。
 *
 * 对外提供与 [com.nekobot.app.data.repository.NekobotRepository] 接口一致的方法签名
 * （使用 [com.nekobot.app.data.repository.Resource] 包装），
 * 由 [com.nekobot.app.data.repository.UnifiedRepository] 按 mode 分发调用。
 *
 * 聊天流程：
 * 1. 保存用户消息 → 2. 加载角色卡+世界书 → 3. 构造 prompt → 4. 调用 AI 流式 →
 * 5. 边收 chunk 边推 Flow → 6. 流结束后保存 assistant 消息 → 7. 更新 session 元信息
 */
class LocalRepository(
    private val db: NekobotDatabase,
    private val aiClient: LocalAiClient = LocalAiClient(),
    private val appContext: android.content.Context? = null
) {
    private val gson = Gson()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val characterDao = db.characterDao()
    private val worldBookDao = db.worldBookDao()
    private val aiModelDao = db.aiModelDao()

    /** 当前正在进行的聊天 Job，用于 stopGeneration */
    @Volatile
    private var currentChatJob: Job? = null

    // ==================== 会话 ====================

    suspend fun listSessions(): List<Session> = withContext(Dispatchers.IO) {
        sessionDao.listAll().map { it.toSession() }
    }

    fun observeSessions(): Flow<List<LocalSessionEntity>> = sessionDao.observeAll()

    suspend fun getSession(id: String): Session? = withContext(Dispatchers.IO) {
        sessionDao.getById(id)?.toSession()
    }

    suspend fun createSession(req: CreateSessionRequest): Session = withContext(Dispatchers.IO) {
        val now = nowIso()
        val id = UUID.randomUUID().toString()
        val character = req.characterId?.let { characterDao.getById(it) }
        val entity = LocalSessionEntity(
            id = id,
            name = req.name ?: character?.name ?: "新会话",
            characterId = req.characterId,
            systemPrompt = req.systemPrompt,
            firstMessage = req.firstMessage ?: character?.firstMessage,
            scenario = req.scenario ?: character?.scenario,
            senderName = req.senderName,
            senderAvatar = req.senderAvatar,
            characterName = character?.name,
            characterAvatar = character?.avatar,
            portrait = character?.portrait ?: req.senderPortrait,
            tags = req.tags?.joinToString(","),
            createdAt = now,
            updatedAt = now
        )
        sessionDao.upsert(entity)
        entity.toSession()
    }

    suspend fun updateSession(id: String, name: String?, systemPrompt: String?, favorite: Boolean?) =
        withContext(Dispatchers.IO) {
            val entity = sessionDao.getById(id) ?: return@withContext
            val updated = entity.copy(
                name = name ?: entity.name,
                systemPrompt = systemPrompt ?: entity.systemPrompt,
                favorite = favorite ?: entity.favorite,
                updatedAt = nowIso()
            )
            sessionDao.upsert(updated)
        }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        sessionDao.deleteById(id)
    }

    // ==================== 消息 ====================

    suspend fun listMessages(sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        messageDao.listBySession(sessionId).map { it.toMessage() }
    }

    fun observeMessages(sessionId: String): Flow<List<LocalMessageEntity>> =
        messageDao.observeBySession(sessionId)

    suspend fun addMessage(sessionId: String, role: String, content: String, sender: String? = null) =
        withContext(Dispatchers.IO) {
            val now = nowIso()
            val msg = LocalMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = role,
                content = content,
                sender = sender,
                timestamp = now,
                createdAt = now
            )
            messageDao.upsert(msg)
            // 更新会话元信息
            val session = sessionDao.getById(sessionId)
            if (session != null) {
                sessionDao.touch(
                    sessionId,
                    lastMessage = content.take(200),
                    count = messageDao.countBySession(sessionId),
                    updatedAt = now
                )
            }
            msg.toMessage()
        }

    /** 保存 assistant 消息并记录 token 用量与模型名。 */
    suspend fun addAssistantMessage(
        sessionId: String,
        content: String,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        model: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = nowIso()
        val msg = LocalMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "assistant",
            content = content,
            sender = "assistant",
            timestamp = now,
            createdAt = now,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens
        )
        messageDao.upsert(msg)
        val session = sessionDao.getById(sessionId)
        if (session != null) {
            sessionDao.touch(
                sessionId,
                lastMessage = content.take(200),
                count = messageDao.countBySession(sessionId),
                updatedAt = now
            )
        }
        msg.toMessage()
    }

    suspend fun deleteMessage(sessionId: String, messageId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteById(messageId)
        sessionDao.touch(
            sessionId,
            lastMessage = messageDao.listBySession(sessionId).lastOrNull()?.content?.take(200) ?: "",
            count = messageDao.countBySession(sessionId),
            updatedAt = nowIso()
        )
    }

    suspend fun clearMessages(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteBySession(sessionId)
        sessionDao.touch(sessionId, "", 0, nowIso())
    }

    // ==================== 聊天 ====================

    /**
     * 流式聊天：保存用户消息 → 调用 AI → 边推 Flow 边累积 → 保存 assistant。
     */
    fun chat(
        sessionId: String,
        userMessage: String,
        activeModel: LocalAiModelEntity
    ): Flow<RealtimeEvent> = flow {
        // 1. 保存用户消息
        addMessage(sessionId, "user", userMessage)

        // 2. 加载会话 + 角色 + 世界书 + 历史
        val session = sessionDao.getById(sessionId)
            ?: run {
                emit(RealtimeEvent.Error("会话不存在"))
                emit(RealtimeEvent.StreamEnd(sessionId))
                return@flow
            }
        val character = session.characterId?.let { characterDao.getById(it) }
        val history = messageDao.listBySession(sessionId)
            .filter { it.role != "system" }
            .dropLast(1)  // 最后一条是刚保存的用户消息，会在 prompt 中单独处理
        val worldBookEntries = loadWorldBookEntries(session.characterId)

        // 3. 构造 prompt
        val messages = LocalPromptBuilder.build(
            session, character, history, userMessage, worldBookEntries
        )

        // 4. 构造 extra 参数
        val extra = buildMap<String, Any?> {
            activeModel.temperature?.let { put("temperature", it) }
            activeModel.maxTokens?.let { put("max_tokens", it) }
            activeModel.topP?.let { put("top_p", it) }
        }

        // 5. 流式调用
        val fullContent = StringBuilder()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var modelName: String? = null
        aiClient.chatStream(activeModel, messages, extra).collect { event ->
            when (event) {
                is RealtimeEvent.StreamChunk -> fullContent.append(event.chunk)
                is RealtimeEvent.Usage -> {
                    inputTokens = event.inputTokens
                    outputTokens = event.outputTokens
                    modelName = event.model
                    emit(event)
                }
                is RealtimeEvent.Error -> emit(event)
                is RealtimeEvent.StreamEnd -> {
                    // 保存 assistant 消息（含 token 用量）
                    val content = fullContent.toString().trim()
                    if (content.isNotEmpty()) {
                        addAssistantMessage(sessionId, content, inputTokens, outputTokens, modelName ?: activeModel.model)
                    }
                    emit(RealtimeEvent.StreamEnd(sessionId))
                }
                else -> emit(event)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 重新生成：删除从 [messageId] 开始的所有消息，然后用上一条 user 消息重新请求。
     * 若 [messageId] 为空，默认删除最后一条 assistant 消息。
     */
    fun regenerate(
        sessionId: String,
        messageId: String?,
        activeModel: LocalAiModelEntity
    ): Flow<RealtimeEvent> = flow {
        val messages = messageDao.listBySession(sessionId)
        val targetId = messageId ?: messages.lastOrNull { it.role == "assistant" }?.id
        if (targetId == null) {
            emit(RealtimeEvent.Error("没有可重新生成的消息"))
            emit(RealtimeEvent.StreamEnd(sessionId))
            return@flow
        }
        // 找到目标消息位置，删除它及之后所有消息
        val targetIdx = messages.indexOfFirst { it.id == targetId }
        if (targetIdx < 0) {
            emit(RealtimeEvent.Error("消息不存在"))
            emit(RealtimeEvent.StreamEnd(sessionId))
            return@flow
        }
        // 找到目标之前最后一条 user 消息作为重新生成的输入
        val lastUserBefore = messages.subList(0, targetIdx).lastOrNull { it.role == "user" }
        // 删除目标及之后所有消息
        messages.subList(targetIdx, messages.size).forEach { messageDao.deleteById(it.id) }

        val userInput = lastUserBefore?.content ?: run {
            emit(RealtimeEvent.Error("找不到要重新生成的用户消息"))
            emit(RealtimeEvent.StreamEnd(sessionId))
            return@flow
        }
        // 删除那条 user 消息（chat 会重新添加）
        lastUserBefore?.let { messageDao.deleteById(it.id) }

        // 复用 chat 流程
        chat(sessionId, userInput, activeModel).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /** 停止生成：取消当前 Job（外层 collect 会被取消）。 */
    fun stopGeneration() {
        currentChatJob?.cancel()
        currentChatJob = null
    }

    // ==================== Pipeline 驱动的聊天（角色运行时） ====================

    /**
     * 使用 AIPipeline + 角色运行时的流式聊天。
     *
     * 与 [chat] 相比，此方法：
     * - 经过 7 阶段 Pipeline（附件→知识库→上下文→AI响应→结果组装）
     * - 启用角色运行时（SignalAnalyzer → ReactionPlanner → StateMachine）
     * - 注入 PromptStack 动态提示词（状态/关系/记忆/世界书）
     * - 自动记忆抽取（每 6 轮）
     *
     * 若会话未绑定角色，会回退到普通 [chat] 流程。
     *
     * @param sessionId 会话 ID
     * @param userMessage 用户消息
     * @param activeModel 激活的 AI 模型
     * @return RealtimeEvent 流
     */
    fun chatWithPipeline(
        sessionId: String,
        userMessage: String,
        activeModel: LocalAiModelEntity
    ): Flow<RealtimeEvent> = flow {
        // 1. 保存用户消息
        addMessage(sessionId, "user", userMessage)

        val session = sessionDao.getById(sessionId) ?: run {
            emit(RealtimeEvent.Error("会话不存在"))
            emit(RealtimeEvent.StreamEnd(sessionId))
            return@flow
        }

        val character = session.characterId?.let { characterDao.getById(it) }

        // 无角色绑定 → 回退到旧流程
        if (character == null) {
            // 回退时需删除刚保存的用户消息（chat 会重新保存）
            messageDao.listBySession(sessionId).lastOrNull { it.role == "user" }?.let {
                messageDao.deleteById(it.id)
            }
            chat(sessionId, userMessage, activeModel).collect { emit(it) }
            return@flow
        }

        // 2. 加载世界书条目
        val worldBookEntries = loadWorldBookEntries(session.characterId)

        // 3. 构建角色运行时
        val profileRepo = com.nekobot.app.data.local.ai.LocalProfileRepository(characterDao)
        val stateRepo = com.nekobot.app.data.local.ai.LocalCharacterStateRepository(db.characterStateDao())
        val relRepo = com.nekobot.app.data.local.ai.LocalRelationshipRepository(db.relationshipDao())
        val memoryService = com.nekobot.app.data.local.ai.LocalMemoryService(
            db.memoryDao(), aiClient
        ) { aiModelDao.getActive() }
        val worldBookStore = com.nekobot.app.data.local.ai.LocalWorldBookStore(characterDao, worldBookDao)
        val autoState = com.nekobot.app.data.local.ai.AutoState(aiClient) { aiModelDao.getActive() }

        val runtime = com.nekobot.app.data.local.ai.CharacterRuntime(
            profileRepo, stateRepo, relRepo, memoryService, worldBookStore, autoState
        )

        val identity = com.nekobot.app.data.local.ai.CharacterIdentity(
            characterId = character.id,
            targetId = "local-user",
            scopeId = sessionId,
            channel = "local"
        )

        // 4. 构建回调
        val callbacks = com.nekobot.app.data.local.ai.LocalPipelineCallbacks(
            db, aiClient, activeModel, session, character, worldBookEntries, runtime, identity
        )

        // 5. 构建上下文
        val chatRequest = com.nekobot.app.data.local.ai.ChatRequest.forLocal(
            sessionId = sessionId,
            content = userMessage,
            userId = "local-user"
        )
        val ctx = com.nekobot.app.data.local.ai.PipelineContext(chatRequest)

        // 标记剧情模式
        if (session.plotMode) {
            ctx.metadata["plot_mode"] = true
        }

        // 注入会话自定义提示词
        val customPromptsRaw = session.customPrompts
        if (!customPromptsRaw.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                @Suppress("UNCHECKED_CAST")
                val customPrompts = gson.fromJson<List<Map<String, Any>>>(customPromptsRaw, type) ?: emptyList()
                ctx.metadata["custom_prompts"] = customPrompts
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }

        // 6. 执行 Pipeline + 转发流式事件
        try {
            coroutineScope {
                // 启动事件转发协程
                val eventsJob = launch {
                    callbacks.events.collect { emit(it) }
                }
                // 执行 Pipeline
                try {
                    com.nekobot.app.data.local.ai.aiPipeline.process(ctx, callbacks)
                } finally {
                    eventsJob.cancel()
                }
            }

            // Phase 6: 剧情模式 → 生成选项
            if (session.plotMode) {
                try {
                    val plotGen = com.nekobot.app.data.local.ai.PlotChoiceGenerator(
                        aiClient, { aiModelDao.getActive() }
                    )
                    val recentHistory = messageDao.listBySession(sessionId)
                        .takeLast(6)
                        .filter { it.role != "system" }
                        .map { mapOf("role" to it.role, "content" to it.content) }
                    val choices = plotGen.generate(
                        responseText = ctx.finalContent,
                        recentHistory = recentHistory
                    )
                    if (choices.isNotEmpty()) {
                        val jsonChoices = gson.toJsonTree(choices)
                        emit(RealtimeEvent.PlotChoices(jsonChoices))
                    }
                } catch (e: Exception) {
                    // 剧情选项生成失败不影响主流程
                }
            }
        } catch (e: Exception) {
            emit(RealtimeEvent.Error(e.message ?: "Pipeline 执行失败"))
            emit(RealtimeEvent.StreamEnd(sessionId))
        }
    }.flowOn(Dispatchers.IO)

    // ==================== 会话 Fork ====================

    /**
     * 从 [messageId] 处分叉新会话：复制目标消息及之前所有消息到新会话。
     */
    suspend fun forkSession(sessionId: String, messageId: String): String? =
        withContext(Dispatchers.IO) {
            val source = sessionDao.getById(sessionId) ?: return@withContext null
            val messages = messageDao.listBySession(sessionId)
            val targetIdx = messages.indexOfFirst { it.id == messageId }
            if (targetIdx < 0) return@withContext null
            val forkMessages = messages.subList(0, targetIdx + 1)

            val now = nowIso()
            val newId = UUID.randomUUID().toString()
            val newSession = source.copy(
                id = newId,
                name = "${source.name} (分叉)",
                createdAt = now,
                updatedAt = now,
                messageCount = forkMessages.size,
                lastMessage = forkMessages.lastOrNull()?.content?.take(200)
            )
            sessionDao.upsert(newSession)
            // 复制消息，分配新 id
            val copied = forkMessages.map {
                it.copy(id = UUID.randomUUID().toString(), sessionId = newId)
            }
            messageDao.upsertAll(copied)
            newId
        }

    // ==================== 上下文压缩 ====================

    /**
     * 压缩上下文：取前 [keepRecent] 条消息保留，更早的消息让 AI 摘要后替换为单条 system 摘要。
     */
    suspend fun compressContext(
        sessionId: String,
        activeModel: LocalAiModelEntity,
        keepRecent: Int = 10
    ): Boolean = withContext(Dispatchers.IO) {
        val messages = messageDao.listBySession(sessionId)
        if (messages.size <= keepRecent + 2) return@withContext true  // 不需要压缩

        val toCompress = messages.dropLast(keepRecent)
        val toKeep = messages.takeLast(keepRecent)

        // 构造摘要请求
        val dialogText = toCompress.joinToString("\n") { m ->
            val speaker = when (m.role) {
                "user" -> "用户"
                "assistant" -> "AI"
                else -> m.role
            }
            "[$speaker] ${m.content}"
        }
        val reqMessages = listOf(
            mapOf(
                "role" to "system",
                "content" to "你是一个对话摘要助手，请把以下对话压缩成一段不超过 500 字的摘要，保留关键信息、人物关系和重要事件。"
            ),
            mapOf("role" to "user", "content" to dialogText)
        )

        val result: LocalAiResult = aiClient.chatOnce(activeModel, reqMessages)
        if (result.error != null || result.content.isBlank()) {
            return@withContext false
        }

        // 删除被压缩的消息
        toCompress.forEach { messageDao.deleteById(it.id) }
        // 在最前面插入摘要 system 消息（用 system role，prompt 构建时会被跳过）
        val now = nowIso()
        val summaryMsg = LocalMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "system",
            content = "【历史对话摘要】\n${result.content}",
            sender = "system",
            timestamp = now,
            createdAt = now
        )
        messageDao.upsert(summaryMsg)

        // 更新会话元信息
        sessionDao.touch(
            sessionId,
            lastMessage = toKeep.lastOrNull()?.content?.take(200) ?: "",
            count = messageDao.countBySession(sessionId),
            updatedAt = now
        )
        true
    }

    // ==================== 角色卡 ====================

    suspend fun listCharacters(): List<CharacterPreset> = withContext(Dispatchers.IO) {
        characterDao.listAll().map { it.toCharacterPreset() }
    }

    fun observeCharacters(): Flow<List<LocalCharacterEntity>> = characterDao.observeAll()

    suspend fun getCharacter(id: String): CharacterPreset? = withContext(Dispatchers.IO) {
        characterDao.getById(id)?.toCharacterPreset()
    }

    suspend fun upsertCharacter(preset: CharacterPreset): CharacterPreset =
        withContext(Dispatchers.IO) {
            val now = nowIso()
            val entity = preset.toEntity(now)
            characterDao.upsert(entity)
            entity.toCharacterPreset()
        }

    suspend fun deleteCharacter(id: String) = withContext(Dispatchers.IO) {
        characterDao.deleteById(id)
    }

    // ==================== 世界书 ====================

    suspend fun listWorldBooks(): List<WorldBook> = withContext(Dispatchers.IO) {
        worldBookDao.listAll().map { it.toWorldBook() }
    }

    suspend fun getWorldBook(id: String): WorldBook? = withContext(Dispatchers.IO) {
        worldBookDao.getById(id)?.toWorldBook()
    }

    suspend fun upsertWorldBook(book: WorldBook): WorldBook = withContext(Dispatchers.IO) {
        val now = nowIso()
        val entity = book.toEntity(now)
        worldBookDao.upsert(entity)
        entity.toWorldBook()
    }

    suspend fun deleteWorldBook(id: String) = withContext(Dispatchers.IO) {
        worldBookDao.deleteById(id)
    }

    suspend fun listEntries(bookId: String): List<WorldBookEntry> = withContext(Dispatchers.IO) {
        worldBookDao.listEntries(bookId).map { it.toEntry() }
    }

    suspend fun upsertEntry(bookId: String, entry: WorldBookEntry): WorldBookEntry =
        withContext(Dispatchers.IO) {
            val entity = entry.toEntity(bookId)
            worldBookDao.upsertEntry(entity)
            entity.toEntry()
        }

    suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        worldBookDao.deleteEntryById(id)
    }

    /** 加载某角色关联的所有世界书条目（含全局世界书）。 */
    suspend fun loadWorldBookEntries(characterId: String?): List<LocalWorldBookEntryEntity> =
        withContext(Dispatchers.IO) {
            if (characterId != null) {
                worldBookDao.listActiveEntriesForCharacter(characterId)
            } else {
                worldBookDao.listAllActiveEntries()
            }
        }

    // ==================== AI 模型 ====================

    fun observeAiModels(): Flow<List<LocalAiModelEntity>> = aiModelDao.observeAll()

    fun observeActiveModel(): Flow<LocalAiModelEntity?> = aiModelDao.observeActive()

    suspend fun listAiModels(): List<LocalAiModelEntity> = withContext(Dispatchers.IO) {
        aiModelDao.listAll()
    }

    suspend fun getActiveModel(): LocalAiModelEntity? = withContext(Dispatchers.IO) {
        aiModelDao.getActive()
    }

    suspend fun upsertAiModel(model: LocalAiModelEntity) = withContext(Dispatchers.IO) {
        aiModelDao.upsert(model)
    }

    suspend fun setActiveModel(id: String) = withContext(Dispatchers.IO) {
        aiModelDao.setActive(id)
    }

    suspend fun deleteAiModel(id: String) = withContext(Dispatchers.IO) {
        aiModelDao.deleteById(id)
    }

    suspend fun testModel(model: LocalAiModelEntity): LocalAiResult = aiClient.testModel(model)

    // ==================== Token 用量统计 ====================

    /** 本地 token 用量统计（基于 local_messages 的 input/output_tokens 字段聚合）。 */
    suspend fun tokenStats(): TokenStats = withContext(Dispatchers.IO) {
        val todayStart = SimpleDateFormat("yyyy-MM-dd 00:00:00", Locale.getDefault()).format(Date())
        val monthStart = SimpleDateFormat("yyyy-MM-01 00:00:00", Locale.getDefault()).format(Date())

        val todayInput = messageDao.todayInputTokens(todayStart).toLong()
        val todayOutput = messageDao.todayOutputTokens(todayStart).toLong()
        val month = messageDao.monthTokens(monthStart).toLong()
        val total = messageDao.totalTokens().toLong()
        val msgCount = messageDao.assistantMessageCount().toLong()

        // 最近记录：最多 50 条
        val recent = messageDao.listAllAssistant().take(50).map { msg ->
            JsonObject().apply {
                addProperty("session_id", msg.sessionId)
                addProperty("model", msg.model ?: "")
                addProperty("input_tokens", msg.inputTokens ?: 0)
                addProperty("output_tokens", msg.outputTokens ?: 0)
                addProperty("total_tokens", (msg.inputTokens ?: 0) + (msg.outputTokens ?: 0))
                addProperty("timestamp", msg.timestamp)
            }
        }

        TokenStats(
            today = todayInput + todayOutput,
            month = month,
            totalTokens = total,
            todayInput = todayInput,
            todayOutput = todayOutput,
            messageCount = msgCount,
            avgTokensPerMsg = if (msgCount > 0) total.toDouble() / msgCount else 0.0,
            estimatedCost = "—",
            recentRecords = recent,
            records = recent
        )
    }

    /** 本地 token 用量排行榜（按 model / session / 用途聚合）。 */
    suspend fun tokenRankings(): TokenRankings = withContext(Dispatchers.IO) {
        val all = messageDao.listAllAssistant()
        // 按模型聚合
        val modelsRank = all.groupBy { it.model ?: "未知" }
            .map { (model, msgs) ->
                val input = msgs.sumOf { it.inputTokens ?: 0 }
                val output = msgs.sumOf { it.outputTokens ?: 0 }
                JsonObject().apply {
                    addProperty("name", model)
                    addProperty("input_tokens", input)
                    addProperty("output_tokens", output)
                    addProperty("total_tokens", input + output)
                    addProperty("count", msgs.size)
                }
            }
            .sortedByDescending { it.get("total_tokens").asLong }

        // 按会话聚合
        val sessionsRank = all.groupBy { it.sessionId }
            .map { (sid, msgs) ->
                val input = msgs.sumOf { it.inputTokens ?: 0 }
                val output = msgs.sumOf { it.outputTokens ?: 0 }
                val sessionName = sessionDao.getById(sid)?.name ?: sid
                JsonObject().apply {
                    addProperty("name", sessionName)
                    addProperty("session_id", sid)
                    addProperty("input_tokens", input)
                    addProperty("output_tokens", output)
                    addProperty("total_tokens", input + output)
                    addProperty("count", msgs.size)
                }
            }
            .sortedByDescending { it.get("total_tokens").asLong }

        TokenRankings(
            sessions = sessionsRank,
            models = modelsRank,
            users = emptyList(),
            purposes = emptyList()
        )
    }

    // ==================== 自定义提示词 ====================

    /** 获取会话的自定义提示词列表（JSON 数组字符串） */
    suspend fun getCustomPromptsRaw(id: String): String? = withContext(Dispatchers.IO) {
        sessionDao.getById(id)?.customPrompts
    }

    /** 全量更新会话的自定义提示词列表 */
    suspend fun updateCustomPrompts(id: String, customPromptsJson: String?) = withContext(Dispatchers.IO) {
        sessionDao.updateCustomPrompts(id, customPromptsJson, nowIso())
    }

    // ==================== 角色卡导入 ====================

    /**
     * 导入 nekobot 协议角色卡：支持 .json 和 .zip（含 character.json + portrait 图片）。
     * @param bytes 文件内容
     * @param fileName 文件名（用于判断类型）
     * @return 导入后的 CharacterPreset（已保存到本地数据库）
     */
    suspend fun importCharacter(bytes: ByteArray, fileName: String): CharacterPreset =
        withContext(Dispatchers.IO) {
            val lower = fileName.lowercase()
            val jsonStr: String
            var portraitPath: String? = null

            when {
                lower.endsWith(".zip") -> {
                    ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                        var foundJson: String? = null
                        var portraitBytes: ByteArray? = null
                        var portraitExt: String? = null
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name.lowercase()
                            if (name == "character.json" || name.endsWith("/character.json")) {
                                val out = ByteArrayOutputStream()
                                val buf = ByteArray(4096)
                                var n = zis.read(buf)
                                while (n > 0) { out.write(buf, 0, n); n = zis.read(buf) }
                                foundJson = out.toString("UTF-8")
                            } else if (name.startsWith("portrait.")) {
                                val out = ByteArrayOutputStream()
                                val buf = ByteArray(4096)
                                var n = zis.read(buf)
                                while (n > 0) { out.write(buf, 0, n); n = zis.read(buf) }
                                portraitBytes = out.toByteArray()
                                portraitExt = name.substringAfterLast(".")
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                        jsonStr = foundJson ?: throw IllegalArgumentException("ZIP 文件中未找到 character.json")
                        // 立绘图片暂存到应用内部存储（本地模式不依赖服务器）
                        if (portraitBytes != null && portraitExt != null) {
                            portraitPath = savePortraitLocal(portraitBytes!!, portraitExt!!)
                        }
                    }
                }
                lower.endsWith(".json") -> {
                    jsonStr = String(bytes, Charsets.UTF_8)
                }
                else -> throw IllegalArgumentException("不支持的文件格式：$fileName")
            }

            // 解析 JSON 为 CharacterPreset
            val preset = try {
                gson.fromJson(jsonStr, CharacterPreset::class.java)
            } catch (e: Exception) {
                throw IllegalArgumentException("角色卡 JSON 解析失败：${e.message}")
            } ?: throw IllegalArgumentException("角色卡 JSON 为空")

            if (preset.name.isNullOrBlank()) {
                throw IllegalArgumentException("角色卡缺少 name 字段")
            }

            // 如果 ZIP 中有立绘，覆盖 preset.portrait
            val finalPreset = if (portraitPath != null) preset.copy(portrait = portraitPath) else preset
            // 保存到数据库（新建 id）
            upsertCharacter(finalPreset.copy(id = null))
        }

    /** 把立绘图片保存到应用私有目录，返回 file:// 路径供 Coil 加载。 */
    private fun savePortraitLocal(imageBytes: ByteArray, ext: String): String {
        val ctx = appContext ?: throw IllegalStateException("App context not initialized")
        val dir = java.io.File(ctx.filesDir, "portraits")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "portrait_${UUID.randomUUID().toString().take(16)}.$ext")
        file.writeBytes(imageBytes)
        return file.toURI().toString()
    }

    // ==================== 工具 ====================

    fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        /** 静态时间戳工具（供 LocalPipelineCallbacks 等外部类使用） */
        fun nowIsoStatic(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun nowIsoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    // ==================== Entity ↔ Model 转换 ====================

    private fun LocalSessionEntity.toSession(): Session = Session(
        id = id,
        name = name,
        type = "local",
        systemPrompt = systemPrompt,
        characterId = characterId,
        firstMessage = firstMessage,
        scenario = scenario,
        senderName = senderName,
        senderAvatar = senderAvatar,
        characterName = characterName,
        characterAvatar = characterAvatar,
        portrait = portrait,
        tags = tags?.split(",")?.filter { it.isNotEmpty() },
        favorite = favorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessage = lastMessage,
        messageCount = messageCount,
        plotMode = plotMode,
        customPrompts = customPrompts?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
    )

    private fun LocalMessageEntity.toMessage(): Message = Message(
        id = id,
        role = role,
        content = content,
        sender = sender,
        timestamp = timestamp,
        model = model,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        createdAt = createdAt
    )

    private fun LocalCharacterEntity.toCharacterPreset(): CharacterPreset = CharacterPreset(
        id = id,
        name = name,
        description = description,
        avatar = avatar,
        portrait = portrait,
        tags = LocalPromptBuilder.parseStringList(tags),
        basicInfo = basicInfo,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        alternateGreetings = LocalPromptBuilder.parseStringList(alternateGreetings),
        exampleDialogues = exampleDialogues,
        responseFormat = responseFormat,
        rules = LocalPromptBuilder.parseStringList(rules),
        state = state?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() },
        systemPrompt = systemPrompt,
        greeting = greeting,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun CharacterPreset.toEntity(now: String): LocalCharacterEntity = LocalCharacterEntity(
        id = id ?: UUID.randomUUID().toString(),
        name = name ?: "未命名角色",
        description = description,
        avatar = avatar,
        portrait = portrait,
        tags = LocalPromptBuilder.stringifyList(tags),
        basicInfo = basicInfo,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        alternateGreetings = LocalPromptBuilder.stringifyList(alternateGreetings),
        exampleDialogues = exampleDialogues,
        responseFormat = responseFormat,
        rules = LocalPromptBuilder.stringifyList(rules),
        state = state?.toString(),
        systemPrompt = systemPrompt,
        greeting = greeting,
        createdAt = createdAt ?: now,
        updatedAt = now
    )

    private fun LocalWorldBookEntity.toWorldBook(): WorldBook = WorldBook(
        id = id,
        name = name,
        description = description,
        characterIds = listOfNotNull(characterId),
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun WorldBook.toEntity(now: String): LocalWorldBookEntity = LocalWorldBookEntity(
        id = id ?: UUID.randomUUID().toString(),
        name = name ?: "未命名世界书",
        description = description,
        characterId = characterId,
        enabled = enabled ?: true,
        createdAt = createdAt ?: now,
        updatedAt = now
    )

    private fun LocalWorldBookEntryEntity.toEntry(): WorldBookEntry = WorldBookEntry(
        id = id,
        keys = LocalPromptBuilder.parseStringList(keys),
        content = content,
        comment = comment,
        enabled = enabled,
        constant = constant,
        selective = selective,
        insertionOrder = insertionOrder,
        priority = priority,
        position = position,
        caseSensitive = caseSensitive,
        displayIndex = displayIndex
    )

    private fun WorldBookEntry.toEntity(bookId: String): LocalWorldBookEntryEntity =
        LocalWorldBookEntryEntity(
            id = id ?: UUID.randomUUID().toString(),
            bookId = bookId,
            keys = LocalPromptBuilder.stringifyList(keys),
            content = content,
            comment = comment,
            enabled = enabled ?: true,
            constant = constant ?: false,
            selective = selective ?: false,
            insertionOrder = insertionOrder ?: 0,
            priority = priority ?: 0,
            position = position,
            caseSensitive = caseSensitive ?: false,
            displayIndex = displayIndex ?: 0
        )

    /** 用于 chat API 返回的统一结果。 */
    fun apiResultOk(): ApiResult = ApiResult(success = true)
}
