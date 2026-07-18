package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.channels.Channel

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
 */
class LocalPipelineCallbacks(
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
    /** 故障转移队列：activeModel 优先 + 同 purpose 其他启用模型，按 priority 升序 */
    private val failoverQueue: List<LocalAiModelEntity> = emptyList(),
    /** 持久化故障转移协调器；非空时 [buildModelCall] 走 coordinator，否则回退到 chatOnceWithFailover */
    private val coordinator: FailoverCoordinator? = null
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
            }
        )
    }

    /** 流式事件通道（UI 层收集），UNLIMITED 避免背压阻塞 */
    val eventChannel: Channel<RealtimeEvent> = Channel(Channel.UNLIMITED)

    /** 非阻塞 emit：Channel.trySend 不会挂起 */
    private fun emitEvent(event: RealtimeEvent) {
        eventChannel.trySend(event)
    }

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
            .dropLast(1)  // 最后一条是刚保存的用户消息

        // 无角色的 Agent 会话沿用旧聊天流程的提示词/世界书组装规则，
        // 仅将执行入口切换到 Pipeline，以便获得进度卡片事件。
        if (character == null) {
            return LocalPromptBuilder.build(
                session = session,
                character = null,
                history = history,
                userInput = ctx.chatRequest.content,
                worldBookEntries = worldBookEntries
            )
        }

        val messages = mutableListOf<Map<String, Any>>()

        // 历史消息
        for (msg in history) {
            messages.add(mapOf(
                "role" to msg.role,
                "content" to msg.content
            ))
        }

        // 当前用户消息
        messages.add(mapOf("role" to "user", "content" to ctx.chatRequest.content))

        return messages
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

        // 保存到 Room（同步执行，因为已在 IO 线程）
        kotlinx.coroutines.runBlocking {
            messageDao.upsert(LocalMessageEntity(
                id = (message["id"] as? String) ?: java.util.UUID.randomUUID().toString(),
                sessionId = session.id,
                role = "assistant",
                content = content,
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
                            aiClient.chatOnce(model, messages, extra)
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
                    aiClient.chatOnceWithFailover(modelQueue, messages, extra)
                }
            }

            if (result.error != null) {
                throw RuntimeException(result.error)
            }

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
                            aiClient.chatOnce(model, messages, extra)
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
                    aiClient.chatOnceWithFailover(modelQueue, messages, extra)
                }
            }

            if (result.error != null) {
                throw RuntimeException(result.error)
            }

            listOf(buildMap<String, Any> {
                put("content", result.content)
                put("usage", result.usage)
                put("_model_id", result.usedModelId ?: activeModel.id)
                put("_model_name", result.usedModelName ?: activeModel.name)
            })
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
        // 简化：本地模式直接返回附件元数据
        return attachment
    }

    // ---- 后处理 ----

    override fun onResponseComplete(ctx: PipelineContext, result: PipelineResult) {
        com.nekobot.app.data.local.LocalLogger.d(TAG, "Response complete: ${result.finalContent.length} chars, error=${result.error}")
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
        return localToolExecutor.execute(toolName, args)
    }
}
