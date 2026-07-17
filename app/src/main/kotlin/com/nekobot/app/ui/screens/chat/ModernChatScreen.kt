package com.nekobot.app.ui.screens.chat

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ChatInputLayoutMode
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 现代聊天容器。
 *
 * 消息区域、顶部栏、流式回复和多媒体渲染继续复用 [ChatScreen]；这里只在其上方覆盖
 * 新的剧情选项栏、操作面板和一体式输入胶囊。两层通过同一 NavBackStackEntry 获取同一个
 * [ChatViewModel]，因此不会复制会话状态或网络请求。
 */
@Composable
fun ModernChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onOpenSessionDetail: (String) -> Unit = {},
    onOpenWorkspace: (String) -> Unit = {},
    onOpenStoryGraph: (String) -> Unit = {},
    onJumpToLatest: () -> Unit = {}
) {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val plotChoices by viewModel.plotChoices.collectAsState()
    val plotChoicesLoading by viewModel.plotChoicesLoading.collectAsState()
    val listState = rememberLazyListState()
    val composerScope = rememberCoroutineScope()

    ChatScreen(
    sessionId = sessionId,
    onBack = onBack,
    onOpenChat = onOpenChat,
    onOpenSessionDetail = onOpenSessionDetail,
    onOpenWorkspace = onOpenWorkspace,
    onOpenStoryGraph = onOpenStoryGraph,
    externalListState = listState,
    customBottomBar = {
        ModernChatComposer(
            modifier = Modifier.fillMaxWidth(),
            sessionId = sessionId,
            messages = messages,
            sending = sending,
            plotChoices = plotChoices,
            plotChoicesLoading = plotChoicesLoading,
            onSend = { text, plotChoiceId -> viewModel.sendMessage(text, plotChoiceId) },
            onStop = viewModel::stop,
            onCompress = viewModel::compressContext,
            onClear = { viewModel.clearMessages(sessionId) },
            onRegeneratePlotChoices = viewModel::regeneratePlotChoices,
            onOpenWorkspace = { onOpenWorkspace(sessionId) },
            onJumpToLatest = onJumpToLatest,
            onJumpToMessage = { msg ->
                val idx = messages.indexOfFirst { it.id == msg.id }
                if (idx >= 0) composerScope.launch { listState.animateScrollToItem(idx + 1) }
            }
        )
    }
)
}

private enum class ModernComposerAction {
    VOICE,
    SEND,
    STOP
}

