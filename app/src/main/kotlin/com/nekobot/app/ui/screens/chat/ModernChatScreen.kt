package com.nekobot.app.ui.screens.chat

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
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
    val selectionMode by viewModel.selectionMode.collectAsState()
    val listState = rememberLazyListState()
    val composerScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        ChatScreen(
            sessionId = sessionId,
            onBack = onBack,
            onOpenChat = onOpenChat,
            onOpenSessionDetail = onOpenSessionDetail,
            onOpenWorkspace = onOpenWorkspace,
            onOpenStoryGraph = onOpenStoryGraph,
            externalListState = listState
        )

        if (!selectionMode) {
            ModernChatComposer(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(20f),
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
    }
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
                    onSelect = { choice ->
                        pendingPlotChoiceId = choice.id
                        input = choice.title
                        closePanel()
                    },
                    onRegenerate = onRegeneratePlotChoices
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

            if (input.isNotBlank()) {
                Text(
                    text = "$charCount 字 / ~$tokenEstimate tok",
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 20.dp, top = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
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
                    IconButton(
                        onClick = {
                            panelExpanded = !panelExpanded
                            if (panelExpanded) keyboard?.hide()
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (panelExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                            )
                    ) {
                        Icon(
                            imageVector = if (panelExpanded) Icons.Filled.Close else Icons.Filled.Add,
                            contentDescription = if (panelExpanded) "关闭操作菜单" else "更多操作",
                            tint = if (panelExpanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

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
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 128.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
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

                    val actionColor = when {
                        sending -> Color(0xFFFF6B6B)
                        input.isNotBlank() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                    }
                    IconButton(
                        onClick = {
                            when {
                                sending -> onStop()
                                input.isNotBlank() -> {
                                    val text = input
                                    val choiceId = pendingPlotChoiceId
                                    input = ""
                                    pendingPlotChoiceId = null
                                    closePanel()
                                    keyboard?.hide()
                                    onSend(text, choiceId)
                                }
                                else -> requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(actionColor)
                    ) {
                        when {
                            sending -> Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止生成",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                            input.isNotBlank() -> Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                            else -> Icon(
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
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在录音") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(modernFormatDuration(recordingDuration))
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
    onSelect: (PlotChoice) -> Unit,
    onRegenerate: () -> Unit
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
            IconButton(
                onClick = onRegenerate,
                enabled = enabled && !loading,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "换一组", modifier = Modifier.size(19.dp))
            }
        }

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                choices.take(3).forEach { choice ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clickable(enabled = enabled) { onSelect(choice) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (choice.id == selectedId || choice.selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        } else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = choice.title.ifBlank { "选项" },
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
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
