package com.nekobot.app.data.local.ai

/**
 * AI 管线数据模型与回调接口，对应原仓库 nbot/core/ai_pipeline.py 的数据类部分。
 *
 * 包含：PipelineContext / PipelineResult / ProgressReporter / PipelineCallbacks。
 * 所有频道（web/qq/local）通过实现 PipelineCallbacks 接入统一管线。
 */

// ============================================================================
// 管线上下文
// ============================================================================

/**
 * 贯穿 AI 管道的上下文，承载输入 → 中间状态 → 输出。
 *
 * 对应原仓库 PipelineContext。
 */
class PipelineContext(
    /** 聊天请求 */
    val chatRequest: ChatRequest,
    /** 频道适配器（可选，用于构建 assistant_message 等） */
    val adapter: Any? = null
) {
    // === 会话 / 消息准备 ===
    /** 准备好的消息列表（含 system prompt） */
    var messages: List<Map<String, Any>> = emptyList()
    /** 工具调用历史（用于"继续"功能） */
    var toolCallHistory: List<Map<String, Any>>? = null

    // === 知识库 ===
    /** RAG 检索到的知识文本 */
    var knowledgeText: String = ""
    var knowledgeRetrieved: Boolean = false

    // === 附件处理 ===
    /** 图片 URL 列表（可能是 http URL 或 base64 data URI） */
    val imageUrls: MutableList<String> = mutableListOf()
    /** 图片附件名列表（与 imageUrls 一一对应，用于描述标注） */
    val imageNames: MutableList<String?> = mutableListOf()
    /** 视觉模型识别后的图片描述列表 */
    val imageDescriptions: MutableList<String> = mutableListOf()
    /** 文件文本内容列表 */
    val fileContents: MutableList<String> = mutableListOf()

    // === 工具上下文 ===
    /** 工具执行上下文（工作区路径等） */
    var toolContext: Map<String, Any> = emptyMap()

    // === 停止控制 ===
    /** 停止标志（true 表示用户请求停止） */
    @Volatile
    var stopped: Boolean = false
    /** 外部会话级停止信号；用于同步模型/工具循环主动轮询。 */
    var stopRequested: () -> Boolean = { false }

    fun shouldStop(): Boolean = stopped || stopRequested()

    // === 流式状态 ===
    /** 流式消息对象（流式期间累积） */
    var streamedMessage: Map<String, Any>? = null

    // === 角色运行时 ===
    /** 角色运行时本轮上下文 */
    var characterTurn: CharacterTurnContext? = null

    // === PromptStack ===
    /** 本轮提示词栈 */
    val promptStack: PromptStack = PromptStack()

    // === 结果 ===
    /** 最终回复文本 */
    var finalContent: String = ""
    /** 本轮模型返回的完整思考/推理文本。 */
    var finalReasoning: String = ""
    /** 是否被用户提前停止 */
    var stoppedPrematurely: Boolean = false
    /** 工具调用追踪 */
    var toolTrace: List<Map<String, Any>> = emptyList()
    /** 连续错误计数 */
    var consecutiveErrors: Int = 0
    /** token 用量 */
    var usage: Map<String, Any> = emptyMap()
    /** 错误信息 */
    var error: String? = null
    /** 扩展元数据 */
    val metadata: MutableMap<String, Any> = mutableMapOf()
}

// ============================================================================
// 管线结果
// ============================================================================

/**
 * 管道处理结果。
 * 对应原仓库 PipelineResult。
 */
data class PipelineResult(
    val finalContent: String = "",
    val assistantMessage: Map<String, Any>? = null,
    val toolTrace: List<Map<String, Any>> = emptyList(),
    val canContinue: Boolean = false,
    val stoppedPrematurely: Boolean = false,
    val usage: Map<String, Any> = emptyMap(),
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    /** 转换为 ChatResponse */
    fun toChatResponse(): ChatResponse = ChatResponse(
        finalContent = finalContent,
        assistantMessage = assistantMessage,
        toolTrace = toolTrace,
        canContinue = canContinue,
        usage = usage,
        error = error,
        metadata = metadata
    )
}

