package com.nekobot.app.ui.screens.chat

import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.remote.ExecConfirmationRequest
import com.nekobot.app.data.remote.HookNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class MessageTtsStatus { Generating, Ready, Error }

data class MessageTtsUiState(
    val status: MessageTtsStatus,
    val error: String? = null
)

/**
 * 本地后台手动压缩的结果事件。
 *
 * [ChatSessionState.compressionEvents] replay=0：仅通知当前存活的订阅者；
 * 页面退出期间完成的压缩由重新 loadMessages 恢复显示。
 */
data class ContextCompressionEvent(
    val sessionId: String,
    val compressed: Boolean,
    val archiveSessionId: String? = null,
    val error: String? = null
)

/**
 * Agent 会话在 AI 生成期间排队的待发送消息。
 *
 * - 生成结束后自动发送队顶消息（[ChatSessionState.queuedMessages]）
 * - 用户点击“立即发送”时进入 [ChatSessionState.urgentMessages]，
 *   由本地 Agent 工具循环在下一次模型调用前注入上下文
 */
data class QueuedChatMessage(
    val id: String,
    /** 用户输入的原始文本 */
    val content: String,
    /** 随消息发送的附件（自动发送时完整重建消息内容） */
    val attachments: List<Map<String, Any>> = emptyList(),
    /** 排队时的思考强度选择 */
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 单个会话的跨 ViewModel 共享运行时状态。
 *
 * 设计动机：聊天界面的 AI 生成流程（chatStream / 命名 / 故事图 / life_sim / TTS / 通知等）
 * 不应随 [ChatViewModel] 的销毁而中断。把"需要跨 VM 持久"的状态与 Job 挪到这里管理，
 * [ChatViewModel] 只是这些 StateFlow 的订阅者，VM 销毁时只减少引用计数，不取消 Job。
 *
 * 字段不加锁：所有写入都在 [ServiceContainer.applicationScope] 的单线程协程上下文中进行，
 * StateFlow 自身线程安全。
 */
class ChatSessionState(val sessionId: String) {

    // ============ 跨 VM 共享的 UI 状态 ============
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val sending = MutableStateFlow(false)
    val execConfirmation = MutableStateFlow<ExecConfirmationRequest?>(null)
    /** ask_user_question 提问请求（AI 调用提问工具后挂起等待用户回答）。 */
    val askUserQuestion =
        MutableStateFlow<com.nekobot.app.data.local.ai.AskUserQuestionRequest?>(null)
    val plotChoices = MutableStateFlow<List<PlotChoice>>(emptyList())
    val plotChoicesLoading = MutableStateFlow(false)
    val hookNotifications = MutableStateFlow<List<HookNotification>>(emptyList())
    val ttsStates = MutableStateFlow<Map<String, MessageTtsUiState>>(emptyMap())
    val agentContextCompressionInProgress = MutableStateFlow(false)
    /** Agent 任务列表（todo_write 工具更新；输入框上方可折叠面板展示）。 */
    val agentTodos = MutableStateFlow<List<com.nekobot.app.data.model.AgentTodo>>(emptyList())

    // ============ Agent 会话消息排队 ============
    /**
     * AI 生成期间用户发送的消息队列（FIFO）。
     * 当前生成结束后自动发送队顶消息；用户也可手动“立即发送”。
     */
    val queuedMessages = MutableStateFlow<List<QueuedChatMessage>>(emptyList())
    /**
     * 请求“立即发送”的排队消息：由本地 Agent 工具循环在下一次模型调用前
     * 消费并注入上下文；线程安全队列，跨线程 drain/enqueue。
     */
    val urgentMessages = java.util.concurrent.ConcurrentLinkedQueue<QueuedChatMessage>()

    /** 将未消费的加急消息移回排队队列队首（生成已结束时兜底回收）。 */
    fun recycleUrgentMessages() {
        if (urgentMessages.isEmpty()) return
        val leftovers = mutableListOf<QueuedChatMessage>()
        while (true) {
            val item = urgentMessages.poll() ?: break
            leftovers += item
        }
        if (leftovers.isEmpty()) return
        queuedMessages.value = leftovers + queuedMessages.value
    }

    // ============ 后台压缩 Job（挂到 applicationScope，不随 VM 销毁）============
    /** 本地模式手动上下文压缩的后台 Job：退出会话页面后仍继续执行。 */
    @Volatile
    var compressionJob: Job? = null
    /** 手动压缩结果事件：replay=0，仅投递给当前存活的订阅者。 */
    val compressionEvents = MutableSharedFlow<ContextCompressionEvent>(extraBufferCapacity = 8)

    /**
     * 本次运行时生命周期内被用户删除的消息 id。
     * loadMessages 的孤儿 assistant 保留逻辑需要跳过这些消息，
     * 否则刚删除的命令回复会因 Room 异步竞态兜底被重新加回列表。
     */
    val deletedMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ============ 流式生成的临时可变状态 ============
    /** 流式生成中的临时消息内容累加器 */
    val streamingContent = StringBuilder()
    /** 流式生成中的模型思考内容累加器 */
    val streamingReasoning = StringBuilder()
    /** 仅供当前流式气泡订阅的正文预览，避免刷新整份消息列表。 */
    val streamingContentPreview = MutableStateFlow("")
    /** 仅供当前流式气泡订阅的思考预览（非 Agent 会话）。 */
    val streamingReasoningPreview = MutableStateFlow("")
    /** 上次流式 chunk 更新 UI 的时间戳，用于节流（避免高频 chunk 触发 MarkdownText 全量重解析） */
    @Volatile
    var lastStreamUiUpdateMs: Long = 0L
    /** 流式节流间隔（毫秒） */
    val streamThrottleMs = 100L
    /** 用户是否请求停止生成 */
    @Volatile
    var generationStopRequested = false

    // ============ 后台 Job（挂到 applicationScope，不随 VM 销毁） ============
    /** 收集 Socket.IO 事件 / 本地 Hook 事件的 Job */
    @Volatile
    var eventsJob: Job? = null
    /** 本地模式流式聊天收集 Job */
    @Volatile
    var localChatJob: Job? = null
    /** 正文已经完成，但自动命名/剧情选项等后处理可能仍在运行。 */
    @Volatile
    private var localResponseComplete = false
    val ttsJobs = ConcurrentHashMap<String, Job>()

    /** 引用计数：ChatViewModel.init 时 +1，onCleared 时 -1；为 0 且无活跃 Job 时可被清理 */
    @Volatile
    var subscriberCount: Int = 0

    /** 重置流式生成相关临时状态（用于新一轮发送前清场） */
    fun resetStreamingState() {
        streamingContent.setLength(0)
        streamingReasoning.setLength(0)
        streamingContentPreview.value = ""
        streamingReasoningPreview.value = ""
        lastStreamUiUpdateMs = 0L
        generationStopRequested = false
    }

    /**
     * 同一会话只允许一个前台回复；正文完成后的旧后处理可被新一轮安全抢占。
     * 新 Job 先成为当前所有者，再取消旧 Job，旧 completion 无法清理新状态。
     */
    @Synchronized
    fun installLocalChatJob(job: Job): Boolean {
        val previous = localChatJob
        if (previous?.isActive == true && !localResponseComplete) return false
        localChatJob = job
        localResponseComplete = false
        if (previous?.isActive == true) previous.cancel()
        return true
    }

    /** 正文/最终消息已经可见，立即释放输入；后台后处理仍由当前 Job 承担。 */
    @Synchronized
    fun markLocalResponseComplete(job: Job): Boolean {
        if (localChatJob !== job) return false
        localResponseComplete = true
        sending.value = false
        return true
    }

    @Synchronized
    fun ownsLocalChatJob(job: Job): Boolean = localChatJob === job

    /** 仅允许 Job 清理自己，避免上一轮 completion 回调误清掉下一轮。 */
    @Synchronized
    fun clearLocalChatJob(job: Job): Boolean {
        if (localChatJob !== job) return false
        localChatJob = null
        localResponseComplete = false
        return true
    }

    /** 同一会话同时只允许一个后台手动压缩；LAZY Job 先安装再 start，避免重复触发。 */
    @Synchronized
    fun installCompressionJob(job: Job): Boolean {
        if (compressionJob?.isActive == true) return false
        compressionJob = job
        return true
    }

    /** 仅允许压缩 Job 自身的 completion 清理，避免误清新一轮压缩。 */
    @Synchronized
    fun clearCompressionJob(job: Job): Boolean {
        if (compressionJob !== job) return false
        compressionJob = null
        return true
    }

    /** 只有尚未产出最终回复的 Job 才阻塞下一条消息。 */
    fun hasBlockingLocalChatJob(): Boolean =
        localChatJob?.isActive == true && !localResponseComplete

    /** 是否有一轮 AI 回复仍在生成；被动事件监听和 TTS 不应阻止重新加载持久化消息。 */
    fun hasActiveGeneration(): Boolean =
        sending.value || hasBlockingLocalChatJob()

    /** 是否还有活跃的后台 Job */
    fun hasActiveJobs(): Boolean =
        hasActiveGeneration() ||
            localChatJob?.isActive == true ||
            eventsJob?.isActive == true ||
            ttsJobs.values.any { it.isActive }

    /**
     * 是否仍有必须跨页面保留的实际工作。事件监听本身不算工作，否则它会让会话状态
     * 永远无法回收，并间接持有已经退出页面的 ViewModel 与整份 Agent 进度数据。
     */
    fun hasRetainedWork(): Boolean =
        sending.value ||
            localChatJob?.isActive == true ||
            compressionJob?.isActive == true ||
            ttsJobs.values.any { it.isActive } ||
            queuedMessages.value.isNotEmpty() ||
            urgentMessages.isNotEmpty()
}

/**
 * 全局会话运行时管理器。按 sessionId 维护一份 [ChatSessionState]。
 *
 * - [acquire]：ChatViewModel.init 时调用，返回（或创建）状态并增加引用计数
 * - [release]：ChatViewModel.onCleared 时调用，减少引用计数；为 0 且无活跃 Job 时移除
 * - [get]：在不需要增加引用计数的场景下访问（如发送通知时查找状态）
 *
 * 注意：状态可能在没有 ChatViewModel 时仍然存在（后台生成中），此时 UI 已销毁，
 * 但状态保留供下次进入界面恢复显示。
 */
object ChatSessionManager {

    private val sessions = ConcurrentHashMap<String, ChatSessionState>()

    /** 获取或创建会话状态，并增加引用计数。 */
    fun acquire(sessionId: String): ChatSessionState = sessions.compute(sessionId) { _, existing ->
        val state = existing ?: ChatSessionState(sessionId)
        synchronized(state) { state.subscriberCount++ }
        state
    }!!

    /** 减少引用计数；为 0 且无实际后台工作时取消监听并从内存移除。 */
    fun release(sessionId: String) {
        sessions.computeIfPresent(sessionId) { _, state ->
            synchronized(state) {
                state.subscriberCount = (state.subscriberCount - 1).coerceAtLeast(0)
                retainOrDispose(state)
            }
        }
    }

    /** 后台聊天/TTS 完成后再次尝试回收没有页面订阅者的会话。 */
    fun pruneIfIdle(sessionId: String) {
        sessions.computeIfPresent(sessionId) { _, state ->
            synchronized(state) { retainOrDispose(state) }
        }
    }

    private fun retainOrDispose(state: ChatSessionState): ChatSessionState? {
        if (state.subscriberCount > 0 || state.hasRetainedWork()) return state
        state.eventsJob?.cancel()
        state.eventsJob = null
        return null
    }

    /** 不增加引用计数地访问会话状态（可能为 null，如尚未 init 或已清理）。 */
    fun get(sessionId: String): ChatSessionState? = sessions[sessionId]

    /** 应用退出时清理所有会话状态（取消所有后台 Job）。 */
    fun releaseAll() {
        sessions.values.forEach { state ->
            state.localChatJob?.cancel()
            state.compressionJob?.cancel()
            state.eventsJob?.cancel()
            state.ttsJobs.values.forEach { it.cancel() }
        }
        sessions.clear()
    }

    /** 当前正在后台生成中的会话 ID 列表（用于诊断/通知路由）。 */
    fun activeSessionIds(): Set<String> = sessions.values
        .filter { it.hasRetainedWork() }
        .map { it.sessionId }
        .toSet()
}
