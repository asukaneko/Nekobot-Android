package com.nekobot.app.data.local

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.ai.FailoverAllFailedException
import com.nekobot.app.data.local.ai.FailoverCoordinator
import com.nekobot.app.data.local.ai.FailoverHealthStore
import com.nekobot.app.data.local.ai.FailoverUsage
import com.nekobot.app.data.local.ai.FailoverUsageReader
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.ai.LocalAiResult
import com.nekobot.app.data.local.ai.LocalGenerationController
import com.nekobot.app.data.local.ai.LocalMcpRuntime
import com.nekobot.app.data.local.ai.LocalPromptBuilder
import com.nekobot.app.data.local.ai.TokenStatsManager
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalApiKeyEntity
import com.nekobot.app.data.local.db.LocalFailoverHealthEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalHookEntity
import com.nekobot.app.data.local.db.LocalMcpServerEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalMessageFavoriteEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalSkillEntity
import com.nekobot.app.data.local.db.LocalTaskEntity
import com.nekobot.app.data.local.db.LocalToolEntity
import com.nekobot.app.data.local.db.LocalWorkflowEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.data.model.ApiKeyRequest
import com.nekobot.app.data.model.ApiResult
import com.nekobot.app.data.model.AiModel
import com.nekobot.app.data.model.BindCharacterRequest
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Hook
import com.nekobot.app.data.model.HookExecutionLog
import com.nekobot.app.data.model.HookRequest
import com.nekobot.app.data.model.McpServer
import com.nekobot.app.data.model.McpServerRequest
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.model.SkillInstallRequest
import com.nekobot.app.data.model.SkillRequest
import com.nekobot.app.data.model.TaskItem
import com.nekobot.app.data.model.TaskRequest
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.Tool
import com.nekobot.app.data.model.ToolRequest
import com.nekobot.app.data.model.Workflow
import com.nekobot.app.data.model.WorkflowRequest
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.remote.RealtimeEvent
import androidx.room.withTransaction
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
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val failoverHealthDao = db.failoverHealthDao()
    private val localExecAuthorizationManager =
        com.nekobot.app.data.local.ai.LocalExecAuthorizationManager()
    private val localMcpRuntime = LocalMcpRuntime()
    private val localSkillStorage = appContext?.filesDir
        ?.let { LocalSkillStorage(File(it, "skills")) }
    private val skillPackageDownloader = SkillPackageDownloader()

    // ==================== 故障转移协调器（持久化健康状态 + token 限额）====================

    /** Room DAO 适配器：将 [FailoverHealthDao] 暴露为 [FailoverHealthStore] */
    private val failoverHealthStore: FailoverHealthStore by lazy { RoomFailoverHealthStore(failoverHealthDao) }

    /** SharedPreferences 适配器：聚合每个模型的日/周 token 用量供 [FailoverCoordinator] 限额检查 */
    private val failoverUsageReader: FailoverUsageReader by lazy {
        PrefsFailoverUsageReader(tokenUsagePrefs) { id ->
            // records 中存储的是 model 名（如 "gpt-4o"），需要把 modelId (UUID) 解析为对应的 model 名
            aiModelDao.getById(id)?.model
        }
    }

    /** 通用故障转移协调器：按 priority 顺序尝试模型，自动跳过冷却中/超限额的模型 */
    val failoverCoordinator: FailoverCoordinator by lazy {
        FailoverCoordinator(failoverHealthStore, failoverUsageReader)
    }

    /**
     * 取指定 purpose 的故障转移队列：所有启用模型按 (priority, createdAt) 升序排序。
     * P0 在前，作为首选模型；其余作为故障转移备选。
     */
    private suspend fun queueFor(purpose: String): List<LocalAiModelEntity> =
        aiModelDao.listByPurpose(purpose)
            .sortedWith(compareBy(LocalAiModelEntity::priority, LocalAiModelEntity::createdAt))

    init {
        val savedGraph = appContext
            ?.getSharedPreferences("plot_graph", android.content.Context.MODE_PRIVATE)
            ?.getString("graph", null)
        if (!savedGraph.isNullOrBlank()) {
            com.nekobot.app.data.local.ai.getGlobalPlotGraphManager().fromJson(savedGraph)
        }
    }

    /** 将本地故事图整体持久化，保证重启应用后节点、边和当前分支仍可恢复。 */
    fun persistPlotGraph() {
        val json = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager().toJson()
        appContext?.getSharedPreferences("plot_graph", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString("graph", json)?.apply()
    }

    /** 当前正在进行的聊天 Job，用于 stopGeneration */
    @Volatile
    private var currentChatJob: Job? = null
    private val activeGenerations = ConcurrentHashMap<String, LocalGenerationController>()

    /**
     * 当前进行中会话 ID。二级 LLM 调用（AutoState/记忆抽取）在跨会话单例内触发，
     * 缺少 per-turn sessionId，故在此暂存，供 token 记账归属到正确会话。
     */
    @Volatile
    private var currentSessionId: String = ""

    /** 二级 LLM 调用（state/memory）token 记账回调 */
    private fun recordSecondaryTokenUsage(source: String, model: String, input: Int, output: Int) {
        if (input == 0 && output == 0) return
        // source 映射到 purpose：state → utility，memory → memory
        val purpose = when (source) {
            "state" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
            "memory" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_MEMORY
            else -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_CHAT
        }
        appendTokenUsageRecord(
            sessionId = currentSessionId,
            model = model,
            inputTokens = input,
            outputTokens = output,
            timestamp = nowIsoTimestamp(),
            source = source,
            purpose = purpose
        )
    }

    /** 剧情选项生成 token 记账回调 */
    private fun recordPlotTokenUsage(model: String, input: Int, output: Int) {
        if (input == 0 && output == 0) return
        appendTokenUsageRecord(
            sessionId = currentSessionId,
            model = model,
            inputTokens = input,
            outputTokens = output,
            timestamp = nowIsoTimestamp(),
            source = "plot",
            purpose = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_PLOT
        )
    }

    // ==================== 角色运行时单例（跨会话保持 turnCounters 状态）====================

    /** AutoState 必须跨轮次保持 turnCounters，否则永远达不到触发阈值 */
    private val autoState by lazy {
        com.nekobot.app.data.local.ai.AutoState(
            aiClient,
            { aiModelDao.getActive() },
            onTokenUsage = ::recordSecondaryTokenUsage
        )
    }
    /** LocalMemoryService 同理，turnCounters 需跨轮次保持 */
    private val memoryService by lazy {
        com.nekobot.app.data.local.ai.LocalMemoryService(
            db.memoryDao(), aiClient,
            { aiModelDao.getActive() },
            onTokenUsage = ::recordSecondaryTokenUsage
        )
    }
    /** CharacterRuntime 依赖上述单例 */
    private val characterRuntime by lazy {
        val profileRepo = com.nekobot.app.data.local.ai.LocalProfileRepository(characterDao)
        val stateRepo = com.nekobot.app.data.local.ai.LocalCharacterStateRepository(db.characterStateDao())
        val relRepo = com.nekobot.app.data.local.ai.LocalRelationshipRepository(db.relationshipDao())
        val worldBookStore = com.nekobot.app.data.local.ai.LocalWorldBookStore(characterDao, worldBookDao)
        val memFS = com.nekobot.app.data.local.ai.MemoryFS(db.memoryDao())
        val snapshotRepo = com.nekobot.app.data.local.ai.LocalStateSnapshotRepository(db.stateSnapshotDao())
        com.nekobot.app.data.local.ai.CharacterRuntime(
            profileRepo, stateRepo, relRepo, memoryService, worldBookStore, autoState, memFS, snapshotRepo
        )
    }

    /** 本地模式 Hook 执行引擎（跨会话保持 once_per_conversation 状态） */
    val hookExecutor by lazy {
        com.nekobot.app.data.local.ai.HookExecutor(db)
    }

    /** 本地模式会话自动命名器（跨会话保持 autoNamed/lastRenameCount 状态） */
    val sessionNameGenerator by lazy {
        com.nekobot.app.data.local.ai.SessionNameGenerator(
            aiClient,
            { aiModelDao.getActive() },
            onTokenUsage = ::recordSecondaryTokenUsage
        )
    }

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
            updatedAt = now,
            sessionMode = req.sessionMode
        )
        sessionDao.upsert(entity)

        // 角色会话：若角色有 firstMessage，自动插入一条 assistant 消息到数据库
        // （agent 模式不插入，agent 会话由用户主动发起）
        val firstMsg = req.firstMessage ?: character?.firstMessage ?: character?.greeting
        if (!firstMsg.isNullOrBlank() && !req.sessionMode.equals("agent", ignoreCase = true)) {
            val msgId = UUID.randomUUID().toString()
            messageDao.upsert(
                LocalMessageEntity(
                    id = msgId,
                    sessionId = id,
                    role = "assistant",
                    content = firstMsg,
                    sender = character?.name,
                    timestamp = System.currentTimeMillis().toString(),
                    createdAt = now
                )
            )
            sessionDao.touch(id, firstMsg.take(200), 1, now)
        }

        entity.toSession()
    }

    suspend fun updateSession(
        id: String,
        name: String? = null,
        systemPrompt: String? = null,
        favorite: Boolean? = null,
        tags: List<String>? = null,
        plotMode: Boolean? = null,
        plotRealTimeSync: Boolean? = null,
        plotChoiceStyle: String? = null,
        plotOutline: String? = null,
        userPersona: String? = null,
        autoStateInterval: Int? = null,
        disabledPromptKeys: List<String>? = null,
        isPublic: Boolean? = null,
        proactiveChat: String? = null,
        ttsConfig: String? = null,
        shareConfig: String? = null,
        archived: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        val entity = sessionDao.getById(id) ?: run {
            android.util.Log.d("LocalRepo", "updateSession: entity not found for id=$id")
            return@withContext
        }
        android.util.Log.d("LocalRepo", "updateSession: isPublic=$isPublic, entity.isPublic=${entity.isPublic}, ttsConfig=$ttsConfig, shareConfig=$shareConfig")
        val updated = entity.copy(
            name = name ?: entity.name,
            systemPrompt = systemPrompt ?: entity.systemPrompt,
            favorite = favorite ?: entity.favorite,
            tags = tags?.let { it.joinToString(",") } ?: entity.tags,
            plotMode = plotMode ?: entity.plotMode,
            plotRealTimeSync = plotRealTimeSync ?: entity.plotRealTimeSync,
            plotChoiceStyle = plotChoiceStyle ?: entity.plotChoiceStyle,
            plotOutline = plotOutline ?: entity.plotOutline,
            userPersona = userPersona ?: entity.userPersona,
            autoStateInterval = autoStateInterval ?: entity.autoStateInterval,
            disabledPromptKeys = disabledPromptKeys?.let { it.joinToString(",") } ?: entity.disabledPromptKeys,
            isPublic = isPublic ?: entity.isPublic,
            proactiveChat = proactiveChat ?: entity.proactiveChat,
            ttsConfig = ttsConfig ?: entity.ttsConfig,
            shareConfig = shareConfig ?: entity.shareConfig,
            archived = archived ?: entity.archived,
            updatedAt = nowIso()
        )
        // 使用 @Update 而非 upsert(@Insert REPLACE)，避免触发外键级联删除消息
        sessionDao.update(updated)
        // 额外用直接 SQL 确保 is_public / share_config / tts_config / proactive_chat 落库
        // （@Update 理论上会更新所有字段，但实测 is_public 等新增列偶发不生效，此处兜底）
        if (isPublic != null || shareConfig != null || ttsConfig != null || proactiveChat != null) {
            sessionDao.updatePublicShareConfig(
                id = id,
                isPublic = updated.isPublic,
                shareConfig = updated.shareConfig,
                ttsConfig = updated.ttsConfig,
                proactiveChat = updated.proactiveChat,
                updatedAt = nowIso()
            )
        }
        android.util.Log.d("LocalRepo", "updateSession: updated.isPublic=${updated.isPublic}, updated.ttsConfig=${updated.ttsConfig}, updated.shareConfig=${updated.shareConfig}")
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        // 先清理该会话的剧情选项缓存（不影响 token 用量）
        appContext?.getSharedPreferences("plot_choices", android.content.Context.MODE_PRIVATE)
            ?.edit()?.remove(id)?.apply()
        sessionDao.deleteById(id)
    }

    // ==================== 剧情选项持久化 ====================

    /** 保存剧情选项到 SharedPreferences（与 session 生命周期解耦） */
    fun savePlotChoices(sessionId: String, choicesJson: String) {
        appContext?.getSharedPreferences("plot_choices", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString(sessionId, choicesJson)?.apply()
    }

    /** 读取已保存的剧情选项 */
    fun getPlotChoices(sessionId: String): String? {
        return appContext?.getSharedPreferences("plot_choices", android.content.Context.MODE_PRIVATE)
            ?.getString(sessionId, null)
    }

    /** 将故事图当前节点的未选择选项同步到聊天页缓存。 */
    fun syncPlotChoicesFromGraph(sessionId: String) {
        val choices = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            .getLatestChoices(sessionId)
            .map { it.toDict() }
        val payload = JsonObject().apply { add("choices", gson.toJsonTree(choices)) }
        savePlotChoices(sessionId, payload.toString())
    }

    // ==================== 状态历程缓存 ====================

    private val stateHistoryCacheFile get() = appContext?.cacheDir?.resolve("state_history_cache.json")

    /** 保存状态历程数据到缓存文件 */
    fun saveStateHistoryCache(json: String) {
        try {
            stateHistoryCacheFile?.writeText(json)
        } catch (_: Exception) { /* 忽略缓存写入失败 */ }
    }

    /** 读取缓存的状态历程数据 */
    fun loadStateHistoryCache(): String? {
        return try {
            stateHistoryCacheFile?.takeIf { it.exists() }?.readText()
        } catch (_: Exception) { null }
    }

    // ==================== 消息 ====================

    suspend fun listMessages(sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        messageDao.listBySession(sessionId).map { it.toMessage() }
    }

    fun observeMessages(sessionId: String): Flow<List<LocalMessageEntity>> =
        messageDao.observeBySession(sessionId)

    /** 用故事图根到目标节点的消息路径替换当前会话，供本地分支切换与回溯使用。 */
    suspend fun replaceMessagesWithPlotPath(sessionId: String, nodeId: String) = withContext(Dispatchers.IO) {
        val path = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager().materializePath(nodeId)
        messageDao.deleteBySession(sessionId)
        val base = Instant.now()
        val entities = path.mapIndexedNotNull { index, message ->
            val content = message["content"].orEmpty()
            if (content.isBlank()) return@mapIndexedNotNull null
            val role = message["role"] ?: "assistant"
            val timestamp = base.plusMillis(index.toLong()).toString()
            LocalMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = role,
                content = content,
                sender = if (role == "assistant") "assistant" else null,
                timestamp = timestamp,
                createdAt = timestamp
            )
        }
        if (entities.isNotEmpty()) messageDao.upsertAll(entities)
        sessionDao.getById(sessionId)?.let {
            sessionDao.touch(
                sessionId,
                lastMessage = entities.lastOrNull()?.content?.take(200).orEmpty(),
                count = entities.size,
                updatedAt = base.toString()
            )
        }
    }

    /** 把根到指定剧情节点的消息复制为一个本地归档会话。 */
    suspend fun archivePlotBranch(sessionId: String, nodeId: String): Int = withContext(Dispatchers.IO) {
        val source = sessionDao.getById(sessionId) ?: return@withContext 0
        val path = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager().materializePath(nodeId)
        if (path.isEmpty()) return@withContext 0
        val archiveId = UUID.randomUUID().toString()
        val now = Instant.now()
        val nodeTitle = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            .getNode(nodeId)?.title?.takeIf { it.isNotBlank() } ?: nodeId.take(8)
        sessionDao.upsert(
            source.copy(
                id = archiveId,
                name = "${source.name} · 分支归档 · $nodeTitle",
                archived = true,
                favorite = false,
                lastMessage = path.last()["content"].orEmpty().take(200),
                messageCount = path.size,
                createdAt = now.toString(),
                updatedAt = now.toString()
            )
        )
        messageDao.upsertAll(path.mapIndexed { index, message ->
            val role = message["role"] ?: "assistant"
            val timestamp = now.plusMillis(index.toLong()).toString()
            LocalMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = archiveId,
                role = role,
                content = message["content"].orEmpty(),
                sender = if (role == "assistant") "assistant" else null,
                timestamp = timestamp,
                createdAt = timestamp
            )
        })
        path.size
    }

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
        // 同时追加到独立 token 用量存储（不受会话删除影响）
        appendTokenUsageRecord(
            sessionId = sessionId,
            model = model ?: "",
            inputTokens = inputTokens ?: 0,
            outputTokens = outputTokens ?: 0,
            timestamp = now
        )
        msg.toMessage()
    }

    // ==================== 独立 Token 用量存储（与会话生命周期解耦）====================

    /**
     * Token 用量存储：SharedPreferences 文件名带 db 名后缀，
     * 切换 db 后自动隔离到对应 profile 的用量记录。
     */
    private val tokenUsagePrefs by lazy {
        val dbName = db.dbName
        appContext?.getSharedPreferences("token_usage_$dbName", android.content.Context.MODE_PRIVATE)
    }

    /**
     * 追加一条 token 用量记录到 SharedPreferences JSON 数组。
     * @param source 用量来源：chat（主对话）/ state（状态评估）/ memory（记忆抽取）/ plot（剧情）
     * @param purpose 用途标签（与 TokenStatsManager 常量对齐）：chat/utility/memory/plot 等
     */
    private fun appendTokenUsageRecord(
        sessionId: String, model: String,
        inputTokens: Int, outputTokens: Int, timestamp: String,
        source: String = "chat",
        purpose: String = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_CHAT
    ) {
        val prefs = tokenUsagePrefs ?: return
        val existing = prefs.getString("records", "[]") ?: "[]"
        try {
            val arr = JsonParser.parseString(existing).asJsonArray
            val record = JsonObject().apply {
                addProperty("session_id", sessionId)
                addProperty("model", model)
                addProperty("input_tokens", inputTokens)
                addProperty("output_tokens", outputTokens)
                addProperty("total_tokens", inputTokens + outputTokens)
                addProperty("timestamp", timestamp)
                addProperty("source", source)
                addProperty("purpose", purpose)
                // 提取日期部分（yyyy-MM-dd）用于按日聚合
                addProperty("date", timestamp.substringBefore("T").substringBefore(" ").ifBlank { timestamp })
            }
            arr.add(record)
            // 限制最多 5000 条，超出则丢弃最早的
            while (arr.size() > 5000) arr.remove(0)
            prefs.edit().putString("records", arr.toString()).apply()
        } catch (_: Exception) { }
    }

    /** 读取所有 token 用量记录 */
    private fun readTokenUsageRecords(): List<JsonObject> {
        val prefs = tokenUsagePrefs ?: return emptyList()
        val raw = prefs.getString("records", "[]") ?: "[]"
        return try {
            JsonParser.parseString(raw).asJsonArray
                .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
        } catch (_: Exception) { emptyList() }
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
     * 自动启用故障转移：传入的 [activeModel] 作为首选，失败时按 purpose=chat 队列重试。
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

        // 5. 构造故障转移队列：activeModel 优先，附加同 purpose 其他启用模型
        val queue = buildList {
            add(activeModel)
            aiModelDao.listByPurpose(activeModel.purpose.ifBlank { "chat" })
                .filter { it.id != activeModel.id }
                .forEach { add(it) }
        }

        // 6. 流式调用（带故障转移）
        val fullContent = StringBuilder()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var modelName: String? = null
        aiClient.chatStreamWithFailover(queue, messages, extra).collect { event ->
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

    /** 停止生成：同时中断模型请求、工具调用、命令进程和授权等待。 */
    fun stopGeneration(sessionId: String? = null) {
        val sessionIds = sessionId
            ?.let(::listOf)
            ?: activeGenerations.keys.toList()
        sessionIds.forEach { id ->
            activeGenerations[id]?.requestStop()
            localExecAuthorizationManager.cancelSession(id)
            aiClient.cancelRequests(id)
        }
        localMcpRuntime.cancelActiveToolCall()
        currentChatJob?.cancel()
        currentChatJob = null
    }

    /** 释放本地仓库持有的长连接与 stdio 子进程。 */
    fun close() {
        stopGeneration()
        localMcpRuntime.close()
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
     * 无角色的普通会话会回退到 [chat]；Agent 会话即使没有角色也进入 Pipeline，
     * 以产生并持久化进度卡片。
     *
     * @param sessionId 会话 ID
     * @param userMessage 用户消息
     * @param activeModel 激活的 AI 模型
     * @return RealtimeEvent 流
     */
    fun chatWithPipeline(
        sessionId: String,
        userMessage: String,
        activeModel: LocalAiModelEntity,
        attachments: List<Map<String, Any>> = emptyList()
    ): Flow<RealtimeEvent> = flow {
        val generationController = LocalGenerationController()
        activeGenerations.put(sessionId, generationController)?.requestStop()
        try {
        // 标记当前会话，供二级 LLM 调用（AutoState/记忆）token 记账归属
        currentSessionId = sessionId

        // 1. 保存用户消息
        val savedUserMessage = addMessage(sessionId, "user", userMessage)
        val parentMessageId = savedUserMessage.id ?: java.util.UUID.randomUUID().toString()

        val session = sessionDao.getById(sessionId) ?: run {
            emit(RealtimeEvent.Error("会话不存在"))
            emit(RealtimeEvent.StreamEnd(sessionId))
            return@flow
        }

        if (
            session.sessionMode.equals("agent", ignoreCase = true) &&
            userMessage.trim().equals("/yolo", ignoreCase = true)
        ) {
            localExecAuthorizationManager.enableYolo(sessionId)
            val reply = addAssistantMessage(
                sessionId = sessionId,
                content = "已开启 YOLO 模式：本会话内命令无需授权即可执行，高风险黑名单命令仍会被阻止。",
                model = activeModel.model
            )
            emit(RealtimeEvent.AiResponse(reply))
            return@flow
        }

        val character = session.characterId?.let { characterDao.getById(it) }

        // 普通无角色会话沿用旧流程；Agent 无角色会话需要进入 Pipeline 产生进度卡片。
        // 图片附件必须经过 Pipeline 的附件解析与视觉描述阶段；普通无角色会话也不能回退旧聊天链路。
        if (!shouldUseLocalPipeline(session.sessionMode, character != null, attachments.isNotEmpty())) {
            // 回退时需删除刚保存的用户消息（chat 会重新保存）
            messageDao.listBySession(sessionId).lastOrNull { it.role == "user" }?.let {
                messageDao.deleteById(it.id)
            }
            chat(sessionId, userMessage, activeModel).collect { emit(it) }
            return@flow
        }

        // 2. 加载世界书条目
        val worldBookEntries = loadWorldBookEntries(session.characterId)

        // 3. 使用成员变量（跨轮次保持 turnCounters 状态）
        val runtime = character?.let { characterRuntime }

        val identity = character?.let {
            com.nekobot.app.data.local.ai.CharacterIdentity(
                characterId = it.id,
                targetId = "local-user",
                scopeId = sessionId,
                channel = "local"
            )
        }

        // 4. 构建回调（传入故障转移备选队列：同 purpose 其他启用模型 + 持久化协调器）
        val failoverQueue = aiModelDao.listByPurpose(activeModel.purpose.ifBlank { "chat" })
            .filter { it.id != activeModel.id }
        val callbacks = com.nekobot.app.data.local.ai.LocalPipelineCallbacks(
            db, aiClient, activeModel, session, character, worldBookEntries, runtime, identity,
            parentMessageId = parentMessageId,
            onTokenRecorded = { sid, model, input, output, ts, purpose ->
                appendTokenUsageRecord(sid, model, input, output, ts, purpose = purpose)
            },
            onThinkingCardUpdate = { card ->
                // 持久化进度卡片到父用户消息
                kotlinx.coroutines.runBlocking {
                    updateMessageThinkingCards(parentMessageId, listOf(card))
                }
            },
            workspaceRoot = appContext?.filesDir
                ?.let { LocalWorkspaceStorage.resolve(it, sessionId) },
            execAuthorizationManager = localExecAuthorizationManager,
            mcpToolExecutor = localMcpRuntime::executeByFullName,
            skillToolExecutor = ::executeLocalSkillTool,
            failoverQueue = failoverQueue,
            coordinator = failoverCoordinator,
            hookExecutor = hookExecutor,
            visionDescriber = { imageUrl, question ->
                describeImageViaQueue(
                    imageUrl = imageUrl,
                    question = question,
                    requestTag = sessionId,
                    shouldStop = { generationController.isStopped }
                )
            },
            generationController = generationController
        )

        // 5. 构建上下文（含会话级配置：剧情模式、禁用注入项、自动状态间隔等）
        val disabledKeys = session.disabledPromptKeys
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val chatRequest = com.nekobot.app.data.local.ai.ChatRequest.forLocal(
            sessionId = sessionId,
            content = userMessage,
            userId = "local-user",
            attachments = attachments,
            metadata = buildMap {
                put("session_mode", session.sessionMode)
                if (session.plotMode) put("plot_mode", true)
                if (session.plotRealTimeSync) put("plot_realtime_sync", true)
                put("auto_state_interval", session.autoStateInterval)
                if (disabledKeys.isNotEmpty()) put("disabled_prompt_keys", disabledKeys)
                // 本会话用户名 + 用户人设/背景，供 AIPipeline 注入 PromptStack、AutoMemory 替换"用户"标签
                session.senderName?.takeIf { it.isNotBlank() }?.let { put("sender_name", it) }
                session.userPersona?.takeIf { it.isNotBlank() }?.let { put("user_persona", it) }
            }
        )
        val ctx = com.nekobot.app.data.local.ai.PipelineContext(chatRequest)
        ctx.stopRequested = { generationController.isStopped }
        ctx.metadata["session_mode"] = session.sessionMode
        if (session.sessionMode.equals("agent", ignoreCase = true)) {
            val skillsPrompt = buildEnabledSkillsPrompt()
            if (skillsPrompt.isNotBlank()) {
                ctx.promptStack.add(
                    key = "skills.available",
                    content = skillsPrompt,
                    priority = com.nekobot.app.data.local.ai.PromptStack.Priority.TOOL_INSTRUCTIONS,
                    scope = "global"
                )
            }
        }
        var currentPlotNode: com.nekobot.app.data.local.ai.PlotNode? = null

        // 标记剧情模式（管线层使用）
        if (session.plotMode) {
            ctx.metadata["plot_mode"] = true
        }
        // 禁用列表也同步到 ctx.metadata（供 phasePrepareContext 的管线栈使用）
        if (disabledKeys.isNotEmpty()) {
            ctx.metadata["disabled_prompt_keys"] = disabledKeys
        }
        // auto_state_interval 同步到 ctx.metadata（供 AutoState 读取）
        ctx.metadata["auto_state_interval"] = session.autoStateInterval
        // sender_name / user_persona 同步到 ctx.metadata（供 AIPipeline 注入 PromptStack 的 user.persona 项）
        session.senderName?.takeIf { it.isNotBlank() }?.let { ctx.metadata["sender_name"] = it }
        session.userPersona?.takeIf { it.isNotBlank() }?.let { ctx.metadata["user_persona"] = it }

        // 注入会话自定义提示词
        val customPromptsRaw = session.customPrompts
        if (character != null && !customPromptsRaw.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                @Suppress("UNCHECKED_CAST")
                val customPrompts = gson.fromJson<List<Map<String, Any>>>(customPromptsRaw, type) ?: emptyList()
                ctx.metadata["custom_prompts"] = customPrompts
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "解析会话自定义提示词失败: ${e.message}")
            }
        }

        // 6. 执行 Pipeline + 转发流式事件
        try {
            // 在单独协程中执行 Pipeline，同时从 Channel 转发事件到 Flow
            kotlinx.coroutines.coroutineScope {
                // HookExecutor 事件由 ChatViewModel 直接收集（独立于 localChatJob），
                // 避免阻塞 coroutineScope 导致下一次聊天被取消时报 "StandaloneCoroutine was cancelled"
                val pipelineJob = launch {
                    try {
                        val tools = if (session.sessionMode.equals("agent", ignoreCase = true)) {
                            com.nekobot.app.data.local.ai.buildLocalAgentToolDefinitions() +
                                com.nekobot.app.data.local.ai.buildLocalSkillToolDefinitions() +
                                prepareMcpAgentTools()
                        } else {
                            emptyList()
                        }
                        com.nekobot.app.data.local.ai.aiPipeline.process(
                            ctx = ctx,
                            callbacks = callbacks,
                            tools = tools
                        )
                    } finally {
                        callbacks.eventChannel.close()
                    }
                }
                // 从 Channel 转发事件（在当前协程上下文中 emit，不跨协程）
                for (event in callbacks.eventChannel) {
                    emit(event)
                }
                pipelineJob.join()
            }

            // 会话自动命名：异步触发，成功后通过 RealtimeEvent.SessionRenamed 通知 UI
            if (
                !generationController.isStopped &&
                ctx.finalContent.isNotBlank() &&
                !ctx.metadata.containsKey("is_heartbeat")
            ) {
                try {
                    val latestSession = sessionDao.getById(sessionId)
                    val latestMessages = messageDao.listBySession(sessionId)
                    if (latestSession != null && latestMessages.isNotEmpty()) {
                        val charName = character?.name ?: latestSession.characterName ?: ""
                        val charDesc = character?.description ?: ""
                        val newName = sessionNameGenerator.tryAutoName(
                            session = latestSession,
                            messages = latestMessages,
                            characterName = charName,
                            characterDescription = charDesc
                        )
                        if (newName != null) {
                            // 持久化新名称到 DB
                            sessionDao.updateName(sessionId, newName, com.nekobot.app.data.local.LocalRepository.nowIsoStatic())
                            // 通知 UI 刷新
                            emit(RealtimeEvent.SessionRenamed(sessionId, newName))
                            com.nekobot.app.data.local.LocalLogger.i(TAG, "会话自动命名: $sessionId -> $newName")
                        }
                    }
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "会话自动命名失败（不影响主流程）: ${e.message}", e)
                }
            }

            // 剧情模式的每轮回复必须真正落入故事图；此前这里只生成了悬空选项，
            // 导致故事地图始终为空或无法形成边。
            if (
                !generationController.isStopped &&
                session.plotMode &&
                character != null &&
                ctx.finalContent.isNotBlank()
            ) {
                val manager = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
                val parent = manager.getLatestNode(sessionId)
                val selectedChoice = parent?.selectedChoiceId?.let(manager::getChoice)
                val turn = ctx.characterTurn
                val node = com.nekobot.app.data.local.ai.PlotNode(
                    conversationId = sessionId,
                    characterId = character.id,
                    title = selectedChoice?.text?.take(80)
                        ?: userMessage.replace('\n', ' ').take(80).ifBlank { "剧情节点" },
                    summary = ctx.finalContent.take(500),
                    level = selectedChoice?.level ?: "normal",
                    scene = turn?.state?.scene ?: emptyMap(),
                    stateSnapshot = turn?.state?.toDict() ?: emptyMap(),
                    relationshipSnapshot = turn?.relationship?.toDict() ?: emptyMap(),
                    parentNodeId = parent?.id,
                    userMessage = userMessage,
                    assistantMessage = ctx.finalContent,
                    activityType = turn?.state?.scene?.get("current_activity") as? String ?: "chat",
                    location = turn?.state?.scene?.get("location") as? String ?: "",
                    mood = turn?.state?.mood ?: ""
                )
                if (selectedChoice != null) manager.branchFrom(selectedChoice.id, node)
                else {
                    manager.addNode(node)
                    if (parent != null) {
                        manager.addEdge(
                            com.nekobot.app.data.local.ai.PlotEdge(
                                fromNodeId = parent.id,
                                toNodeId = node.id
                            )
                        )
                        manager.setActiveNode(sessionId, node.id)
                    }
                }
                currentPlotNode = node
                persistPlotGraph()
            }

            // Phase 5.5: 保存 prompt_stack_debug 和 composed_system_prompt 到会话（供会话详情页展示）
            val stackDebug = ctx.metadata["prompt_stack_debug"]
            if (stackDebug != null) {
                try {
                    sessionDao.updatePromptStackDebug(sessionId, gson.toJson(stackDebug))
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "保存 prompt_stack_debug 失败: ${e.message}")
                }
            }
            val composedPrompt = ctx.metadata["composed_system_prompt"] as? String
            if (!composedPrompt.isNullOrBlank()) {
                try {
                    sessionDao.updateComposedSystemPrompt(sessionId, composedPrompt)
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "保存 composed_system_prompt 失败: ${e.message}")
                }
            }

            // Phase 6: 剧情模式 → 生成选项
            if (!generationController.isStopped && session.plotMode) {
                try {
                    val plotGen = com.nekobot.app.data.local.ai.PlotChoiceGenerator(
                        aiClient, { aiModelDao.getActive() },
                        onTokenUsage = ::recordPlotTokenUsage
                    )
                    val recentHistory = messageDao.listBySession(sessionId)
                        .takeLast(6)
                        .filter { it.role != "system" }
                        .map { mapOf("role" to it.role, "content" to it.content) }
                    val choices = plotGen.generate(
                        responseText = ctx.finalContent,
                        recentHistory = recentHistory,
                        style = session.plotChoiceStyle ?: "default",
                        outline = session.plotOutline ?: ""
                    )
                    if (choices.isNotEmpty()) {
                        // 为每个选项添加 id（parsePlotChoices 要求 id 非空）
                        val choicesWithId = choices.mapIndexed { idx, c ->
                            c.toMutableMap().apply { put("id", "plot_${System.currentTimeMillis()}_$idx") }
                        }
                        currentPlotNode?.let { node ->
                            val manager = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
                            choicesWithId.forEach { choice ->
                                manager.addChoice(
                                    com.nekobot.app.data.local.ai.PlotChoice(
                                        id = choice["id"].orEmpty(),
                                        nodeId = node.id,
                                        text = choice["text"].orEmpty(),
                                        level = choice["level"] ?: "normal",
                                        intent = choice["intent"].orEmpty()
                                    )
                                )
                            }
                            persistPlotGraph()
                        }
                        // 包装成 {"choices": [...]} 格式（parsePlotChoices 期望 JsonObject）
                        val payload = com.google.gson.JsonObject().apply {
                            add("choices", gson.toJsonTree(choicesWithId))
                        }
                        // 持久化到 SharedPreferences，重新进入会话时可恢复
                        savePlotChoices(sessionId, payload.toString())
                        emit(RealtimeEvent.PlotChoices(payload))
                    }
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项生成失败（不影响主流程）: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            emit(RealtimeEvent.Error(e.message ?: "Pipeline 执行失败"))
            emit(RealtimeEvent.StreamEnd(sessionId))
        }
        } catch (e: Exception) {
            emit(RealtimeEvent.Error(e.message ?: "本地聊天失败"))
            emit(RealtimeEvent.StreamEnd(sessionId))
        } finally {
            activeGenerations.remove(sessionId, generationController)
        }
    }.flowOn(Dispatchers.IO)

    /** 提交本地 Agent 命令授权结果。 */
    fun respondToExecConfirmation(
        requestId: String,
        sessionId: String,
        authorization: com.nekobot.app.data.remote.ExecAuthorization
    ): Boolean = localExecAuthorizationManager.resolve(
        requestId = requestId,
        sessionId = sessionId,
        authorization = authorization
    )

    /**
     * 本地模式：单独重新生成剧情选项（不重新跑完整 Pipeline）。
     * 复用最近一轮的 assistant 回复 + 最近历史调用 PlotChoiceGenerator。
     */
    fun regeneratePlotChoicesLocal(sessionId: String): Flow<RealtimeEvent> = flow {
        try {
            val session = sessionDao.getById(sessionId)
                ?: run {
                    emit(RealtimeEvent.Error("会话不存在"))
                    return@flow
                }
            if (!session.plotMode) {
                emit(RealtimeEvent.Error("未开启剧情模式"))
                return@flow
            }
            val activeModel = aiModelDao.getActive()
                ?: run {
                    emit(RealtimeEvent.Error("未配置激活的 AI 模型"))
                    return@flow
                }

            // 取最近 assistant 回复 + 历史
            val recentMsgs = messageDao.listBySession(sessionId)
                .filter { it.role != "system" }
                .takeLast(6)
            val lastAssistant = recentMsgs.lastOrNull { it.role == "assistant" }
                ?: run {
                    emit(RealtimeEvent.Error("没有可用的助手回复"))
                    return@flow
                }
            val recentHistory = recentMsgs.map { mapOf("role" to it.role, "content" to it.content) }

            val plotGen = com.nekobot.app.data.local.ai.PlotChoiceGenerator(
                aiClient, { aiModelDao.getActive() },
                onTokenUsage = ::recordPlotTokenUsage
            )
            val choices = plotGen.generate(
                responseText = lastAssistant.content,
                recentHistory = recentHistory,
                style = session.plotChoiceStyle ?: "default",
                outline = session.plotOutline ?: ""
            )
            if (choices.isNotEmpty()) {
                val choicesWithId = choices.mapIndexed { idx, c ->
                    c.toMutableMap().apply { put("id", "plot_${System.currentTimeMillis()}_$idx") }
                }
                val manager = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
                manager.getLatestNode(sessionId)?.let { node ->
                    manager.deleteChoicesForNode(node.id)
                    choicesWithId.forEach { choice ->
                        manager.addChoice(
                            com.nekobot.app.data.local.ai.PlotChoice(
                                id = choice["id"].orEmpty(),
                                nodeId = node.id,
                                text = choice["text"].orEmpty(),
                                level = choice["level"] ?: "normal",
                                intent = choice["intent"].orEmpty()
                            )
                        )
                    }
                    persistPlotGraph()
                }
                val payload = com.google.gson.JsonObject().apply {
                    add("choices", gson.toJsonTree(choicesWithId))
                }
                savePlotChoices(sessionId, payload.toString())
                emit(RealtimeEvent.PlotChoices(payload))
            } else {
                emit(RealtimeEvent.Error("未能生成剧情选项"))
            }
        } catch (e: Exception) {
            emit(RealtimeEvent.Error(e.message ?: "重新生成剧情选项失败"))
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

        // 把被压缩的原始消息归档为一个独立会话（archive session），供"提取归档 N 轮"使用
        val source = sessionDao.getById(sessionId)
        val archiveId = UUID.randomUUID().toString()
        val now = nowIso()
        if (source != null) {
            val archiveEntity = source.copy(
                id = archiveId,
                name = "${source.name}（归档 ${now.take(10)})",
                archived = true,
                createdAt = now,
                updatedAt = now,
                archiveSessionId = null
            )
            sessionDao.upsert(archiveEntity)
            // 复制被压缩的消息到归档会话
            val archiveMsgs = toCompress.map { m ->
                m.copy(id = UUID.randomUUID().toString(), sessionId = archiveId, createdAt = m.createdAt)
            }
            if (archiveMsgs.isNotEmpty()) messageDao.upsertAll(archiveMsgs)
            // 把归档会话 id 回写到源会话
            val updated = source.copy(archiveSessionId = archiveId, updatedAt = now)
            sessionDao.update(updated)
        }

        // 删除被压缩的消息
        toCompress.forEach { messageDao.deleteById(it.id) }
        // 在最前面插入摘要 system 消息（用 system role，prompt 构建时会被跳过）
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

    /**
     * 从归档会话末尾提取 N 轮对话回到当前会话。
     *
     * 本地模式：从 [LocalSessionEntity.archiveSessionId] 找到归档会话，
     * 取末尾 N 轮（user+assistant 配对），追加到当前会话消息列表末尾。
     */
    suspend fun restoreFromArchive(sessionId: String, turns: Int): Boolean = withContext(Dispatchers.IO) {
        val source = sessionDao.getById(sessionId) ?: return@withContext false
        val archiveId = source.archiveSessionId ?: return@withContext false
        val archiveMsgs = messageDao.listBySession(archiveId)
        if (archiveMsgs.isEmpty()) return@withContext false

        // 一轮 = 一条 user + 一条 assistant；从末尾取 N 轮
        val pairs = mutableListOf<List<LocalMessageEntity>>()
        var i = archiveMsgs.lastIndex
        while (i >= 0 && pairs.size < turns) {
            // 找到一对 user+assistant
            if (archiveMsgs[i].role == "assistant" && i > 0 && archiveMsgs[i - 1].role == "user") {
                pairs.add(0, listOf(archiveMsgs[i - 1], archiveMsgs[i]))
                i -= 2
            } else {
                i -= 1
            }
        }
        if (pairs.isEmpty()) return@withContext false

        val base = Instant.now()
        val toAppend = pairs.flatten().mapIndexed { idx, m ->
            val ts = base.plusMillis(idx.toLong()).toString()
            m.copy(id = UUID.randomUUID().toString(), sessionId = sessionId, timestamp = ts, createdAt = ts)
        }
        messageDao.upsertAll(toAppend)
        sessionDao.touch(
            sessionId,
            lastMessage = toAppend.lastOrNull()?.content?.take(200) ?: "",
            count = messageDao.countBySession(sessionId),
            updatedAt = base.toString()
        )
        true
    }

    /** 获取归档会话及其消息（用于"查看归档"对话框）。 */
    suspend fun viewArchive(sessionId: String): Pair<Session?, List<Message>> = withContext(Dispatchers.IO) {
        val source = sessionDao.getById(sessionId) ?: return@withContext null to emptyList()
        val archiveId = source.archiveSessionId ?: return@withContext null to emptyList()
        val archiveSession = sessionDao.getById(archiveId)?.toSession()
        val archiveMsgs = messageDao.listBySession(archiveId).map { it.toMessage() }
        archiveSession to archiveMsgs
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
        // 列表页用 book.entries.size 显示条目数，所以每个 book 都要附带 entries
        worldBookDao.listAll().map { entity ->
            entity.toWorldBook().copy(
                entries = worldBookDao.listEntries(entity.id).map { it.toEntry() }
            )
        }
    }

    suspend fun getWorldBook(id: String): WorldBook? = withContext(Dispatchers.IO) {
        worldBookDao.getById(id)?.toWorldBook()
    }

    suspend fun upsertWorldBook(book: WorldBook): WorldBook = withContext(Dispatchers.IO) {
        val now = nowIso()
        val entity = book.toEntity(now)
        // 保留现有条目：REPLACE 冲突策略会先 DELETE 旧行再 INSERT，触发外键 CASCADE 删除所有条目；
        // 这里先读出条目，upsert 后再插回，避免切换角色等场景下条目内容丢失
        val existingEntries = worldBookDao.listEntries(entity.id)
        worldBookDao.upsert(entity)
        if (existingEntries.isNotEmpty()) {
            worldBookDao.upsertEntries(existingEntries)
        }
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

    // ==================== AI 生成（本地模式）====================

    /**
     * AI 生成角色卡：使用当前激活的本地 AI 模型，按后端相同的 system prompt 生成。
     * @return 生成的 CharacterPreset（未持久化，由调用方决定 createCharacter 保存）
     */
    suspend fun aiGenerateCharacter(description: String): CharacterPreset = withContext(Dispatchers.IO) {
        val activeModel = aiModelDao.getActive()
            ?: throw IllegalStateException("未配置激活的 AI 模型")
        val systemPrompt = buildCharacterSystemPrompt()
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请根据以下描述创建角色卡：\n\n${description.trim()}")
        )
        val result = aiClient.chatOnce(activeModel, messages)
        if (result.error != null) throw IllegalStateException(result.error)
        // 记账
        val usage = result.usage
        if (usage.isNotEmpty()) {
            val input = usage["prompt_tokens"] ?: usage["input_tokens"] ?: 0
            val output = usage["completion_tokens"] ?: usage["output_tokens"] ?: 0
            appendTokenUsageRecord(
                sessionId = currentSessionId,
                model = activeModel.model,
                inputTokens = input,
                outputTokens = output,
                timestamp = nowIsoTimestamp(),
                source = "web",
                purpose = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
            )
        }
        val content = stripMarkdownCodeFence(result.content)
        val obj = JsonParser.parseString(content).asJsonObject
        // 填充默认值，与后端保持一致
        val defaultState = JsonObject().apply {
            addProperty("affection", 50)
            addProperty("trust", 50)
            addProperty("familiarity", 30)
            addProperty("dependency", 30)
            addProperty("security", 50)
            addProperty("mood", "开心")
        }
        val stateEl = obj.get("state")
        val mergedState = if (stateEl != null && stateEl.isJsonObject) {
            val s = stateEl.asJsonObject
            defaultState.keySet().forEach { k ->
                if (!s.has(k) || s.get(k).isJsonNull) s.add(k, defaultState.get(k))
            }
            s
        } else defaultState
        obj.add("state", mergedState)
        if (!obj.has("portrait") || obj.get("portrait").isJsonNull) obj.addProperty("portrait", "")
        gson.fromJson(obj, CharacterPreset::class.java)
    }

    /**
     * AI 批量生成世界书条目：使用当前激活的本地 AI 模型生成 5-10 个条目并立即持久化。
     * @return 生成的条目列表（已写入数据库）
     */
    suspend fun aiGenerateWorldBookEntries(bookId: String, topic: String?): List<WorldBookEntry> =
        withContext(Dispatchers.IO) {
            val activeModel = aiModelDao.getActive()
                ?: throw IllegalStateException("未配置激活的 AI 模型")
            val book = worldBookDao.getById(bookId)
                ?: throw IllegalStateException("世界书不存在")
            // 收集绑定角色的信息
            val charInfos = mutableListOf<String>()
            book.characterId?.takeIf { it.isNotBlank() }?.let { cid ->
                val c = characterDao.getById(cid)
                if (c != null) {
                    charInfos.add(
                        buildString {
                            append("【${c.name ?: "未命名角色"}】\n")
                            c.description?.takeIf { it.isNotBlank() }?.let { append("描述: $it\n") }
                            c.basicInfo?.takeIf { it.isNotBlank() }?.let { append("基本信息: $it\n") }
                            c.personality?.takeIf { it.isNotBlank() }?.let { append("性格: $it\n") }
                            c.scenario?.takeIf { it.isNotBlank() }?.let { append("背景设定: $it\n") }
                            c.rules?.takeIf { it.isNotBlank() }?.let {
                                val rulesText = try {
                                    JsonParser.parseString(it).asJsonArray.joinToString(", ") { r -> r.asString }
                                } catch (_: Exception) { it }
                                append("行为规则: $rulesText")
                            }
                        }
                    )
                }
            }
            val systemPrompt = buildWorldBookSystemPrompt(charInfos, topic)
            val userMsg = buildString {
                append("请为世界书「${book.name ?: "未命名"}」生成世界观条目。")
                book.description?.takeIf { it.isNotBlank() }?.let { append("\n世界书描述：$it") }
                topic?.takeIf { it.isNotBlank() }?.let { append("\n主题方向：$it") }
                if (charInfos.isEmpty() && topic.isNullOrBlank()) {
                    append("\n（未绑定角色也未指定主题，请根据世界书名称自由发挥）")
                }
            }
            val messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMsg)
            )
            val result = aiClient.chatOnce(activeModel, messages)
            if (result.error != null) throw IllegalStateException(result.error)
            // 记账
            val usage = result.usage
            if (usage.isNotEmpty()) {
                val input = usage["prompt_tokens"] ?: usage["input_tokens"] ?: 0
                val output = usage["completion_tokens"] ?: usage["output_tokens"] ?: 0
                appendTokenUsageRecord(
                    sessionId = currentSessionId,
                    model = activeModel.model,
                    inputTokens = input,
                    outputTokens = output,
                    timestamp = nowIsoTimestamp(),
                    source = "web",
                    purpose = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
                )
            }
            val content = stripMarkdownCodeFence(result.content)
            val parsed = JsonParser.parseString(content).asJsonObject
            val arr = parsed.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw IllegalStateException("AI 未生成有效条目")
            val created = mutableListOf<WorldBookEntry>()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val keysEl = o.get("keywords") ?: o.get("keys")
                val keys = keysEl?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.map { it.asString } ?: emptyList()
                val contentStr = o.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                if (contentStr.isBlank()) continue
                val priority = o.get("priority")?.takeIf { !it.isJsonNull }?.asInt ?: 10
                val position = o.get("position")?.takeIf { !it.isJsonNull }?.asString ?: "before_char"
                val entry = WorldBookEntry(
                    id = UUID.randomUUID().toString(),
                    keys = keys.takeIf { it.isNotEmpty() },
                    content = contentStr,
                    comment = o.get("comment")?.takeIf { !it.isJsonNull }?.asString,
                    enabled = true,
                    constant = o.get("always_on")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    selective = true,
                    priority = priority,
                    position = position,
                    caseSensitive = false
                )
                val saved = upsertEntry(bookId, entry)
                created.add(saved)
            }
            created
        }

    /** 剥离可能的 markdown 代码块围栏，返回纯 JSON 字符串 */
    private fun stripMarkdownCodeFence(raw: String): String {
        val trimmed = raw.trim()
        val regex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
        return regex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
    }

    /** 角色卡 AI 生成的 system prompt（与后端 personality.py 一致） */
    private fun buildCharacterSystemPrompt(): String = """你是一个角色卡生成专家。用户会描述一个角色，你需要根据描述生成一个完整的角色卡JSON。

你必须严格按照以下JSON格式返回（不要包含任何额外的文字说明，只返回JSON）：

{
    "name": "角色名称",
    "description": "简短角色描述（用于卡片显示，20字以内）",
    "avatar": "fas fa-star（FontAwesome图标类名，从以下选择最合适的：fas fa-cat, fas fa-dragon, fas fa-hat-wizard, fas fa-skull, fas fa-robot, fas fa-user-secret, fas fa-user-ninja, fas fa-user-astronaut, fas fa-user-graduate, fas fa-user-tie, fas fa-user, fas fa-crown, fas fa-heart, fas fa-star, fas fa-moon, fas fa-sun, fas fa-fire, fas fa-ghost, fas fa-magic, fas fa-shield-haltered, fas fa-wand-sparkles）",
    "tags": ["标签1", "标签2", "标签3"],
    "basicInfo": "角色的基本资料（身高、年龄、职业、外貌、喜好等），每行一项",
    "personality": "性格特点的详细描述",
    "scenario": "角色的背景故事和世界观设定",
    "firstMessage": "新会话中AI自动发送的第一条消息（要符合角色风格）",
    "exampleDialogues": "<user>用户消息示例\n<assistant>角色回复示例（要符合角色风格和语气）",
    "responseFormat": "期望的回复格式描述，如：（动作描写）对话内容【心情/附加信息】",
    "rules": ["行为规则1", "行为规则2", "行为规则3"],
    "state": {
        "affection": 50,
        "trust": 50,
        "familiarity": 30,
        "dependency": 30,
        "security": 50,
        "mood": "开心"
    }
}

要求：
1. name 必须有创意且贴合描述
2. tags 至少3个，最多5个
3. basicInfo 要具体详细，包含形象特征
4. personality 要生动、有层次
5. scenario 要有沉浸感
6. firstMessage 要符合角色性格，自然不做作
7. exampleDialogues 至少包含2轮对话示例
8. rules 要覆盖角色行为约束和特色
9. state 中的关系初始值必须根据角色设定合理设置：
   - affection（好感）：根据角色对用户的初始态度设置（0-100）
   - trust（信任）：根据角色对用户的初始信任程度设置（0-100）
   - familiarity（熟悉）：根据角色与用户的初始熟悉度设置（0-100）
   - dependency（依赖）：根据角色对用户的初始依赖程度设置（0-100）
   - security（安全感）：根据角色在关系中的初始安全感设置（0-100）
   - mood（心情）：根据角色当前心情设置
10. 所有字段都用中文填写"""

    /** 世界书 AI 生成的 system prompt（与后端 world_book.py 一致） */
    private fun buildWorldBookSystemPrompt(charInfos: List<String>, topic: String?): String {
        val charSection = if (charInfos.isNotEmpty()) {
            "\n\n以下是已绑定的角色信息，请根据这些角色的世界观和设定来生成内容：\n\n" + charInfos.joinToString("\n\n")
        } else ""
        val topicSection = if (!topic.isNullOrBlank()) "\n\n用户指定的主题/方向：$topic" else ""
        return """你是一个世界观设定专家。请为一个世界书生成条目。

每个条目包含以下字段：
- name: 条目名称（简短有辨识度）
- keywords: 关键词列表（3-5个，用于在用户消息中匹配触发此条目）
- content: 注入内容（当关键词命中时，这段内容会被注入到 AI 的提示词中，帮助 AI 理解世界观）
- match_mode: "any"（任一关键词命中即触发）或 "all"（全部命中才触发）
- priority: 优先级数字（0-100，越高越优先注入）
- entry_type: 条目类型，可选值：
  - "lore" 世界观设定
  - "location" 地点
  - "npc" NPC角色
  - "faction" 阵营/组织
  - "relationship" 角色与用户关系
  - "rule" 世界规则
  - "style" 叙事风格
  - "event" 剧情事件
  - "secret" 隐藏真相（不会被助手回复自动触发）
- trigger_sources: 触发源列表，可选值：
  - "user" 用户消息触发（默认）
  - "assistant_recent" 助手最近回复触发（用于维持场景连续性）
  - "history" 历史上下文触发
  - "scene_state" 场景状态触发
  一般条目用 ["user"]，地点/NPC/事件类建议加上 "assistant_recent" 和 "scene_state"
- weight: 额外权重（0-50，用于微调优先级）
- always_on: 是否常驻注入（true/false，只有核心规则才设为true）
- cooldown_turns: 冷却轮数（命中后需间隔多少轮才能再次触发，0=无冷却）

请生成 5-10 个条目，覆盖该世界观的核心设定。
$charSection$topicSection

你必须严格按照以下JSON格式返回（不要包含任何额外的文字说明，只返回JSON）：

{
    "entries": [
        {
            "name": "条目名称",
            "keywords": ["关键词1", "关键词2", "关键词3"],
            "content": "当用户消息命中关键词时注入的内容...",
            "match_mode": "any",
            "priority": 50,
            "entry_type": "lore",
            "trigger_sources": ["user"],
            "weight": 0,
            "always_on": false,
            "cooldown_turns": 0
        }
    ]
}

要求：
1. keywords 应该是用户聊天中可能出现的词语，不要太生僻
2. content 要具体、有信息量，帮助 AI 角色扮演时理解世界观
3. 优先级根据条目重要程度分配（核心设定 > 细节设定）
4. 如果有绑定角色，生成的设定要与角色背景契合
5. entry_type 和 trigger_sources 要根据条目内容合理选择
6. 地点、NPC、事件类条目的 trigger_sources 建议包含 "assistant_recent"
7. secret 类型不要设置 "assistant_recent" 触发源，防止提前暴露
"""
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

    /** 获取指定 purpose 的激活模型。未指定 purpose 时回退到默认 chat。 */
    suspend fun getActiveModel(purpose: String): LocalAiModelEntity? = withContext(Dispatchers.IO) {
        val p = purpose.ifBlank { "chat" }
        aiModelDao.getActiveByPurpose(p) ?: aiModelDao.getActive()
    }

    /** 获取指定 purpose 下所有启用的模型（按 priority 升序，用于故障转移队列）。 */
    suspend fun listModelsByPurpose(purpose: String): List<LocalAiModelEntity> = withContext(Dispatchers.IO) {
        aiModelDao.listByPurpose(purpose.ifBlank { "chat" })
    }

    suspend fun upsertAiModel(model: LocalAiModelEntity) = withContext(Dispatchers.IO) {
        aiModelDao.upsert(model)
    }

    suspend fun setActiveModel(id: String) = withContext(Dispatchers.IO) {
        aiModelDao.setActive(id)
    }

    /** 设置指定 purpose 的激活模型，同 purpose 下其他模型自动取消激活。 */
    suspend fun setActiveModelForPurpose(id: String, purpose: String) = withContext(Dispatchers.IO) {
        val p = purpose.ifBlank { "chat" }
        // 若目标模型 purpose 与传入 purpose 不一致，则先校正
        val target = aiModelDao.getById(id)
        if (target != null && target.purpose != p) {
            aiModelDao.upsert(target.copy(purpose = p))
        }
        aiModelDao.setActiveForPurpose(id, p)
    }

    suspend fun deleteAiModel(id: String) = withContext(Dispatchers.IO) {
        aiModelDao.deleteById(id)
    }

    suspend fun testModel(model: LocalAiModelEntity): LocalAiResult = aiClient.testModel(model)

    /**
     * 本地 STT 语音识别：通过故障转移队列调用 purpose=stt 模型，将音频字节数组转为文字。
     *
     * 队列执行：按 (priority, createdAt) 升序逐个尝试，自动跳过冷却中/超限额的模型。
     *
     * @param audioBytes 音频字节（mp3/wav/m4a 等）
     * @param filename 文件名（用于 multipart 上传字段）
     * @param language 语言提示（如 "zh"），可为空
     * @return 识别结果文本，失败抛异常
     */
    suspend fun transcribeSpeech(
        audioBytes: ByteArray,
        filename: String = "audio.m4a",
        language: String? = "zh"
    ): String = withContext(Dispatchers.IO) {
        val queue = queueFor("stt")
        if (queue.isEmpty()) {
            throw IllegalStateException("未配置 STT 模型，请在 AI 配置中心启用 purpose=stt 的模型")
        }
        val exec = try {
            failoverCoordinator.execute(queue, "stt") { model ->
                aiClient.transcribeSpeech(model, audioBytes, filename, language)
            }
        } catch (e: FailoverAllFailedException) {
            throw IllegalStateException(e.message ?: "STT 全部模型失败")
        }
        // STT 通常无 token 用量；若返回结果携带 usage，则按 purpose=chat 记账
        exec.value.usage.takeIf { it.isNotEmpty() }?.let { u ->
            val input = u["prompt_tokens"] ?: u["input_tokens"] ?: 0
            val output = u["completion_tokens"] ?: u["output_tokens"] ?: 0
            if (input > 0 || output > 0) {
                appendTokenUsageRecord(
                    sessionId = currentSessionId,
                    model = exec.model.model,
                    inputTokens = input,
                    outputTokens = output,
                    timestamp = nowIso(),
                    source = "stt",
                    purpose = TokenStatsManager.PURPOSE_CHAT
                )
            }
        }
        exec.value.content
    }

    // ==================== 故障转移队列驱动的多媒体生成 ===================

    /**
     * 本地 TTS 语音合成：通过故障转移队列调用 purpose=tts 模型。
     * 返回缓存到 cacheDir/tts/ 的音频文件 URI。
     *
     * @param text 待合成文本
     * @param voice OpenAI 兼容音色（如 "alloy"/"nova"/"echo"），默认 "alloy"
     * @param speed 语速倍率，默认 1.0
     * @return [LocalAudioResult] 包含缓存 URI 与所用模型信息
     */
    suspend fun synthesizeAudio(
        text: String,
        voice: String = "alloy",
        speed: Float = 1.0f
    ): LocalAudioResult = withContext(Dispatchers.IO) {
        val queue = queueFor("tts")
        if (queue.isEmpty()) {
            throw IllegalStateException("未配置 TTS 模型，请在 AI 配置中心启用 purpose=tts 的模型")
        }
        val exec = try {
            failoverCoordinator.execute(queue, "tts") { model ->
                aiClient.synthesizeSpeech(model, text, voice, speed)
            }
        } catch (e: FailoverAllFailedException) {
            throw IllegalStateException(e.message ?: "TTS 全部模型失败")
        }
        val cacheDir = appContext?.cacheDir
            ?: throw IllegalStateException("应用上下文未初始化，无法缓存 TTS 音频")
        val cacheFile = File(cacheDir, "tts/${UUID.randomUUID()}.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(exec.value)
        }
        LocalAudioResult(
            cacheUri = android.net.Uri.fromFile(cacheFile).toString(),
            mimeType = "audio/mpeg",
            usedModelId = exec.model.id,
            usedModelName = exec.model.name
        )
    }

    /**
     * 本地图片生成：通过故障转移队列调用 purpose=image_generation 模型。
     * 返回缓存到 cacheDir/image_gen/ 的图片文件 URI 列表。
     *
     * @param prompt 图片描述
     * @param size 图片尺寸（如 "1024x1024"/"1792x1024"），默认 "1024x1024"
     * @param n 生成数量，默认 1
     * @return [LocalImageResult] 列表（每张图一个）
     */
    suspend fun generateImages(
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1
    ): List<LocalImageResult> = withContext(Dispatchers.IO) {
        val queue = queueFor("image_generation")
        if (queue.isEmpty()) {
            throw IllegalStateException("未配置图片生成模型，请在 AI 配置中心启用 purpose=image_generation 的模型")
        }
        val exec = try {
            failoverCoordinator.execute(queue, "image_generation") { model ->
                aiClient.generateImage(model, prompt, size, n)
            }
        } catch (e: FailoverAllFailedException) {
            throw IllegalStateException(e.message ?: "图片生成全部模型失败")
        }
        val cacheDir = appContext?.cacheDir
            ?: throw IllegalStateException("应用上下文未初始化，无法缓存生成图片")
        exec.value.mapNotNull { img ->
            val bytes = img.bytes ?: run {
                // 通过 URL 下载
                val url = img.url ?: return@mapNotNull null
                try {
                    val resp = okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url(url).build()
                    ).execute()
                    resp.body?.bytes() ?: return@mapNotNull null
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
            }
            val cacheFile = File(cacheDir, "image_gen/${UUID.randomUUID()}.png").apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            LocalImageResult(
                cacheUri = android.net.Uri.fromFile(cacheFile).toString(),
                mimeType = "image/png",
                usedModelId = exec.model.id,
                usedModelName = exec.model.name
            )
        }
    }

    /**
     * 远程模式图片生成：直接调用远程 AI 模型列表中 purpose=image_generation 的模型供应商 API。
     * 绕过后端（后端目前无通用图片生成端点），复用 [aiClient] 的 OpenAI 兼容 /images/generations 调用逻辑。
     *
     * 简单故障转移：按 priority 降序遍历模型，第一个成功即返回；全部失败则抛出聚合错误。
     *
     * @param models 远程模型列表（调用方需预先筛选 purpose=image_generation 且 enabled=true）
     * @param prompt 图片描述
     * @param size 图片尺寸
     * @param n 生成数量
     * @return [LocalImageResult] 列表（图片已缓存到 cacheDir/image_gen/）
     */
    suspend fun generateImagesFromRemoteModels(
        models: List<AiModel>,
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1
    ): List<LocalImageResult> = withContext(Dispatchers.IO) {
        if (models.isEmpty()) {
            throw IllegalStateException("未配置图片生成模型，请在 AI 配置中心启用 purpose=image_generation 的模型")
        }
        val cacheDir = appContext?.cacheDir
            ?: throw IllegalStateException("应用上下文未初始化，无法缓存生成图片")

        val errors = mutableListOf<String>()
        for (model in models) {
            val baseUrl = model.baseUrl ?: continue
            val modelName = model.model ?: continue
            try {
                val images = aiClient.generateImage(
                    baseUrl = baseUrl,
                    apiKey = model.apiKey.orEmpty(),
                    modelName = modelName,
                    prompt = prompt,
                    size = size,
                    n = n
                )
                if (images.isNotEmpty()) {
                    return@withContext images.mapNotNull { img ->
                        val bytes = img.bytes ?: run {
                            val url = img.url ?: return@mapNotNull null
                            try {
                                okhttp3.OkHttpClient().newCall(
                                    okhttp3.Request.Builder().url(url).build()
                                ).execute().body?.bytes() ?: return@mapNotNull null
                            } catch (_: Exception) {
                                null
                            } ?: return@mapNotNull null
                        }
                        val cacheFile = File(cacheDir, "image_gen/${UUID.randomUUID()}.png").apply {
                            parentFile?.mkdirs()
                            writeBytes(bytes)
                        }
                        LocalImageResult(
                            cacheUri = android.net.Uri.fromFile(cacheFile).toString(),
                            mimeType = "image/png",
                            usedModelId = model.id ?: "",
                            usedModelName = model.displayName
                        )
                    }
                }
            } catch (e: Exception) {
                errors.add("${model.displayName}: ${e.message}")
            }
        }
        throw IllegalStateException("图片生成全部模型失败：\n${errors.joinToString("\n")}")
    }

    /**
     * 通过故障转移队列调用 purpose=vision 模型理解单张图片。
     * 用于聊天流程中解析用户上传的图片附件，生成文本描述注入到 prompt。
     *
     * 成功：返回模型给出的描述文本。
     * 失败：返回非阻塞失败标记文本，包含 [VISION_FAILURE_MARKER]，提示后续 LLM 不得猜测图片内容。
     */
    suspend fun describeImageViaQueue(
        imageUrl: String,
        question: String = "请详细描述这张图片的内容。",
        requestTag: String? = null,
        shouldStop: () -> Boolean = { false }
    ): String = withContext(Dispatchers.IO) {
        if (shouldStop()) throw kotlinx.coroutines.CancellationException("生成已停止")
        var queue = queueFor("vision")
        // 回退：若未配置 vision 模型，尝试使用 chat 模型（GPT-4o 等多模态聊天模型支持图片）
        if (queue.isEmpty()) {
            com.nekobot.app.data.local.LocalLogger.w("LocalRepo", "describeImageViaQueue: 视觉模型队列为空，回退到 chat 模型队列")
            queue = queueFor("chat")
        }
        if (queue.isEmpty()) {
            com.nekobot.app.data.local.LocalLogger.w("LocalRepo", "describeImageViaQueue: 视觉和聊天模型队列均为空（没有 purpose=vision/chat 且 enabled=1 的模型）")
            return@withContext VISION_FAILURE_MARKER + "未配置视觉模型或聊天模型，不得猜测图片内容。"
        }
        com.nekobot.app.data.local.LocalLogger.i("LocalRepo", "describeImageViaQueue: 开始识别 | 队列=${queue.size}个模型 | 首选=${queue.first().name} | url=${if (imageUrl.startsWith("data:")) "data:${imageUrl.length}字符" else imageUrl.take(100)}")
        try {
            val exec = failoverCoordinator.execute(queue, "vision") { model ->
                if (shouldStop()) throw kotlinx.coroutines.CancellationException("生成已停止")
                try {
                    aiClient.describeImage(model, imageUrl, question, requestTag)
                } catch (error: Exception) {
                    if (shouldStop()) {
                        throw kotlinx.coroutines.CancellationException("生成已停止").apply {
                            initCause(error)
                        }
                    }
                    throw error
                }
            }
            exec.value.usage.takeIf { it.isNotEmpty() }?.let { u ->
                val input = u["prompt_tokens"] ?: u["input_tokens"] ?: 0
                val output = u["completion_tokens"] ?: u["output_tokens"] ?: 0
                if (input > 0 || output > 0) {
                    appendTokenUsageRecord(
                        sessionId = currentSessionId,
                        model = exec.model.model,
                        inputTokens = input,
                        outputTokens = output,
                        timestamp = nowIso(),
                        source = "vision",
                        purpose = TokenStatsManager.PURPOSE_VISION
                    )
                }
            }
            com.nekobot.app.data.local.LocalLogger.i("LocalRepo", "describeImageViaQueue: 识别成功 | 模型=${exec.model.name} | 结果长度=${exec.value.content.length}")
            exec.value.content
        } catch (e: FailoverAllFailedException) {
            com.nekobot.app.data.local.LocalLogger.w("LocalRepo", "describeImageViaQueue: 所有模型失败 | 尝试=${e.attempts.size} | 失败原因=${e.failures.map { it.message }}")
            VISION_FAILURE_MARKER + "视觉识别失败：" + (e.message ?: "所有模型均不可用") + "。不得猜测图片内容。"
        }
    }

    /**
     * 批量解析图片附件：对每个 (url, name) 调用 [describeImageViaQueue]。
     * 失败的图片使用非阻塞标记文本占位，不阻断主聊天流程。
     *
     * @param images 待解析的图片列表（imageUrl + 可选附件名）
     * @return 每个图片对应的描述或失败标记文本，与输入顺序一致
     */
    suspend fun resolveImagesViaQueue(
        images: List<Pair<String, String?>>
    ): List<String> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext emptyList()
        images.map { (url, name) ->
            val desc = runCatching { describeImageViaQueue(url) }.getOrDefault(
                VISION_FAILURE_MARKER + "视觉识别异常，不得猜测图片内容。"
            )
            buildString {
                if (!name.isNullOrBlank()) append("【附件: $name】\n")
                append(desc)
            }
        }
    }

    // ==================== Token 用量统计（从独立存储聚合，不受会话删除影响）====================

    /** 本地 token 用量统计（基于独立 SharedPreferences 存储，非 messageDao）。 */
    suspend fun tokenStats(): TokenStats = withContext(Dispatchers.IO) {
        val records = readTokenUsageRecords()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        var todayInput = 0L
        var todayOutput = 0L
        var monthTotal = 0L
        var totalTokens = 0L
        var msgCount = 0L

        for (rec in records) {
            val input = rec.get("input_tokens")?.asLong ?: 0
            val output = rec.get("output_tokens")?.asLong ?: 0
            val total = rec.get("total_tokens")?.asLong ?: (input + output)
            val date = rec.get("date")?.asString ?: ""
            totalTokens += total
            msgCount++
            if (date == todayStr) {
                todayInput += input
                todayOutput += output
            }
            if (date.startsWith(monthStr)) {
                monthTotal += total
            }
        }

        // 最近记录：最多 50 条
        val recent = records.takeLast(50).reversed().map { rec ->
            JsonObject().apply {
                addProperty("session_id", rec.get("session_id")?.asString ?: "")
                addProperty("model", rec.get("model")?.asString ?: "")
                addProperty("input_tokens", rec.get("input_tokens")?.asLong ?: 0)
                addProperty("output_tokens", rec.get("output_tokens")?.asLong ?: 0)
                addProperty("total_tokens", rec.get("total_tokens")?.asLong ?: 0)
                addProperty("timestamp", rec.get("timestamp")?.asString ?: "")
                // 用量来源：chat / state / memory / plot（旧记录无此字段时默认 chat）
                addProperty("source", rec.get("source")?.asString ?: "chat")
                // 用途标签（与 TokenStatsManager 常量对齐）：旧记录无此字段时按 source 推断
                val purpose = rec.get("purpose")?.asString
                    ?: when (rec.get("source")?.asString) {
                        "state" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
                        "memory" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_MEMORY
                        "plot" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_PLOT
                        else -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_CHAT
                    }
                addProperty("purpose", purpose)
            }
        }

        TokenStats(
            today = todayInput + todayOutput,
            month = monthTotal,
            totalTokens = totalTokens,
            todayInput = todayInput,
            todayOutput = todayOutput,
            messageCount = msgCount,
            avgTokensPerMsg = if (msgCount > 0) totalTokens.toDouble() / msgCount else 0.0,
            estimatedCost = "—",
            recentRecords = recent,
            records = recent
        )
    }

    /**
     * 获取指定会话历史总 Token 数：从本地 token 用量记录按 session_id 聚合 input+output。
     */
    suspend fun sessionTokenUsage(sessionId: String): Long = withContext(Dispatchers.IO) {
        val records = readTokenUsageRecords()
        var sum = 0L
        for (rec in records) {
            val sid = rec.get("session_id")?.asString ?: continue
            if (sid != sessionId) continue
            val input = rec.get("input_tokens")?.asLong ?: 0L
            val output = rec.get("output_tokens")?.asLong ?: 0L
            sum += input + output
        }
        sum
    }

    /** 本地 token 用量排行榜（按 model / session 聚合，从独立存储读取）。 */
    suspend fun tokenRankings(): TokenRankings = withContext(Dispatchers.IO) {
        val records = readTokenUsageRecords()

        // 按模型聚合
        val modelsRank = records.groupBy { it.get("model")?.asString ?: "未知" }
            .map { (model, recs) ->
                val input = recs.sumOf { it.get("input_tokens")?.asLong ?: 0 }
                val output = recs.sumOf { it.get("output_tokens")?.asLong ?: 0 }
                JsonObject().apply {
                    addProperty("name", model)
                    addProperty("input_tokens", input)
                    addProperty("output_tokens", output)
                    addProperty("total_tokens", input + output)
                    addProperty("count", recs.size)
                }
            }
            .sortedByDescending { it.get("total_tokens").asLong }

        // 按会话聚合
        val sessionsRank = records.groupBy { it.get("session_id")?.asString ?: "" }
            .map { (sid, recs) ->
                val input = recs.sumOf { it.get("input_tokens")?.asLong ?: 0 }
                val output = recs.sumOf { it.get("output_tokens")?.asLong ?: 0 }
                val sessionName = if (sid.isNotEmpty()) sessionDao.getById(sid)?.name ?: sid else "未知会话"
                JsonObject().apply {
                    addProperty("name", sessionName)
                    addProperty("session_id", sid)
                    addProperty("input_tokens", input)
                    addProperty("output_tokens", output)
                    addProperty("total_tokens", input + output)
                    addProperty("count", recs.size)
                }
            }
            .sortedByDescending { it.get("total_tokens").asLong }

        // 按用途聚合（兼容旧记录：无 purpose 字段时按 source 推断）
        val purposeLabels = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_LABELS
        val purposesRank = records.groupBy { rec ->
            rec.get("purpose")?.asString
                ?: when (rec.get("source")?.asString) {
                    "state" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
                    "memory" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_MEMORY
                    "plot" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_PLOT
                    else -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_CHAT
                }
        }.map { (purpose, recs) ->
            val input = recs.sumOf { it.get("input_tokens")?.asLong ?: 0 }
            val output = recs.sumOf { it.get("output_tokens")?.asLong ?: 0 }
            JsonObject().apply {
                addProperty("name", purposeLabels[purpose] ?: purpose)
                addProperty("purpose", purpose)
                addProperty("input_tokens", input)
                addProperty("output_tokens", output)
                addProperty("total_tokens", input + output)
                addProperty("count", recs.size)
            }
        }.sortedByDescending { it.get("total_tokens").asLong }

        TokenRankings(
            sessions = sessionsRank,
            models = modelsRank,
            users = emptyList(),
            purposes = purposesRank
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
        private const val TAG = "LocalRepository"

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
        pinned = pinned,
        archived = archived,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessage = lastMessage,
        messageCount = messageCount,
        plotMode = plotMode,
        plotRealTimeSync = plotRealTimeSync,
        plotChoiceStyle = plotChoiceStyle,
        plotOutline = plotOutline,
        userPersona = userPersona,
        autoStateInterval = autoStateInterval,
        disabledPromptKeys = disabledPromptKeys?.split(",")?.filter { it.isNotEmpty() },
        customPrompts = customPrompts?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        promptStackDebug = promptStackDebug?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        composedSystemPrompt = composedSystemPrompt,
        isPublic = isPublic,
        proactiveChat = proactiveChat?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        ttsConfig = ttsConfig?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        shareConfig = shareConfig?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        archiveSessionId = archiveSessionId,
        sessionMode = sessionMode
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
        // 合并 input+output 作为总 token 数，供 UI 统计使用
        tokens = listOfNotNull(inputTokens, outputTokens).takeIf { it.size == 2 }?.sum(),
        createdAt = createdAt,
        // 反序列化进度卡片 JSON（agent 模式持久化）
        thinkingCards = thinkingCards?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val type = object : TypeToken<List<ThinkingCard>>() {}.type
                gson.fromJson<List<ThinkingCard>>(it, type)
            }.getOrNull()
        }
    )

    /** 持久化指定用户消息关联的进度卡片列表（agent 模式）。 */
    suspend fun updateMessageThinkingCards(messageId: String, cards: List<ThinkingCard>?) =
        withContext(Dispatchers.IO) {
            val json = cards?.takeIf { it.isNotEmpty() }?.let {
                runCatching { gson.toJson(it) }.getOrNull()
            }
            messageDao.updateThinkingCards(messageId, json)
        }

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
        // 兼容多角色绑定：character_id 列以英文逗号分隔存储多个角色 ID
        characterIds = characterId
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun WorldBook.toEntity(now: String): LocalWorldBookEntity = LocalWorldBookEntity(
        id = id ?: UUID.randomUUID().toString(),
        name = name ?: "未命名世界书",
        description = description,
        // 多角色 ID 以英文逗号拼接存入单列，便于本地 SQLite 查询
        characterId = characterIds
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(",") { it.trim() },
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

    // ==================== 角色记忆（UI 用） ====================

    /** 列出本地角色记忆，映射为 LegacyMemory 格式供 UI 使用。 */
    suspend fun listMemories(characterId: String? = null): List<com.nekobot.app.data.model.LegacyMemory> =
        withContext(Dispatchers.IO) {
            val entities = if (characterId != null) {
                db.memoryDao().listByCharacter(characterId)
            } else {
                db.memoryDao().listAll()
            }
            if (entities.isEmpty()) return@withContext emptyList()
            // 批量查询角色名，避免每条记忆一次 DB 查询
            val charIds = entities.map { it.characterId }.distinct()
            val charNameMap = mutableMapOf<String, String>()
            for (cid in charIds) {
                if (cid.isBlank()) continue
                val char = try { db.characterDao().getById(cid) } catch (_: Exception) { null }
                if (char != null) {
                    charNameMap[cid] = char.name.ifBlank { cid }
                }
            }
            entities.map { it.toLegacyMemory(charNameMap[it.characterId] ?: "") }
        }

    /** 删除本地角色记忆。 */
    suspend fun deleteMemory(id: String) = withContext(Dispatchers.IO) {
        db.memoryDao().deleteById(id)
    }

    /** 新增/更新本地角色记忆（id 存在则更新，否则新增）。 */
    suspend fun saveMemory(
        id: String?, title: String, content: String, summary: String,
        type: String, priority: String, characterId: String?
    ) = withContext(Dispatchers.IO) {
        val now = nowIso()
        val importance = when (priority) {
            "high" -> 8
            "normal" -> 4
            else -> 1
        }
        val memId = id ?: UUID.randomUUID().toString()
        val entity = com.nekobot.app.data.local.db.LocalCharacterMemoryEntity(
            id = memId,
            characterId = characterId ?: "",
            targetId = "local-user",
            title = title,
            content = content,
            summary = summary,
            type = if (type == "short") "short" else "long",
            importance = importance,
            category = "legacy",
            createdAt = now
        )
        db.memoryDao().upsert(entity)
        memId
    }

    private fun com.nekobot.app.data.local.db.LocalCharacterMemoryEntity.toLegacyMemory(characterName: String = ""): com.nekobot.app.data.model.LegacyMemory {
        val typeLabel = when (type) {
            "flash" -> "short"
            "short" -> "short"
            else -> "long"
        }
        val priorityLabel = when {
            importance >= 8 -> "high"
            importance >= 4 -> "normal"
            else -> "low"
        }
        return com.nekobot.app.data.model.LegacyMemory(
            id = id,
            title = title,
            content = content,
            summary = summary,
            type = typeLabel,
            priority = priorityLabel,
            targetId = targetId,
            characterName = characterName,
            createdAt = createdAt,
            updatedAt = createdAt,
            category = category  // 真实 memoryfs category
        )
    }

    // ==================== AI 立绘生成（本地模式） ====================

    /**
     * 本地模式 AI 立绘生成：调用 purpose=image_generation 模型生成竖版立绘，
     * 保存到 filesDir/portraits/，返回与远程一致的成功响应（直接 completed，无需轮询）。
     *
     * 失败时返回 success=false 的 JsonObject：
     * - 未配置图片生成模型 → need_config=true
     * - 其他错误 → error 字段
     */
    suspend fun generatePortraitLocal(
        characterName: String,
        description: String,
        basicInfo: String,
        personality: String
    ): com.google.gson.JsonElement = withContext(Dispatchers.IO) {
        try {
            val prompt = buildPortraitPrompt(characterName, description, basicInfo, personality)
            // 竖版立绘：portrait 尺寸（1024x1792），便于呈现全身角色
            val images = generateImages(prompt = prompt, size = "1024x1792", n = 1)
            if (images.isEmpty()) {
                throw IllegalStateException("图片生成未返回结果")
            }
            // 从缓存复制到 portraits 目录
            val ctx = appContext ?: throw IllegalStateException("应用上下文未初始化")
            val dir = java.io.File(ctx.filesDir, "portraits")
            if (!dir.exists()) dir.mkdirs()
            val sourceUri = android.net.Uri.parse(images.first().cacheUri)
            val sourceFile = sourceUri.path?.let { java.io.File(it) }
            val targetFile = java.io.File(dir, "portrait_${UUID.randomUUID().toString().take(16)}.png")
            if (sourceFile != null && sourceFile.exists()) {
                sourceFile.copyTo(targetFile, overwrite = true)
            } else {
                // 回退：通过 ContentResolver 读取
                ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取生成的图片")
            }
            val portraitUrl = android.net.Uri.fromFile(targetFile).toString()
            com.google.gson.JsonObject().apply {
                addProperty("success", true)
                addProperty("status", "completed")
                addProperty("portrait_url", portraitUrl)
            }
        } catch (e: IllegalStateException) {
            // 未配置图片生成模型等配置类错误
            com.google.gson.JsonObject().apply {
                addProperty("success", false)
                val msg = e.message ?: "AI 立绘生成失败"
                addProperty("need_config", msg.contains("未配置") || msg.contains("image_generation"))
                addProperty("error", msg)
            }
        } catch (e: Exception) {
            com.google.gson.JsonObject().apply {
                addProperty("success", false)
                addProperty("error", e.message ?: "AI 立绘生成失败")
            }
        }
    }

    /** 构建立绘生成 prompt：综合角色名/描述/基础信息/人格，引导生成竖版动漫立绘。 */
    private fun buildPortraitPrompt(
        characterName: String,
        description: String,
        basicInfo: String,
        personality: String
    ): String {
        val sb = StringBuilder()
        sb.append("anime style full-body character portrait, vertical composition, high quality, detailed, ")
        sb.append("character name: $characterName")
        if (description.isNotBlank()) sb.append(", description: $description")
        if (basicInfo.isNotBlank()) sb.append(", basic info: $basicInfo")
        if (personality.isNotBlank()) sb.append(", personality: $personality")
        sb.append(", solo, clean background, masterpiece, best quality")
        return sb.toString()
    }

    // ==================== 状态历程（UI 用） ====================

    /**
     * 获取会话最新的角色运行时状态快照（用于会话详情页"角色进行时状态"展示）。
     * 从 local_state_snapshots 读取最后一条记录，构造与远程 character_runtime_snapshot 一致的 JsonObject。
     * 没有快照时返回 null。
     */
    suspend fun getLatestStateSnapshot(sessionId: String): com.google.gson.JsonObject? = withContext(Dispatchers.IO) {
        val snapshots = db.stateSnapshotDao().listBySession(sessionId)
        val latest = snapshots.lastOrNull() ?: return@withContext null
        com.google.gson.JsonObject().apply {
            addProperty("mood", latest.mood)
            addProperty("mood_intensity", latest.moodIntensity)
            addProperty("energy", latest.energy)
            addProperty("affection", latest.affection)
            addProperty("trust", latest.trust)
            addProperty("familiarity", latest.familiarity)
            addProperty("dependency", latest.dependency)
            addProperty("security", latest.security)
            addProperty("jealousy", latest.jealousy)
        }
    }

    /**
     * 构建本地模式的状态历程时间线。
     *
     * 从 local_state_snapshots 读取每轮 after_turn 写入的真实状态快照，
     * 呈现情绪/精力/关系六维（含 jealousy）随时间的演变，以及本轮质量评分。
     * 快照按时间正序存储，正序返回（旧→新）供 UI 展示，UI 初始定位到末尾（最新）。
     */
    suspend fun listStateHistory(sessionId: String): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        val snapshots = db.stateSnapshotDao().listBySession(sessionId)
        snapshots.map { snap ->
            val entry = mutableMapOf<String, Any>(
                "timestamp" to snap.timestamp,
                "type" to "state_snapshot",
                "source" to "local_runtime",
                "trigger_type" to snap.triggerType,
                "mood" to snap.mood,
                "mood_intensity" to snap.moodIntensity,
                "energy" to snap.energy,
                // 关系六维（含 jealousy）
                "affection" to snap.affection,
                "trust" to snap.trust,
                "familiarity" to snap.familiarity,
                "dependency" to snap.dependency,
                "security" to snap.security,
                "jealousy" to snap.jealousy
            )
            // 质量评分（AutoState 产出，可空）
            snap.qualityScoresJson?.let { json ->
                try {
                    val obj = JsonParser.parseString(json).asJsonObject
                    for ((k, v) in obj.entrySet()) {
                        if (v.isJsonPrimitive) entry["quality_$k"] = v.asFloat
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解析质量评分 JSON 失败: ${e.message}")
                }
            }
            entry
        }
    }

    // ==================== 扩展功能：Hook ====================

    suspend fun listHooks(): List<Hook> = withContext(Dispatchers.IO) {
        db.hookDao().listAll().map { it.toHook() }
    }

    suspend fun createHook(req: HookRequest): Hook = withContext(Dispatchers.IO) {
        val now = nowIso()
        val entity = LocalHookEntity(
            id = UUID.randomUUID().toString(),
            name = req.name,
            event = req.event,
            description = req.description,
            enabled = req.enabled,
            scope = req.scope,
            priority = req.priority,
            actionsJson = gson.toJson(req.actions),
            conditionsJson = req.conditions?.let { gson.toJson(it) },
            permissionsJson = req.permissions?.let { gson.toJson(it) },
            timeoutMs = req.timeoutMs,
            maxRetries = req.maxRetries,
            triggerMode = req.triggerMode,
            conditionLogic = req.conditionLogic,
            characterId = req.characterId,
            conversationId = req.conversationId,
            userId = req.userId,
            createdAt = now,
            updatedAt = now
        )
        db.hookDao().upsert(entity)
        entity.toHook()
    }

    suspend fun updateHook(id: String, req: HookRequest): Hook = withContext(Dispatchers.IO) {
        val existing = db.hookDao().getById(id) ?: throw IllegalStateException("Hook 不存在")
        val updated = existing.copy(
            name = req.name,
            event = req.event,
            description = req.description,
            enabled = req.enabled,
            scope = req.scope,
            priority = req.priority,
            actionsJson = gson.toJson(req.actions),
            conditionsJson = req.conditions?.let { gson.toJson(it) },
            permissionsJson = req.permissions?.let { gson.toJson(it) },
            timeoutMs = req.timeoutMs,
            maxRetries = req.maxRetries,
            triggerMode = req.triggerMode,
            conditionLogic = req.conditionLogic,
            characterId = req.characterId,
            conversationId = req.conversationId,
            userId = req.userId,
            updatedAt = nowIso()
        )
        db.hookDao().upsert(updated)
        updated.toHook()
    }

    suspend fun deleteHook(id: String) = withContext(Dispatchers.IO) {
        db.hookDao().deleteById(id)
    }

    suspend fun toggleHook(id: String): Hook = withContext(Dispatchers.IO) {
        val existing = db.hookDao().getById(id) ?: throw IllegalStateException("Hook 不存在")
        val newEnabled = !existing.enabled
        db.hookDao().setEnabled(id, newEnabled)
        existing.copy(enabled = newEnabled).toHook()
    }

    /**
     * 本地模式 Hook 测试：构造一个临时 Hook 直接执行 actions，返回执行结果。
     *
     * 入参格式与后端 `/api/hooks/test` 一致：可传入完整 Hook 对象或仅 actions 数组。
     * 测试执行不持久化，仅触发 actions 并通过 HookExecutor.events 推送通知。
     */
    suspend fun testHook(body: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        try {
            val obj = body.takeIf { it.isJsonObject }?.asJsonObject
            val actions = obj?.get("actions")?.takeIf { it.isJsonArray }
                ?.let { it.asJsonArray.map { el -> el } }
                ?: (if (body.isJsonArray) body.asJsonArray.map { it } else emptyList())
            val tempHook = com.nekobot.app.data.model.Hook(
                id = "test_${System.currentTimeMillis()}",
                name = obj?.get("name")?.takeUnless { it.isJsonNull }?.asString ?: "测试 Hook",
                event = obj?.get("event")?.takeUnless { it.isJsonNull }?.asString ?: "test",
                actions = actions,
                enabled = true,
                triggerMode = "always",
                conditionLogic = "and"
            )
            hookExecutor.triggerHookDirectly(
                hook = tempHook,
                conversationId = obj?.get("conversation_id")?.takeUnless { it.isJsonNull }?.asString ?: "test_session",
                characterId = obj?.get("character_id")?.takeUnless { it.isJsonNull }?.asString
            )
            JsonObject().apply {
                addProperty("success", true)
                addProperty("message", "Hook 已执行（请查看日志和聊天界面通知）")
                addProperty("actions_executed", actions.size)
                add("input", body)
            }
        } catch (e: Exception) {
            JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "Hook 执行失败: ${e.message}")
                add("input", body)
            }
        }
    }

    suspend fun listHookLogs(hookId: String?, limit: Int): List<HookExecutionLog> = withContext(Dispatchers.IO) {
        // 本地模式不持久化执行日志，返回空列表
        emptyList()
    }

    suspend fun hookStats(): JsonElement = withContext(Dispatchers.IO) {
        val total = db.hookDao().listAll().size
        val enabled = db.hookDao().listEnabled().size
        JsonObject().apply {
            addProperty("total", total)
            addProperty("enabled", enabled)
            addProperty("disabled", total - enabled)
        }
    }

    private fun LocalHookEntity.toHook(): Hook = Hook(
        id = id,
        name = name,
        event = event,
        actions = runCatching { JsonParser.parseString(actionsJson).asJsonArray.map { it } }.getOrDefault(emptyList()),
        description = description,
        enabled = enabled,
        scope = scope,
        priority = priority,
        conditions = conditionsJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        permissions = permissionsJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        triggerMode = triggerMode,
        conditionLogic = conditionLogic,
        characterId = characterId,
        conversationId = conversationId,
        userId = userId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    // ==================== 扩展功能：任务中心 ====================

    suspend fun listTasks(): List<TaskItem> = withContext(Dispatchers.IO) {
        db.taskDao().listAll().map { it.toTaskItem() }
    }

    suspend fun createTask(req: TaskRequest): TaskItem = withContext(Dispatchers.IO) {
        val now = nowIso()
        val entity = LocalTaskEntity(
            id = UUID.randomUUID().toString(),
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            trigger = req.trigger,
            configJson = req.config?.let { gson.toJson(it) },
            targetSessionId = req.targetSessionId,
            prompt = req.prompt,
            createdAt = now
        )
        db.taskDao().upsert(entity)
        entity.toTaskItem()
    }

    suspend fun updateTask(id: String, req: TaskRequest): TaskItem = withContext(Dispatchers.IO) {
        val existing = db.taskDao().getById(id) ?: throw IllegalStateException("任务不存在")
        val updated = existing.copy(
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            trigger = req.trigger,
            configJson = req.config?.let { gson.toJson(it) },
            targetSessionId = req.targetSessionId,
            prompt = req.prompt
        )
        db.taskDao().upsert(updated)
        updated.toTaskItem()
    }

    suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) {
        db.taskDao().deleteById(id)
    }

    suspend fun toggleTask(id: String): TaskItem = withContext(Dispatchers.IO) {
        val existing = db.taskDao().getById(id) ?: throw IllegalStateException("任务不存在")
        val newEnabled = !existing.enabled
        db.taskDao().setEnabled(id, newEnabled)
        existing.copy(enabled = newEnabled).toTaskItem()
    }

    /** 本地模式无调度器，runTask 仅记录 last_run 时间并返回回显。 */
    suspend fun runTask(id: String): JsonElement = withContext(Dispatchers.IO) {
        db.taskDao().touchRun(id, nowIso())
        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", "本地模式仅记录执行时间，不实际调度任务")
            addProperty("task_id", id)
        }
    }

    private fun LocalTaskEntity.toTaskItem(): TaskItem = TaskItem(
        id = id,
        kind = kind,
        name = name,
        description = description,
        enabled = enabled,
        trigger = trigger,
        config = configJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        targetSessionId = targetSessionId,
        prompt = prompt,
        createdAt = createdAt,
        lastRun = lastRun,
        nextRun = nextRun
    )

    // ==================== 扩展功能：工作流 ====================

    suspend fun listWorkflows(): List<Workflow> = withContext(Dispatchers.IO) {
        db.workflowDao().listAll().map { it.toWorkflow() }
    }

    suspend fun createWorkflow(req: WorkflowRequest): Workflow = withContext(Dispatchers.IO) {
        val entity = LocalWorkflowEntity(
            id = UUID.randomUUID().toString(),
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            trigger = req.trigger,
            configJson = req.config?.let { gson.toJson(it) },
            createdAt = nowIso()
        )
        db.workflowDao().upsert(entity)
        entity.toWorkflow()
    }

    suspend fun updateWorkflow(id: String, req: WorkflowRequest): Workflow = withContext(Dispatchers.IO) {
        val existing = db.workflowDao().getById(id) ?: throw IllegalStateException("工作流不存在")
        val updated = existing.copy(
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            trigger = req.trigger,
            configJson = req.config?.let { gson.toJson(it) }
        )
        db.workflowDao().upsert(updated)
        updated.toWorkflow()
    }

    suspend fun deleteWorkflow(id: String) = withContext(Dispatchers.IO) {
        db.workflowDao().deleteById(id)
    }

    suspend fun toggleWorkflow(id: String): Workflow = withContext(Dispatchers.IO) {
        val existing = db.workflowDao().getById(id) ?: throw IllegalStateException("工作流不存在")
        val newEnabled = !existing.enabled
        db.workflowDao().setEnabled(id, newEnabled)
        existing.copy(enabled = newEnabled).toWorkflow()
    }

    /** 本地模式无工作流执行引擎，executeWorkflow 仅返回回显。 */
    suspend fun executeWorkflow(id: String): JsonElement = withContext(Dispatchers.IO) {
        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", "本地模式仅保存配置，不实际执行工作流")
            addProperty("workflow_id", id)
        }
    }

    /**
     * AI 生成工作流：使用当前激活的本地 AI 模型，根据用户描述生成 [WorkflowRequest]。
     * 返回的请求由调用方决定是否调用 [createWorkflow] 持久化。
     */
    suspend fun aiGenerateWorkflow(description: String): WorkflowRequest = withContext(Dispatchers.IO) {
        val activeModel = aiModelDao.getActive()
            ?: throw IllegalStateException("未配置激活的 AI 模型")
        val systemPrompt = buildWorkflowSystemPrompt()
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请根据以下描述生成工作流配置：\n\n${description.trim()}")
        )
        val result = aiClient.chatOnce(activeModel, messages)
        if (result.error != null) throw IllegalStateException(result.error)
        // 记账
        val usage = result.usage
        if (usage.isNotEmpty()) {
            val input = usage["prompt_tokens"] ?: usage["input_tokens"] ?: 0
            val output = usage["completion_tokens"] ?: usage["output_tokens"] ?: 0
            appendTokenUsageRecord(
                sessionId = currentSessionId,
                model = activeModel.model,
                inputTokens = input,
                outputTokens = output,
                timestamp = nowIsoTimestamp(),
                source = "web",
                purpose = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
            )
        }
        val content = stripMarkdownCodeFence(result.content)
        val obj = JsonParser.parseString(content).asJsonObject
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
            ?: throw IllegalStateException("AI 未返回工作流名称")
        val workflowDesc = obj.get("description")?.takeIf { !it.isJsonNull }?.asString
        val trigger = obj.get("trigger")?.takeIf { !it.isJsonNull }?.asString?.lowercase()
            ?: "manual"
        val enabled = obj.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
        val config: JsonElement? = when (trigger) {
            "cron" -> {
                val cronExpr = obj.get("cron")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("config")?.takeIf { it.isJsonObject }
                        ?.asJsonObject?.get("cron")?.takeIf { !it.isJsonNull }?.asString
                if (cronExpr.isNullOrBlank()) null
                else JsonObject().apply { addProperty("cron", cronExpr) }
            }
            else -> null
        }
        WorkflowRequest(
            name = name,
            description = workflowDesc,
            enabled = enabled,
            trigger = if (trigger in listOf("manual", "cron")) trigger else "manual",
            config = config
        )
    }

    /** 工作流 AI 生成的 system prompt，约束 AI 仅返回严格 JSON */
    private fun buildWorkflowSystemPrompt(): String = """你是一个工作流生成助手。用户会描述想要的工作流，你需要生成一个工作流配置 JSON。

你必须严格按照以下JSON格式返回（不要包含任何额外的文字说明，只返回JSON）：

{
    "name": "工作流名称（简短，10字以内）",
    "description": "工作流的详细描述（30字以内）",
    "trigger": "触发方式，仅限 manual 或 cron",
    "enabled": true,
    "cron": "仅当 trigger 为 cron 时填写 cron 表达式，例如 0 8 * * *"
}

规则：
1. 仅返回 JSON，不要添加 markdown 代码块或说明文字。
2. trigger 必须是 manual 或 cron 之一。
3. 如果用户描述中提到定时（每天、每小时等），使用 cron；否则使用 manual。
4. cron 表达式使用 5 字段标准格式（分 时 日 月 周）。
5. name 必须简短且能体现工作流用途。
6. description 应说明工作流的目的和预期效果。
""".trimIndent()

    private fun LocalWorkflowEntity.toWorkflow(): Workflow = Workflow(
        id = id,
        name = name,
        description = description,
        enabled = enabled,
        trigger = trigger,
        config = configJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        createdAt = createdAt
    )

    // ==================== 扩展功能：Skills ====================

    suspend fun listSkills(): List<Skill> = withContext(Dispatchers.IO) {
        db.skillDao().listAll().map { entity ->
            if (localSkillStorage != null && !localSkillStorage.exists(entity.name)) {
                localSkillStorage.save(entity.name, skillMd = null, referenceMd = null)
            }
            entity.toSkill()
        }
    }

    suspend fun readSkillFile(skillName: String, relativePath: String): String = withContext(Dispatchers.IO) {
        localSkillStorage?.readText(skillName, relativePath)
            ?: throw IllegalStateException("本地 Skill 存储不可用")
    }

    suspend fun createSkill(req: SkillRequest): Skill = withContext(Dispatchers.IO) {
        validateSkillNameValue(req.name)
        ensureUniqueSkillName(req.name)
        require(localSkillStorage?.exists(req.name) != true) { "Skill「${req.name.trim()}」的存储目录已存在" }
        val entity = LocalSkillEntity(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            description = req.description,
            aliasesJson = gson.toJson(req.aliases),
            enabled = req.enabled,
            parametersJson = req.parameters?.let { gson.toJson(it) },
            createdAt = nowIso()
        )
        localSkillStorage?.save(
            name = entity.name,
            skillMd = req.skillMd,
            referenceMd = req.referenceMd
        )
        runCatching { db.skillDao().upsert(entity) }.getOrElse {
            localSkillStorage?.delete(entity.name)
            throw it
        }
        entity.toSkill()
    }

    suspend fun updateSkill(id: String, req: SkillRequest): Skill = withContext(Dispatchers.IO) {
        val existing = db.skillDao().getById(id) ?: throw IllegalStateException("Skill 不存在")
        validateSkillNameValue(req.name)
        ensureUniqueSkillName(req.name, excludingId = id)
        val newName = req.name.trim()
        val oldSkillMd = localSkillStorage?.skillMd(existing.name)
        val oldReference = localSkillStorage?.referenceMd(existing.name)
        val sourceUrl = localSkillStorage?.sourceUrl(existing.name)
        if (existing.name != newName) {
            localSkillStorage?.rename(existing.name, newName)
        }
        localSkillStorage?.save(
            name = newName,
            skillMd = req.skillMd ?: oldSkillMd,
            referenceMd = req.referenceMd ?: oldReference,
            sourceUrl = sourceUrl
        )
        val updated = existing.copy(
            name = newName,
            description = req.description,
            aliasesJson = gson.toJson(req.aliases),
            enabled = req.enabled,
            parametersJson = req.parameters?.let { gson.toJson(it) }
        )
        db.skillDao().upsert(updated)
        updated.toSkill()
    }

    suspend fun deleteSkill(id: String) = withContext(Dispatchers.IO) {
        val existing = db.skillDao().getById(id)
        db.skillDao().deleteById(id)
        existing?.let { localSkillStorage?.delete(it.name) }
    }

    suspend fun toggleSkill(id: String): Skill = withContext(Dispatchers.IO) {
        val existing = db.skillDao().getById(id) ?: throw IllegalStateException("Skill 不存在")
        val newEnabled = !existing.enabled
        db.skillDao().setEnabled(id, newEnabled)
        existing.copy(enabled = newEnabled).toSkill()
    }

    suspend fun installSkillFromUrl(req: SkillInstallRequest): List<Skill> = withContext(Dispatchers.IO) {
        val packages = skillPackageDownloader.download(req.url)
        require(packages.isNotEmpty()) { "没有发现可安装的 Skill" }
        val duplicatePackageNames = packages
            .groupBy { skillDirectoryName(it.name).lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys
        require(duplicatePackageNames.isEmpty()) {
            "仓库中包含重名 Skill: ${duplicatePackageNames.joinToString()}"
        }
        packages.forEach { validateSkillNameValue(it.name) }

        val existing = db.skillDao().listAll()
        if (!req.overwrite) {
            val conflicts = packages.filter { pkg ->
                existing.any { it.name.equals(pkg.name, true) } ||
                    localSkillStorage?.exists(pkg.name) == true
            }
            require(conflicts.isEmpty()) {
                "以下 Skill 已存在：${conflicts.joinToString { it.name }}。如需替换，请开启覆盖同名。"
            }
        }

        packages.map { pkg ->
            val old = existing.firstOrNull { it.name.equals(pkg.name, true) }
            val entity = LocalSkillEntity(
                id = old?.id ?: UUID.randomUUID().toString(),
                name = pkg.name.trim(),
                description = pkg.description,
                aliasesJson = gson.toJson(pkg.aliases),
                enabled = req.enabled,
                parametersJson = old?.parametersJson,
                createdAt = old?.createdAt ?: nowIso()
            )
            if (req.overwrite && old != null && old.name != pkg.name) {
                localSkillStorage?.delete(old.name)
            }
            localSkillStorage
                ?.install(pkg, overwrite = req.overwrite)
                ?: throw IllegalStateException("本地 Skill 存储不可用")
            db.skillDao().upsert(entity)
            entity.toSkill()
        }
    }

    private fun LocalSkillEntity.toSkill(): Skill = Skill(
        id = id,
        name = name,
        description = description,
        aliases = runCatching { JsonParser.parseString(aliasesJson).asJsonArray.map { it.asString } }.getOrDefault(emptyList()),
        enabled = enabled,
        parameters = parametersJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        skillMd = localSkillStorage?.skillMd(name),
        referenceMd = localSkillStorage?.referenceMd(name),
        sourceUrl = localSkillStorage?.sourceUrl(name),
        hasStorage = localSkillStorage?.exists(name) == true,
        files = localSkillStorage?.listFiles(name).orEmpty(),
        createdAt = createdAt
    )

    private suspend fun ensureUniqueSkillName(name: String, excludingId: String? = null) {
        val conflict = db.skillDao().listAll().firstOrNull {
            it.id != excludingId && it.name.equals(name.trim(), ignoreCase = true)
        }
        require(conflict == null) { "Skill「${name.trim()}」已存在" }
    }

    private suspend fun buildEnabledSkillsPrompt(): String {
        val skills = db.skillDao().listAll().filter { it.enabled }
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("## 可用技能 (Skills)")
            appendLine()
            appendLine("当用户任务与某个技能匹配时，先调用 skill_read 读取该技能的 SKILL.md，再严格按其中说明执行。")
            appendLine("可使用 skill_list、skill_view、skill_read 查看技能目录和参考资源。不要直接执行下载 Skill 中的脚本，除非用户明确要求且命令执行已通过授权。")
            skills.forEach { skill ->
                appendLine()
                appendLine("### ${skill.name}")
                appendLine("- 描述: ${skill.description.orEmpty().ifBlank { "未填写" }}")
                val aliases = runCatching {
                    JsonParser.parseString(skill.aliasesJson).asJsonArray.joinToString { it.asString }
                }.getOrDefault("")
                if (aliases.isNotBlank()) appendLine("- 别名: $aliases")
            }
        }.trim()
    }

    private fun executeLocalSkillTool(toolName: String, args: Map<String, Any>): Map<String, Any> =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val storage = localSkillStorage
                ?: return@runBlocking mapOf("success" to false, "error" to "本地 Skill 存储不可用")
            val enabled = db.skillDao().listAll().filter { it.enabled }
            fun findSkill(): LocalSkillEntity? {
                val requested = args["skill_name"]?.toString().orEmpty()
                return enabled.firstOrNull {
                    it.name.equals(requested, true) ||
                        runCatching {
                            JsonParser.parseString(it.aliasesJson).asJsonArray.any { alias ->
                                alias.asString.equals(requested, true)
                            }
                        }.getOrDefault(false)
                }
            }

            try {
                when (toolName) {
                    "skill_get_info" -> mapOf(
                        "success" to true,
                        "skills_root" to "应用私有目录/skills",
                        "skills_count" to enabled.size,
                        "structure" to listOf("SKILL.md", "reference.md", "LICENSE.txt", "scripts/", "resources/"),
                        "note" to "Skill 脚本不会被自动执行"
                    )
                    "skill_list" -> mapOf(
                        "success" to true,
                        "skills" to enabled.map { skill ->
                            mapOf(
                                "name" to skill.name,
                                "description" to skill.description.orEmpty(),
                                "aliases" to runCatching {
                                    JsonParser.parseString(skill.aliasesJson).asJsonArray.map { it.asString }
                                }.getOrDefault(emptyList()),
                                "files_count" to storage.listFiles(skill.name).size
                            )
                        }
                    )
                    "skill_view" -> {
                        val skill = findSkill()
                            ?: return@runBlocking mapOf("success" to false, "error" to "Skill 不存在或未启用")
                        mapOf(
                            "success" to true,
                            "skill_name" to skill.name,
                            "files" to storage.listFiles(skill.name),
                            "source_url" to storage.sourceUrl(skill.name).orEmpty()
                        )
                    }
                    "skill_read" -> {
                        val skill = findSkill()
                            ?: return@runBlocking mapOf("success" to false, "error" to "Skill 不存在或未启用")
                        val path = args["file_path"]?.toString().orEmpty().ifBlank { "SKILL.md" }
                        val content = storage.readText(skill.name, path)
                        val startLine = (args["start_line"] as? Number)?.toInt()
                        val endLine = (args["end_line"] as? Number)?.toInt()
                        val selected = if (startLine != null || endLine != null) {
                            val lines = content.lines()
                            val from = ((startLine ?: 1) - 1).coerceIn(0, lines.size)
                            val to = (endLine ?: lines.size).coerceIn(from, lines.size)
                            lines.subList(from, to).joinToString("\n")
                        } else {
                            content.take(12_000)
                        }
                        mapOf(
                            "success" to true,
                            "skill_name" to skill.name,
                            "file_path" to path,
                            "content" to selected,
                            "total_length" to content.length,
                            "truncated" to (selected.length < content.length)
                        )
                    }
                    else -> mapOf("success" to false, "error" to "未知 Skill 工具: $toolName")
                }
            } catch (e: Exception) {
                mapOf("success" to false, "error" to (e.message ?: "Skill 工具执行失败"))
            }
        }

    // ==================== 扩展功能：Tools ====================

    /**
     * 确保内置工具已写入数据库（幂等）。
     * 在 LocalRepository 初始化或首次 listTools 时调用。
     */
    suspend fun ensureBuiltinTools() = withContext(Dispatchers.IO) {
        val existing = db.toolDao().listAll().map { it.id }.toSet()
        val toInsert = com.nekobot.app.data.local.db.BuiltinTools.all.filter { it.id !in existing }
        for (spec in toInsert) {
            db.toolDao().upsert(
                com.nekobot.app.data.local.db.LocalToolEntity(
                    id = spec.id,
                    name = spec.name,
                    description = spec.description,
                    enabled = spec.enabled,
                    parametersJson = spec.parametersJson,
                    implementationJson = spec.implementationJson,
                    builtin = true,
                    createdAt = nowIso()
                )
            )
        }
    }

    suspend fun listTools(): List<Tool> = withContext(Dispatchers.IO) {
        ensureBuiltinTools()
        // 内置工具按预设顺序置顶，用户自定义工具按创建时间倒序
        val builtinOrder = com.nekobot.app.data.local.db.BuiltinTools.all.mapIndexed { i, s -> s.id to i }.toMap()
        db.toolDao().listAll()
            .sortedWith(compareBy({ builtinOrder[it.id] ?: Int.MAX_VALUE }, { it.createdAt }))
            .map { it.toTool() }
    }

    suspend fun createTool(req: ToolRequest): Tool = withContext(Dispatchers.IO) {
        val entity = LocalToolEntity(
            id = UUID.randomUUID().toString(),
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            parametersJson = req.parameters?.let { gson.toJson(it) },
            implementationJson = req.implementation?.let { gson.toJson(it) },
            builtin = false,
            createdAt = nowIso()
        )
        db.toolDao().upsert(entity)
        entity.toTool()
    }

    suspend fun updateTool(id: String, req: ToolRequest): Tool = withContext(Dispatchers.IO) {
        val existing = db.toolDao().getById(id) ?: throw IllegalStateException("Tool 不存在")
        val updated = existing.copy(
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            parametersJson = req.parameters?.let { gson.toJson(it) },
            implementationJson = req.implementation?.let { gson.toJson(it) }
        )
        db.toolDao().upsert(updated)
        updated.toTool()
    }

    suspend fun deleteTool(id: String) = withContext(Dispatchers.IO) {
        val existing = db.toolDao().getById(id)
        // 内置工具不允许删除
        if (existing?.builtin == true) {
            throw IllegalStateException("内置工具不可删除")
        }
        db.toolDao().deleteById(id)
    }

    suspend fun toggleTool(id: String): Tool = withContext(Dispatchers.IO) {
        val existing = db.toolDao().getById(id) ?: throw IllegalStateException("Tool 不存在")
        if (existing.builtin) throw IllegalStateException("内置工具不可切换")
        val newEnabled = !existing.enabled
        db.toolDao().setEnabled(id, newEnabled)
        existing.copy(enabled = newEnabled).toTool()
    }

    private fun LocalToolEntity.toTool(): Tool = Tool(
        id = id,
        name = name,
        description = description,
        enabled = enabled,
        parameters = parametersJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        implementation = implementationJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        builtin = builtin,
        createdAt = createdAt
    )

    // ==================== 扩展功能：MCP 服务 ====================

    suspend fun listMcpServers(): List<McpServer> = withContext(Dispatchers.IO) {
        autoConnectMcpServers()
        db.mcpServerDao().listAll().map { server ->
            val connected = localMcpRuntime.isConnected(server.id)
            server.toMcpServer(
                connectedOverride = connected,
                toolCountOverride = if (connected) localMcpRuntime.toolCount(server.id) else 0
            )
        }
    }

    suspend fun createMcpServer(req: McpServerRequest): McpServer = withContext(Dispatchers.IO) {
        validateMcpRequest(req)
        val entity = LocalMcpServerEntity(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            transport = req.transport,
            description = req.description,
            enabled = req.enabled,
            autoConnect = req.autoConnect,
            url = req.url?.trim(),
            command = req.command?.trim(),
            argsJson = gson.toJson(req.args),
            envJson = req.env?.let { gson.toJson(it) },
            builtin = false,
            createdAt = nowIso()
        )
        db.mcpServerDao().upsert(entity)
        entity.toMcpServer()
    }

    suspend fun updateMcpServer(id: String, req: McpServerRequest): McpServer = withContext(Dispatchers.IO) {
        val existing = db.mcpServerDao().getById(id) ?: throw IllegalStateException("MCP 服务不存在")
        validateMcpRequest(req)
        localMcpRuntime.disconnect(id)
        val updated = existing.copy(
            name = req.name.trim(),
            transport = req.transport,
            description = req.description,
            enabled = req.enabled,
            autoConnect = req.autoConnect,
            connected = false,
            toolCount = 0,
            url = req.url?.trim(),
            command = req.command?.trim(),
            argsJson = gson.toJson(req.args),
            envJson = req.env?.let { gson.toJson(it) },
            lastConnectedAt = null
        )
        db.mcpServerDao().upsert(updated)
        updated.toMcpServer()
    }

    suspend fun deleteMcpServer(id: String) = withContext(Dispatchers.IO) {
        val existing = db.mcpServerDao().getById(id)
            ?: throw IllegalStateException("MCP 服务不存在")
        if (existing.builtin) throw IllegalStateException("内置 MCP 服务不可删除")
        localMcpRuntime.disconnect(id)
        db.mcpServerDao().deleteById(id)
    }

    suspend fun connectMcpServer(id: String): JsonElement = withContext(Dispatchers.IO) {
        val server = db.mcpServerDao().getById(id)
            ?: throw IllegalStateException("MCP 服务不存在")
        val tools = try {
            localMcpRuntime.connect(server)
        } catch (error: Throwable) {
            db.mcpServerDao().setRuntimeState(id, false, 0, null)
            throw IllegalStateException(error.message ?: "MCP 连接失败", error)
        }
        db.mcpServerDao().setRuntimeState(id, true, tools.size, nowIso())
        JsonObject().apply {
            addProperty("success", true)
            addProperty("connected", true)
            addProperty("tool_count", tools.size)
        }
    }

    suspend fun disconnectMcpServer(id: String): JsonElement = withContext(Dispatchers.IO) {
        db.mcpServerDao().getById(id) ?: throw IllegalStateException("MCP 服务不存在")
        localMcpRuntime.disconnect(id)
        db.mcpServerDao().setRuntimeState(id, false, 0, null)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("connected", false)
        }
    }

    suspend fun mcpServerTools(id: String): JsonElement = withContext(Dispatchers.IO) {
        db.mcpServerDao().getById(id) ?: throw IllegalStateException("MCP 服务不存在")
        val tools = localMcpRuntime.getServerTools(id)
        JsonObject().apply {
            add("tools", JsonArray().also { array ->
                tools.forEach { array.add(it.toJson()) }
            })
            addProperty("count", tools.size)
        }
    }

    suspend fun testMcpServer(id: String): JsonElement = withContext(Dispatchers.IO) {
        val server = db.mcpServerDao().getById(id)
            ?: throw IllegalStateException("MCP 服务不存在")
        val tools = localMcpRuntime.test(server)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("tool_count", tools.size)
            add("tools", JsonArray().also { array ->
                tools.forEach { array.add(it.name) }
            })
        }
    }

    /**
     * 自动连接与原仓库启动行为一致；Android 在首次读取 MCP 或首次 Agent 对话时延迟执行，
     * 避免仓库构造阶段阻塞应用启动。
     */
    private suspend fun autoConnectMcpServers() {
        db.mcpServerDao().listAll()
            .filter { it.enabled && it.autoConnect && !localMcpRuntime.isConnected(it.id) }
            .forEach { server ->
                runCatching {
                    val tools = localMcpRuntime.connect(server)
                    db.mcpServerDao().setRuntimeState(server.id, true, tools.size, nowIso())
                }.onFailure { error ->
                    db.mcpServerDao().setRuntimeState(server.id, false, 0, null)
                    LocalLogger.w(TAG, "MCP 自动连接失败: ${server.name}", error)
                }
            }
    }

    /** 与原仓库一致：本地 Agent 的内置工具之外，注入所有真实已连接的 MCP 工具。 */
    private suspend fun prepareMcpAgentTools(): List<Map<String, Any>> {
        autoConnectMcpServers()
        val connectedIds = db.mcpServerDao().listAll()
            .asSequence()
            .filter { localMcpRuntime.isConnected(it.id) }
            .map { it.id }
            .toSet()
        return localMcpRuntime.getOpenAiToolDefinitions(connectedIds)
    }

    private fun validateMcpRequest(req: McpServerRequest) {
        require(req.name.isNotBlank()) { "MCP 服务名称不能为空" }
        when (req.transport.lowercase()) {
            "stdio" -> require(!req.command.isNullOrBlank()) { "stdio 模式需要 command 参数" }
            "streamable-http", "http" ->
                require(!req.url.isNullOrBlank()) { "HTTP 模式需要 url 参数" }
            else -> throw IllegalArgumentException("不支持的 MCP transport: ${req.transport}")
        }
    }

    private fun LocalMcpServerEntity.toMcpServer(
        connectedOverride: Boolean? = null,
        toolCountOverride: Int? = null
    ): McpServer = McpServer(
        id = id,
        name = name,
        transport = transport,
        description = description,
        enabled = enabled,
        autoConnect = autoConnect,
        connected = connectedOverride ?: connected,
        toolCount = toolCountOverride ?: toolCount,
        url = url,
        command = command,
        args = runCatching { JsonParser.parseString(argsJson ?: "[]").asJsonArray.map { it.asString } }.getOrDefault(emptyList()),
        env = envJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        createdAt = createdAt,
        lastConnectedAt = lastConnectedAt,
        builtin = builtin
    )

    // ==================== 扩展功能：API Keys ====================

    suspend fun listApiKeys(): List<ApiKey> = withContext(Dispatchers.IO) {
        db.apiKeyDao().listAll().map { it.toApiKey(maskKey = true) }
    }

    suspend fun getApiKey(id: String): ApiKey? = withContext(Dispatchers.IO) {
        db.apiKeyDao().getById(id)?.toApiKey(maskKey = false)
    }

    suspend fun createApiKey(req: ApiKeyRequest): ApiKey = withContext(Dispatchers.IO) {
        val now = nowIso()
        val entity = LocalApiKeyEntity(
            id = UUID.randomUUID().toString(),
            name = req.name,
            key = req.key,
            createdAt = now,
            updatedAt = now
        )
        db.apiKeyDao().upsert(entity)
        entity.toApiKey(maskKey = false)
    }

    suspend fun updateApiKey(id: String, req: ApiKeyRequest): ApiKey = withContext(Dispatchers.IO) {
        val existing = db.apiKeyDao().getById(id) ?: throw IllegalStateException("API Key 不存在")
        val updated = existing.copy(name = req.name, key = req.key, updatedAt = nowIso())
        db.apiKeyDao().upsert(updated)
        updated.toApiKey(maskKey = false)
    }

    suspend fun deleteApiKey(id: String) = withContext(Dispatchers.IO) {
        db.apiKeyDao().deleteById(id)
    }

    private fun LocalApiKeyEntity.toApiKey(maskKey: Boolean): ApiKey = ApiKey(
        id = id,
        name = name,
        key = if (maskKey) maskKeyValue(key) else key,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun maskKeyValue(key: String): String =
        if (key.length <= 8) "****" else key.take(4) + "****" + key.takeLast(4)

    // ==================== 扩展功能：AI 配置中心（基于激活模型生成 AiConfig）====================

    /**
     * 本地模式无独立 AiConfig 表，配置由激活的 LocalAiModelEntity 推导：
     * - model / temperature / max_tokens / top_p 来自激活模型
     * - system_prompt 留空（由角色卡 + 自定义提示词运行时合成）
     * - purpose 固定为 "chat"
     */
    suspend fun getAiConfig(): JsonElement = withContext(Dispatchers.IO) {
        val active = aiModelDao.getActive()
        JsonObject().apply {
            addProperty("model", active?.model ?: "")
            addProperty("temperature", active?.temperature ?: 0.7)
            addProperty("max_tokens", active?.maxTokens ?: 2048)
            // 本地模型未单独存储上下文窗口长度，使用默认值 100k（与后端默认一致）
            addProperty("max_context_length", 100000)
            addProperty("top_p", active?.topP ?: 1.0)
            addProperty("frequency_penalty", 0.0)
            addProperty("presence_penalty", 0.0)
            addProperty("system_prompt", "")
            addProperty("purpose", "chat")
        }
    }

    /** 本地模式更新 AiConfig 等价于更新激活模型的生成参数。 */
    suspend fun updateAiConfig(config: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val active = aiModelDao.getActive()
            ?: throw IllegalStateException("未配置激活的 AI 模型")
        val obj = config.takeIf { it.isJsonObject }?.asJsonObject
        val updated = active.copy(
            model = obj?.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: active.model,
            temperature = obj?.get("temperature")?.takeIf { it.isJsonPrimitive }?.asFloat ?: active.temperature,
            maxTokens = obj?.get("max_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: active.maxTokens,
            topP = obj?.get("top_p")?.takeIf { it.isJsonPrimitive }?.asFloat ?: active.topP
        )
        aiModelDao.upsert(updated)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", "已同步到当前激活的本地 AI 模型")
        }
    }

    /** 测试 AI 配置：用激活模型发送一条 "你好"。 */
    suspend fun testAiConfig(): JsonElement = withContext(Dispatchers.IO) {
        val active = aiModelDao.getActive()
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "未配置激活的 AI 模型")
            }
        val result = aiClient.testModel(active)
        JsonObject().apply {
            addProperty("success", result.error == null)
            addProperty("message", result.error ?: result.content)
        }
    }

    /** 用途列表（与远程保持一致）。 */
    fun allPurposes(): JsonElement = JsonParser.parseString(
        """["chat","vision","video","tts","stt","embedding","image_generation"]"""
    )

    // ==================== 扩展功能：故障转移队列 ====================

    /**
     * 取该 purpose 下所有启用模型按 (priority, createdAt) 升序组成队列，
     * 并附带从 [failoverHealthStore] 读取的真实健康状态（连续失败次数、冷却剩余时间等）。
     */
    suspend fun getFailoverQueue(purpose: String): JsonElement = withContext(Dispatchers.IO) {
        val models = queueFor(purpose)
        val now = System.currentTimeMillis()
        val arr = JsonArray()
        models.forEach { m ->
            val h = failoverHealthStore.get(m.id)
            val cooldownRemaining = if (h != null && h.cooldownUntilMs > now) {
                (h.cooldownUntilMs - now) / 1000.0
            } else 0.0
            val available = cooldownRemaining <= 0.0
            val usage = runCatching { failoverUsageReader.getUsage(m.id) }
                .getOrDefault(com.nekobot.app.data.local.ai.FailoverUsage())
            JsonObject().also { o ->
                o.addProperty("model_id", m.id)
                o.addProperty("id", m.id)
                o.addProperty("name", m.name)
                o.addProperty("model", m.model)
                o.addProperty("provider", m.provider ?: m.protocol)
                o.addProperty("priority", m.priority)
                o.addProperty("active", m.active)
                o.addProperty("purpose", m.purpose)
                o.addProperty("enabled", m.enabled)
                o.addProperty("token_limit_daily", m.tokenLimitDaily)
                o.addProperty("token_limit_weekly", m.tokenLimitWeekly)
                o.addProperty("failover_timeout", m.failoverTimeout)
                o.addProperty("input_price", m.inputPrice)
                o.addProperty("output_price", m.outputPrice)
                o.add("health", JsonObject().also { ho ->
                    ho.addProperty("available", available)
                    ho.addProperty("daily_failures", h?.dailyFailures?.takeIf { h.dailyFailuresDate == todayDateString() } ?: 0)
                    ho.addProperty("consecutive_failures", h?.consecutiveFailures ?: 0)
                    ho.addProperty("last_failure_code", h?.lastFailureCode ?: 0)
                    ho.addProperty("cooldown_remaining", cooldownRemaining)
                    ho.addProperty("cooldown_until_ms", h?.cooldownUntilMs ?: 0)
                    ho.addProperty("last_failure_at_ms", h?.lastFailureAtMs ?: 0)
                })
                o.add("usage", JsonObject().also { uo ->
                    uo.addProperty("daily_tokens", usage.dailyTokens)
                    uo.addProperty("weekly_tokens", usage.weeklyTokens)
                    uo.addProperty("daily_limit", m.tokenLimitDaily)
                    uo.addProperty("weekly_limit", m.tokenLimitWeekly)
                })
            }.also { arr.add(it) }
        }
        JsonObject().apply {
            addProperty("purpose", purpose)
            add("queue", arr)
        }
    }

    /** 当日字符串（yyyy-MM-dd），用于判断 daily_failures 是否属于今天 */
    private fun todayDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * 重置模型健康状态：清除冷却记录与失败计数。
     * @param modelId 待重置的模型 ID；为 null 时清空所有模型的健康状态
     */
    suspend fun resetFailover(modelId: String?): JsonElement = withContext(Dispatchers.IO) {
        if (modelId.isNullOrBlank()) {
            failoverHealthStore.clear()
        } else {
            failoverHealthStore.delete(modelId)
        }
        JsonObject().apply {
            addProperty("success", true)
            addProperty("message", if (modelId.isNullOrBlank()) "已重置所有模型健康状态" else "已重置模型健康状态")
            modelId?.let { addProperty("model_id", it) }
        }
    }

    /**
     * 取单个模型的故障转移详情：基础信息 + 健康状态（来自 [failoverHealthStore]）+
     * token 用量（来自 [failoverUsageReader]）+ 策略与价格（来自 [LocalAiModelEntity]）。
     *
     * 供 [com.nekobot.app.data.repository.UnifiedRepository] 在本地模式下返回类型化详情。
     */
    suspend fun getFailoverDetail(modelId: String): com.nekobot.app.data.model.FailoverModelDetail? =
        withContext(Dispatchers.IO) {
            val model = aiModelDao.getById(modelId) ?: return@withContext null
            val now = System.currentTimeMillis()
            val health = failoverHealthStore.get(modelId)
            val cooldownRemaining = if (health != null && health.cooldownUntilMs > now) {
                (health.cooldownUntilMs - now) / 1000.0
            } else 0.0
            val usage = runCatching { failoverUsageReader.getUsage(modelId) }
                .getOrDefault(com.nekobot.app.data.local.ai.FailoverUsage())
            val today = todayDateString()
            com.nekobot.app.data.model.FailoverModelDetail(
                modelId = model.id,
                name = model.name,
                model = model.model,
                provider = model.provider ?: model.protocol,
                priority = model.priority,
                active = model.active,
                purpose = model.purpose,
                enabled = model.enabled,
                health = com.nekobot.app.data.model.FailoverHealth(
                    available = cooldownRemaining <= 0.0,
                    dailyFailures = health?.dailyFailures?.takeIf { health.dailyFailuresDate == today } ?: 0,
                    consecutiveFailures = health?.consecutiveFailures ?: 0,
                    lastFailureCode = health?.lastFailureCode ?: 0,
                    cooldownRemaining = cooldownRemaining,
                    cooldownUntilMs = health?.cooldownUntilMs ?: 0,
                    lastFailureAtMs = health?.lastFailureAtMs ?: 0
                ),
                usage = com.nekobot.app.data.model.FailoverTokenUsage(
                    dailyTokens = usage.dailyTokens,
                    weeklyTokens = usage.weeklyTokens,
                    dailyLimit = model.tokenLimitDaily,
                    weeklyLimit = model.tokenLimitWeekly
                ),
                tokenLimitDaily = model.tokenLimitDaily,
                tokenLimitWeekly = model.tokenLimitWeekly,
                failoverTimeout = model.failoverTimeout,
                inputPrice = model.inputPrice,
                outputPrice = model.outputPrice,
                maxTokens = model.maxTokens,
                temperature = model.temperature?.toDouble(),
                topP = model.topP?.toDouble()
            )
        }

    /**
     * 更新模型故障转移策略：token 限额 + 超时秒数。
     * 三个数值字段必须非负（由调用方 [com.nekobot.app.data.repository.UnifiedRepository] 校验）。
     */
    suspend fun updateFailoverPolicy(
        modelId: String,
        tokenLimitDaily: Long,
        tokenLimitWeekly: Long,
        failoverTimeout: Int
    ): JsonElement = withContext(Dispatchers.IO) {
        val model = aiModelDao.getById(modelId) ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "模型不存在: $modelId")
        }
        aiModelDao.upsert(
            model.copy(
                tokenLimitDaily = tokenLimitDaily,
                tokenLimitWeekly = tokenLimitWeekly,
                failoverTimeout = failoverTimeout
            )
        )
        JsonObject().apply {
            addProperty("success", true)
            addProperty("model_id", modelId)
            addProperty("token_limit_daily", tokenLimitDaily)
            addProperty("token_limit_weekly", tokenLimitWeekly)
            addProperty("failover_timeout", failoverTimeout)
        }
    }

    /**
     * 重排故障转移队列：按传入的 id 顺序重写 priority。
     * - 使用 [db.withTransaction] 保证原子性
     * - 校验每个 id 都属于 [purpose]，否则拒绝整次重排
     * - 重排后将 priority=0（P0）的模型设为该 purpose 的当前激活模型
     *
     * @param body JSON: {"purpose":"chat","model_ids":["id1","id2",...]}
     */
    suspend fun reorderFailover(body: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val obj = body.takeIf { it.isJsonObject }?.asJsonObject
        val purpose = obj?.get("purpose")?.asString ?: "chat"
        val ids: List<String> = obj?.get("model_ids")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()

        if (ids.isEmpty()) {
            return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "model_ids 不能为空")
            }
        }

        // 校验：所有 id 必须属于该 purpose 的当前启用模型
        val validIds = queueFor(purpose).map { it.id }.toSet()
        val invalid = ids.filter { it !in validIds }
        if (invalid.isNotEmpty()) {
            return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "以下模型不属于 purpose=$purpose 的启用队列: ${invalid.joinToString(",")}")
                add("invalid_ids", gson.toJsonTree(invalid))
            }
        }

        // 事务内重写 priority
        db.withTransaction {
            for ((index, id) in ids.withIndex()) {
                val m = aiModelDao.getById(id) ?: continue
                aiModelDao.upsert(m.copy(priority = index))
            }
            // P0 自动设为当前激活模型
            ids.firstOrNull()?.let { p0Id ->
                aiModelDao.setActiveForPurpose(p0Id, purpose)
            }
        }

        JsonObject().apply {
            addProperty("success", true)
            addProperty("purpose", purpose)
            addProperty("reordered_count", ids.size)
            addProperty("active_model_id", ids.firstOrNull() ?: "")
        }
    }

    // ==================== 扩展功能：绑定角色变更 ====================

    /** 本地模式绑定角色：更新 local_sessions.character_id 及关联角色信息。 */
    suspend fun bindCharacter(sessionId: String, req: BindCharacterRequest): JsonElement = withContext(Dispatchers.IO) {
        val session = sessionDao.getById(sessionId)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "会话不存在")
            }
        val character = req.characterId?.let { characterDao.getById(it) }
        val updated = session.copy(
            characterId = req.characterId,
            characterName = character?.name ?: req.characterId,
            characterAvatar = character?.avatar ?: session.characterAvatar,
            portrait = character?.portrait ?: session.portrait,
            senderName = req.senderName.ifBlank { session.senderName ?: "我" },
            senderAvatar = req.senderAvatar ?: session.senderAvatar,
            scenario = req.scenario ?: character?.scenario ?: session.scenario,
            systemPrompt = req.systemPrompt ?: character?.systemPrompt ?: session.systemPrompt,
            firstMessage = character?.firstMessage ?: session.firstMessage,
            updatedAt = nowIso()
        )
        sessionDao.update(updated)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("session_id", sessionId)
            addProperty("character_id", req.characterId ?: "")
        }
    }

    // ==================== 扩展功能：消息收藏 ====================

    suspend fun listMessageFavorites(sessionId: String): JsonElement = withContext(Dispatchers.IO) {
        val favs = db.messageFavoriteDao().listBySession(sessionId)
        val arr = JsonArray()
        favs.forEach { f ->
            val msgIds = runCatching { JsonParser.parseString(f.messageIdsJson).asJsonArray.map { it.asString } }
                .getOrDefault(emptyList())
            val msgs = msgIds.mapNotNull { id -> messageDao.listBySession(sessionId).find { it.id == id } }
            JsonObject().also { o ->
                o.addProperty("id", f.id)
                o.addProperty("title", f.title)
                o.addProperty("collection_id", f.id)
                o.addProperty("created_at", f.createdAt)
                o.add("message_ids", gson.toJsonTree(msgIds))
                val msgsArr = JsonArray()
                msgs.forEach { m ->
                    JsonObject().also { mo ->
                        mo.addProperty("id", m.id)
                        mo.addProperty("role", m.role)
                        mo.addProperty("content", m.content)
                        mo.addProperty("timestamp", m.timestamp)
                    }.also { msgsArr.add(it) }
                }
                o.add("messages", msgsArr)
            }.also { arr.add(it) }
        }
        JsonObject().apply { add("collections", arr) }
    }

    /**
     * 删除指定收藏夹。与 updateMessageFavorites 的"空 messageIds 删除"语义解耦，
     * 避免远程后端 PUT 端点不支持空 message_ids 的问题。
     */
    suspend fun deleteMessageFavorite(collectionId: String): JsonElement = withContext(Dispatchers.IO) {
        db.messageFavoriteDao().deleteById(collectionId)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("deleted", true)
            addProperty("collection_id", collectionId)
        }
    }

    /**
     * 更新消息收藏：根据 req.collectionId 决定更新已有收藏夹还是新建。
     * 若 messageIds 为空则删除该收藏夹。
     */
    suspend fun updateMessageFavorites(sessionId: String, req: MessageFavoriteRequest): JsonElement = withContext(Dispatchers.IO) {
        val now = nowIso()
        val collectionId = req.collectionId ?: UUID.randomUUID().toString()
        if (req.messageIds.isEmpty()) {
            db.messageFavoriteDao().deleteById(collectionId)
            return@withContext JsonObject().apply {
                addProperty("success", true)
                addProperty("deleted", true)
                addProperty("collection_id", collectionId)
            }
        }
        val entity = LocalMessageFavoriteEntity(
            id = collectionId,
            sessionId = sessionId,
            title = req.title?.ifBlank { "未命名收藏" } ?: "未命名收藏",
            messageIdsJson = gson.toJson(req.messageIds),
            createdAt = now
        )
        db.messageFavoriteDao().upsert(entity)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("collection_id", collectionId)
            addProperty("title", entity.title)
            addProperty("message_count", req.messageIds.size)
        }
    }

    // ==================== 扩展功能：工作区文件 ====================

    /** 工作区根目录：filesDir/workspace/<sessionId>/。 */
    private fun workspaceDir(sessionId: String): java.io.File? {
        val ctx = appContext ?: return null
        return LocalWorkspaceStorage.resolve(ctx.filesDir, sessionId)
    }

    suspend fun listWorkspaceFiles(sessionId: String, path: String?): JsonElement = withContext(Dispatchers.IO) {
        val dir = workspaceDir(sessionId) ?: return@withContext JsonArray()
        val target = if (path.isNullOrBlank()) dir else java.io.File(dir, path)
        val arr = JsonArray()
        if (!target.exists()) return@withContext arr
        target.listFiles()?.forEach { f ->
            JsonObject().also { o ->
                o.addProperty("name", f.name)
                o.addProperty("type", if (f.isDirectory) "directory" else "file")
                o.addProperty("size", f.length())
                o.addProperty("path", f.relativeTo(dir).path)
                o.addProperty("mime_type", guessMime(f.name))
            }.also { arr.add(it) }
        }
        arr
    }

    suspend fun uploadWorkspaceFile(sessionId: String, bytes: ByteArray, fileName: String): JsonElement = withContext(Dispatchers.IO) {
        val dir = workspaceDir(sessionId) ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "工作区目录不可用")
        }
        val safeName = fileName.substringAfterLast('/').ifBlank { UUID.randomUUID().toString() }
        val file = java.io.File(dir, safeName)
        file.writeBytes(bytes)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("name", safeName)
            addProperty("size", bytes.size)
            addProperty("path", safeName)
            addProperty("mime_type", guessMime(safeName))
        }
    }

    suspend fun deleteWorkspaceFile(sessionId: String, filename: String): JsonElement = withContext(Dispatchers.IO) {
        val dir = workspaceDir(sessionId) ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "工作区目录不可用")
        }
        val file = java.io.File(dir, filename)
        val ok = if (file.exists()) file.deleteRecursively() else false
        JsonObject().apply {
            addProperty("success", ok)
            addProperty("filename", filename)
        }
    }

    suspend fun downloadWorkspaceFile(sessionId: String, filename: String): java.io.File? = withContext(Dispatchers.IO) {
        val dir = workspaceDir(sessionId) ?: return@withContext null
        val file = java.io.File(dir, filename)
        if (file.exists() && file.isFile) file else null
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

// ============================================================================
// 顶层类型：故障转移队列的多媒体结果 + 视觉失败标记 + 适配器实现
// ============================================================================

/** 视觉识别失败的非阻塞标记前缀。出现此前缀的描述表示图片解析失败，后续 LLM 不得猜测图片内容。 */
const val VISION_FAILURE_MARKER: String = "[视觉识别失败] "

/** 本地 TTS 合成结果：缓存 URI + MIME + 实际使用的模型信息。 */
data class LocalAudioResult(
    val cacheUri: String,
    val mimeType: String,
    val usedModelId: String,
    val usedModelName: String
)

/** 本地图片生成结果：缓存 URI + MIME + 实际使用的模型信息。 */
data class LocalImageResult(
    val cacheUri: String,
    val mimeType: String,
    val usedModelId: String,
    val usedModelName: String
)

/**
 * Room DAO 适配器：将 [com.nekobot.app.data.local.db.FailoverHealthDao] 暴露为
 * [com.nekobot.app.data.local.ai.FailoverHealthStore] 供 [FailoverCoordinator] 使用。
 */
private class RoomFailoverHealthStore(
    private val dao: com.nekobot.app.data.local.db.FailoverHealthDao
) : FailoverHealthStore {
    override suspend fun get(modelId: String): LocalFailoverHealthEntity? = dao.get(modelId)
    override suspend fun upsert(entity: LocalFailoverHealthEntity) = dao.upsert(entity)
    override suspend fun listAll(): List<LocalFailoverHealthEntity> = dao.listAll()
    override suspend fun delete(modelId: String) = dao.delete(modelId)
    override suspend fun clear() = dao.clear()
}

/**
 * SharedPreferences 适配器：从 token_usage_<dbName>.xml 中聚合每个模型的日/周 token 用量，
 * 供 [FailoverCoordinator] 在执行前检查 [LocalAiModelEntity.tokenLimitDaily] /
 * [LocalAiModelEntity.tokenLimitWeekly] 限额。
 *
 * 记录字段约定见 [LocalRepository.appendTokenUsageRecord]：每条记录含 model + total_tokens + date。
 * 由于 records 中存储的是 model 名（如 "gpt-4o"），而协调器传入的是 model.id (UUID)，
 * 需通过 [resolveModelName] 回调把 id 解析为对应的 model 名后再做匹配。
 */
private class PrefsFailoverUsageReader(
    private val prefs: android.content.SharedPreferences?,
    private val resolveModelName: suspend (String) -> String?
) : FailoverUsageReader {
    override suspend fun getUsage(modelId: String): FailoverUsage {
        val p = prefs ?: return FailoverUsage()
        // 先把 modelId (UUID) 解析为对应的 model 名，再按名匹配历史 records
        val modelName = resolveModelName(modelId) ?: return FailoverUsage()
        val raw = p.getString("records", "[]") ?: "[]"
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val weekStart = weekStartDateString()
        var daily = 0L
        var weekly = 0L
        try {
            val arr = JsonParser.parseString(raw).asJsonArray
            for (item in arr) {
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val model = obj.get("model")?.asString ?: continue
                if (model != modelName) continue
                val total = obj.get("total_tokens")?.asLong ?: continue
                val date = obj.get("date")?.asString ?: continue
                if (date == today) daily += total
                if (date >= weekStart) weekly += total
            }
        } catch (_: Exception) { }
        return FailoverUsage(dailyTokens = daily, weeklyTokens = weekly)
    }

    /** 返回本周一日期字符串（yyyy-MM-dd），用于聚合周用量 */
    private fun weekStartDateString(): String {
        val cal = java.util.Calendar.getInstance(Locale.US)
        cal.firstDayOfWeek = java.util.Calendar.MONDAY
        cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
}