// ============================================================================
// 进度报告接口
// ============================================================================

/**
 * 抽象的进度报告接口。
 * Web 频道通过实现此接口推送实时进度，其他频道使用默认空实现。
 *
 * 对应原仓库 ProgressReporter。
 */
open class ProgressReporter {
    open fun onPreparingStart(ctx: PipelineContext) {}
    open fun onAttachmentStart(ctx: PipelineContext, count: Int) {}
    open fun onAttachmentItem(ctx: PipelineContext, name: String, itemType: String) {}
    open fun onAttachmentItemDone(ctx: PipelineContext, name: String, success: Boolean, resultPreview: String = "") {}
    open fun onAttachmentsDone(ctx: PipelineContext) {}
    open fun onKnowledgeStart(ctx: PipelineContext) {}
    open fun onKnowledgeDone(ctx: PipelineContext, retrieved: Boolean) {}
    open fun onThinkingStart(ctx: PipelineContext) {}
    open fun onThinkingContent(ctx: PipelineContext, content: String) {}
    open fun onToolStart(ctx: PipelineContext, toolName: String, arguments: Map<String, Any>, thinking: String) {}
    open fun onToolDone(ctx: PipelineContext, toolName: String, result: Map<String, Any>, thinking: String) {}
    open fun onToolIteration(ctx: PipelineContext, iteration: Int) {}
    open fun onSendMessage(ctx: PipelineContext, content: String) {}
    open fun onSendFile(ctx: PipelineContext, filePath: String, filename: String) {}
    open fun onDone(ctx: PipelineContext) {}
    open fun onWaitingConfirmation(ctx: PipelineContext, command: String, requestId: String) {}
}

// ============================================================================
// 模型调用函数类型
// ============================================================================

/**
 * 模型调用函数签名。
 *
 * @param messages 消息列表
 * @param stopped 是否已停止
 * @return 模型响应字典（含 content / tool_calls / usage / finish_reason 等）
 */
typealias ModelCall = (messages: List<Map<String, Any>>, stopped: Boolean) -> Map<String, Any>

/**
 * 流式模型调用函数签名，返回 chunk 序列。
 */
typealias StreamModelCall = (messages: List<Map<String, Any>>, stopped: Boolean) -> List<Map<String, Any>>

// ============================================================================
// 管线回调接口
// ============================================================================

/**
 * 频道需实现的回调接口。
 *
 * 所有方法都有默认实现，简单频道只需覆写约 2-4 个方法。
 * 对应原仓库 PipelineCallbacks。
 */
open class PipelineCallbacks {

    // ---- 会话 / 消息 I/O ----

    /**
     * 返回会话的完整消息列表（包含 system prompt）。
     * 默认：用 getSystemPrompt + 当前用户消息构建。
     */
    open fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> {
        val system = getSystemPrompt(ctx)
        val messages = mutableListOf<Map<String, Any>>()
        if (system.isNotEmpty()) {
            messages.add(mapOf("role" to "system", "content" to system))
        }
        messages.add(mapOf("role" to "user", "content" to ctx.chatRequest.content))
        return messages
    }

    /** 返回此会话的系统提示词 */
    open fun getSystemPrompt(ctx: PipelineContext): String = ""

    /** 持久化助手消息到会话存储 */
    open fun saveAssistantMessage(ctx: PipelineContext, message: Map<String, Any>) {}

    // ---- AI 模型交互 ----

    /**
     * 返回 model_call 函数。
     * 子类必须覆写此方法以提供实际的 AI 调用能力。
     *
     * @param ctx 管线上下文
     * @param tools 可用工具定义列表（空列表表示不启用工具）
     * @return model_call 函数
     */
    open fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall {
        throw NotImplementedError("buildModelCall must be implemented by the channel")
    }

