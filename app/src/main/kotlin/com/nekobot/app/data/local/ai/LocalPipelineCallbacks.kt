package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalImageResult
import com.nekobot.app.data.local.VISION_FAILURE_MARKER
import com.nekobot.app.data.local.agentContextWindow
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.local.isLocalCommandMessage
import com.nekobot.app.data.local.shouldInjectWorldBooks
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 本地模式 PipelineCallbacks 实现。
 *
 * 将 AIPipeline 与现有本地基础设施（LocalAiClient / Room / RealtimeEvent Flow）桥接。
 * 流式输出通过 [events] SharedFlow 推送，与旧 LocalRepository.chat() 行为一致。
 *
 * @param db 数据库
 * @param aiClient 本地 AI 客户端
 * @param activeModel 当前激活的 AI 模型
 * @param session 会话实体
 * @param character 角色卡实体（可空）
 * @param worldBookEntries 世界书条目（已加载）
 * @param characterRuntime 角色运行时（可空，启用角色模式时传入）
 * @param characterIdentity 角色身份标识（可空）
 * @param coordinator 故障转移协调器（可空，传入则使用持久化队列；否则回退到内存版 chatOnceWithFailover）
 * @param failoverQueue 故障转移队列：除 activeModel 外可用的备选模型（同 purpose，按 priority 升序）
 * @param hookExecutor Hook 执行引擎（可空，传入则在 before_turn/after_turn/model.after_call 事件触发 hook）
 */
