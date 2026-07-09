package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.remote.RealtimeEvent
import com.nekobot.app.data.remote.SocketState
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BubbleAssistant
import com.nekobot.app.ui.theme.BubbleUser
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对话页：展示会话消息列表与输入栏，支持发送、重新生成、停止、清空、删除消息。
 * 进入页面自动加载 [sessionId] 的消息与会话信息。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(sessionId: String, onBack: () -> Unit) {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsState()
    val session by viewModel.session.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    var input by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<Message?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // 进入页面加载
    LaunchedEffect(sessionId) {
        viewModel.init(sessionId)
    }
    // 新消息时滚到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = session?.displayName ?: "对话",
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnSurface)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = OnSurface)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("重新生成") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.regenerate()
                                },
                                enabled = !sending
                            )
                            DropdownMenuItem(
                                text = { Text("停止生成") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.stop()
                                },
                                enabled = sending
                            )
                            DropdownMenuItem(
                                text = { Text("清空消息", color = Color(0xFFFF6B6B)) },
                                onClick = {
                                    menuExpanded = false
                                    showClearConfirm = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // 底部输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 140.dp),
                    placeholder = { Text(if (sending) "AI 思考中..." else "输入消息...") },
                    enabled = !sending,
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = input
                        input = ""
                        keyboard?.hide()
                        viewModel.sendMessage(text)
                    },
                    enabled = !sending && input.isNotBlank()
                ) {
                    if (sending) {
                        CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Primary)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty() && loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("开始与 AI 对话吧", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id ?: (it.content + it.timestamp + it.hashCode()) }) { msg ->
                        MessageBubble(
                            message = msg,
                            onLongClick = { deletingMessage = msg }
                        )
                    }
                    if (sending) {
                        item(key = "_thinking_indicator") {
                            ThinkingIndicator()
                        }
                    }
                }
            }

            val errorMsg = error
            if (!errorMsg.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    ErrorBanner(
                        message = errorMsg,
                        onRetry = { viewModel.clearError() }
                    )
                }
            }
        }
    }

    // 删除消息确认
    deletingMessage?.let { msg ->
        NekoDialog(
            onDismiss = { deletingMessage = null },
            title = "删除消息",
            message = "确定删除这条消息吗？",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteMessage(sessionId, msg.id.orEmpty()) { deletingMessage = null }
            }
        )
    }

    // 清空消息确认
    if (showClearConfirm) {
        NekoDialog(
            onDismiss = { showClearConfirm = false },
            title = "清空消息",
            message = "将删除本会话所有消息，此操作不可撤销。",
            confirmText = "清空",
            onConfirm = {
                viewModel.clearMessages(sessionId) { showClearConfirm = false }
            }
        )
    }
}