    /** 返回流式 model_call 或 null（不支持流式） */
    open fun buildModelCallStreaming(ctx: PipelineContext, tools: List<Map<String, Any>>): StreamModelCall? = null

    // ---- 输出 / 回复 ----

    /** 发送最终助手消息给用户。必须覆写。 */
    open fun sendResponse(ctx: PipelineContext, message: Map<String, Any>) {
        throw NotImplementedError("sendResponse must be implemented by the channel")
    }

    open fun onStreamStart(ctx: PipelineContext, message: Map<String, Any>) {}
    open fun onReasoningChunk(ctx: PipelineContext, chunk: String, messageId: String) {}
    open fun onStreamChunk(ctx: PipelineContext, chunk: String, messageId: String) {}
    open fun onStreamEnd(ctx: PipelineContext, messageId: String) {}

    // ---- 进度报告 ----

    /** 返回 ProgressReporter 实例。默认返回空实现。 */
    open fun getProgressReporter(ctx: PipelineContext): ProgressReporter = ProgressReporter()

    // ---- 工具确认 ----

    /** 工具需要用户确认时调用 */
    open fun onConfirmationRequired(ctx: PipelineContext, requestId: String, command: String) {}

    /**
     * 检查用户输入是否为确认/拒绝。
     * @return "confirm" / "reject" / null
     */
    open fun checkConfirmation(ctx: PipelineContext, userInput: String): String? = null

    // ---- 知识库 ----

    /** 搜索知识库并返回格式化文本。默认不检索。 */
    open fun searchKnowledge(ctx: PipelineContext, query: String): String = ""

    // ---- 工作区 ----

    /** 确保会话工作区存在。返回工作区路径。 */
    open fun ensureWorkspace(ctx: PipelineContext): String = ""

    /** 返回工作区上下文字典（供工具使用） */
    open fun getWorkspaceContext(ctx: PipelineContext): Map<String, Any> = emptyMap()

    /** 返回自动记忆需要的频道上下文 */
    open fun getMemoryContext(ctx: PipelineContext): Map<String, Any> = getWorkspaceContext(ctx)

    // ---- 附件解析 ----

    /**
     * 解析单个附件，返回 {type, name, data, path, text_content, error} 或 null。
     */
    open fun resolveAttachmentData(ctx: PipelineContext, attachment: Map<String, Any>): Map<String, Any>? = null

    /**
     * 调用视觉模型识别图片内容，返回每张图片的文本描述。
     *
     * - 输入 [imageUrls] 为待识别的图片 URL 或 base64 data URI 列表
     * - 返回与输入等长的描述列表；识别失败的图片返回失败标记文本（非空）
     * - 默认实现返回空列表，表示该频道不支持视觉识别
     *
     * 此方法为 suspend，子类可通过协程调用异步视觉 API。
     */
    open suspend fun resolveImages(ctx: PipelineContext, imageUrls: List<String>): List<String> = emptyList()

    // ---- 后处理 ----

    /** AI 响应完成后的回调 */
    open fun onResponseComplete(ctx: PipelineContext, result: PipelineResult) {}

    // ---- 角色运行时 ----

    /** 返回角色身份标识 (CharacterIdentity)，默认 null 表示不启用角色运行时 */
    open fun getCharacterContext(ctx: PipelineContext): CharacterIdentity? = null

    /** 返回 CharacterRuntime 实例，默认 null */
    open fun getCharacterRuntime(ctx: PipelineContext): CharacterRuntime? = null

    // ---- 工具执行 ----

    /**
     * 执行工具调用。
     * @param toolName 工具名
     * @param args 工具参数
     * @param toolContext 工具上下文
     * @return 工具执行结果字典
     */
    open fun executeTool(toolName: String, args: Map<String, Any>, toolContext: Map<String, Any>): Map<String, Any> = emptyMap()
}
