package com.nekobot.app.ui.screens.chat

import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.remote.ExecConfirmationRequest
import com.nekobot.app.data.remote.HookNotification
import kotlinx.coroutines.Job
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
    val plotChoices = MutableStateFlow<List<PlotChoice>>(emptyList())
    val plotChoicesLoading = MutableStateFlow(false)
    val hookNotifications = MutableStateFlow<List<HookNotification>>(emptyList())
    val ttsStates = MutableStateFlow<Map<String, MessageTtsUiState>>(emptyMap())

    // ============ 流式生成的临时可变状态 ============
    /** 流式生成中的临时消息内容累加器 */
    val streamingContent = StringBuilder()
    /** 上次流式 chunk 更新 UI 的时间戳，用于节流（避免高频 chunk 触发 MarkdownText 全量重解析） */
    @Volatile
    var lastStreamUiUpdateMs: Long = 0L
    /** 流式节流间隔（毫秒） */
    val streamThrottleMs = 60L
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
    val ttsJobs = ConcurrentHashMap<String, Job>()

    /** 引用计数：ChatViewModel.init 时 +1，onCleared 时 -1；为 0 且无活跃 Job 时可被清理 */
    @Volatile
    var subscriberCount: Int = 0

    /** 重置流式生成相关临时状态（用于新一轮发送前清场） */
    fun resetStreamingState() {
        streamingContent.setLength(0)
        lastStreamUiUpdateMs = 0L
        generationStopRequested = false
    }

    /** 是否还有活跃的后台 Job */
    fun hasActiveJobs(): Boolean =
        localChatJob?.isActive == true ||
            eventsJob?.isActive == true ||
            ttsJobs.values.any { it.isActive }
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
    fun acquire(sessionId: String): ChatSessionState {
        val state = sessions.computeIfAbsent(sessionId) { ChatSessionState(it) }
        synchronized(state) {
            state.subscriberCount++
        }
        return state
    }

    /** 减少引用计数；为 0 且无活跃 Job 时从内存移除。 */
    fun release(sessionId: String) {
        val state = sessions[sessionId] ?: return
        val shouldRemove = synchronized(state) {
            state.subscriberCount = (state.subscriberCount - 1).coerceAtLeast(0)
            state.subscriberCount <= 0 && !state.hasActiveJobs()
        }
        if (shouldRemove) {
            sessions.remove(sessionId)
        }
    }

    /** 不增加引用计数地访问会话状态（可能为 null，如尚未 init 或已清理）。 */
    fun get(sessionId: String): ChatSessionState? = sessions[sessionId]

    /** 应用退出时清理所有会话状态（取消所有后台 Job）。 */
    fun releaseAll() {
        sessions.values.forEach { state ->
            state.localChatJob?.cancel()
            state.eventsJob?.cancel()
            state.ttsJobs.values.forEach { it.cancel() }
        }
        sessions.clear()
    }

    /** 当前正在后台生成中的会话 ID 列表（用于诊断/通知路由）。 */
    fun activeSessionIds(): Set<String> = sessions.values
        .filter { it.hasActiveJobs() }
        .map { it.sessionId }
        .toSet()
}