/** 单条消息气泡：用户靠右、AI 靠左。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: Message, onLongClick: () -> Unit) {
    val isUser = message.isUser
    val bgColor = if (isUser) BubbleUser else BubbleAssistant
    val arrangement = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement
    ) {
        // AI 头像
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(BgSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(bgColor)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongClick
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = message.displayContent.ifBlank { "(空消息)" },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            // 元信息：时间 / token 数
            Row(verticalAlignment = Alignment.CenterVertically) {
                message.timestamp?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariant
                    )
                }
                message.tokens?.let { tokens ->
                    if (message.timestamp != null) Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${tokens} tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceVariant
                    )
                }
            }
        }
    }
}

/** AI 思考中状态指示。 */
@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(BgSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        GlassCard(cornerRadius = 16, modifier = Modifier.widthIn(max = 200.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI 思考中...", color = OnSurface, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 对话页 ViewModel：管理消息、会话信息与发送状态。
 * 通过 Socket.IO 接收 AI 的流式回复与消息推送。
 */
class ChatViewModel : BaseViewModel() {

    private val socket = ServiceContainer.socket

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var currentSessionId: String = ""

    /** 流式生成中的临时消息内容累加器 */
    private val streamingContent = StringBuilder()
    /** 流式消息在列表中的临时 id */
    private val streamingId = "_streaming_"

    /** 收集 Socket.IO 事件的 Job */
    private var eventsJob: kotlinx.coroutines.Job? = null
    /** 初始化：连接 Socket.IO、加入会话 room、加载会话信息与消息列表。 */
    fun init(sessionId: String) {
        if (sessionId == currentSessionId && _session.value != null) return
        currentSessionId = sessionId
        loadSession(sessionId)
        loadMessages()
        connectSocket(sessionId)
    }

    /** 连接 Socket.IO 并加入会话 room，监听实时事件。 */
    private fun connectSocket(sessionId: String) {
        eventsJob?.cancel()
        socket.connect()
        socket.joinSession(sessionId)
        eventsJob = viewModelScope.launch {
            socket.events.collect { event -> handleRealtimeEvent(event) }
        }
    }

    /** 处理 Socket.IO 推送的实时事件。 */
    private fun handleRealtimeEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.StreamStart -> {
                _sending.value = true
                streamingContent.setLength(0)
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
                // 更新占位消息内容
                _messages.value = _messages.value.map {
                    if (it.id == streamingId) it.copy(content = streamingContent.toString())
                    else it
                }
            }
            is RealtimeEvent.StreamEnd -> {
                _sending.value = false
                // 流式结束，刷新列表获取服务端持久化的真实消息
                loadMessages()
            }
            is RealtimeEvent.AiResponse -> {
                _sending.value = false
                event.message?.let { msg ->
                    // 移除流式占位，追加完整回复
                    _messages.value = _messages.value
                        .filter { it.id != streamingId }
                        .let { if (msg.content.isNullOrBlank()) it else it + msg }
                } ?: loadMessages()
            }
            is RealtimeEvent.NewMessage -> {
                val msg = event.message
                // 只处理 assistant 消息（用户消息已乐观更新）
                if (!msg.isUser) {
                    _sending.value = false
                    // 移除流式占位，追加新消息（去重）
                    _messages.value = (_messages.value.filter {
                        it.id != streamingId && it.id != msg.id
                    }) + msg
                }
            }
            is RealtimeEvent.Filtered -> {
                _sending.value = false
                showToast(event.message ?: "消息被过滤")
            }
            is RealtimeEvent.Error -> {
                _sending.value = false
                showError(event.message)
            }
        }
    }

    /** 加载会话信息。 */
    private fun loadSession(sessionId: String) {
        launchResult(
            block = { repo.getSession(sessionId) },
            onSuccess = { _session.value = it }
        )
    }

    /** 加载消息列表。 */
    fun loadMessages() {
        if (currentSessionId.isBlank()) return
        launchResult(
            block = { repo.listMessages(currentSessionId) },
            onSuccess = { _messages.value = it ?: emptyList() }
        )
    }

    /**
     * 发送消息：
     * 1. 乐观更新，立即把用户消息加入列表
     * 2. 优先通过 Socket.IO send_message 触发 AI（服务端会推送流式回复）
     * 3. Socket 未连接时回退到 HTTP /chat
     */
    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isBlank() || _sending.value || currentSessionId.isBlank()) return
        // 乐观更新
        val optimistic = Message(
            role = "user",
            content = content,
            timestamp = System.currentTimeMillis().toString()
        )
        _messages.value = _messages.value + optimistic
        _sending.value = true
        clearError()

        if (socket.state.value == SocketState.Connected) {
            // Socket.IO 路径：触发 send_message，等待流式推送
            socket.sendMessage(currentSessionId, content)
            // 兜底：若 60 秒仍无 StreamStart 回调，回退 HTTP
            viewModelScope.launch {
                kotlinx.coroutines.delay(60000)
                if (_sending.value && _messages.value.none { it.id == streamingId }) {
                    launchHttpChat(content)
                }
            }
        } else {
            // Socket 未连接，回退 HTTP
            launchHttpChat(content)
        }
    }

    /** HTTP /chat 回退路径：触发后等待 socket 推送或轮询。 */
    private fun launchHttpChat(content: String) {
        launchResult(
            block = { repo.chat(currentSessionId, content) },
            onSuccess = {
                _sending.value = false
                // HTTP 成功后稍等再刷新，给 AI 生成时间
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1500)
                    loadMessages()
                    // 再次延迟刷新确保拉到回复
                    kotlinx.coroutines.delay(2000)
                    loadMessages()
                }
            },
            onError = {
                _sending.value = false
                showError(it)
            }
        )
    }

    /** 重新生成最后一条 AI 回复。 */
    fun regenerate() {
        if (_sending.value || currentSessionId.isBlank()) return
        _sending.value = true
        launchResult(
            block = { repo.regenerate(currentSessionId) },
            onSuccess = {
                // 等待 socket 推送流式，或延迟刷新
                viewModelScope.launch {
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

    /** 停止生成。 */
    fun stop() {
        if (currentSessionId.isBlank()) return
        launchResult(
            block = { repo.stopGeneration(currentSessionId) },
            onSuccess = {
                _sending.value = false
                showToast("已请求停止")
                loadMessages()
            }
        )
    }

    /** 删除单条消息，成功后回调 [onSuccess]。 */
    fun deleteMessage(sessionId: String, messageId: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { repo.deleteMessage(sessionId, messageId) },
            onSuccess = {
                showToast("已删除")
                loadMessages()
                onSuccess()
            }
        )
    }

    /** 清空会话所有消息，成功后回调 [onSuccess]。 */
    fun clearMessages(sessionId: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { repo.clearMessages(sessionId) },
            onSuccess = {
                showToast("已清空消息")
                _messages.value = emptyList()
                onSuccess()
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        eventsJob?.cancel()
        if (currentSessionId.isNotBlank()) {
            socket.leaveSession(currentSessionId)
        }
    }
}
