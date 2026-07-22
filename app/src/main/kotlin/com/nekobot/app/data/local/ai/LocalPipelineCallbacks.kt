package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.nekobot.app.data.local.VISION_FAILURE_MARKER
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.channels.Channel
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
    private val onTokenRecorded: ((sessionId: String, model: String, inputTokens: Int, outputTokens: Int, timestamp: String, purpose: String) -> Unit)? = null,
    /** 进度卡片更新回调；本地模式用于持久化到父用户消息 */
    private val onThinkingCardUpdate: ((card: ThinkingCard) -> Unit)? = null,
    /** 当前会话的本地 Agent 工作区。 */
    private val workspaceRoot: java.io.File? = null,
    /** 本地命令授权状态，由 LocalRepository 在同一会话内共享。 */
    private val execAuthorizationManager: LocalExecAuthorizationManager = LocalExecAuthorizationManager(),
    /** 已连接 MCP 服务的工具执行入口。 */
    private val mcpToolExecutor: ((toolName: String, args: Map<String, Any>) -> Map<String, Any>)? = null,
    /** 只读 Skills 存储工具执行入口。 */
    private val skillToolExecutor: ((toolName: String, args: Map<String, Any>) -> Map<String, Any>)? = null,
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
    /**
     * 高风险工具授权请求的桥接 emitter：把 LocalDbToolExecutor.requestAuthorization
     * 内部的 ExecConfirmationRequest 路由到 LocalRepository 的 execConfirmationEvents
     * SharedFlow（由 ChatViewModel 收集弹窗）。为空时降级到 eventChannel。
     */
    private val execConfirmationEmitter: ((com.nekobot.app.data.remote.ExecConfirmationRequest) -> Unit)? = null
) : PipelineCallbacks() {

    companion object {
        private const val TAG = "LocalPipelineCB"
    }

    private val gson = Gson()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()
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
            generationController = generationController
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

    /** 流式事件通道（UI 层收集），UNLIMITED 避免背压阻塞 */
    val eventChannel: Channel<RealtimeEvent> = Channel(Channel.UNLIMITED)

    /** 非阻塞 emit：Channel.trySend 不会挂起 */
    private fun emitEvent(event: RealtimeEvent) {
        eventChannel.trySend(event)
    }

    // HookExecutor 事件由 ChatViewModel 直接收集（connectLocalHookEvents），
    // 不再通过 eventChannel 转发——避免 coroutineScope 等待无限 collect 导致死锁。

    /** 流式消息 ID */
    private var streamMessageId: String = ""

    // ---- 会话 / 消息 I/O ----

    override fun getSystemPrompt(ctx: PipelineContext): String {
        // 不再使用旧 LocalPromptBuilder，由 PromptStack 合成
        return ""
    }

    override fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> {
        val history = kotlinx.coroutines.runBlocking {
            messageDao.listBySession(session.id)
        }.filter { it.role != "system" }
            // 当前用户消息由下面统一追加。按 id 排除而不是 dropLast(1)，否则群聊第二名
            // 角色执行时，最后一条已经是前一名角色回复，会被错误删掉并重复注入用户消息。
            .filterNot { it.id == parentMessageId }

        // 无角色的 Agent 会话沿用旧聊天流程的提示词/世界书组装规则，
        // 仅将执行入口切换到 Pipeline，以便获得进度卡片事件。
        if (character == null) {
            // Agent 模式下，把每条用户消息关联的 thinking_cards 渲染为文本块
            // 追加到该用户消息末尾，让 AI 能看到之前 agent 做过的步骤（修复"继续"场景失忆）。
            val isAgentMode = session.sessionMode.equals("agent", ignoreCase = true)
            val enhancedHistory = if (isAgentMode) {
                history.map { msg ->
                    if (msg.role != "user") return@map msg
                    val cardsBlock = renderThinkingCardsForContext(msg.thinkingCards)
                    if (cardsBlock.isBlank()) return@map msg
                    msg.copy(content = msg.content + cardsBlock)
                }
            } else {
                history
            }
            return LocalPromptBuilder.build(
                session = session,
                character = null,
                history = enhancedHistory,
                userInput = ctx.chatRequest.content,
                worldBookEntries = worldBookEntries
            )
        }

        val messages = mutableListOf<Map<String, Any>>()

        // 历史消息
        for (msg in history) {
            // Agent 模式：把用户消息关联的 thinking_cards 渲染为文本块追加到末尾，
            // 让 AI 知道上一轮 agent 做了哪些工具调用与思考（修复"继续"场景下 AI 失忆）。
            val content = if (msg.role == "user" &&
                session.sessionMode.equals("agent", ignoreCase = true)) {
                val cardsBlock = renderThinkingCardsForContext(msg.thinkingCards)
                if (cardsBlock.isBlank()) msg.content else msg.content + cardsBlock
            } else {
                msg.content
            }
            val historyContent = if (session.sessionMode.equals("group", ignoreCase = true)) {
                LocalGroupChat.annotateHistoryContent(msg.role, content, msg.sender)
            } else {
                content
            }
            messages.add(mapOf(
                "role" to msg.role,
                "content" to historyContent
            ))
        }

        // 当前用户消息
        messages.add(mapOf("role" to "user", "content" to ctx.chatRequest.content))

        return messages
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
    private fun renderThinkingCardsForContext(thinkingCardsJson: String?): String {
        if (thinkingCardsJson.isNullOrBlank()) return ""
        return try {
            @Suppress("UNCHECKED_CAST")
            val cards = gson.fromJson(thinkingCardsJson, List::class.java) as? List<Map<String, Any>>
                ?: return ""
            if (cards.isEmpty()) return ""
            val sb = StringBuilder()
            sb.append("\n\n[上一轮 Agent 进度]")
            for (card in cards) {
                @Suppress("UNCHECKED_CAST")
                val steps = (card["steps"] as? List<Map<String, Any>>).orEmpty()
                for (step in steps) {
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
                    sb.append("\n- ").append(label).append(": ").append(name)
                    if (statusMark.isNotEmpty()) sb.append(" [").append(statusMark).append("]")
                    if (detail.isNotBlank()) {
                        sb.append("（").append(detail.take(160)).append("）")
                    }
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    override fun saveAssistantMessage(ctx: PipelineContext, message: Map<String, Any>) {
        val content = (message["content"] as? String) ?: ""
        if (content.isBlank()) return

        val inputTokens = (ctx.usage["prompt_tokens"] as? Int)
            ?: (ctx.usage["input_tokens"] as? Int)
            ?: (ctx.usage["prompt"] as? Int)
        val outputTokens = (ctx.usage["completion_tokens"] as? Int)
            ?: (ctx.usage["output_tokens"] as? Int)
            ?: (ctx.usage["completion"] as? Int)
        val modelName = (ctx.metadata["model_name"] as? String) ?: activeModel.model
        val senderName = if (session.sessionMode.equals("group", ignoreCase = true)) {
            (ctx.metadata["group_speaker_name"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?: character?.name
        } else {
            null
        }

        // 保存到 Room（同步执行，因为已在 IO 线程）
        kotlinx.coroutines.runBlocking {
            messageDao.upsert(LocalMessageEntity(
                id = (message["id"] as? String) ?: java.util.UUID.randomUUID().toString(),
                sessionId = session.id,
                role = "assistant",
                content = content,
                sender = senderName,
                timestamp = System.currentTimeMillis().toString(),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = modelName,
                createdAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            ))

            // 更新会话元信息
            val now = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            val msgCount = messageDao.countBySession(session.id)
            sessionDao.touch(session.id, content.take(200), msgCount, now)
        }

        // 记录 Token 用量到 TokenStatsManager（内存统计）
        if (inputTokens != null || outputTokens != null) {
            try {
                getGlobalTokenStatsManager().recordUsage(
                    promptTokens = inputTokens ?: 0,
                    completionTokens = outputTokens ?: 0,
                    model = modelName,
                    sessionId = session.id,
                    userId = "local-user",
                    purpose = TokenStatsManager.PURPOSE_CHAT
                )
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "TokenStats 记录失败: ${e.message}")
            }
            // 同时持久化到 SharedPreferences（供 tokenStats()/tokenRankings() 聚合读取）
            try {
                onTokenRecorded?.invoke(
                    session.id,
                    modelName,
                    inputTokens ?: 0,
                    outputTokens ?: 0,
                    com.nekobot.app.data.local.LocalRepository.nowIsoStatic(),
                    TokenStatsManager.PURPOSE_CHAT
                )
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "持久化 Token 记录失败: ${e.message}")
            }
        }
    }

    // ---- AI 模型交互 ----

    /** 故障转移队列：activeModel 优先，附加 [failoverQueue] */
    private val modelQueue: List<LocalAiModelEntity> = listOf(activeModel) + failoverQueue.filter { it.id != activeModel.id }

    override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall {
        // 在模型调用前触发 character.before_turn.finished（此时 ctx.characterTurn 已就绪）
        triggerBeforeTurnHook(ctx)
        return { messages, stopped ->
            val extra = buildMap<String, Any?> {
                activeModel.temperature?.let { put("temperature", it) }
                activeModel.maxTokens?.let { put("max_tokens", it) }
                activeModel.topP?.let { put("top_p", it) }
                if (tools.isNotEmpty()) put("tools", tools)
            }

            // 优先走 coordinator（持久化健康状态 + token 限额 + 超时）；为空时回退到内存版
            val result = if (coordinator != null) {
                kotlinx.coroutines.runBlocking {
                    try {
                        val exec = coordinator.execute(modelQueue, activeModel.purpose.ifBlank { "chat" }) { model ->
                            chatOnceForGeneration(model, messages, extra)
                        }
                        // 保留工具调用等结构化响应，并补充实际使用的模型。
                        exec.value.copy(
                            usedModelId = exec.model.id,
                            usedModelName = exec.model.name
                        )
                    } catch (e: FailoverAllFailedException) {
                        LocalAiResult("", error = e.message ?: "所有模型均失败")
                    }
                }
            } else {
                kotlinx.coroutines.runBlocking {
                    aiClient.chatOnceWithFailover(
                        modelQueue,
                        messages,
                        extra,
                        requestTag = session.id,
                        shouldStop = { generationController.isStopped }
                    )
                }
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
                if (result.toolCalls.isNotEmpty()) put("tool_calls", result.toolCalls)
                if (result.thinkingContent.isNotBlank()) {
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
            }

            // 流式回退到非流式 coordinator/chatOnceWithFailover（流式故障转移在 LocalRepository.chat 中处理）
            val result = if (coordinator != null) {
                kotlinx.coroutines.runBlocking {
                    try {
                        val exec = coordinator.execute(modelQueue, activeModel.purpose.ifBlank { "chat" }) { model ->
                            chatOnceForGeneration(model, messages, extra)
                        }
                        exec.value.copy(
                            usedModelId = exec.model.id,
                            usedModelName = exec.model.name
                        )
                    } catch (e: FailoverAllFailedException) {
                        LocalAiResult("", error = e.message ?: "所有模型均失败")
                    }
                }
            } else {
                kotlinx.coroutines.runBlocking {
                    aiClient.chatOnceWithFailover(
                        modelQueue,
                        messages,
                        extra,
                        requestTag = session.id,
                        shouldStop = { generationController.isStopped }
                    )
                }
            }

            if (result.error != null) {
                throw RuntimeException(result.error)
            }

            // 模型调用完成 → 触发 model.after_call hook
            triggerModelAfterCallHook(ctx, result.usedModelName ?: activeModel.name)

            listOf(buildMap<String, Any> {
                put("content", result.content)
                put("usage", result.usage)
                put("_model_id", result.usedModelId ?: activeModel.id)
                put("_model_name", result.usedModelName ?: activeModel.name)
            })
        }
    }

    private suspend fun chatOnceForGeneration(
        model: LocalAiModelEntity,
        messages: List<Map<String, Any>>,
        extra: Map<String, Any?>
    ): LocalAiResult {
        if (generationController.isStopped) {
            throw kotlinx.coroutines.CancellationException("生成已停止")
        }
        return try {
            aiClient.chatOnce(model, messages, extra, requestTag = session.id)
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
            sender = if (session.sessionMode.equals("group", ignoreCase = true)) {
                (ctx.metadata["group_speaker_name"] as? String) ?: character?.name
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
            onUpdate = { card ->
                emitEvent(RealtimeEvent.ThinkingCardUpdate(card))
                onThinkingCardUpdate?.invoke(card)
            }
        )
    }

    // ---- 工具确认 ----

    override fun onConfirmationRequired(ctx: PipelineContext, requestId: String, command: String) {
        emitEvent(RealtimeEvent.Error("需要确认: $command [ID: $requestId]"))
    }

    override fun checkConfirmation(ctx: PipelineContext, userInput: String): String? {
        return detectConfirmation(userInput)
    }

    // ---- 知识库 ----

    override fun searchKnowledge(ctx: PipelineContext, query: String): String = ""

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

    override fun executeTool(toolName: String, args: Map<String, Any>, toolContext: Map<String, Any>): Map<String, Any> {
        if (parseMcpToolName(toolName) != null) {
            return mcpToolExecutor?.invoke(toolName, args)
                ?: mapOf("success" to false, "error" to "MCP 工具运行时不可用")
        }
        if (toolName in localSkillToolIds) {
            return skillToolExecutor?.invoke(toolName, args)
                ?: mapOf("success" to false, "error" to "Skills 存储运行时不可用")
        }
        if (toolName in localDbToolIds) {
            return dbToolExecutor?.invoke(toolName, args) ?: localDbToolExecutor.execute(toolName, args)
        }
        return localToolExecutor.execute(toolName, args)
    }
}
