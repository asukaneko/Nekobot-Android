package com.nekobot.app.data.local

import android.os.StatFs
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.ai.FailoverAllFailedException
import com.nekobot.app.data.local.ai.AgentRunStage
import com.nekobot.app.data.local.ai.AgentRunStatus
import com.nekobot.app.data.local.ai.FailoverCoordinator
import com.nekobot.app.data.local.ai.FailoverExecution
import com.nekobot.app.data.local.ai.FailoverHealthStore
import com.nekobot.app.data.local.ai.FailoverHttpException
import com.nekobot.app.data.local.ai.FailoverUsage
import com.nekobot.app.data.local.ai.FailoverUsageReader
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.ai.LocalAiResult
import com.nekobot.app.data.local.ai.LocalBrowserTool
import com.nekobot.app.data.local.ai.LocalChatFailoverExecutor
import com.nekobot.app.data.local.ai.LocalContextTokenMessage
import com.nekobot.app.data.local.ai.LocalGenerationController
import com.nekobot.app.data.local.ai.LocalSandboxCommandResult
import com.nekobot.app.data.local.ai.LocalLinuxSandboxCoordinator
import com.nekobot.app.data.local.ai.LocalMcpRuntime
import com.nekobot.app.data.local.ai.LocalPersistedTokenMessage
import com.nekobot.app.data.local.ai.LocalPromptBuilder
import com.nekobot.app.data.local.ai.LocalProfileRepository
import com.nekobot.app.data.local.ai.LocalRelationshipRepository
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.ai.RelationshipState
import com.nekobot.app.data.local.ai.SmartModelMetric
import com.nekobot.app.data.local.ai.SmartModelRouter
import com.nekobot.app.data.local.ai.SmartRoutingBudgetNotifier
import com.nekobot.app.data.local.ai.SmartRoutingRequest
import com.nekobot.app.data.local.ai.TokenStatsManager
import com.nekobot.app.data.local.ai.currentLocalContextTokens
import com.nekobot.app.data.local.ai.decodeThinkingCardsForUi
import com.nekobot.app.data.local.ai.encodeToolCallHistory
import com.nekobot.app.data.local.ai.toPersistedProgressCard
import com.nekobot.app.data.local.ai.reconcileLocalTokenUsageRecords
import com.nekobot.app.data.local.ai.relationshipStateFromInitial
import com.nekobot.app.data.local.ai.resolveLocalTokenUsage
import com.nekobot.app.data.local.ai.sessionRelationshipTargetId
import com.nekobot.app.data.local.automation.AutomationExecutionResult
import com.nekobot.app.data.local.automation.LocalAutomationScheduler
import com.nekobot.app.data.local.automation.LocalScheduleCalculator
import com.nekobot.app.data.local.knowledge.LocalKnowledgeManager
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalAgentRunEntity
import com.nekobot.app.data.local.oauth.LocalOAuthManager
import com.nekobot.app.data.repository.SessionImportResult
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
import com.nekobot.app.data.model.RandomCharacterIdea
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Hook
import com.nekobot.app.data.model.HookExecutionLog
import com.nekobot.app.data.model.HookRequest
import com.nekobot.app.data.model.McpServer
import com.nekobot.app.data.model.McpServerRequest
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.KnowledgeDocument
import com.nekobot.app.data.model.KnowledgeDocumentRequest
import com.nekobot.app.data.model.KnowledgeSearchRequest
import com.nekobot.app.data.model.KnowledgeSearchResult
import com.nekobot.app.data.model.KnowledgeStats
import com.nekobot.app.service.AgentForegroundService
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INITIAL
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.model.SkillInstallRequest
import com.nekobot.app.data.model.SkillRequest
import com.nekobot.app.data.model.TaskItem
import com.nekobot.app.data.model.TaskRequest
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.Tool
import com.nekobot.app.data.model.ToolRequest
import com.nekobot.app.data.model.TtsVoice
import com.nekobot.app.data.model.Workflow
import com.nekobot.app.data.model.WorkflowRequest
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.remote.RealtimeEvent
import com.nekobot.app.ServiceContainer
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val agentRunDao = db.agentRunDao()
    private val characterDao = db.characterDao()
    private val worldBookDao = db.worldBookDao()
    private val aiModelDao = db.aiModelDao()
    private val achievementScopeId = "local:${db.dbName.removeSuffix(".db")}"
    val oauthManager = LocalOAuthManager(db.oauthAccountDao(), aiModelDao)
    private val failoverHealthDao = db.failoverHealthDao()
    private val localExecAuthorizationManager =
        com.nekobot.app.data.local.ai.LocalExecAuthorizationManager()
    private val localMcpRuntime = LocalMcpRuntime()
    private val mcpAutoConnectRunning = AtomicBoolean(false)
    @Volatile
    private var cachedMcpAgentTools: List<Map<String, Any>> = emptyList()
    private val localBrowserTools = ConcurrentHashMap<String, LocalBrowserTool>()
    private val localSkillStorage = appContext?.filesDir
        ?.let { LocalSkillStorage(File(it, "skills")) }
    private val skillPackageDownloader = SkillPackageDownloader()
    private val localJmRankingClient by lazy { LocalJmRankingClient() }
    private val localNovelClient by lazy { LocalNovelClient() }
    private val automationScheduler = appContext?.let {
        LocalAutomationScheduler(it, db.dbName.removeSuffix(".db"))
    }
    private val knowledgeManager by lazy { LocalKnowledgeManager(db, aiClient) }
    private val runningTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val runningWorkflowIds = ConcurrentHashMap.newKeySet<String>()
    /**
     * 轻小说搜索的会话级状态：`/findbook`、`/fa` 写入；`/select`、`/info` 读取。
     *
     * 与原仓库 `temp_selections[user_id]` + `api_book[user_id]` 等价，但按 sessionId 隔离。
     */
    private val localNovelSearchStates = ConcurrentHashMap<String, LocalNovelSearchState>()

    // ==================== 故障转移协调器（持久化健康状态 + token 限额）====================

    /** Room DAO 适配器：将 [FailoverHealthDao] 暴露为 [FailoverHealthStore] */
    private val failoverHealthStore: FailoverHealthStore by lazy { RoomFailoverHealthStore(failoverHealthDao) }

    /** 聚合每个模型的日/周 token 用量供 [FailoverCoordinator] 限额检查。 */
    private val failoverUsageReader: FailoverUsageReader by lazy {
        ReconciledFailoverUsageReader(::readTokenUsageRecordsReconciled) { id ->
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

    private suspend fun routedChatQueue(
        sessionId: String,
        prompt: String,
        attachments: List<Map<String, Any>> = emptyList(),
        sessionModeOverride: String? = null
    ): List<LocalAiModelEntity> {
        val models = queueFor("chat")
        val routingPrefs = ServiceContainer.prefs
        if (!routingPrefs.smartRoutingEnabled || models.size <= 1) return models

        val session = sessionId.takeIf(String::isNotBlank)?.let { sessionDao.getById(it) }
        val contextTokens = sessionId.takeIf(String::isNotBlank)
            ?.let { id ->
                estimateLocalAiContextTokens(messageDao.listBySession(id))
            }
            ?: 0
        val records = readTokenUsageRecordsReconciled().takeLast(1_000)
        val healthByModel = failoverHealthDao.listAll().associateBy { it.modelId }
        val nowMs = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val metrics = models.associate { model ->
            val samples = records.filter { record ->
                val actual = record.get("actual_model")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val display = record.get("model")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                actual == model.model || display == model.name
            }.takeLast(30)
            val health = healthByModel[model.id]
            model.id to SmartModelMetric(
                averageTtftMs = samples.mapNotNull { record ->
                    record.get("ttft_ms")?.takeIf { !it.isJsonNull }?.asDouble
                }.takeIf { it.isNotEmpty() }?.average(),
                averageDurationMs = samples.mapNotNull { record ->
                    record.get("duration_ms")?.takeIf { !it.isJsonNull }?.asDouble
                }.takeIf { it.isNotEmpty() }?.average(),
                recentRequests = samples.size,
                consecutiveFailures = health?.consecutiveFailures ?: 0,
                dailyFailures = health?.dailyFailures
                    ?.takeIf { health.dailyFailuresDate == today }
                    ?: 0,
                coolingDown = (health?.cooldownUntilMs ?: 0L) > nowMs
            )
        }
        val modelsByActual = models.associateBy { it.model }
        val modelsByDisplay = models.associateBy { it.name }
        val spentToday = records.asSequence()
            .filter { record ->
                record.get("date")?.takeIf { !it.isJsonNull }?.asString == today
            }
            .sumOf { record ->
                val actual = record.get("actual_model")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val display = record.get("model")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val model = modelsByActual[actual] ?: modelsByDisplay[display]
                if (model == null) {
                    0.0
                } else {
                    val input = record.get("input_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    val output = record.get("output_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    val prices = ModelPricingCatalog.resolvePrices(
                        modelName = model.model,
                        provider = model.provider,
                        inputPrice = model.inputPrice,
                        outputPrice = model.outputPrice
                    )
                    input / 1_000_000.0 * (prices.first ?: 0.0) +
                        output / 1_000_000.0 * (prices.second ?: 0.0)
                }
            }
        val budget = routingPrefs.smartRoutingDailyBudgetUsd
        appContext?.let { context ->
            SmartRoutingBudgetNotifier.notifyIfNeeded(context, routingPrefs, spentToday, budget)
        }
        return SmartModelRouter.route(
            models,
            SmartRoutingRequest(
                promptChars = prompt.length,
                estimatedContextTokens = contextTokens + (prompt.length + 2) / 3,
                sessionMode = sessionModeOverride ?: session?.sessionMode ?: "character",
                hasAttachments = attachments.isNotEmpty(),
                dailyBudgetUsd = budget,
                dailySpentUsd = spentToday
            ),
            metrics
        )
    }

    /**
     * 所有本地非流式文本生成任务的统一入口。
     *
     * 使用 purpose=chat 的完整故障转移队列，而不是单独读取 active 模型。这样角色卡、
     * 世界书、工作流、自动记忆等辅助任务也会遵守冷却、token 限额和超时策略。
     */
    private suspend fun executeChatOnceViaQueue(
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?> = emptyMap(),
        requestTag: String? = null
    ): FailoverExecution<LocalAiResult> {
        val routingPrompt = messages.joinToString("\n") { it["content"]?.toString().orEmpty() }
        val queue = routedChatQueue(
            sessionId = requestTag ?: currentSessionId,
            prompt = routingPrompt,
            sessionModeOverride = "utility"
        )
        if (queue.isEmpty()) {
            throw IllegalStateException("未配置可用的聊天模型，请在故障转移队列中启用 purpose=chat 的模型")
        }
        val execution = failoverCoordinator.execute(queue, "chat") { model ->
            val result = aiClient.chatOnce(model, messages, extra, requestTag)
            result.error?.let { error ->
                throw FailoverHttpException(result.statusCode, error)
            }
            result
        }
        return execution.copy(
            value = execution.value.copy(
                usedModelId = execution.model.id,
                usedModelName = execution.model.name,
                usedModelActualName = execution.model.model
            )
        )
    }

    /** 提供给自动状态、记忆、剧情选项等后台辅助任务的同一故障转移执行器。 */
    private val chatFailoverExecutor: LocalChatFailoverExecutor by lazy {
        LocalChatFailoverExecutor { messages -> executeChatOnceViaQueue(messages) }
    }

    /** 记录通过故障转移实际成功模型产生的 token 用量。 */
    private fun recordFailoverTokenUsage(
        execution: FailoverExecution<LocalAiResult>,
        source: String,
        purpose: String
    ) {
        val usage = execution.value.usage
        if (usage.isEmpty()) return
        val input = usage["prompt_tokens"] ?: usage["input_tokens"] ?: usage["prompt"] ?: 0
        val output = usage["completion_tokens"] ?: usage["output_tokens"] ?: usage["completion"] ?: 0
        if (input <= 0 && output <= 0) return
        appendTokenUsageRecord(
            sessionId = currentSessionId,
            model = execution.model.name,
            actualModel = execution.model.model,
            inputTokens = input,
            outputTokens = output,
            timestamp = nowIsoTimestamp(),
            source = source,
            purpose = purpose
        )
    }

    init {
        aiClient.setOAuthCredentialResolver(oauthManager::resolveCredential)
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
    private val tokenUsageLock = Any()
    @Volatile
    private var tokenUsageReconciled = false

    /**
     * 当前进行中会话 ID。二级 LLM 调用（AutoState/记忆抽取）在跨会话单例内触发，
     * 缺少 per-turn sessionId，故在此暂存，供 token 记账归属到正确会话。
     */
    @Volatile
    private var currentSessionId: String = ""

    /** 二级 LLM 调用（state/memory）token 记账回调 */
    private fun recordSecondaryTokenUsage(source: String, model: String, actualModel: String, input: Int, output: Int) {
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
            actualModel = actualModel,
            inputTokens = input,
            outputTokens = output,
            timestamp = nowIsoTimestamp(),
            source = source,
            purpose = purpose
        )
    }

    /** 剧情选项生成 token 记账回调 */
    private fun recordPlotTokenUsage(model: String, actualModel: String, input: Int, output: Int) {
        if (input == 0 && output == 0) return
        appendTokenUsageRecord(
            sessionId = currentSessionId,
            model = model,
            actualModel = actualModel,
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
            aiClient = aiClient,
            aiModelProvider = { aiModelDao.getActive() },
            failoverExecutor = chatFailoverExecutor,
            onTokenUsage = ::recordSecondaryTokenUsage
        )
    }
    /** LocalMemoryService 同理，turnCounters 需跨轮次保持 */
    private val memoryService by lazy {
        com.nekobot.app.data.local.ai.LocalMemoryService(
            memoryDao = db.memoryDao(),
            aiClient = aiClient,
            aiModelProvider = { aiModelDao.getActive() },
            failoverExecutor = chatFailoverExecutor,
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
            profileRepo,
            stateRepo,
            relRepo,
            memoryService,
            worldBookStore,
            autoState,
            memFS,
            snapshotRepo,
            ::reportAchievementProgress
        )
    }

    private fun reportAchievementProgress(
        metric: AchievementManager.Target.Metric,
        currentValue: Long
    ) {
        AchievementManager.reportProgress(metric, currentValue, achievementScopeId)
    }

    /** 本地模式 Hook 执行引擎（跨会话保持 once_per_conversation 状态） */
    val hookExecutor by lazy {
        com.nekobot.app.data.local.ai.HookExecutor(db)
    }

    /**
     * 跨 pipeline 共享的 Agent 命令授权请求流。
     *
     * 修复"角色卡/世界书/Hook 等高风险工具删除卡住"：
     * LocalDbToolExecutor.requestAuthorization 内部用 runBlocking 等待用户授权，
     * 它通过 LocalPipelineCallbacks.onConfirmationRequired 推送 ExecConfirmationRequest。
     * 旧实现把事件 emit 到 LocalPipelineCallbacks.eventChannel（一个没人 collect 的 Channel），
     * 导致用户永远收不到弹窗、决策永远不会被 resolve，工具卡 10 分钟才超时。
     * 现在统一 emit 到这个 SharedFlow，由 ChatViewModel.connectLocalHookEvents 收集。
     */
    private val _execConfirmationEvents = kotlinx.coroutines.flow.MutableSharedFlow<com.nekobot.app.data.remote.ExecConfirmationRequest>(
        extraBufferCapacity = 16
    )
    val execConfirmationEvents: kotlinx.coroutines.flow.SharedFlow<com.nekobot.app.data.remote.ExecConfirmationRequest> =
        _execConfirmationEvents

    /** 本地模式会话自动命名器（跨会话保持 autoNamed/lastRenameCount 状态） */
    private val sessionNameGenerator by lazy {
        com.nekobot.app.data.local.ai.SessionNameGenerator(
            aiClient = aiClient,
            aiModelProvider = { aiModelDao.getActive() },
            failoverExecutor = chatFailoverExecutor,
            onTokenUsage = ::recordSecondaryTokenUsage,
            stateLoader = { sessionId ->
                ServiceContainer.prefs.getSessionAutoNamingState(sessionId)?.let { (autoNamed, count) ->
                    com.nekobot.app.data.local.ai.SessionNamingState(autoNamed, count)
                }
            },
            stateSaver = { sessionId, state ->
                ServiceContainer.prefs.setSessionAutoNamingState(
                    sessionId = sessionId,
                    autoNamed = state.autoNamed,
                    messageCount = state.lastRenameCount
                )
            }
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

    /**
     * 从聊天页命令行直接操作当前 Agent 会话的 Linux 沙盒。
     *
     * 这是用户主动输入，不经过 AI 命令授权弹窗；仍然只运行在应用私有 PRoot rootfs，
     * 并与 Agent 的 exec_command 复用同一个会话 shell 和 /workspace。
     */
    suspend fun executeSandboxCommand(
        sessionId: String,
        command: String,
        timeoutSeconds: Int = 600,
    ): LocalSandboxCommandResult = withContext(Dispatchers.IO) {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isEmpty()) {
            return@withContext LocalSandboxCommandResult(
                command = command,
                output = "",
                exitCode = -1,
                durationMs = 0L,
                timedOut = false,
                error = "命令不能为空",
            )
        }
        val session = sessionDao.getById(sessionId)
            ?: return@withContext LocalSandboxCommandResult(
                command = normalizedCommand,
                output = "",
                exitCode = -1,
                durationMs = 0L,
                timedOut = false,
                error = "会话不存在",
            )
        if (!session.sessionMode.equals("agent", ignoreCase = true)) {
            return@withContext LocalSandboxCommandResult(
                command = normalizedCommand,
                output = "",
                exitCode = -1,
                durationMs = 0L,
                timedOut = false,
                error = "只有 Agent 会话可以打开沙箱终端",
            )
        }
        val context = appContext
            ?: return@withContext LocalSandboxCommandResult(
                command = normalizedCommand,
                output = "",
                exitCode = -1,
                durationMs = 0L,
                timedOut = false,
                error = "应用上下文不可用",
            )
        val workspace = LocalWorkspaceStorage.resolve(context.filesDir, sessionId)
            ?: return@withContext LocalSandboxCommandResult(
                command = normalizedCommand,
                output = "",
                exitCode = -1,
                durationMs = 0L,
                timedOut = false,
                error = "会话工作区不可用",
            )

        runCatching {
            LocalLinuxSandboxCoordinator.execute(
                context = context,
                sessionId = sessionId,
                workspace = workspace,
                command = normalizedCommand,
                timeoutMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(
                    timeoutSeconds.coerceIn(1, 600).toLong()
                ),
                shouldStop = { false },
            )
        }.fold(
            onSuccess = { result ->
                LocalSandboxCommandResult(
                    command = normalizedCommand,
                    output = result.output,
                    exitCode = result.exitCode,
                    durationMs = result.durationMs,
                    timedOut = result.timedOut,
                )
            },
            onFailure = { error ->
                LocalSandboxCommandResult(
                    command = normalizedCommand,
                    output = "",
                    exitCode = -1,
                    durationMs = 0L,
                    timedOut = false,
                    error = error.message ?: "沙箱命令执行失败",
                )
            },
        )
    }

    /** 中断命令行界面或 Agent 当前正在运行的沙箱命令。 */
    fun stopSandboxCommand(sessionId: String) {
        LocalLinuxSandboxCoordinator.stopSession(sessionId)
    }

    suspend fun createSession(req: CreateSessionRequest): Session = withContext(Dispatchers.IO) {
        val now = nowIso()
        val id = UUID.randomUUID().toString()
        val character = req.characterId?.let { characterDao.getById(it) }
        val isGroup = req.sessionMode.equals("group", ignoreCase = true)
        val groupCharacterIds = req.characterIds.orEmpty().filter { it.isNotBlank() }.distinct()
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
            sessionMode = req.sessionMode,
            groupId = if (isGroup) "gc_${UUID.randomUUID().toString().replace("-", "").take(12)}" else null,
            characterIds = groupCharacterIds.takeIf { isGroup }
                ?.let { gson.toJson(it) },
            groupConfig = req.groupConfig?.takeIf { isGroup }?.toString()
        )
        sessionDao.upsert(entity)

        // 成就触发：会话数量
        kotlin.runCatching {
            reportAchievementProgress(
                AchievementManager.Target.Metric.SESSIONS,
                sessionDao.count().toLong()
            )
        }

        if (character != null && req.sessionMode.equals("character", ignoreCase = true)) {
            initializeSessionRelationship(
                characterId = character.id,
                sessionId = id,
                source = req.relationshipStateSource
            )
        }

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

    /**
     * 导入会话：兼容三种导出格式并写入本地 Room。
     *
     * 支持的 JSON 格式：
     * 1. 本地导出：{session:{...}, messages:[...], exported_at, exported_by}
     * 2. nekobot 远程导出：{version, type:"nbot_session_export", sessions:[{...含内嵌 messages...}]}
     * 3. 单个 session 对象：{id, name, messages:[...], ...}
     *
     * 每个会话生成新 UUID 避免与现有会话冲突；跳过 system 角色消息（本地模式 system_prompt 存于 session 表）。
     */
    suspend fun importSessions(payload: JsonElement): SessionImportResult = withContext(Dispatchers.IO) {
        val parsed = normalizeImportPayload(payload)
        if (parsed.isEmpty()) return@withContext SessionImportResult(0, 0, emptyList())

        val now = nowIso()
        var imported = 0
        val errors = mutableListOf<String>()

        parsed.forEachIndexed { idx, (sessionObj, messagesArr) ->
            try {
                val newId = UUID.randomUUID().toString()
                val entity = mapJsonToSessionEntity(sessionObj, messagesArr, newId, now)
                sessionDao.upsert(entity)

                // 映射并写入消息（跳过 system 消息）
                val msgEntities = mapJsonToMessageEntities(messagesArr, newId, now)
                if (msgEntities.isNotEmpty()) {
                    messageDao.upsertAll(msgEntities)
                    val lastContent = msgEntities.last().content
                    sessionDao.touch(newId, lastContent.take(200), msgEntities.size, now)
                }
                imported++
            } catch (e: Exception) {
                errors.add("Session #${idx + 1}: ${e.message ?: "unknown"}")
            }
        }
        SessionImportResult(imported, parsed.size - imported, errors)
    }

    /** 将多种导出格式统一归一化为 (sessionObj, messagesArr) 列表。 */
    private fun normalizeImportPayload(payload: JsonElement): List<Pair<JsonObject, JsonArray>> {
        if (!payload.isJsonObject) {
            // 顶层是数组：视为多会话列表
            if (payload.isJsonArray) {
                return payload.asJsonArray.mapNotNull { el ->
                    if (el.isJsonObject) extractSessionWithMessages(el.asJsonObject) else null
                }
            }
            return emptyList()
        }
        val obj = payload.asJsonObject

        // 格式 2：nekobot 远程导出
        if (obj.str("type") == "nbot_session_export") {
            val arr = obj.getAsJsonArray("sessions") ?: return emptyList()
            return arr.mapNotNull { el ->
                if (el.isJsonObject) extractSessionWithMessages(el.asJsonObject) else null
            }
        }

        // 格式 1：本地导出 {session, messages}
        val sessionEl = obj.get("session")
        if (sessionEl?.isJsonObject == true) {
            val msgs = obj.getAsJsonArray("messages") ?: JsonArray()
            return listOf(sessionEl.asJsonObject to msgs)
        }

        // 格式 3：{sessions: [...]}
        val sessionsArr = obj.getAsJsonArray("sessions")
        if (sessionsArr != null) {
            return sessionsArr.mapNotNull { el ->
                if (el.isJsonObject) extractSessionWithMessages(el.asJsonObject) else null
            }
        }

        // 格式 4：单个 session 对象（含 messages 或 name/id 字段）
        if (obj.has("messages") || obj.has("name") || obj.has("id") || obj.has("role")) {
            return listOf(extractSessionWithMessages(obj))
        }
        return emptyList()
    }

    /** 从 session 对象提取 (session, messages)，messages 内嵌或不存在。 */
    private fun extractSessionWithMessages(sessionObj: JsonObject): Pair<JsonObject, JsonArray> {
        val msgs = sessionObj.getAsJsonArray("messages") ?: JsonArray()
        return sessionObj to msgs
    }

    /** 把 JSON session 对象映射为 [LocalSessionEntity]，生成新 ID。 */
    private fun mapJsonToSessionEntity(
        sessionObj: JsonObject,
        messagesArr: JsonArray,
        newId: String,
        now: String
    ): LocalSessionEntity {
        // system_prompt：优先 session 字段，否则从 messages 里找 role==system 的消息
        var systemPrompt = sessionObj.str("system_prompt", "systemPrompt")
        if (systemPrompt.isNullOrBlank()) {
            systemPrompt = messagesArr.firstOrNull { el ->
                el.isJsonObject && (el.asJsonObject.str("role")?.equals("system", ignoreCase = true) == true)
            }?.asJsonObject?.str("content")
        }

        val tagsArr = sessionObj.getAsJsonArray("tags")
        val charIdsArr = sessionObj.getAsJsonArray("character_ids")
            ?: sessionObj.getAsJsonArray("characterIds")
        val disabledKeysArr = sessionObj.getAsJsonArray("disabled_prompt_keys")
            ?: sessionObj.getAsJsonArray("disabledPromptKeys")

        val sessionMode = sessionObj.str("session_mode", "sessionMode") ?: "character"
        val isGroup = sessionMode.equals("group", ignoreCase = true)
        val groupCharacterIds = charIdsArr?.mapNotNull { el ->
            el.takeIf { !it.isJsonNull }?.asString
        }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()

        return LocalSessionEntity(
            id = newId,
            name = sessionObj.str("name") ?: "Imported ${newId.take(8)}",
            characterId = sessionObj.str("character_id", "characterId"),
            systemPrompt = systemPrompt,
            firstMessage = sessionObj.str("first_message", "firstMessage"),
            scenario = sessionObj.str("scenario"),
            senderName = sessionObj.str("sender_name", "senderName"),
            senderAvatar = sessionObj.str("sender_avatar", "senderAvatar"),
            characterName = sessionObj.str("character_name", "characterName"),
            characterAvatar = sessionObj.str("character_avatar", "characterAvatar"),
            portrait = sessionObj.str("portrait", "portrait_url", "character_portrait", "character_portrait_url"),
            tags = tagsArr?.mapNotNull { el -> el.takeIf { !it.isJsonNull }?.asString }
                ?.joinToString(","),
            favorite = sessionObj.boolVal("favorite") ?: false,
            pinned = sessionObj.boolVal("pinned") ?: false,
            archived = sessionObj.boolVal("archived") ?: false,
            createdAt = sessionObj.str("created_at", "createdAt") ?: now,
            updatedAt = now,
            plotMode = sessionObj.boolVal("plot_mode", "plotMode") ?: false,
            plotRealTimeSync = sessionObj.boolVal("plot_realtime_sync", "plot_real_time_sync") ?: false,
            plotChoiceStyle = sessionObj.str("plot_choice_style", "plotChoiceStyle"),
            plotOutline = sessionObj.str("plot_outline", "plotOutline"),
            userPersona = sessionObj.str("user_persona", "userPersona"),
            autoStateInterval = sessionObj.intVal("auto_state_interval", "autoStateInterval") ?: 2,
            disabledPromptKeys = disabledKeysArr?.mapNotNull { el ->
                el.takeIf { !it.isJsonNull }?.asString
            }?.joinToString(","),
            customPrompts = sessionObj.jsonStr("custom_prompts", "customPrompts"),
            ttsConfig = sessionObj.jsonStr("tts_config", "ttsConfig"),
            shareConfig = sessionObj.jsonStr("share_config", "shareConfig"),
            proactiveChat = sessionObj.jsonStr("proactive_chat", "proactiveChat"),
            groupConfig = sessionObj.jsonStr("group_config", "groupConfig")?.takeIf { isGroup },
            sessionMode = sessionMode,
            groupId = if (isGroup) "gc_${UUID.randomUUID().toString().replace("-", "").take(12)}" else null,
            characterIds = if (isGroup && groupCharacterIds.isNotEmpty()) gson.toJson(groupCharacterIds) else null
        )
    }

    /** 把 JSON messages 数组映射为 [LocalMessageEntity] 列表，跳过 system 消息。 */
    private fun mapJsonToMessageEntities(
        messagesArr: JsonArray,
        sessionId: String,
        now: String
    ): List<LocalMessageEntity> {
        val result = mutableListOf<LocalMessageEntity>()
        messagesArr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val m = el.asJsonObject
            val role = m.str("role") ?: return@forEach
            // 跳过 system 消息：本地模式 system_prompt 存于 session 表，避免上下文重复
            if (role.equals("system", ignoreCase = true)) return@forEach
            // 跳过进度卡片类型消息（thinking_card），不作为常规消息导入
            val type = m.str("type")
            if (type?.equals("thinking_card", ignoreCase = true) == true) return@forEach

            val content = m.str("content") ?: ""
            val ts = m.str("timestamp") ?: m.str("created_at", "createdAt")
                ?: System.currentTimeMillis().toString()
            val createdAt = m.str("created_at", "createdAt") ?: now
            val thinkingCardsEl = m.get("thinking_cards") ?: m.get("thinkingCards")
            val toolCallHistoryEl = m.get("tool_call_history") ?: m.get("toolCallHistory")

            result.add(
                LocalMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = role,
                    content = content,
                    reasoningContent = m.str("reasoning_content", "thinking_content", "reasoningContent"),
                    sender = m.str("sender", "sender_name", "character_name"),
                    timestamp = ts,
                    model = m.str("model"),
                    inputTokens = m.intVal("input_tokens", "inputTokens"),
                    outputTokens = m.intVal("output_tokens", "outputTokens"),
                    audioUrl = m.str("audio_url", "audioUrl"),
                    createdAt = createdAt,
                    thinkingCards = thinkingCardsEl?.takeIf { !it.isJsonNull }?.toString(),
                    toolCallHistory = toolCallHistoryEl?.takeIf { !it.isJsonNull }?.toString()
                )
            )
        }
        return result
    }

    // ---- JsonObject 取值辅助（兼容 snake_case / camelCase 多键名）----
    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonPrimitive) {
                val v = el.asString
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    private fun JsonObject.boolVal(vararg keys: String): Boolean? {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonPrimitive) {
                return runCatching { el.asBoolean }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.intVal(vararg keys: String): Int? {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonPrimitive) {
                return runCatching { el.asInt }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.jsonStr(vararg keys: String): String? {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonNull) continue
            return el.toString()
        }
        return null
    }

    /**
     * 为新角色会话保存独立的六维状态。
     * 继承时优先采用最近一轮真实对话快照；旧数据没有快照时再读取最近保存的关系行。
     */
    private suspend fun initializeSessionRelationship(
        characterId: String,
        sessionId: String,
        source: String
    ) {
        val targetId = sessionRelationshipTargetId(sessionId)
        val now = nowIso()
        val inherited = if (source != RELATIONSHIP_STATE_SOURCE_INITIAL) {
            db.stateSnapshotDao().getLatestForCharacter(characterId)?.let { snapshot ->
                RelationshipState(
                    characterId = characterId,
                    targetId = targetId,
                    affection = snapshot.affection,
                    trust = snapshot.trust,
                    familiarity = snapshot.familiarity,
                    dependency = snapshot.dependency,
                    security = snapshot.security,
                    jealousy = snapshot.jealousy,
                    updatedAt = now
                )
            } ?: db.relationshipDao().getLatestForCharacter(characterId, targetId)?.let { entity ->
                RelationshipState.fromJson(entity.dataJson).copy(
                    characterId = characterId,
                    targetId = targetId,
                    updatedAt = now
                )
            }
        } else {
            null
        }

        val relationship = inherited ?: relationshipStateFromInitial(
            characterId = characterId,
            targetId = targetId,
            initialState = LocalProfileRepository(characterDao).getById(characterId)?.initialState.orEmpty(),
            updatedAt = now
        )
        LocalRelationshipRepository(db.relationshipDao()).save(relationship)
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
        characterIds: List<String>? = null,
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
            characterIds = characterIds?.filter { it.isNotBlank() }?.distinct()
                ?.let { gson.toJson(it) } ?: entity.characterIds,
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
        if (favorite != null) {
            reportAchievementProgress(
                AchievementManager.Target.Metric.FAVORITE_SESSIONS,
                sessionDao.countFavorites().toLong()
            )
        }
        if (proactiveChat != null) {
            scheduleProactiveSession(updated)
        }
        android.util.Log.d("LocalRepo", "updateSession: updated.isPublic=${updated.isPublic}, updated.ttsConfig=${updated.ttsConfig}, updated.shareConfig=${updated.shareConfig}")
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        // 先清理该会话的剧情选项缓存（不影响 token 用量）
        appContext?.getSharedPreferences("plot_choices", android.content.Context.MODE_PRIVATE)
            ?.edit()?.remove(id)?.apply()
        localBrowserTools.remove(id)?.close()
        LocalLinuxSandboxCoordinator.stopSession(id)
        automationScheduler?.cancelProactive(id)
        sessionDao.deleteById(id)
    }

    /**
     * 重新扫描当前本地 Profile 的自动化配置。
     * 应用启动、切换本地数据库或从远程模式返回本地模式时调用。
     */
    suspend fun syncAutomationSchedules() = withContext(Dispatchers.IO) {
        db.taskDao().listAll().forEach { scheduleTask(it, preserveExisting = true) }
        db.workflowDao().listAll().forEach { scheduleWorkflow(it, preserveExisting = true) }
        sessionDao.listAll().forEach { scheduleProactiveSession(it, replaceExisting = false) }
    }

    /**
     * 主动聊天使用固定间隔，基线取最近一次真实用户活动与最近一次主动回复中的较新者。
     * 用户重新发言会立即重置下一次触发时间；主动聊天不使用指数退避。
     */
    private suspend fun scheduleProactiveSession(
        session: LocalSessionEntity,
        replaceExisting: Boolean = true,
        appendAfterCurrent: Boolean = false
    ) {
        val config = session.proactiveChat
            ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val enabled = config?.get("enabled")?.let { runCatching { it.asBoolean }.getOrNull() } == true
        if (
            !enabled ||
            session.archived ||
            session.sessionMode.equals("group", ignoreCase = true)
        ) {
            automationScheduler?.cancelProactive(session.id)
            return
        }
        val latestUser = messageDao.latestUserBySession(session.id)
        if (latestUser == null) {
            automationScheduler?.cancelProactive(session.id)
            return
        }
        val latestProactive = messageDao.latestBySource(session.id, "proactive_chat")
        val userAt = parseStoredInstant(latestUser.createdAt)
            ?: parseStoredInstant(latestUser.timestamp)
            ?: Instant.now()
        val proactiveAt = latestProactive?.let {
            parseStoredInstant(it.createdAt) ?: parseStoredInstant(it.timestamp)
        }
        val intervalMinutes = config.get("interval_minutes")
            ?.let { runCatching { it.asInt }.getOrNull() }
            ?.coerceAtLeast(1)
            ?: 60
        val baseline = if (proactiveAt != null && proactiveAt.isAfter(userAt)) proactiveAt else userAt
        automationScheduler?.scheduleProactive(
            sessionId = session.id,
            dueAt = baseline.plusSeconds(intervalMinutes * 60L),
            replaceExisting = replaceExisting,
            appendAfterCurrent = appendAfterCurrent
        )
    }

    suspend fun executeProactiveChat(sessionId: String): AutomationExecutionResult =
        withContext(Dispatchers.IO) {
            val session = sessionDao.getById(sessionId)
                ?: return@withContext AutomationExecutionResult("主动聊天", notify = false)
            val config = session.proactiveChat
                ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            val enabled = config?.get("enabled")?.let { runCatching { it.asBoolean }.getOrNull() } == true
            if (
                !enabled ||
                session.archived ||
                session.sessionMode.equals("group", ignoreCase = true)
            ) {
                return@withContext AutomationExecutionResult(session.name, notify = false)
            }
            val latestUser = messageDao.latestUserBySession(sessionId)
                ?: return@withContext AutomationExecutionResult(session.name, notify = false)
            val latestProactive = messageDao.latestBySource(sessionId, "proactive_chat")
            val userAt = parseStoredInstant(latestUser.createdAt)
                ?: parseStoredInstant(latestUser.timestamp)
                ?: Instant.EPOCH
            val proactiveAt = latestProactive?.let {
                parseStoredInstant(it.createdAt) ?: parseStoredInstant(it.timestamp)
            }
            val intervalMinutes = config.get("interval_minutes")
                ?.let { runCatching { it.asInt }.getOrNull() }
                ?.coerceAtLeast(1)
                ?: 60
            val baseline = if (proactiveAt != null && proactiveAt.isAfter(userAt)) proactiveAt else userAt
            val dueAt = baseline.plusSeconds(intervalMinutes * 60L)
            if (Instant.now().isBefore(dueAt)) {
                return@withContext AutomationExecutionResult(session.name, notify = false)
            }

            val configuredPrompt = config.get("prompt")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
            val hiddenTrigger = configuredPrompt?.takeIf(String::isNotBlank)
                ?: """
                    这是一次静默主动聊天触发。结合既有对话、角色状态和当前情境，自然地主动找用户聊天。
                    不要提及定时器、后台任务、系统提示或“主动聊天”机制；只输出角色真正会发给用户的消息。
                """.trimIndent()
            val content = executeAutomationPrompt(
                sessionId = sessionId,
                prompt = hiddenTrigger,
                assistantSource = "proactive_chat",
                allowTools = false,
                persistUserMessage = false,
                metadata = mapOf(
                    "is_heartbeat" to true,
                    "silent_trigger" to true,
                    "is_proactive_chat" to true,
                    "skip_auto_memory" to true,
                    "skip_character_after_turn" to true,
                    "source" to "proactive_chat",
                    "proactive_chat_triggered_at" to OffsetDateTime.now().toString()
                )
            )
            AutomationExecutionResult(
                title = session.characterName?.takeIf(String::isNotBlank) ?: session.name,
                content = content,
                sessionId = sessionId
            )
        }

    suspend fun onAutomationWorkerFinished(type: String, targetId: String) =
        withContext(Dispatchers.IO) {
            when (type) {
                LocalAutomationScheduler.TYPE_TASK ->
                    db.taskDao().getById(targetId)?.let {
                        if (it.enabled) {
                            scheduleTask(it, appendAfterCurrent = true)
                        } else {
                            db.taskDao().updateNextRun(it.id, null)
                        }
                    }
                LocalAutomationScheduler.TYPE_WORKFLOW ->
                    db.workflowDao().getById(targetId)?.let {
                        if (it.enabled && it.trigger.equals("cron", true)) {
                            scheduleWorkflow(it, appendAfterCurrent = true)
                        } else {
                            db.workflowDao().updateNextRun(it.id, null)
                        }
                    }
                LocalAutomationScheduler.TYPE_PROACTIVE ->
                    sessionDao.getById(targetId)?.let {
                        val config = it.proactiveChat
                            ?.let { raw -> runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() }
                        val enabled = config?.get("enabled")
                            ?.let { value -> runCatching { value.asBoolean }.getOrNull() } == true
                        if (
                            enabled &&
                            !it.archived &&
                            !it.sessionMode.equals("group", ignoreCase = true)
                        ) {
                            scheduleProactiveSession(it, appendAfterCurrent = true)
                        }
                    }
            }
        }

    private fun parseStoredInstant(raw: String?): Instant? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()?.let { return it }
        val localFormatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        )
        localFormatters.forEach { formatter ->
            runCatching {
                LocalDateTime.parse(value, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }.getOrNull()?.let { return it }
        }
        return value.toLongOrNull()?.let { epoch ->
            if (epoch > 10_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
        }
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

    /** 仅供 AI 调用链读取；命令输入和命令结果继续保留在聊天记录中。 */
    private suspend fun listAiContextMessages(sessionId: String): List<LocalMessageEntity> =
        messageDao.listBySession(sessionId)
            .filterNot { it.isLocalCommandMessage() }

    fun observeMessages(sessionId: String): Flow<List<LocalMessageEntity>> =
        messageDao.observeBySession(sessionId)

    /** 未完成的本地 Agent 运行；聊天页结合内存 Job 判断是否需要显示恢复入口。 */
    fun observeAgentRun(sessionId: String): Flow<LocalAgentRunEntity?> =
        agentRunDao.observeBySession(sessionId)

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
            // 成就触发：用户消息数
            if (role.equals("user", ignoreCase = true)) {
                kotlin.runCatching {
                    reportAchievementProgress(
                        AchievementManager.Target.Metric.MESSAGES,
                        messageDao.countUserMessages().toLong()
                    )
                }
                sessionDao.getById(sessionId)?.let { scheduleProactiveSession(it) }
            }
            msg.toMessage()
        }

    /** 保存 assistant 消息并记录 token 用量与模型名。 */
    suspend fun addAssistantMessage(
        sessionId: String,
        content: String,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        model: String? = null,
        actualModel: String? = null,
        usageEstimated: Boolean = false,
        durationMs: Double? = null,
        ttftMs: Double? = null,
        reasoningContent: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = nowIso()
        val msg = LocalMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "assistant",
            content = content,
            reasoningContent = reasoningContent?.takeIf(String::isNotBlank),
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
        if ((inputTokens ?: 0) > 0 || (outputTokens ?: 0) > 0) {
            appendTokenUsageRecord(
                sessionId = sessionId,
                messageId = msg.id,
                model = model ?: "",
                actualModel = actualModel ?: "",
                inputTokens = inputTokens ?: 0,
                outputTokens = outputTokens ?: 0,
                timestamp = now,
                estimated = usageEstimated,
                durationMs = durationMs,
                ttftMs = ttftMs
            )
        }
        msg.toMessage()
    }

    /**
     * 执行本地斜杠命令并保存一条普通 assistant 回复。
     *
     * 这里只放 Android 能稳定完成的能力。依赖 Python native wheel、QQ Bot API 或
     * 服务器进程的命令由命令目录统一返回说明，不能继续落入 AI Pipeline 猜测执行。
     */
    private suspend fun executeLocalSlashCommand(
        session: LocalSessionEntity,
        activeModel: LocalAiModelEntity,
        command: LocalParsedCommand,
        parentMessageId: String,
        onProgress: suspend (ThinkingCard) -> Unit = {}
    ): Message {
        val progressReporter = when (command.action) {
            LocalCommandAction.JM_RANK,
            LocalCommandAction.JM_DOWNLOAD,
            LocalCommandAction.NOVEL_SEARCH,
            LocalCommandAction.NOVEL_SEARCH_AUTHOR,
            LocalCommandAction.NOVEL_HOT,
            LocalCommandAction.NOVEL_RANDOM,
            LocalCommandAction.NOVEL_INFO,
            LocalCommandAction.NOVEL_SELECT,
            LocalCommandAction.NOVEL_RES -> LocalCommandProgressReporter(
                parentMessageId = parentMessageId,
                onUpdate = { card ->
                    updateMessageThinkingCards(parentMessageId, listOf(card))
                    onProgress(card)
                }
            )
            else -> null
        }
        val content = when (command.action) {
            LocalCommandAction.HELP -> LocalSlashCommands.helpText()
            LocalCommandAction.LOCAL_STATUS -> localStatusText(session, activeModel)
            LocalCommandAction.EXPORT_CHAT -> localExportChatText(session)
            LocalCommandAction.NOTE_ADD -> localNoteAddText(session.id, command.args)
            LocalCommandAction.NOTES_SHOW -> localNotesShowText(session.id)
            LocalCommandAction.TTS -> executeLocalTtsCommand(session, command.args)
            LocalCommandAction.WORKSPACE_LIST -> localWorkspaceListText(session.id)
            LocalCommandAction.WORKSPACE_SEND -> localWorkspaceSendText(session.id, command.args)
            LocalCommandAction.ROLL -> localCommandResult {
                LocalCommandUtilities.rollDiceText(command.args)
            }
            LocalCommandAction.RANDOM_RPS -> {
                val choice = listOf("石头 ✊", "剪刀 ✌️", "布 ✋").random()
                "我出：**$choice**"
            }
            LocalCommandAction.COIN ->
                if (kotlin.random.Random.nextBoolean()) "🪙 **正面**" else "🪙 **反面**"
            LocalCommandAction.PICK -> localCommandResult {
                LocalCommandUtilities.pickText(command.args)
            }
            LocalCommandAction.CALCULATE -> localCommandResult {
                val result = LocalCommandUtilities.calculate(command.args)
                "🧮 **计算结果：$result**"
            }
            LocalCommandAction.PASSWORD -> localCommandResult {
                val password = LocalCommandUtilities.generatePassword(command.args)
                "🔐 已在本机生成随机密码：\n\n`$password`\n\n密码不会发送给 AI 或服务器。"
            }
            LocalCommandAction.HASH -> localCommandResult {
                val digest = LocalCommandUtilities.sha256(command.args)
                "SHA-256：\n\n`$digest`"
            }
            LocalCommandAction.FORTUNE -> localFortuneText(session.id)
            LocalCommandAction.JM_RANK ->
                localJmRankingText(session.id, command.args, progressReporter)
            LocalCommandAction.JM_DOWNLOAD ->
                localJmDownloadPdfText(session.id, command.args, progressReporter)
            LocalCommandAction.NOVEL_SEARCH ->
                localNovelSearchText(session.id, command.args, progressReporter, byAuthor = false)
            LocalCommandAction.NOVEL_SEARCH_AUTHOR ->
                localNovelSearchText(session.id, command.args, progressReporter, byAuthor = true)
            LocalCommandAction.NOVEL_HOT ->
                localNovelHotText(session.id, command.args, progressReporter)
            LocalCommandAction.NOVEL_RANDOM ->
                localNovelRandomText(session.id, progressReporter)
            LocalCommandAction.NOVEL_INFO ->
                localNovelInfoText(session.id, command.args, progressReporter)
            LocalCommandAction.NOVEL_SELECT ->
                localNovelSelectText(session.id, command.args, progressReporter)
            LocalCommandAction.NOVEL_RES ->
                localNovelResText(session.id, command.args, progressReporter)
            LocalCommandAction.NOVEL_SET_COOKIE ->
                localNovelSetCookieText(command.args)
            LocalCommandAction.PYTHON_RUNTIME_REQUIRED ->
                LocalSlashCommands.pythonRuntimeMessage(command.name)
            LocalCommandAction.REMOTE_RUNTIME_REQUIRED ->
                LocalSlashCommands.remoteRuntimeMessage(command.name)
            LocalCommandAction.UNKNOWN -> LocalSlashCommands.unknownMessage(command.name)
        }
        return addAssistantMessage(
            sessionId = session.id,
            content = content,
            model = LOCAL_COMMAND_MODEL
        )
    }

    private suspend fun localStatusText(
        session: LocalSessionEntity,
        activeModel: LocalAiModelEntity
    ): String {
        val messages = messageDao.listBySession(session.id).dropLast(1)
        val userCount = messages.count { it.role.equals("user", ignoreCase = true) }
        val assistantCount = messages.count { it.role.equals("assistant", ignoreCase = true) }
        val inputTokens = messages.sumOf { it.inputTokens ?: 0 }
        val outputTokens = messages.sumOf { it.outputTokens ?: 0 }
        val workspaceRoot = localWorkspaceRoot(session.id)
        val workspaceFiles = workspaceRoot
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.toList()
            .orEmpty()
        val workspaceBytes = workspaceFiles.sumOf(File::length)
        val ttsEnabled = session.ttsConfig
            ?.let { raw ->
                runCatching {
                    JsonParser.parseString(raw).asJsonObject
                        .get("enabled")
                        ?.takeUnless { it.isJsonNull }
                        ?.asBoolean
                }.getOrNull()
            }
            ?: false
        val mode = when (session.sessionMode.lowercase()) {
            "agent" -> "Agent"
            "group" -> "群聊"
            else -> "角色对话"
        }
        return buildString {
            appendLine("📱 **本地会话状态**")
            appendLine()
            appendLine("• 会话：${session.name}")
            appendLine("• 模式：$mode")
            session.characterName?.takeIf { it.isNotBlank() }?.let {
                appendLine("• 角色：$it")
            }
            appendLine("• 模型：${activeModel.name.ifBlank { activeModel.model }}")
            appendLine("• 消息：${messages.size} 条（用户 $userCount / AI $assistantCount）")
            appendLine("• 已记录 Token：${inputTokens + outputTokens}（输入 $inputTokens / 输出 $outputTokens）")
            appendLine("• 工作区：${workspaceFiles.size} 个文件，${formatLocalFileSize(workspaceBytes)}")
            append("• TTS：${if (ttsEnabled) "已开启" else "已关闭"}")
        }
    }

    private suspend fun localExportChatText(session: LocalSessionEntity): String {
        val root = localWorkspaceRoot(session.id)
            ?: return "无法打开当前会话工作区。"
        val messages = messageDao.listBySession(session.id).dropLast(1)
        if (messages.isEmpty()) return "当前会话还没有可导出的消息。"

        val exportedAt = java.time.LocalDateTime.now()
        val fileStamp = exportedAt.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        )
        val relativePath = "exports/chat-$fileStamp.md"
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        val markdown = buildString {
            appendLine("# ${session.name}")
            appendLine()
            appendLine("- 导出时间：${exportedAt.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            appendLine("- 会话模式：${session.sessionMode}")
            session.characterName?.takeIf { it.isNotBlank() }?.let {
                appendLine("- 角色：$it")
            }
            appendLine()
            messages.forEach { message ->
                val role = when (message.role.lowercase()) {
                    "user", "human" -> "用户"
                    "assistant" -> message.sender?.takeIf { it.isNotBlank() } ?: "AI"
                    else -> message.role
                }
                appendLine("## $role · ${message.timestamp}")
                appendLine()
                appendLine(message.content.ifBlank { "（空消息）" })
                appendLine()
            }
        }
        return runCatching {
            target.writeText(markdown, Charsets.UTF_8)
            "已将 ${messages.size} 条消息导出到本地工作区。\n\n[File: $relativePath]"
        }.getOrElse { error ->
            "导出失败：${error.message ?: "无法写入文件"}"
        }
    }

    private fun localNoteAddText(sessionId: String, rawNote: String): String {
        val note = rawNote.trim()
        if (note.isEmpty()) return "格式：`/note <内容>`"
        if (note.length > 10_000) return "单条速记不能超过 10000 个字符。"
        val root = localWorkspaceRoot(sessionId)
            ?: return "无法打开当前会话工作区。"
        val relativePath = "notes/local-notes.md"
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val indented = note
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\n  ")
        return runCatching {
            if (!target.exists()) {
                target.writeText("# 本地速记\n\n", Charsets.UTF_8)
            }
            target.appendText("- **$timestamp**\n  $indented\n\n", Charsets.UTF_8)
            "已保存本地速记。\n\n[File: $relativePath]"
        }.getOrElse { error ->
            "保存速记失败：${error.message ?: "无法写入文件"}"
        }
    }

    private fun localNotesShowText(sessionId: String): String {
        val root = localWorkspaceRoot(sessionId)
            ?: return "无法打开当前会话工作区。"
        val relativePath = "notes/local-notes.md"
        val target = File(root, relativePath)
        return if (target.isFile) {
            "[File: $relativePath]"
        } else {
            "当前会话还没有本地速记，使用 `/note <内容>` 添加。"
        }
    }

    private suspend fun executeLocalTtsCommand(
        session: LocalSessionEntity,
        args: String
    ): String {
        val config = session.ttsConfig
            ?.let { raw ->
                runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            }
            ?: JsonObject()
        val currentlyEnabled = config.get("enabled")
            ?.takeUnless { it.isJsonNull }
            ?.asBoolean
            ?: false

        val enabled = when (args.trim().lowercase()) {
            "" -> !currentlyEnabled
            "on", "开启", "开" -> true
            "off", "关闭", "关" -> false
            else -> return "格式：`/tts [on|off]`"
        }
        config.addProperty("enabled", enabled)
        // 留空时由所选 TTS 模型使用自己的默认音色，避免小米/豆包误用 OpenAI 的 alloy。
        if (!config.has("voice")) config.addProperty("voice", "")
        updateSession(session.id, ttsConfig = config.toString())

        if (!enabled) return "已关闭当前会话的 TTS。"

        val hasTtsModel = aiModelDao.listByPurpose("tts").isNotEmpty()
        return if (hasTtsModel) {
            "已开启当前会话的 TTS。"
        } else {
            "已开启当前会话的 TTS，但还没有可用的 TTS 模型；请先在 AI 配置中心添加并启用 `purpose=tts` 的模型。"
        }
    }

    private fun localWorkspaceListText(sessionId: String): String {
        val root = localWorkspaceRoot(sessionId)
            ?: return "无法打开当前会话工作区。"
        val files = root.walkTopDown()
            .filter { it.isFile }
            .take(100)
            .toList()

        if (files.isEmpty()) return "当前会话工作区为空。"

        val lines = files.map { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            "• `$relative`（${formatLocalFileSize(file.length())}）"
        }
        return buildString {
            appendLine("当前会话工作区")
            appendLine()
            append(lines.joinToString("\n"))
            if (files.size >= 100) append("\n\n仅显示前 100 个文件。")
            append("\n\n使用 `/ws_send <文件名>` 发送文件。")
        }
    }

    private fun localWorkspaceSendText(sessionId: String, rawPath: String): String {
        if (rawPath.isBlank()) return "格式：`/ws_send <文件名>`"
        val root = localWorkspaceRoot(sessionId)
            ?: return "无法打开当前会话工作区。"
        val relativeInput = rawPath
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace('\\', '/')
        val target = runCatching { File(root, relativeInput).canonicalFile }.getOrNull()
            ?: return "文件路径无效：`$relativeInput`"
        val isInside = target.path == root.path ||
            target.path.startsWith(root.path + File.separator)
        if (!isInside || !target.isFile) {
            return "工作区中找不到文件：`$relativeInput`"
        }
        val relative = target.relativeTo(root).invariantSeparatorsPath
        return "[File: $relative]"
    }

    private fun localWorkspaceRoot(sessionId: String): File? =
        appContext?.filesDir
            ?.let { filesDir -> LocalWorkspaceStorage.resolve(filesDir, sessionId) }
            ?.canonicalFile

    private fun executeLocalBrowserTool(
        sessionId: String,
        args: Map<String, Any>
    ): Map<String, Any> {
        val context = appContext
            ?: return mapOf("success" to false, "error" to "应用上下文未初始化，无法使用浏览器")
        val workspaceRoot = localWorkspaceRoot(sessionId)
            ?: return mapOf("success" to false, "error" to "无法打开当前会话工作区")
        val browser = localBrowserTools.computeIfAbsent(sessionId) {
            LocalBrowserTool(
                context = context,
                sessionId = sessionId,
                workspaceRoot = workspaceRoot
            )
        }
        return browser.execute(args)
    }

    private inline fun localCommandResult(block: () -> String): String =
        runCatching(block).getOrElse { error ->
            error.message ?: "命令执行失败。"
        }

    private suspend fun localJmRankingText(
        sessionId: String,
        rawPeriod: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val period = try {
            parseLocalJmRankingPeriod(rawPeriod)
        } catch (error: Exception) {
            val message = error.message ?: "格式：`/jmrank [周排行|月排行]`"
            progressReporter?.update(
                content = "JM 排行参数错误",
                progress = 0,
                steps = listOf(
                    ThinkingStep(type = "done", name = "参数校验失败", status = "error", detail = message)
                ),
                isComplete = true,
                force = true
            )
            return message
        }
        val currentProgress = AtomicInteger(0)

        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        report(
            content = "获取 JM ${period.displayName}",
            progress = 0,
            steps = listOf(
                ThinkingStep(
                    type = "knowledge",
                    name = "获取排行榜数据",
                    status = "running",
                    detail = period.displayName
                )
            ),
            force = true
        )

        return try {
            val entries = withContext(Dispatchers.IO) {
                localJmRankingClient.fetchRanking(period)
            }
            report(
                content = "处理 JM ${period.displayName}",
                progress = 12,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "获取排行榜数据",
                        status = "done",
                        detail = "${entries.size} 条"
                    ),
                    ThinkingStep(
                        type = "image",
                        name = "下载高清封面",
                        status = "running",
                        detail = "0/${entries.size}"
                    )
                ),
                force = true
            )
            val semaphore = Semaphore(6)
            val completedCovers = AtomicInteger(0)
            val successfulCovers = AtomicInteger(0)
            val covers = coroutineScope {
                entries.map { entry ->
                    async(Dispatchers.IO) {
                        val cover = semaphore.withPermit {
                            localJmRankingClient.fetchCoverDataUrl(entry.id)
                        }
                        if (!cover.isNullOrBlank()) successfulCovers.incrementAndGet()
                        val completed = completedCovers.incrementAndGet()
                        val progress = progressBetween(12, 90, completed, entries.size)
                        report(
                            content = "下载 JM 排行封面",
                            progress = progress,
                            steps = listOf(
                                ThinkingStep(
                                    type = "knowledge",
                                    name = "获取排行榜数据",
                                    status = "done",
                                    detail = "${entries.size} 条"
                                ),
                                ThinkingStep(
                                    type = "image",
                                    name = "下载高清封面",
                                    status = if (completed == entries.size) "done" else "running",
                                    detail = "$completed/${entries.size}，成功 ${successfulCovers.get()} 张"
                                )
                            )
                        )
                        entry.id to cover
                    }
                }.awaitAll().toMap()
            }
            report(
                content = "生成 JM 排行文件",
                progress = 94,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "获取排行榜数据",
                        status = "done",
                        detail = "${entries.size} 条"
                    ),
                    ThinkingStep(
                        type = "image",
                        name = "下载高清封面",
                        status = "done",
                        detail = "成功 ${successfulCovers.get()}/${entries.size} 张"
                    ),
                    ThinkingStep(
                        type = "file",
                        name = "生成排行榜 HTML",
                        status = "running"
                    )
                ),
                force = true
            )
            val html = buildLocalJmRankingHtml(period, entries, covers)
            val root = localWorkspaceRoot(sessionId)
                ?: error("无法打开当前会话工作区。")
            val rankDir = File(root, "rank")
            if (!rankDir.exists() && !rankDir.mkdirs()) {
                error("无法创建排行榜目录。")
            }
            val periodName = period.name.lowercase(Locale.ROOT)
            val fileName = "jm-$periodName-${System.currentTimeMillis()}.html"
            withContext(Dispatchers.IO) {
                File(rankDir, fileName).writeText(html, Charsets.UTF_8)
            }
            val coverCount = covers.values.count { !it.isNullOrBlank() }
            report(
                content = "JM ${period.displayName}已生成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "获取排行榜数据",
                        status = "done",
                        detail = "${entries.size} 条"
                    ),
                    ThinkingStep(
                        type = "image",
                        name = "下载高清封面",
                        status = "done",
                        detail = "成功 $coverCount/${entries.size} 张"
                    ),
                    ThinkingStep(
                        type = "file",
                        name = "生成排行榜 HTML",
                        status = "done",
                        detail = "rank/$fileName"
                    )
                ),
                isComplete = true,
                force = true
            )
            "已生成 JM ${period.displayName}：${entries.size} 条，成功获取 $coverCount 张封面。" +
                "\n\n[File: rank/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "JM ${period.displayName}已取消",
                    progress = currentProgress.get(),
                    steps = listOf(
                        ThinkingStep(type = "done", name = "任务已取消", status = "error")
                    ),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "获取 JM ${period.displayName}失败，请稍后重试。"
            progressReporter?.update(
                content = "JM ${period.displayName}获取失败",
                progress = currentProgress.get(),
                steps = listOf(
                    ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)
                ),
                isComplete = true,
                force = true
            )
            message
        }
    }

    private suspend fun localJmDownloadPdfText(
        sessionId: String,
        rawArgs: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val request = try {
            parseLocalJmDownloadRequest(rawArgs)
        } catch (error: Exception) {
            val message = error.message ?: "格式：`/jm <漫画ID> [--force]`"
            progressReporter?.update(
                content = "JM 下载参数错误",
                progress = 0,
                steps = listOf(
                    ThinkingStep(type = "done", name = "参数校验失败", status = "error", detail = message)
                ),
                isComplete = true,
                force = true
            )
            return message
        }
        val currentProgress = AtomicInteger(0)

        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        report(
            content = "准备下载 JM${request.albumId}",
            progress = 0,
            steps = listOf(
                ThinkingStep(type = "knowledge", name = "获取漫画信息", status = "running")
            ),
            force = true
        )

        var partialFile: File? = null
        return try {
            val root = localWorkspaceRoot(sessionId)
                ?: error("无法打开当前会话工作区。")
            val downloadDir = File(root, "downloads")
            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                error("无法创建当前会话的下载目录。")
            }
            val album = withContext(Dispatchers.IO) {
                localJmRankingClient.fetchAlbum(request.albumId)
            }
            report(
                content = "读取《${album.title}》章节",
                progress = 6,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "获取漫画信息",
                        status = "done",
                        detail = "${album.chapters.size} 章"
                    ),
                    ThinkingStep(
                        type = "knowledge",
                        name = "读取章节页数",
                        status = "running",
                        detail = "0/${album.chapters.size}"
                    )
                ),
                force = true
            )
            val semaphore = Semaphore(JM_CHAPTER_FETCH_CONCURRENCY)
            val completedChapters = AtomicInteger(0)
            val photos = coroutineScope {
                album.chapters.map { chapter ->
                    async(Dispatchers.IO) {
                        val photo = semaphore.withPermit {
                            localJmRankingClient.fetchPhoto(album, chapter)
                        }
                        val completed = completedChapters.incrementAndGet()
                        val progress = progressBetween(6, 15, completed, album.chapters.size)
                        report(
                            content = "读取《${album.title}》章节",
                            progress = progress,
                            steps = listOf(
                                ThinkingStep(
                                    type = "knowledge",
                                    name = "获取漫画信息",
                                    status = "done",
                                    detail = "${album.chapters.size} 章"
                                ),
                                ThinkingStep(
                                    type = "knowledge",
                                    name = "读取章节页数",
                                    status = if (completed == album.chapters.size) "done" else "running",
                                    detail = "$completed/${album.chapters.size}"
                                )
                            )
                        )
                        photo
                    }
                }.awaitAll()
            }
            val totalPages = photos.sumOf { it.imageFiles.size }
            if (totalPages <= 0) {
                error("JM${album.id} 没有可下载的图片。")
            }
            if (totalPages > JM_DEFAULT_MAX_PAGES && !request.force) {
                error(
                    "JM${album.id} 共 $totalPages 页，超过本地单次下载保护上限 " +
                        "$JM_DEFAULT_MAX_PAGES 页。确认存储空间充足后使用 " +
                        "`/jm ${album.id} --force`。"
                )
            }

            val estimatedBytes =
                totalPages.toLong() * JM_ESTIMATED_BYTES_PER_PAGE + JM_REQUIRED_FREE_RESERVE
            val availableBytes = StatFs(downloadDir.absolutePath).availableBytes
            if (availableBytes < estimatedBytes) {
                error(
                    "存储空间可能不足：预计至少需要 ${formatLocalFileSize(estimatedBytes)}，" +
                    "当前可用 ${formatLocalFileSize(availableBytes)}。"
                )
            }
            report(
                content = "准备下载《${album.title}》",
                progress = 16,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "漫画与章节信息",
                        status = "done",
                        detail = "${photos.size} 章、$totalPages 页"
                    ),
                    ThinkingStep(
                        type = "image",
                        name = "准备图片解码",
                        status = "running"
                    )
                ),
                force = true
            )

            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val fileName = "JM${album.id}-$timestamp.pdf"
            val finalFile = File(downloadDir, fileName)
            val tempFile = File(downloadDir, "$fileName.part")
            partialFile = tempFile
            val scrambleId = withContext(Dispatchers.IO) {
                localJmRankingClient.fetchScrambleId(album, photos.first().id)
            }
            report(
                content = "下载《${album.title}》",
                progress = 18,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "漫画与章节信息",
                        status = "done",
                        detail = "${photos.size} 章、$totalPages 页"
                    ),
                    ThinkingStep(
                        type = "image",
                        name = "下载并解码图片",
                        status = "running",
                        detail = "0/$totalPages 页"
                    ),
                    ThinkingStep(
                        type = "file",
                        name = "写入 PDF",
                        status = "running",
                        detail = "0/$totalPages 页"
                    )
                ),
                force = true
            )

            withContext(Dispatchers.IO) {
                var completedPages = 0
                LocalImagePdfWriter(tempFile).use { writer ->
                    photos.forEach { photo ->
                        photo.imageFiles.forEach { imageFile ->
                            currentCoroutineContext().ensureActive()
                            if (
                                writer.pageCount > 0 &&
                                writer.pageCount % JM_FREE_SPACE_CHECK_INTERVAL == 0
                            ) {
                                val remaining = StatFs(downloadDir.absolutePath).availableBytes
                                if (remaining < JM_MINIMUM_REMAINING_BYTES) {
                                    error(
                                        "下载已停止：设备剩余空间低于 " +
                                            formatLocalFileSize(JM_MINIMUM_REMAINING_BYTES)
                                    )
                                }
                            }
                            val image = localJmRankingClient.fetchPageJpeg(
                                album = album,
                                photoId = photo.id,
                                imageFile = imageFile,
                                scrambleId = scrambleId
                            )
                            writer.addJpegPage(
                                jpegBytes = image.jpegBytes,
                                pixelWidth = image.width,
                                pixelHeight = image.height
                            )
                            completedPages += 1
                            val progress = progressBetween(18, 96, completedPages, totalPages)
                            report(
                                content = "下载《${album.title}》",
                                progress = progress,
                                steps = listOf(
                                    ThinkingStep(
                                        type = "knowledge",
                                        name = "漫画与章节信息",
                                        status = "done",
                                        detail = "${photos.size} 章、$totalPages 页"
                                    ),
                                    ThinkingStep(
                                        type = "image",
                                        name = "下载并解码图片",
                                        status = if (completedPages == totalPages) "done" else "running",
                                        detail = "$completedPages/$totalPages 页 · ${photo.title}"
                                    ),
                                    ThinkingStep(
                                        type = "file",
                                        name = "写入 PDF",
                                        status = if (completedPages == totalPages) "done" else "running",
                                        detail = "$completedPages/$totalPages 页"
                                    )
                                )
                            )
                        }
                    }
                    writer.finish()
                }
            }
            report(
                content = "保存《${album.title}》PDF",
                progress = 98,
                steps = listOf(
                    ThinkingStep(
                        type = "image",
                        name = "下载并解码图片",
                        status = "done",
                        detail = "$totalPages/$totalPages 页"
                    ),
                    ThinkingStep(
                        type = "file",
                        name = "保存 PDF 文件",
                        status = "running"
                    )
                ),
                force = true
            )
            if (!tempFile.renameTo(finalFile)) {
                error("PDF 已生成，但无法从临时文件保存为最终文件。")
            }
            partialFile = null
            report(
                content = "《${album.title}》下载完成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(
                        type = "knowledge",
                        name = "漫画与章节信息",
                        status = "done",
                        detail = "${photos.size} 章、$totalPages 页"
                    ),
                    ThinkingStep(
                        type = "image",
                        name = "下载并解码图片",
                        status = "done",
                        detail = "$totalPages 页"
                    ),
                    ThinkingStep(
                        type = "file",
                        name = "PDF 已保存",
                        status = "done",
                        detail = "downloads/$fileName · ${formatLocalFileSize(finalFile.length())}"
                    )
                ),
                isComplete = true,
                force = true
            )
            "已下载《${album.title}》：${photos.size} 章、$totalPages 页，" +
                "PDF ${formatLocalFileSize(finalFile.length())}。" +
                "\n\n[File: downloads/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    partialFile?.takeIf { it.exists() }?.delete()
                }
                progressReporter?.update(
                    content = "JM${request.albumId} 下载已取消",
                    progress = currentProgress.get(),
                    steps = listOf(
                        ThinkingStep(type = "done", name = "任务已取消", status = "error")
                    ),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            partialFile?.takeIf { it.exists() }?.delete()
            val message = error.message ?: "下载 JM${request.albumId} 失败，请稍后重试。"
            progressReporter?.update(
                content = "JM${request.albumId} 下载失败",
                progress = currentProgress.get(),
                steps = listOf(
                    ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)
                ),
                isComplete = true,
                force = true
            )
            message
        }
    }

    // ==================== 轻小说命令（/findbook /fa /hotnovel /random_novel /info /select /novel_res /set_wenku_cookie）====================

    /**
     * 读取 wenku8 Cookie。
     *
     * 与 [PrefsManager.wenku8Cookie] 共用 SharedPreferences 文件与 key，
     * 因此 `/set_wenku_cookie` 写入的值在 LocalRepository 中也能立即读到。
     */
    private fun readWenku8Cookie(): String =
        ServiceContainer.prefs.wenku8Cookie

    /** 写入 wenku8 Cookie，供 `/set_wenku_cookie` 命令使用。 */
    private fun writeWenku8Cookie(cookie: String) {
        ServiceContainer.prefs.wenku8Cookie = cookie
    }

    /**
     * 读取 wenku8 自定义 User-Agent。
     *
     * CloudFlare 的 cf_clearance 绑定获取时的 IP + UA，
     * 允许用户通过 `/set_wenku_cookie <Cookie> || <UA>` 一并设置。
     */
    private fun readWenku8UserAgent(): String =
        ServiceContainer.prefs.wenku8UserAgent

    /** 写入 wenku8 自定义 User-Agent。 */
    private fun writeWenku8UserAgent(ua: String) {
        ServiceContainer.prefs.wenku8UserAgent = ua
    }

    /**
     * `/findbook <书名>` / `/fa <作者>`：搜索 wenku8 + 番茄 API，生成卡片网格 HTML。
     *
     * 对齐原仓库 `handle_find_book` / `handle_find_author`：
     * 1. wenku8 网页搜索（按书名或作者）
     * 2. 番茄 API 搜索（仅按书名时调用）
     * 3. 合并去重后写入会话工作区 `novel/search-*.html`
     * 4. 把结果存入 [localNovelSearchStates] 供 `/select`、`/info` 引用
     */
    private suspend fun localNovelSearchText(
        sessionId: String,
        rawArgs: String,
        progressReporter: LocalCommandProgressReporter?,
        byAuthor: Boolean
    ): String {
        val searchTerm = rawArgs.trim()
        if (searchTerm.isBlank()) {
            val hint = if (byAuthor) "请输入要搜索的作者喵~" else "请输入要搜索的书名喵~"
            progressReporter?.update(
                content = "轻小说搜索参数为空",
                progress = 0,
                steps = listOf(ThinkingStep(type = "done", name = "参数错误", status = "error", detail = hint)),
                isComplete = true,
                force = true
            )
            return hint
        }
        val cookie = readWenku8Cookie()
        val userAgent = readWenku8UserAgent()
        val currentProgress = AtomicInteger(0)

        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        val searchType = if (byAuthor) "作者" else "书名"
        report(
            content = "搜索轻小说（$searchType：$searchTerm）",
            progress = 5,
            steps = listOf(ThinkingStep(type = "knowledge", name = "搜索 wenku8", status = "running", detail = searchTerm)),
            force = true
        )

        return try {
            // 1. wenku8 网页搜索
            val webMatches = withContext(Dispatchers.IO) {
                localNovelClient.searchBooks(
                    searchTerm = searchTerm,
                    searchType = if (byAuthor) "author" else "articlename",
                    cookie = cookie,
                    userAgent = userAgent
                )
            }
            report(
                content = "wenku8 搜索完成",
                progress = 50,
                steps = listOf(ThinkingStep(type = "knowledge", name = "搜索 wenku8", status = "done", detail = "${webMatches.size} 条")),
                force = true
            )

            // 2. 番茄 API 搜索（仅按书名时调用，按作者时 API 无对应能力）
            val apiMatches = if (!byAuthor) {
                report(
                    content = "搜索番茄小说 API",
                    progress = 65,
                    steps = listOf(
                        ThinkingStep(type = "knowledge", name = "搜索 wenku8", status = "done", detail = "${webMatches.size} 条"),
                        ThinkingStep(type = "knowledge", name = "搜索番茄 API", status = "running")
                    ),
                    force = true
                )
                withContext(Dispatchers.IO) { localNovelClient.findFromApi(searchTerm) }
            } else {
                emptyList()
            }
            report(
                content = "生成搜索结果",
                progress = 85,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "搜索 wenku8", status = "done", detail = "${webMatches.size} 条"),
                    ThinkingStep(type = "knowledge", name = "搜索番茄 API", status = "done", detail = "${apiMatches.size} 条"),
                    ThinkingStep(type = "file", name = "生成 HTML", status = "running")
                ),
                force = true
            )

            if (webMatches.isEmpty() && apiMatches.isEmpty()) {
                val msg = "没有找到包含 '$searchTerm' 的轻小说喵~"
                report(
                    content = "搜索无结果",
                    progress = 100,
                    steps = listOf(ThinkingStep(type = "done", name = "无结果", status = "error", detail = msg)),
                    isComplete = true,
                    force = true
                )
                return msg
            }

            // 3. 去重（以标题为唯一键）
            val seenTitles = mutableSetOf<String>()
            val dedupWeb = webMatches.filter { seenTitles.add(it.title) }
            val dedupApi = apiMatches.filter { seenTitles.add(it.title) }

            // 4. 保存会话级搜索状态
            localNovelSearchStates[sessionId] = LocalNovelSearchState(dedupWeb, dedupApi)

            // 5. 生成 HTML 网格并写入工作区
            val title = if (byAuthor) {
                "$searchTerm · 作者搜索"
            } else {
                "$searchTerm · 轻小说搜索"
            }
            val html = buildLocalNovelGridHtml(title, dedupWeb)
            val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
            val novelDir = File(root, "novel")
            if (!novelDir.exists() && !novelDir.mkdirs()) {
                error("无法创建轻小说目录。")
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val safeName = searchTerm.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(20)
            val fileName = "search-${safeName}-$timestamp.html"
            withContext(Dispatchers.IO) {
                File(novelDir, fileName).writeText(html, Charsets.UTF_8)
            }

            val totalCount = dedupWeb.size + dedupApi.size
            report(
                content = "搜索完成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "搜索 wenku8", status = "done", detail = "${dedupWeb.size} 条"),
                    ThinkingStep(type = "knowledge", name = "搜索番茄 API", status = "done", detail = "${dedupApi.size} 条"),
                    ThinkingStep(type = "file", name = "HTML 已生成", status = "done", detail = "novel/$fileName")
                ),
                isComplete = true,
                force = true
            )
            "找到 $totalCount 本轻小说喵~ 点击卡片查看详情或使用 `/info 编号` 获取信息喵~" +
                "\n\n[File: novel/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "轻小说搜索已取消",
                    progress = currentProgress.get(),
                    steps = listOf(ThinkingStep(type = "done", name = "任务已取消", status = "error")),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "搜索轻小说失败，请稍后重试。"
            progressReporter?.update(
                content = "轻小说搜索失败",
                progress = currentProgress.get(),
                steps = listOf(ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            message
        }
    }

    /**
     * `/hotnovel <day|month> [数量]`：获取今日/本月热门榜单。
     *
     * 对齐原仓库 `handle_hotnovel`。
     */
    private suspend fun localNovelHotText(
        sessionId: String,
        rawArgs: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val period = try {
            val parts = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            parseLocalNovelRankingPeriod(parts.firstOrNull() ?: "")
        } catch (error: Exception) {
            val message = error.message ?: "格式：`/hotnovel <day|month> [数量]`"
            progressReporter?.update(
                content = "热门榜单参数错误",
                progress = 0,
                steps = listOf(ThinkingStep(type = "done", name = "参数校验失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            return message
        }
        val limit = parseLocalNovelHotLimit(rawArgs)
        val cookie = readWenku8Cookie()
        val userAgent = readWenku8UserAgent()
        val currentProgress = AtomicInteger(0)

        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        report(
            content = "获取 ${period.displayName}",
            progress = 5,
            steps = listOf(ThinkingStep(type = "knowledge", name = "获取榜单数据", status = "running", detail = period.displayName)),
            force = true
        )

        return try {
            val entries = withContext(Dispatchers.IO) {
                localNovelClient.fetchHotNovels(period, cookie, userAgent, limit)
            }
            report(
                content = "处理 ${period.displayName}",
                progress = 70,
                steps = listOf(ThinkingStep(type = "knowledge", name = "获取榜单数据", status = "done", detail = "${entries.size} 条")),
                force = true
            )
            if (entries.isEmpty()) {
                val msg = "没找到热门榜单喵，可能网页结构变了喵~"
                report(
                    content = "榜单为空",
                    progress = 100,
                    steps = listOf(ThinkingStep(type = "done", name = "无数据", status = "error", detail = msg)),
                    isComplete = true,
                    force = true
                )
                return msg
            }

            // 保存到会话级搜索状态（清空 API 部分，避免干扰 /select）
            localNovelSearchStates[sessionId] = LocalNovelSearchState(entries, emptyList())

            report(
                content = "生成榜单 HTML",
                progress = 90,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取榜单数据", status = "done", detail = "${entries.size} 条"),
                    ThinkingStep(type = "file", name = "生成 HTML", status = "running")
                ),
                force = true
            )
            val html = buildLocalNovelGridHtml("${period.displayName} · 轻小说排行", entries)
            val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
            val novelDir = File(root, "novel")
            if (!novelDir.exists() && !novelDir.mkdirs()) {
                error("无法创建轻小说目录。")
            }
            val periodName = period.name.lowercase(Locale.ROOT)
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val fileName = "hot-$periodName-$timestamp.html"
            withContext(Dispatchers.IO) {
                File(novelDir, fileName).writeText(html, Charsets.UTF_8)
            }
            report(
                content = "${period.displayName}已生成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取榜单数据", status = "done", detail = "${entries.size} 条"),
                    ThinkingStep(type = "file", name = "HTML 已生成", status = "done", detail = "novel/$fileName")
                ),
                isComplete = true,
                force = true
            )
            "✨ ${period.displayName}前 ${entries.size} 名喵~ 点击卡片查看详情或使用 `/info 编号` 获取信息喵~" +
                "\n\n[File: novel/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "${period.displayName}获取已取消",
                    progress = currentProgress.get(),
                    steps = listOf(ThinkingStep(type = "done", name = "任务已取消", status = "error")),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "获取 ${period.displayName}失败，请稍后重试。"
            progressReporter?.update(
                content = "${period.displayName}获取失败",
                progress = currentProgress.get(),
                steps = listOf(ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            message
        }
    }

    /**
     * `/random_novel`：从今日热门榜单随机取一本，生成详情 HTML。
     *
     * 对齐原仓库 `handle_random_novel`。
     */
    private suspend fun localNovelRandomText(
        sessionId: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val cookie = readWenku8Cookie()
        val userAgent = readWenku8UserAgent()
        val currentProgress = AtomicInteger(0)

        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        report(
            content = "随机推荐轻小说",
            progress = 5,
            steps = listOf(ThinkingStep(type = "knowledge", name = "获取热门榜单", status = "running")),
            force = true
        )

        return try {
            val book = withContext(Dispatchers.IO) {
                localNovelClient.fetchRandomFromHot(cookie, userAgent)
            }
            if (book == null) {
                val msg = "获取随机小说失败喵~请稍后再试~"
                report(
                    content = "随机推荐失败",
                    progress = 100,
                    steps = listOf(ThinkingStep(type = "done", name = "无数据", status = "error", detail = msg)),
                    isComplete = true,
                    force = true
                )
                return msg
            }
            report(
                content = "获取《${book.title}》详情",
                progress = 60,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取热门榜单", status = "done", detail = book.title),
                    ThinkingStep(type = "knowledge", name = "获取书籍详情", status = "running")
                ),
                force = true
            )
            // 尝试获取更详细的页面信息
            val detail = withContext(Dispatchers.IO) {
                localNovelClient.fetchBookDetail(book.id, cookie, userAgent) ?: book
            }
            report(
                content = "生成《${detail.title}》详情",
                progress = 90,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取热门榜单", status = "done", detail = book.title),
                    ThinkingStep(type = "knowledge", name = "获取书籍详情", status = "done"),
                    ThinkingStep(type = "file", name = "生成 HTML", status = "running")
                ),
                force = true
            )
            val html = buildLocalNovelDetailHtml(detail)
            val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
            val novelDir = File(root, "novel")
            if (!novelDir.exists() && !novelDir.mkdirs()) {
                error("无法创建轻小说目录。")
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val safeName = detail.title.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(20)
            val fileName = "random-$safeName-$timestamp.html"
            withContext(Dispatchers.IO) {
                File(novelDir, fileName).writeText(html, Charsets.UTF_8)
            }
            report(
                content = "随机推荐完成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取热门榜单", status = "done", detail = book.title),
                    ThinkingStep(type = "knowledge", name = "获取书籍详情", status = "done"),
                    ThinkingStep(type = "file", name = "HTML 已生成", status = "done", detail = "novel/$fileName")
                ),
                isComplete = true,
                force = true
            )
            "抽选到了《${detail.title}》喵~\n作者：${detail.author}\n字数：${detail.wordCount}\n状态：${detail.isSerialize}\n简介：${detail.introduction}" +
                "\n\n[File: novel/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "随机推荐已取消",
                    progress = currentProgress.get(),
                    steps = listOf(ThinkingStep(type = "done", name = "任务已取消", status = "error")),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "获取随机小说失败，请稍后重试。"
            progressReporter?.update(
                content = "随机推荐失败",
                progress = currentProgress.get(),
                steps = listOf(ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            message
        }
    }

    /**
     * `/info <编号>`：获取上次搜索结果中指定编号的书籍详情。
     *
     * 对齐原仓库 `handle_info`：
     * - wenku8 结果：抓取详情页 HTML
     * - API 结果：调用番茄 API 获取详情
     */
    private suspend fun localNovelInfoText(
        sessionId: String,
        rawArgs: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val state = localNovelSearchStates[sessionId]
        if (state == null || state.totalSize == 0) {
            val msg = "没有找到主人的搜索记录喵~请先使用 `/findbook` 搜索喵~"
            progressReporter?.update(
                content = "无搜索记录",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "无搜索记录", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }
        val selection = parseLocalNovelSelection(rawArgs)
        if (selection == null) {
            val msg = "请输入有效的编号喵~"
            progressReporter?.update(
                content = "编号无效",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "编号无效", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }
        val index = selection - 1
        if (index < 0 || index >= state.totalSize) {
            val msg = "编号无效喵~请选择列表中的编号喵~"
            progressReporter?.update(
                content = "编号越界",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "编号越界", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }

        val currentProgress = AtomicInteger(0)
        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        report(
            content = "获取书籍详情",
            progress = 10,
            steps = listOf(ThinkingStep(type = "knowledge", name = "获取详情", status = "running", detail = "编号 $selection")),
            force = true
        )

        return try {
            val cookie = readWenku8Cookie()
            val userAgent = readWenku8UserAgent()
            val isWebMatch = index < state.webMatches.size
            val book: LocalNovelBook = if (isWebMatch) {
                val webBook = state.webMatches[index]
                // 尝试从详情页获取更完整的信息，失败则回退到搜索结果
                withContext(Dispatchers.IO) {
                    localNovelClient.fetchBookDetail(webBook.id, cookie, userAgent) ?: webBook
                }
            } else {
                val apiIndex = index - state.webMatches.size
                val apiBook = state.apiMatches[apiIndex]
                withContext(Dispatchers.IO) {
                    localNovelClient.fetchApiBookInfo(apiBook.bookId) ?: LocalNovelBook(
                        id = apiBook.bookId,
                        title = apiBook.title,
                        author = "未知",
                        pageUrl = "https://fanqienovel.com/page/${apiBook.bookId}",
                        apiBookId = apiBook.bookId
                    )
                }
            }
            report(
                content = "生成《${book.title}》详情",
                progress = 85,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取详情", status = "done", detail = book.title),
                    ThinkingStep(type = "file", name = "生成 HTML", status = "running")
                ),
                force = true
            )
            val html = buildLocalNovelDetailHtml(book)
            val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
            val novelDir = File(root, "novel")
            if (!novelDir.exists() && !novelDir.mkdirs()) {
                error("无法创建轻小说目录。")
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val safeName = book.title.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(20)
            val fileName = "info-$safeName-$timestamp.html"
            withContext(Dispatchers.IO) {
                File(novelDir, fileName).writeText(html, Charsets.UTF_8)
            }
            report(
                content = "《${book.title}》详情已生成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(type = "knowledge", name = "获取详情", status = "done", detail = book.title),
                    ThinkingStep(type = "file", name = "HTML 已生成", status = "done", detail = "novel/$fileName")
                ),
                isComplete = true,
                force = true
            )
            "《${book.title}》的信息如下喵~\n作者：${book.author}\n分类：${book.category}\n字数：${book.wordCount}\n状态：${book.isSerialize}\n更新日期：${book.lastDate}\n简介：${book.introduction}" +
                "\n\n[File: novel/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "获取详情已取消",
                    progress = currentProgress.get(),
                    steps = listOf(ThinkingStep(type = "done", name = "任务已取消", status = "error")),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "获取书籍详情失败，请稍后重试。"
            progressReporter?.update(
                content = "获取详情失败",
                progress = currentProgress.get(),
                steps = listOf(ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            message
        }
    }

    /**
     * `/select <编号>`：选择上次搜索结果中的书籍。
     *
     * 对齐原仓库 `handle_select`：
     * - wenku8 结果：下载 TXT 并保存到工作区
     * - API 结果：下载 TXT 并保存到工作区
     */
    private suspend fun localNovelSelectText(
        sessionId: String,
        rawArgs: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val state = localNovelSearchStates[sessionId]
        if (state == null || state.totalSize == 0) {
            val msg = "没有找到主人的搜索记录喵~请先使用 `/findbook` 搜索喵~"
            progressReporter?.update(
                content = "无搜索记录",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "无搜索记录", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }
        val selection = parseLocalNovelSelection(rawArgs)
        if (selection == null) {
            val msg = "请输入有效的编号喵~"
            progressReporter?.update(
                content = "编号无效",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "编号无效", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }
        val index = selection - 1
        if (index < 0 || index >= state.totalSize) {
            val msg = "编号无效喵~请选择列表中的编号喵~"
            progressReporter?.update(
                content = "编号越界",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "编号越界", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }

        val isWebMatch = index < state.webMatches.size
        val currentProgress = AtomicInteger(0)
        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        return try {
            if (isWebMatch) {
                // wenku8 结果：下载 GBK TXT，转换为 UTF-8 后保存到工作区
                val book = state.webMatches[index]
                report(
                    content = "下载《${book.title}》",
                    progress = 20,
                    steps = listOf(ThinkingStep(type = "file", name = "下载 TXT", status = "running", detail = book.title)),
                    force = true
                )
                val bytes = withContext(Dispatchers.IO) {
                    localNovelClient.downloadWenku8Txt(book.id, readWenku8Cookie(), readWenku8UserAgent())
                }
                val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
                val novelDir = File(root, "novel")
                if (!novelDir.exists() && !novelDir.mkdirs()) {
                    error("无法创建轻小说目录。")
                }
                val safeName = book.title
                    .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
                    .trim('_')
                    .take(40)
                    .ifBlank { "wenku8-${book.id}" }
                val fileName = "$safeName.txt"
                withContext(Dispatchers.IO) {
                    val content = String(bytes, charset("GBK"))
                    File(novelDir, fileName).writeText(content, Charsets.UTF_8)
                }
                report(
                    content = "《${book.title}》下载完成",
                    progress = 100,
                    steps = listOf(
                        ThinkingStep(
                            type = "file",
                            name = "TXT 已保存",
                            status = "done",
                            detail = "novel/$fileName"
                        )
                    ),
                    isComplete = true,
                    force = true
                )
                "已下载《${book.title}》-- ${book.author}喵~\n\n[File: novel/$fileName]"
            } else {
                // API 结果：下载 TXT
                val apiIndex = index - state.webMatches.size
                val apiBook = state.apiMatches[apiIndex]
                report(
                    content = "下载《${apiBook.title}》",
                    progress = 20,
                    steps = listOf(ThinkingStep(type = "file", name = "下载 TXT", status = "running", detail = apiBook.title)),
                    force = true
                )
                val content = withContext(Dispatchers.IO) {
                    localNovelClient.downloadApiBook(apiBook.bookId)
                }
                if (content.isNullOrBlank()) {
                    val msg = "下载《${apiBook.title}》失败喵~请稍后再试~"
                    report(
                        content = "下载失败",
                        progress = 100,
                        steps = listOf(ThinkingStep(type = "done", name = "下载失败", status = "error", detail = msg)),
                        isComplete = true,
                        force = true
                    )
                    return msg
                }
                val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
                val novelDir = File(root, "novel")
                if (!novelDir.exists() && !novelDir.mkdirs()) {
                    error("无法创建轻小说目录。")
                }
                val safeName = apiBook.title.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(40)
                val fileName = "$safeName.txt"
                withContext(Dispatchers.IO) {
                    File(novelDir, fileName).writeText(content, Charsets.UTF_8)
                }
                report(
                    content = "《${apiBook.title}》下载完成",
                    progress = 100,
                    steps = listOf(ThinkingStep(type = "file", name = "TXT 已保存", status = "done", detail = "novel/$fileName")),
                    isComplete = true,
                    force = true
                )
                "已下载《${apiBook.title}》喵~\n\n[File: novel/$fileName]"
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "选择已取消",
                    progress = currentProgress.get(),
                    steps = listOf(ThinkingStep(type = "done", name = "任务已取消", status = "error")),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "选择轻小说失败，请稍后重试。"
            progressReporter?.update(
                content = "选择失败",
                progress = currentProgress.get(),
                steps = listOf(ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            message
        }
    }

    /**
     * `/novel_res <res值>`：根据 wenku8 书 ID 下载 TXT。
     *
     * 对齐原仓库 `handle_novel_by_res`：直接走 wenku8 下载接口。
     */
    private suspend fun localNovelResText(
        sessionId: String,
        rawArgs: String,
        progressReporter: LocalCommandProgressReporter?
    ): String {
        val resValue = parseLocalNovelRes(rawArgs)
        if (resValue.isNullOrBlank()) {
            val msg = "请输入要下载的 res 编号喵~例如：`/novel_res 1121`"
            progressReporter?.update(
                content = "参数为空",
                progress = 100,
                steps = listOf(ThinkingStep(type = "done", name = "参数错误", status = "error", detail = msg)),
                isComplete = true,
                force = true
            )
            return msg
        }
        val cookie = readWenku8Cookie()
        val userAgent = readWenku8UserAgent()
        val currentProgress = AtomicInteger(0)
        suspend fun report(
            content: String,
            progress: Int,
            steps: List<ThinkingStep>,
            isComplete: Boolean = false,
            force: Boolean = false
        ) {
            currentProgress.set(progress.coerceIn(0, 100))
            progressReporter?.update(content, progress, steps, isComplete, force)
        }

        report(
            content = "下载 res=$resValue",
            progress = 10,
            steps = listOf(ThinkingStep(type = "file", name = "下载 TXT", status = "running", detail = "bookId=$resValue")),
            force = true
        )

        return try {
            val bytes = withContext(Dispatchers.IO) {
                localNovelClient.downloadWenku8Txt(resValue, cookie, userAgent)
            }
            report(
                content = "保存 TXT 文件",
                progress = 85,
                steps = listOf(
                    ThinkingStep(type = "file", name = "下载 TXT", status = "done", detail = "${formatLocalFileSize(bytes.size.toLong())}"),
                    ThinkingStep(type = "file", name = "保存文件", status = "running")
                ),
                force = true
            )
            val root = localWorkspaceRoot(sessionId) ?: error("无法打开当前会话工作区。")
            val novelDir = File(root, "novel")
            if (!novelDir.exists() && !novelDir.mkdirs()) {
                error("无法创建轻小说目录。")
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val fileName = "wenku8-$resValue-$timestamp.txt"
            withContext(Dispatchers.IO) {
                File(novelDir, fileName).writeBytes(bytes)
            }
            report(
                content = "下载完成",
                progress = 100,
                steps = listOf(
                    ThinkingStep(type = "file", name = "下载 TXT", status = "done", detail = "${formatLocalFileSize(bytes.size.toLong())}"),
                    ThinkingStep(type = "file", name = "TXT 已保存", status = "done", detail = "novel/$fileName")
                ),
                isComplete = true,
                force = true
            )
            "已下载 res=$resValue 的轻小说喵~\n大小：${formatLocalFileSize(bytes.size.toLong())}" +
                "\n\n[File: novel/$fileName]"
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                progressReporter?.update(
                    content = "下载已取消",
                    progress = currentProgress.get(),
                    steps = listOf(ThinkingStep(type = "done", name = "任务已取消", status = "error")),
                    isComplete = true,
                    force = true
                )
            }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "下载 res=$resValue 失败，请稍后重试。"
            progressReporter?.update(
                content = "下载失败",
                progress = currentProgress.get(),
                steps = listOf(ThinkingStep(type = "done", name = "任务失败", status = "error", detail = message)),
                isComplete = true,
                force = true
            )
            message
        }
    }

    /**
     * `/set_wenku_cookie <Cookie>` 或 `/set_wenku_cookie <Cookie> || <User-Agent>`：更新 wenku8 Cookie。
     *
     * 对齐原仓库 `handle_set_wenku_cookie`，但不做管理员校验（本地模式无多用户概念）。
     *
     * 支持用 `||` 分隔 Cookie 和 User-Agent：
     * - `/set_wenku_cookie PHPSESSID=xxx; cf_clearance=yyy`
     * - `/set_wenku_cookie PHPSESSID=xxx; cf_clearance=yyy || Mozilla/5.0 ... Chrome/125.0.0.0`
     *
     * CloudFlare 的 cf_clearance 绑定获取时的 IP + UA，建议使用内置浏览器登录
     * （设置 → 轻小说 → wenku8 登录），自动保证 IP + UA + Cookie 三者一致，避免 403。
     */
    private fun localNovelSetCookieText(rawArgs: String): String {
        val trimmed = rawArgs.trim()
        if (trimmed.isEmpty()) {
            return "📖 wenku8 Cookie 设置喵~\n\n" +
                "推荐方式：设置 → 轻小说 → wenku8 登录\n" +
                "（内置浏览器登录，自动保存 Cookie + UA，避免 403）\n\n" +
                "手动方式（高级）：\n" +
                "/set_wenku_cookie <Cookie>\n" +
                "/set_wenku_cookie <Cookie> || <User-Agent>\n\n" +
                "提示：CloudFlare 的 cf_clearance 绑定 IP + UA，\n" +
                "手动设置时请确保与获取 Cookie 时的环境一致"
        }
        // 按 `||` 分隔 Cookie 和 User-Agent
        val parts = trimmed.split("||", limit = 2)
        val cookie = parts[0].trim()
        val userAgent = parts.getOrNull(1)?.trim().orEmpty()
        if (cookie.isEmpty()) return "请输入有效的 Cookie 喵~"
        writeWenku8Cookie(cookie)
        writeWenku8UserAgent(userAgent)
        return if (userAgent.isNotEmpty()) {
            "✅ Cookie + User-Agent 更新成功喵！\nUA: ${userAgent.take(80)}...\n现在可以尝试使用 `/hotnovel` 喵~"
        } else {
            "✅ Cookie 更新成功喵！现在可以尝试使用 `/hotnovel` 喵~\n" +
                "提示：如遇 403，建议使用 设置 → 轻小说 → wenku8 登录\n" +
                "（内置浏览器自动保存 Cookie + UA，避免 IP/UA 不匹配）"
        }
    }

    private fun localFortuneText(sessionId: String): String {
        val today = java.time.LocalDate.now()
        val seed = sessionId.hashCode().toLong() * 31L + today.toEpochDay()
        val score = java.util.Random(seed).nextInt(101)
        val label = when (score) {
            in 90..100 -> "大吉"
            in 75..89 -> "吉"
            in 55..74 -> "小吉"
            in 35..54 -> "平"
            in 15..34 -> "小凶"
            else -> "凶"
        }
        return "🔮 **${today} 今日运势：$label**\n\n幸运指数：**$score / 100**"
    }

    private fun formatLocalFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 ->
            String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        else ->
            String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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
     * @param model 用户配置的模型名称（LocalAiModelEntity.name），用于 Token 记录展示
     * @param actualModel 实际请求的模型标识（LocalAiModelEntity.model，如 gpt-4o），
     *                    用于排行榜按模型聚合（相同模型名、不同提供商可合并）。为空时回退到 [model]。
     * @param source 用量来源：chat（主对话）/ state（状态评估）/ memory（记忆抽取）/ plot（剧情）
     * @param purpose 用途标签（与 TokenStatsManager 常量对齐）：chat/utility/memory/plot 等
     * @param durationMs 完成耗时（毫秒），主对话路径从 AIPipeline 透传
     * @param ttftMs 首字延迟（毫秒），主对话路径从 AIPipeline 透传
     */
    private fun appendTokenUsageRecord(
        sessionId: String, model: String, actualModel: String = "",
        inputTokens: Int, outputTokens: Int, timestamp: String,
        source: String = "chat",
        purpose: String = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_CHAT,
        estimated: Boolean = false,
        messageId: String? = null,
        durationMs: Double? = null,
        ttftMs: Double? = null
    ) {
        val prefs = tokenUsagePrefs ?: return
        synchronized(tokenUsageLock) {
            val existing = prefs.getString("records", "[]") ?: "[]"
            try {
                val parsed = runCatching { JsonParser.parseString(existing).asJsonArray }
                val arr = parsed.getOrElse {
                    tokenUsageReconciled = false
                    LocalLogger.w("LocalRepo", "Token 用量记录损坏，已备份并重建: ${it.message}")
                    JsonArray()
                }
                val record = JsonObject().apply {
                    addProperty("id", UUID.randomUUID().toString())
                    messageId?.let { addProperty("message_id", it) }
                    addProperty("session_id", sessionId)
                    addProperty("model", model)
                    // 实际模型标识，用于排行榜按模型聚合（相同模型名、不同提供商可合并）
                    if (actualModel.isNotBlank()) addProperty("actual_model", actualModel)
                    addProperty("input_tokens", inputTokens)
                    addProperty("output_tokens", outputTokens)
                    addProperty("total_tokens", inputTokens + outputTokens)
                    addProperty("timestamp", timestamp)
                    addProperty("source", source)
                    addProperty("purpose", purpose)
                    addProperty("estimated", estimated)
                    // 完成耗时（毫秒），主对话路径来自 AIPipeline
                    durationMs?.let { addProperty("duration_ms", it) }
                    // 首字延迟（毫秒），主对话路径来自 AIPipeline
                    ttftMs?.let { addProperty("ttft_ms", it) }
                    // 提取日期部分（yyyy-MM-dd）用于按日聚合
                    addProperty("date", timestamp.substringBefore("T").substringBefore(" ").ifBlank { timestamp })
                }
                arr.add(record)
                // 限制最多 5000 条，超出则丢弃最早的
                while (arr.size() > 5000) arr.remove(0)
                val editor = prefs.edit().putString("records", arr.toString())
                if (parsed.isFailure) editor.putString("records_corrupt_backup", existing)
                if (!editor.commit()) {
                    tokenUsageReconciled = false
                    LocalLogger.w("LocalRepo", "Token 用量记录写入失败，稍后将从消息记录恢复")
                }
                // 成就触发：累计 token 消耗
                kotlin.runCatching {
                    val total = arr.sumOf { el ->
                        (el as? JsonObject)?.get("total_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    }
                    reportAchievementProgress(AchievementManager.Target.Metric.TOKENS, total)
                }
            } catch (e: Exception) {
                tokenUsageReconciled = false
                LocalLogger.w("LocalRepo", "Token 用量记录写入失败: ${e.message}")
            }
        }
    }

    /** 读取所有 token 用量记录 */
    private fun readTokenUsageRecords(): List<JsonObject> {
        val prefs = tokenUsagePrefs ?: return emptyList()
        return synchronized(tokenUsageLock) {
            val raw = prefs.getString("records", "[]") ?: "[]"
            runCatching { parseTokenUsageRecords(raw) }.getOrDefault(emptyList())
        }
    }

    /** 首次读取时以 Room 消息为事实来源，补回消息已保存但明细写入失败的记录。 */
    private suspend fun readTokenUsageRecordsReconciled(): List<JsonObject> {
        if (tokenUsageReconciled) return readTokenUsageRecords()

        val sessionCreatedAtById = sessionDao.listAll().associate { it.id to it.createdAt }
        val messages = messageDao.listAllAssistant().asReversed().mapNotNull { message ->
            val input = message.inputTokens ?: return@mapNotNull null
            val output = message.outputTokens ?: return@mapNotNull null
            if (input <= 0 && output <= 0) return@mapNotNull null
            LocalPersistedTokenMessage(
                id = message.id,
                sessionId = message.sessionId,
                model = message.model.orEmpty(),
                inputTokens = input,
                outputTokens = output,
                timestamp = message.timestamp.ifBlank { message.createdAt },
                createdAt = message.createdAt,
                sessionCreatedAt = sessionCreatedAtById[message.sessionId].orEmpty(),
                content = message.content
            )
        }
        val prefs = tokenUsagePrefs
            ?: return reconcileLocalTokenUsageRecords(emptyList(), messages).records.takeLast(5000)

        return synchronized(tokenUsageLock) {
            if (tokenUsageReconciled) {
                val raw = prefs.getString("records", "[]") ?: "[]"
                return@synchronized runCatching { parseTokenUsageRecords(raw) }.getOrDefault(emptyList())
            }

            val raw = prefs.getString("records", "[]") ?: "[]"
            val parsed = runCatching { parseTokenUsageRecords(raw) }
            val reconciliation = reconcileLocalTokenUsageRecords(
                records = parsed.getOrDefault(emptyList()),
                messages = messages
            )
            val normalized = reconciliation.records.takeLast(5000)
            val needsWrite = parsed.isFailure || reconciliation.changed ||
                normalized.size != reconciliation.records.size

            if (needsWrite) {
                val array = JsonArray().apply { normalized.forEach(::add) }
                val editor = prefs.edit().putString("records", array.toString())
                if (parsed.isFailure) editor.putString("records_corrupt_backup", raw)
                if (!editor.commit()) {
                    tokenUsageReconciled = false
                    LocalLogger.w("LocalRepo", "Token 用量修复写入失败，下次读取将重试")
                    return@synchronized normalized
                }
                if (reconciliation.recoveredCount > 0) {
                    LocalLogger.i("LocalRepo", "已从消息记录补回 ${reconciliation.recoveredCount} 条 Token 用量")
                }
            }
            tokenUsageReconciled = true
            normalized
        }
    }

    private fun parseTokenUsageRecords(raw: String): List<JsonObject> =
        JsonParser.parseString(raw).asJsonArray
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }

    private fun JsonObject.isInheritedTokenUsage(): Boolean = runCatching {
        get("inherited")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    }.getOrDefault(false)

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
        agentRunDao.deleteBySession(sessionId)
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
        activeModel: LocalAiModelEntity,
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort = com.nekobot.app.data.model.ReasoningEffort.NONE
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
        val history = listAiContextMessages(sessionId)
            .filter { it.role != "system" }
            .dropLast(1)  // 最后一条是刚保存的用户消息，会在 prompt 中单独处理
        val worldBookEntries = if (shouldInjectWorldBooks(session.sessionMode)) {
            loadWorldBookEntries(session.characterId)
        } else {
            emptyList()
        }

        // 3. 构造 prompt
        val messages = LocalPromptBuilder.build(
            session, character, history, userMessage, worldBookEntries
        )

        // 4. 构造 extra 参数
        val extra = buildMap<String, Any?> {
            activeModel.temperature?.let { put("temperature", it) }
            activeModel.maxTokens?.let { put("max_tokens", it) }
            activeModel.topP?.let { put("top_p", it) }
            put("reasoning_effort", reasoningEffort.wireValue)
        }

        // 5. 构造故障转移队列：activeModel 优先，附加同 purpose 其他启用模型
        val queue = routedChatQueue(sessionId, userMessage).let { routed ->
            if (routed.any { it.id == activeModel.id }) {
                listOf(activeModel) + routed.filter { it.id != activeModel.id }
            } else {
                listOf(activeModel) + routed
            }
        }

        // 6. 流式调用（带故障转移）
        val fullContent = StringBuilder()
        val fullReasoning = StringBuilder()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var modelName: String? = null
        var modelDisplayName: String? = null
        val streamStartNano = System.nanoTime()
        var firstChunkNano: Long? = null
        aiClient.chatStreamWithFailover(queue, messages, extra).collect { event ->
            when (event) {
                is RealtimeEvent.StreamChunk -> {
                    if (fullContent.isEmpty()) firstChunkNano = System.nanoTime()
                    fullContent.append(event.chunk)
                }
                is RealtimeEvent.ReasoningChunk -> {
                    if (reasoningEffort != com.nekobot.app.data.model.ReasoningEffort.NONE) {
                        fullReasoning.append(event.chunk)
                        emit(event)
                    }
                }
                is RealtimeEvent.Usage -> {
                    inputTokens = event.inputTokens
                    outputTokens = event.outputTokens
                    modelName = event.model
                    modelDisplayName = event.modelDisplayName
                    emit(event)
                }
                is RealtimeEvent.Error -> emit(event)
                is RealtimeEvent.StreamEnd -> {
                    // 保存 assistant 消息（含 token 用量）
                    val content = fullContent.toString().trim()
                    if (content.isNotEmpty()) {
                        val usage = resolveLocalTokenUsage(
                            usage = buildMap {
                                inputTokens?.let { put("prompt", it) }
                                outputTokens?.let { put("completion", it) }
                            },
                            messages = messages,
                            outputText = content
                        )
                        val durationMs = (System.nanoTime() - streamStartNano) / 1_000_000.0
                        val ttftMs = firstChunkNano?.let { (it - streamStartNano) / 1_000_000.0 }
                        addAssistantMessage(
                            sessionId,
                            content,
                            usage.inputTokens,
                            usage.outputTokens,
                            // 显示名优先用配置名；为空时回退到 activeModel.name
                            modelDisplayName ?: activeModel.name,
                            // 实际模型标识（用于排行榜聚合），回退到 activeModel.model
                            actualModel = modelName ?: activeModel.model,
                            usageEstimated = usage.estimated,
                            durationMs = durationMs,
                            ttftMs = ttftMs ?: durationMs,
                            reasoningContent = fullReasoning.toString()
                        )
                    }
                    emit(RealtimeEvent.StreamEnd(sessionId))
                }
                else -> emit(event)
            }
        }

        // 无角色普通会话走旧聊天链路，也必须执行与 Pipeline 相同的标题后处理。
        // 此前该路径只保存回复并发出 StreamEnd，开启 TTS 后又改为等待 ReplyPostProcessed，
        // 结果既不会自动命名，也永远不会启动本轮 TTS。
        if (fullContent.isNotBlank()) {
            try {
                val latestSession = sessionDao.getById(sessionId)
                val latestMessages = listAiContextMessages(sessionId)
                if (latestSession != null && latestMessages.isNotEmpty()) {
                    val newName = sessionNameGenerator.tryAutoName(
                        session = latestSession,
                        messages = latestMessages
                    )
                    if (newName != null) {
                        sessionDao.updateName(sessionId, newName, nowIso())
                        emit(RealtimeEvent.SessionRenamed(sessionId, newName))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalLogger.w(TAG, "普通会话自动命名失败（不影响主流程）: ${e.message}", e)
            }
        }
        emit(
            RealtimeEvent.ReplyPostProcessed(
                sessionId,
                listOf(fullContent.toString()).filter(String::isNotBlank)
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 重新生成：删除从 [messageId] 开始的所有消息，然后用上一条 user 消息重新请求。
     * 若 [messageId] 为空，默认删除最后一条 assistant 消息。
     */
    fun regenerate(
        sessionId: String,
        messageId: String?,
        activeModel: LocalAiModelEntity,
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort = com.nekobot.app.data.model.ReasoningEffort.NONE
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
        chat(sessionId, userInput, activeModel, reasoningEffort).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * 从最后一个 Agent 安全检查点继续；若尚无已完成工具批次，则复用原用户消息重新执行。
     *
     * checkpoint 不为空时通过现有“继续”协议恢复，不重放已经落入 checkpoint 的工具结果。
     */
    suspend fun resumeAgentRun(
        sessionId: String,
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort =
            com.nekobot.app.data.model.ReasoningEffort.NONE
    ): Flow<RealtimeEvent>? = withContext(Dispatchers.IO) {
        val run = agentRunDao.getBySession(sessionId) ?: return@withContext null
        val session = sessionDao.getById(sessionId) ?: return@withContext null
        if (!session.sessionMode.equals("agent", ignoreCase = true)) {
            agentRunDao.deleteBySession(sessionId)
            return@withContext null
        }
        val originalUser = messageDao.listBySession(sessionId)
            .firstOrNull { it.id == run.userMessageId && it.role.equals("user", ignoreCase = true) }
            ?: throw IllegalStateException("原任务消息已不存在，无法恢复")
        val attachments = decodeAgentRunAttachments(run.attachmentsJson)
        val model = getRoutedModel(sessionId, run.prompt, attachments) ?: return@withContext null

        if (!run.checkpointHistory.isNullOrBlank()) {
            val existingMarker = run.assistantMessageId?.let { markerId ->
                messageDao.listBySession(sessionId).firstOrNull { it.id == markerId }
            }
            if (existingMarker?.toolCallHistory.isNullOrBlank()) {
                existingMarker?.let { messageDao.deleteById(it.id) }
                val now = nowIso()
                val marker = LocalMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    role = "assistant",
                    content = "【上次 Agent 执行被中断，已保存安全检查点】",
                    sender = "assistant",
                    timestamp = now,
                    createdAt = now,
                    toolCallHistory = run.checkpointHistory,
                    source = "agent_recovery"
                )
                messageDao.upsert(marker)
                agentRunDao.updateAssistantMessageId(
                    sessionId = sessionId,
                    runId = run.runId,
                    messageId = marker.id,
                    updatedAt = now
                )
                sessionDao.touch(
                    sessionId,
                    marker.content,
                    messageDao.countBySession(sessionId),
                    now
                )
            }
            return@withContext chatWithPipeline(
                sessionId = sessionId,
                userMessage = "继续",
                activeModel = model,
                reasoningEffort = reasoningEffort
            )
        }

        // 尚未到达首个工具安全边界：删除本轮失败/停止占位回复，复用原用户消息，避免重复插入。
        run.assistantMessageId?.let { messageDao.deleteById(it) }
        chatWithPipeline(
            sessionId = sessionId,
            userMessage = run.prompt.ifBlank { originalUser.content },
            activeModel = model,
            attachments = attachments,
            persistUserMessage = false,
            existingParentMessageId = originalUser.id,
            reasoningEffort = reasoningEffort
        )
    }

    suspend fun discardAgentRun(sessionId: String) = withContext(Dispatchers.IO) {
        agentRunDao.deleteBySession(sessionId)
    }

    private fun decodeAgentRunAttachments(raw: String?): List<Map<String, Any>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            gson.fromJson<List<Map<String, Any>>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 停止生成：同时中断模型请求、工具调用、命令进程和授权等待。 */
    fun stopGeneration(sessionId: String? = null) {
        val now = nowIso()
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            if (sessionId == null) agentRunDao.pauseAllRunning(now)
            else agentRunDao.pauseRunning(sessionId, now)
        }
        val sessionIds = sessionId
            ?.let(::listOf)
            ?: activeGenerations.keys.toList()
        sessionIds.forEach { id ->
            activeGenerations[id]?.requestStop()
            LocalLinuxSandboxCoordinator.stopSession(id)
            localExecAuthorizationManager.cancelSession(id)
            aiClient.cancelRequests(id)
            localMcpRuntime.cancelActiveToolCall(id)
        }
        if (sessionId == null) {
            localMcpRuntime.cancelActiveToolCall()
            currentChatJob?.cancel()
            currentChatJob = null
        }
    }

    /** 释放本地仓库持有的长连接与 stdio 子进程。 */
    fun close() {
        stopGeneration()
        localMcpRuntime.close()
        localBrowserTools.values.forEach(LocalBrowserTool::close)
        localBrowserTools.clear()
        LocalLinuxSandboxCoordinator.closeAll()
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
        attachments: List<Map<String, Any>> = emptyList(),
        persistUserMessage: Boolean = true,
        existingParentMessageId: String? = null,
        internalMetadata: Map<String, Any> = emptyMap(),
        assistantSource: String? = null,
        allowTools: Boolean = true,
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort = com.nekobot.app.data.model.ReasoningEffort.NONE
    ): Flow<RealtimeEvent> = flow {
        val generationController = LocalGenerationController()
        var agentForegroundStarted = false
        activeGenerations.put(sessionId, generationController)?.requestStop()
        try {
        // 标记当前会话，供二级 LLM 调用（AutoState/记忆）token 记账归属
        currentSessionId = sessionId

        // 1. 保存用户消息
        val savedUserMessage = if (persistUserMessage) {
            addMessage(sessionId, "user", userMessage)
        } else {
            null
        }
        val parentMessageId = savedUserMessage?.id ?: existingParentMessageId

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
                model = LOCAL_COMMAND_MODEL
            )
            localCommandCompletionEvents(sessionId, reply).forEach { emit(it) }
            return@flow
        }

        if (persistUserMessage) LocalSlashCommands.parse(userMessage)?.let { command ->
            val commandParentId = parentMessageId ?: java.util.UUID.randomUUID().toString()
            if (
                command.action == LocalCommandAction.JM_RANK ||
                command.action == LocalCommandAction.JM_DOWNLOAD
            ) {
                // Room 持久化不会在命令执行期间自动刷新 ChatViewModel。
                // 用 channelFlow 把并发封面/章节任务的卡片安全地实时推给聊天界面。
                channelFlow {
                    val reply = executeLocalSlashCommand(
                        session = session,
                        activeModel = activeModel,
                        command = command,
                        parentMessageId = commandParentId,
                        onProgress = { card ->
                            send(RealtimeEvent.ThinkingCardUpdate(card))
                        }
                    )
                    localCommandCompletionEvents(sessionId, reply).forEach { send(it) }
                }.collect { event -> emit(event) }
            } else {
                val reply = executeLocalSlashCommand(
                    session = session,
                    activeModel = activeModel,
                    command = command,
                    parentMessageId = commandParentId
                )
                localCommandCompletionEvents(sessionId, reply).forEach { emit(it) }
            }
            return@flow
        }

        // 群聊会话没有单一 character_id：按 group_config 调度一个或多个角色，
        // 每个角色使用自己的 CharacterRuntime/角色卡执行同一条共享消息历史。
        if (session.sessionMode.equals("group", ignoreCase = true)) {
            chatGroupWithPipeline(
                session = session,
                userMessage = userMessage,
                activeModel = activeModel,
                attachments = attachments,
                parentMessageId = parentMessageId ?: java.util.UUID.randomUUID().toString(),
                generationController = generationController,
                reasoningEffort = reasoningEffort
            ).collect { emit(it) }
            return@flow
        }

        val character = session.characterId?.let { characterDao.getById(it) }

        // 普通无角色会话沿用旧流程；Agent 无角色会话需要进入 Pipeline 产生进度卡片。
        // 图片附件必须经过 Pipeline 的附件解析与视觉描述阶段；普通无角色会话也不能回退旧聊天链路。
        if (
            persistUserMessage &&
            !shouldUseLocalPipeline(session.sessionMode, character != null, attachments.isNotEmpty())
        ) {
            // 回退时需删除刚保存的用户消息（chat 会重新保存）
            messageDao.listBySession(sessionId).lastOrNull { it.role == "user" }?.let {
                messageDao.deleteById(it.id)
            }
            chat(sessionId, userMessage, activeModel, reasoningEffort).collect { emit(it) }
            return@flow
        }

        if (allowTools && session.sessionMode.equals("agent", ignoreCase = true)) {
            appContext?.let { context ->
                agentForegroundStarted = runCatching {
                    AgentForegroundService.acquire(context, sessionId)
                }.isSuccess
            }
        }

        val agentRunId = if (
            allowTools &&
            assistantSource == null &&
            session.sessionMode.equals("agent", ignoreCase = true) &&
            !parentMessageId.isNullOrBlank()
        ) {
            val runId = UUID.randomUUID().toString()
            val now = nowIso()
            agentRunDao.upsert(
                LocalAgentRunEntity(
                    sessionId = sessionId,
                    runId = runId,
                    userMessageId = parentMessageId,
                    prompt = userMessage,
                    attachmentsJson = attachments.takeIf { it.isNotEmpty() }
                        ?.let(gson::toJson),
                    status = AgentRunStatus.RUNNING,
                    stage = AgentRunStage.PREPARING,
                    checkpointHistory = null,
                    completedToolCalls = 0,
                    lastToolName = null,
                    lastError = null,
                    assistantMessageId = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
            runId
        } else {
            null
        }

        // 2. Agent 是通用工具会话，不注入角色世界书；无角色时也不能加载全部公共世界书。
        val worldBookEntries = if (shouldInjectWorldBooks(session.sessionMode)) {
            loadWorldBookEntries(session.characterId)
        } else {
            emptyList()
        }

        // 3. 使用成员变量（跨轮次保持 turnCounters 状态）
        val runtime = character?.let { characterRuntime }

        val identity = character?.let {
            com.nekobot.app.data.local.ai.CharacterIdentity(
                characterId = it.id,
                targetId = "local-user",
                scopeId = sessionId,
                relationshipTargetId = sessionRelationshipTargetId(sessionId),
                channel = "local"
            )
        }

        // 4. 构建回调（传入故障转移备选队列：同 purpose 其他启用模型 + 持久化协调器）
        val failoverQueue = routedChatQueue(
            sessionId = sessionId,
            prompt = userMessage,
            attachments = attachments,
            sessionModeOverride = session.sessionMode
        )
            .filter { it.id != activeModel.id }
        val callbacks = com.nekobot.app.data.local.ai.LocalPipelineCallbacks(
            db, aiClient, activeModel, session, character, worldBookEntries, runtime, identity,
            parentMessageId = parentMessageId,
            assistantSource = assistantSource,
            knowledgeSearcher = { query ->
                kotlinx.coroutines.runBlocking {
                    knowledgeManager.searchPrompt(
                        query = query,
                        sessionId = sessionId,
                        characterId = character?.id
                    )
                }
            },
            onTokenRecorded = { sid, messageId, model, actualModel, input, output, ts, purpose, estimated, durationMs, ttftMs ->
                appendTokenUsageRecord(
                    sid, model, actualModel, input, output, ts,
                    purpose = purpose,
                    estimated = estimated,
                    messageId = messageId,
                    durationMs = durationMs,
                    ttftMs = ttftMs
                )
            },
            onThinkingCardUpdate = { card ->
                // 持久化进度卡片到父用户消息
                if (parentMessageId != null) kotlinx.coroutines.runBlocking {
                    updateMessageThinkingCards(parentMessageId, listOf(card))
                }
            },
            workspaceRoot = appContext?.filesDir
                ?.let { LocalWorkspaceStorage.resolve(it, sessionId) },
            sharedWorkspaceRoot = appContext?.filesDir
                ?.let { LocalWorkspaceStorage.resolveShared(it) },
            execAuthorizationManager = localExecAuthorizationManager,
            mcpToolExecutor = { toolName, args ->
                localMcpRuntime.executeByFullName(toolName, args, session.id)
            },
            skillToolExecutor = ::executeLocalSkillTool,
            browserToolExecutor = { args -> executeLocalBrowserTool(session.id, args) },
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
            generationController = generationController,
            agentRunId = agentRunId,
            reasoningEffort = reasoningEffort,
            execConfirmationEmitter = { request -> _execConfirmationEvents.tryEmit(request) }
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
                put("reasoning_effort", reasoningEffort.wireValue)
                if (disabledKeys.isNotEmpty()) put("disabled_prompt_keys", disabledKeys)
                // senderName 是 AI 扮演的角色名；userPersona 是本会话玩家身份描述（含姓名/背景）。
                // userPersona 供 AIPipeline 注入 PromptStack 的 user.persona 项、AutoMemory 识别玩家姓名。
                session.senderName?.takeIf { it.isNotBlank() }?.let { put("sender_name", it) }
                session.userPersona?.takeIf { it.isNotBlank() }?.let { put("user_persona", it) }
                putAll(internalMetadata)
            }
        )
        val ctx = com.nekobot.app.data.local.ai.PipelineContext(chatRequest)
        ctx.stopRequested = { generationController.isStopped }
        ctx.metadata["session_mode"] = session.sessionMode
        ctx.metadata.putAll(internalMetadata)
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
        // senderName（角色名）/ userPersona（玩家身份描述）同步到 ctx.metadata。
        // AIPipeline 读 user_persona 注入 PromptStack；AutoMemory 读 user_persona 识别玩家姓名。
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

        // === life_sim 懒触发（对齐原仓库静默心跳）===
        // plot_mode + plot_realtime_sync 开启且非 agent 模式时，
        // 检查距离上次 life_sim 生成的时间，超过阈值则补生成一次写入 MemoryFS。
        // CharacterRuntime.beforeTurn 会从 MemoryFS 读取 life_sim 注入到 prompt。
        if (session.plotMode && session.plotRealTimeSync &&
            !session.sessionMode.equals("agent", ignoreCase = true) && character != null) {
            try {
                val stateRepo = com.nekobot.app.data.local.ai.LocalCharacterStateRepository(db.characterStateDao())
                val state = stateRepo.get(character.id, sessionId)
                val lastRun = (state?.scene?.get("life_sim_last_run") as? String)

                if (com.nekobot.app.data.local.ai.LifeSimulator.shouldTrigger(lastRun)) {
                    // 构建角色卡文本
                    val profileText = buildString {
                        append("【角色名】${character.name}")
                        character.description?.takeIf { it.isNotBlank() }?.let { append("\n【描述】$it") }
                        character.personality?.takeIf { it.isNotBlank() }?.let { append("\n【性格】$it") }
                        character.scenario?.takeIf { it.isNotBlank() }?.let { append("\n【场景】$it") }
                        character.systemPrompt?.takeIf { it.isNotBlank() }?.let { append("\n【系统提示词】$it") }
                    }

                    // 收集最近用户消息（仅用于了解用户身份）
                    val recentMessages = listAiContextMessages(sessionId)
                        .filter { it.role == "user" }
                        .takeLast(5)
                        .map { it.content }

                    // 昼夜状态
                    val circadianState = com.nekobot.app.data.local.ai.TimeContext.buildCircadianState()

                    // 生成并持久化
                    val activity = com.nekobot.app.data.local.ai.LifeSimulator.generateAndPersist(
                        aiClient = aiClient,
                        activeModel = activeModel,
                        failoverExecutor = chatFailoverExecutor,
                        memoryDao = db.memoryDao(),
                        characterId = character.id,
                        conversationId = sessionId,
                        targetId = "local-user",
                        profileText = profileText,
                        circadianState = circadianState,
                        recentMessages = recentMessages
                    ) { input, output, model, actualModel ->
                        appendTokenUsageRecord(
                            sessionId = sessionId,
                            model = model,
                            actualModel = actualModel,
                            inputTokens = input,
                            outputTokens = output,
                            timestamp = nowIsoTimestamp(),
                            source = "web",
                            purpose = com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_HEARTBEAT
                        )
                    }

                    // 更新 CharacterState.scene（life_sim_last_run + current_activity）
                    val currentState = state ?: com.nekobot.app.data.local.ai.CharacterState(
                        characterId = character.id,
                        scopeId = sessionId
                    )
                    val newScene = currentState.scene.toMutableMap().apply {
                        put("life_sim_last_run", java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                        if (activity.isNotBlank()) {
                            put("current_activity", activity)
                            put("activity_source", "heartbeat_ai")
                        }
                    }
                    currentState.scene = newScene
                    stateRepo.save(currentState)

                    com.nekobot.app.data.local.LocalLogger.i(TAG, "life_sim 懒触发完成 | activity=$activity")
                }
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "life_sim 懒触发异常: ${e.message}", e)
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
                        val progressReporter = if (
                            session.sessionMode.equals("agent", ignoreCase = true)
                        ) {
                            callbacks.getProgressReporter(ctx).also { it.onPreparingStart(ctx) }
                        } else {
                            null
                        }
                        val tools = if (allowTools && session.sessionMode.equals("agent", ignoreCase = true)) {
                            com.nekobot.app.data.local.ai.buildLocalAgentToolDefinitions() +
                                com.nekobot.app.data.local.ai.buildLocalSkillToolDefinitions() +
                                com.nekobot.app.data.local.ai.buildLocalDbToolDefinitions() +
                                prepareMcpAgentTools()
                        } else {
                            emptyList()
                        }
                        com.nekobot.app.data.local.ai.aiPipeline.process(
                            ctx = ctx,
                            callbacks = callbacks,
                            tools = tools,
                            progressReporter = progressReporter
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
                    val latestMessages = listAiContextMessages(sessionId)
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "会话自动命名失败（不影响主流程）: ${e.message}", e)
                }
            }
            // TTS 必须等标题总结尝试结束后再启动，避免两个模型请求并发争用导致标题丢失。
            emit(
                RealtimeEvent.ReplyPostProcessed(
                    sessionId,
                    listOf(ctx.finalContent).filter(String::isNotBlank)
                )
            )

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
                        ?: if (persistUserMessage) {
                            userMessage.replace('\n', ' ').take(80).ifBlank { "剧情节点" }
                        } else {
                            "主动聊天"
                        },
                    summary = ctx.finalContent.take(500),
                    level = selectedChoice?.level ?: "normal",
                    scene = turn?.state?.scene ?: emptyMap(),
                    stateSnapshot = turn?.state?.toDict() ?: emptyMap(),
                    relationshipSnapshot = turn?.relationship?.toDict() ?: emptyMap(),
                    parentNodeId = parent?.id,
                    userMessage = if (persistUserMessage) userMessage else "",
                    assistantMessage = ctx.finalContent,
                    activityType = if (persistUserMessage) {
                        turn?.state?.scene?.get("current_activity") as? String ?: "chat"
                    } else {
                        "proactive_chat"
                    },
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "保存 prompt_stack_debug 失败: ${e.message}")
                }
            }
            val composedPrompt = ctx.metadata["composed_system_prompt"] as? String
            if (!composedPrompt.isNullOrBlank()) {
                try {
                    sessionDao.updateComposedSystemPrompt(sessionId, composedPrompt)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "保存 composed_system_prompt 失败: ${e.message}")
                }
            }

            // Phase 6: 剧情模式 → 生成选项
            if (!generationController.isStopped && session.plotMode) {
                try {
                    val plotGen = com.nekobot.app.data.local.ai.PlotChoiceGenerator(
                        aiClient = aiClient,
                        aiModelProvider = { aiModelDao.getActive() },
                        failoverExecutor = chatFailoverExecutor,
                        onTokenUsage = ::recordPlotTokenUsage
                    )
                    val recentHistory = listAiContextMessages(sessionId)
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项生成失败（不影响主流程）: ${e.message}", e)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(RealtimeEvent.Error(e.message ?: "Pipeline 执行失败"))
            emit(RealtimeEvent.StreamEnd(sessionId))
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(RealtimeEvent.Error(e.message ?: "本地聊天失败"))
            emit(RealtimeEvent.StreamEnd(sessionId))
        } finally {
            if (agentForegroundStarted) {
                appContext?.let { AgentForegroundService.release(it, sessionId) }
            }
            activeGenerations.remove(sessionId, generationController)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 本地群聊一轮处理。
     *
     * 对齐原仓库 Web 群聊路径：持久化 GroupConfig/成员，按策略选择发言者；round_robin
     * 一轮内让所有角色各回复一次，并顺序执行，使后发言角色能看到前面角色刚落库的消息。
     */
    private fun chatGroupWithPipeline(
        session: LocalSessionEntity,
        userMessage: String,
        activeModel: LocalAiModelEntity,
        attachments: List<Map<String, Any>>,
        parentMessageId: String,
        generationController: LocalGenerationController,
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort
    ): Flow<RealtimeEvent> = flow {
        val characterIds = session.characterIds?.let { json ->
            runCatching {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type)
            }.getOrNull()
        }.orEmpty().filter { it.isNotBlank() }.distinct()

        val charactersById = characterIds.mapNotNull { id ->
            characterDao.getById(id)?.let { id to it }
        }.toMap(LinkedHashMap())

        if (charactersById.isEmpty()) {
            emit(RealtimeEvent.Error("群聊没有可用角色，请重新创建群聊并至少选择一个角色"))
            emit(RealtimeEvent.StreamEnd(session.id))
            return@flow
        }

        val participants = charactersById.values.map { character ->
            val relationship = db.relationshipDao().get(character.id, "local-user")
                ?.let { entity ->
                    runCatching {
                        com.nekobot.app.data.local.ai.RelationshipState.fromJson(entity.dataJson)
                    }.getOrNull()
                }
            com.nekobot.app.data.local.ai.LocalGroupParticipant(
                id = character.id,
                name = character.name,
                description = character.description.orEmpty(),
                personality = character.personality.orEmpty(),
                relationWeight = relationship?.let {
                    it.affection + it.trust * 0.8 + it.familiarity * 0.5
                }
            )
        }
        val groupConfig = com.nekobot.app.data.local.ai.LocalGroupConfig.fromJson(session.groupConfig)
        val speakers = com.nekobot.app.data.local.ai.LocalGroupChat.selectSpeakers(
            config = groupConfig,
            message = userMessage,
            participants = participants,
            lastSpeakerId = session.groupActiveSpeaker
        )
        if (speakers.isEmpty()) {
            emit(RealtimeEvent.Error("未能选择群聊发言角色"))
            emit(RealtimeEvent.StreamEnd(session.id))
            return@flow
        }

        val disabledKeys = session.disabledPromptKeys
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val customPrompts = session.customPrompts?.let { raw ->
            runCatching {
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                gson.fromJson<List<Map<String, Any>>>(raw, type)
            }.getOrNull()
        }.orEmpty()
        val failoverQueue = routedChatQueue(
            sessionId = session.id,
            prompt = userMessage,
            attachments = attachments,
            sessionModeOverride = session.sessionMode
        )
            .filter { it.id != activeModel.id }

        var lastCompletedSpeaker: com.nekobot.app.data.local.ai.LocalGroupParticipant? = null
        var lastContext: com.nekobot.app.data.local.ai.PipelineContext? = null
        val completedResponseContents = mutableListOf<String>()
        val roundResponses = mutableListOf<com.nekobot.app.data.local.ai.LocalGroupRoundResponse>()
        val scheduledSpeakers = speakers.toMutableList()
        val initialSpeakerCount = speakers.size

        var index = 0
        while (index < scheduledSpeakers.size) {
            val speaker = scheduledSpeakers[index]
            if (generationController.isStopped) break
            val character = charactersById[speaker.id]
            if (character == null) {
                index++
                continue
            }
            val isCrossTalk = index >= initialSpeakerCount
            val callbacks = com.nekobot.app.data.local.ai.LocalPipelineCallbacks(
                db = db,
                aiClient = aiClient,
                activeModel = activeModel,
                session = session,
                character = character,
                worldBookEntries = loadWorldBookEntries(character.id),
                characterRuntime = characterRuntime,
                characterIdentity = com.nekobot.app.data.local.ai.CharacterIdentity(
                    characterId = character.id,
                    targetId = "local-user",
                    scopeId = session.id,
                    channel = "local"
                ),
                parentMessageId = parentMessageId,
                knowledgeSearcher = { query ->
                    kotlinx.coroutines.runBlocking {
                        knowledgeManager.searchPrompt(
                            query = query,
                            sessionId = session.id,
                            characterId = character.id
                        )
                    }
                },
                onTokenRecorded = { sid, messageId, model, actualModel, input, output, ts, purpose, estimated, durationMs, ttftMs ->
                    appendTokenUsageRecord(
                        sid, model, actualModel, input, output, ts,
                        purpose = purpose,
                        estimated = estimated,
                        messageId = messageId,
                        durationMs = durationMs,
                        ttftMs = ttftMs
                    )
                },
                workspaceRoot = appContext?.filesDir
                    ?.let { LocalWorkspaceStorage.resolve(it, session.id) },
                sharedWorkspaceRoot = appContext?.filesDir
                    ?.let { LocalWorkspaceStorage.resolveShared(it) },
                execAuthorizationManager = localExecAuthorizationManager,
                mcpToolExecutor = { toolName, args ->
                    localMcpRuntime.executeByFullName(toolName, args, session.id)
                },
                skillToolExecutor = ::executeLocalSkillTool,
                failoverQueue = failoverQueue,
                coordinator = failoverCoordinator,
                hookExecutor = hookExecutor,
                visionDescriber = { imageUrl, question ->
                    describeImageViaQueue(
                        imageUrl = imageUrl,
                        question = question,
                        requestTag = session.id,
                        shouldStop = { generationController.isStopped }
                    )
                },
                generationController = generationController,
                reasoningEffort = reasoningEffort,
                execConfirmationEmitter = { request -> _execConfirmationEvents.tryEmit(request) }
            )

            val metadata = buildMap<String, Any> {
                put("session_mode", "group")
                put("group_id", session.groupId ?: session.id)
                put("group_speaker", speaker.id)
                put("group_speaker_name", speaker.name)
                put(
                    "group_round_complete",
                    if (isCrossTalk) index == scheduledSpeakers.lastIndex else index == initialSpeakerCount - 1
                )
                if (isCrossTalk) put("cross_talk_triggered", true)
                put("auto_state_interval", session.autoStateInterval)
                put("reasoning_effort", reasoningEffort.wireValue)
                if (disabledKeys.isNotEmpty()) put("disabled_prompt_keys", disabledKeys)
                if (customPrompts.isNotEmpty()) put("custom_prompts", customPrompts)
                session.userPersona?.takeIf { it.isNotBlank() }?.let { put("user_persona", it) }
            }
            val request = com.nekobot.app.data.local.ai.ChatRequest.forLocal(
                sessionId = session.id,
                content = userMessage,
                userId = "local-user",
                attachments = attachments,
                metadata = metadata
            )
            val ctx = com.nekobot.app.data.local.ai.PipelineContext(request).apply {
                stopRequested = { generationController.isStopped }
                this.metadata.putAll(metadata)
                promptStack.add(
                    key = "group.scene",
                    content = com.nekobot.app.data.local.ai.LocalGroupChat.buildSystemPrompt(
                        groupName = session.name,
                        participants = participants,
                        speaker = speaker
                    ),
                    priority = 85,
                    scope = "turn"
                )
            }

            coroutineScope {
                val pipelineJob = launch {
                    try {
                        com.nekobot.app.data.local.ai.aiPipeline.process(
                            ctx = ctx,
                            callbacks = callbacks,
                            tools = emptyList()
                        )
                    } finally {
                        callbacks.eventChannel.close()
                    }
                }
                for (event in callbacks.eventChannel) {
                    // 群聊一轮可能有多名角色；单个角色结束只负责落下该气泡，
                    // 不能提前释放整轮发送状态。全部角色结束后再单独发 ForegroundComplete。
                    val groupEvent = when (event) {
                        is RealtimeEvent.NewMessage -> event.copy(completesForeground = false)
                        is RealtimeEvent.StreamEnd -> event.copy(completesForeground = false)
                        is RealtimeEvent.AiResponse -> event.copy(completesForeground = false)
                        is RealtimeEvent.Filtered -> event.copy(completesForeground = false)
                        is RealtimeEvent.Error -> event.copy(completesForeground = false)
                        else -> event
                    }
                    emit(groupEvent)
                }
                pipelineJob.join()
            }

            if (!isCrossTalk && ctx.finalContent.isNotBlank()) {
                roundResponses += com.nekobot.app.data.local.ai.LocalGroupRoundResponse(
                    speakerId = speaker.id,
                    speakerName = speaker.name,
                    content = ctx.finalContent
                )
            }
            if (
                index == initialSpeakerCount - 1 &&
                groupConfig.allowCharacterCrossTalk &&
                !generationController.isStopped
            ) {
                scheduledSpeakers += com.nekobot.app.data.local.ai.LocalGroupChat.collectCrossTalkSpeakers(
                    responses = roundResponses,
                    participants = participants,
                    maxMentions = groupConfig.crossTalkMaxMentions
                )
            }
            lastCompletedSpeaker = speaker
            lastContext = ctx
            ctx.finalContent.takeIf(String::isNotBlank)?.let(completedResponseContents::add)
            index++
        }

        // 所有可见回复均已完成。后续轮次推进、调试信息保存、自动命名和 TTS 交接
        // 都属于后台收尾，不再让输入框显示“AI 正在回复”。
        emit(RealtimeEvent.ForegroundComplete(session.id))

        if (lastCompletedSpeaker != null && !generationController.isStopped) {
            sessionDao.advanceGroupTurn(session.id, lastCompletedSpeaker.id, nowIso())
        }

        // 与单角色会话一致：保存最后一名发言角色实际使用的完整提示词栈，供详情页查看。
        lastContext?.let { ctx ->
            ctx.metadata["prompt_stack_debug"]?.let { stackDebug ->
                try {
                    sessionDao.updatePromptStackDebug(session.id, gson.toJson(stackDebug))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LocalLogger.w(TAG, "保存群聊 prompt_stack_debug 失败: ${e.message}")
                }
            }
            (ctx.metadata["composed_system_prompt"] as? String)?.takeIf { it.isNotBlank() }?.let { prompt ->
                try {
                    sessionDao.updateComposedSystemPrompt(session.id, prompt)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LocalLogger.w(TAG, "保存群聊 composed_system_prompt 失败: ${e.message}")
                }
            }
        }

        // 群聊同样只在一轮全部结束后尝试自动命名一次。
        if (!generationController.isStopped && lastContext?.finalContent?.isNotBlank() == true) {
            try {
                val latestSession = sessionDao.getById(session.id)
                if (latestSession != null) {
                    val latestMessages = listAiContextMessages(session.id)
                    val newName = sessionNameGenerator.tryAutoName(
                        session = latestSession,
                        messages = latestMessages,
                        characterName = participants.joinToString("、") { it.name },
                        characterDescription = participants.joinToString("；") { it.description }.take(500)
                    )
                    if (newName != null) {
                        sessionDao.updateName(session.id, newName, nowIso())
                        emit(RealtimeEvent.SessionRenamed(session.id, newName))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalLogger.w(TAG, "群聊会话自动命名失败（不影响主流程）: ${e.message}", e)
            }
        }
        emit(
            RealtimeEvent.ReplyPostProcessed(
                session.id,
                completedResponseContents
            )
        )
    }

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
            // 取最近 assistant 回复 + 历史
            val recentMsgs = listAiContextMessages(sessionId)
                .filter { it.role != "system" }
                .takeLast(6)
            val lastAssistant = recentMsgs.lastOrNull { it.role == "assistant" }
                ?: run {
                    emit(RealtimeEvent.Error("没有可用的助手回复"))
                    return@flow
                }
            val recentHistory = recentMsgs.map { mapOf("role" to it.role, "content" to it.content) }

            val plotGen = com.nekobot.app.data.local.ai.PlotChoiceGenerator(
                aiClient = aiClient,
                aiModelProvider = { aiModelDao.getActive() },
                failoverExecutor = chatFailoverExecutor,
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
            // fork 历史消息需要计入新会话统计，但不能重复算作新的模型调用。
            // 下次读取统计时由消息指纹识别并写入 inherited 记录。
            tokenUsageReconciled = false
            newId
        }

    // ==================== 上下文压缩 ====================

    /**
     * 压缩上下文：取前 [keepRecent] 条消息保留，更早的消息让 AI 摘要后替换为单条 system 摘要。
     */
    suspend fun compressContext(
        sessionId: String,
        keepRecent: Int = 10
    ): Boolean = withContext(Dispatchers.IO) {
        val messages = listAiContextMessages(sessionId)
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

        val execution = executeChatOnceViaQueue(reqMessages)
        val result = execution.value
        recordFailoverTokenUsage(
            execution = execution,
            source = "compression",
            purpose = TokenStatsManager.PURPOSE_UTILITY
        )
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

    suspend fun countHighAffectionCharacters(threshold: Int = 90): Int =
        withContext(Dispatchers.IO) {
            LocalRelationshipRepository(db.relationshipDao())
                .countCharactersAtOrAboveAffection(threshold)
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
            // 同步刷新以该角色为主角的会话立绘快照，使会话列表/聊天头像跟随角色卡立绘变更
            val cid = preset.id?.takeIf { it.isNotBlank() }
            if (cid != null) {
                sessionDao.updatePortraitsByCharacterId(
                    characterId = cid,
                    portrait = entity.portrait,
                    characterAvatar = entity.avatar
                )
            }
            reportAchievementProgress(
                AchievementManager.Target.Metric.CHARACTERS,
                characterDao.count().toLong()
            )
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
        reportAchievementProgress(
            AchievementManager.Target.Metric.WORLD_BOOKS,
            worldBookDao.count().toLong()
        )
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
     * AI 生成角色卡：使用 chat 故障转移队列，按后端相同的 system prompt 生成。
     * @return 生成的 CharacterPreset（未持久化，由调用方决定 createCharacter 保存）
     */
    suspend fun aiGenerateCharacter(description: String): CharacterPreset = withContext(Dispatchers.IO) {
        val systemPrompt = buildCharacterSystemPrompt()
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请根据以下描述创建角色卡：\n\n${description.trim()}")
        )
        val execution = executeChatOnceViaQueue(messages)
        val result = execution.value
        recordFailoverTokenUsage(execution, "web", TokenStatsManager.PURPOSE_UTILITY)
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
     * AI 随机生成角色灵感条目（标题 + 描述 + 标签）。
     * 使用 chat 故障转移队列，生成 3 个互不重复、风格差异明显的角色灵感。
     * @return 生成的角色灵感列表
     */
    suspend fun aiGenerateRandomCharacterIdeas(): List<RandomCharacterIdea> = withContext(Dispatchers.IO) {
        val systemPrompt = buildRandomCharacterIdeasSystemPrompt()
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请随机生成 3 个角色灵感，要求风格差异明显、有创意，每个灵感都要包含标题、简短描述和 2-4 个标签。只返回 JSON，不要任何额外说明。")
        )
        val execution = executeChatOnceViaQueue(messages)
        val result = execution.value
        recordFailoverTokenUsage(execution, "web", TokenStatsManager.PURPOSE_UTILITY)
        val content = stripMarkdownCodeFence(result.content)
        val parsed = JsonParser.parseString(content).asJsonObject
        val arr = parsed.get("ideas")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalStateException("AI 未生成有效角色灵感")
        val ideas = mutableListOf<RandomCharacterIdea>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            val description = o.get("description")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
            if (title.isBlank() || description.isBlank()) continue
            val tags = o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            ideas.add(RandomCharacterIdea(title, description, tags))
        }
        if (ideas.isEmpty()) throw IllegalStateException("AI 返回的角色灵感为空")
        ideas
    }

    /**
     * AI 批量生成世界书条目：使用 chat 故障转移队列生成 5-10 个条目并立即持久化。
     * @return 生成的条目列表（已写入数据库）
     */
    suspend fun aiGenerateWorldBookEntries(bookId: String, topic: String?): List<WorldBookEntry> =
        withContext(Dispatchers.IO) {
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
            val execution = executeChatOnceViaQueue(messages)
            val result = execution.value
            recordFailoverTokenUsage(execution, "web", TokenStatsManager.PURPOSE_UTILITY)
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
                val triggerSources = o.get("trigger_sources")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                val stateTriggers = o.get("state_triggers")
                    ?.takeIf { it.isJsonObject }
                    ?.let { value ->
                        val type = object : TypeToken<Map<String, List<String>>>() {}.type
                        runCatching {
                            gson.fromJson<Map<String, List<String>>>(value, type)
                        }.getOrNull()
                    }
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
                    caseSensitive = false,
                    triggerSources = triggerSources,
                    stateTriggers = stateTriggers,
                    matchMode = o.get("match_mode")?.takeIf { !it.isJsonNull }?.asString ?: "any",
                    entryType = o.get("entry_type")?.takeIf { !it.isJsonNull }?.asString ?: "lore"
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

    /** 随机角色灵感 AI 生成的 system prompt */
    private fun buildRandomCharacterIdeasSystemPrompt(): String = """你是一个角色创作灵感助手。请随机生成 3 个风格迥异、有创意的角色灵感条目。

你必须严格按照以下 JSON 格式返回，不要包含任何额外文字说明：

{
    "ideas": [
        {
            "title": "角色灵感标题（4-10 字，有吸引力）",
            "description": "角色核心特点的简短描述（20-40 字，突出个性与场景感）",
            "tags": ["标签1", "标签2", "标签3"]
        }
    ]
}

要求：
1. 3 个灵感风格差异明显（如治愈系、傲娇系、神秘系、冒险系、腹黑系、元气系等）
2. title 简洁有辨识度
3. description 要有画面感，让用户一眼想聊
4. tags 2-4 个，准确概括角色标签
5. 所有内容用中文填写"""

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
- state_triggers: 可选动态条件对象，例如 {"affection":[">=60"],"location":["白塔"],"time_of_day":["night"]}；
  可用字段包括 mood、energy、affection、trust、location、hour、time_of_day、weekday、plot_node、plot_level
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
            "state_triggers": {},
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

    /** 首次升级后把 Room 中遗留的明文模型、MCP 和 API Key 凭据原位改写为 Keystore 密文。 */
    suspend fun migrateStoredSecrets() {
        aiModelDao.migrateStoredSecrets()
        db.mcpServerDao().migrateStoredSecrets()
        db.apiKeyDao().migrateStoredSecrets()
    }

    suspend fun listAiModels(): List<LocalAiModelEntity> = withContext(Dispatchers.IO) {
        aiModelDao.listAll()
    }

    suspend fun getActiveModel(): LocalAiModelEntity? = withContext(Dispatchers.IO) {
        aiModelDao.getActive()
    }

    suspend fun getRoutedModel(
        sessionId: String,
        prompt: String,
        attachments: List<Map<String, Any>> = emptyList()
    ): LocalAiModelEntity? = withContext(Dispatchers.IO) {
        routedChatQueue(sessionId, prompt, attachments).firstOrNull()
            ?: aiModelDao.getActive()
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

    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String = ""
    ): List<String> =
        withContext(Dispatchers.IO) {
            aiClient.fetchAvailableModels(baseUrl, apiKey, appendBaseUrlPath, proxyUrl)
        }

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
                    model = exec.model.name,
                    actualModel = exec.model.model,
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
        voice: String? = null,
        speed: Float? = null,
        pitch: Float? = null,
        volume: Float? = null,
        modelId: String? = null
    ): LocalAudioResult = withContext(Dispatchers.IO) {
        val defaultQueue = queueFor("tts")
        val requested = modelId
            ?.takeIf { it.isNotBlank() }
            ?.let { aiModelDao.getById(it) }
            ?.takeIf { it.enabled && it.purpose == "tts" }
        val queue = if (requested == null) {
            defaultQueue
        } else {
            listOf(requested) + defaultQueue.filterNot { it.id == requested.id }
        }
        if (queue.isEmpty()) {
            throw IllegalStateException("未配置 TTS 模型，请在 AI 配置中心启用 purpose=tts 的模型")
        }
        val exec = try {
            failoverCoordinator.execute(queue, "tts") { model ->
                aiClient.synthesizeSpeech(model, text, voice, speed, pitch, volume)
            }
        } catch (e: FailoverAllFailedException) {
            throw IllegalStateException(e.message ?: "TTS 全部模型失败")
        }
        val filesDir = appContext?.filesDir
            ?: throw IllegalStateException("应用上下文未初始化，无法保存 TTS 音频")
        val format = exec.model.ttsFormat.lowercase().takeIf { it in setOf("mp3", "wav", "opus", "flac", "ogg") }
            ?: "mp3"
        val cacheFile = File(filesDir, "tts/${UUID.randomUUID()}.$format").apply {
            parentFile?.mkdirs()
            writeBytes(exec.value)
        }
        LocalAudioResult(
            cacheUri = android.net.Uri.fromFile(cacheFile).toString(),
            mimeType = when (format) {
                "mp3" -> "audio/mpeg"
                else -> "audio/$format"
            },
            usedModelId = exec.model.id,
            usedModelName = exec.model.name
        )
    }

    suspend fun updateMessageAudioUrl(messageId: String, audioUrl: String?) =
        withContext(Dispatchers.IO) {
            messageDao.updateAudioUrl(messageId, audioUrl)
        }

    /** 根据当前 TTS 模型提供商返回与原仓库一致的内置音色。 */
    suspend fun listLocalTtsVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        val active = aiModelDao.getActiveByPurpose("tts")
            ?: aiModelDao.listByPurpose("tts").firstOrNull()
            ?: return@withContext emptyList()
        val provider = active.ttsProvider.ifBlank {
            active.provider?.takeIf { it.isNotBlank() } ?: active.protocol
        }.lowercase()
        when (provider) {
            "xiaomi", "mimo" -> listOf(
                "mimo_default" to "MiMo 默认",
                "冰糖" to "冰糖",
                "茉莉" to "茉莉",
                "苏打" to "苏打",
                "白桦" to "白桦",
                "Mia" to "Mia",
                "Chloe" to "Chloe",
                "Milo" to "Milo",
                "Dean" to "Dean"
            )
            "doubao", "volcengine", "bytedance" -> listOf(
                "zh_female_shuangkuaisisi_moon_bigtts" to "爽快思思",
                "zh_male_bvlazysheep" to "懒羊羊",
                "zh_male_ahu_conversation_wvae_bigtts" to "阿虎",
                "zh_female_vv_uranus_bigtts" to "VV",
                "zh_female_cancan_mars_bigtts" to "灿灿",
                "zh_male_chongchong_mars_bigtts" to "冲冲",
                "zh_female_dandan_mars_bigtts" to "丹丹",
                "zh_male_haoyu_mars_bigtts" to "浩宇",
                "zh_female_wanwan_mars_bigtts" to "婉婉",
                "zh_male_yunxi_mars_bigtts" to "云希"
            )
            else -> listOf(
                "alloy" to "Alloy",
                "echo" to "Echo",
                "fable" to "Fable",
                "onyx" to "Onyx",
                "nova" to "Nova",
                "shimmer" to "Shimmer",
                "coral" to "Coral",
                "verse" to "Verse",
                "ballad" to "Ballad",
                "ash" to "Ash",
                "sage" to "Sage",
                "marin" to "Marin",
                "cedar" to "Cedar"
            )
        }.map { (id, name) ->
            TtsVoice(id = id, name = name, provider = provider)
        }
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
                        model = exec.model.name,
                        actualModel = exec.model.model,
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

    /**
     * 本地 token 用量统计（基于独立 SharedPreferences 存储，非 messageDao）。
     *
     * [dateRange] 控制聚合范围，与远程 API 字段语义对齐：
     * - "today"：仅今日记录
     * - "month"：仅本月记录
     * - "total" 或 null：全部记录
     * - "custom"：按 [startDate]/[endDate]（yyyy-MM-dd）闭区间过滤
     *
     * 返回的 TokenStats 中所有字段均表示「当前所选范围内」的统计；
     * UI 端无需根据范围挑选不同字段展示。
     */
    suspend fun tokenStats(
        dateRange: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ): TokenStats = withContext(Dispatchers.IO) {
        val allRecords = readTokenUsageRecordsReconciled()
        // fork 继承记录只用于新会话归属，不代表发生了新的模型调用。
        val usageRecords = allRecords.filterNot { it.isInheritedTokenUsage() }
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        // 按所选范围过滤记录
        val filtered = when (dateRange) {
            "today" -> usageRecords.filter { (it.get("date")?.asString ?: "") == todayStr }
            "month" -> usageRecords.filter { (it.get("date")?.asString ?: "").startsWith(monthStr) }
            "custom" -> {
                val start = startDate?.trim() ?: ""
                val end = endDate?.trim() ?: ""
                usageRecords.filter { rec ->
                    val date = rec.get("date")?.asString ?: ""
                    (start.isEmpty() || date >= start) && (end.isEmpty() || date <= end)
                }
            }
            else -> usageRecords // "total" 或 null：全部实际调用
        }

        var rangeInput = 0L
        var rangeOutput = 0L
        var rangeTotal = 0L
        var msgCount = 0L

        for (rec in filtered) {
            val input = rec.get("input_tokens")?.asLong ?: 0
            val output = rec.get("output_tokens")?.asLong ?: 0
            val total = rec.get("total_tokens")?.asLong ?: (input + output)
            rangeInput += input
            rangeOutput += output
            rangeTotal += total
            msgCount++
        }

        // records 返回范围内完整明细；recentRecords 仅保留最近 50 条用于兼容旧调用方。
        val details = filtered.asReversed().mapIndexed { index, rec ->
            rec.deepCopy().apply {
                val input = get("input_tokens")?.asLong ?: 0L
                val output = get("output_tokens")?.asLong ?: 0L
                if (!has("id")) {
                    addProperty(
                        "id",
                        "legacy:${get("session_id")?.asString.orEmpty()}:" +
                            "${get("timestamp")?.asString.orEmpty()}:$index"
                    )
                }
                if (!has("total_tokens")) addProperty("total_tokens", input + output)
                if (!has("source")) addProperty("source", "chat")
                if (!has("purpose")) {
                    val purpose = when (get("source")?.asString) {
                        "state" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_UTILITY
                        "memory" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_MEMORY
                        "plot" -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_PLOT
                        else -> com.nekobot.app.data.local.ai.TokenStatsManager.PURPOSE_CHAT
                    }
                    addProperty("purpose", purpose)
                }
            }
        }

        // 所有字段统一表示「当前所选范围内」的统计；UI 不再需要按范围挑字段。
        TokenStats(
            today = rangeTotal,
            month = rangeTotal,
            totalTokens = rangeTotal,
            todayInput = rangeInput,
            todayOutput = rangeOutput,
            messageCount = msgCount,
            avgTokensPerMsg = if (msgCount > 0) rangeTotal.toDouble() / msgCount else 0.0,
            estimatedCost = "—",
            recentRecords = details.take(50),
            records = details
        )
    }

    /**
     * 获取指定会话历史总 Token 数：从本地 token 用量记录按 session_id 聚合 input+output。
     */
    suspend fun sessionTokenUsage(sessionId: String): Long = withContext(Dispatchers.IO) {
        val records = readTokenUsageRecordsReconciled()
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

    /**
     * 获取当前仍处于模型上下文中的 Token 数。
     * 最近一条助手消息的 input+output 已覆盖此前完整 prompt，不能再把各轮 input 重复求和。
     */
    suspend fun sessionContextTokenUsage(sessionId: String): Long = withContext(Dispatchers.IO) {
        val messages = listAiContextMessages(sessionId)
            .filter { it.role != "system" }
            .map {
                LocalContextTokenMessage(
                    content = it.content,
                    inputTokens = it.inputTokens,
                    outputTokens = it.outputTokens
                )
            }
        currentLocalContextTokens(messages)
    }

    /** 本地 token 用量排行榜（按 model / session 聚合，从独立存储读取）。 */
    suspend fun tokenRankings(): TokenRankings = withContext(Dispatchers.IO) {
        val records = readTokenUsageRecordsReconciled()
        val usageRecords = records.filterNot { it.isInheritedTokenUsage() }

        // 模型与用途排行只统计实际调用，避免 fork 历史重复增加全局消耗。
        // 模型排行按 actual_model 分组（相同模型名、不同提供商可合并），
        // 旧记录无 actual_model 时回退到 model 字段。
        val modelsRank = usageRecords.groupBy { rec ->
            rec.get("actual_model")?.takeIf { it.isJsonPrimitive && !it.asString.isNullOrBlank() }?.asString
                ?: rec.get("model")?.asString
                ?: "未知"
        }
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

        // 按会话聚合（已删除的会话不纳入排行榜）
        val sessionsRank = records.groupBy { it.get("session_id")?.asString ?: "" }
            .mapNotNull { (sid, recs) ->
                // 会话已删除（无法匹配会话名）则跳过
                val sessionName = if (sid.isNotEmpty()) {
                    sessionDao.getById(sid)?.name ?: return@mapNotNull null
                } else {
                    "未知会话"
                }
                val input = recs.sumOf { it.get("input_tokens")?.asLong ?: 0 }
                val output = recs.sumOf { it.get("output_tokens")?.asLong ?: 0 }
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
        val purposesRank = usageRecords.groupBy { rec ->
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
     * 导入角色卡：支持 nekobot 协议（.json / .zip）与 SillyTavern 酒馆格式（v2/v3 .json、PNG 嵌入式 .png）。
     * - .zip：nekobot 协议，含 character.json + portrait 图片
     * - .json：nekobot 协议 或 SillyTavern v2（flat snake_case）/ v3（data 包裹层，spec=chara_card_v3）
     * - .png：SillyTavern PNG 嵌入式角色卡，从 tEXt chunk（chara/ccv3）提取 base64 JSON；PNG 自身作为立绘
     * @param bytes 文件内容
     * @param fileName 文件名（用于判断类型）
     * @return 导入后的 CharacterPreset（已保存到本地数据库）
     */
    suspend fun importCharacter(bytes: ByteArray, fileName: String): CharacterPreset =
        withContext(Dispatchers.IO) {
            val lower = fileName.lowercase()
            val jsonStr: String
            var portraitPath: String? = null
            var pngBytesForPortrait: ByteArray? = null

            when {
                lower.endsWith(".png") -> {
                    // SillyTavern PNG 嵌入式角色卡：从 tEXt chunk 提取 chara/ccv3
                    pngBytesForPortrait = bytes
                    jsonStr = extractCharacterJsonFromPng(bytes)
                        ?: throw IllegalArgumentException("PNG 文件中未找到角色卡数据（chara/ccv3 tEXt chunk）")
                }
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

            // SillyTavern v3 解包：若 spec=chara_card_v3 且含 data 对象，提取 data 内容合并为顶层
            val normalizedJson = unwrapSillyTavernV3(jsonStr)

            // 解析 JSON 为 CharacterPreset（@SerializedName alternate 处理 v2 snake_case）
            val preset = try {
                gson.fromJson(normalizedJson, CharacterPreset::class.java)
            } catch (e: Exception) {
                throw IllegalArgumentException("角色卡 JSON 解析失败：${e.message}")
            } ?: throw IllegalArgumentException("角色卡 JSON 为空")

            if (preset.name.isNullOrBlank()) {
                throw IllegalArgumentException("角色卡缺少 name 字段")
            }

            // 立绘优先级：ZIP 内立绘 > PNG 自身（SillyTavern 嵌入式角色卡的 PNG 即角色图像）> 原有 portrait 字段
            val finalPreset = when {
                portraitPath != null -> preset.copy(portrait = portraitPath)
                pngBytesForPortrait != null -> {
                    val pngPortraitPath = savePortraitLocal(pngBytesForPortrait, "png")
                    preset.copy(portrait = pngPortraitPath)
                }
                else -> preset
            }
            // 保存到数据库（新建 id）
            upsertCharacter(finalPreset.copy(id = null))
        }

    /**
     * 从 SillyTavern PNG 角色卡中提取嵌入的 JSON。
     * 解析 PNG tEXt chunk，查找 keyword 为 `ccv3`（v3，优先）或 `chara`（v2）的文本，
     * 内容为 base64 编码的 JSON 字符串。
     * @return 解码后的 JSON 字符串；非 PNG 或未找到 chunk 时返回 null
     */
    private fun extractCharacterJsonFromPng(bytes: ByteArray): String? {
        // PNG 签名: 89 50 4E 47 0D 0A 1A 0A
        val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < pngSig.size || !bytes.copyOfRange(0, pngSig.size).contentEquals(pngSig)) {
            return null  // 不是 PNG
        }
        var offset = pngSig.size
        var foundV3: String? = null
        var foundV2: String? = null
        while (offset + 8 <= bytes.size) {
            // 4 字节长度（大端）
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                         ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                         ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                         (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            // 4 字节 chunk 类型
            if (offset + 4 > bytes.size) break
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            offset += 4
            if (length < 0 || offset + length + 4 > bytes.size) break  // 截断或非法
            val data = bytes.copyOfRange(offset, offset + length)
            offset += length + 4  // 跳过数据 + 4 字节 CRC

            if (type == "tEXt") {
                // tEXt chunk: keyword\0text（均为 Latin-1/ASCII）
                val nullIdx = data.indexOf(0.toByte())
                if (nullIdx > 0) {
                    val keyword = String(data, 0, nullIdx, Charsets.US_ASCII)
                    val text = String(data, nullIdx + 1, data.size - nullIdx - 1, Charsets.US_ASCII)
                    when (keyword) {
                        "ccv3" -> foundV3 = text
                        "chara" -> foundV2 = text
                    }
                }
            } else if (type == "iTXt") {
                // iTXt chunk（国际化文本）：keyword\0 compressionFlag compressionMethod langTag\0 translatedKeyword\0 text
                // SillyTavern 角色卡一般用 tEXt，此处兼容处理未压缩的 iTXt chara/ccv3
                val nullIdx = data.indexOf(0.toByte())
                if (nullIdx > 0) {
                    val keyword = String(data, 0, nullIdx, Charsets.US_ASCII)
                    if (keyword == "ccv3" || keyword == "chara") {
                        // compressionFlag 在 nullIdx+1，compressionMethod 在 nullIdx+2
                        if (nullIdx + 2 < data.size && data[nullIdx + 1].toInt() == 0) {
                            // 跳过 langTag 和 translatedKeyword（各以 \0 结尾）
                            var p = nullIdx + 3
                            // langTag
                            while (p < data.size && data[p].toInt() != 0) p++
                            p++ // 跳过 \0
                            // translatedKeyword
                            while (p < data.size && data[p].toInt() != 0) p++
                            p++ // 跳过 \0
                            if (p < data.size) {
                                val text = String(data, p, data.size - p, Charsets.UTF_8)
                                if (keyword == "ccv3") foundV3 = text else foundV2 = text
                            }
                        }
                    }
                }
            }
            if (foundV3 != null) break  // v3 优先，找到即停
        }
        val base64 = foundV3 ?: foundV2 ?: return null
        return try {
            val decoded = java.util.Base64.getDecoder().decode(base64)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * SillyTavern v3 角色卡的 data 包裹层解包。
     * v3 规范：顶层含 `spec: "chara_card_v3"` 和 `data: { ... }`，真正的角色字段在 data 对象内。
     * 本函数检测到 v3 结构后，将 data 内容提升到顶层（data 优先，顶层非 spec/data 字段补充覆盖）。
     * @return 解包后的 JSON 字符串；非 v3 格式则原样返回
     */
    private fun unwrapSillyTavernV3(jsonStr: String): String {
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val spec = obj.get("spec")?.takeIf { !it.isJsonNull }?.asString
            val dataEl = obj.get("data")?.takeIf { it.isJsonObject }
            if (spec != null && spec.startsWith("chara_card_v") && dataEl != null) {
                // 合并：先放 data 内容，再用顶层非 spec/data 字段补充（creator / character_version 等）
                val merged = JsonObject()
                dataEl.asJsonObject.entrySet().forEach { (k, v) -> merged.add(k, v) }
                obj.entrySet().forEach { (k, v) ->
                    if (k != "spec" && k != "data" && !v.isJsonNull) {
                        merged.add(k, v)
                    }
                }
                merged.toString()
            } else {
                jsonStr
            }
        } catch (e: Exception) {
            jsonStr  // 解析失败，原样返回，交给后续 Gson 解析抛错
        }
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
        private const val JM_CHAPTER_FETCH_CONCURRENCY = 6
        private const val JM_DEFAULT_MAX_PAGES = 1_200
        private const val JM_FREE_SPACE_CHECK_INTERVAL = 20
        private const val JM_ESTIMATED_BYTES_PER_PAGE = 650L * 1024L
        private const val JM_REQUIRED_FREE_RESERVE = 128L * 1024L * 1024L
        private const val JM_MINIMUM_REMAINING_BYTES = 96L * 1024L * 1024L

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
        sessionMode = sessionMode,
        groupId = groupId,
        characterIds = characterIds?.let {
            runCatching {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(it, type)
            }.getOrNull()
        },
        groupConfig = groupConfig?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
    )

    private fun LocalMessageEntity.toMessage(): Message = Message(
        id = id,
        role = role,
        content = content,
        reasoningContent = reasoningContent,
        sender = sender,
        timestamp = timestamp,
        model = model,
        audioUrl = audioUrl,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        // 合并 input+output 作为总 token 数，供 UI 统计使用
        tokens = listOfNotNull(inputTokens, outputTokens).takeIf { it.size == 2 }?.sum(),
        createdAt = createdAt,
        // UI 历史只加载有界进度卡。完整工具历史仅供 AI 上下文路径读取，绝不能塞进聊天状态。
        thinkingCards = decodeThinkingCardsForUi(id, thinkingCards, gson),
        toolCallHistory = null
    )

    /** 持久化指定用户消息关联的进度卡片列表（agent 模式）。 */
    suspend fun updateMessageThinkingCards(messageId: String, cards: List<ThinkingCard>?) =
        withContext(Dispatchers.IO) {
            val json = cards?.takeIf { it.isNotEmpty() }?.let {
                runCatching { gson.toJson(it.map(ThinkingCard::toPersistedProgressCard)) }.getOrNull()
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
        displayIndex = displayIndex,
        triggerSources = triggerSourcesJson?.let {
            LocalPromptBuilder.parseStringList(it)
        },
        stateTriggers = stateTriggersJson?.let { json ->
            runCatching {
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                gson.fromJson<Map<String, List<String>>>(json, type)
            }.getOrNull()
        },
        matchMode = matchMode,
        entryType = entryType
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
            displayIndex = displayIndex ?: 0,
            triggerSourcesJson = triggerSources?.let { gson.toJson(it) },
            stateTriggersJson = stateTriggers?.let { gson.toJson(it) },
            matchMode = matchMode?.lowercase()?.takeIf { it in setOf("any", "all") } ?: "any",
            entryType = entryType?.trim()?.lowercase()?.ifBlank { "lore" } ?: "lore"
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
        snapshots.mapIndexed { idx, snap ->
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
                "jealousy" to snap.jealousy,
                // 对话回放：本轮对话原文 + 1-based 序号（按时间顺序）
                "message_index" to (idx + 1)
            )
            snap.userMessage?.takeIf { it.isNotBlank() }?.let { entry["user_message"] = it }
            snap.assistantMessage?.takeIf { it.isNotBlank() }?.let { entry["assistant_message"] = it }
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
        val effectiveLimit = limit.coerceIn(1, 500)
        val logs = if (hookId.isNullOrBlank()) {
            db.hookLogDao().listAll(effectiveLimit)
        } else {
            db.hookLogDao().listByHook(hookId, effectiveLimit)
        }
        logs.map { it.toHookExecutionLog() }
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

    /** Hook 执行日志实体转 API 模型，供 HooksScreen 展示。 */
    private fun com.nekobot.app.data.local.db.LocalHookLogEntity.toHookExecutionLog(): HookExecutionLog =
        HookExecutionLog(
            id = id,
            hookId = hookId,
            eventId = eventId,
            status = status,
            actionsExecuted = actionsExecuted,
            error = error,
            durationMs = durationMs,
            conversationId = conversationId,
            eventType = eventType,
            createdAt = createdAt
        )

    // ==================== 扩展功能：本地知识库 ====================

    suspend fun listKnowledge(): List<KnowledgeDocument> = withContext(Dispatchers.IO) {
        knowledgeManager.list()
    }

    suspend fun createKnowledge(req: KnowledgeDocumentRequest): KnowledgeDocument =
        withContext(Dispatchers.IO) { knowledgeManager.create(req) }

    suspend fun updateKnowledge(id: String, req: KnowledgeDocumentRequest): KnowledgeDocument =
        withContext(Dispatchers.IO) { knowledgeManager.update(id, req) }

    suspend fun deleteKnowledge(id: String) = withContext(Dispatchers.IO) {
        knowledgeManager.delete(id)
    }

    suspend fun importKnowledge(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray
    ): KnowledgeDocument = withContext(Dispatchers.IO) {
        knowledgeManager.import(fileName, mimeType, bytes)
    }

    suspend fun indexKnowledge(id: String): JsonElement = withContext(Dispatchers.IO) {
        knowledgeManager.index(id)
        JsonObject().apply {
            addProperty("success", true)
            addProperty("document_id", id)
        }
    }

    suspend fun knowledgeStats(): KnowledgeStats = withContext(Dispatchers.IO) {
        knowledgeManager.stats()
    }

    suspend fun searchKnowledge(req: KnowledgeSearchRequest): List<KnowledgeSearchResult> =
        withContext(Dispatchers.IO) {
            knowledgeManager.search(req.query, req.topK)
        }

    suspend fun rebuildKnowledge(): JsonElement = withContext(Dispatchers.IO) {
        knowledgeManager.rebuild()
        JsonObject().apply { addProperty("success", true) }
    }

    // ==================== 扩展功能：任务中心 ====================

    suspend fun listTasks(): List<TaskItem> = withContext(Dispatchers.IO) {
        db.taskDao().listAll().map { it.toTaskItem() }
    }

    suspend fun createTask(req: TaskRequest): TaskItem = withContext(Dispatchers.IO) {
        validateTaskRequest(req)
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
        scheduleTask(entity).toTaskItem()
    }

    suspend fun updateTask(id: String, req: TaskRequest): TaskItem = withContext(Dispatchers.IO) {
        validateTaskRequest(req)
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
        scheduleTask(updated).toTaskItem()
    }

    suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) {
        automationScheduler?.cancelTask(id)
        db.taskDao().deleteById(id)
    }

    suspend fun toggleTask(id: String): TaskItem = withContext(Dispatchers.IO) {
        val existing = db.taskDao().getById(id) ?: throw IllegalStateException("任务不存在")
        val newEnabled = !existing.enabled
        db.taskDao().setEnabled(id, newEnabled)
        scheduleTask(existing.copy(enabled = newEnabled)).toTaskItem()
    }

    /** 立即执行任务；定时任务也复用这一条真实聊天 Pipeline。 */
    suspend fun runTask(id: String): JsonElement {
        val result = executeTask(id, requireEnabled = false)
        return JsonObject().apply {
            addProperty("success", true)
            addProperty("message", result.content)
            addProperty("task_id", id)
            result.sessionId?.let { addProperty("session_id", it) }
        }
    }

    suspend fun executeScheduledTask(id: String): AutomationExecutionResult =
        executeTask(id, requireEnabled = true)

    private suspend fun executeTask(
        id: String,
        requireEnabled: Boolean
    ): AutomationExecutionResult = withContext(Dispatchers.IO) {
        var task = db.taskDao().getById(id) ?: throw IllegalStateException("任务不存在")
        if (requireEnabled && !task.enabled) {
            return@withContext AutomationExecutionResult(
                title = task.name,
                content = "",
                notify = false
            )
        }
        val prompt = task.prompt?.trim().orEmpty()
        require(prompt.isNotBlank()) { "任务未配置执行提示词" }
        check(runningTaskIds.add(id)) { "任务正在执行中" }

        try {
            var session = task.targetSessionId?.let { sessionDao.getById(it) }
            if (!task.targetSessionId.isNullOrBlank() && session == null) {
                throw IllegalStateException("目标会话不存在：${task.targetSessionId}")
            }
            if (session == null) {
                val created = createSession(
                    CreateSessionRequest(
                        name = "任务 · ${task.name}",
                        sessionMode = "agent",
                        systemPrompt = task.description
                    )
                )
                session = sessionDao.getById(created.id ?: error("创建任务会话失败"))
                    ?: error("创建任务会话失败")
                task = task.copy(targetSessionId = session.id)
                db.taskDao().upsert(task)
            }
            val targetSession = session ?: error("创建任务会话失败")

            val startedAt = nowIso()
            db.taskDao().updateExecutionState(id, "running", null, startedAt)
            val isOneShot = task.trigger.equals("run_at", true) || task.trigger.equals("date", true)
            if (isOneShot) {
                db.taskDao().setEnabled(id, false)
                db.taskDao().updateNextRun(id, null)
            }
            val content = executeAutomationPrompt(
                sessionId = targetSession.id,
                prompt = prompt,
                assistantSource = "task_center",
                allowTools = targetSession.sessionMode.equals("agent", ignoreCase = true)
            )
            db.taskDao().updateExecutionState(id, "success", null, nowIso())
            AutomationExecutionResult(
                title = "任务完成 · ${task.name}",
                content = content,
                sessionId = targetSession.id
            )
        } catch (error: Exception) {
            db.taskDao().updateExecutionState(id, "failed", error.message, nowIso())
            throw error
        } finally {
            runningTaskIds.remove(id)
        }
    }

    private suspend fun scheduleTask(
        task: LocalTaskEntity,
        preserveExisting: Boolean = false,
        appendAfterCurrent: Boolean = false
    ): LocalTaskEntity {
        val preserved = task.nextRun
            ?.takeIf { preserveExisting }
            ?.let(::parseStoredInstant)
        val calculated = if (task.enabled) {
            preserved ?: LocalScheduleCalculator.nextRun(task.trigger, task.configJson)
        } else {
            null
        }
        val next = automationScheduler?.scheduleTask(
            task = task,
            preferredDueAt = calculated,
            replaceExisting = !preserveExisting,
            appendAfterCurrent = appendAfterCurrent
        ) ?: calculated
        val nextText = next?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        db.taskDao().updateNextRun(task.id, nextText)
        return task.copy(nextRun = nextText)
    }

    private fun validateTaskRequest(req: TaskRequest) {
        require(req.name.isNotBlank()) { "任务名称不能为空" }
        require(!req.prompt.isNullOrBlank()) { "任务提示词不能为空" }
        require(req.trigger in setOf("interval", "cron", "run_at", "date", "manual")) {
            "不支持的任务触发方式：${req.trigger}"
        }
        if (req.trigger != "manual") {
            require(LocalScheduleCalculator.nextRun(req.trigger, req.config?.toString()) != null) {
                when (req.trigger) {
                    "cron" -> "Cron 表达式无效"
                    "run_at", "date" -> "指定运行时间无效"
                    else -> "任务调度配置无效"
                }
            }
        }
    }

    private suspend fun executeAutomationPrompt(
        sessionId: String,
        prompt: String,
        assistantSource: String,
        allowTools: Boolean,
        persistUserMessage: Boolean = true,
        metadata: Map<String, Any> = emptyMap()
    ): String {
        val model = aiModelDao.getActiveByPurpose("chat") ?: aiModelDao.getActive()
            ?: throw IllegalStateException("请先配置并启用聊天模型")
        var failure: String? = null
        chatWithPipeline(
            sessionId = sessionId,
            userMessage = prompt,
            activeModel = model,
            persistUserMessage = persistUserMessage,
            internalMetadata = metadata,
            assistantSource = assistantSource,
            allowTools = allowTools
        ).collect { event ->
            if (event is RealtimeEvent.Error) failure = event.message
        }
        failure?.let { throw IllegalStateException(it) }
        return messageDao.latestBySource(sessionId, assistantSource)?.content
            ?: messageDao.listBySession(sessionId)
                .lastOrNull { it.role == "assistant" }
                ?.content
            ?: throw IllegalStateException("AI 未生成可保存的结果")
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
        nextRun = nextRun,
        status = status,
        lastError = lastError
    )

    // ==================== 扩展功能：工作流 ====================

    suspend fun listWorkflows(): List<Workflow> = withContext(Dispatchers.IO) {
        db.workflowDao().listAll().map { it.toWorkflow() }
    }

    suspend fun createWorkflow(req: WorkflowRequest): Workflow = withContext(Dispatchers.IO) {
        validateWorkflowRequest(req)
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
        scheduleWorkflow(entity).toWorkflow()
    }

    suspend fun updateWorkflow(id: String, req: WorkflowRequest): Workflow = withContext(Dispatchers.IO) {
        validateWorkflowRequest(req)
        val existing = db.workflowDao().getById(id) ?: throw IllegalStateException("工作流不存在")
        val updated = existing.copy(
            name = req.name,
            description = req.description,
            enabled = req.enabled,
            trigger = req.trigger,
            configJson = req.config?.let { gson.toJson(it) }
        )
        db.workflowDao().upsert(updated)
        scheduleWorkflow(updated).toWorkflow()
    }

    suspend fun deleteWorkflow(id: String) = withContext(Dispatchers.IO) {
        automationScheduler?.cancelWorkflow(id)
        db.workflowDao().deleteById(id)
    }

    suspend fun toggleWorkflow(id: String): Workflow = withContext(Dispatchers.IO) {
        val existing = db.workflowDao().getById(id) ?: throw IllegalStateException("工作流不存在")
        val newEnabled = !existing.enabled
        db.workflowDao().setEnabled(id, newEnabled)
        scheduleWorkflow(existing.copy(enabled = newEnabled)).toWorkflow()
    }

    suspend fun executeWorkflow(id: String): JsonElement {
        val result = executeWorkflowInternal(
            id = id,
            requireEnabled = false,
            triggerSource = "manual"
        )
        return JsonObject().apply {
            addProperty("success", true)
            addProperty("message", result.content)
            addProperty("workflow_id", id)
            result.sessionId?.let { addProperty("session_id", it) }
        }
    }

    suspend fun executeScheduledWorkflow(id: String): AutomationExecutionResult =
        executeWorkflowInternal(
            id = id,
            requireEnabled = true,
            triggerSource = "scheduler"
        )

    private suspend fun executeWorkflowInternal(
        id: String,
        requireEnabled: Boolean,
        triggerSource: String
    ): AutomationExecutionResult = withContext(Dispatchers.IO) {
        var workflow = db.workflowDao().getById(id) ?: throw IllegalStateException("工作流不存在")
        if (requireEnabled && !workflow.enabled) {
            return@withContext AutomationExecutionResult(
                title = workflow.name,
                content = "",
                notify = false
            )
        }
        check(runningWorkflowIds.add(id)) { "工作流正在执行中" }
        try {
            var session = workflow.sessionId?.let { sessionDao.getById(it) }
            if (session == null) {
                val created = createSession(
                    CreateSessionRequest(
                        name = "工作流 · ${workflow.name}",
                        sessionMode = "agent",
                        systemPrompt = workflow.description
                    )
                )
                session = sessionDao.getById(created.id ?: error("创建工作流会话失败"))
                    ?: error("创建工作流会话失败")
                db.workflowDao().updateSessionId(id, session.id)
                workflow = workflow.copy(sessionId = session.id)
            }
            val targetSession = session ?: error("创建工作流会话失败")
            val configPrompt = workflow.configJson
                ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?.get("prompt")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
            val workflowDescription = configPrompt
                ?: workflow.description?.trim()?.takeIf(String::isNotBlank)
                ?: "执行工作流「${workflow.name}」，分析当前会话并完成可执行的步骤。"
            val prompt = """
                [工作流触发 - $triggerSource] 请根据以下工作流描述执行任务。触发时间：${OffsetDateTime.now()}

                $workflowDescription
            """.trimIndent()

            db.workflowDao().updateExecutionState(id, "running", null, nowIso())
            val content = executeAutomationPrompt(
                sessionId = targetSession.id,
                prompt = prompt,
                assistantSource = "workflow",
                allowTools = true
            )
            db.workflowDao().updateExecutionState(id, "success", null, nowIso())
            AutomationExecutionResult(
                title = "工作流完成 · ${workflow.name}",
                content = content,
                sessionId = targetSession.id
            )
        } catch (error: Exception) {
            db.workflowDao().updateExecutionState(id, "failed", error.message, nowIso())
            throw error
        } finally {
            runningWorkflowIds.remove(id)
        }
    }

    private suspend fun scheduleWorkflow(
        workflow: LocalWorkflowEntity,
        preserveExisting: Boolean = false,
        appendAfterCurrent: Boolean = false
    ): LocalWorkflowEntity {
        val preserved = workflow.nextRun
            ?.takeIf { preserveExisting }
            ?.let(::parseStoredInstant)
        val calculated = if (workflow.enabled && workflow.trigger.equals("cron", true)) {
            preserved ?: LocalScheduleCalculator.nextRun(workflow.trigger, workflow.configJson)
        } else {
            null
        }
        val next = automationScheduler?.scheduleWorkflow(
            workflow = workflow,
            preferredDueAt = calculated,
            replaceExisting = !preserveExisting,
            appendAfterCurrent = appendAfterCurrent
        ) ?: calculated
        val nextText = next?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        db.workflowDao().updateNextRun(workflow.id, nextText)
        return workflow.copy(nextRun = nextText)
    }

    private fun validateWorkflowRequest(req: WorkflowRequest) {
        require(req.name.isNotBlank()) { "工作流名称不能为空" }
        require(!req.description.isNullOrBlank()) { "工作流描述不能为空" }
        require(req.trigger in setOf("manual", "cron")) {
            "不支持的工作流触发方式：${req.trigger}"
        }
        if (req.trigger == "cron") {
            require(LocalScheduleCalculator.nextRun(req.trigger, req.config?.toString()) != null) {
                "Cron 表达式无效"
            }
        }
    }

    /**
     * AI 生成工作流：使用 chat 故障转移队列，根据用户描述生成 [WorkflowRequest]。
     * 返回的请求由调用方决定是否调用 [createWorkflow] 持久化。
     */
    suspend fun aiGenerateWorkflow(description: String): WorkflowRequest = withContext(Dispatchers.IO) {
        val systemPrompt = buildWorkflowSystemPrompt()
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请根据以下描述生成工作流配置：\n\n${description.trim()}")
        )
        val execution = executeChatOnceViaQueue(messages)
        val result = execution.value
        recordFailoverTokenUsage(execution, "web", TokenStatsManager.PURPOSE_UTILITY)
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
        createdAt = createdAt,
        sessionId = sessionId,
        lastRun = lastRun,
        nextRun = nextRun,
        status = status,
        lastError = lastError
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
            headersJson = req.headers?.let { gson.toJson(it) },
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
            headersJson = req.headers?.let { gson.toJson(it) },
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

    /**
     * 本地 Agent 的内置工具之外，注入已经连接的 MCP 工具。
     *
     * 自动连接可能包含最长 90 秒的阻塞式 HTTP 初始化，不能放在聊天首条进度事件之前同步等待；
     * 否则界面只剩三个点。未连接的服务在后台连接，成功后从下一轮开始加入工具列表。
     */
    private suspend fun prepareMcpAgentTools(): List<Map<String, Any>> {
        if (mcpAutoConnectRunning.get()) return cachedMcpAgentTools

        val servers = db.mcpServerDao().listAll()
        val connectedIds = servers
            .asSequence()
            .filter { localMcpRuntime.isConnected(it.id) }
            .map { it.id }
            .toSet()
        val currentTools = localMcpRuntime.getOpenAiToolDefinitions(connectedIds)
        cachedMcpAgentTools = currentTools

        val needsAutoConnect = servers.any {
            it.enabled && it.autoConnect && it.id !in connectedIds
        }
        if (needsAutoConnect && mcpAutoConnectRunning.compareAndSet(false, true)) {
            ServiceContainer.applicationScope.launch(Dispatchers.IO) {
                try {
                    runCatching {
                        autoConnectMcpServers()
                        val latestConnectedIds = db.mcpServerDao().listAll()
                            .asSequence()
                            .filter { localMcpRuntime.isConnected(it.id) }
                            .map { it.id }
                            .toSet()
                        cachedMcpAgentTools =
                            localMcpRuntime.getOpenAiToolDefinitions(latestConnectedIds)
                    }.onFailure { error ->
                        LocalLogger.w(TAG, "后台自动连接 MCP 失败，不阻塞 Agent 对话", error)
                    }
                } finally {
                    mcpAutoConnectRunning.set(false)
                }
            }
        }
        return currentTools
    }

    private fun validateMcpRequest(req: McpServerRequest) {
        require(req.name.isNotBlank()) { "MCP 服务名称不能为空" }
        when (req.transport.lowercase()) {
            "stdio" -> require(!req.command.isNullOrBlank()) { "stdio 模式需要 command 参数" }
            "streamable-http", "http" -> {
                require(!req.url.isNullOrBlank()) { "HTTP 模式需要 url 参数" }
                req.headers?.let { headers ->
                    require(headers.isJsonObject) { "HTTP 请求头必须是键值对象" }
                    headers.asJsonObject.entrySet().forEach { (name, value) ->
                        require(name.isNotBlank()) { "HTTP 请求头名称不能为空" }
                        require(value.isJsonPrimitive) { "HTTP 请求头 $name 的值必须是文本" }
                    }
                }
            }
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
        headers = headersJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
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
        val dir = workspaceDir(sessionId)?.canonicalFile ?: return@withContext JsonArray()
        val target = resolveWorkspaceEntry(dir, path, allowRoot = true)
            ?.takeIf { it.isDirectory }
            ?: return@withContext JsonArray()
        val arr = JsonArray()
        target.listFiles()
            ?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { f ->
            JsonObject().also { o ->
                o.addProperty("name", f.name)
                o.addProperty("type", if (f.isDirectory) "directory" else "file")
                o.addProperty("size", f.length())
                o.addProperty("path", f.relativeTo(dir).invariantSeparatorsPath)
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
        val dir = workspaceDir(sessionId)?.canonicalFile ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "工作区目录不可用")
        }
        val file = resolveWorkspaceEntry(dir, filename, allowRoot = false)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "文件路径无效")
            }
        val ok = if (file.exists()) file.deleteRecursively() else false
        JsonObject().apply {
            addProperty("success", ok)
            addProperty("filename", filename)
        }
    }

    suspend fun downloadWorkspaceFile(sessionId: String, filename: String): java.io.File? = withContext(Dispatchers.IO) {
        val dir = workspaceDir(sessionId)?.canonicalFile ?: return@withContext null
        val file = resolveWorkspaceEntry(dir, filename, allowRoot = false)
            ?: return@withContext null
        if (file.exists() && file.isFile) file else null
    }

    private fun resolveWorkspaceEntry(
        root: java.io.File,
        relativePath: String?,
        allowRoot: Boolean
    ): java.io.File? {
        val normalized = relativePath
            .orEmpty()
            .trim()
            .replace('\\', '/')
            .trim('/')
        if (normalized.isBlank()) return root.takeIf { allowRoot }
        val target = runCatching { java.io.File(root, normalized).canonicalFile }.getOrNull()
            ?: return null
        val isInside = target.path.startsWith(root.path + java.io.File.separator)
        return target.takeIf { isInside }
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

    // ==================== 共享工作区（跨会话，本地模式） ====================

    /** 共享工作区根目录：filesDir/workspace/shared/。 */
    private fun sharedWorkspaceDir(): java.io.File? {
        val ctx = appContext ?: return null
        return LocalWorkspaceStorage.resolveShared(ctx.filesDir)
    }

    /** 列出共享工作区文件。 */
    suspend fun listSharedFiles(path: String?): JsonElement = withContext(Dispatchers.IO) {
        val dir = sharedWorkspaceDir()?.canonicalFile ?: return@withContext JsonArray()
        val target = resolveWorkspaceEntry(dir, path, allowRoot = true)
            ?.takeIf { it.isDirectory }
            ?: return@withContext JsonArray()
        val arr = JsonArray()
        target.listFiles()
            ?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { f ->
                JsonObject().also { o ->
                    o.addProperty("name", f.name)
                    o.addProperty("type", if (f.isDirectory) "directory" else "file")
                    o.addProperty("size", f.length())
                    o.addProperty("path", f.relativeTo(dir).invariantSeparatorsPath)
                    o.addProperty("mime_type", guessMime(f.name))
                }.also { arr.add(it) }
            }
        arr
    }

    /** 上传文件到共享工作区。 */
    suspend fun uploadSharedFile(bytes: ByteArray, fileName: String): JsonElement = withContext(Dispatchers.IO) {
        val dir = sharedWorkspaceDir() ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "共享工作区目录不可用")
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

    /** 删除共享工作区文件。 */
    suspend fun deleteSharedFile(filename: String): JsonElement = withContext(Dispatchers.IO) {
        val dir = sharedWorkspaceDir()?.canonicalFile ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "共享工作区目录不可用")
        }
        val file = resolveWorkspaceEntry(dir, filename, allowRoot = false)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "文件路径无效")
            }
        val ok = if (file.exists()) file.deleteRecursively() else false
        JsonObject().apply {
            addProperty("success", ok)
            addProperty("filename", filename)
        }
    }

    /** 下载共享工作区文件。 */
    suspend fun downloadSharedFile(filename: String): java.io.File? = withContext(Dispatchers.IO) {
        val dir = sharedWorkspaceDir()?.canonicalFile ?: return@withContext null
        val file = resolveWorkspaceEntry(dir, filename, allowRoot = false)
            ?: return@withContext null
        if (file.exists() && file.isFile) file else null
    }

    /** 移动会话工作区文件到共享工作区。 */
    suspend fun moveToShared(sessionId: String, filename: String): JsonElement = withContext(Dispatchers.IO) {
        val srcDir = workspaceDir(sessionId)?.canonicalFile
        val sharedDir = sharedWorkspaceDir()?.canonicalFile
        if (srcDir == null || sharedDir == null) return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "目录不可用")
        }
        val src = resolveWorkspaceEntry(srcDir, filename, allowRoot = false)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "源文件不存在")
            }
        if (!src.exists()) return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "源文件不存在")
        }
        val target = java.io.File(sharedDir, src.name)
        src.copyRecursively(target, overwrite = true)
        src.deleteRecursively()
        JsonObject().apply {
            addProperty("success", true)
            addProperty("filename", src.name)
        }
    }

    /** 移动共享工作区文件到指定会话。 */
    suspend fun moveSharedToPrivate(filename: String, sessionId: String): JsonElement = withContext(Dispatchers.IO) {
        val sharedDir = sharedWorkspaceDir()?.canonicalFile
        val targetDir = workspaceDir(sessionId)?.canonicalFile
        if (sharedDir == null || targetDir == null) return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "目录不可用")
        }
        val src = resolveWorkspaceEntry(sharedDir, filename, allowRoot = false)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "源文件不存在")
            }
        if (!src.exists()) return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "源文件不存在")
        }
        val target = java.io.File(targetDir, src.name)
        src.copyRecursively(target, overwrite = true)
        src.deleteRecursively()
        JsonObject().apply {
            addProperty("success", true)
            addProperty("filename", src.name)
            addProperty("session_id", sessionId)
        }
    }

    /** 创建共享工作区文件夹。 */
    suspend fun createSharedFolder(folderPath: String): JsonElement = withContext(Dispatchers.IO) {
        val dir = sharedWorkspaceDir()?.canonicalFile ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "共享工作区目录不可用")
        }
        val safeName = folderPath.trim().replace('\\', '/').trim('/')
        if (safeName.isBlank()) return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "文件夹名称不能为空")
        }
        val folder = resolveWorkspaceEntry(dir, safeName, allowRoot = false)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "路径无效")
            }
        folder.mkdirs()
        JsonObject().apply {
            addProperty("success", true)
            addProperty("path", safeName)
        }
    }

    /** 创建会话工作区文件夹。 */
    suspend fun createWorkspaceFolder(sessionId: String, folderPath: String): JsonElement = withContext(Dispatchers.IO) {
        val dir = workspaceDir(sessionId)?.canonicalFile ?: return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "工作区目录不可用")
        }
        val safeName = folderPath.trim().replace('\\', '/').trim('/')
        if (safeName.isBlank()) return@withContext JsonObject().apply {
            addProperty("success", false)
            addProperty("message", "文件夹名称不能为空")
        }
        val folder = resolveWorkspaceEntry(dir, safeName, allowRoot = false)
            ?: return@withContext JsonObject().apply {
                addProperty("success", false)
                addProperty("message", "路径无效")
            }
        folder.mkdirs()
        JsonObject().apply {
            addProperty("success", true)
            addProperty("path", safeName)
        }
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
 * 从修复后的 token 明细中聚合每个模型的日/周 token 用量，
 * 供 [FailoverCoordinator] 在执行前检查 [LocalAiModelEntity.tokenLimitDaily] /
 * [LocalAiModelEntity.tokenLimitWeekly] 限额。
 *
 * 记录字段约定见 [LocalRepository.appendTokenUsageRecord]：每条记录含 model + total_tokens + date。
 * 由于 records 中存储的是 model 名（如 "gpt-4o"），而协调器传入的是 model.id (UUID)，
 * 需通过 [resolveModelName] 回调把 id 解析为对应的 model 名后再做匹配。
 */
private class ReconciledFailoverUsageReader(
    private val readRecords: suspend () -> List<JsonObject>,
    private val resolveModelName: suspend (String) -> String?
) : FailoverUsageReader {
    override suspend fun getUsage(modelId: String): FailoverUsage {
        // 先把 modelId (UUID) 解析为对应的 model 名，再按名匹配历史 records
        val modelName = resolveModelName(modelId) ?: return FailoverUsage()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val weekStart = weekStartDateString()
        var daily = 0L
        var weekly = 0L
        for (record in readRecords()) {
            val inherited = runCatching {
                record.get("inherited")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            }.getOrDefault(false)
            if (inherited) continue
            val model = record.get("model")?.asString ?: continue
            if (model != modelName) continue
            val total = record.get("total_tokens")?.asLong ?: continue
            val date = record.get("date")?.asString ?: continue
            if (date == today) daily += total
            if (date >= weekStart) weekly += total
        }
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
