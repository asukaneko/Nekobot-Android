package com.nekobot.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.nekobot.app.ui.components.BorderlessOutlinedButton as OutlinedButton
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.nekobot.app.R
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import com.nekobot.app.data.local.ChatInputLayoutMode
import com.nekobot.app.data.local.MessageImageGenerationScheduler
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.VISION_FAILURE_MARKER
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.local.isLocalCommandMessage
import com.nekobot.app.data.local.db.LocalMessageImageEntity
import com.nekobot.app.data.local.ai.LocalSandboxCommandResult
import com.nekobot.app.data.local.ai.AgentRecoveryState
import com.nekobot.app.data.local.ai.toRecoveryState
import com.nekobot.app.data.local.ai.RealtimeContextMessage
import com.nekobot.app.data.local.ai.RealtimeFunctionCall
import com.nekobot.app.data.local.ai.RealtimeVoiceClient
import com.nekobot.app.data.local.ai.RealtimeVoiceEvent
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TtsPreviewRequest
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.data.remote.ExecConfirmationRequest
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.RealtimeEvent
import com.nekobot.app.data.remote.SocketState
import com.nekobot.app.data.remote.targetSessionId
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.adaptive.liveRegion
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.MarkdownText
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.theme.BubbleUser
import com.nekobot.app.ui.theme.BubbleUserLight
import com.nekobot.app.ui.theme.parseHexColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * 对话状态与实时事件协调器。与 Compose 视图分离，避免界面文件继续承担会话编排职责。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel : BaseViewModel() {

    companion object {
        /** 流式消息在列表中的临时 id（供 MessageBubble 识别流式占位） */
        const val STREAMING_ID = "_streaming_"
        /** 服务端持久化尚未刷新时使用的临时正式消息 id 前缀。 */
        const val STREAM_FALLBACK_PREFIX = "_stream_fallback_"
    }

    private val socket = ServiceContainer.socket

    /**
     * 跨 ViewModel 共享的会话运行时状态。
     *
     * 所有"需要跨 VM 持久"的状态（messages/sending/execConfirmation/plotChoices/
     * plotChoicesLoading/hookNotifications/streamingContent 等）以及后台 Job
     * 都由 [ChatSessionState] 持有。本 VM 只是这些 StateFlow 的订阅者，
     * VM 销毁时只减少引用计数，不取消正在运行的 AI 生成 Job。
     *
     * 在 [init] 调用前为占位状态（sessionId 为空），所有访问都会落到空集合上。
     */
    private val _runtime = MutableStateFlow<ChatSessionState>(ChatSessionState(""))
    private val runtime: ChatSessionState get() = _runtime.value

    val messages: StateFlow<List<Message>> = _runtime
        .map { it.messages }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _messages: MutableStateFlow<List<Message>> get() = runtime.messages

    /** 当前流式气泡独立订阅，正文分片不再触发整份消息列表和进度卡片重组。 */
    val streamingContentPreview: StateFlow<String> = _runtime
        .map { it.streamingContentPreview }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 非 Agent 会话的流式思考独立预览。 */
    val streamingReasoningPreview: StateFlow<String> = _runtime
        .map { it.streamingReasoningPreview }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val ttsStates: StateFlow<Map<String, MessageTtsUiState>> = _runtime
        .map { it.ttsStates }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _ttsStates: MutableStateFlow<Map<String, MessageTtsUiState>>
        get() = runtime.ttsStates

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _groupCharacters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    val groupCharacters: StateFlow<List<CharacterPreset>> = _groupCharacters.asStateFlow()

    val sending: StateFlow<Boolean> = _runtime
        .map { it.sending }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _sending: MutableStateFlow<Boolean> get() = runtime.sending

    val agentContextCompressionInProgress: StateFlow<Boolean> = _runtime
        .map { it.agentContextCompressionInProgress }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _agentContextCompressionInProgress: MutableStateFlow<Boolean>
        get() = runtime.agentContextCompressionInProgress

    val execConfirmation: StateFlow<ExecConfirmationRequest?> = _runtime
        .map { it.execConfirmation }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _execConfirmation: MutableStateFlow<ExecConfirmationRequest?> get() = runtime.execConfirmation

    private val _agentRecovery = MutableStateFlow<AgentRecoveryState?>(null)
    val agentRecovery: StateFlow<AgentRecoveryState?> = _agentRecovery.asStateFlow()
    private var agentRecoveryJob: kotlinx.coroutines.Job? = null

    /** 跨 VM 共享的剧情选项列表（plot_mode 开启时从服务器获取） */
    val plotChoices: StateFlow<List<PlotChoice>> = _runtime
        .map { it.plotChoices }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _plotChoices: MutableStateFlow<List<PlotChoice>> get() = runtime.plotChoices

    /** 跨 VM 共享的剧情选项加载状态（用于骨架动画） */
    val plotChoicesLoading: StateFlow<Boolean> = _runtime
        .map { it.plotChoicesLoading }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _plotChoicesLoading: MutableStateFlow<Boolean> get() = runtime.plotChoicesLoading

    /**
     * Hook 触发通知列表（成就式弹窗）。
     *
     * - 远程模式：服务端通过 `hook_notification` Socket.IO 事件推送
     * - 本地模式：HookExecutor 执行 hook 成功后构造发出
     *
     * 通知最多保留 5 条，每条 5 秒后自动移除（与原仓库前端 5s 超时一致）。
     */
    val hookNotifications: StateFlow<List<com.nekobot.app.data.remote.HookNotification>> = _runtime
        .map { it.hookNotifications }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _hookNotifications: MutableStateFlow<List<com.nekobot.app.data.remote.HookNotification>> get() = runtime.hookNotifications

    /**
     * Agent 模式进度卡片（thinking_card）管理。
     *
     * 卡片挂载到父用户消息的 [Message.thinkingCards] 字段上，随消息一起持久化。
     * - 收到 ThinkingCardUpdate 事件时，按 parentMessageId 定位父用户消息
     *   （找不到则回退到最后一条 user 消息，对齐原仓库 orphanCards 兜底逻辑）
     * - 在该消息的 thinkingCards 列表中替换同 id 卡片或追加
     * - StreamEnd 时 loadMessages 会用持久化数据覆盖，保证最终一致
     */
    private fun applyThinkingCardUpdate(card: ThinkingCard) {
        _messages.value = attachThinkingCardToMessages(_messages.value, card)
    }

    private fun isReasoningEnabled(): Boolean =
        ServiceContainer.prefs.getSessionReasoningEffort(currentSessionId) != ReasoningEffort.NONE

    private fun isAgentSession(): Boolean =
        _session.value?.sessionMode.equals("agent", ignoreCase = true) ||
            _messages.value.any { message -> message.thinkingCards.orEmpty().any(ThinkingCard::isAgent) }

    private fun parseRealtimeToolArguments(arguments: String): Map<String, Any> = runCatching {
        @Suppress("UNCHECKED_CAST")
        com.google.gson.Gson().fromJson(
            arguments.ifBlank { "{}" },
            Map::class.java
        ) as? Map<String, Any>
    }.getOrNull().orEmpty()

    private fun enrichAgentThinkingCard(card: ThinkingCard, reasoning: String): ThinkingCard {
        if (reasoning.isBlank()) return card
        val steps = card.steps.toMutableList()
        val thinkingIndex = steps.indexOfLast { it.type.equals("thinking", ignoreCase = true) }
        val status = if (card.isComplete) "done" else "active"
        val enrichedStep = if (thinkingIndex >= 0) {
            steps[thinkingIndex].copy(
                name = steps[thinkingIndex].name?.takeIf(String::isNotBlank)
                    ?: string(R.string.chat_thinking),
                status = steps[thinkingIndex].status?.takeIf(String::isNotBlank) ?: status,
                detail = reasoning.takeLast(160),
                thinkingContent = reasoning
            )
        } else {
            ThinkingStep(
                type = "thinking",
                name = string(R.string.chat_thinking),
                status = status,
                detail = reasoning.takeLast(160),
                thinkingContent = reasoning
            )
        }
        if (thinkingIndex >= 0) steps[thinkingIndex] = enrichedStep else steps.add(enrichedStep)
        return card.copy(steps = steps)
    }

    private fun applyAgentReasoningToLatestCard(reasoning: String) {
        if (reasoning.isBlank()) return
        val latestCard = _messages.value
            .asReversed()
            .asSequence()
            .filter(Message::isUser)
            .flatMap { it.thinkingCards.orEmpty().asReversed().asSequence() }
            .firstOrNull { it.isAgent }
            ?: return
        applyThinkingCardUpdate(enrichAgentThinkingCard(latestCard, reasoning))
    }

    // 多选模式状态
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()
    private val _selectedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedMessageIds: StateFlow<Set<String>> = _selectedMessageIds.asStateFlow()

    private val _messageImages = MutableStateFlow<List<LocalMessageImageEntity>>(emptyList())
    val messageImages: StateFlow<List<LocalMessageImageEntity>> = _messageImages.asStateFlow()
    private var messageImagesJob: kotlinx.coroutines.Job? = null

    private var currentSessionId: String = ""
    private var realtimeLiveJob: kotlinx.coroutines.Job? = null
    private val realtimeVoiceClient = RealtimeVoiceClient()

    /** 流式生成中的临时消息内容累加器（引用 runtime，跨 VM 共享） */
    private val streamingContent: StringBuilder get() = runtime.streamingContent
    private val streamingReasoning: StringBuilder get() = runtime.streamingReasoning
    /** 流式消息在列表中的临时 id */
    private val streamingId = STREAMING_ID
    /** 上次流式 chunk 更新 UI 的时间戳，用于节流（避免高频 chunk 触发 MarkdownText 全量重解析） */
    private var lastStreamUiUpdateMs: Long
        get() = runtime.lastStreamUiUpdateMs
        set(value) { runtime.lastStreamUiUpdateMs = value }
    /** 流式节流间隔（毫秒） */
    private val streamThrottleMs: Long get() = runtime.streamThrottleMs

    /** 收集 Socket.IO 事件 / 本地 Hook 事件的 Job（挂到 applicationScope，不随 VM 销毁） */
    private var eventsJob: kotlinx.coroutines.Job?
        get() = runtime.eventsJob
        set(value) { runtime.eventsJob = value }
    /** 本地模式流式聊天收集 Job（挂到 applicationScope，不随 VM 销毁） */
    private var localChatJob: kotlinx.coroutines.Job?
        get() = runtime.localChatJob
        set(value) { runtime.localChatJob = value }
    private var generationStopRequested: Boolean
        get() = runtime.generationStopRequested
        set(value) { runtime.generationStopRequested = value }

    /**
     * 启动一轮本地聊天。最终正文到达时先释放发送状态，Job 则继续承载自动命名等后台收尾。
     * 新一轮可抢占已经完成正文的旧 Job；旧 completion 只能清理自己的状态。
     */
    private fun startLocalChatCollection(block: suspend (kotlinx.coroutines.Job) -> Unit): Boolean {
        val target = runtime
        lateinit var job: kotlinx.coroutines.Job
        job = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            block(job)
        }
        if (!target.installLocalChatJob(job)) {
            job.cancel()
            target.sending.value = true
            return false
        }
        job.invokeOnCompletion { cause ->
            if (target.clearLocalChatJob(job)) {
                target.sending.value = false
                if (cause is kotlinx.coroutines.CancellationException) {
                    target.streamingContentPreview.value = ""
                    target.streamingReasoningPreview.value = ""
                    target.messages.value = target.messages.value.filter { it.id != streamingId }
                }
            }
            ChatSessionManager.pruneIfIdle(target.sessionId)
        }
        job.start()
        return true
    }

    /** 当前会话是否对用户可见（在聊天界面且应用在前台） */
    var isChatVisible: Boolean = false
        private set

    /** 标记聊天界面可见性（由 ChatScreen 的 onResume/onPause 调用） */
    fun setChatVisible(visible: Boolean) {
        isChatVisible = visible
    }

    /** 返回已存在的聊天页时重新把全局 Socket 切换到当前会话 room。 */
    fun activateRealtimeSession() {
        if (!isLocalMode && currentSessionId.isNotBlank()) {
            connectSocket(currentSessionId)
        }
    }

    /** 初始化：加载会话信息与消息列表；服务器模式额外连接 Socket.IO。 */
    fun init(sessionId: String) {
        if (sessionId == currentSessionId && _session.value != null) return
        // 切换会话时释放旧 runtime 引用
        if (currentSessionId.isNotBlank() && currentSessionId != sessionId) {
            ChatSessionManager.release(currentSessionId)
        }
        currentSessionId = sessionId
        messageImagesJob?.cancel()
        messageImagesJob = viewModelScope.launch {
            unified.observeMessageImages(sessionId).collect { images ->
                if (currentSessionId == sessionId) _messageImages.value = images
            }
        }
        // 获取（或创建）跨 VM 共享的运行时状态，引用计数 +1
        // 通过 _runtime.value 赋值使 Compose 的 flatMapLatest 自动切换到新 runtime
        _runtime.value = ChatSessionManager.acquire(sessionId)
        observeAgentRecovery(sessionId)
        _groupCharacters.value = emptyList()
        loadSession(sessionId)
        // 仅当该会话没有正在进行的 AI 生成时才重新加载消息，
        // 否则保留 runtime 中的流式状态（用户切回正在生成的会话时能看到进度）。
        if (!runtime.hasActiveGeneration()) {
            loadMessages()
        }
        if (!isLocalMode) {
            connectSocket(sessionId)
        } else {
            // 本地模式：收集 HookExecutor 事件流（hook 触发通知）
            // 独立于 localChatJob，避免阻塞聊天 flow 的 coroutineScope
            connectLocalHookEvents()
        }
    }

    /** 本地模式：收集 HookExecutor.events，将 HookNotificationEvent 路由到 handleRealtimeEvent。 */
    private fun connectLocalHookEvents() {
        // 仅当没有现存的 eventsJob 时才启动，避免重复订阅
        if (eventsJob?.isActive == true) return
        val target = runtime
        val targetSessionId = currentSessionId
        val hookEvents = com.nekobot.app.ServiceContainer.localRepository.hookExecutor.events
        val confirmationEvents = com.nekobot.app.ServiceContainer.localRepository.execConfirmationEvents
            .map { request -> RealtimeEvent.ExecConfirmationRequired(request) }
        // 同时收集两路：
        // 1. hookExecutor.events → HookNotificationEvent
        // 2. localRepository.execConfirmationEvents → 高风险工具（删除角色卡等）的确认请求
        //    修复"删除角色卡卡住"：原实现把确认事件 emit 到 LocalPipelineCallbacks.eventChannel
        //    但 eventChannel 没人 collect，导致 requestAuthorization 的 runBlocking 永远等待。
        eventsJob = ServiceContainer.applicationScope.launch {
            kotlinx.coroutines.flow.merge(
                hookEvents,
                confirmationEvents
            ).collect { event ->
                // 这里不能调用 ChatViewModel.handleRealtimeEvent：eventsJob 跨页面存活，捕获 this
                // 会永久保留已经退出的 ViewModel 和 Agent 大消息列表。
                when (event) {
                    is RealtimeEvent.HookNotificationEvent -> {
                        val notification = event.notification
                        if (
                            notification.conversationId.isNullOrBlank() ||
                            notification.conversationId == targetSessionId
                        ) {
                            val next = (target.hookNotifications.value + notification).takeLast(5)
                            target.hookNotifications.value = next
                            launch {
                                kotlinx.coroutines.delay(5000)
                                target.hookNotifications.value = target.hookNotifications.value
                                    .filter { it !== notification }
                            }
                        }
                    }
                    is RealtimeEvent.ExecConfirmationRequired -> {
                        val request = event.request
                        if (request.sessionId.isBlank() || request.sessionId == targetSessionId) {
                            target.execConfirmation.value = request.copy(
                                sessionId = request.sessionId.ifBlank { targetSessionId }
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** 连接 Socket.IO 并加入会话 room，监听实时事件。 */
    private fun connectSocket(sessionId: String) {
        socket.connect()
        socket.joinSession(sessionId)
        // 仅当没有现存的 eventsJob 时才启动订阅，避免重复
        if (eventsJob?.isActive == true) return
        eventsJob = ServiceContainer.applicationScope.launch {
            socket.events.collect { event -> handleRealtimeEvent(event) }
        }
    }

    /** 处理 Socket.IO 推送的实时事件。 */
    private fun handleRealtimeEvent(
        event: RealtimeEvent,
        sourceLocalJob: kotlinx.coroutines.Job? = null
    ) {
        if (isLocalMode && sourceLocalJob != null && !runtime.ownsLocalChatJob(sourceLocalJob)) {
            // 被新一轮抢占的旧后处理可能仍有少量排队事件，禁止其污染新一轮 UI。
            return
        }
        if (!isLocalMode && event.targetSessionId() != currentSessionId) {
            android.util.Log.d(
                "NekoSocket",
                "Ignore event for ${event.targetSessionId()} while current=$currentSessionId"
            )
            return
        }
        if (
            generationStopRequested && (
                event is RealtimeEvent.StreamStart ||
                    event is RealtimeEvent.StreamChunk ||
                    event is RealtimeEvent.ReasoningChunk ||
                    event is RealtimeEvent.ThinkingCardUpdate
                )
        ) {
            // 注意：保留 ExecConfirmationRequired 的处理，否则删除角色卡等高风险工具
            // 会因为旧 generation 已停止导致用户收不到确认弹窗 → 工具卡 10 分钟。
            return
        }
        fun completeLocalForeground() {
            if (isLocalMode && sourceLocalJob != null) {
                runtime.markLocalResponseComplete(sourceLocalJob)
            }
        }
        when (event) {
            is RealtimeEvent.StreamStart -> {
                _sending.value = true
                streamingContent.setLength(0)
                streamingReasoning.setLength(0)
                runtime.streamingContentPreview.value = ""
                runtime.streamingReasoningPreview.value = ""
                lastStreamUiUpdateMs = 0L
                // 插入流式占位消息
                val placeholder = Message(
                    id = streamingId,
                    role = "assistant",
                    content = "",
                    timestamp = System.currentTimeMillis().toString()
                )
                _messages.value = _messages.value.filter { it.id != streamingId } + placeholder
            }
            is RealtimeEvent.StreamChunk -> {
                streamingContent.append(event.chunk)
                // 节流：距上次 UI 更新不足阈值时跳过，避免高频 chunk 触发 MarkdownText 全量重解析
                val now = System.currentTimeMillis()
                if (now - lastStreamUiUpdateMs >= streamThrottleMs) {
                    lastStreamUiUpdateMs = now
                    runtime.streamingContentPreview.value = buildStreamingDisplayPreview(
                        streamingContent,
                        omittedPrefix = string(R.string.chat_stream_prefix_omitted)
                    )
                }
            }
            is RealtimeEvent.ReasoningChunk -> {
                if (!isReasoningEnabled()) return
                streamingReasoning.append(event.chunk)
                if (isAgentSession()) {
                    applyAgentReasoningToLatestCard(streamingReasoning.toString())
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastStreamUiUpdateMs >= streamThrottleMs) {
                    lastStreamUiUpdateMs = now
                    runtime.streamingReasoningPreview.value = buildStreamingDisplayPreview(
                        streamingReasoning,
                        omittedPrefix = string(R.string.chat_stream_prefix_omitted)
                    )
                }
            }
            is RealtimeEvent.StreamEnd -> {
                if (!isLocalMode) _sending.value = false
                // 本地流程在 StreamEnd 前已完成 Room 持久化，直接刷新数据库即可。
                // 若再生成随机 ID 的正式消息，刷新时会因时间戳不同同时保留两条相同气泡。
                val finalContent = streamingContent.toString()
                val finalReasoning = streamingReasoning.toString()
                    .takeIf { isReasoningEnabled() && !isAgentSession() }
                    .orEmpty()
                _messages.value = finalizeStreamEndMessages(
                    current = _messages.value,
                    streamingId = streamingId,
                    finalContent = finalContent,
                    finalReasoning = finalReasoning,
                    materializeFallback = !isLocalMode
                )
                runtime.streamingContentPreview.value = ""
                runtime.streamingReasoningPreview.value = ""
                // 刷新列表获取服务端持久化的真实消息（含 id/token 等）
                loadMessages()
                // 远程模式在流结束后触发；本地模式必须等标题总结后处理完成事件，
                // 避免 TTS 与会话命名同时占用模型请求。
                if (!isLocalMode) {
                    scheduleTtsForLatestAssistant(finalContent)
                }
                // 检查是否需要发送通知（用户不在聊天界面时）
                trySendNotification(streamingContent.toString())
                // 如果剧情模式开启，显示骨架并加载新剧情选项
                if (_session.value?.plotMode == true) {
                    _plotChoices.value = emptyList()
                    _plotChoicesLoading.value = true
                    if (isLocalMode) {
                        // 本地模式：等待 PlotChoices 事件到达（chatWithPipeline 在 StreamEnd 后生成）
                        // 15 秒超时保护：生成失败时自动关闭骨架（Phase 6 含 AI 调用，需充足时间）
                        ServiceContainer.applicationScope.launch {
                            kotlinx.coroutines.delay(15000)
                            if (_plotChoicesLoading.value) {
                                _plotChoicesLoading.value = false
                            }
                        }
                    } else {
                        // 服务器模式：延迟 8 秒后通过 HTTP 加载（兜底：若 plot_choices socket 事件先到则覆盖）
                        // 延迟足够长以避免拉到上一轮的旧选项（服务端生成新选项需要时间）
                        ServiceContainer.applicationScope.launch {
                            kotlinx.coroutines.delay(8000)
                            if (_plotChoicesLoading.value) loadPlotChoices()
                        }
                    }
                }
                if (event.completesForeground) completeLocalForeground()
            }
            is RealtimeEvent.PlotChoices -> {
                // 服务端推送新剧情选项，直接解析更新
                _plotChoices.value = parsePlotChoices(event.choices)
                _plotChoicesLoading.value = false
            }
            is RealtimeEvent.AiResponse -> {
                if (!isLocalMode) _sending.value = false
                _execConfirmation.value = null
                val msg = event.message?.let { incoming ->
                    if (isReasoningEnabled() && !isAgentSession()) incoming
                    else incoming.copy(reasoningContent = null)
                }
                if (msg != null && !msg.content.isNullOrBlank()) {
                    // 移除流式占位，追加完整回复
                    _messages.value = (_messages.value.filter {
                        it.id != streamingId &&
                            (msg.id == null || it.id != msg.id) &&
                            !(it.id?.startsWith(STREAM_FALLBACK_PREFIX) == true && it.content == msg.content)
                    }) + msg
                    if (!isLocalMode) {
                        scheduleTtsForMessage(msg)
                    }
                    // 通知检查
                    trySendNotification(msg.content.orEmpty())
                } else {
                    // 完整响应缺失或格式异常时刷新持久化消息，不能让空对象静默吞掉最终回复
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    loadMessages()
                }
                // 非流式回复也需刷新剧情选项
                if (_session.value?.plotMode == true) {
                    _plotChoices.value = emptyList()
                    _plotChoicesLoading.value = true
                    ServiceContainer.applicationScope.launch {
                        kotlinx.coroutines.delay(1000)
                        if (_plotChoicesLoading.value) loadPlotChoices()
                    }
                }
                if (event.completesForeground) completeLocalForeground()
            }
            is RealtimeEvent.NewMessage -> {
                val msg = event.message.let { incoming ->
                    if (isReasoningEnabled() && !isAgentSession()) incoming
                    else incoming.copy(reasoningContent = null)
                }
                // 过滤进度卡片（thinking_card），不展示在聊天列表
                if (msg.isThinkingCard && !msg.isAgentContextSummary()) return
                _messages.value = mergeRealtimeNewMessage(
                    current = _messages.value,
                    incoming = msg,
                    isSending = _sending.value,
                    streamingId = streamingId
                )
                if (!msg.isUser) {
                    if (!isLocalMode) _sending.value = false
                    if (!isLocalMode) {
                        scheduleTtsForMessage(msg)
                    }
                    if (event.completesForeground) completeLocalForeground()
                }
            }
            is RealtimeEvent.Filtered -> {
                if (!isLocalMode) _sending.value = false
                showToast(event.message ?: string(R.string.chat_message_filtered))
                if (event.completesForeground) completeLocalForeground()
            }
            is RealtimeEvent.Error -> {
                if (!isLocalMode) _sending.value = false
                showError(event.message)
                if (event.completesForeground) completeLocalForeground()
            }
            is RealtimeEvent.Usage -> {
                // 本地模式 token 用量已由 LocalRepository 保存到消息，UI 无需额外处理
            }
            is RealtimeEvent.ExecConfirmationRequired -> {
                val request = event.request
                if (request.sessionId.isBlank() || request.sessionId == currentSessionId) {
                    if (!isLocalMode) _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    _execConfirmation.value = request.copy(
                        sessionId = request.sessionId.ifBlank { currentSessionId }
                    )
                }
            }
            is RealtimeEvent.ExecConfirmationResolved -> {
                if (event.sessionId.isNullOrBlank() || event.sessionId == currentSessionId) {
                    if (!isLocalMode) _sending.value = false
                    _execConfirmation.value = null
                }
            }
            is RealtimeEvent.ThinkingCardUpdate -> {
                // 本地 Agent 的首张卡片可能早于 loadSession 返回；此时以卡片自身的 isAgent
                // 标记为准，不能因为 _session 暂时为空而丢掉整轮进度事件。
                if (shouldApplyThinkingCardUpdate(_session.value?.sessionMode, event.card.isAgent)) {
                    val card = if (
                        event.card.isAgent &&
                        isReasoningEnabled() &&
                        streamingReasoning.isNotBlank()
                    ) {
                        enrichAgentThinkingCard(event.card, streamingReasoning.toString())
                    } else event.card
                    applyThinkingCardUpdate(card)
                    if (!isLocalMode) _sending.value = !event.card.isComplete
                }
            }
            is RealtimeEvent.ContextCompressionStatus -> {
                if (event.sessionId == currentSessionId) {
                    _agentContextCompressionInProgress.value = event.inProgress
                    if (!event.inProgress && event.compressed) loadMessages()
                }
            }
            is RealtimeEvent.HookNotificationEvent -> {
                // Hook 触发通知：仅处理当前会话的通知（与远程模式 conversationId 路由一致）
                val notif = event.notification
                if (notif.conversationId.isNullOrBlank() || notif.conversationId == currentSessionId) {
                    addHookNotification(notif)
                }
            }
            is RealtimeEvent.SessionRenamed -> {
                // 本地/远程自动命名：更新当前会话标题
                if (event.sessionId == currentSessionId) {
                    _session.value = _session.value?.copy(name = event.newName)
                }
            }
            is RealtimeEvent.ForegroundComplete -> completeLocalForeground()
            is RealtimeEvent.ReplyPostProcessed -> {
                if (
                    isLocalMode &&
                    event.sessionId == currentSessionId
                ) {
                    event.contents
                        .filter(String::isNotBlank)
                        .distinct()
                        .forEach(::scheduleTtsForLatestAssistant)
                }
            }
        }
    }

    /**
     * 添加 hook 通知到列表，5 秒后自动移除（与原仓库前端 5s 超时一致）。
     * 最多保留 5 条，超出时移除最旧的。
     */
    private fun addHookNotification(notif: com.nekobot.app.data.remote.HookNotification) {
        val current = _hookNotifications.value.toMutableList()
        current.add(notif)
        if (current.size > 5) {
            current.removeAt(0)
        }
        _hookNotifications.value = current
        // 5 秒后自动移除
        ServiceContainer.applicationScope.launch {
            kotlinx.coroutines.delay(5000)
            _hookNotifications.value = _hookNotifications.value.filter { it !== notif }
        }
    }

    /** 手动移除 hook 通知（用户点击关闭按钮） */
    fun dismissHookNotification(notif: com.nekobot.app.data.remote.HookNotification) {
        _hookNotifications.value = _hookNotifications.value.filter { it !== notif }
    }

    /** 提交命令授权结果；Socket 断开时保留弹窗，允许用户稍后重试。 */
    fun respondToExecConfirmation(authorization: ExecAuthorization) {
        val request = _execConfirmation.value ?: return
        val sessionId = request.sessionId.ifBlank { currentSessionId }
        val submitted = if (isLocalMode) {
            unified.respondToLocalExecConfirmation(
                requestId = request.requestId,
                authorization = authorization,
                sessionId = sessionId
            )
        } else {
            socket.respondToExecConfirmation(
                requestId = request.requestId,
                authorization = authorization,
                sessionId = sessionId
            )
        }
        if (!submitted) {
            // 拒绝是安全默认值：即使断线无法回传，也允许关闭弹窗；
            // 未获明确授权的服务端 pending command 不会自动执行。
            if (!authorization.approved) {
                _execConfirmation.value = null
                _sending.value = false
            }
            showError(string(R.string.chat_exec_confirm_socket_disconnected))
            return
        }
        _execConfirmation.value = null
        _sending.value = true
        showToast(
            string(
                when (authorization) {
                    ExecAuthorization.Reject -> R.string.chat_exec_confirm_rejected
                    ExecAuthorization.Once -> R.string.chat_exec_confirm_approved
                    ExecAuthorization.Always -> R.string.chat_exec_confirm_always_approved
                }
            )
        )
    }

    /** 加载会话信息。 */
    private fun loadSession(sessionId: String) {
        launchResult(
            block = { unified.getSession(sessionId) },
            onSuccess = {
                _session.value = it
                if (it?.sessionMode.equals("group", ignoreCase = true)) {
                    loadGroupCharacters(it?.characterIds.orEmpty())
                } else {
                    _groupCharacters.value = emptyList()
                }
                // 剧情模式：立即标记加载中，避免输入框先出现再消失的滑动动画
                if (it?.plotMode == true) {
                    _plotChoicesLoading.value = true
                    ServiceContainer.applicationScope.launch {
                        if (isLocalMode) loadLocalPlotChoices() else loadPlotChoices()
                    }
                }
            }
        )
    }

    /**
     * 刷新当前会话信息（用于角色卡立绘变更后同步头像）。
     * 仅重新拉取 session 数据，不重置消息列表和 Socket 连接。
     */
    fun refreshSession() {
        val sid = currentSessionId.takeIf { it.isNotBlank() } ?: return
        if (!runtime.hasActiveGeneration()) {
            loadMessages()
        }
        launchResult(
            block = { unified.getSession(sid) },
            onSuccess = { latest ->
                _session.value = latest
                if (latest?.sessionMode.equals("group", ignoreCase = true)) {
                    loadGroupCharacters(latest?.characterIds.orEmpty())
                }
            }
        )
    }

    /** 加载消息列表。 */
    fun loadMessages() {
        if (currentSessionId.isBlank()) return
        val requestedSessionId = currentSessionId
        val target = runtime
        launchResult(
            block = { unified.listMessages(requestedSessionId) },
            onSuccess = { fresh ->
                if (currentSessionId == requestedSessionId && runtime === target) {
                // tool_call_history 只供模型恢复上下文，聊天 UI 不读取也不持有这份大对象。
                val uiFresh = fresh.orEmpty().map { message ->
                    if (message.toolCallHistory != null) message.copy(toolCallHistory = null) else message
                }
                val hasAgentCards = uiFresh.any { message ->
                    message.thinkingCards.orEmpty().any(ThinkingCard::isAgent)
                }
                val showStandaloneReasoning =
                    isReasoningEnabled() && !isAgentSession() && !hasAgentCards
                // 合并：保留现有 thinking_cards，避免被刷新覆盖（对齐原仓库 nbot-methods.js:6221）
                val current = _messages.value.map { message ->
                    if (showStandaloneReasoning || message.isUser) message
                    else message.copy(reasoningContent = null)
                }
                val byId = current.associateBy { it.id }
                val byUserContent = current.filter { it.isUser }
                    .associateBy { it.content to it.timestamp }
                // 当前 UI 中非占位的 assistant 消息（按内容+时间戳匹配，避免刚生成的回复被 fresh 覆盖丢失）
                val currentAssistantByContent = current
                    .filter { !it.isUser && it.id != streamingId && !it.content.isNullOrBlank() }
                    .associateBy { it.content to it.timestamp }

                val merged = uiFresh
                    .filterNot { msg -> msg.isThinkingCard && !msg.isAgentContextSummary() }
                    .map { message ->
                        if (showStandaloneReasoning || message.isUser) message
                        else message.copy(reasoningContent = null)
                    }
                    .map { newMsg ->
                    // 历史加载的 thinking_cards 必定已完成（否则为数据不一致），
                    // 强制最后一张卡片 isComplete=true，避免重进会话还在转圈
                    var normalizedCardsChanged = false
                    val normalizedCards = newMsg.thinkingCards?.map { card ->
                        if (!card.isComplete) {
                            normalizedCardsChanged = true
                            card.copy(isComplete = true)
                        } else card
                    }
                    val withCards = if (normalizedCardsChanged && normalizedCards != null) {
                        newMsg.copy(thinkingCards = normalizedCards)
                    } else newMsg

                    val existing = withCards.id?.let { byId[it] }
                    val mergedCards = mergeThinkingCardReasoning(
                        freshCards = withCards.thinkingCards,
                        currentCards = existing?.thinkingCards
                    )
                    val mergedCard = if (mergedCards !== withCards.thinkingCards) {
                        withCards.copy(thinkingCards = mergedCards)
                    } else withCards
                    val mergedAudio = if (
                        mergedCard.audioUrl.isNullOrBlank() &&
                        !existing?.audioUrl.isNullOrBlank()
                    ) {
                        mergedCard.copy(audioUrl = existing?.audioUrl)
                    } else mergedCard
                    // 兜底：按 user 消息内容+时间戳匹配（乐观消息无 id，被服务器消息替换时保留 thinking_cards）
                    if (mergedAudio.thinkingCards == null && mergedAudio.isUser) {
                        val key = mergedAudio.content to mergedAudio.timestamp
                        byUserContent[key]?.thinkingCards?.let { tc ->
                            mergedAudio.copy(thinkingCards = tc)
                        } ?: mergedAudio
                    } else mergedAudio
                }

                // 保留 current 中 fresh 没有的 assistant 消息（刚生成的回复可能因 Room 异步竞态未被 fresh 包含）
                val freshAssistantKeys = merged
                    .filter { !it.isUser && !it.content.isNullOrBlank() }
                    .associateBy { it.content to it.timestamp }
                    .keys
                val freshAssistantContents = merged
                    .filter { !it.isUser && !it.content.isNullOrBlank() }
                    .mapTo(mutableSetOf()) { it.content }
                val orphanAssistants = currentAssistantByContent.values.filter { msg ->
                    val idAlreadyLoaded = !msg.id.isNullOrBlank() && merged.any { it.id == msg.id }
                    !idAlreadyLoaded &&
                        (msg.content to msg.timestamp) !in freshAssistantKeys &&
                        !(msg.id?.startsWith(STREAM_FALLBACK_PREFIX) == true &&
                            msg.content in freshAssistantContents)
                }

                val nextMessages = deduplicateMessagesById(
                    if (orphanAssistants.isEmpty()) merged else merged + orphanAssistants
                )
                _messages.value = nextMessages
                val nextTtsStates = _ttsStates.value.toMutableMap()
                nextMessages.forEach { msg ->
                    val messageId = msg.id
                    if (!messageId.isNullOrBlank() && !msg.audioUrl.isNullOrBlank()) {
                        nextTtsStates[messageId] = MessageTtsUiState(MessageTtsStatus.Ready)
                    }
                }
                _ttsStates.value = nextTtsStates
                }
            }
        )
    }

    private data class ActiveTtsConfig(
        val modelId: String?,
        val voice: String,
        val speed: Float,
        val pitch: Float,
        val volume: Float
    )

    private fun activeTtsConfig(
        session: Session? = _session.value,
        requireEnabled: Boolean = true
    ): ActiveTtsConfig? {
        val config = session?.ttsConfig
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        if (config == null) {
            return if (requireEnabled) null else ActiveTtsConfig(
                modelId = null,
                voice = "",
                speed = 1f,
                pitch = 1f,
                volume = 1f
            )
        }
        val enabled = runCatching {
            config.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean
        }.getOrNull() == true
        if (requireEnabled && !enabled) return null

        fun stringValue(name: String): String? = runCatching {
            config.get(name)?.takeIf { !it.isJsonNull }?.asString?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }

        fun floatValue(name: String): Float = runCatching {
            config.get(name)?.takeIf { !it.isJsonNull }?.asFloat
        }.getOrNull()?.takeIf { it > 0f } ?: 1f

        return ActiveTtsConfig(
            modelId = stringValue("model_id"),
            voice = stringValue("voice").orEmpty(),
            speed = floatValue("speed"),
            pitch = floatValue("pitch"),
            volume = floatValue("volume")
        )
    }

    /**
     * 每次回复结束都从真实仓库读取最新会话配置。
     *
     * 聊天 ViewModel 会在进入会话详情页时保留，若只读 [_session]，返回聊天页后的第一条回复
     * 仍会使用切换 TTS 前的旧快照，造成开关延迟一轮生效。
     */
    private suspend fun resolveLatestTtsConfig(
        sessionId: String,
        requireEnabled: Boolean = true
    ): ActiveTtsConfig? {
        val latest = when (val result = unified.getSession(sessionId)) {
            is Resource.Success -> result.data
            else -> null
        }
        if (latest != null) {
            if (currentSessionId == sessionId) _session.value = latest
            return activeTtsConfig(latest, requireEnabled)
        }
        return activeTtsConfig(requireEnabled = requireEnabled)
    }

    /**
     * 远程端会在助手消息落库后异步生成会话标题。TTS 若立刻发起第二个模型请求，
     * 部分单并发服务会让标题请求失败；因此默认标题尚未更新时先等待重命名事件/REST 状态。
     * 最多等待 30 秒，超时后仍继续生成语音，避免标题服务异常时永久阻塞 TTS。
     */
    private suspend fun waitForRemoteAutoTitle(sessionId: String) {
        if (isLocalMode || !isAutoGeneratedTitlePending(_session.value?.name)) return
        repeat(60) {
            if (!isAutoGeneratedTitlePending(_session.value?.name)) return
            kotlinx.coroutines.delay(500)
            val latest = when (val result = unified.getSession(sessionId)) {
                is Resource.Success -> result.data
                else -> null
            } ?: return@repeat
            if (currentSessionId == sessionId) {
                _session.value = latest
            }
            if (!isAutoGeneratedTitlePending(latest.name)) return
        }
    }

    /** Room 是事实来源；仅当该会话没有活跃内存 Job 时，把 running 记录解释为进程中断。 */
    private fun observeAgentRecovery(sessionId: String) {
        agentRecoveryJob?.cancel()
        _agentRecovery.value = null
        if (!isLocalMode) return
        val target = runtime
        val runFlow = unified.observeLocalAgentRun(sessionId) ?: return
        agentRecoveryJob = viewModelScope.launch {
            combine(runFlow, target.sending) { run, _ ->
                run?.toRecoveryState(target.hasActiveGeneration())
            }.collect { state ->
                if (currentSessionId == sessionId && runtime === target) {
                    _agentRecovery.value = state
                }
            }
        }
    }

    private fun updateTtsState(
        target: ChatSessionState,
        messageId: String,
        state: MessageTtsUiState?
    ) {
        val next = target.ttsStates.value.toMutableMap()
        if (state == null) next.remove(messageId) else next[messageId] = state
        target.ttsStates.value = next
    }

    private fun isPersistedAssistantMessage(message: Message): Boolean {
        val id = message.id
        return !message.isUser &&
            !message.isThinkingCard &&
            !message.content.isNullOrBlank() &&
            !id.isNullOrBlank() &&
            id != streamingId &&
            !id.startsWith(STREAM_FALLBACK_PREFIX)
    }

    /**
     * Socket/本地流结束时真实消息 id 可能尚未回到 UI，因此短暂轮询持久层；
     * 找到对应助手消息后再启动 TTS，避免把音频挂到临时占位消息上。
     */
    private fun scheduleTtsForLatestAssistant(
        finalContent: String? = null,
        excludedMessageIds: Set<String> = emptySet(),
        requireTtsEnabled: Boolean = true
    ) {
        val target = runtime
        val sessionId = currentSessionId
        if (sessionId.isBlank()) return
        val expectedContent = finalContent?.trim().orEmpty()

        fun findCandidate(messages: List<Message>): Message? {
            val eligible = messages.filter {
                isPersistedAssistantMessage(it) && it.id !in excludedMessageIds
            }
            return if (expectedContent.isNotBlank()) {
                eligible.lastOrNull { it.displayContent.trim() == expectedContent }
            } else {
                eligible.lastOrNull()
            }
        }

        val lookupKey = "_tts_lookup_${expectedContent.hashCode()}_${excludedMessageIds.hashCode()}_$requireTtsEnabled"
        if (target.ttsJobs[lookupKey]?.isActive == true) return
        val lookupJob = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            val config = resolveLatestTtsConfig(sessionId, requireTtsEnabled) ?: return@launch
            findCandidate(target.messages.value)?.let {
                val force = !requireTtsEnabled &&
                    it.id?.let(target.ttsStates.value::get)?.status == MessageTtsStatus.Error
                startMessageTts(
                    target,
                    sessionId,
                    it,
                    config,
                    force = force,
                    waitForAutoTitle = requireTtsEnabled
                )
                return@launch
            }
            repeat(20) {
                val candidate = when (val result = unified.listMessages(sessionId)) {
                    is Resource.Success -> findCandidate(result.data)
                    else -> null
                }
                if (candidate != null) {
                    val existing = target.messages.value.firstOrNull { it.id == candidate.id }
                    val mergedCandidate = candidate.copy(
                        audioUrl = candidate.audioUrl ?: existing?.audioUrl,
                        thinkingCards = candidate.thinkingCards ?: existing?.thinkingCards
                    )
                    target.messages.value = target.messages.value
                        .filterNot { existing ->
                            existing.id?.startsWith(STREAM_FALLBACK_PREFIX) == true &&
                                existing.content == candidate.content
                        }
                        .let { current ->
                            if (current.any { it.id == candidate.id }) {
                                current.map { if (it.id == candidate.id) mergedCandidate else it }
                            } else {
                                current + mergedCandidate
                            }
                        }
                    val force = !requireTtsEnabled &&
                        mergedCandidate.id?.let(target.ttsStates.value::get)?.status == MessageTtsStatus.Error
                    startMessageTts(
                        target,
                        sessionId,
                        mergedCandidate,
                        config,
                        force = force,
                        waitForAutoTitle = requireTtsEnabled
                    )
                    return@launch
                }
                kotlinx.coroutines.delay(250)
            }
        }
        target.ttsJobs[lookupKey] = lookupJob
        lookupJob.invokeOnCompletion {
            target.ttsJobs.remove(lookupKey, lookupJob)
            ChatSessionManager.pruneIfIdle(target.sessionId)
        }
        lookupJob.start()
    }

    private fun scheduleTtsForMessage(message: Message?) {
        if (message == null) return
        if (!isPersistedAssistantMessage(message)) {
            scheduleTtsForLatestAssistant(message.content)
            return
        }
        val target = runtime
        val sessionId = currentSessionId
        val messageId = message.id ?: return
        if (!message.audioUrl.isNullOrBlank()) {
            updateTtsState(target, messageId, MessageTtsUiState(MessageTtsStatus.Ready))
            return
        }
        if (
            target.ttsStates.value[messageId] != null ||
            target.ttsJobs[messageId]?.isActive == true
        ) return

        val prepareKey = "_tts_prepare_$messageId"
        if (target.ttsJobs[prepareKey]?.isActive == true) return
        val prepareJob = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            val config = resolveLatestTtsConfig(sessionId) ?: return@launch
            startMessageTts(target, sessionId, message, config)
        }
        target.ttsJobs[prepareKey] = prepareJob
        prepareJob.invokeOnCompletion {
            target.ttsJobs.remove(prepareKey, prepareJob)
            ChatSessionManager.pruneIfIdle(target.sessionId)
        }
        prepareJob.start()
    }

    private fun startMessageTts(
        target: ChatSessionState,
        sessionId: String,
        message: Message,
        config: ActiveTtsConfig,
        force: Boolean = false,
        waitForAutoTitle: Boolean = true
    ) {
        val messageId = message.id ?: return
        val text = prepareTtsText(message.displayContent)
        if (text.isBlank() || sessionId.isBlank()) return

        if (!force) {
            if (!message.audioUrl.isNullOrBlank()) {
                updateTtsState(target, messageId, MessageTtsUiState(MessageTtsStatus.Ready))
                return
            }
            if (target.ttsStates.value[messageId] != null) return
        }
        if (target.ttsJobs[messageId]?.isActive == true) return

        updateTtsState(target, messageId, MessageTtsUiState(MessageTtsStatus.Generating))
        if (force) {
            target.messages.value = target.messages.value.map {
                if (it.id == messageId) it.copy(audioUrl = null) else it
            }
        }

        val localMode = isLocalMode
        val ttsJob = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            try {
                if (waitForAutoTitle) waitForRemoteAutoTitle(sessionId)
                val audioUrl = if (localMode) {
                    when (
                        val result = unified.synthesizeAudio(
                            text = text,
                            voice = config.voice.takeIf { it.isNotBlank() },
                            speed = config.speed,
                            pitch = config.pitch,
                            volume = config.volume,
                            modelId = config.modelId
                        )
                    ) {
                        is Resource.Success -> result.data.cacheUri
                        is Resource.Error -> throw IllegalStateException(result.message)
                        is Resource.Loading -> throw IllegalStateException(string(R.string.tts_synthesis_incomplete))
                    }
                } else {
                    when (
                        val result = unified.synthesizeRemoteTts(
                            TtsPreviewRequest(
                                text = text,
                                modelId = config.modelId,
                                voice = config.voice,
                                speed = config.speed,
                                pitch = config.pitch,
                                volume = config.volume
                            )
                        )
                    ) {
                        is Resource.Success -> {
                            val response = result.data
                            if (response.success != true || response.audioUrl.isNullOrBlank()) {
                                throw IllegalStateException(response.message ?: string(R.string.tts_no_audio_returned))
                            }
                            response.audioUrl
                        }
                        is Resource.Error -> throw IllegalStateException(result.message)
                        is Resource.Loading -> throw IllegalStateException(string(R.string.tts_synthesis_incomplete))
                    }
                }

                target.messages.value = target.messages.value.map {
                    if (it.id == messageId) it.copy(audioUrl = audioUrl) else it
                }
                updateTtsState(target, messageId, MessageTtsUiState(MessageTtsStatus.Ready))

                when (val saved = unified.updateMessageAudioUrl(sessionId, messageId, audioUrl)) {
                    is Resource.Error -> android.util.Log.w(
                        "ChatTts",
                        "音频已生成，但保存到消息失败: ${saved.message}"
                    )
                    else -> Unit
                }
            } catch (e: Exception) {
                updateTtsState(
                    target,
                    messageId,
                    MessageTtsUiState(
                        status = MessageTtsStatus.Error,
                        error = e.message ?: string(R.string.tts_synthesis_failed)
                    )
                )
            }
        }
        target.ttsJobs[messageId] = ttsJob
        ttsJob.invokeOnCompletion {
            target.ttsJobs.remove(messageId, ttsJob)
            ChatSessionManager.pruneIfIdle(target.sessionId)
        }
        ttsJob.start()
    }

    /** 手动重试当前消息的 TTS，并立即切回“生成中”动画。 */
    fun regenerateMessageTts(message: Message) {
        if (!isPersistedAssistantMessage(message)) return
        val target = runtime
        val sessionId = currentSessionId
        val messageId = message.id ?: return
        val prepareKey = "_tts_regenerate_$messageId"
        if (target.ttsJobs[prepareKey]?.isActive == true) return
        val prepareJob = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            val config = resolveLatestTtsConfig(sessionId)
            if (config == null) {
                updateTtsState(
                    target,
                    messageId,
                    MessageTtsUiState(MessageTtsStatus.Error, string(R.string.tts_session_disabled))
                )
                return@launch
            }
            startMessageTts(target, sessionId, message, config, force = true)
        }
        target.ttsJobs[prepareKey] = prepareJob
        prepareJob.invokeOnCompletion {
            target.ttsJobs.remove(prepareKey, prepareJob)
            ChatSessionManager.pruneIfIdle(target.sessionId)
        }
        prepareJob.start()
    }

    /**
     * Live 对话始终需要语音输出，因此即使普通会话的自动 TTS 开关关闭，
     * 也会使用当前会话参数和已配置的 purpose=tts 模型生成并持久化音频。
     */
    fun prepareMessageTtsForLive(message: Message) {
        val target = runtime
        val sessionId = currentSessionId
        if (sessionId.isBlank() || message.isUser || message.displayContent.isBlank()) return

        if (!isPersistedAssistantMessage(message)) {
            scheduleTtsForLatestAssistant(
                finalContent = message.displayContent,
                requireTtsEnabled = false
            )
            return
        }

        val messageId = message.id ?: return
        if (!message.audioUrl.isNullOrBlank()) {
            updateTtsState(target, messageId, MessageTtsUiState(MessageTtsStatus.Ready))
            return
        }
        if (target.ttsJobs[messageId]?.isActive == true) return

        val prepareKey = "_live_tts_prepare_$messageId"
        if (target.ttsJobs[prepareKey]?.isActive == true) return
        val prepareJob = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            val config = resolveLatestTtsConfig(sessionId, requireEnabled = false) ?: return@launch
            val force = target.ttsStates.value[messageId]?.status == MessageTtsStatus.Error
            startMessageTts(
                target,
                sessionId,
                message,
                config,
                force = force,
                waitForAutoTitle = false
            )
        }
        target.ttsJobs[prepareKey] = prepareJob
        prepareJob.invokeOnCompletion {
            target.ttsJobs.remove(prepareKey, prepareJob)
            ChatSessionManager.pruneIfIdle(target.sessionId)
        }
        prepareJob.start()
    }

    /** 使用 purpose=live 的 Realtime 模型执行一轮原生语音对话。 */
    internal fun startRealtimeLiveTurn(
        pcm16: ByteArray,
        callbacks: LiveRealtimeTurnCallbacks
    ) {
        val sessionId = currentSessionId
        if (sessionId.isBlank() || pcm16.isEmpty()) return
        realtimeLiveJob?.cancel()
        _sending.value = true
        clearError()

        val currentSession = _session.value
        val context = _messages.value
            .asSequence()
            .filterNot { it.isThinkingCard || it.id == STREAMING_ID || it.displayContent.isBlank() }
            .map {
                RealtimeContextMessage(
                    role = if (it.isUser) "user" else "assistant",
                    content = it.displayContent
                )
            }
            .toList()
        val job = viewModelScope.launch {
            try {
                val preparedPrompt = currentSession?.composedSystemPrompt
                    ?.takeIf(String::isNotBlank)
                    ?: when (val result = unified.prepareRealtimeLivePrompt(sessionId)) {
                        is Resource.Success -> result.data.takeIf(String::isNotBlank)
                        is Resource.Error -> throw IllegalStateException(result.message)
                        is Resource.Loading -> null
                    }
                val config = when (val result = unified.getRealtimeLiveModel()) {
                    is Resource.Success -> result.data
                    is Resource.Error -> throw IllegalStateException(result.message)
                    is Resource.Loading -> throw IllegalStateException("Live 模型配置仍在加载")
                }
                val toolRuntime = if (
                    currentSession?.sessionMode.equals("agent", ignoreCase = true) &&
                    config.isQwenRealtime
                ) {
                    unified.createRealtimeAgentToolRuntime(sessionId)
                } else {
                    null
                }
                val instructions = buildString {
                    val systemPrompt = preparedPrompt
                        ?: currentSession?.systemPrompt?.takeIf(String::isNotBlank)
                    if (systemPrompt != null) appendLine(systemPrompt)
                    append("你正在与用户进行实时语音通话。延续上述会话设定与完整上下文，使用用户当前的语言自然、简洁地回答。")
                    if (toolRuntime != null) {
                        append("需要完成实际操作时，使用已提供的工具；工具返回后再向用户说明结果。")
                    }
                }
                val toolCallHandler: (suspend (RealtimeFunctionCall) -> Map<String, Any>)? =
                    toolRuntime?.let { runtime ->
                        { call ->
                            runtime.execute(
                                call.name,
                                parseRealtimeToolArguments(call.arguments)
                            )
                        }
                    }
                realtimeVoiceClient.streamTurn(
                    config = config,
                    instructions = instructions,
                    context = context,
                    pcm16 = pcm16,
                    tools = toolRuntime?.tools.orEmpty(),
                    onToolCall = toolCallHandler
                ).collect { event ->
                    when (event) {
                        RealtimeVoiceEvent.Connected -> callbacks.onConnected()
                        is RealtimeVoiceEvent.UserTranscript ->
                            callbacks.onUserTranscript(event.text, event.isFinal)
                        is RealtimeVoiceEvent.AssistantTranscript ->
                            callbacks.onAssistantTranscript(event.text, event.isFinal)
                        is RealtimeVoiceEvent.AudioDelta -> callbacks.onAudioDelta(event.pcm16)
                        is RealtimeVoiceEvent.Failure -> throw IllegalStateException(event.message)
                        is RealtimeVoiceEvent.Completed -> {
                            val userText = event.userTranscript.trim().ifBlank {
                                string(R.string.live_voice_message)
                            }
                            val assistantText = event.assistantTranscript.trim()
                            if (assistantText.isBlank()) {
                                throw IllegalStateException(string(R.string.live_realtime_empty_response))
                            }
                            callbacks.onUserTranscript(userText, true)
                            callbacks.onAssistantTranscript(assistantText, true)
                            when (
                                val saved = unified.addConversationMessage(
                                    sessionId,
                                    role = "user",
                                    content = userText
                                )
                            ) {
                                is Resource.Error -> throw IllegalStateException(saved.message)
                                else -> Unit
                            }
                            when (
                                val saved = unified.addConversationMessage(
                                    sessionId,
                                    role = "assistant",
                                    content = assistantText,
                                    sender = currentSession?.senderName ?: currentSession?.characterName
                                )
                            ) {
                                is Resource.Error -> throw IllegalStateException(saved.message)
                                else -> Unit
                            }
                            _sending.value = false
                            loadMessages()
                            callbacks.onCompleted()
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 主动挂断或切换链路属于正常取消。
            } catch (error: Exception) {
                val message = error.message ?: string(R.string.live_realtime_failed)
                showError(message)
                callbacks.onError(message)
            } finally {
                _sending.value = false
            }
        }
        realtimeLiveJob = job
        job.invokeOnCompletion {
            if (realtimeLiveJob === job) realtimeLiveJob = null
        }
    }

    fun stopRealtimeLiveTurn() {
        realtimeLiveJob?.cancel()
        realtimeLiveJob = null
        _sending.value = false
    }

    /**
     * 发送消息：
     * - 本地模式：调用 [UnifiedRepository.chatStream] 返回的 Flow，直接收集事件
     * - 服务器模式：优先通过 Socket.IO send_message 触发 AI（服务端会推送流式回复），
     *   Socket 未连接时回退到 HTTP /chat
     */
    fun resumeAgentRun() {
        val recovery = _agentRecovery.value ?: return
        if (!isLocalMode || _sending.value || runtime.hasBlockingLocalChatJob()) return

        generationStopRequested = false
        _sending.value = true
        clearError()
        streamingContent.setLength(0)
        streamingReasoning.setLength(0)
        runtime.streamingContentPreview.value = ""
        runtime.streamingReasoningPreview.value = ""

        if (recovery.canContinueFromCheckpoint) {
            _messages.value = _messages.value + Message(
                role = "user",
                content = string(R.string.common_continue),
                timestamp = System.currentTimeMillis().toString()
            )
        }
        _messages.value = _messages.value.filter { it.id != streamingId } + Message(
            id = streamingId,
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis().toString()
        )

        startLocalChatCollection { chatJob ->
            val flow = try {
                unified.resumeAgentRunStream(
                    currentSessionId,
                    ServiceContainer.prefs.getSessionReasoningEffort(currentSessionId)
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@startLocalChatCollection
            } catch (error: Exception) {
                _sending.value = false
                _messages.value = _messages.value.filter { it.id != streamingId }
                showError(error.message ?: string(R.string.chat_agent_run_resume_failed))
                return@startLocalChatCollection
            }
            if (flow == null) {
                _sending.value = false
                _messages.value = _messages.value.filter { it.id != streamingId }
                showError(string(R.string.chat_agent_run_resume_failed))
                return@startLocalChatCollection
            }
            try {
                flow.collect { event -> handleRealtimeEvent(event, chatJob) }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 用户再次停止属于正常取消，检查点仍由 Room 保留。
            } catch (error: Exception) {
                _sending.value = false
                _messages.value = _messages.value.filter { it.id != streamingId }
                showError(error.message ?: string(R.string.chat_agent_run_resume_failed))
            }
        }
    }

    fun discardAgentRun() {
        val sessionId = currentSessionId.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            unified.discardAgentRun(sessionId)
            _messages.value = _messages.value.map { message ->
                val cards = message.thinkingCards?.map { card ->
                    if (card.isComplete) card else card.copy(isComplete = true)
                }
                if (cards != null) message.copy(thinkingCards = cards) else message
            }
        }
    }

    fun sendMessage(
        text: String,
        plotChoiceId: String? = null,
        attachments: List<Map<String, Any>> = emptyList(),
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort = com.nekobot.app.data.model.ReasoningEffort.NONE
    ) {
        val content = text.trim()
        val messageContent = buildChatMessageContent(content, attachments)
        if (messageContent.isBlank()) return
        if (_sending.value || runtime.hasBlockingLocalChatJob() || currentSessionId.isBlank()) return
        if (plotChoiceId != null) {
            viewModelScope.launch {
                commitPlotChoiceSelection(plotChoiceId)
                sendMessage(content, attachments = attachments, reasoningEffort = reasoningEffort)
            }
            return
        }
        // 乐观更新
        val optimistic = Message(
            role = "user",
            content = messageContent,
            timestamp = System.currentTimeMillis().toString()
        )
        _messages.value = _messages.value + optimistic
        generationStopRequested = false
        _sending.value = true
        clearError()

        // 立即创建流式占位消息，显示骨架动画（等待第一个 chunk）
        streamingContent.setLength(0)
        streamingReasoning.setLength(0)
        runtime.streamingContentPreview.value = ""
        runtime.streamingReasoningPreview.value = ""
        val placeholder = Message(
            id = streamingId,
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis().toString()
        )
        _messages.value = _messages.value.filter { it.id != streamingId } + placeholder

        if (isLocalMode) {
            // 本地模式：直接收集 Flow 事件
            // 挂到 applicationScope：退出聊天界面后 AI 生成继续后台运行
            startLocalChatCollection { chatJob ->
                val flow = try {
                    unified.chatStream(currentSessionId, messageContent, attachments, reasoningEffort)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@startLocalChatCollection
                } catch (e: Exception) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    showError(e.message ?: string(R.string.chat_send_failed))
                    return@startLocalChatCollection
                }
                if (flow == null) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    showError(string(R.string.chat_no_ai_model))
                    return@startLocalChatCollection
                }
                try {
                    flow.collect { event -> handleRealtimeEvent(event, chatJob) }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // 停止生成、应用退出或会话回收都属于正常协程取消，不能显示为聊天错误。
                } catch (e: Exception) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    val errMsg = e.message ?: string(R.string.chat_send_failed)
                    showError(errMsg)
                    // 用户已退出聊天界面时，通过通知栏告知后台生成失败
                    trySendErrorNotification(errMsg)
                }
            }
        } else if (socket.state.value == SocketState.Connected) {
            // Socket.IO 路径：触发 send_message，等待流式推送
            socket.sendMessage(currentSessionId, messageContent, attachments, reasoningEffort)
            // 兜底：若 60 秒仍无 chunk 回调，尝试刷新消息
            ServiceContainer.applicationScope.launch {
                kotlinx.coroutines.delay(60000)
                if (_sending.value && streamingContent.isEmpty()) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    loadMessages()
                }
            }
        } else {
            // Socket 未连接，回退 HTTP
            launchHttpChat(messageContent, attachments, reasoningEffort)
        }
    }

    /** HTTP /chat 回退路径：触发后等待 socket 推送或轮询。 */
    private fun launchHttpChat(
        content: String,
        attachments: List<Map<String, Any>> = emptyList(),
        reasoningEffort: com.nekobot.app.data.model.ReasoningEffort = com.nekobot.app.data.model.ReasoningEffort.NONE
    ) {
        val previousAssistantIds = _messages.value
            .filterNot { it.isUser }
            .mapNotNull { it.id }
            .toSet()
        // 创建流式占位消息，显示骨架动画（等待第一个 chunk）
        streamingContent.setLength(0)
        streamingReasoning.setLength(0)
        runtime.streamingContentPreview.value = ""
        runtime.streamingReasoningPreview.value = ""
        val placeholder = Message(
            id = streamingId,
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis().toString()
        )
        _messages.value = _messages.value.filter { it.id != streamingId } + placeholder
        launchResult(
            block = { unified.chat(currentSessionId, content, attachments, reasoningEffort) },
            onSuccess = {
                _sending.value = false
                // HTTP 成功后稍等再刷新，给 AI 生成时间
                ServiceContainer.applicationScope.launch {
                    kotlinx.coroutines.delay(1500)
                    loadMessages()
                    // 再次延迟刷新确保拉到回复
                    kotlinx.coroutines.delay(2000)
                    loadMessages()
                    scheduleTtsForLatestAssistant(excludedMessageIds = previousAssistantIds)
                }
            },
            onError = {
                _sending.value = false
                // 移除占位消息
                _messages.value = _messages.value.filter { it.id != streamingId }
                showError(it)
            }
        )
    }

    /** 检查是否需要发送本地通知（用户不在聊天界面且开启了通知提醒时） */
    private fun trySendNotification(content: String) {
        if (content.isBlank()) return
        if (isChatVisible) return // 用户在聊天界面，不通知
        val sid = currentSessionId
        if (sid.isBlank()) return
        if (!ServiceContainer.prefs.isSessionNotificationEnabled(sid)) return
        ServiceContainer.applicationScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                val ctx = ServiceContainer.appContext ?: return@launch
                val sender = _session.value?.senderName ?: _session.value?.characterName ?: "AI"
                val preview = content.take(100) + if (content.length > 100) "..." else ""
                val mgr = androidx.core.app.NotificationManagerCompat.from(ctx)
                if (!mgr.areNotificationsEnabled()) return@launch
                val channelId = "nekobot_chat_reply"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        string(R.string.chat_notification_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = string(R.string.chat_notification_desc) }
                    androidx.core.app.NotificationManagerCompat.from(ctx).createNotificationChannel(channel)
                }
                // 点击通知跳转到对应会话聊天界面
                val launchIntent = android.content.Intent(ctx, com.nekobot.app.MainActivity::class.java).apply {
                    putExtra("session_id", sid)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    ctx, sid.hashCode(), launchIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notif = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(sender)
                    .setContentText(preview)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content.take(500)))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                mgr.notify(sid.hashCode(), notif)
            } catch (_: Exception) { /* 忽略通知发送失败 */ }
        }
    }

    /**
     * 后台 AI 生成失败时通过通知栏告知用户。
     *
     * 与 [trySendNotification] 的区别：
     * - 标题明确标注"生成失败"，避免与正常回复通知混淆
     * - 不检查 [isChatVisible]：用户可能已经退出聊天界面，但仍需知道后台生成失败
     * - 仍检查通知开关：尊重用户"不通知"的偏好
     */
    private fun trySendErrorNotification(errorMessage: String) {
        if (errorMessage.isBlank()) return
        val sid = currentSessionId
        if (sid.isBlank()) return
        // 仅在用户不在界面（VM 即将销毁或已销毁）时发送错误通知；
        // 在界面内时 showError 已经显示了 Toast/Banner，无需重复
        if (isChatVisible) return
        if (!ServiceContainer.prefs.isSessionNotificationEnabled(sid)) return
        ServiceContainer.applicationScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                val ctx = ServiceContainer.appContext ?: return@launch
                val sessionName = _session.value?.name
                    ?: _session.value?.characterName
                    ?: string(R.string.chat_notification_channel)
                val mgr = androidx.core.app.NotificationManagerCompat.from(ctx)
                if (!mgr.areNotificationsEnabled()) return@launch
                // 复用 chat_reply 渠道，避免新建渠道打扰用户
                val channelId = "nekobot_chat_reply"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        string(R.string.chat_notification_channel),
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = string(R.string.chat_notification_desc) }
                    mgr.createNotificationChannel(channel)
                }
                val launchIntent = android.content.Intent(ctx, com.nekobot.app.MainActivity::class.java).apply {
                    putExtra("session_id", sid)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    ctx, sid.hashCode(), launchIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notif = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(string(R.string.chat_generation_failed_notification, sessionName))
                    .setContentText(errorMessage.take(200))
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(errorMessage.take(500)))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                // 用 sid.hashCode() + 1 避免与正常回复通知 ID 冲突
                mgr.notify(sid.hashCode() + 1, notif)
            } catch (_: Exception) { /* 忽略通知发送失败 */ }
        }
    }

    /** 重新生成最后一条 AI 回复：先隐藏旧 AI 消息，再请求重新生成。 */
    fun regenerate() {
        if (_sending.value || runtime.hasBlockingLocalChatJob() || currentSessionId.isBlank()) return
        // 找到最后一条 assistant 消息的 id 传给服务器
        val lastAssistant = _messages.value.lastOrNull { !it.isUser }
        val messageId = lastAssistant?.id
        if (messageId.isNullOrBlank()) {
            showError(string(R.string.chat_no_ai_to_regenerate))
            return
        }
        // 先从列表中移除旧的 AI 回复（含其后的所有消息）
        val removeIndex = _messages.value.indexOfLast { it.id == messageId }
        if (removeIndex >= 0) {
            _messages.value = _messages.value.subList(0, removeIndex)
        }
        generationStopRequested = false
        _sending.value = true
        // 如果剧情模式开启，清除旧选项并显示骨架（仅服务器模式）
        if (!isLocalMode && _session.value?.plotMode == true) {
            _plotChoices.value = emptyList()
            _plotChoicesLoading.value = true
        }
        if (isLocalMode) {
            // 本地模式：直接收集 Flow 事件
            // 挂到 applicationScope：退出聊天界面后 AI 生成继续后台运行
            startLocalChatCollection { chatJob ->
                val flow = try {
                    unified.regenerateStream(
                        currentSessionId,
                        messageId,
                        ServiceContainer.prefs.getSessionReasoningEffort(currentSessionId)
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@startLocalChatCollection
                } catch (e: Exception) {
                    _sending.value = false
                    showError(e.message ?: string(R.string.chat_regenerate_failed))
                    return@startLocalChatCollection
                }
                if (flow == null) {
                    _sending.value = false
                    showError(string(R.string.chat_no_ai_model))
                    return@startLocalChatCollection
                }
                try {
                    flow.collect { event -> handleRealtimeEvent(event, chatJob) }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // 正常取消不显示 StandaloneCoroutine 错误。
                } catch (e: Exception) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    val errMsg = e.message ?: string(R.string.chat_regenerate_failed)
                    showError(errMsg)
                    // 用户已退出聊天界面时，通过通知栏告知后台重新生成失败
                    trySendErrorNotification(errMsg)
                }
            }
        } else {
            launchResult(
                block = {
                    unified.regenerate(
                        currentSessionId,
                        messageId,
                        ServiceContainer.prefs.getSessionReasoningEffort(currentSessionId)
                    )
                },
                onSuccess = {
                    // 等待 socket 推送流式，或延迟刷新
                    ServiceContainer.applicationScope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (_sending.value) loadMessages()
                    }
                },
                onError = {
                    _sending.value = false
                    _plotChoicesLoading.value = false
                    showError(it)
                }
            )
        }
    }

    /**
     * 重新生成开场白（首条 AI 消息）。
     * 本地模式：删除旧开场白，用 AI 生成新的开场白，流式返回。
     * 服务器模式：走 regenerate API（传首条消息 id）。
     */
    fun regenerateGreeting() {
        if (_sending.value || runtime.hasBlockingLocalChatJob() || currentSessionId.isBlank()) return
        // 找到首条 assistant 消息
        val firstAssistant = _messages.value.firstOrNull { !it.isUser }
        val messageId = firstAssistant?.id
        if (messageId.isNullOrBlank()) {
            showError(string(R.string.chat_no_ai_to_regenerate))
            return
        }
        // 从内存列表移除旧开场白
        _messages.value = _messages.value.filter { it.id != messageId }
        generationStopRequested = false
        _sending.value = true
        if (isLocalMode) {
            startLocalChatCollection { chatJob ->
                val flow = try {
                    unified.regenerateGreetingStream(
                        currentSessionId,
                        ServiceContainer.prefs.getSessionReasoningEffort(currentSessionId)
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    return@startLocalChatCollection
                } catch (e: Exception) {
                    _sending.value = false
                    showError(e.message ?: string(R.string.chat_regenerate_failed))
                    return@startLocalChatCollection
                }
                if (flow == null) {
                    _sending.value = false
                    showError(string(R.string.chat_no_ai_model))
                    return@startLocalChatCollection
                }
                try {
                    flow.collect { event -> handleRealtimeEvent(event, chatJob) }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // 正常取消不显示错误
                } catch (e: Exception) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    val errMsg = e.message ?: string(R.string.chat_regenerate_failed)
                    showError(errMsg)
                    trySendErrorNotification(errMsg)
                }
            }
        } else {
            launchResult(
                block = {
                    unified.regenerateGreeting(
                        currentSessionId,
                        messageId,
                        ServiceContainer.prefs.getSessionReasoningEffort(currentSessionId)
                    )
                },
                onSuccess = {
                    ServiceContainer.applicationScope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (_sending.value) loadMessages()
                    }
                },
                onError = {
                    _sending.value = false
                    showError(it)
                }
            )
        }
    }

    /** 停止生成。 */
    fun stop() {
        val sessionId = currentSessionId.ifBlank { return }
        generationStopRequested = true

        _execConfirmation.value?.let { request ->
            val confirmationSessionId = request.sessionId.ifBlank { sessionId }
            if (isLocalMode) {
                unified.respondToLocalExecConfirmation(
                    requestId = request.requestId,
                    sessionId = confirmationSessionId,
                    authorization = ExecAuthorization.Reject
                )
            } else {
                socket.respondToExecConfirmation(
                    requestId = request.requestId,
                    authorization = ExecAuthorization.Reject,
                    sessionId = confirmationSessionId
                )
            }
        }
        _execConfirmation.value = null
        _sending.value = false
        _plotChoicesLoading.value = false
        streamingContent.setLength(0)
        streamingReasoning.setLength(0)
        runtime.streamingContentPreview.value = ""
        runtime.streamingReasoningPreview.value = ""
        _messages.value = _messages.value
            .filter { it.id != streamingId }
            .map { message ->
                val cards = message.thinkingCards?.map { card ->
                    if (card.isComplete) card else card.copy(isComplete = true)
                }
                if (cards != null) message.copy(thinkingCards = cards) else message
            }

        val chatJob = localChatJob
        viewModelScope.launch {
            unified.stopGeneration(sessionId)
            if (isLocalMode) {
                chatJob?.cancel()
                if (localChatJob === chatJob) localChatJob = null
            }
            kotlinx.coroutines.delay(150)
            loadMessages()
        }
        showToast(
            string(
                if (isLocalMode) R.string.chat_stopped_toast
                else R.string.chat_stop_requested
            )
        )
    }

    /** 执行聊天页命令行输入，并在主线程返回结果以更新 Compose 状态。 */
    fun executeSandboxCommand(
        command: String,
        onResult: (LocalSandboxCommandResult) -> Unit,
    ) {
        val sessionId = currentSessionId
        if (sessionId.isBlank() || !isLocalMode) return
        viewModelScope.launch {
            onResult(unified.executeSandboxCommand(sessionId, command))
        }
    }

    fun stopSandboxCommand() {
        currentSessionId.takeIf(String::isNotBlank)?.let(unified::stopSandboxCommand)
    }

    /** 群聊气泡需要按每条消息的 sender 匹配成员角色卡头像。 */
    private fun loadGroupCharacters(characterIds: List<String>) {
        val selectedIds = characterIds.filter(String::isNotBlank).toSet()
        viewModelScope.launch {
            when (val result = unified.listCharacters()) {
                is Resource.Success -> {
                    val characters = result.data ?: emptyList()
                    _groupCharacters.value = if (selectedIds.isEmpty()) {
                        characters
                    } else {
                        characters.filter { it.id in selectedIds }
                    }
                }
                is Resource.Error -> _groupCharacters.value = emptyList()
                is Resource.Loading -> Unit
            }
        }
    }

    /** 压缩上下文：将早期消息摘要化以节省 token。 */
    fun compressContext() {
        if (currentSessionId.isBlank()) return
        val showAgentCompressionState = isLocalMode && isAgentSession()
        if (showAgentCompressionState) _agentContextCompressionInProgress.value = true
        launchResult(
            block = { unified.compressContext(currentSessionId) },
            onSuccess = { json ->
                if (showAgentCompressionState) _agentContextCompressionInProgress.value = false
                val compressed = json?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("compressed")?.asBoolean ?: true
                // 后端返回 archive_session_id，写回当前 session 状态
                val archiveId = json?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("archive_session_id")?.asString
                if (archiveId != null) {
                    _session.value = _session.value?.copy(archiveSessionId = archiveId)
                }
                if (compressed) {
                    showToast(string(R.string.chat_context_compressed))
                    loadMessages()
                } else {
                    showToast(string(R.string.chat_context_compression_not_needed))
                }
            },
            onError = { message ->
                if (showAgentCompressionState) _agentContextCompressionInProgress.value = false
                showError(message)
            }
        )
    }

    /**
     * 切换剧情模式开关。
     * 使用 unified.updateSession 持久化（与 SessionDetailScreen 一致），
     * 避免 plotToggle API 要求会话已加载到 server.sessions 内存字典的限制。
     */
    fun togglePlotMode() {
        val sid = currentSessionId.ifBlank { return }
        val current = _session.value?.plotMode == true
        // 乐观更新：先翻转 UI 状态，再调用 API；API 失败时回滚并 Toast 提示
        _session.value = _session.value?.copy(plotMode = !current)
        if (current) {
            // 关闭剧情模式时同步关闭实时同步，避免脏状态
            _session.value = _session.value?.copy(plotRealTimeSync = false)
        }
        launchResult(
            block = {
                unified.updateSession(
                    sid,
                    UpdateSessionRequest(
                        plotMode = !current,
                        plotRealTimeSync = if (current) false else _session.value?.plotRealTimeSync
                    )
                )
            },
            onSuccess = {
                // 开启剧情模式后，持久化成功，立即生成/拉取一次剧情选项
                if (!current) {
                    if (isLocalMode) {
                        regeneratePlotChoices()
                    } else {
                        loadPlotChoices()
                    }
                }
            },
            onError = {
                // 回滚
                _session.value = _session.value?.copy(
                    plotMode = current,
                    plotRealTimeSync = if (current) _session.value?.plotRealTimeSync else false
                )
                showToast(it)
            }
        )
        showToast(
            string(if (!current) R.string.sessions_detail_plot_mode_on else R.string.sessions_detail_plot_mode_off)
        )
    }

    /**
     * 切换同步现实时间开关：仅在剧情模式开启时可用。
     * 使用 unified.updateSession 持久化（与 SessionDetailScreen 一致），
     * 避免 plotRealTimeSyncToggle API 要求会话已加载到 server.sessions 内存字典的限制。
     */
    fun togglePlotRealTimeSync() {
        val sid = currentSessionId.ifBlank { return }
        if (_session.value?.plotMode != true) return
        val current = _session.value?.plotRealTimeSync == true
        // 乐观更新：先翻转 UI，再调用 API；API 失败回滚
        _session.value = _session.value?.copy(plotRealTimeSync = !current)
        launchWith(
            onError = {
                // 回滚
                _session.value = _session.value?.copy(plotRealTimeSync = current)
                showToast(it)
            },
            block = {
                unified.updateSession(
                    sid,
                    UpdateSessionRequest(plotRealTimeSync = !current)
                )
            }
        )
        showToast(
            string(if (!current) R.string.sessions_detail_realtime_sync_on else R.string.sessions_detail_realtime_sync_off)
        )
    }



    /** 从归档会话提取 N 轮对话回到当前会话。 */
    fun restoreFromArchive(turns: Int) {
        if (currentSessionId.isBlank()) return
        launchResult(
            block = { unified.restoreFromArchive(currentSessionId, turns) },
            onSuccess = {
                showToast(string(R.string.chat_extracted_from_archive, turns))
                loadMessages()
            }
        )
    }

    /** 查看归档会话：拉取归档会话详情并以只读方式展示。 */
    fun viewArchive(onLoaded: (Session, List<Message>) -> Unit) {
        val archiveId = _session.value?.archiveSessionId ?: return
        launchResult(
            block = { unified.getSession(archiveId) },
            onSuccess = { archiveSession ->
                val sid = archiveSession.id ?: return@launchResult
                launchResult(
                    block = { unified.listMessages(sid) },
                    onSuccess = { msgs -> onLoaded(archiveSession, msgs ?: emptyList()) }
                )
            }
        )
    }

    /** 从指定消息处分叉新会话，成功后回调 [onSuccess] 传入新会话 ID。 */
    fun forkFromMessage(messageId: String, onSuccess: (String) -> Unit) {
        if (currentSessionId.isBlank()) return
        launchResult(
            block = { unified.forkSession(currentSessionId, messageId) },
            onSuccess = { json ->
                val newId = when {
                    json.isJsonObject -> json.asJsonObject.get("new_session_id")?.asString
                        ?: json.asJsonObject.get("id")?.asString
                        ?: json.asJsonObject.get("session_id")?.asString
                    else -> null
                }
                if (newId != null) {
                    showToast(string(R.string.chat_forked_toast))
                    onSuccess(newId)
                } else {
                    showToast(string(R.string.chat_fork_no_id))
                }
            }
        )
    }

    /** 删除单条消息，成功后回调 [onSuccess]。 */
    fun deleteMessage(sessionId: String, messageId: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.deleteMessage(sessionId, messageId) },
            onSuccess = {
                showToast(string(R.string.chat_deleted_toast))
                loadMessages()
                onSuccess()
            }
        )
    }

    /** 根据长按的消息内容创建持久生图任务，并交给 WorkManager 异步执行。 */
    fun generateImageForMessage(message: Message) {
        val sessionId = currentSessionId.takeIf(String::isNotBlank) ?: return
        val messageId = message.id?.takeIf(String::isNotBlank) ?: return
        val messagePrompt = message.displayContent.trim()
        if (messagePrompt.isBlank()) return

        viewModelScope.launch {
            val currentSession = _session.value
            val characterId = currentSession?.characterId
                ?: currentSession?.characterIds?.firstOrNull()
            val character = characterId?.let { id ->
                when (val result = unified.getCharacter(id)) {
                    is Resource.Success -> result.data
                    else -> null
                }
            }
            val prompt = buildMessageImagePrompt(messagePrompt, currentSession, character)
            val referencePath = resolveAvatarUrl(
                character?.portrait
                    ?: character?.avatar
                    ?: currentSession?.portraitUrl
            )
            when (
                val result = unified.enqueueMessageImage(
                    sessionId = sessionId,
                    messageId = messageId,
                    prompt = prompt,
                    referenceImagePath = referencePath
                )
            ) {
                is Resource.Success -> {
                    val context = ServiceContainer.appContext
                    if (context == null) {
                        ServiceContainer.localRepository.failMessageImage(
                            result.data.id,
                            "应用上下文未初始化"
                        )
                        showError(string(R.string.chat_message_image_failed, "应用上下文未初始化"))
                    } else {
                        MessageImageGenerationScheduler.enqueue(context, result.data.id)
                        showToast(string(R.string.chat_message_image_queued_toast))
                    }
                }

                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }

    private fun buildMessageImagePrompt(
        messagePrompt: String,
        session: Session?,
        character: CharacterPreset?
    ): String {
        val sections = mutableListOf<String>()
        fun add(label: String, value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let {
                sections += "$label：${it.take(8_000)}"
            }
        }

        add("角色名", character?.name ?: session?.characterName)
        add("角色描述", character?.description)
        add("基础信息", character?.basicInfo)
        add("性格", character?.personality)
        add("角色场景", character?.scenario ?: session?.scenario)
        add("系统设定", character?.systemPrompt)
        add("行为规则", character?.rules?.joinToString("\n"))
        add("标签", character?.tags?.joinToString(", "))
        add("首条问候", character?.firstMessage)
        add("备用问候", character?.alternateGreetings?.joinToString("\n"))
        add("示例对话", character?.exampleDialogues)
        add("会话设定", session?.systemPrompt)

        return buildString {
            appendLine("请根据下面的画面要求生成一张图片。")
            if (sections.isNotEmpty()) {
                appendLine("角色卡上下文（仅用于保持角色身份、外观、服装、气质与世界观一致，不要把这些说明文字绘制到图片中）：")
                sections.forEach { appendLine(it) }
            }
            appendLine("本次消息的画面要求：")
            appendLine(messagePrompt)
            append("请优先保持角色卡与参考立绘中的人物特征一致，并将消息内容转化为自然的画面构图；图片中不要出现角色卡、提示词或解释文字。")
        }.take(32_000)
    }

    /** 进入多选模式，并预选指定消息。 */
    fun enterSelectionMode(messageId: String) {
        _selectionMode.value = true
        _selectedMessageIds.value = setOf(messageId)
    }

    /** 切换某条消息的选中状态；全部取消后自动退出多选模式。 */
    fun toggleSelection(messageId: String) {
        val current = _selectedMessageIds.value.toMutableSet()
        if (messageId in current) current.remove(messageId) else current.add(messageId)
        _selectedMessageIds.value = current
        if (current.isEmpty()) _selectionMode.value = false
    }

    /** 退出多选模式并清空选中。 */
    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedMessageIds.value = emptySet()
    }

    /** 删除所有选中的消息，完成后退出多选模式并刷新列表。 */
    fun deleteSelectedMessages() {
        val ids = _selectedMessageIds.value.toList()
        if (ids.isEmpty()) return
        val sid = currentSessionId
        viewModelScope.launch {
            ids.forEach { id ->
                try { unified.deleteMessage(sid, id) } catch (_: Exception) {}
            }
            exitSelectionMode()
            loadMessages()
        }
    }

    /** 清空会话所有消息，成功后回调 [onSuccess]。 */
    fun clearMessages(sessionId: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.clearMessages(sessionId) },
            onSuccess = {
                showToast(string(R.string.chat_messages_cleared))
                _messages.value = emptyList()
                onSuccess()
            }
        )
    }

    override fun onCleared() {
        stopRealtimeLiveTurn()
        messageImagesJob?.cancel()
        super.onCleared()
        // 不取消 eventsJob / localChatJob：让 AI 生成流程在后台继续运行
        // 仅释放本 VM 对 runtime 的引用计数；若没有其他订阅者且无活跃 Job，状态会被清理
        if (currentSessionId.isNotBlank()) {
            ChatSessionManager.release(currentSessionId)
        }
        // 服务器模式：不主动 leaveSession，让 Socket.IO 继续接收推送，
        // 用户下次进入会话时通过 loadMessages 拉持久化结果即可
    }

    /** 加载最新剧情选项（仅服务器模式 + plot_mode 开启时调用）。 */
    fun loadPlotChoices() {
        if (isLocalMode || currentSessionId.isBlank()) return
        _plotChoicesLoading.value = true
        launchResult(
            block = { repo.getLatestPlotChoices(currentSessionId) },
            onSuccess = { json ->
                _plotChoices.value = parsePlotChoices(json)
                _plotChoicesLoading.value = false
            },
            onError = { _plotChoicesLoading.value = false }
        )
    }

    /** 本地模式：从 SharedPreferences 加载已保存的剧情选项 */
    fun loadLocalPlotChoices() {
        if (currentSessionId.isBlank()) return
        viewModelScope.launch {
            val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.nekobot.app.ServiceContainer.localRepository.getPlotChoices(currentSessionId)
            }
            if (json != null) {
                try {
                    val element = com.google.gson.JsonParser.parseString(json)
                    _plotChoices.value = parsePlotChoices(element)
                } catch (_: Exception) { }
            }
            _plotChoicesLoading.value = false
        }
    }

    /** 在发送消息前提交最终剧情选项，并清除同节点的其他选中状态。 */
    private suspend fun commitPlotChoiceSelection(choiceId: String) {
        if (currentSessionId.isBlank()) return
        _plotChoices.value = _plotChoices.value.map { choice ->
            choice.copy(selected = choice.id == choiceId)
        }
        if (isLocalMode) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val local = ServiceContainer.localRepository
                    val raw = local
                        .getPlotChoices(currentSessionId) ?: "{\"choices\":[]}"
                    val payload = com.google.gson.JsonParser.parseString(raw).asJsonObject
                    val choicesArr = payload.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                    choicesArr?.forEach { element ->
                        if (element.isJsonObject) {
                            val obj = element.asJsonObject
                            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                                ?: obj.get("choice_id")?.takeIf { it.isJsonPrimitive }?.asString
                            if (id != null) {
                                obj.addProperty("selected", id == choiceId)
                            }
                        }
                    }
                    local.commitPlotChoiceSelection(
                        sessionId = currentSessionId,
                        choiceId = choiceId,
                        choicesJson = payload.toString()
                    )
                } catch (_: Exception) { }
            }
            return
        }
        try {
            repo.selectPlotChoice(currentSessionId, choiceId)
        } catch (_: Exception) { }
    }

    /** 重新生成剧情选项。 */
    fun regeneratePlotChoices() {
        if (currentSessionId.isBlank()) return
        if (isLocalMode) {
            // 本地模式：清除当前选项，重新触发聊天流程的最后一步生成选项
            _plotChoicesLoading.value = true
            _plotChoices.value = emptyList()
            // 挂到 applicationScope：剧情选项的 AI 生成不应因退出界面而中断
            ServiceContainer.applicationScope.launch {
                try {
                    val repo = com.nekobot.app.ServiceContainer.localRepository
                    val session = repo.getSession(currentSessionId)
                    if (session?.plotMode == true) {
                        // 调用本地重新生成（复用 LocalRepository 的 regeneratePlotChoices）
                        val flow = repo.regeneratePlotChoicesLocal(currentSessionId)
                        flow.collect { event ->
                            when (event) {
                                is RealtimeEvent.PlotChoices -> {
                                    _plotChoices.value = parsePlotChoices(event.choices)
                                    _plotChoicesLoading.value = false
                                }
                                is RealtimeEvent.Error -> {
                                    _plotChoicesLoading.value = false
                                }
                                else -> {}
                            }
                        }
                    } else {
                        _plotChoicesLoading.value = false
                    }
                } catch (e: Exception) {
                    _plotChoicesLoading.value = false
                }
            }
            return
        }
        _plotChoicesLoading.value = true
        _plotChoices.value = emptyList()
        launchResult(
            block = { repo.regeneratePlotChoices(currentSessionId) },
            onSuccess = { json ->
                _plotChoices.value = parsePlotChoices(json)
                _plotChoicesLoading.value = false
            },
            onError = { _plotChoicesLoading.value = false }
        )
    }

    /** 解析服务器返回的剧情选项列表。服务器字段：id, text, intent, level, selected。 */
    private fun parsePlotChoices(json: JsonElement?): List<PlotChoice> {
        if (json == null || !json.isJsonObject) return emptyList()
        val arr = json.asJsonObject.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            PlotChoice(
                id = obj.get("id")?.asString ?: obj.get("choice_id")?.asString ?: "",
                title = obj.get("text")?.asString ?: obj.get("title")?.asString ?: "",
                description = obj.get("intent")?.asString ?: obj.get("description")?.asString ?: "",
                selected = obj.get("selected")?.asBoolean == true,
                level = obj.get("level")?.asString ?: "normal"
            )
        }.filter { it.id.isNotBlank() }
    }
}

/** 剧情选项数据类。 */
