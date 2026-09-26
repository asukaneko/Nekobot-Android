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

enum class MessageTtsStatus {
    Generating,
    Ready,
    Error,

    /**
     * 正文已切换到另一版候选，当前语音属于旧版本。
     *
     * 不是错误：音频已被清空（`audio_url = NULL`），这里只提示可以按新正文重新生成。
     */
    Stale
}

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
 * 会话内联状态提示的统一模型。
 *
 * 上下文压缩、自动技能沉淀、自动长期记忆以及后续新增的会话内状态提示都实现本接口，
 * 由 [ChatSessionState.inlineNotices] 统一持有；聊天界面按 [anchorContent]
 * 把提示**渲染在触发它的那条消息气泡下方**，而不是固定贴在消息列表末尾。
 *
 * 新增一种提示的统一动作：
 * 1. 实现本接口（给出 [anchorContent] / [running]，必要时覆写 [anchorMatchesUserMessages] 与 [order]）；
 * 2. 在 [ChatSessionState] 提供 apply 方法写入 [ChatSessionState.inlineNotices]；
 * 3. 在 ChatScreen 的 `ChatInlineNoticeDivider` 中补一个渲染分支（sealed when 会强制补齐）。
 */
sealed interface ChatInlineNotice {
    /** 触发提示的消息正文前缀；空串表示无法锚定，界面回退到列表末尾。 */
    val anchorContent: String

    /** 进行中：渲染转圈动画；完成后渲染静态图标。 */
    val running: Boolean

    /** 锚点是否允许匹配用户消息（压缩由用户消息触发；技能/记忆锚定在 AI 回复上）。 */
    val anchorMatchesUserMessages: Boolean get() = false

    /** 同一条消息上多个提示的渲染顺序（小者在前），保证顺序稳定。 */
    val order: Int get() = 0
}

/**
 * 上下文压缩进度提示：[running] 为 true 时显示"正在压缩上下文"。
 *
 * 压缩结束（成功或失败）后提示即移除：已完成状态由摘要边界分隔线接管。
 * 锚点可以是用户消息（自动压缩由刚发送的消息触发）或任意最后一条消息（手动压缩）。
 */
data class ContextCompressionUiState(
    override val anchorContent: String = "",
    override val running: Boolean = true
) : ChatInlineNotice {
    override val anchorMatchesUserMessages: Boolean get() = true
}

/**
 * 自动技能沉淀的内联提示状态。
 *
 * 形态对齐上下文压缩提示：[running] 为 true 时显示"正在总结技能"；
 * 完成后显示"已自动沉淀/更新技能「X」"并**持久保留**（写进设置，
 * 退出页面或重启应用后重新进入会话仍然可见），直到本会话下一次沉淀结果覆盖它。
 *
 * [anchorContent] 是触发本次审查的回复正文（前缀）：提示渲染在**那条消息下面**。
 */
data class AutoSkillUiState(
    val skillName: String = "",
    val created: Boolean = false,
    override val running: Boolean = true,
    override val anchorContent: String = ""
) : ChatInlineNotice {
    override val order: Int get() = 1
}

/**
 * 自动长期记忆的内联提示状态。
 *
 * 形态与 [AutoSkillUiState] 一致：[running] 为 true 时显示"正在整理长期记忆"；
 * 完成后显示"已自动记忆 N 条"并**持久保留**（写进设置，退出页面或重启应用后
 * 重新进入会话仍然可见），直到本会话下一次抽取结果覆盖它。
 *
 * [anchorContent] 是触发本次抽取的回复正文（前缀）：提示渲染在**那条消息下面**，
 * 而不是一直贴在消息列表末尾——记忆是针对某一轮发生的，提示留在发生处更符合预期。
 */
data class AutoMemoryUiState(
    val changedItems: Int = 0,
    override val running: Boolean = true,
    override val anchorContent: String = ""
) : ChatInlineNotice {
    override val order: Int get() = 2
}

/** 内联提示锚点保留的消息正文长度上限（与 PrefsManager 的持久化长度保持一致）。 */
private const val AGENT_NOTICE_ANCHOR_CHARS = 200

/** 截取消息正文前缀作为内联提示锚点。 */
internal fun noticeAnchor(content: String?): String =
    content.orEmpty().trim().take(AGENT_NOTICE_ANCHOR_CHARS)

/**
 * 把内联提示解析到应渲染在哪条消息之后（返回消息下标）。
 *
 * 锚点是触发提示的消息正文前缀：取最后一条匹配的消息（同文本时以最新一条为准）。
 * 找不到（消息被删、被压缩归档、或锚点为空）时返回 null，
 * 由界面回退到贴列表末尾，避免提示整个消失。
 */