@Composable
private fun ModernChatComposer(
    modifier: Modifier,
    sessionId: String,
    messages: List<Message>,
    sending: Boolean,
    plotChoices: List<PlotChoice>,
    plotChoicesLoading: Boolean,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onCompress: () -> Unit,
    onClear: () -> Unit,
    onRegeneratePlotChoices: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onJumpToLatest: () -> Unit,
    onJumpToMessage: (Message) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var panelExpanded by remember { mutableStateOf(false) }
    var inputExpanded by remember { mutableStateOf(false) }
    var chatInputLayout by remember {
        mutableStateOf(ServiceContainer.prefs.chatInputLayoutMode)
    }
    var pendingPlotChoiceId by remember { mutableStateOf<String?>(null) }
    var filePickMode by remember { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showMyMessages by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFavorites by remember { mutableStateOf(false) }
    var favoritesLoading by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<JsonObject>>(emptyList()) }

    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var voiceTranscribing by remember { mutableStateOf(false) }
    var recorderRef by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var audioFileRef by remember { mutableStateOf<java.io.File?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun startVoiceRecording() {
        try {
            val file = java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }
            recorder.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorderRef = recorder
            audioFileRef = file
            recordingDuration = 0
            isRecording = true
        } catch (e: Exception) {
            toast("录音启动失败：${e.message ?: "未知错误"}")
        }
    }

    fun cancelRecording() {
        val recorder = recorderRef
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorderRef = null
        audioFileRef?.delete()
        audioFileRef = null
        isRecording = false
        recordingDuration = 0
    }

    fun stopAndTranscribe() {
        val recorder = recorderRef ?: return
        val file = audioFileRef
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        recorderRef = null
        isRecording = false
        if (file == null || !file.exists()) return

        voiceTranscribing = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                when (val result = ServiceContainer.unified.transcribeAudio(bytes, file.name, "zh")) {
                    is Resource.Success -> {
                        val text = result.data?.text.orEmpty().trim()
                        if (text.isNotEmpty()) {
                            input = if (input.isBlank()) text else "$input $text"
                        } else {
                            toast("语音识别未返回文字")
                        }
                    }
                    is Resource.Error -> toast("识别失败：${result.message}")
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                toast("识别失败：${e.message ?: "未知错误"}")
            } finally {
                voiceTranscribing = false
                file.delete()
                audioFileRef = null
            }
        }
    }

    val requestMicPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceRecording() else toast("需要录音权限才能使用语音输入")
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val mode = filePickMode
        filePickMode = null
        if (uri == null || mode == null) return@rememberLauncherForActivityResult

        fileBusy = true
        scope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    modernReadUriFile(context, uri) ?: error("读取文件失败")
                }
                val body = bytes.toRequestBody(modernGuessMime(name).toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", name, body)
                when (val result = ServiceContainer.unified.uploadWorkspaceFile(sessionId, part)) {
                    is Resource.Success -> {
                        if (mode == "send") {
                            input = buildString {
                                if (input.isNotBlank()) append(input).append('\n')
                                append("[已上传文件: ").append(name).append(']')
                            }
                            toast("文件已上传，引用已插入输入框")
                        } else {
                            toast("已上传到工作区：$name")
                        }
                    }
                    is Resource.Error -> toast("上传失败：${result.message}")
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                toast("操作失败：${e.message ?: "未知错误"}")
            } finally {
                fileBusy = false
            }
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordingDuration++
        }
    }

    LaunchedEffect(showFavorites) {
        if (!showFavorites) return@LaunchedEffect
        favoritesLoading = true
        favorites = when (val result = ServiceContainer.unified.listMessageFavorites(sessionId)) {
            is Resource.Success -> {
                val obj = result.data?.takeIf { it.isJsonObject }?.asJsonObject
                val array = obj?.getAsJsonArray("collections") ?: obj?.getAsJsonArray("favorites")
                array?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }.orEmpty()
            }
            is Resource.Error -> {
                toast("加载收藏夹失败：${result.message}")
                emptyList()
            }
            is Resource.Loading -> emptyList()
        }
        favoritesLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorderRef?.release() }
            audioFileRef?.delete()
        }
    }

    val closePanel = { panelExpanded = false }
    val messageCount = messages.count { !it.isThinkingCard }
    val charCount = input.length
    val tokenEstimate = estimateModernChatDraftTokens(input)
    val hasPlotSurface = plotChoicesLoading || plotChoices.isNotEmpty()
    val inputVisible = shouldShowChatInput(
        layoutMode = chatInputLayout,
        inputExpanded = inputExpanded,
        hasPlotSurface = hasPlotSurface,
        hasDraft = input.isNotBlank()
    )
    val toggleInput = {
        if (input.isBlank()) {
            inputExpanded = !inputVisible
            if (inputVisible) keyboard?.hide()
        }
    }
    val togglePanel = {
        panelExpanded = !panelExpanded
        if (panelExpanded) keyboard?.hide()
    }
    val toggleLayout = {
        chatInputLayout = if (chatInputLayout == ChatInputLayoutMode.MERGED) {
            ChatInputLayoutMode.SEPARATE
        } else {
            ChatInputLayoutMode.MERGED
        }
        ServiceContainer.prefs.chatInputLayoutMode = chatInputLayout
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (plotChoicesLoading || plotChoices.isNotEmpty()) {
                ModernPlotChoices(
                    loading = plotChoicesLoading,
                    choices = plotChoices,
                    selectedId = pendingPlotChoiceId,
                    enabled = !sending,
                    layoutMode = chatInputLayout,
                    inputVisible = inputVisible,
                    panelExpanded = panelExpanded,
                    sending = sending,
                    onSelect = { choice ->
                        pendingPlotChoiceId = choice.id
                        input = choice.title
                        inputExpanded = true
                        closePanel()
                    },
                    onToggleInput = toggleInput,
                    onTogglePanel = togglePanel,
                    onToggleLayout = toggleLayout,
                    onRegenerate = {
                        pendingPlotChoiceId = null
                        onRegeneratePlotChoices()
                    },
                    onStop = onStop
                )
            }

            AnimatedVisibility(
                visible = panelExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ModernChatActionPanel(
                    messageCount = messageCount,
                    charCount = charCount,
                    tokenEstimate = tokenEstimate,
                    sending = sending,
                    fileBusy = fileBusy,
                    onCompress = { onCompress() },
                    onSendFile = {
                        filePickMode = "send"
                        pickFile.launch("*/*")
                    },
                    onOpenWorkspace = { onOpenWorkspace() },
                    onSearch = { showSearch = true },
                    onFavorites = { showFavorites = true },
                    onJumpToLatest = { onJumpToLatest() },
                    onMyMessages = { showMyMessages = true },
                    onUploadOnly = {
                        filePickMode = "upload"
                        pickFile.launch("*/*")
                    },
                    onClear = { showClearConfirm = true }
                )
            }

            AnimatedVisibility(
                visible = inputVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val composerContainerColor = MaterialTheme.colorScheme.surfaceVariant

                    // 草稿统计：胶囊样式，右对齐悬浮于输入框上方
                    if (input.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 20.dp, top = 4.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = "$charCount 字 / ~$tokenEstimate tok",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(30.dp),
                        color = composerContainerColor,
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                        ),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 面板开关按钮：展开/收起时背景与图标颜色平滑过渡
                            val panelBtnBg by animateColorAsState(
                                targetValue = if (panelExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                label = "panel_btn_bg"
                            )
                            val panelBtnTint by animateColorAsState(
                                targetValue = if (panelExpanded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "panel_btn_tint"
                            )
                            IconButton(
                                onClick = togglePanel,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(panelBtnBg)
                            ) {
                                Crossfade(targetState = panelExpanded, label = "panel_icon") { expanded ->
                                    Icon(
                                        imageVector = if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                                        contentDescription = if (expanded) "关闭操作菜单" else "更多操作",
                                        tint = panelBtnTint,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp, max = 128.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(composerContainerColor),
                                contentAlignment = Alignment.TopStart
                            ) {
                                BasicTextField(
                                    value = input,
                                    onValueChange = {
                                        if (panelExpanded) closePanel()
                                        input = it
                                        if (pendingPlotChoiceId != null && it != plotChoices.firstOrNull { c -> c.id == pendingPlotChoiceId }?.title) {
                                            pendingPlotChoiceId = null
                                        }
                                    },
                                    enabled = !sending,
                                    maxLines = 5,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.TopStart
                                        ) {
                                            if (input.isEmpty()) {
                                                Text(
                                                    text = if (sending) "AI 思考中..." else "输入消息...",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                            }

                            // 主操作按钮：背景和图标同步过渡，避免语音/发送/停止状态生硬跳变
                            val action = when {
                                sending -> ModernComposerAction.STOP
                                input.isNotBlank() -> ModernComposerAction.SEND
                                else -> ModernComposerAction.VOICE
                            }
                            val idleActionColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                            val actionStartColor by animateColorAsState(
                                targetValue = when (action) {
                                    ModernComposerAction.VOICE -> idleActionColor
                                    ModernComposerAction.SEND -> MaterialTheme.colorScheme.primary
                                    ModernComposerAction.STOP -> Color(0xFFFF6B6B)
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "composer_action_start"
                            )
                            val actionEndColor by animateColorAsState(
                                targetValue = when (action) {
                                    ModernComposerAction.VOICE -> idleActionColor
                                    ModernComposerAction.SEND -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                                    ModernComposerAction.STOP -> Color(0xFFFF6B6B)
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "composer_action_end"
                            )
                            IconButton(
                                onClick = {
                                    when (action) {
                                        ModernComposerAction.STOP -> onStop()
                                        ModernComposerAction.SEND -> {
                                            val text = input
                                            val choiceId = pendingPlotChoiceId
                                            input = ""
                                            inputExpanded = false
                                            pendingPlotChoiceId = null
                                            closePanel()
                                            keyboard?.hide()
                                            onSend(text, choiceId)
                                        }
                                        ModernComposerAction.VOICE -> requestMicPermission.launch(
                                            android.Manifest.permission.RECORD_AUDIO
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(actionStartColor, actionEndColor)
                                        )
                                    )
                            ) {
                                Crossfade(
                                    targetState = action,
                                    animationSpec = tween(durationMillis = 180),
                                    label = "composer_action_icon"
                                ) { currentAction ->
                                    when (currentAction) {
                                        ModernComposerAction.STOP -> Icon(
                                            Icons.Filled.Stop,
                                            contentDescription = "停止生成",
                                            tint = Color.White,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        ModernComposerAction.SEND -> Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "发送",
                                            tint = Color.White,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        ModernComposerAction.VOICE -> Icon(
                                            Icons.Filled.Mic,
                                            contentDescription = "语音输入",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空当前会话") },
            text = { Text("将删除当前会话的全部消息，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClear()
                    }
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showMyMessages) {
        ModernMessageListDialog(
            title = "我的消息",
            messages = messages.filter { it.isUser },
            emptyText = "当前会话还没有用户消息",
            onJump = { msg ->
                showMyMessages = false
                onJumpToMessage(msg)
            },
            onDismiss = { showMyMessages = false }
        )
    }

    if (showSearch) {
        ModernSearchDialog(
            messages = messages,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showSearch = false }
        )
    }

    if (showFavorites) {
        ModernFavoritesDialog(
            favorites = favorites,
            loading = favoritesLoading,
            onDismiss = { showFavorites = false }
        )
    }

    if (isRecording) {
        // 录音脉冲动画：外圈光晕随节奏缩放、渐隐
        val pulse = rememberInfiniteTransition(label = "mic_pulse")
        val haloScale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "mic_halo_scale"
        )
        val haloAlpha by pulse.animateFloat(
            initialValue = 0.30f,
            targetValue = 0.06f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "mic_halo_alpha"
        )
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在录音") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .scale(haloScale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = haloAlpha))
                        )
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        modernFormatDuration(recordingDuration),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { stopAndTranscribe() }) { Text("结束并识别") }
            },
            dismissButton = {
                TextButton(onClick = { cancelRecording() }) { Text("取消") }
            }
        )
    }

    if (voiceTranscribing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("语音识别中") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ModernPlotChoices(
    loading: Boolean,
    choices: List<PlotChoice>,
    selectedId: String?,
    enabled: Boolean,
    layoutMode: ChatInputLayoutMode,
    inputVisible: Boolean,
    panelExpanded: Boolean,
    sending: Boolean,
    onSelect: (PlotChoice) -> Unit,
    onToggleInput: () -> Unit,
    onTogglePanel: () -> Unit,
    onToggleLayout: () -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (loading) "剧情选项生成中..." else "剧情选项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (layoutMode == ChatInputLayoutMode.MERGED) {
                IconButton(onClick = onTogglePanel, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (panelExpanded) Icons.Filled.MoreVert else Icons.Filled.Add,
                        contentDescription = if (panelExpanded) "收起更多操作" else "更多操作",
                        tint = if (panelExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = onToggleInput, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (inputVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                        contentDescription = if (inputVisible) "隐藏输入框" else "展开输入框",
                        tint = if (inputVisible) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            IconButton(onClick = onToggleLayout, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (layoutMode == ChatInputLayoutMode.MERGED) {
                        Icons.Filled.VerticalSplit
                    } else {
                        Icons.Filled.ViewAgenda
                    },
                    contentDescription = if (layoutMode == ChatInputLayoutMode.MERGED) {
                        "切换为分离布局"
                    } else {
                        "切换为合并布局"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            if (sending) {
                IconButton(onClick = onStop, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "停止生成",
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(19.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onRegenerate,
                    enabled = enabled && !loading,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "换一组",
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        if (loading) {
            // 骨架微光动画：等待剧情选项生成
            val transition = rememberInfiniteTransition(label = "plot_skeleton")
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "plot_alpha"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                choices.take(3).forEach { choice ->
                    // 剧情等级配色：转折点红、重要琥珀、普通主题色
                    val levelColor = when (choice.level) {
                        "turning_point" -> Color(0xFFFF6B6B)
                        "important" -> Color(0xFFFFB347)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val selected = choice.id == selectedId || choice.selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clickable(enabled = enabled) { onSelect(choice) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) levelColor.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (selected) BorderStroke(1.dp, levelColor.copy(alpha = 0.6f)) else null
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
                            // 等级标识：色点 + 文字
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
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
                                    color = levelColor
                                )
                            }
                            Spacer(Modifier.height(3.dp))
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
}

@Composable
private fun ModernChatActionPanel(
    messageCount: Int,
    charCount: Int,
    tokenEstimate: Int,
    sending: Boolean,
    fileBusy: Boolean,
    onCompress: () -> Unit,
    onSendFile: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSearch: () -> Unit,
    onFavorites: () -> Unit,
    onJumpToLatest: () -> Unit,
    onMyMessages: () -> Unit,
    onUploadOnly: () -> Unit,
    onClear: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        cornerRadius = 24,
        contentPadding = PaddingValues(0.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 470.dp),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ModernContextCard(
                    messageCount = messageCount,
                    charCount = charCount,
                    tokenEstimate = tokenEstimate,
                    sending = sending,
                    onCompress = onCompress
                )
            }
            item { ModernSectionTitle("常用") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModernQuickAction(
                        icon = Icons.Filled.AttachFile,
                        title = "发送文件",
                        subtitle = "交给 AI 阅读",
                        enabled = !fileBusy,
                        onClick = onSendFile,
                        modifier = Modifier.weight(1f)
                    )
                    ModernQuickAction(
                        icon = Icons.Filled.Folder,
                        title = "工作区",
                        subtitle = "浏览会话文件",
                        enabled = true,
                        onClick = onOpenWorkspace,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModernQuickAction(
                        icon = Icons.Filled.Search,
                        title = "搜索对话",
                        subtitle = "查找历史内容",
                        enabled = messageCount > 0,
                        onClick = onSearch,
                        modifier = Modifier.weight(1f)
                    )
                    ModernQuickAction(
                        icon = Icons.Filled.Star,
                        title = "收藏夹",
                        subtitle = "查看收藏消息",
                        enabled = true,
                        onClick = onFavorites,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item { ModernSectionTitle("会话工具") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.43f)
                ) {
                    Column {
                        ModernToolRow(
                            icon = Icons.Filled.KeyboardDoubleArrowDown,
                            title = "回到最新消息",
                            subtitle = "重新定位到会话底部",
                            enabled = messageCount > 0,
                            onClick = onJumpToLatest
                        )
                        ModernMenuDivider()
                        ModernToolRow(
                            icon = Icons.Outlined.AccountTree,
                            title = "我的消息",
                            subtitle = "浏览我发送过的内容",
                            enabled = messageCount > 0,
                            onClick = onMyMessages
                        )
                        ModernMenuDivider()
                        ModernToolRow(
                            icon = Icons.Filled.CloudUpload,
                            title = "仅上传到工作区",
                            subtitle = "保存文件，不触发 AI",
                            enabled = !fileBusy,
                            onClick = onUploadOnly
                        )
                    }
                }
            }
            item { ModernSectionTitle("危险操作") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.07f)
                ) {
                    ModernToolRow(
                        icon = Icons.Filled.CleaningServices,
                        title = "清空当前会话",
                        subtitle = "删除全部消息且无法恢复",
                        enabled = !sending && messageCount > 0,
                        danger = true,
                        onClick = onClear
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernContextCard(
    messageCount: Int,
    charCount: Int,
    tokenEstimate: Int,
    sending: Boolean,
    onCompress: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "上下文",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "当前会话与输入统计",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onCompress, enabled = !sending && messageCount > 0) {
                    Icon(Icons.Filled.Compress, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("压缩")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernMetric("消息", messageCount.toString(), Modifier.weight(1f))
                ModernMetric("草稿字符", charCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernMetric("估算 Token", tokenEstimate.toString(), Modifier.weight(1f))
                ModernMetric("状态", if (sending) "生成中" else "就绪", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModernMetric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModernSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ModernQuickAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ModernToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = if (enabled) 0.13f else 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.62f else 0.28f)
        )
    }
}

@Composable
private fun ModernMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    )
}

@Composable
private fun ModernMessageListDialog(
    title: String,
    messages: List<Message>,
    emptyText: String,
    onJump: (Message) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title (${messages.size})") },
        text = {
            if (messages.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id ?: it.hashCode().toString() }) { message ->
                        val ts = remember(message.timestamp) { compactTime(message.timestamp) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.clickable { onJump(message) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                if (!ts.isNullOrBlank()) {
                                    Text(
                                        ts,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = message.displayContent.ifBlank { "（空消息）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ModernSearchDialog(
    messages: List<Message>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val results = remember(query, messages) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) emptyList()
        else messages.filter {
            it.role != "system" && it.displayContent.lowercase().contains(normalized)
        }.take(80)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索对话") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索当前会话...") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                when {
                    query.isBlank() -> Text("输入关键词开始搜索", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    results.isEmpty() -> Text("无匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(results, key = { it.id ?: it.hashCode().toString() }) { message ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        if (message.isUser) "我" else "AI",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        message.displayContent,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ModernFavoritesDialog(
    favorites: List<JsonObject>,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏夹") },
        text = {
            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
                favorites.isEmpty() -> Text("暂无收藏内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(favorites, key = { item ->
                        item.get("id")?.takeIf { !it.isJsonNull }?.asString ?: item.hashCode().toString()
                    }) { item ->
                        val title = item.get("title")?.takeIf { !it.isJsonNull }?.asString
                            ?: item.get("name")?.takeIf { !it.isJsonNull }?.asString
                            ?: "未命名收藏"
                        val count = item.getAsJsonArray("messages")?.size()
                            ?: item.getAsJsonArray("message_ids")?.size()
                            ?: 0
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ) {
                            Column(modifier = Modifier.padding(11.dp)) {
                                Text(title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "$count 条消息",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

internal fun estimateModernChatDraftTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val chineseCount = text.count { it.code in 0x4E00..0x9FFF }
    val otherCount = text.length - chineseCount
    return (chineseCount + otherCount / 4).coerceAtLeast(1)
}

private fun modernReadUriFile(
    context: android.content.Context,
    uri: android.net.Uri
): Pair<String, ByteArray>? = runCatching {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else uri.lastPathSegment
    } ?: uri.lastPathSegment ?: "file"
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    name to bytes
}.getOrNull()

private fun modernGuessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt" -> "text/plain"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    else -> "application/octet-stream"
}

private fun modernFormatDuration(seconds: Int): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)
