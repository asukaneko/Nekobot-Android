package com.nekobot.app.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.AccountTree
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.google.gson.JsonElement
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.BubbleAssistant
import com.nekobot.app.ui.theme.BubbleAssistantLight
import com.nekobot.app.ui.theme.BubbleUser
import com.nekobot.app.ui.theme.BubbleUserLight
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
fun ChatScreen(sessionId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit = {}) {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsState()
    val session by viewModel.session.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val plotChoices by viewModel.plotChoices.collectAsState()
    val plotChoicesLoading by viewModel.plotChoicesLoading.collectAsState()

    var input by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<Message?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showMyMessages by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurface)
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
            // 底部输入栏：左侧 + 按钮展开数据/操作面板，中间输入框，右侧发送
            ChatInputBar(
                input = input,
                onInputChange = { input = it },
                sending = sending,
                messageCount = messages.size,
                plotChoices = plotChoices,
                plotChoicesLoading = plotChoicesLoading,
                onSelectPlotChoice = { choice ->
                    // 填入输入框（用户可编辑后手动发送），后台标记选中
                    input = choice.title
                    viewModel.selectPlotChoice(choice.id)
                },
                onRegeneratePlotChoices = { viewModel.regeneratePlotChoices() },
                onScrollToBottom = {
                    if (messages.isNotEmpty()) {
                        scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                    }
                },
                onShowMyMessages = { showMyMessages = true },
                onSend = {
                    val text = input
                    input = ""
                    keyboard?.hide()
                    viewModel.sendMessage(text)
                },
                onStop = { viewModel.stop() },
                onClear = { showClearConfirm = true },
                onCompress = { viewModel.compressContext() }
            )
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
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("开始与 AI 对话吧", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            portraitUrl = session?.portraitUrl,
                            onLongClick = { deletingMessage = msg },
                            onRegenerate = { viewModel.regenerate() },
                            onFork = { msg.id?.let { mid -> viewModel.forkFromMessage(mid) { onOpenChat(it) } } },
                            onCopy = { msg.displayContent }
                        )
                    }
                    // AI 处理进度卡片已移除：流式占位消息由 StreamStart 事件创建
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

    // 我的消息列表弹窗：点击可跳转到对应气泡
    if (showMyMessages) {
        val myMessages = messages.mapIndexedNotNull { idx, m -> if (m.isUser) idx to m else null }
        NekoDialog(
            onDismiss = { showMyMessages = false },
            title = "我的消息 (${myMessages.size})",
            message = if (myMessages.isEmpty()) "暂无用户消息" else null,
            confirmText = "关闭",
            onConfirm = null
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(myMessages, key = { it.first }) { (idx, msg) ->
                    val preview = msg.displayContent.take(60).replace("\n", " ")
                    val ts = compactTime(msg.timestamp)?.let { "  $it" } ?: ""
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                showMyMessages = false
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                            .padding(10.dp)
                    ) {
                        Text(
                            text = preview + ts,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 单条消息气泡：用户靠右、AI 靠左。支持 <||> 分隔符拆分为多段气泡。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    portraitUrl: String? = null,
    onLongClick: () -> Unit,
    onRegenerate: () -> Unit = {},
    onFork: () -> Unit = {},
    onCopy: () -> String = { "" }
) {
    val isUser = message.isUser
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isUser) (if (isDark) BubbleUser else BubbleUserLight) else (if (isDark) BubbleAssistant else BubbleAssistantLight)
    // 文字颜色：用户气泡（紫色）始终白色；AI 气泡深色模式白色，浅色模式深色
    val textColor = if (isUser) Color.White else (if (isDark) Color.White else MaterialTheme.colorScheme.onSurface)
    val arrangement = if (isUser) Arrangement.End else Arrangement.Start
    val clipboard = LocalClipboardManager.current

    // 按 <||> 拆分内容为多段（保留非空段）
    val isStreamingPlaceholder = message.id == ChatViewModel.STREAMING_ID
    val segments = remember(message.content) {
        message.displayContent
            .split("<||>")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { if (it.isEmpty() && !isStreamingPlaceholder) listOf("(空消息)") else it }
    }
    // 解析每段的多媒体内容段
    val parsedSegments = remember(segments) {
        segments.map { parseContentSegments(it) }
    }
    // 是否包含多媒体内容（图片/视频/音频/txt/html）或音频 URL，决定气泡最大宽度
    val hasMultimedia = parsedSegments.any { segs -> segs.any { it.type != SegmentType.TEXT } }
    val hasAudioUrl = !message.audioUrl.isNullOrBlank()
    val maxBubbleWidth = if (hasMultimedia || hasAudioUrl) 320.dp else 280.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement
    ) {
        // AI 头像（使用角色立绘，回退到图标）
        if (!isUser) {
            val resolved = resolveAvatarUrl(portraitUrl)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (!resolved.isNullOrBlank()) {
                    AsyncImage(
                        model = resolved,
                        contentDescription = "角色立绘",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.widthIn(max = maxBubbleWidth)) {
            // 多段气泡：每段一个气泡，段间小间距
            segments.forEachIndexed { idx, segment ->
                val isFirst = idx == 0
                val isLast = idx == segments.lastIndex
                // 解析多媒体内容段
                val contentSegments = parsedSegments[idx]
                val segHasMultimedia = contentSegments.any { it.type != SegmentType.TEXT }
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else if (isLast) 16.dp else 4.dp,
                                bottomEnd = if (isUser) if (isLast) 4.dp else 16.dp else 16.dp
                            )
                        )
                        .background(bgColor)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = onLongClick
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (segHasMultimedia) {
                        // 多媒体内容：用渲染器渲染，宽度可超出普通文本宽度
                        RenderContentSegments(
                            segments = contentSegments,
                            textColor = textColor,
                            modifier = Modifier.widthIn(max = 320.dp)
                        )
                    } else {
                        Text(
                            text = segment,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (!isLast) Spacer(Modifier.height(10.dp))
            }

            // 如果有音频 URL，追加音频播放器
            if (hasAudioUrl) {
                val resolvedAudioUrl = resolveAvatarUrl(message.audioUrl) ?: message.audioUrl!!
                Spacer(Modifier.height(6.dp))
                AudioRenderer(url = resolvedAudioUrl, modifier = Modifier.widthIn(max = 280.dp))
            }

            // 元信息：时间（精简到分钟）/ token 数
            val compactTs = compactTime(message.timestamp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (compactTs != null) {
                    Text(compactTs, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                message.tokens?.let { tokens ->
                    if (compactTs != null) Spacer(Modifier.width(6.dp))
                    Text("${tokens} tok", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 操作按钮（小且不显眼）：AI 有 重新生成/分支/复制，用户只有 复制
            BubbleActions(
                isUser = isUser,
                onRegenerate = onRegenerate,
                onFork = onFork,
                onCopy = {
                    val text = onCopy()
                    clipboard.setText(AnnotatedString(text))
                }
            )
        }
    }
}

/** 气泡下方的小操作按钮行：低对比度、小图标。 */
@Composable
private fun BubbleActions(
    isUser: Boolean,
    onRegenerate: () -> Unit,
    onFork: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isUser) {
            // 重新生成
            IconActionButton(icon = Icons.Filled.Refresh, description = "重新生成", onClick = onRegenerate)
            Spacer(Modifier.width(4.dp))
            // 分支
            IconActionButton(icon = Icons.Outlined.AccountTree, description = "分支", onClick = onFork)
            Spacer(Modifier.width(4.dp))
        }
        // 复制
        IconActionButton(icon = Icons.Filled.ContentCopy, description = "复制", onClick = onCopy)
    }
}

/** 极小图标按钮：低对比度，不抢视觉。 */
@Composable
private fun IconActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/** AI 思考中状态：骨架占位动画（shimmer），不展示进度卡片。 */
@Composable
private fun ThinkingIndicator(portraitUrl: String? = null) {
    // shimmer 动画 alpha
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // 头像骨架（若已有立绘则直接显示）
        val resolved = resolveAvatarUrl(portraitUrl)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (!resolved.isNullOrBlank()) {
                AsyncImage(
                    model = resolved,
                    contentDescription = "角色立绘",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        // 骨架气泡：两行占位条
        Column(modifier = Modifier.widthIn(max = 220.dp)) {
            // 第一行气泡骨架
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background((if (isSystemInDarkTheme()) BubbleAssistant else BubbleAssistantLight).copy(alpha = alpha))
            )
            Spacer(Modifier.height(8.dp))
            // 第二行气泡骨架（较短）
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background((if (isSystemInDarkTheme()) BubbleAssistant else BubbleAssistantLight).copy(alpha = alpha))
            )
        }
    }
}

/** 剧情选项生成中骨架动画。 */
@Composable
private fun PlotChoicesSkeleton() {
    val transition = rememberInfiniteTransition(label = "plot_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "plot_alpha"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("剧情选项生成中...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                )
            }
        }
    }
}

/**
 * 剧情选项栏：在输入框上方展示最多 3 个剧情选项卡片，可点击选择、重新生成、隐藏/显示。
 */
@Composable
private fun PlotChoicesBar(
    choices: List<PlotChoice>,
    onSelect: (PlotChoice) -> Unit,
    onRegenerate: () -> Unit,
    enabled: Boolean = true,
    collapsed: Boolean = false,
    onToggleCollapse: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("剧情选项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onToggleCollapse,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    if (collapsed) "显示" else "隐藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onRegenerate,
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("换一组", style = MaterialTheme.typography.labelSmall, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 折叠时不显示选项卡片
        if (!collapsed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                choices.forEach { choice ->
                    // 根据等级选择强调色
                    val levelColor = when (choice.level) {
                        "turning_point" -> Color(0xFFFF6B6B)
                        "important" -> Color(0xFFFFB347)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    GlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onSelect(choice) },
                        cornerRadius = 12,
                        containerColor = if (choice.selected) levelColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(levelColor)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = when (choice.level) {
                                    "turning_point" -> "转折"
                                    "important" -> "重要"
                                    else -> "普通"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = levelColor,
                                fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = choice.title.ifBlank { "选项" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把时间戳/时间字符串精简到「分钟」级，尽量短以节省气泡下方空间。
 * 支持毫秒时间戳、ISO 字符串、已格式化字符串三种输入。
 * 例：2026-07-10T14:30:45.123 → "14:30"；2026-07-10 14:30 → "14:30"；14:30:45 → "14:30"
 */
private fun compactTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    return try {
        when {
            // 纯数字时间戳（毫秒）
            s.matches(Regex("^\\d{10,13}$")) -> {
                val ms = if (s.length == 10) s.toLong() * 1000 else s.toLong()
                val instant = java.util.Date(ms)
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(instant)
            }
            // ISO 或带 T 的时间：提取 HH:mm
            s.contains('T') -> {
                val timePart = s.substringAfter('T').take(5)
                if (timePart.matches(Regex("\\d{2}:\\d{2}"))) timePart else null
            }
            // 含空格分隔日期时间：取时间部分前 5 位
            s.contains(' ') -> {
                val timePart = s.substringAfter(' ').take(5)
                if (timePart.matches(Regex("\\d{2}:\\d{2}"))) timePart else null
            }
            // 仅时间 HH:mm:ss 或 HH:mm
            s.matches(Regex("\\d{2}:\\d{2}(:\\d{2})?")) -> s.take(5)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 底部输入栏：模仿 webui，左侧 + 按钮点击展开上下文数据与操作按钮面板。
 * - 输入框居中，支持多行
 * - 右侧发送/停止按钮
 * - 展开面板含消息数、字符数、估算 token，以及 滚动到底部 / 我的信息 / 清空 / 压缩 操作
 */
@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    messageCount: Int,
    plotChoices: List<PlotChoice> = emptyList(),
    plotChoicesLoading: Boolean = false,
    onSelectPlotChoice: (PlotChoice) -> Unit = {},
    onRegeneratePlotChoices: () -> Unit = {},
    onScrollToBottom: () -> Unit = {},
    onShowMyMessages: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onCompress: () -> Unit
) {
    var panelExpanded by remember { mutableStateOf(false) }
    var plotChoicesCollapsed by remember { mutableStateOf(false) }
    // 字符数与 token 估算：中文字符约 1 token/字，英文约 0.25 token/字符
    val charCount = input.length
    val chineseCount = input.count { it.code in 0x4E00..0x9FFF }
    val otherCount = charCount - chineseCount
    val tokenEstimate = (chineseCount + otherCount / 4).coerceAtLeast(if (charCount > 0) 1 else 0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 剧情模式选项（输入框上方，最多展示 3 个）
        if (plotChoicesLoading) {
            // 剧情选项生成中：显示骨架动画
            PlotChoicesSkeleton()
        } else if (plotChoices.isNotEmpty()) {
            PlotChoicesBar(
                choices = plotChoices.take(3),
                onSelect = onSelectPlotChoice,
                onRegenerate = onRegeneratePlotChoices,
                enabled = !sending,
                collapsed = plotChoicesCollapsed,
                onToggleCollapse = { plotChoicesCollapsed = !plotChoicesCollapsed }
            )
        }
        // 展开面板（向上展开）
        AnimatedVisibility(visible = panelExpanded) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                cornerRadius = 14
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 上下文数据
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("上下文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("$messageCount 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    // 操作按钮网格（每行 2 个，按钮更大）
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.KeyboardDoubleArrowDown,
                            label = "滚到底部",
                            enabled = messageCount > 0,
                            onClick = onScrollToBottom,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            icon = Icons.Outlined.AccountTree,
                            label = "我的消息",
                            enabled = messageCount > 0,
                            onClick = onShowMyMessages,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.Compress,
                            label = "压缩上下文",
                            enabled = !sending,
                            onClick = onCompress,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            icon = Icons.Filled.CleaningServices,
                            label = "清空消息",
                            enabled = !sending,
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 字符数与 token 估算（输入框上方，始终可见）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                "$charCount 字 / ~$tokenEstimate tok",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 输入行：+ 按钮 + 输入框 + 发送/停止（三者同高 48dp）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 + 按钮：展开/收起面板
            IconButton(
                onClick = { panelExpanded = !panelExpanded },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (panelExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    if (panelExpanded) Icons.Filled.MoreVert else Icons.Filled.Add,
                    contentDescription = "更多操作",
                    tint = if (panelExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text(if (sending) "AI 思考中..." else "输入消息...") },
                enabled = !sending,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = if (sending) onStop else onSend,
                enabled = sending || input.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (sending) androidx.compose.ui.graphics.Color(0xFFFF6B6B) else MaterialTheme.colorScheme.primary)
            ) {
                if (sending) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止", tint = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** 操作胶囊按钮：图标 + 文案（2 列布局用，按钮更大）。 */
@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 对话页 ViewModel：管理消息、会话信息与发送状态。
 *
 * 服务器模式：通过 Socket.IO 接收 AI 的流式回复与消息推送。
 * 本地模式：通过 [UnifiedRepository.chatStream] 返回的 Flow 接收流式分片，不走 Socket。
 */
class ChatViewModel : BaseViewModel() {

    companion object {
        /** 流式消息在列表中的临时 id（供 MessageBubble 识别流式占位） */
        const val STREAMING_ID = "_streaming_"
    }

    private val socket = ServiceContainer.socket

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** 剧情选项列表（plot_mode 开启时从服务器获取） */
    private val _plotChoices = MutableStateFlow<List<PlotChoice>>(emptyList())
    val plotChoices: StateFlow<List<PlotChoice>> = _plotChoices.asStateFlow()

    /** 剧情选项是否正在生成中（用于骨架动画） */
    private val _plotChoicesLoading = MutableStateFlow(false)
    val plotChoicesLoading: StateFlow<Boolean> = _plotChoicesLoading.asStateFlow()

    private var currentSessionId: String = ""

    /** 流式生成中的临时消息内容累加器 */
    private val streamingContent = StringBuilder()
    /** 流式消息在列表中的临时 id */
    private val streamingId = STREAMING_ID

    /** 收集 Socket.IO 事件的 Job（服务器模式） */
    private var eventsJob: kotlinx.coroutines.Job? = null
    /** 本地模式流式聊天收集 Job */
    private var localChatJob: kotlinx.coroutines.Job? = null

    /** 初始化：加载会话信息与消息列表；服务器模式额外连接 Socket.IO。 */
    fun init(sessionId: String) {
        if (sessionId == currentSessionId && _session.value != null) return
        currentSessionId = sessionId
        loadSession(sessionId)
        loadMessages()
        if (!isLocalMode) {
            connectSocket(sessionId)
        }
        // 剧情选项仅服务器模式支持
        if (!isLocalMode) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(800)
                if (_session.value?.plotMode == true) loadPlotChoices()
            }
        }
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
                // 如果剧情模式开启，显示骨架并加载新剧情选项
                if (_session.value?.plotMode == true) {
                    _plotChoices.value = emptyList()
                    _plotChoicesLoading.value = true
                    // 延迟 1 秒后通过 HTTP 加载（兜底：若 plot_choices socket 事件先到则覆盖）
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1000)
                        if (_plotChoicesLoading.value) loadPlotChoices()
                    }
                }
            }
            is RealtimeEvent.PlotChoices -> {
                // 服务端推送新剧情选项，直接解析更新
                _plotChoices.value = parsePlotChoices(event.choices)
                _plotChoicesLoading.value = false
            }
            is RealtimeEvent.AiResponse -> {
                _sending.value = false
                event.message?.let { msg ->
                    // 移除流式占位，追加完整回复
                    _messages.value = _messages.value
                        .filter { it.id != streamingId }
                        .let { if (msg.content.isNullOrBlank()) it else it + msg }
                } ?: loadMessages()
                // 非流式回复也需刷新剧情选项
                if (_session.value?.plotMode == true) {
                    _plotChoices.value = emptyList()
                    _plotChoicesLoading.value = true
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1000)
                        if (_plotChoicesLoading.value) loadPlotChoices()
                    }
                }
            }
            is RealtimeEvent.NewMessage -> {
                val msg = event.message
                // 过滤进度卡片（thinking_card），不展示在聊天列表
                if (msg.isThinkingCard) return
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
            is RealtimeEvent.Usage -> {
                // 本地模式 token 用量已由 LocalRepository 保存到消息，UI 无需额外处理
            }
        }
    }

    /** 加载会话信息。 */
    private fun loadSession(sessionId: String) {
        launchResult(
            block = { unified.getSession(sessionId) },
            onSuccess = { _session.value = it }
        )
    }

    /** 加载消息列表。 */
    fun loadMessages() {
        if (currentSessionId.isBlank()) return
        launchResult(
            block = { unified.listMessages(currentSessionId) },
            onSuccess = { _messages.value = (it ?: emptyList()).filterNot { msg -> msg.isThinkingCard } }
        )
    }

    /**
     * 发送消息：
     * - 本地模式：调用 [UnifiedRepository.chatStream] 返回的 Flow，直接收集事件
     * - 服务器模式：优先通过 Socket.IO send_message 触发 AI（服务端会推送流式回复），
     *   Socket 未连接时回退到 HTTP /chat
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

        if (isLocalMode) {
            // 本地模式：直接收集 Flow 事件
            val flow = unified.chatStream(currentSessionId, content)
            if (flow == null) {
                _sending.value = false
                showError("未配置 AI 模型，请在设置中添加")
                return
            }
            localChatJob?.cancel()
            localChatJob = viewModelScope.launch {
                flow.collect { event -> handleRealtimeEvent(event) }
            }
        } else if (socket.state.value == SocketState.Connected) {
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
            block = { unified.chat(currentSessionId, content) },
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

    /** 重新生成最后一条 AI 回复：先隐藏旧 AI 消息，再请求重新生成。 */
    fun regenerate() {
        if (_sending.value || currentSessionId.isBlank()) return
        // 找到最后一条 assistant 消息的 id 传给服务器
        val lastAssistant = _messages.value.lastOrNull { !it.isUser }
        val messageId = lastAssistant?.id
        if (messageId.isNullOrBlank()) {
            showError("未找到可重新生成的 AI 消息")
            return
        }
        // 先从列表中移除旧的 AI 回复（含其后的所有消息）
        val removeIndex = _messages.value.indexOfLast { it.id == messageId }
        if (removeIndex >= 0) {
            _messages.value = _messages.value.subList(0, removeIndex)
        }
        _sending.value = true
        // 如果剧情模式开启，清除旧选项并显示骨架（仅服务器模式）
        if (!isLocalMode && _session.value?.plotMode == true) {
            _plotChoices.value = emptyList()
            _plotChoicesLoading.value = true
        }
        if (isLocalMode) {
            // 本地模式：直接收集 Flow 事件
            val flow = unified.regenerateStream(currentSessionId, messageId)
            if (flow == null) {
                _sending.value = false
                showError("未配置 AI 模型，请在设置中添加")
                return
            }
            localChatJob?.cancel()
            localChatJob = viewModelScope.launch {
                flow.collect { event -> handleRealtimeEvent(event) }
            }
        } else {
            launchResult(
                block = { unified.regenerate(currentSessionId, messageId) },
                onSuccess = {
                    // 等待 socket 推送流式，或延迟刷新
                    viewModelScope.launch {
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

    /** 停止生成。 */
    fun stop() {
        if (currentSessionId.isBlank()) return
        if (isLocalMode) {
            localChatJob?.cancel()
            localChatJob = null
            _sending.value = false
            showToast("已停止")
            loadMessages()
        } else {
            launchResult(
                block = { unified.stopGeneration(currentSessionId) },
                onSuccess = {
                    _sending.value = false
                    showToast("已请求停止")
                    loadMessages()
                }
            )
        }
    }

    /** 压缩上下文：将早期消息摘要化以节省 token。 */
    fun compressContext() {
        if (currentSessionId.isBlank()) return
        launchResult(
            block = { unified.compressContext(currentSessionId) },
            onSuccess = {
                showToast("上下文已压缩")
                loadMessages()
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
                    showToast("已从该消息处分叉")
                    onSuccess(newId)
                } else {
                    showToast("分叉成功，但未返回新会话 ID")
                }
            }
        )
    }

    /** 删除单条消息，成功后回调 [onSuccess]。 */
    fun deleteMessage(sessionId: String, messageId: String, onSuccess: () -> Unit = {}) {
        launchResult(
            block = { unified.deleteMessage(sessionId, messageId) },
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
            block = { unified.clearMessages(sessionId) },
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
        localChatJob?.cancel()
        if (!isLocalMode && currentSessionId.isNotBlank()) {
            socket.leaveSession(currentSessionId)
        }
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

    /** 选择一个剧情选项：后台标记选中（fire-and-forget），不清除选项列表。 */
    fun selectPlotChoice(choiceId: String) {
        if (isLocalMode || currentSessionId.isBlank()) return
        // 后台标记选中，不阻塞用户操作
        viewModelScope.launch {
            try { repo.selectPlotChoice(currentSessionId, choiceId) } catch (_: Exception) {}
        }
        // 标记已选中状态（UI 可高亮），不清除列表
        _plotChoices.value = _plotChoices.value.map { if (it.id == choiceId) it.copy(selected = true) else it }
    }

    /** 重新生成剧情选项。 */
    fun regeneratePlotChoices() {
        if (isLocalMode || currentSessionId.isBlank()) return
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
data class PlotChoice(
    val id: String,
    val title: String,
    val description: String,
    val selected: Boolean = false,
    val level: String = "normal"
)