internal fun resolveInlineNoticeAnchorIndex(
    notice: ChatInlineNotice,
    messages: List<Message>
): Int? {
    val anchor = notice.anchorContent.trim()
    if (anchor.isEmpty()) return null
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        if (!notice.anchorMatchesUserMessages && message.isUser) continue
        if (message.content?.trim()?.startsWith(anchor) == true) return index
    }
    return null
}

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
class ChatSessionState(
    val sessionId: String,
    /**
     * 读取本会话最近一次自动沉淀结果（技能名 + 是否新建）。
     *
     * 默认从 [ServiceContainer.prefs] 读；注入点存在是为了让状态机可以在单元测试里
     * 不依赖 Android/SharedPreferences 完整验证"持久显示/恢复"路径。
     */
    private val loadSkillNotice: (String) -> Pair<String, Boolean>? = { id ->
        runCatching { ServiceContainer.prefs.getAgentSkillNotice(id) }.getOrNull()
    },
    /** 读取触发上次技能沉淀的回复正文锚点（前缀）；没有记录时返回空串。 */
    private val loadSkillAnchor: (String) -> String = { id ->
        runCatching { ServiceContainer.prefs.getAgentSkillNoticeAnchor(id) }.getOrDefault("")
    },
    /**
     * 读取本会话最近一次自动长期记忆的改动条数。
     *
     * 与 [loadSkillNotice] 同理：默认从 [ServiceContainer.prefs] 读，注入点让状态机
     * 可以在单元测试里不依赖 Android/SharedPreferences。
     */
    private val loadMemoryNotice: (String) -> Int? = { id ->
        runCatching { ServiceContainer.prefs.getAgentMemoryNotice(id) }.getOrNull()
    },
    /** 读取触发上次记忆抽取的回复正文锚点（前缀）；没有记录时返回空串。 */
    private val loadMemoryAnchor: (String) -> String = { id ->
        runCatching { ServiceContainer.prefs.getAgentMemoryNoticeAnchor(id) }.getOrDefault("")
    }
) {

    // ============ 跨 VM 共享的 UI 状态 ============
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val sending = MutableStateFlow(false)
    val execConfirmation = MutableStateFlow<ExecConfirmationRequest?>(null)
    /** ask_user_question 提问请求（AI 调用提问工具后挂起等待用户回答）。 */
    val askUserQuestion =
        MutableStateFlow<com.nekobot.app.data.local.ai.AskUserQuestionRequest?>(null)
    /**
     * 第三方插件安装确认请求（Agent 安装工作区 ZIP 时挂起等待）。
     * 非空时会话界面展示第三方插件同意弹窗（协议 + 权限勾选），YOLO 不能跳过。
     */
    val pluginInstallConfirmation =
        MutableStateFlow<com.nekobot.app.data.local.ai.PluginInstallConfirmationRequest?>(null)
    val plotChoices = MutableStateFlow<List<PlotChoice>>(emptyList())
    val plotChoicesLoading = MutableStateFlow(false)
    val hookNotifications = MutableStateFlow<List<HookNotification>>(emptyList())
    val ttsStates = MutableStateFlow<Map<String, MessageTtsUiState>>(emptyMap())
    /**
     * 所有会话内联提示（上下文压缩 / 技能沉淀 / 长期记忆，以及后续新增类型）。
     *
     * 统一持有并统一锚定：界面据 [ChatInlineNotice.anchorContent] 把提示渲染在触发它的
     * 消息下方，`anchorContent` 为空或锚点失效时才回退到列表末尾。
     */
    val inlineNotices = MutableStateFlow<List<ChatInlineNotice>>(emptyList())
    /** Agent 任务列表（todo_write 工具更新；输入框上方可折叠面板展示）。 */
    val agentTodos = MutableStateFlow<List<com.nekobot.app.data.model.AgentTodo>>(emptyList())
    /** Agent 会话目标（/goal 命令更新；输入框上方横幅展示）。 */
    val agentGoal = MutableStateFlow<String?>(null)
    /** Agent 规格任务（/spec 命令更新；输入框上方横幅展示）。 */
    val agentSpec = MutableStateFlow<com.nekobot.app.data.model.AgentSessionSpec?>(null)

    /** 某类内联提示的当前值；没有时返回 null。 */
    inline fun <reified T : ChatInlineNotice> inlineNoticeOrNull(): T? =
        inlineNotices.value.filterIsInstance<T>().firstOrNull()

    /**
     * 覆盖写入某一类内联提示；传 null 表示收起该类提示。
     *
     * 同一类型同时只保留最新一条；按 [ChatInlineNotice.order] 排序，
     * 保证多个提示锚定同一条消息时渲染顺序稳定。
     */
    inline fun <reified T : ChatInlineNotice> setInlineNotice(notice: T?) {
        val next = inlineNotices.value.filterNot { it is T }.toMutableList()
        if (notice != null) next += notice
        inlineNotices.value = next.sortedBy { it.order }
    }

    /**
     * 应用一次上下文压缩状态：进行中显示内联提示，结束后移除。
     *
     * [anchorContent] 是触发压缩的消息正文（自动压缩为刚发送的用户消息，
     * 手动压缩为触发时刻的最后一条消息）。
     */
    fun applyContextCompressionNotice(inProgress: Boolean, anchorContent: String = "") {
        setInlineNotice(
            if (inProgress) ContextCompressionUiState(anchorContent = noticeAnchor(anchorContent))
            else null
        )
    }

    /**
     * 应用一次自动技能沉淀通知。
     *
     * - RUNNING：显示"正在总结技能"（锚定到触发它的那条回复）；
     * - DONE 且带技能名：显示结果，并**持久保留**（不自动消失）；
     * - DONE 但没有沉淀任何技能：收起进行中的提示，回退显示上一次已持久化的沉淀结果。
     *
     * 逻辑放在运行时状态上（而不是 ViewModel），因为事件监听 Job 跨页面存活，
     * 不能捕获已经退出的 ViewModel。
     */
    fun applyAutoSkillNotice(notice: com.nekobot.app.data.local.ai.AgentSkillNotice) {
        val anchor = noticeAnchor(notice.anchorContent)
        if (notice.phase == com.nekobot.app.data.local.ai.AgentSkillPhase.RUNNING) {
            setInlineNotice(AutoSkillUiState(running = true, anchorContent = anchor))
            return
        }
        if (notice.skillName.isBlank()) {
            setInlineNotice(loadPersistedSkillNotice(sessionId))
            return
        }
        setInlineNotice(
            AutoSkillUiState(
                skillName = notice.skillName,
                created = notice.created,
                running = false,
                anchorContent = anchor
            )
        )
    }

    /** 从上次沉淀结果恢复内联提示（进入会话时也用它恢复持久显示）。 */
    fun restoreAutoSkillNotice() {
        if (inlineNoticeOrNull<AutoSkillUiState>()?.running == true) return
        setInlineNotice(loadPersistedSkillNotice(sessionId))
    }

    /** 读取持久化的沉淀结果（技能名 + 新建标记 + 锚点）并转成界面状态；没有记录时返回 null。 */
    private fun loadPersistedSkillNotice(id: String): AutoSkillUiState? =
        loadSkillNotice(id)
            ?.takeIf { (name, _) -> name.isNotBlank() }
            ?.let { (name, created) ->
                AutoSkillUiState(
                    skillName = name,
                    created = created,
                    running = false,
                    anchorContent = noticeAnchor(loadSkillAnchor(id))
                )
            }

    /**
     * 应用一次自动长期记忆通知。
     *
     * - RUNNING：显示"正在整理长期记忆"（锚定到触发它的那条回复）；
     * - DONE 且有改动：显示"已自动记忆 N 条"，并**持久保留**（不自动消失）；
     * - DONE 但没有改动：收起进行中的提示，回退显示上一次已持久化的结果。
     */
    fun applyAutoMemoryNotice(notice: com.nekobot.app.data.local.ai.AgentMemoryNotice) {
        val anchor = noticeAnchor(notice.anchorContent)
        if (notice.phase == com.nekobot.app.data.local.ai.AgentMemoryPhase.RUNNING) {
            setInlineNotice(AutoMemoryUiState(running = true, anchorContent = anchor))
            return
        }
        if (notice.changedItems <= 0) {
            setInlineNotice(loadPersistedMemoryNotice(sessionId))
            return
        }
        setInlineNotice(
            AutoMemoryUiState(
                changedItems = notice.changedItems,
                running = false,
                anchorContent = anchor
            )
        )
    }

    /** 从上次记忆写入结果恢复内联提示（进入会话时也用它恢复持久显示）。 */
    fun restoreAutoMemoryNotice() {
        if (inlineNoticeOrNull<AutoMemoryUiState>()?.running == true) return
        setInlineNotice(loadPersistedMemoryNotice(sessionId))
    }

    /** 读取持久化的记忆改动条数与锚点并转成界面状态；没有记录时返回 null（不显示提示）。 */
    private fun loadPersistedMemoryNotice(id: String): AutoMemoryUiState? =
        loadMemoryNotice(id)
            ?.takeIf { it > 0 }
            ?.let {
                AutoMemoryUiState(
                    changedItems = it,
                    running = false,
                    anchorContent = noticeAnchor(loadMemoryAnchor(id))
                )
            }

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
