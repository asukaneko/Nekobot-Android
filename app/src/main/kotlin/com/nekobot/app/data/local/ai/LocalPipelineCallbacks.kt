package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
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
    private val onTokenRecorded: ((sessionId: String, model: String, inputTokens: Int, outputTokens: Int, timestamp: String) -> Unit)? = null
) : PipelineCallbacks() {

    companion object {
        private const val TAG = "LocalPipelineCB"
    }

    private val gson = Gson()
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()

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
                Log.w(TAG, "TokenStats 记录失败: ${e.message}")
            }
            // 同时持久化到 SharedPreferences（供 tokenStats()/tokenRankings() 聚合读取）
            try {
                onTokenRecorded?.invoke(
                    session.id,
                    modelName,
                    inputTokens ?: 0,
                    outputTokens ?: 0,
                    com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
                )
            } catch (e: Exception) {
                Log.w(TAG, "持久化 Token 记录失败: ${e.message}")
            }
        }
    }

    // ---- AI 模型交互 ----

    override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall {
        return { messages, stopped ->
            val extra = buildMap<String, Any?> {
                activeModel.temperature?.let { put("temperature", it) }
                activeModel.maxTokens?.let { put("max_tokens", it) }
                activeModel.topP?.let { put("top_p", it) }
                if (tools.isNotEmpty()) put("tools", tools)
            }

            // 使用 LocalAiClient.chatOnce 同步调用
            val result = kotlinx.coroutines.runBlocking {
                aiClient.chatOnce(activeModel, messages, extra)
            }

            if (result.error != null) {
                throw RuntimeException(result.error)
            }

            buildMap<String, Any> {
                put("content", result.content)
                put("usage", result.usage)
                put("finish_reason", "stop")
                put("_model_id", activeModel.id)
                put("_model_name", activeModel.name)
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

            // 这里返回单个"chunk"，实际流式由 runStreaming 内部处理
            // 为了复用 LocalAiClient 的流式能力，我们在 onStreamStart/onStreamChunk 中处理
            // 此方法返回包含完整内容的列表（简化）
            val result = kotlinx.coroutines.runBlocking {
                aiClient.chatOnce(activeModel, messages, extra)
            }

            if (result.error != null) {
                throw RuntimeException(result.error)
            }

            listOf(buildMap<String, Any> {
                put("content", result.content)
                put("usage", result.usage)
                put("_model_id", activeModel.id)
                put("_model_name", activeModel.name)
            })
        }
    }

    // ---- 输出 / 回复 ----

    override fun sendResponse(ctx: PipelineContext, message: Map<String, Any>) {
        // 非流式：无需额外推送（消息已通过 saveAssistantMessage 保存）
        // UI 层通过 loadMessages() 刷新
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

    override fun getProgressReporter(ctx: PipelineContext): ProgressReporter = ProgressReporter()

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

    override fun ensureWorkspace(ctx: PipelineContext): String = ""

    override fun getWorkspaceContext(ctx: PipelineContext): Map<String, Any> = emptyMap()

    override fun getMemoryContext(ctx: PipelineContext): Map<String, Any> = emptyMap()

    // ---- 附件解析 ----

    override fun resolveAttachmentData(ctx: PipelineContext, attachment: Map<String, Any>): Map<String, Any>? {
        // 简化：本地模式直接返回附件元数据
        return attachment
    }

    // ---- 后处理 ----

    override fun onResponseComplete(ctx: PipelineContext, result: PipelineResult) {
        Log.d(TAG, "Response complete: ${result.finalContent.length} chars, error=${result.error}")
    }

    // ---- 角色运行时 ----

    override fun getCharacterContext(ctx: PipelineContext): CharacterIdentity? = characterIdentity

    override fun getCharacterRuntime(ctx: PipelineContext): CharacterRuntime? = characterRuntime

    // ---- 工具执行 ----

    override fun executeTool(toolName: String, args: Map<String, Any>, toolContext: Map<String, Any>): Map<String, Any> {
        // 本地模式暂不支持工具执行
        return mapOf("error" to "本地模式不支持工具调用: $toolName")
    }
}