internal class LocalPipelineCallbacks(
    private val db: NekobotDatabase,
    private val aiClient: LocalAiClient,
    private val activeModel: LocalAiModelEntity,
    private val session: LocalSessionEntity,
    private val character: LocalCharacterEntity?,
    private val worldBookEntries: List<LocalWorldBookEntryEntity> = emptyList(),
    private val characterRuntime: CharacterRuntime? = null,
    private val characterIdentity: CharacterIdentity? = null,
    /** 父用户消息 id；agent 模式进度卡片关联用，UI 在用户气泡下方渲染 */
    private val parentMessageId: String? = null,
    /** 助手消息来源标记；后台主动聊天用 proactive_chat，普通聊天为空。 */
    private val assistantSource: String? = null,
    /** 本地知识库检索入口。 */
    private val knowledgeSearcher: ((query: String) -> String)? = null,
    private val onTokenRecorded: ((sessionId: String, messageId: String, model: String, actualModel: String, inputTokens: Int, outputTokens: Int, timestamp: String, purpose: String, estimated: Boolean, durationMs: Double?, ttftMs: Double?, provider: String?, inputPricePerMillion: Double?, outputPricePerMillion: Double?) -> Unit)? = null,
    /** 路由决策完成回调：把实际费用、延迟和失败结果回写到解释性日志。 */
    private val onRoutingCompleted: (suspend (model: LocalAiModelEntity, usage: Map<String, Int>, durationMs: Double?, ttftMs: Double?, success: Boolean, failureReason: String?) -> Unit)? = null,
    /** 进度卡片更新回调；本地模式用于持久化到父用户消息 */
    private val onThinkingCardUpdate: ((card: ThinkingCard) -> Unit)? = null,
    /** 当前会话的本地 Agent 工作区。 */
    private val workspaceRoot: java.io.File? = null,
    /** 共享工作区根目录（跨会话），为 null 时工具不支持 shared:// 路径。 */
    private val sharedWorkspaceRoot: java.io.File? = null,
    /** 本地命令授权状态，由 LocalRepository 在同一会话内共享。 */
    private val execAuthorizationManager: LocalExecAuthorizationManager = LocalExecAuthorizationManager(),
    /** 已连接 MCP 服务的工具执行入口。 */
    private val mcpToolExecutor: ((toolName: String, args: Map<String, Any>) -> Map<String, Any>)? = null,
    /** 只读 Skills 存储工具执行入口。 */
    private val skillToolExecutor: ((toolName: String, args: Map<String, Any>) -> Map<String, Any>)? = null,
    /** 会话级原生浏览器工具执行入口。 */
    private val browserToolExecutor: ((args: Map<String, Any>) -> Map<String, Any>)? = null,
    /** 本地数据库操作工具执行入口（角色卡/世界书/Hook/工作流/Skill/AI 模型等 CRUD）。 */
    private val dbToolExecutor: ((toolName: String, args: Map<String, Any>) -> Map<String, Any>)? = null,
    /** 故障转移队列：activeModel 优先 + 同 purpose 其他启用模型，按 priority 升序 */
    private val failoverQueue: List<LocalAiModelEntity> = emptyList(),
    /** 持久化故障转移协调器；非空时 [buildModelCall] 走 coordinator，否则回退到 chatOnceWithFailover */
    private val coordinator: FailoverCoordinator? = null,
    /** Hook 执行引擎；非空时在管线关键节点触发 hook 事件 */
    private val hookExecutor: HookExecutor? = null,
    /** 视觉识别 suspend 函数：传入 imageUrl（http URL 或 data URI）和问题，返回描述文本 */
    private val visionDescriber: (suspend (imageUrl: String, question: String) -> String)? = null,
    /** 当前生成的会话级取消控制器。 */
    private val generationController: LocalGenerationController = LocalGenerationController(),
    /** 当前 Agent 运行记录 id；用于拒绝已被新一轮替换的旧 Job 回写检查点。 */
    private val agentRunId: String? = null,
    /** 当前会话选择的思考强度。 */
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    /**
     * 高风险工具授权请求的桥接 emitter：把 LocalDbToolExecutor.requestAuthorization
     * 内部的 ExecConfirmationRequest 路由到 LocalRepository 的 execConfirmationEvents
     * SharedFlow（由 ChatViewModel 收集弹窗）。为空时降级到 eventChannel。
     */
    private val execConfirmationEmitter: ((com.nekobot.app.data.remote.ExecConfirmationRequest) -> Unit)? = null,
    /**
     * 排队消息“立即发送”提供者：工具循环每轮模型调用前调用，
     * 返回待注入的排队用户消息文本（取出即消费）。非空时由 [LocalRepository] 负责持久化。
     */
    private val pendingUserMessageProvider: (() -> List<String>)? = null,
    /**
     * ask_user_question 等待管理器：非空时 AI 可调用提问工具挂起等待用户回答。
     * 由 LocalRepository 传入以跨轮共享 pending 状态。
     */
    private val askUserQuestionManager: LocalAskUserQuestionManager? = null,
    /**
     * 提问请求桥接 emitter：把 ask_user_question 的请求路由到 LocalRepository 的
     * askUserQuestionEvents SharedFlow（由 ChatViewModel 收集弹窗）。
     * 为空时降级到 eventChannel。
     */
    private val askUserQuestionEmitter: ((AskUserQuestionRequest) -> Unit)? = null
) : PipelineCallbacks() {

    companion object {
        private const val TAG = "LocalPipelineCB"
    }

    private val gson = Gson()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
    private val agentRunDao = db.agentRunDao()
    private val pendingGeneratedImages = mutableListOf<Pair<String, List<LocalImageResult>>>()
    private val localToolExecutor by lazy {
        LocalAgentToolExecutor(
            sessionId = session.id,
            workspaceRoot = workspaceRoot,
            authorizationManager = execAuthorizationManager,
            onConfirmationRequired = { request ->
                emitEvent(RealtimeEvent.ExecConfirmationRequired(request))
            },
            thinkingHistoryProvider = { limit ->
                kotlinx.coroutines.runBlocking {
                    messageDao.listBySession(session.id)
                        .asReversed()
                        .mapNotNull { message ->
                            val cardsJson = message.thinkingCards?.takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            @Suppress("UNCHECKED_CAST")
                            val cards = runCatching {
                                gson.fromJson(cardsJson, List::class.java) as List<Map<String, Any>>
                            }.getOrDefault(emptyList())
                            mapOf(
                                "message_id" to message.id,
                                "timestamp" to message.timestamp,
                                "cards" to cards
                            )
                        }
                        .take(limit)
                }
            },
            visionDescriber = { url, question ->
                kotlinx.coroutines.runBlocking {
                    visionDescriber?.invoke(url, question)
                        ?: VISION_FAILURE_MARKER + "视觉识别运行时不可用"
                }
            },
            generatedImagesSink = { prompt, images ->
                synchronized(pendingGeneratedImages) {
                    pendingGeneratedImages += prompt to images
                }
            },
            generationController = generationController,
            sharedWorkspaceRoot = sharedWorkspaceRoot,
            askUserQuestionManager = askUserQuestionManager,
            onAskUserQuestionRequired = { request ->
                // 与命令授权一致：优先走 LocalRepository SharedFlow（ChatViewModel 始终收集），
                // 避免依赖 eventChannel 的收集时序导致挂起无人解除。
                askUserQuestionEmitter?.invoke(request)
                    ?: emitEvent(RealtimeEvent.AskUserQuestionRequired(request))
            },
            onTodosUpdated = { todos ->
                // 任务列表持久化到会话实体，并推送事件刷新输入框上方可视化面板
                kotlinx.coroutines.runBlocking {
                    runCatching {
                        sessionDao.updateAgentTodos(
                            session.id,
                            com.nekobot.app.data.model.AgentTodo.encodeList(todos)
                        )
                    }.onFailure { error ->
                        com.nekobot.app.data.local.LocalLogger.w(
                            TAG,
                            "持久化 Agent 任务列表失败: ${error.message}"
                        )
                    }
                }
                emitEvent(
                    RealtimeEvent.AgentTodosUpdated(session.id, todos)
                )
            }
        )
    }

    /** 本地数据库 CRUD 工具执行器（角色卡/世界书/Hook/工作流/Skill/AI 模型等）。 */
    private val localDbToolExecutor by lazy {
        LocalDbToolExecutor(
            db = db,
            sessionId = session.id,
            authorizationManager = execAuthorizationManager,
            onConfirmationRequired = { request ->
                // 修复"删除角色卡卡住"：原实现 emit 到 LocalPipelineCallbacks.eventChannel
                // 但 eventChannel 没人 collect，requestAuthorization 的 runBlocking 永远等待。
                // 改为路由到 LocalRepository 的 execConfirmationEvents SharedFlow，
                // 由 ChatViewModel.connectLocalHookEvents 统一收集弹窗。
                execConfirmationEmitter?.invoke(request)
                    ?: emitEvent(RealtimeEvent.ExecConfirmationRequired(request))
            },
            generationController = generationController
        )
    }

    /**
     * 流式事件通道（UI 层收集）。正文/思考分片已在生产端合并，使用有限缓冲避免 UI
     * 短暂变慢时无限堆积事件与字符串对象。
     */
    val eventChannel: Channel<RealtimeEvent> = Channel(Channel.BUFFERED)

    /** 优先无阻塞发送；极少数缓冲已满的情况等待消费者，保证正文与结束事件不丢失。 */
    private fun emitEvent(event: RealtimeEvent) {
        if (eventChannel.trySend(event).isSuccess) return
        runCatching {
            kotlinx.coroutines.runBlocking { eventChannel.send(event) }
        }
    }

    // HookExecutor 事件由 ChatViewModel 直接收集（connectLocalHookEvents），
    // 不再通过 eventChannel 转发——避免 coroutineScope 等待无限 collect 导致死锁。

    /** 流式消息 ID */
    private var streamMessageId: String = ""
    private var activeAgentProgressReporter: LocalAgentProgressReporter? = null

    private fun createStreamEventCoalescer(ctx: PipelineContext): LocalStreamEventCoalescer =
        LocalStreamEventCoalescer(
            onEvent = { event ->
                when (event) {
                    is RealtimeEvent.ReasoningChunk -> {
                        if (reasoningEffort != ReasoningEffort.NONE) {
                            if (session.sessionMode.equals("agent", ignoreCase = true)) {
                                ctx.metadata["agent_reasoning_streamed"] = true
                                activeAgentProgressReporter?.onThinkingContent(ctx, event.chunk)
                            } else {
                                emitEvent(event)
                            }
                        }
                    }
                    else -> emitEvent(event)
                }
            }
        )

    // ---- 会话 / 消息 I/O ----

    override fun getSystemPrompt(ctx: PipelineContext): String {
        // 不再使用旧 LocalPromptBuilder，由 PromptStack 合成
        return ""
    }

    override fun searchKnowledge(ctx: PipelineContext, query: String): String =
        knowledgeSearcher?.invoke(query).orEmpty()

    override fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> {
        val isAgentSession = session.sessionMode.equals("agent", ignoreCase = true)
        val history = kotlinx.coroutines.runBlocking {
            messageDao.listBySession(session.id)
        }.let { messages ->
            if (isAgentSession) messages.agentContextWindow() else messages
        }.filter { message ->
            !message.role.equals("system", ignoreCase = true) ||
                (isAgentSession && message.isAgentContextSummary())
        }
            .filterNot { it.isLocalCommandMessage() }
            // 当前用户消息由下面统一追加。按 id 排除而不是 dropLast(1)，否则群聊第二名
            // 角色执行时，最后一条已经是前一名角色回复，会被错误删掉并重复注入用户消息。
            .filterNot { it.id == parentMessageId }

        val contextHistory = if (isAgentSession) {
            addLegacyAgentContextFallback(history)
        } else {
            history
        }

        // 无角色会话沿用旧提示词组装；Agent 只保留会话提示词，不得继承公共世界书。
        if (character == null) {
            return LocalPromptBuilder.build(
                session = session,
                character = null,
                history = contextHistory,
                userInput = ctx.chatRequest.content,
                worldBookEntries = worldBookEntries.takeIf {
                    shouldInjectWorldBooks(session.sessionMode)
                }.orEmpty()
            )
        }

        val messages = mutableListOf<Map<String, Any>>()

        // 历史消息
        for (msg in contextHistory) {
            val historyContent = if (session.sessionMode.equals("group", ignoreCase = true)) {
                LocalGroupChat.annotateHistoryContent(msg.role, msg.content, msg.sender)
            } else {
                msg.content
            }
            messages.add(buildMap {
                put("role", msg.role)
                put("content", historyContent)
                if (msg.role.equals("assistant", ignoreCase = true)) {
                    decodeToolCallHistory(msg.toolCallHistory)
                        .takeIf { it.isNotEmpty() }
                        ?.let { toolHistory ->
                            put("tool_call_history", toolHistory)
                            put("can_continue", true)
                        }
                }
            })
        }

        // 当前用户消息
        messages.add(mapOf("role" to "user", "content" to ctx.chatRequest.content))

        return messages
    }

    /**
     * 旧版本只把 Agent 工具结果保存在用户消息的 thinking_cards 中。
     * 新版本优先恢复助手消息上的完整 tool_call_history；仅对没有完整历史的旧轮次
     * 使用进度卡片摘要兜底，避免同一工具结果被重复注入。
     */
    private fun addLegacyAgentContextFallback(
        history: List<LocalMessageEntity>
    ): List<LocalMessageEntity> {
        return history.mapIndexed { index, message ->
            if (!message.role.equals("user", ignoreCase = true)) return@mapIndexed message
            val hasStoredToolHistory = history
                .drop(index + 1)
                .takeWhile { !it.role.equals("user", ignoreCase = true) }
                .any {
                    it.role.equals("assistant", ignoreCase = true) &&
                        decodeToolCallHistory(it.toolCallHistory).isNotEmpty()
                }
            if (hasStoredToolHistory) return@mapIndexed message

            val cardsBlock = renderThinkingCardsForContext(message.thinkingCards)
            if (cardsBlock.isBlank()) message else message.copy(content = message.content + cardsBlock)
        }
    }

    /**
     * 将一条用户消息的 thinking_cards JSON 渲染为可读文本块，用于"继续"场景下
     * 把上一轮 agent 的进度步骤注入到 AI 上下文中，避免 AI 失忆。
     *
     * 格式示例：
     * ```
     *
     * [上一轮 Agent 进度]
     * - 思考: AI 正在思考...（detail）
     * - 工具调用: db_list_characters（参数预览）→ 结果: <result preview>
     * - 工具调用: db_update_character（参数预览）→ 结果: <result preview>
     * - 处理完成
     * ```
     *
     * @return 文本块（含前置换行）；无 thinking_cards 或解析失败时返回空串。
     */
    private fun renderThinkingCardsForContext(
        thinkingCardsJson: String?,
        maxChars: Int = 12_000
    ): String {
        if (thinkingCardsJson.isNullOrBlank()) return ""
        return try {
            @Suppress("UNCHECKED_CAST")
            val cards = gson.fromJson(thinkingCardsJson, List::class.java) as? List<Map<String, Any>>
                ?: return ""
            if (cards.isEmpty()) return ""
            val sb = StringBuilder()
            fun appendBounded(text: String) {
                val remaining = maxChars - sb.length
                if (remaining > 0) sb.append(text.take(remaining))
            }

            appendBounded("\n\n[历史 Agent 执行记录（旧格式兜底）]")
            for (card in cards) {
                if (sb.length >= maxChars) break
                @Suppress("UNCHECKED_CAST")
                val steps = (card["steps"] as? List<Map<String, Any>>).orEmpty()
                for (step in steps) {
                    if (sb.length >= maxChars) break
                    val type = (step["type"] as? String).orEmpty()
                    val name = (step["name"] as? String).orEmpty()
                    val status = (step["status"] as? String).orEmpty()
                    val detail = (step["detail"] as? String).orEmpty()
                    val label = when (type) {
                        "thinking", "ai_thinking" -> "思考"
                        "tool", "tool_done" -> "工具调用"
                        "send_message" -> "发送消息"
                        "file" -> "文件"
                        "upload" -> "附件"
                        "knowledge" -> "知识库"
                        "done" -> "完成"
                        else -> type.ifBlank { "步骤" }
                    }
                    val statusMark = when (status) {
                        "done" -> "✓"
                        "running", "active" -> "…"
                        "error" -> "✗"
                        else -> ""
                    }
                    appendBounded("\n- $label: $name")
                    if (statusMark.isNotEmpty()) appendBounded(" [$statusMark]")
                    @Suppress("UNCHECKED_CAST")
                    val arguments = step["arguments"] as? Map<String, Any>
                    if (!arguments.isNullOrEmpty()) {
                        appendBounded("\n  参数: ${gson.toJson(arguments).take(1_000)}")
                    }
                    @Suppress("UNCHECKED_CAST")
                    val fullResult = step["full_result"] as? Map<String, Any>
                    if (!fullResult.isNullOrEmpty()) {
                        val path = (fullResult["path"] as? String)
                            ?: (fullResult["absolute_path"] as? String)
                        if (!path.isNullOrBlank()) appendBounded("\n  文件: $path")
                        val fileContent = fullResult["content"] as? String
                        if (!fileContent.isNullOrBlank()) {
                            appendBounded("\n  读取内容:\n")
                            appendBounded(fileContent)
                            if (fullResult["truncated"] == true) {
                                appendBounded("\n  [读取结果已截断，建议重新调用并设置更大的 max_chars 一次性读取完整内容，避免分片读取浪费上下文]")
                            }
                        } else {
                            appendBounded("\n  结果: ${gson.toJson(fullResult).take(2_000)}")
                        }
                    }
                    if (detail.isNotBlank()) {
                        appendBounded("\n  摘要: ${detail.take(240)}")
                    }
                }
            }
            if (sb.length >= maxChars) {
                sb.append("\n[历史执行记录达到上下文预算；需要细节时请重新读取对应文件]")
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    override fun saveAssistantMessage(ctx: PipelineContext, message: Map<String, Any>) {
        val content = (message["content"] as? String) ?: ""
        if (content.isBlank()) {
            try {
                kotlinx.coroutines.runBlocking {
                    onRoutingCompleted?.invoke(
                        activeModel,
                        emptyMap(),
                        (ctx.metadata["duration_ms"] as? Number)?.toDouble(),
                        (ctx.metadata["ttft_ms"] as? Number)?.toDouble(),
                        false,
                        ctx.error ?: "模型未返回有效内容"
                    )
                }
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "路由失败结果回写失败: ${e.message}")
            }
            return
        }
        @Suppress("UNCHECKED_CAST")
        val toolCallHistoryJson = encodeToolCallHistory(
            message["tool_call_history"] as? List<Map<String, Any>>
        )

        val resolvedUsage = if (ctx.error == null) {
            resolveLocalTokenUsage(ctx.usage, ctx.messages, content)
        } else {
            null
        }
        val inputTokens = resolvedUsage?.inputTokens
        val outputTokens = resolvedUsage?.outputTokens
        if (resolvedUsage != null) {
            ctx.usage = mapOf(
                "prompt" to resolvedUsage.inputTokens,
                "completion" to resolvedUsage.outputTokens,
                "total" to (resolvedUsage.inputTokens + resolvedUsage.outputTokens),
                "estimated" to resolvedUsage.estimated
            )
        }
        val modelName = (ctx.metadata["model_name"] as? String) ?: activeModel.name
        val actualModelName = (ctx.metadata["model_actual_name"] as? String) ?: activeModel.model
        val senderName = if (session.sessionMode.equals("group", ignoreCase = true)) {
            (ctx.metadata["group_speaker_name"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: character?.name
        } else {
            null
        }

        val messageId = (message["id"] as? String) ?: java.util.UUID.randomUUID().toString()
        val reasoningContent = if (reasoningEffort == ReasoningEffort.NONE) {
            ""
        } else {
            (message["reasoning_content"] as? String)
                ?: (message["thinking_content"] as? String)
                ?: ctx.finalReasoning
        }

        // 保存到 Room（同步执行，因为已在 IO 线程）
        kotlinx.coroutines.runBlocking {
            messageDao.upsert(LocalMessageEntity(
                id = messageId,
                sessionId = session.id,
                role = "assistant",
                content = content,
                sender = senderName,
                timestamp = System.currentTimeMillis().toString(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = modelName,
                createdAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic(),
                toolCallHistory = toolCallHistoryJson,
                source = assistantSource,
                reasoningContent = reasoningContent.takeIf(String::isNotBlank)
            ))

            persistPendingGeneratedImages(messageId)

            // 更新会话元信息
            val now = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            val msgCount = messageDao.countBySession(session.id)
            sessionDao.touch(session.id, content.take(200), msgCount, now)

            agentRunId?.let { runId ->
                val checkpoint = toolCallHistoryJson
                    ?: encodeToolCallHistory(ctx.toolTrace)
                val completedTools = completedAgentToolCallCount(ctx.toolTrace)
                val persistedStage = agentRunDao.getBySession(session.id)
                    ?.takeIf { it.runId == runId }
                    ?.stage
                when {
                    ctx.stoppedPrematurely || ctx.metadata["agent_waiting_confirmation"] == true -> {
                        agentRunDao.markStatus(
                            sessionId = session.id,
                            runId = runId,
                            status = AgentRunStatus.PAUSED,
                            stage = persistedStage ?: AgentRunStage.PAUSED,
                            checkpointHistory = checkpoint,
                            completedToolCalls = completedTools,
                            lastError = null,
                            assistantMessageId = messageId,
                            updatedAt = now
                        )
                    }
                    ctx.error != null -> {
                        agentRunDao.markStatus(
                            sessionId = session.id,
                            runId = runId,
                            status = AgentRunStatus.FAILED,
                            stage = persistedStage ?: AgentRunStage.FAILED,
                            checkpointHistory = checkpoint,
                            completedToolCalls = completedTools,
                            lastError = ctx.error,
                            assistantMessageId = messageId,
                            updatedAt = now
                        )
                    }
                    else -> agentRunDao.deleteRun(session.id, runId)
                }
            }
        }

        // 从 Pipeline metadata 提取首字延迟与总耗时，路由日志和 Token 统计共用。
        val durationMs = (ctx.metadata["duration_ms"] as? Number)?.toDouble()
        val ttftMs = (ctx.metadata["ttft_ms"] as? Number)?.toDouble()
        val priceModel = modelQueue.firstOrNull { model ->
            model.model == actualModelName || model.name == modelName
        } ?: activeModel

        // 记录 Token 用量到 TokenStatsManager（内存统计）
        if (inputTokens != null || outputTokens != null) {
            try {
                getGlobalTokenStatsManager().recordUsage(
                    promptTokens = inputTokens ?: 0,
                    completionTokens = outputTokens ?: 0,
                    model = modelName,
                    actualModel = actualModelName,
                    sessionId = session.id,
                    userId = "local-user",
                    purpose = TokenStatsManager.PURPOSE_CHAT,
                    durationMs = durationMs,
                    ttftMs = ttftMs,
                    provider = priceModel.provider,
                    inputPricePerMillion = priceModel.inputPrice,
                    outputPricePerMillion = priceModel.outputPrice
                )
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "TokenStats 记录失败: ${e.message}")
            }
            // 同时持久化到 SharedPreferences（供 tokenStats()/tokenRankings() 聚合读取）
            try {
                onTokenRecorded?.invoke(
                    session.id,
                    messageId,
                    modelName,
                    actualModelName,
                    inputTokens ?: 0,
                    outputTokens ?: 0,
                    com.nekobot.app.data.local.LocalRepository.nowIsoStatic(),
                    TokenStatsManager.PURPOSE_CHAT,
                    resolvedUsage?.estimated == true,
                    durationMs,
                    ttftMs,
                    priceModel.provider,
                    priceModel.inputPrice,
                    priceModel.outputPrice
                )
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "持久化 Token 记录失败: ${e.message}")
            }
        }

        try {
            kotlinx.coroutines.runBlocking {
                onRoutingCompleted?.invoke(
                    priceModel,
                    buildMap {
                        inputTokens?.let { put("prompt_tokens", it) }
                        outputTokens?.let { put("completion_tokens", it) }
                    },
                    durationMs,
                    ttftMs,
                    ctx.error == null && content.isNotBlank(),
                    ctx.error
                )
            }
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "路由结果回写失败: ${e.message}")
        }
    }

    /** 图片工具早于最终回复执行，待回复消息拥有稳定 ID 后再写入关联记录。 */
    private suspend fun persistPendingGeneratedImages(assistantMessageId: String) {
        val imageBatches = synchronized(pendingGeneratedImages) {
            pendingGeneratedImages.toList().also { pendingGeneratedImages.clear() }
        }
        imageBatches.forEach { (prompt, images) ->
            try {
                ServiceContainer.localRepository.attachGeneratedImages(
                    sessionId = session.id,
                    messageId = assistantMessageId,
                    prompt = prompt,
                    images = images
                )
            } catch (error: Exception) {
                com.nekobot.app.data.local.LocalLogger.e(
                    TAG,
                    "关联 Agent 生成图片失败: ${error.message}"
                )
            }
        }
    }

    // ---- AI 模型交互 ----

    /** 故障转移队列：activeModel 优先，附加 [failoverQueue] */
    private val modelQueue: List<LocalAiModelEntity> = listOf(activeModel) + failoverQueue.filter { it.id != activeModel.id }

    private fun modelQueueFor(ctx: PipelineContext): List<LocalAiModelEntity> =
        if (ctx.metadata["direct_image_input"] == true) {
            modelQueue.filter(LocalAiModelEntity::supportsVision).ifEmpty { modelQueue }
        } else {
            modelQueue
        }

    override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall {
        // 在模型调用前触发 character.before_turn.finished（此时 ctx.characterTurn 已就绪）
        triggerBeforeTurnHook(ctx)
        return { messages, stopped ->
            val extra = buildMap<String, Any?> {
                activeModel.temperature?.let { put("temperature", it) }
                activeModel.maxTokens?.let { put("max_tokens", it) }
                activeModel.topP?.let { put("top_p", it) }
                if (tools.isNotEmpty()) put("tools", tools)
                put("reasoning_effort", reasoningEffort.wireValue)
            }
            val streamRelay = if (session.sessionMode.equals("agent", ignoreCase = true)) {
                createStreamEventCoalescer(ctx)
            } else null
            val streamCallbacks = streamRelay?.let { relay ->
                LocalAiStreamCallbacks(
                    onStart = { relay.onStart() },
                    onContentChunk = relay::onContentChunk,
                    onThinkingChunk = { chunk ->
                        if (reasoningEffort != ReasoningEffort.NONE) {
                            ctx.metadata["agent_reasoning_streamed"] = true
                            relay.onReasoningChunk(chunk)
                        }
                    }
                )
            }

            // 优先走 coordinator（持久化健康状态 + token 限额 + 超时）；为空时回退到内存版
            val result = try {
                if (coordinator != null) {
                    kotlinx.coroutines.runBlocking {
                        try {
                            val exec = coordinator.execute(
                                models = modelQueueFor(ctx),
                                // Agent 的单次模型调用可能长时间输出 reasoning；使用独立的
                                // 默认超时，避免普通聊天的 120 秒故障转移时钟中断工具循环。
                                purpose = if (session.sessionMode.equals("agent", ignoreCase = true)) {
                                    "agent"
                                } else {
                                    activeModel.purpose.ifBlank { "chat" }
                                },
                                requiredContextTokens = estimateLocalMessagesTokens(messages)
                            ) { model ->
                                chatOnceForGeneration(model, messages, extra, streamCallbacks)
                            }
                            // 保留工具调用等结构化响应，并补充实际使用的模型。
                            exec.value.copy(
                                usedModelId = exec.model.id,
                                usedModelName = exec.model.name,
                                usedModelActualName = exec.model.model
                            )
                        } catch (e: FailoverAllFailedException) {
                            LocalAiResult("", error = e.message ?: "所有模型均失败")
                        }
                    }
                } else {
                    kotlinx.coroutines.runBlocking {
                        aiClient.chatOnceWithFailover(
                            modelQueueFor(ctx),
                            messages,
                            extra,
                            requestTag = session.id,
                            shouldStop = { generationController.isStopped },
                            streamCallbacks = streamCallbacks,
                            requiredContextTokens = estimateLocalMessagesTokens(messages)
                        )
                    }
                }
            } finally {
                streamRelay?.flush()
            }

            if (result.error != null) {
                throw RuntimeException(result.error)
            }

            // 模型调用完成 → 触发 model.after_call hook
            triggerModelAfterCallHook(ctx, result.usedModelName ?: activeModel.name)

            buildMap<String, Any> {
                put("content", result.content)
                put("usage", result.usage)
                put("finish_reason", result.finishReason.ifBlank {
                    if (result.toolCalls.isNotEmpty()) "tool_calls" else "stop"
                })
                put("_model_id", result.usedModelId ?: activeModel.id)
                put("_model_name", result.usedModelName ?: activeModel.name)
                put("_model_actual_name", result.usedModelActualName ?: activeModel.model)
                if (result.toolCalls.isNotEmpty()) put("tool_calls", result.toolCalls)
                if (reasoningEffort != ReasoningEffort.NONE && result.thinkingContent.isNotBlank()) {
                    put("thinking_content", result.thinkingContent)
                }
            }
        }
    }

    override fun buildModelCallStreaming(ctx: PipelineContext, tools: List<Map<String, Any>>): StreamModelCall? {
        if (tools.isNotEmpty()) return null  // 工具调用不支持流式

        // 在模型调用前触发 character.before_turn.finished
        triggerBeforeTurnHook(ctx)

        return { messages, stopped ->
            val extra = buildMap<String, Any?> {
                activeModel.temperature?.let { put("temperature", it) }
                activeModel.maxTokens?.let { put("max_tokens", it) }
                activeModel.topP?.let { put("top_p", it) }
                put("reasoning_effort", reasoningEffort.wireValue)
            }

            val fullContent = StringBuilder()
            val fullReasoning = StringBuilder()
            var streamError: String? = null
            var usedModelName = activeModel.name
            var usedActualModel = activeModel.model
            var usage: Map<String, Any> = emptyMap()
            val streamRelay = createStreamEventCoalescer(ctx)

            // UI 事件在网络读取时合并转发；Pipeline 只接收最终正文/思考各一个聚合块，
            // 避免长回复产生“每 token 一个 Map”的内存放大。
            try {
                kotlinx.coroutines.runBlocking {
                    aiClient.chatStreamWithFailover(
                        models = modelQueueFor(ctx),
                        messages = messages,
                        extra = extra,
                        requiredContextTokens = estimateLocalMessagesTokens(messages)
                    ).collect { event ->
                        if (generationController.isStopped) {
                            throw kotlinx.coroutines.CancellationException("生成已停止")
                        }
                        when (event) {
                            is RealtimeEvent.StreamStart -> streamRelay.onStart()
                            is RealtimeEvent.ReasoningChunk -> {
                                if (reasoningEffort != ReasoningEffort.NONE) {
                                    fullReasoning.append(event.chunk)
                                    streamRelay.onReasoningChunk(event.chunk)
                                }
                            }
                            is RealtimeEvent.StreamChunk -> {
                                fullContent.append(event.chunk)
                                streamRelay.onContentChunk(event.chunk)
                            }
                            is RealtimeEvent.Usage -> {
                                usedModelName = event.modelDisplayName ?: usedModelName
                                usedActualModel = event.model ?: usedActualModel
                                usage = mapOf(
                                    "prompt" to event.inputTokens,
                                    "completion" to event.outputTokens,
                                    "total" to event.inputTokens + event.outputTokens
                                )
                            }
                            is RealtimeEvent.Error -> streamError = event.message
                            else -> Unit
                        }
                    }
                }
            } finally {
                streamRelay.flush()
            }
            streamError?.let { throw RuntimeException(it) }
            val chunks = buildList<Map<String, Any>> {
                if (fullReasoning.isNotEmpty()) {
                    add(mapOf("thinking_content" to fullReasoning.toString(), "_relayed" to true))
                }
                if (fullContent.isNotEmpty()) {
                    add(mapOf("content" to fullContent.toString(), "_relayed" to true))
                }
                add(buildMap {
                    if (usage.isNotEmpty()) put("usage", usage)
                    put("_model_id", activeModel.id)
                    put("_model_name", usedModelName)
                    put("_model_actual_name", usedActualModel)
                    put("_relayed", true)
                })
            }
            triggerModelAfterCallHook(ctx, usedModelName)
            chunks
        }
    }

    private suspend fun chatOnceForGeneration(
        model: LocalAiModelEntity,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?>,
        streamCallbacks: LocalAiStreamCallbacks? = null
    ): LocalAiResult {
        if (generationController.isStopped) {
            throw kotlinx.coroutines.CancellationException("生成已停止")
        }
        return try {
            aiClient.chatOnce(
                model,
                messages,
                extra,
                requestTag = session.id,
                streamCallbacks = streamCallbacks
            )
        } catch (error: Exception) {
            if (generationController.isStopped) {
                throw kotlinx.coroutines.CancellationException("生成已停止").apply {
                    initCause(error)
                }
            }
            throw error
        }
    }

    // ---- 输出 / 回复 ----

    override fun sendResponse(ctx: PipelineContext, message: Map<String, Any>) {
        // 非流式（如 agent 工具循环结束）：构造完整消息推送 AiResponse，
        // 触发 UI 移除流式占位 + 追加新消息，避免"AI 回复不显示，需重进会话"问题。
        val msg = com.nekobot.app.data.model.Message(
            id = (message["id"] as? String),
            role = "assistant",
            content = (message["content"] as? String) ?: "",
            reasoningContent = if (reasoningEffort == ReasoningEffort.NONE) null else {
                (message["reasoning_content"] as? String)
                    ?: (message["thinking_content"] as? String)
                    ?: ctx.finalReasoning.takeIf(String::isNotBlank)
            },
            sender = if (session.sessionMode.equals("group", ignoreCase = true)) {
                (ctx.metadata["group_speaker_name"] as? String) ?: character?.name
            } else {
                null
            },
            name = if (session.sessionMode.equals("group", ignoreCase = true)) {
                (ctx.metadata["group_speaker_name"] as? String) ?: character?.name
            } else {
                null
            },
            avatar = if (session.sessionMode.equals("group", ignoreCase = true)) {
                character?.portrait ?: character?.avatar
            } else {
                null
            },
            timestamp = (message["timestamp"] as? String) ?: System.currentTimeMillis().toString(),
            model = (ctx.metadata["model_name"] as? String) ?: activeModel.model
        )
        emitEvent(RealtimeEvent.AiResponse(msg))
    }

    override fun onStreamStart(ctx: PipelineContext, message: Map<String, Any>) {
        streamMessageId = (message["id"] as? String) ?: java.util.UUID.randomUUID().toString()
        emitEvent(RealtimeEvent.StreamStart(null))
    }

    override fun onStreamChunk(ctx: PipelineContext, chunk: String, messageId: String) {
        emitEvent(RealtimeEvent.StreamChunk(chunk))
    }

    override fun onReasoningChunk(ctx: PipelineContext, chunk: String, messageId: String) {
        if (reasoningEffort == ReasoningEffort.NONE) return
        if (session.sessionMode.equals("agent", ignoreCase = true)) {
            activeAgentProgressReporter?.onThinkingContent(ctx, chunk)
        } else {
            emitEvent(RealtimeEvent.ReasoningChunk(chunk))
        }
    }

    override fun onStreamEnd(ctx: PipelineContext, messageId: String) {
        emitEvent(RealtimeEvent.StreamEnd(session.id))
    }

    // ---- 进度报告 ----
    // agent 模式启用进度卡片：上报 thinking/tool/iteration/done 等步骤到 UI
    // 非 agent 模式返回空实现，避免无意义的事件吞吐
    override fun getProgressReporter(ctx: PipelineContext): ProgressReporter {
        if (!session.sessionMode.equals("agent", ignoreCase = true)) return ProgressReporter()
        return LocalAgentProgressReporter(
            parentMessageId = parentMessageId,
            onUpdate = { card -> emitEvent(RealtimeEvent.ThinkingCardUpdate(card)) },
            onCheckpoint = { card -> onThinkingCardUpdate?.invoke(card) }
        ).also { activeAgentProgressReporter = it }
    }

    override fun saveAgentCheckpoint(
        ctx: PipelineContext,
        toolCallHistory: List<Map<String, Any>>,
        stage: String,
        lastToolName: String?
    ) {
        val runId = agentRunId ?: return
        val encoded = encodeToolCallHistory(toolCallHistory)
        kotlinx.coroutines.runBlocking {
            agentRunDao.updateCheckpoint(
                sessionId = session.id,
                runId = runId,
                stage = stage,
                checkpointHistory = encoded,
                completedToolCalls = completedAgentToolCallCount(toolCallHistory),
                lastToolName = lastToolName,
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )
        }
    }

    override fun markAgentToolRunning(ctx: PipelineContext, toolName: String) {
        val runId = agentRunId ?: return
        kotlinx.coroutines.runBlocking {
            agentRunDao.updateStage(
                sessionId = session.id,
                runId = runId,
                stage = AgentRunStage.TOOL,
                lastToolName = toolName.takeIf(String::isNotBlank),
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )
        }
    }

    // ---- 排队消息注入 ----

    override fun drainPendingUserMessages(ctx: PipelineContext): List<String> {
        val provider = pendingUserMessageProvider ?: return emptyList()
        return runCatching { provider() }.getOrElse { error ->
            com.nekobot.app.data.local.LocalLogger.w(
                TAG,
                "取出排队消息失败: ${error.message}"
            )
            emptyList()
        }
    }

    // ---- 工具确认 ----

    override fun onConfirmationRequired(ctx: PipelineContext, requestId: String, command: String) {
        ctx.metadata["agent_waiting_confirmation"] = true
        emitEvent(RealtimeEvent.Error("需要确认: $command [ID: $requestId]"))
    }

    override fun checkConfirmation(ctx: PipelineContext, userInput: String): String? {
        return detectConfirmation(userInput)
    }

    // ---- 工作区 ----

    override fun ensureWorkspace(ctx: PipelineContext): String {
        workspaceRoot?.mkdirs()
        return workspaceRoot?.absolutePath.orEmpty()
    }

    override fun getWorkspaceContext(ctx: PipelineContext): Map<String, Any> = buildMap {
        put("session_id", session.id)
        ensureWorkspace(ctx).takeIf { it.isNotBlank() }?.let { put("workspace_path", it) }
    }

    override fun getMemoryContext(ctx: PipelineContext): Map<String, Any> = getWorkspaceContext(ctx)

    // ---- 附件解析 ----

    override fun supportsDirectImageInput(ctx: PipelineContext): Boolean =
        activeModel.purpose.equals("chat", ignoreCase = true) && activeModel.supportsVision

    override fun resolveAttachmentData(ctx: PipelineContext, attachment: Map<String, Any>): Map<String, Any>? {
        val attType = (attachment["type"] as? String ?: "").lowercase()
        val isImage = attType.startsWith("image/") || looksLikeImageName(attachment)
        if (!isImage) return attachment

        // 图片附件：优先将本地文件路径转为 base64 data URI，供 vision API 直接使用
        val filePath = (attachment["path"] as? String) ?: (attachment["file_path"] as? String)
        if (!filePath.isNullOrBlank()) {
            val file = java.io.File(filePath)
            if (file.isFile) {
                val dataUri = fileToDataUri(file) ?: return attachment
                return buildMap<String, Any> {
                    put("type", attachment["type"] ?: "image")
                    val attName = attachment["name"] ?: attachment["filename"]
                    if (attName != null) put("name", attName)
                    put("data", dataUri)
                }
            }
        }
        // 工作区相对路径：尝试在会话工作区内解析
        val relPath = (attachment["name"] as? String) ?: (attachment["filename"] as? String)
        if (!relPath.isNullOrBlank() && workspaceRoot != null) {
            val wsFile = java.io.File(workspaceRoot, relPath).canonicalFile
            if (wsFile.isFile && wsFile.path.startsWith(workspaceRoot.canonicalFile.path + java.io.File.separator)) {
                val dataUri = fileToDataUri(wsFile) ?: return attachment
                return buildMap<String, Any> {
                    put("type", attachment["type"] ?: "image")
                    put("name", relPath)
                    put("data", dataUri)
                }
            }
        }
        // 已是 data URI 或 http URL：原样返回
        return attachment
    }

    private fun looksLikeImageName(att: Map<String, Any>): Boolean {
        val name = ((att["name"] as? String) ?: (att["filename"] as? String) ?: "").lowercase()
        val imageExt = setOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg")
        val ext = if ("." in name) "." + name.substringAfterLast(".") else ""
        return ext in imageExt
    }

    /** 将图片文件转为 base64 data URI，供 vision API 使用。 */
    private fun fileToDataUri(file: java.io.File): String? {
        return try {
            // 限制 20MB，避免 base64 编码后过大导致 API 拒绝
            if (file.length() > 20L * 1024 * 1024) return null
            val mime = guessImageMime(file.name)
            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:$mime;base64,$base64"
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "fileToDataUri 失败: ${e.message}")
            null
        }
    }

    private fun guessImageMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }

    /** 视觉识别：对每张图片调用 vision 模型获取描述。 */
    override suspend fun resolveImages(ctx: PipelineContext, imageUrls: List<String>): List<String> {
        val describer = visionDescriber ?: run {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "resolveImages: visionDescriber 为 null（未注入），跳过视觉识别")
            return emptyList()
        }
        com.nekobot.app.data.local.LocalLogger.i(TAG, "resolveImages: 开始识别 ${imageUrls.size} 张图片")
        return imageUrls.mapIndexed { idx, url ->
            val name = ctx.imageNames.getOrNull(idx)
            val question = "请详细描述这张图片的内容，包括主要对象、场景、颜色、文字等关键信息。"
            val desc = try {
                describer.invoke(url, question)
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "视觉识别失败 [${name ?: url.take(80)}]: ${e.message}")
                VISION_FAILURE_MARKER + "视觉识别异常：${e.message}"
            }
            com.nekobot.app.data.local.LocalLogger.i(TAG, "resolveImages: 第${idx + 1}张识别完成 | name=$name | 长度=${desc.length} | 含失败标记=${desc.contains(VISION_FAILURE_MARKER)}")
            buildString {
                if (!name.isNullOrBlank()) append("【附件: $name】\n")
                append(desc)
            }
        }
    }

    // ---- 后处理 ----

    override fun onResponseComplete(ctx: PipelineContext, result: PipelineResult) {
        com.nekobot.app.data.local.LocalLogger.d(TAG, "Response complete: ${result.finalContent.length} chars, error=${result.error}")
        // 触发 character.after_turn.finished hook（此时 afterTurn 已完成，状态/关系已是最新）
        triggerAfterTurnHook(ctx)
    }

    // ============== Hook 触发辅助 ==============

    /** 触发 character.before_turn.finished 事件 */
    private fun triggerBeforeTurnHook(ctx: PipelineContext) {
        val executor = hookExecutor ?: return
        val turn = ctx.characterTurn ?: return
        kotlinx.coroutines.GlobalScope.launch {
            executor.triggerEvent(
                eventType = "character.before_turn.finished",
                conversationId = session.id,
                characterId = characterIdentity?.characterId,
                ctx = HookContext(
                    state = turn.state,
                    relationship = turn.relationship,
                    promptStack = ctx.promptStack,
                    targetId = characterIdentity?.targetId ?: "local-user",
                    conditionLogic = "and"
                )
            )
        }
    }

    /** 触发 model.after_call 事件 */
    private fun triggerModelAfterCallHook(ctx: PipelineContext, modelName: String) {
        val executor = hookExecutor ?: return
        val turn = ctx.characterTurn
        kotlinx.coroutines.GlobalScope.launch {
            executor.triggerEvent(
                eventType = "model.after_call",
                conversationId = session.id,
                characterId = characterIdentity?.characterId,
                ctx = HookContext(
                    state = turn?.state,
                    relationship = turn?.relationship,
                    targetId = characterIdentity?.targetId ?: "local-user",
                    conditionLogic = "and"
                )
            )
        }
    }

    /** 触发 character.after_turn.finished 事件 */
    private fun triggerAfterTurnHook(ctx: PipelineContext) {
        val executor = hookExecutor ?: return
        val turn = ctx.characterTurn ?: return
        kotlinx.coroutines.GlobalScope.launch {
            executor.triggerEvent(
                eventType = "character.after_turn.finished",
                conversationId = session.id,
                characterId = characterIdentity?.characterId,
                ctx = HookContext(
                    state = turn.state,
                    relationship = turn.relationship,
                    targetId = characterIdentity?.targetId ?: "local-user",
                    conditionLogic = "and"
                )
            )
        }
    }

    // ---- 角色运行时 ----

    override fun getCharacterContext(ctx: PipelineContext): CharacterIdentity? = characterIdentity

    override fun getCharacterRuntime(ctx: PipelineContext): CharacterRuntime? = characterRuntime

    // ---- 工具执行 ----

    override suspend fun executeTool(
        toolName: String,
        args: Map<String, Any>,
        toolContext: Map<String, Any>
    ): Map<String, Any> {
        if (parseMcpToolName(toolName) != null) {
            return mcpToolExecutor?.invoke(toolName, args)
                ?: mapOf("success" to false, "error" to "MCP 工具运行时不可用")
        }
        if (toolName in localSkillToolIds) {
            return skillToolExecutor?.invoke(toolName, args)
                ?: mapOf("success" to false, "error" to "Skills 存储运行时不可用")
        }
        if (toolName == "browser_use") {
            return executeBrowserTool(args)
        }
        if (toolName in localDbToolIds) {
            return dbToolExecutor?.invoke(toolName, args) ?: localDbToolExecutor.execute(toolName, args)
        }
        val result = localToolExecutor.execute(toolName, args)
        maybeAttachGitDiff(toolName, result)
        return result
    }

    /** 文件变更类工具集：工具成功后据此刷新 git 变更摘要卡片。 */
    private val fileMutationTools = setOf(
        "workspace_create_file",
        "workspace_edit_file",
        "file_write",
        "file_edit",
        "workspace_delete_file",
        "workspace_extract_epub",
        // exec 可经由 shell 直接写工作区（echo > file、cp、git 等），
        // 执行前后快照对比纳入追踪。
        "exec_command"
    )

    /**
     * 本地 Agent 模式下，文件变更类工具成功后把最新的 git 变更摘要附加到当前进度卡片。
     * 非 Agent 会话、非文件工具或摘要为空时直接跳过，绝不抛异常。
     */
    private fun maybeAttachGitDiff(toolName: String, result: Map<String, Any>) {
        if (!session.sessionMode.equals("agent", ignoreCase = true)) return
        if (toolName !in fileMutationTools) return
        if (result["success"] != true) return
        val reporter = activeAgentProgressReporter ?: run {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "maybeAttachGitDiff: activeAgentProgressReporter 为空，跳过 git 摘要")
            return
        }
        val changed = localToolExecutor.currentChangedPaths()
        if (changed.isEmpty()) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "maybeAttachGitDiff: changedPaths 为空，跳过 git 摘要")
            return
        }
        runCatching {
            val summary = localToolExecutor.currentGitDiffSummary()
            com.nekobot.app.data.local.LocalLogger.i(
                TAG,
                "maybeAttachGitDiff: tool=$toolName changed=$changed summary=${summary?.files?.size ?: "null"}"
            )
            reporter.attachGitDiff(summary)
        }.onFailure { e ->
            com.nekobot.app.data.local.LocalLogger.w(TAG, "附加 git 变更摘要失败: ${e.message}")
        }
    }

    /**
     * 浏览器截图可在同一次工具调用内交给 vision 模型。
     *
     * 普通 screenshot 仍只保存截图；understand_screenshot 或 analyze=true 会将工作区图片
     * 交给 understand_image，避免模型拿到路径后忘记继续调用图片理解工具。
     */
    private suspend fun executeBrowserTool(args: Map<String, Any>): Map<String, Any> {
        val browser = browserToolExecutor
            ?: return mapOf("success" to false, "error" to "浏览器运行时不可用")
        val browserResult = browser(args)
        if (!browserScreenshotNeedsVision(args) || browserResult["success"] != true) {
            return browserResult
        }

        val screenshotPath = browserResult["image_url"]?.toString()
            ?: browserResult["screenshot_path"]?.toString()
            ?: browserResult["screenshot_absolute_path"]?.toString()
            ?: return browserResult.toMutableMap().apply {
                put("success", false)
                put("error", "浏览器截图成功，但没有返回可供图片理解的文件路径")
            }
        val question = browserScreenshotVisionQuestion(args)
        val visionResult = localToolExecutor.execute(
            "understand_image",
            mapOf(
                "image_url" to screenshotPath,
                "question" to question
            )
        )

        return browserResult.toMutableMap().apply {
            put("action", "understand_screenshot")
            put("vision_success", visionResult["success"] == true)
            if (visionResult["success"] == true) {
                val description = visionResult["description"]?.toString().orEmpty()
                put("vision_description", description)
                put(
                    "content",
                    "${browserResult["content"]?.toString().orEmpty()}\n\n图片理解结果：$description"
                )
            } else {
                val error = visionResult["error"]?.toString().orEmpty()
                    .ifBlank { "视觉模型没有返回可用结果" }
                put("success", false)
                put("vision_error", error)
                put("error", "浏览器截图成功，但图片理解失败：$error")
            }
        }
    }
}

internal fun browserScreenshotNeedsVision(args: Map<String, Any>): Boolean {
    val action = args["action"]?.toString()?.trim()?.lowercase().orEmpty()
    val analyze = args["analyze"] as? Boolean
        ?: args["analyze"]?.toString()?.equals("true", ignoreCase = true)
        ?: false
    return action == "understand_screenshot" || (action == "screenshot" && analyze)
}

internal fun browserScreenshotVisionQuestion(args: Map<String, Any>): String =
    args["question"]?.toString()?.trim().orEmpty().ifBlank {
        "请分析当前浏览器截图，说明页面的主要视觉内容、图片、图表、布局，以及仅靠页面文本无法判断的信息。"
    }
