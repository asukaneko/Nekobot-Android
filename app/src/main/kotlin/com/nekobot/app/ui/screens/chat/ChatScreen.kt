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
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.nekobot.app.data.local.ChatInputLayoutMode
import com.nekobot.app.data.local.VISION_FAILURE_MARKER
import com.nekobot.app.data.local.isLocalCommandMessage
import com.nekobot.app.data.local.ai.LocalSandboxCommandResult
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/**
 * 对话页：展示会话消息列表与输入栏，支持发送、重新生成、停止、清空、删除消息。
 * 进入页面自动加载 [sessionId] 的消息与会话信息。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onOpenSessionDetail: (String) -> Unit = {},
    onOpenWorkspace: (String) -> Unit = {},
    onOpenStoryGraph: (String) -> Unit = {},
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    customBottomBar: (@Composable () -> Unit)? = null
) {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsState()
    val ttsStates by viewModel.ttsStates.collectAsState()
    val session by viewModel.session.collectAsState()
    val groupCharacters by viewModel.groupCharacters.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val plotChoices by viewModel.plotChoices.collectAsState()
    val plotChoicesLoading by viewModel.plotChoicesLoading.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedMessageIds.collectAsState()
    val execConfirmation by viewModel.execConfirmation.collectAsState()
    val hookNotifications by viewModel.hookNotifications.collectAsState()
    val latestBrowserProgressCardId = remember(messages) {
        messages.asReversed().firstNotNullOfOrNull { message ->
            message.thinkingCards
                ?.asReversed()
                ?.firstOrNull { card ->
                    card.steps.any { step ->
                        step.name.equals("browser_use", ignoreCase = true) ||
                            step.name?.contains("browser", ignoreCase = true) == true
                    }
                }
                ?.id
        }
    }

    var input by remember(sessionId) {
        mutableStateOf(ServiceContainer.prefs.getChatInputDraft(sessionId))
    }
    // 输入框草稿持久化：退出会话后保留
    LaunchedEffect(input, sessionId) {
        ServiceContainer.prefs.setChatInputDraft(sessionId, input)
    }
    var pendingPlotChoiceId by remember { mutableStateOf<String?>(null) }
    // Agent 进度卡片步骤详情弹窗目标（点击 step 时填充）
    var stepDetailTarget by remember { mutableStateOf<com.nekobot.app.data.model.ThinkingStep?>(null) }
    var chatInputLayout by remember {
        mutableStateOf(ServiceContainer.prefs.chatInputLayoutMode)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<Message?>(null) }
    var messageActionTarget by remember(sessionId) { mutableStateOf<Message?>(null) }
    var selectingTextMessage by remember(sessionId) { mutableStateOf<Message?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showMyMessages by remember { mutableStateOf(false) }
    var showRestoreArchiveDialog by remember { mutableStateOf(false) }
    var showArchiveViewer by remember { mutableStateOf(false) }
    var showSandboxTerminal by remember(sessionId) { mutableStateOf(false) }
    var sandboxTerminalEntries by remember(sessionId) {
        mutableStateOf<List<SandboxTerminalEntry>>(emptyList())
    }
    var sandboxTerminalRunning by remember(sessionId) { mutableStateOf(false) }
    // 展开状态必须高于 LazyColumn item：工具步骤更新或卡片离屏回收后仍保留用户选择。
    val progressCardExpansionOverrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    val listState = externalListState ?: rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // 文件选择模式：null=未选择, "send"=发送文件(上传+插入引用), "upload"=仅上传到工作区
    var filePickMode by remember { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    // 待发送的图片附件：每项含 name/path/type，发送消息时一并传入聊天管线进行视觉识别
    var pendingImageAttachments by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

    // ===== 收藏夹 & 搜索 =====
    var showFavoritesDialog by remember { mutableStateOf(false) }
    var showAddFavoritesDialog by remember { mutableStateOf(false) }
    var favTitleInput by remember { mutableStateOf("") }
    var showSearchDialog by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var favoritesLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // ===== 语音输入（录音 + STT 识别）=====
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var voiceTranscribing by remember { mutableStateOf(false) }
    var recorderRef by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var audioFileRef by remember { mutableStateOf<java.io.File?>(null) }

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
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_recording_start_failed, e.message ?: context.getString(R.string.common_unknown_error))) }
        }
    }

    fun stopAndTranscribe() {
        val recorder = recorderRef ?: return
        val file = audioFileRef
        try {
            recorder.stop()
            recorder.release()
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_recording_end_error, e.message ?: "")) }
        }
        recorderRef = null
        isRecording = false
        if (file == null || !file.exists()) return
        voiceTranscribing = true
        scope.launch {
            try {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { file.readBytes() }
                when (val res = ServiceContainer.unified.transcribeAudio(bytes, file.name, "zh")) {
                    is com.nekobot.app.data.repository.Resource.Success -> {
                        val text = res.data?.text
                        if (!text.isNullOrBlank()) {
                            input = if (input.isBlank()) text else "$input $text"
                        } else {
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_no_text))
                        }
                    }
                    is com.nekobot.app.data.repository.Resource.Error ->
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_recognize_failed, res.message ?: ""))
                    is com.nekobot.app.data.repository.Resource.Loading -> {}
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_recognize_failed, e.message ?: context.getString(R.string.common_unknown_error)))
            } finally {
                voiceTranscribing = false
                file.delete()
            }
        }
    }

    fun cancelRecording() {
        val recorder = recorderRef ?: return
        try {
            recorder.stop()
            recorder.release()
        } catch (_: Exception) {}
        recorderRef = null
        audioFileRef?.delete()
        audioFileRef = null
        isRecording = false
        recordingDuration = 0
    }

    val requestMicPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecording()
        } else {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_permission_required)) }
        }
    }

    // 录音计时器
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordingDuration++
        }
    }

    fun startVoiceInput() {
        requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    // 标记是否为首次加载，用于跳过滚动动画直接定位到最新消息
    var initialLoad by remember { mutableStateOf(true) }

    // 进入页面加载
    LaunchedEffect(sessionId) {
        initialLoad = true
        viewModel.init(sessionId)
    }
    // 角色卡立绘/头像变更后刷新当前会话，使顶栏头像和消息头像跟随更新
    LaunchedEffect(Unit) {
        ServiceContainer.characterChanged.collect {
            viewModel.refreshSession()
        }
    }
    // 生命周期绑定：聊天界面可见性追踪（用于通知提醒判断）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    viewModel.setChatVisible(true)
                    viewModel.refreshSession()
                    viewModel.activateRealtimeSession()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.setChatVisible(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 新消息时滚动：首次加载用瞬时滚动（无动画），后续用动画滚动
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (initialLoad) {
                // 首次加载：直接跳到底部，无动画
                listState.scrollToItem(messages.lastIndex)
                initialLoad = false
            } else {
                listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }

    // 文件选择器：选取本地文件
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val mode = filePickMode
        filePickMode = null
        if (uri == null || mode == null) return@rememberLauncherForActivityResult
        fileBusy = true
        scope.launch {
            try {
                // 读取文件名和字节
                val (name, bytes) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readUriFile(context, uri) ?: throw IllegalStateException(context.getString(R.string.chat_read_file_failed))
                }
                val mediaType = guessMime(name).toMediaTypeOrNull()
                val body = bytes.toRequestBody(mediaType)
                val part = MultipartBody.Part.createFormData("file", name, body)
                when (val res = ServiceContainer.unified.uploadWorkspaceFile(sessionId, part)) {
                    is com.nekobot.app.data.repository.Resource.Success -> {
                        if (mode == "send") {
                            // 发送文件：判断是否为图片
                            val uploadedAttachment = buildWorkspaceChatAttachment(
                                uploadResult = res.data,
                                sessionId = sessionId,
                                originalName = name,
                                fallbackMime = guessMime(name)
                            )
                            val uploadedName = uploadedAttachment["name"]?.toString() ?: name
                            val mime = uploadedAttachment["type"]?.toString() ?: guessMime(uploadedName)
                            val isImage = mime.startsWith("image/")
                            // 解析工作区绝对路径，供视觉识别读取
                            val workspacePath = if (ServiceContainer.prefs.isLocalMode) {
                                val filesDir = context.filesDir
                                com.nekobot.app.data.local.LocalWorkspaceStorage.resolve(filesDir, sessionId)
                                    ?.let { java.io.File(it, uploadedName).absolutePath }
                            } else {
                                null
                            }
                            if (isImage) {
                                // 图片：加入待发送附件列表，输入框插入引用（可编辑）
                                pendingImageAttachments = pendingImageAttachments + buildWorkspaceChatAttachment(
                                    uploadResult = res.data,
                                    sessionId = sessionId,
                                    originalName = uploadedName,
                                    fallbackMime = mime,
                                    localPath = workspacePath
                                )
                                input = buildString {
                                    if (input.isNotBlank()) { append(input); append("\n") }
                                    append(context.getString(R.string.chat_file_uploaded_ref_inline, uploadedName))
                                }
                                snackbarHostState.showSnackbar(context.getString(R.string.chat_image_attached, uploadedName))
                            } else {
                                // 非图片：仅插入文本引用
                                input = buildString {
                                    if (input.isNotBlank()) { append(input); append("\n") }
                                    append(context.getString(R.string.chat_file_uploaded_ref_inline, uploadedName))
                                }
                                snackbarHostState.showSnackbar(context.getString(R.string.chat_file_uploaded_ref))
                            }
                        } else {
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_uploaded_to_workspace, name))
                        }
                    }
                    is com.nekobot.app.data.repository.Resource.Error -> {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_upload_failed, res.message ?: ""))
                    }
                    is com.nekobot.app.data.repository.Resource.Loading -> {}
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_operation_failed, e.message ?: context.getString(R.string.common_unknown_error)))
            } finally {
                fileBusy = false
            }
        }
    }

    // 加载收藏夹（服务器模式）
    LaunchedEffect(showFavoritesDialog) {
        if (showFavoritesDialog && !com.nekobot.app.ServiceContainer.prefs.isLocalMode) {
            favoritesLoading = true
            when (val res = ServiceContainer.unified.listMessageFavorites(sessionId)) {
                is com.nekobot.app.data.repository.Resource.Success -> {
                    val obj = res.data?.asJsonObject
                    val arr = obj?.getAsJsonArray("collections") ?: obj?.getAsJsonArray("favorites")
                    favorites = arr?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
                }
                else -> {}
            }
            favoritesLoading = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 顶栏融入聊天背景，底部以发丝分割线区分内容区
            Column {
                if (selectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.chat_selected_count, selectedIds.size),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_exit_select), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.deleteSelectedMessages() }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(onClick = {
                                favTitleInput = ""
                                showAddFavoritesDialog = true
                            }) {
                                Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.chat_add_to_favorites), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                } else {
                    val conversationLabel = stringResource(R.string.chat_conversation)
                    val typingLabel = stringResource(R.string.chat_typing)
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChatAvatar(
                                    portraitUrl = session?.portraitUrl,
                                    size = 34.dp,
                                    ring = true,
                                    fallbackIcon = if (session?.sessionMode == "group") Icons.Outlined.Group else Icons.Outlined.SmartToy
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = session?.displayName ?: conversationLabel,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // 副标题：生成中显示“正在输入…”，否则显示消息总数
                                    Text(
                                        text = if (sending) typingLabel
                                        else stringResource(R.string.chat_message_count, messages.count { !it.isThinkingCard }),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (sending) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_more), tint = MaterialTheme.colorScheme.onSurface)
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_session_detail)) },
                                        onClick = {
                                            menuExpanded = false
                                            session?.id?.let { onOpenSessionDetail(it) }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_story_graph)) },
                                        onClick = {
                                            menuExpanded = false
                                            session?.id?.let { onOpenStoryGraph(it) }
                                        }
                                    )
                                    if (
                                        session?.sessionMode.equals("agent", ignoreCase = true) &&
                                        ServiceContainer.prefs.isLocalMode
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_sandbox_terminal)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Filled.Keyboard,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                showSandboxTerminal = true
                                            }
                                        )
                                    }
                                    // 仅当当前会话绑定了归档会话时显示
                                    if (!session?.archiveSessionId.isNullOrBlank()) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_extract_archive)) },
                                            onClick = {
                                                menuExpanded = false
                                                showRestoreArchiveDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_view_archive)) },
                                            onClick = {
                                                menuExpanded = false
                                                showArchiveViewer = true
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_clear_messages), color = Color(0xFFFF6B6B)) },
                                        onClick = {
                                            menuExpanded = false
                                            showClearConfirm = true
                                        }
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                )
            }
        },
        bottomBar = {
            // 多选模式下隐藏输入栏
            if (!selectionMode) {
        if (customBottomBar != null) {
            customBottomBar()
        } else {
                // 底部输入栏：左侧 + 按钮展开数据/操作面板，中间输入框，右侧发送
                ChatInputBar(
                    input = input,
                    onInputChange = { input = it },
                    sending = sending,
                    messageCount = messages.size,
                    plotChoices = plotChoices,
                    plotChoicesLoading = plotChoicesLoading,
                    pendingPlotChoiceId = pendingPlotChoiceId,
                    layoutMode = chatInputLayout,
                    onLayoutModeChange = { mode ->
                        chatInputLayout = mode
                        ServiceContainer.prefs.chatInputLayoutMode = mode
                    },
                    onSelectPlotChoice = { choice ->
                        // 点击仅作为待发送候选，真正发送时才提交最终选项。
                        input = choice.title
                        pendingPlotChoiceId = choice.id
                    },
                    onRegeneratePlotChoices = {
                        pendingPlotChoiceId = null
                        viewModel.regeneratePlotChoices()
                    },
                    onScrollToBottom = {
                        if (messages.isNotEmpty()) {
                            scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                        }
                    },
                    onShowMyMessages = { showMyMessages = true },
                    onSend = {
                        val text = input
                        val plotChoiceId = pendingPlotChoiceId
                        val images = pendingImageAttachments
                        input = ""
                        pendingPlotChoiceId = null
                        pendingImageAttachments = emptyList()
                        keyboard?.hide()
                        viewModel.sendMessage(text, plotChoiceId, attachments = images)
                    },
                    onStop = { viewModel.stop() },
                    onClear = { showClearConfirm = true },
                    onCompress = { viewModel.compressContext() },
                    onSendFile = {
                        filePickMode = "send"
                        pickFile.launch("*/*")
                    },
                    onUploadToWorkspace = {
                        filePickMode = "upload"
                        pickFile.launch("*/*")
                    },
                    onOpenWorkspace = { onOpenWorkspace(sessionId) },
                    onVoiceInput = { startVoiceInput() },
                    onShowFavorites = { showFavoritesDialog = true },
                    onShowSearch = { showSearchDialog = true },
                    fileBusy = fileBusy
                )
                }
            }
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 氛围背景：顶部一抹主题色微光，向下渐隐，增加层次感
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = 520f
                        )
                    )
            )
            if (messages.isEmpty() && loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (messages.isEmpty()) {
                // 空会话引导：角色会话显示立绘，其他模式显示默认图标
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (session?.sessionMode != "agent" && session?.sessionMode != "group") {
                        ChatAvatar(
                            portraitUrl = session?.portraitUrl,
                            size = 88.dp,
                            ring = true
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (session?.sessionMode == "group") Icons.Outlined.Group
                                else Icons.Outlined.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        stringResource(R.string.chat_start_new_conversation),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            R.string.chat_send_prompt,
                            // 优先显示角色名：characterName（本地模式）→ senderName（远程模式后端字段）→ displayName（会话名）→ AI
                            session?.characterName?.takeIf { it.isNotBlank() }
                                ?: session?.senderName?.takeIf { it.isNotBlank() }
                                ?: session?.displayName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.chat_ai_label)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Agent 模式不显示背景设定卡片（agent 会话不继承角色卡配置）
                    if (session?.sessionMode != "agent") {
                        item(key = "background_setting", contentType = "background_setting") {
                            BackgroundSettingCard(content = session?.scenario)
                        }
                    }
                    itemsIndexed(
                        messages,
                        key = { _, it -> it.id ?: (it.content + it.timestamp + it.hashCode()) }
                    ) { index, msg ->
                        // 注意：LazyColumn 单个 item 内的多个平级节点会像 Box 一样叠放，
                        // 因此日期分隔条与气泡必须包在 Column 里纵向排布
                        Column {
                            // 跨天消息之间插入日期分隔条
                            val day = dayKey(msg.timestamp)
                            val prevDay = messages.getOrNull(index - 1)?.let { dayKey(it.timestamp) }
                            if (day != null && day != prevDay) {
                                DateSeparatorChip(label = dayLabel(day))
                                Spacer(Modifier.height(2.dp))
                            }
                            // 流式占位消息且内容为空：显示骨架动画（等待第一个 chunk）
                            if (msg.id == ChatViewModel.STREAMING_ID && msg.displayContent.isBlank()) {
                                ThinkingIndicator(
                                    portraitUrl = session?.portraitUrl,
                                    showAiAvatar = session?.sessionMode != "agent",
                                    fillAiWidth = session?.sessionMode == "agent",
                                    fallbackIcon = if (session?.sessionMode == "group") Icons.Outlined.Group else Icons.Outlined.SmartToy
                                )
                            } else {
                                val groupIdentity = if (session?.sessionMode.equals("group", ignoreCase = true)) {
                                    resolveGroupMessageIdentity(msg, groupCharacters)
                                } else {
                                    GroupMessageIdentity()
                                }
                                MessageBubble(
                                    message = msg,
                                    ttsState = msg.id?.let { ttsStates[it] },
                                    portraitUrl = groupIdentity.portraitUrl ?: session?.portraitUrl,
                                    senderName = groupIdentity.name,
                                    showAiAvatar = session?.sessionMode != "agent",
                                    fillAiWidth = session?.sessionMode == "agent",
                                    onLongClick = {
                                        if (selectionMode) {
                                            msg.id?.let(viewModel::toggleSelection)
                                        } else if (msg.id != ChatViewModel.STREAMING_ID) {
                                            messageActionTarget = msg
                                        }
                                    },
                                    onRegenerate = { viewModel.regenerate() },
                                    onRegenerateTts = { viewModel.regenerateMessageTts(msg) },
                                    onFork = { msg.id?.let { mid -> viewModel.forkFromMessage(mid) { onOpenChat(it) } } },
                                    onCopy = { msg.displayContent },
                                    onDelete = if (
                                        ServiceContainer.prefs.isLocalMode &&
                                        msg.isLocalCommandMessage()
                                    ) {
                                        { deletingMessage = msg }
                                    } else {
                                        null
                                    },
                                    sessionId = sessionId,
                                    selectionMode = selectionMode,
                                    isSelected = msg.id != null && msg.id in selectedIds,
                                    onToggleSelection = { msg.id?.let { viewModel.toggleSelection(it) } }
                                )
                            }
                            // Agent 与本地耗时命令：在用户气泡下方渲染持久化进度卡片。
                            if (msg.isUser && !msg.thinkingCards.isNullOrEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                msg.thinkingCards.forEach { card ->
                                    androidx.compose.runtime.key(card.id) {
                                        ProgressCard(
                                            card = card,
                                            sessionId = sessionId,
                                            expanded = resolveProgressCardExpanded(
                                                cardId = card.id,
                                                isAgent = card.isAgent,
                                                expansionOverrides = progressCardExpansionOverrides
                                            ),
                                            showBrowserPreview = card.id == latestBrowserProgressCardId,
                                            onExpandedChange = { expanded ->
                                                progressCardExpansionOverrides[card.id] = expanded
                                            },
                                            onStepClick = { step ->
                                                stepDetailTarget = step
                                            }
                                        )
                                    }
                                }
                            }
                        }
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

            // Hook 触发通知：顶部居中叠加成就式弹窗（磨砂玻璃 + 琥珀描边）
            if (hookNotifications.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    hookNotifications.forEach { notif ->
                        HookNotificationCard(
                            notification = notif,
                            onDismiss = { viewModel.dismissHookNotification(notif) }
                        )
                    }
                }
            }
        }

        // Agent 进度卡片步骤详情弹窗（点击含详情的 step 时弹出）
        stepDetailTarget?.let { step ->
            StepDetailDialog(
                step = step,
                onDismiss = { stepDetailTarget = null }
            )
        }
    }

    // 长按消息后的操作菜单
    messageActionTarget?.let { target ->
        val targetId = target.id?.takeIf {
            it.isNotBlank() && it != ChatViewModel.STREAMING_ID
        }
        ModalBottomSheet(
            onDismissRequest = { messageActionTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Text(
                text = stringResource(R.string.chat_message_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            MessageActionSheetItem(
                text = stringResource(R.string.common_delete),
                icon = Icons.Filled.Delete,
                tint = MaterialTheme.colorScheme.error,
                enabled = targetId != null,
                onClick = {
                    messageActionTarget = null
                    deletingMessage = target
                }
            )
            MessageActionSheetItem(
                text = stringResource(R.string.chat_multi_select),
                icon = Icons.Filled.CheckCircle,
                enabled = targetId != null,
                onClick = {
                    messageActionTarget = null
                    targetId?.let(viewModel::enterSelectionMode)
                }
            )
            MessageActionSheetItem(
                text = stringResource(R.string.common_select),
                icon = Icons.Filled.ContentCopy,
                onClick = {
                    messageActionTarget = null
                    selectingTextMessage = target
                }
            )
            MessageActionSheetItem(
                text = stringResource(R.string.chat_fork),
                icon = Icons.Filled.VerticalSplit,
                enabled = targetId != null,
                onClick = {
                    messageActionTarget = null
                    targetId?.let { messageId ->
                        viewModel.forkFromMessage(messageId) { onOpenChat(it) }
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    selectingTextMessage?.let { selectedMessage ->
        val selectableText = selectedMessage.displayContent
        NekoDialog(
            onDismiss = { selectingTextMessage = null },
            title = stringResource(R.string.chat_select_text_title),
            confirmText = stringResource(R.string.common_copy),
            onConfirm = {
                clipboard.setText(AnnotatedString(selectableText))
                selectingTextMessage = null
            },
            cancelText = stringResource(R.string.common_close),
            onCancel = { selectingTextMessage = null }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = selectableText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // Agent 非白名单命令授权
    execConfirmation?.let { request ->
        val mainCommandLabel = request.mainCommand.ifBlank {
            stringResource(R.string.chat_exec_confirm_this_command)
        }
        NekoDialog(
            onDismiss = { viewModel.respondToExecConfirmation(ExecAuthorization.Reject) },
            title = stringResource(R.string.chat_exec_confirm_title),
            message = stringResource(R.string.chat_exec_confirm_description),
            confirmText = stringResource(R.string.chat_exec_confirm_allow),
            onConfirm = { viewModel.respondToExecConfirmation(ExecAuthorization.Once) },
            cancelText = stringResource(R.string.chat_exec_confirm_reject),
            onCancel = { viewModel.respondToExecConfirmation(ExecAuthorization.Reject) }
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = request.command.ifBlank {
                                stringResource(R.string.chat_exec_confirm_unknown_command)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }
            }
            if (request.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = request.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.respondToExecConfirmation(ExecAuthorization.Always)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        R.string.chat_exec_confirm_always_allow,
                        mainCommandLabel
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.chat_exec_confirm_request_id,
                    request.requestId.take(8)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 删除消息确认
    deletingMessage?.let { msg ->
        NekoDialog(
            onDismiss = { deletingMessage = null },
            title = stringResource(R.string.chat_delete_message_title),
            message = stringResource(R.string.chat_delete_message_confirm),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                viewModel.deleteMessage(sessionId, msg.id.orEmpty()) { deletingMessage = null }
            }
        )
    }

    // 清空消息确认
    if (showClearConfirm) {
        NekoDialog(
            onDismiss = { showClearConfirm = false },
            title = stringResource(R.string.chat_clear_messages_title),
            message = stringResource(R.string.chat_clear_messages_confirm),
            confirmText = stringResource(R.string.common_clear),
            onConfirm = {
                viewModel.clearMessages(sessionId) { showClearConfirm = false }
            }
        )
    }

    // 提取归档对话框：输入要提取的对话轮数
    if (showRestoreArchiveDialog) {
        var turnsText by remember { mutableStateOf("5") }
        NekoDialog(
            onDismiss = { showRestoreArchiveDialog = false },
            title = stringResource(R.string.chat_extract_archive_title),
            message = stringResource(R.string.chat_extract_archive_desc),
            confirmText = stringResource(R.string.chat_extract_button),
            onConfirm = {
                val turns = turnsText.trim().toIntOrNull()?.coerceIn(1, 100) ?: 5
                viewModel.restoreFromArchive(turns)
                showRestoreArchiveDialog = false
            }
        ) {
            OutlinedTextField(
                value = turnsText,
                onValueChange = { s -> turnsText = s.filter { it.isDigit() }.take(3) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                label = { Text(stringResource(R.string.chat_turns)) }
            )
        }
    }

    // 查看归档对话框：拉取归档会话消息并展示
    if (showArchiveViewer) {
        val archiveSession = remember { androidx.compose.runtime.mutableStateOf<Session?>(null) }
        val archiveMessages = remember { androidx.compose.runtime.mutableStateOf<List<Message>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(showArchiveViewer) {
            if (showArchiveViewer) {
                viewModel.viewArchive { s, msgs ->
                    archiveSession.value = s
                    archiveMessages.value = msgs
                }
            }
        }
        NekoDialog(
            onDismiss = {
                showArchiveViewer = false
                archiveSession.value = null
                archiveMessages.value = emptyList()
            },
            title = stringResource(R.string.chat_archive_session, archiveSession.value?.displayName ?: stringResource(R.string.common_loading)),
            message = if (archiveMessages.value.isEmpty()) stringResource(R.string.chat_no_archive_messages) else null,
            confirmText = stringResource(R.string.common_close),
            onConfirm = null
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(archiveMessages.value, key = { it.id ?: it.content.hashCode().toString() }) { msg ->
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        color = if (msg.isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = if (msg.isUser) stringResource(R.string.chat_me) else stringResource(R.string.chat_ai_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = msg.content.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // 我的消息列表弹窗：点击可跳转到对应气泡
    if (showMyMessages) {
        val myMessages = messages.mapIndexedNotNull { idx, m -> if (m.isUser) idx to m else null }
        NekoDialog(
            onDismiss = { showMyMessages = false },
            title = stringResource(R.string.chat_my_messages_title, myMessages.size),
            message = if (myMessages.isEmpty()) stringResource(R.string.chat_no_user_messages) else null,
            confirmText = stringResource(R.string.common_close),
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

    // 语音录制中弹窗
    if (isRecording) {
        VoiceRecordingDialog(
            duration = recordingDuration,
            onStop = { stopAndTranscribe() },
            onCancel = { cancelRecording() }
        )
    }
    // 语音识别中弹窗
    if (voiceTranscribing) {
        NekoDialog(
            onDismiss = {},
            title = stringResource(R.string.chat_voice_recognizing),
            message = stringResource(R.string.chat_voice_recognizing_msg),
            confirmText = stringResource(R.string.chat_please_wait),
            onConfirm = null,
            cancelText = null,
            onCancel = null
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // 收藏夹弹窗
    if (showFavoritesDialog) {
        MessageFavoritesDialog(
            favorites = favorites,
            loading = favoritesLoading,
            onDismiss = { showFavoritesDialog = false }
        )
    }

    // 搜索对话弹窗
    if (showSearchDialog) {
        MessageSearchDialog(
            messages = messages,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showSearchDialog = false },
            onResultClick = { msgId ->
                showSearchDialog = false
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
            }
        )
    }

    // 添加到收藏夹弹窗（多选模式）
    if (showAddFavoritesDialog) {
        NekoDialog(
            onDismiss = { showAddFavoritesDialog = false },
            title = stringResource(R.string.chat_add_to_favorites_title),
            message = stringResource(R.string.chat_selected_messages_count, selectedIds.size),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                val visibleMessageIds = messages.asSequence()
                    .mapNotNull { it.id }
                    .filter { it != ChatViewModel.STREAMING_ID }
                    .toSet()
                val ids = selectedIds.filter { it in visibleMessageIds }
                if (ids.isEmpty()) {
                    showAddFavoritesDialog = false
                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.chat_messages_not_synced)) }
                    return@NekoDialog
                }
                scope.launch {
                    val res = try {
                        ServiceContainer.unified.updateMessageFavorites(
                            sessionId,
                            MessageFavoriteRequest(messageIds = ids, title = favTitleInput.ifBlank { null })
                        )
                    } catch (e: Exception) {
                        com.nekobot.app.data.repository.Resource.Error(e.message ?: context.getString(R.string.chat_favorite_failed))
                    }
                    when (res) {
                        is com.nekobot.app.data.repository.Resource.Success -> {
                            val response = res.data?.takeIf { it.isJsonObject }?.asJsonObject
                            if (response?.get("success")?.takeIf { !it.isJsonNull }?.asBoolean == false) {
                                val message = response.get("error")?.asString ?: context.getString(R.string.chat_server_no_favorite)
                                snackbarHostState.showSnackbar(context.getString(R.string.chat_favorite_failed_msg, message))
                                return@launch
                            }
                            viewModel.exitSelectionMode()
                            showAddFavoritesDialog = false
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_added_to_favorites))
                        }
                        is com.nekobot.app.data.repository.Resource.Error -> {
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_favorite_failed_msg, res.message ?: ""))
                        }
                        is com.nekobot.app.data.repository.Resource.Loading -> {}
                    }
                }
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { showAddFavoritesDialog = false }
        ) {
            OutlinedTextField(
                value = favTitleInput,
                onValueChange = { favTitleInput = it },
                label = { Text(stringResource(R.string.chat_favorite_name_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showSandboxTerminal) {
        SandboxTerminalDialog(
            entries = sandboxTerminalEntries,
            running = sandboxTerminalRunning,
            onRunCommand = { rawCommand ->
                val command = rawCommand.trim()
                when {
                    command.isEmpty() -> Unit
                    command == "clear" -> sandboxTerminalEntries = emptyList()
                    command == "exit" -> showSandboxTerminal = false
                    !sandboxTerminalRunning -> {
                        val entryId = System.nanoTime()
                        sandboxTerminalEntries = sandboxTerminalEntries + SandboxTerminalEntry(
                            id = entryId,
                            command = command,
                            isRunning = true,
                        )
                        sandboxTerminalRunning = true
                        viewModel.executeSandboxCommand(command) { result ->
                            var accepted = false
                            sandboxTerminalEntries = sandboxTerminalEntries.map { entry ->
                                if (entry.id == entryId && entry.isRunning) {
                                    accepted = true
                                    entry.withResult(result)
                                } else {
                                    entry
                                }
                            }
                            if (accepted) sandboxTerminalRunning = false
                        }
                    }
                }
            },
            onStop = {
                viewModel.stopSandboxCommand()
                sandboxTerminalEntries = sandboxTerminalEntries.map { entry ->
                    if (entry.isRunning) {
                        entry.copy(
                            output = "^C",
                            exitCode = 130,
                            isRunning = false,
                        )
                    } else {
                        entry
                    }
                }
                sandboxTerminalRunning = false
            },
            onClear = {
                if (!sandboxTerminalRunning) sandboxTerminalEntries = emptyList()
            },
            onDismiss = { showSandboxTerminal = false },
        )
    }
}

private data class SandboxTerminalEntry(
    val id: Long,
    val command: String,
    val output: String = "",
    val error: String? = null,
    val exitCode: Int? = null,
    val durationMs: Long = 0L,
    val timedOut: Boolean = false,
    val isRunning: Boolean = false,
) {
    fun withResult(result: LocalSandboxCommandResult): SandboxTerminalEntry = copy(
        output = result.output,
        error = result.error,
        exitCode = result.exitCode,
        durationMs = result.durationMs,
        timedOut = result.timedOut,
        isRunning = false,
    )
}

/**
 * 当前 Agent 会话的全屏沙箱终端。
 *
 * 终端只负责展示和输入，命令状态由 ChatScreen 提升持有，因此关闭再打开时
 * 本次页面生命周期内的输出仍在；底层 shell 则由会话级 coordinator 长期持有。
 */
@Composable
private fun SandboxTerminalDialog(
    entries: List<SandboxTerminalEntry>,
    running: Boolean,
    onRunCommand: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val background = Color(0xFF0B0F14)
    val panel = Color(0xFF111820)
    val foreground = Color(0xFFD8DEE9)
    val muted = Color(0xFF7F8B99)
    val prompt = Color(0xFF73D99F)
    val errorColor = Color(0xFFFF7B72)
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val terminalBottomInsets = WindowInsets.navigationBars
        .union(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom)

    fun submit() {
        val command = input.trim()
        if (command.isBlank() || running) return
        input = ""
        onRunCommand(command)
    }

    LaunchedEffect(Unit) {
        delay(120)
        focusRequester.requestFocus()
    }
    LaunchedEffect(entries.size, entries.lastOrNull()?.isRunning) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = null,
                        tint = prompt,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_sandbox_terminal_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = foreground,
                        )
                        Text(
                            text = stringResource(R.string.chat_sandbox_terminal_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                        )
                    }
                    IconButton(
                        onClick = onClear,
                        enabled = entries.isNotEmpty() && !running,
                    ) {
                        Icon(
                            Icons.Filled.CleaningServices,
                            contentDescription = stringResource(R.string.chat_sandbox_terminal_clear),
                            tint = if (entries.isNotEmpty() && !running) foreground else muted,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = foreground,
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                if (entries.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "$ _",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            color = prompt,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.chat_sandbox_terminal_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(entries, key = SandboxTerminalEntry::id) { entry ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SelectionContainer {
                                    Text(
                                        text = "$ ${entry.command}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = prompt,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                when {
                                    entry.isRunning -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 1.5.dp,
                                                color = prompt,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.chat_sandbox_terminal_running),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = muted,
                                            )
                                        }
                                    }
                                    entry.error != null -> {
                                        SelectionContainer {
                                            Text(
                                                text = entry.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = errorColor,
                                            )
                                        }
                                    }
                                    else -> {
                                        SelectionContainer {
                                            Text(
                                                text = entry.output.ifBlank {
                                                    stringResource(R.string.chat_sandbox_terminal_no_output)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = foreground,
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(
                                            text = if (entry.timedOut) {
                                                stringResource(R.string.chat_sandbox_terminal_timeout)
                                            } else {
                                                stringResource(
                                                    R.string.chat_sandbox_terminal_exit_status,
                                                    entry.exitCode ?: -1,
                                                    entry.durationMs,
                                                )
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if ((entry.exitCode ?: -1) == 0) muted else errorColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = panel,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(terminalBottomInsets)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            enabled = !running,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = foreground,
                            ),
                            leadingIcon = {
                                Text(
                                    text = "$",
                                    fontFamily = FontFamily.Monospace,
                                    color = prompt,
                                )
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.chat_sandbox_terminal_hint),
                                    fontFamily = FontFamily.Monospace,
                                    color = muted,
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = prompt,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                                disabledBorderColor = Color.White.copy(alpha = 0.08f),
                                cursorColor = prompt,
                                focusedContainerColor = background,
                                unfocusedContainerColor = background,
                                disabledContainerColor = background,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = if (running) onStop else ::submit,
                        ) {
                            Icon(
                                imageVector = if (running) {
                                    Icons.Filled.Stop
                                } else {
                                    Icons.AutoMirrored.Filled.Send
                                },
                                contentDescription = stringResource(
                                    if (running) {
                                        R.string.chat_sandbox_terminal_stop
                                    } else {
                                        R.string.chat_sandbox_terminal_run
                                    }
                                ),
                                tint = if (running) errorColor else prompt,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 语音录制中弹窗：显示录音时长 + 停止/取消按钮。 */
@Composable
private fun VoiceRecordingDialog(
    duration: Int,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    val minutes = duration / 60
    val seconds = duration % 60
    val timeText = "%02d:%02d".format(minutes, seconds)
    // 脉冲动画
    val transition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "pulse_alpha"
    )
    NekoDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.chat_recording_title),
        message = null,
        confirmText = stringResource(R.string.chat_stop_and_recognize),
        onConfirm = onStop,
        cancelText = stringResource(R.string.common_cancel),
        onCancel = onCancel
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = stringResource(R.string.chat_recording_title),
                tint = Color(0xFFFF6B6B).copy(alpha = pulseAlpha),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = timeText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 收藏夹弹窗：列出消息收藏集，每个含 title/created_at/messages。 */
@Composable
private fun MessageFavoritesDialog(
    favorites: List<JsonObject>,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    var selectedCollection by remember(favorites) { mutableStateOf<JsonObject?>(null) }
    val selectedTitle = selectedCollection?.get("title")?.takeIf { !it.isJsonNull }?.asString

    NekoDialog(
        onDismiss = onDismiss,
        title = selectedTitle ?: stringResource(R.string.chat_favorites),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss
    ) {
        if (loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (selectedCollection != null) {
            val collection = selectedCollection!!
            val messages = collection.getAsJsonArray("messages")
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                .orEmpty()
            TextButton(onClick = { selectedCollection = null }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_back_to_favorites))
            }
            if (messages.isEmpty()) {
                Text(stringResource(R.string.chat_favorite_no_messages), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.get("message_id")?.asString ?: it.get("id")?.asString ?: it.hashCode().toString() }) { message ->
                        FavoriteMessageCard(message)
                    }
                }
            }
        } else if (favorites.isEmpty()) {
            Text(stringResource(R.string.chat_no_favorites), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites, key = { it.get("id")?.asString ?: "" }) { collection ->
                    val title = collection.get("title")?.asString ?: stringResource(R.string.chat_unnamed_favorite)
                    val createdAt = collection.get("created_at")?.asString?.take(10) ?: ""
                    val messages = collection.getAsJsonArray("messages")
                        ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedCollection = collection },
                        cornerRadius = 12
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Text(createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.chat_favorite_messages_count, messages.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            messages.take(2).forEach { msg ->
                                val role = msg.get("role")?.asString ?: ""
                                val content = msg.get("content")?.asString?.take(60) ?: ""
                                Text("[$role] $content", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteMessageCard(message: JsonObject) {
    val role = message.get("role")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    val userLabel = stringResource(R.string.chat_role_user)
    val aiLabel = stringResource(R.string.chat_ai_label)
    val systemLabel = stringResource(R.string.chat_role_system)
    val msgLabel = stringResource(R.string.chat_role_message)
    val roleLabel = when (role.lowercase()) {
        "user", "human" -> userLabel
        "assistant", "ai" -> aiLabel
        "system" -> systemLabel
        else -> message.get("sender")?.takeIf { !it.isJsonNull }?.asString ?: role.ifBlank { msgLabel }
    }
    val content = message.get("content")?.takeIf { !it.isJsonNull }?.let {
        runCatching { it.asString }.getOrElse { it.toString() }
    }.orEmpty()
    val timestamp = message.get("timestamp")?.takeIf { !it.isJsonNull }?.asString
        ?.replace('T', ' ')?.take(19).orEmpty()

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                roleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (role.equals("user", true) || role.equals("human", true)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (timestamp.isNotBlank()) {
                Text(timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                content.ifBlank { stringResource(R.string.chat_empty_message) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 搜索对话弹窗：纯前端过滤当前会话消息，点击结果滚动到对应消息。 */
@Composable
private fun MessageSearchDialog(
    messages: List<Message>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultClick: (String?) -> Unit
) {
    val results = remember(query, messages) {
        val q = query.trim().lowercase()
        if (q.isBlank()) emptyList()
        else messages.filter { it.role != "system" && it.content?.lowercase()?.contains(q) == true }.take(80)
    }
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.chat_search_conversation),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.chat_search_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        if (query.isNotBlank() && results.isEmpty()) {
            Text(stringResource(R.string.chat_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else if (results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(results, key = { it.id ?: "" }) { msg ->
                    val role = msg.role ?: "unknown"
                    val content = msg.content ?: ""
                    val idx = content.lowercase().indexOf(query.lowercase())
                    val preview = if (idx >= 0) {
                        val start = maxOf(0, idx - 36)
                        val end = minOf(content.length, idx + query.length + 72)
                        (if (start > 0) "..." else "") + content.slice(start until end).replace("\n", " ").trim() + (if (end < content.length) "..." else "")
                    } else content.take(100)
                    GlassCard(
                        modifier = Modifier.fillMaxWidth().clickable { onResultClick(msg.id) },
                        cornerRadius = 12
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (role == "assistant") Icons.Outlined.SmartToy else Icons.Filled.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (role == "assistant") stringResource(R.string.chat_ai_label) else stringResource(R.string.chat_me), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单条消息气泡：用户靠右、AI 靠左。支持 <||> 分隔符拆分为多段气泡。 */
@Composable
private fun MessageActionSheetItem(
    text: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    ttsState: MessageTtsUiState? = null,
    portraitUrl: String? = null,
    senderName: String? = null,
    showAiAvatar: Boolean = true,
    fillAiWidth: Boolean = false,
    onLongClick: () -> Unit,
    onRegenerate: () -> Unit = {},
    onRegenerateTts: () -> Unit = {},
    onFork: () -> Unit = {},
    onCopy: () -> String = { "" },
    onDelete: (() -> Unit)? = null,
    sessionId: String = "",
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {}
) {
    val isUser = message.isUser
    val isLocalCommand = message.isLocalCommandMessage()
    // 用户气泡基色：若设置了主题色覆盖则跟随主题，否则使用默认紫色
    val userBubble = ServiceContainer.prefs.themeColorOverride?.let { parseHexColor(it) }
        ?: if (isSystemInDarkTheme()) BubbleUser else BubbleUserLight
    // 用户气泡：基色 → 加深色的对角渐变，营造立体感
    val userBrush = Brush.linearGradient(
        colors = listOf(userBubble, deepenColor(userBubble))
    )
    // AI 气泡：半透明玻璃质感容器 + 发丝边框
    val aiContainer = aiBubbleContainerColor()
    val aiBorder = aiBubbleBorderColor()
    // 文字颜色：用户气泡始终白色；AI 气泡跟随主题（尊重字体颜色覆盖）
    val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
    val arrangement = if (isUser) Arrangement.End else Arrangement.Start
    val clipboard = LocalClipboardManager.current

    // 按 <||> 拆分内容为多段（保留非空段）
    val isStreamingPlaceholder = message.id == ChatViewModel.STREAMING_ID
    val emptyMessageParen = stringResource(R.string.chat_empty_message_paren)
    val segments = remember(message.content) {
        message.displayContent
            .split("<||>")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { if (it.isEmpty() && !isStreamingPlaceholder) listOf(emptyMessageParen) else it }
    }
    // 解析每段的多媒体内容段
    val parsedSegments = remember(segments) {
        segments.map { parseContentSegments(it) }
    }
    val hasUserImage = isUser && parsedSegments.any { content ->
        content.any { it.isImageContent() }
    }
    // 是否包含多媒体内容（图片/视频/音频/txt/html）或音频 URL，决定气泡最大宽度
    val hasMultimedia = parsedSegments.any { segs -> segs.any { it.type != SegmentType.TEXT } }
    val hasAudioUrl = !message.audioUrl.isNullOrBlank()
    val maxBubbleWidth = if (hasMultimedia || hasAudioUrl || ttsState != null) 360.dp else 280.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.Top
    ) {
        // 多选模式：AI 气泡左侧显示勾选框
        if (selectionMode && !isUser) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        // AI 头像（使用角色立绘，带回主题色光环，失败回退到图标）
        if (!isUser && showAiAvatar) {
            ChatAvatar(portraitUrl = portraitUrl, size = 34.dp, ring = true)
            Spacer(Modifier.width(8.dp))
        }

        Column(
            modifier = when {
                !isUser && fillAiWidth -> Modifier.weight(1f)
                hasUserImage -> Modifier.widthIn(min = 300.dp, max = maxBubbleWidth)
                isUser -> Modifier.widthIn(max = maxBubbleWidth).width(IntrinsicSize.Max)
                else -> Modifier.widthIn(max = maxBubbleWidth)
            }
        ) {
            if (!isUser && !senderName.isNullOrBlank()) {
                Text(
                    text = senderName,
                    modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 视觉识别失败警告：当消息内容包含 VISION_FAILURE_MARKER 时显示非阻塞提示
            if (message.displayContent.contains(VISION_FAILURE_MARKER)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.chat_vision_failure),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // 多段气泡：每段一个气泡，段间小间距
            segments.forEachIndexed { idx, segment ->
                val isFirst = idx == 0
                val isLast = idx == segments.lastIndex
                // 解析多媒体内容段
                val contentSegments = parsedSegments[idx]
                val segHasMultimedia = contentSegments.any { it.type != SegmentType.TEXT }
                val segHasUserImage = isUser && contentSegments.any { it.isImageContent() }
                // 气泡形状：主圆角 20dp，连续多段时中间段一侧收小形成连贯“气泡链”
                val segShape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isUser) 20.dp else if (isLast) 20.dp else 6.dp,
                    bottomEnd = if (isUser) if (isLast) 6.dp else 20.dp else 20.dp
                )
                Box(
                    modifier = Modifier
                        .then(if (!isUser && fillAiWidth) Modifier.fillMaxWidth() else Modifier)
                        // 用户气泡带轻微投影，浮于背景之上
                        .then(if (isUser && !segHasUserImage) Modifier.shadow(2.dp, segShape, clip = false) else Modifier)
                        .then(
                            when {
                                isUser && !segHasUserImage ->
                                    Modifier.background(brush = userBrush, shape = segShape)
                                !isUser ->
                                    Modifier.background(color = aiContainer, shape = segShape)
                                else -> Modifier
                            }
                        )
                        .then(
                            when {
                                isSelected -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, segShape)
                                !isUser -> Modifier.border(1.dp, aiBorder, segShape)
                                else -> Modifier
                            }
                        )
                        .combinedClickable(
                            onClick = { if (selectionMode) onToggleSelection() },
                            onLongClick = onLongClick
                        )
                        .then(
                            if (segHasUserImage) Modifier
                            else Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                ) {
                    if (segHasUserImage) {
                        // 图片保持独立展示；同一条消息里的文字仍使用用户气泡，避免图文同发时文字裸露。
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            groupUserMessageContent(contentSegments).forEach { group ->
                                if (group.firstOrNull()?.isImageContent() == true) {
                                    RenderContentSegments(
                                        segments = group,
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth(),
                                        sessionId = sessionId
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = maxBubbleWidth)
                                            .shadow(2.dp, segShape, clip = false)
                                            .background(brush = userBrush, shape = segShape)
                                            .padding(horizontal = 14.dp, vertical = 9.dp)
                                    ) {
                                        RenderContentSegments(
                                            segments = group,
                                            textColor = textColor,
                                            modifier = Modifier.widthIn(max = maxBubbleWidth),
                                            sessionId = sessionId
                                        )
                                    }
                                }
                            }
                        }
                    } else if (segHasMultimedia) {
                        // 多媒体内容：用渲染器渲染，宽度可超出普通文本宽度
                        RenderContentSegments(
                            segments = contentSegments,
                            textColor = textColor,
                            modifier = Modifier.widthIn(max = 360.dp),
                            sessionId = sessionId
                        )
                    } else {
                        // 文本内容：用 Markdown 渲染
                        // 用户气泡：宽度跟随实际内容（短消息不撑满）；AI 气泡：填满最大宽度
                        MarkdownText(
                            text = segment,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            chatMode = true,
                            processParens = !isUser,
                            modifier = if (isUser) Modifier.widthIn(max = maxBubbleWidth) else Modifier.fillMaxWidth()
                        )
                    }
                }
                if (!isLast) Spacer(Modifier.height(10.dp))
            }

            // 流式生成中：气泡下方追加打字圆点，提示仍在输出
            if (isStreamingPlaceholder) {
                Spacer(Modifier.height(6.dp))
                TypingDots(modifier = Modifier.padding(start = 6.dp))
            }

            if (!isUser && ttsState != null && ttsState.status != MessageTtsStatus.Ready) {
                Spacer(Modifier.height(6.dp))
                TtsGenerationBar(
                    state = ttsState,
                    onRetry = onRegenerateTts,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }

            // 如果有音频 URL，追加音频播放器
            if (hasAudioUrl) {
                val resolvedAudioUrl = resolveAvatarUrl(message.audioUrl) ?: message.audioUrl!!
                Spacer(Modifier.height(6.dp))
                AudioRenderer(
                    url = resolvedAudioUrl,
                    onRegenerate = onRegenerateTts,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }

            // 元信息：时间（精简到分钟）/ token 数 + 操作按钮，AI 气泡合并到同一行
            val compactTs = compactTime(message.timestamp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compactTs != null) {
                        Text(compactTs, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    message.tokens?.let { tokens ->
                        if (compactTs != null) Spacer(Modifier.width(6.dp))
                        Text("${tokens} tok", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 用户气泡：复制按钮放最右边；AI 气泡：三个操作按钮放最右边
                if (!selectionMode) {
                    if (isUser) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconActionButton(
                                icon = Icons.Filled.ContentCopy,
                                description = stringResource(R.string.common_copy),
                                onClick = {
                                    val text = onCopy()
                                    clipboard.setText(AnnotatedString(text))
                                }
                            )
                            onDelete?.let { delete ->
                                Spacer(Modifier.width(4.dp))
                                IconActionButton(
                                    icon = Icons.Filled.Delete,
                                    description = stringResource(R.string.common_delete),
                                    onClick = delete
                                )
                            }
                        }
                    } else {
                        BubbleActions(
                            isUser = isUser,
                            showGenerationActions = !isLocalCommand,
                            onRegenerate = onRegenerate,
                            onFork = onFork,
                            onCopy = {
                                val text = onCopy()
                                clipboard.setText(AnnotatedString(text))
                            },
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
        // 多选模式：用户气泡右侧显示勾选框
        if (selectionMode && isUser) {
            Spacer(Modifier.width(4.dp))
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/** 气泡下方的小操作按钮行：低对比度、小图标。 */
@Composable
private fun BubbleActions(
    isUser: Boolean,
    showGenerationActions: Boolean = true,
    onRegenerate: () -> Unit,
    onFork: () -> Unit,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Row(
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isUser && showGenerationActions) {
            // 重新生成
            IconActionButton(icon = Icons.Filled.Refresh, description = stringResource(R.string.chat_regenerate), onClick = onRegenerate)
            Spacer(Modifier.width(4.dp))
            // 分支
            IconActionButton(icon = Icons.Outlined.AccountTree, description = stringResource(R.string.chat_fork), onClick = onFork)
            Spacer(Modifier.width(4.dp))
        }
        // 复制
        IconActionButton(icon = Icons.Filled.ContentCopy, description = stringResource(R.string.common_copy), onClick = onCopy)
        onDelete?.let { delete ->
            Spacer(Modifier.width(4.dp))
            IconActionButton(
                icon = Icons.Filled.Delete,
                description = stringResource(R.string.common_delete),
                onClick = delete
            )
        }
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

/**
 * Agent 模式进度卡片：磨砂玻璃风格，展示 AI 处理过程的步骤与状态。
 *
 * 视觉参考原仓库 Web 端 thinking-card：
 * - 半透明渐变背景模拟玻璃质感
 * - 头部：旋转图标（未完成）/ 勾选图标（完成）+ 头部文本
 * - 步骤列表：每项显示图标 + 名称 + 状态色 + 详情摘要（折叠/展开）
 *
 * @param card 进度卡片数据（含头部文本、步骤列表、完成状态）
 */
@Composable
private fun ProgressCard(
    card: com.nekobot.app.data.model.ThinkingCard,
    sessionId: String,
    expanded: Boolean,
    showBrowserPreview: Boolean = false,
    onExpandedChange: (Boolean) -> Unit,
    onStepClick: (com.nekobot.app.data.model.ThinkingStep) -> Unit = {}
) {
    val progress = card.progress?.coerceIn(0, 100)
    val hasError = card.steps.any { it.status.equals("error", ignoreCase = true) }
    val statusColor = if (hasError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        borderColor = if (hasError) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.48f)
        } else if (card.isComplete) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 头部：图标 + 内容文本 + 展开开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasError) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
            } else if (card.isComplete) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                // CircularProgressIndicator 自带旋转动画
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = card.content.stripEmoji().ifBlank { "AI 正在处理..." },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (progress != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }
            if (card.steps.isNotEmpty()) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (showBrowserPreview) {
            Spacer(Modifier.height(10.dp))
            LocalBrowserPreview(sessionId = sessionId)
        }

        // 步骤列表（可折叠）
        if (expanded && card.steps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.steps.forEach { step ->
                    ProgressStepRow(step, onStepClick = onStepClick)
                }
            }
        }
    }
}

/**
 * Agent 卡片默认折叠、本地命令卡片默认展开；一旦用户操作过，则始终以页面级覆盖值为准。
 */
internal fun resolveProgressCardExpanded(
    cardId: String,
    isAgent: Boolean,
    expansionOverrides: Map<String, Boolean>
): Boolean = expansionOverrides[cardId] ?: !isAgent

/** 进度卡片单步渲染：Material Icon + 名称 + 状态色 + 详情摘要，含详情时可点击。 */
@Composable
private fun ProgressStepRow(
    step: com.nekobot.app.data.model.ThinkingStep,
    onStepClick: (com.nekobot.app.data.model.ThinkingStep) -> Unit = {}
) {
    val statusColor = when (step.status?.lowercase()) {
        "done" -> MaterialTheme.colorScheme.primary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // step.type 映射到 Material Icon（不使用 emoji）
    val iconVector = when (step.type?.lowercase()) {
        "thinking" -> Icons.Filled.Psychology
        "tool", "tool_done" -> Icons.Filled.Build
        "upload" -> Icons.Filled.Upload
        "knowledge" -> Icons.Filled.MenuBook
        "done" -> Icons.Filled.TaskAlt
        else -> Icons.Filled.Circle
    }
    val iconSize = if (step.type?.lowercase() == "done") 14.dp else 16.dp
    val name = step.name?.stripEmoji()?.takeIf { it.isNotBlank() } ?: "步骤"
    val detail = step.detail?.stripEmoji()?.takeIf { it.isNotBlank() }
    // 含任一详情字段时可点击查看详情（对齐原仓库 has-detail 判定）
    val hasDetail = step.arguments != null || step.fullResult != null || !step.thinkingContent.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasDetail) Modifier.clickable { onStepClick(step) } else Modifier),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (step.status == "running" || step.status == "active") {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    color = statusColor,
                    modifier = Modifier.size(iconSize)
                )
            } else {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (hasDetail) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 进度卡片步骤详情弹窗：展示 AI 思考内容 / 工具参数 / 返回结果。
 *
 * 对齐原仓库 Web 端 stepDetailModal：按字段存在与否展示对应区块，
 * 无详情时显示"该步骤没有详细信息"占位。
 *
 * @param step 步骤数据
 * @param onDismiss 关闭回调
 */
@Composable
private fun StepDetailDialog(
    step: com.nekobot.app.data.model.ThinkingStep,
    onDismiss: () -> Unit
) {
    val name = step.name?.stripEmoji()?.takeIf { it.isNotBlank() } ?: "步骤详情"
    val detail = step.detail?.stripEmoji()?.takeIf { it.isNotBlank() }
    val thinkingContent = step.thinkingContent?.stripEmoji()?.takeIf { it.isNotBlank() }
    val argumentsJson = step.arguments?.let { formatJson(it) }
    val fullResultJson = step.fullResult?.let { formatJson(it) }
    val hasAny = detail != null || thinkingContent != null ||
        !argumentsJson.isNullOrBlank() || !fullResultJson.isNullOrBlank()

    NekoDialog(
        onDismiss = onDismiss,
        title = name,
        confirmText = stringResource(R.string.common_close),
        onConfirm = null,
        cancelText = null,
        onCancel = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (!hasAny) {
                Text(
                    text = "该步骤没有详细信息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!detail.isNullOrBlank()) {
                        StepDetailSection(
                            label = "描述",
                            content = detail
                        )
                    }
                    if (!thinkingContent.isNullOrBlank()) {
                        StepDetailSection(
                            label = "AI 思考过程",
                            icon = Icons.Filled.Psychology,
                            content = thinkingContent,
                            accent = true
                        )
                    }
                    if (!argumentsJson.isNullOrBlank()) {
                        StepDetailSection(
                            label = "参数 (Arguments)",
                            icon = Icons.Filled.Key,
                            content = argumentsJson,
                            isCode = true
                        )
                    }
                    if (!fullResultJson.isNullOrBlank()) {
                        StepDetailSection(
                            label = "返回结果 (Result)",
                            icon = Icons.Filled.CheckCircle,
                            content = fullResultJson,
                            isCode = true
                        )
                    }
                }
            }
        }
    }
}

/** 步骤详情区块：Icon + 标题 + 内容（普通/代码块/强调样式）。 */
@Composable
private fun StepDetailSection(
    label: String,
    content: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isCode: Boolean = false,
    accent: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (accent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (accent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = content,
                style = if (isCode) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

/**
 * Hook 触发通知卡片：磨砂玻璃风格 + 琥珀描边，仿"成就解锁"提示。
 *
 * 设计参考原仓库 web 端 `.hook-notif-card`：
 * - 36dp 渐变图标（琥珀 → 橙）+ 主消息文本 + 副事件标签 + 关闭按钮
 * - 半透明背景 + 琥珀色描边 + 柔和阴影
 * - 整体宽度自适应，最大 380dp
 *
 * @param notification 通知数据（displayMessage 为主文案，hookName/eventType 为副文案）
 * @param onDismiss 用户点击关闭按钮回调
 */
@Composable
private fun HookNotificationCard(
    notification: com.nekobot.app.data.remote.HookNotification,
    onDismiss: () -> Unit
) {
    // 状态色：success=琥珀，partial=橙，failed=红
    val accentColor = when (notification.status.lowercase()) {
        "failed" -> Color(0xFFEF4444)
        "partial" -> Color(0xFFF97316)
        else -> Color(0xFFF59E0B)
    }
    val accentColorLight = Color(0xFFFBBF24)

    val titleText = stringResource(R.string.chat_hook_notif_title)
    val mainText = notification.displayMessage.ifBlank { notification.hookName.ifBlank { titleText } }
    val eventText = buildString {
        if (notification.hookName.isNotBlank()) append(notification.hookName)
        if (notification.eventType.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(notification.eventType)
        }
        if (isEmpty()) append(stringResource(R.string.chat_hook_notif_event_label))
    }

    Box(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = accentColor.copy(alpha = 0.18f),
                spotColor = accentColor.copy(alpha = 0.25f)
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.55f),
                        accentColor.copy(alpha = 0.12f)
                    )
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 图标徽章：渐变琥珀 → 橙
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(accentColor, accentColorLight)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = titleText,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            // 正文：主消息 + 副事件标签
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mainText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (eventText.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = eventText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_hook_notif_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 格式化 JSON 字符串（任意对象转 pretty JSON）。 */
private fun formatJson(value: Any): String {
    return try {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val element = gson.toJsonTree(value)
        gson.toJson(element)
    } catch (e: Exception) {
        value.toString()
    }
}

/**
 * 剥离文本中的 emoji 字符与常见 emoji 前缀（如 "🔄 AI 正在处理..." → "AI 正在处理..."）。
 *
 * 后端 progress_card.py 的 STEP_CONFIG 使用 emoji 图标（🤔💭🧠🔧🖼️📄📤📚✅），
 * content 字段也含 emoji 前缀（🔄/✅）。Android 端已改用 Material Icon 渲染，
 * 文本字段需剥离 emoji，避免与 Icon 并列显示。
 */
private fun String.stripEmoji(): String {
    if (isBlank()) return this
    // BMP 内符号区间 + 所有补充平面字符（代理对，覆盖 emoji 主平面 U+1F000-U+1FAFF 等）
    val emojiRegex = Regex(
        "[" +
        "\u2600-\u27BF" +     // 杂项符号与装饰符号（✅✨ etc.）
        "\u2B00-\u2BFF" +     // 其他符号（⬆ etc.）
        "\uFE00-\uFE0F" +     // variation selector
        "\u200D" +            // ZWJ
        "\u20E3" +            // combining enclosing keycap
        "]|" +
        "[\uD800-\uDBFF][\uDC00-\uDFFF]"  // 所有代理对（补充平面字符，含所有 emoji）
    )
    var stripped = emojiRegex.replace(this, "")
    // 折叠多余空格与首尾空白，处理 "AI 正在处理... (1/50)" 前缀被剥离后的残余空格
    stripped = stripped.replace(Regex("\\s{2,}"), " ").trim()
    return stripped
}

/**
 * 背景设定卡片：固定在聊天列表顶部，可折叠展示会话绑定角色卡的「背景设定」(scenario)。
 * 折叠时仅显示标题与首行预览，展开后用 MarkdownText（非 chatMode）渲染完整内容。
 */
@Composable
private fun BackgroundSettingCard(content: String?) {
    if (content.isNullOrBlank()) return
    var expanded by remember { mutableStateOf(false) }
    // 预览取首个非空行，截断到 60 字符
    val preview = remember(content) {
        content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_background_setting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!expanded && preview.isNotEmpty()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_collapse) else stringResource(R.string.chat_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 1.dp
                    )
                    Spacer(Modifier.height(10.dp))
                    MarkdownText(
                        text = content,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        chatMode = false
                    )
                }
            }
        }
    }
}

/** AI 思考中状态：AI 气泡内的打字圆点动画（等待第一个流式 chunk）。 */
@Composable
private fun ThinkingIndicator(
    portraitUrl: String? = null,
    showAiAvatar: Boolean = true,
    fillAiWidth: Boolean = false,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.SmartToy
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (showAiAvatar) {
            ChatAvatar(portraitUrl = portraitUrl, size = 34.dp, ring = true, fallbackIcon = fallbackIcon)
            Spacer(Modifier.width(8.dp))
        }
        val shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 6.dp,
            bottomEnd = 20.dp
        )
        Box(
            modifier = Modifier
                .then(if (fillAiWidth) Modifier.fillMaxWidth() else Modifier)
                .background(color = aiBubbleContainerColor(), shape = shape)
                .border(1.dp, aiBubbleBorderColor(), shape)
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            TypingDots()
        }
    }
}

/** 打字圆点：三个圆点错峰呼吸，用于思考占位与流式输出提示。 */
@Composable
private fun TypingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val transition = rememberInfiniteTransition(label = "typing_dots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 180)
                ),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha))
            )
        }
    }
}

/** 角色头像：圆形立绘，可带主题色光环；加载中/失败回退到机器人图标。 */
@Composable
private fun ChatAvatar(
    portraitUrl: String?,
    size: androidx.compose.ui.unit.Dp,
    ring: Boolean = false,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.SmartToy
) {
    val resolved = resolveAvatarUrl(portraitUrl)
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (ring) Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = CircleShape
                ) else Modifier
            )
            .padding(if (ring) 2.dp else 0.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(
                fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.55f)
            )
        }
        if (!resolved.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(resolved)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.chat_character_portrait),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { fallback() },
                error = { fallback() }
            )
        } else {
            fallback()
        }
    }
}

/** AI 气泡容器色：深色为半透明玻璃质感，浅色为纯白卡片。 */
@Composable
private fun aiBubbleContainerColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    else MaterialTheme.colorScheme.surface

/** AI 气泡发丝边框色。 */
@Composable
private fun aiBubbleBorderColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.08f else 0.05f)

/** 将颜色调深（降低明度、略提饱和度），用于用户气泡渐变末端。 */
private fun deepenColor(color: Color, factor: Float = 0.78f): Color {
    val argb = color.toArgb()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        hsv
    )
    hsv[1] = (hsv[1] * 1.06f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 日期分隔条：居中胶囊样式，在跨天消息之间插入。 */
@Composable
private fun DateSeparatorChip(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 提取消息时间所属的日期键（yyyy-MM-dd，按手机时区），无法解析返回 null。 */
private fun dayKey(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    return try {
        when {
            // 纯数字时间戳（秒/毫秒）
            s.matches(Regex("^\\d{10,13}$")) -> {
                val ms = if (s.length == 10) s.toLong() * 1000 else s.toLong()
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(ms))
            }
            // ISO 8601 带时区标识（Z 或 +HH:MM[:SS]）：按手机时区转换日期
            s.contains('T') && (s.endsWith('Z') || s.contains(Regex("[+\\-]\\d{2}:?\\d{2}"))) -> {
                val instant = parseIsoInstant(s) ?: return null
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(instant.toEpochMilli()))
            }
            // ISO 或 “日期 时间” 格式（无时区标识）：取前 10 位日期（视为本地时区）
            s.length >= 10 && s.substring(0, 10).matches(Regex("\\d{4}-\\d{2}-\\d{2}")) ->
                s.substring(0, 10)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** 把日期键（yyyy-MM-dd）转为本地化分隔标签：今天 / 昨天 / M月d日 / yyyy年M月d日。 */
private fun dayLabel(day: String): String {
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val date = fmt.parse(day) ?: return day
        val now = java.util.Calendar.getInstance()
        if (day == fmt.format(now.time)) return ServiceContainer.getString(R.string.chat_today)
        now.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (day == fmt.format(now.time)) return ServiceContainer.getString(R.string.chat_yesterday)
        val cal = java.util.Calendar.getInstance().apply { time = date }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val dom = cal.get(java.util.Calendar.DAY_OF_MONTH)
        if (year == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) {
            ServiceContainer.localizedContext?.getString(R.string.chat_month_day, month, dom) ?: day
        } else {
            ServiceContainer.localizedContext?.getString(R.string.chat_year_month_day, year, month, dom) ?: day
        }
    } catch (e: Exception) {
        day
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
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
    pendingChoiceId: String?,
    onSelect: (PlotChoice) -> Unit,
    onRegenerate: () -> Unit,
    enabled: Boolean = true,
    layoutMode: ChatInputLayoutMode,
    inputVisible: Boolean,
    panelExpanded: Boolean,
    sending: Boolean,
    onToggleInput: () -> Unit,
    onTogglePanel: () -> Unit,
    onToggleLayout: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PlotChoicesHeader(
            title = stringResource(R.string.chat_plot_choices),
            layoutMode = layoutMode,
            inputVisible = inputVisible,
            panelExpanded = panelExpanded,
            sending = sending,
            refreshEnabled = enabled,
            onToggleInput = onToggleInput,
            onTogglePanel = onTogglePanel,
            onToggleLayout = onToggleLayout,
            onRegenerate = onRegenerate,
            onStop = onStop
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            choices.forEach { choice ->
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
                    containerColor = if (choice.id == pendingChoiceId || choice.selected) {
                        levelColor.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 6.dp
                    )
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
                                "turning_point" -> stringResource(R.string.chat_plot_turning)
                                "important" -> stringResource(R.string.chat_plot_important)
                                else -> stringResource(R.string.chat_plot_normal)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = levelColor,
                            fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = choice.title.ifBlank { stringResource(R.string.chat_plot_choice) },
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

@Composable
private fun PlotChoicesHeader(
    title: String,
    layoutMode: ChatInputLayoutMode,
    inputVisible: Boolean,
    panelExpanded: Boolean,
    sending: Boolean,
    refreshEnabled: Boolean,
    onToggleInput: () -> Unit,
    onTogglePanel: () -> Unit,
    onToggleLayout: () -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (layoutMode == ChatInputLayoutMode.MERGED) {
            IconButton(onClick = onTogglePanel, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (panelExpanded) Icons.Filled.MoreVert else Icons.Filled.Add,
                    contentDescription = if (panelExpanded) stringResource(R.string.chat_collapse_actions) else stringResource(R.string.chat_expand_actions),
                    tint = if (panelExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onToggleInput, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (inputVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                    contentDescription = if (inputVisible) stringResource(R.string.chat_hide_input) else stringResource(R.string.chat_show_input),
                    tint = if (inputVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onToggleLayout, modifier = Modifier.size(36.dp)) {
            Icon(
                if (layoutMode == ChatInputLayoutMode.MERGED) Icons.Filled.VerticalSplit else Icons.Filled.ViewAgenda,
                contentDescription = if (layoutMode == ChatInputLayoutMode.MERGED) stringResource(R.string.chat_switch_separated) else stringResource(R.string.chat_switch_merged),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (sending) {
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            IconButton(onClick = onRegenerate, enabled = refreshEnabled, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.chat_regenerate_group), modifier = Modifier.size(20.dp))
            }
        }
    }
}

internal fun shouldShowChatInput(
    layoutMode: ChatInputLayoutMode,
    inputExpanded: Boolean,
    hasPlotSurface: Boolean,
    hasDraft: Boolean
): Boolean = layoutMode == ChatInputLayoutMode.SEPARATE ||
    !hasPlotSurface || inputExpanded || hasDraft

/**
 * 结束流式消息时整理 UI 列表。
 *
 * 本地模式在 StreamEnd 前已写入 Room，不创建临时正式消息；服务器模式保留一条兜底消息，
 * 等服务端持久化结果刷新后再用真实 ID 替换。
 */
internal fun finalizeStreamEndMessages(
    current: List<Message>,
    streamingId: String,
    finalContent: String,
    materializeFallback: Boolean,
    fallbackId: String = ChatViewModel.STREAM_FALLBACK_PREFIX + java.util.UUID.randomUUID(),
    fallbackTimestamp: String = System.currentTimeMillis().toString()
): List<Message> {
    val withoutPlaceholder = current.filter { it.id != streamingId }
    if (!materializeFallback || finalContent.isBlank()) return withoutPlaceholder
    if (withoutPlaceholder.any { !it.isUser && it.content == finalContent }) return withoutPlaceholder
    return withoutPlaceholder + Message(
        id = fallbackId,
        role = "assistant",
        content = finalContent,
        timestamp = fallbackTimestamp
    )
}

/**
 * 合并 Socket `new_message`：
 * - WebUI 发出的用户消息直接追加到安卓当前会话；
 * - 安卓自身发送的消息用服务端正式 id 替换乐观消息；
 * - AI 消息移除流式占位并按 id 去重。
 */
internal fun mergeRealtimeNewMessage(
    current: List<Message>,
    incoming: Message,
    isSending: Boolean,
    streamingId: String = ChatViewModel.STREAMING_ID,
    fallbackPrefix: String = ChatViewModel.STREAM_FALLBACK_PREFIX
): List<Message> {
    val incomingId = incoming.id
    if (!incomingId.isNullOrBlank()) {
        val existingIndex = current.indexOfFirst { it.id == incomingId }
        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            return current.toMutableList().apply {
                this[existingIndex] = incoming.copy(
                    thinkingCards = incoming.thinkingCards ?: existing.thinkingCards,
                    audioUrl = incoming.audioUrl ?: existing.audioUrl
                )
            }
        }
    }

    if (incoming.isUser) {
        val optimisticIndex = if (isSending) {
            current.indexOfLast {
                it.isUser &&
                    it.id.isNullOrBlank() &&
                    it.content == incoming.content
            }
        } else {
            -1
        }
        if (optimisticIndex >= 0) {
            val optimistic = current[optimisticIndex]
            return current.toMutableList().apply {
                this[optimisticIndex] = incoming.copy(
                    thinkingCards = incoming.thinkingCards ?: optimistic.thinkingCards
                )
            }
        }
        return current + incoming
    }

    return current.filter {
        it.id != streamingId &&
            !(it.id?.startsWith(fallbackPrefix) == true && it.content == incoming.content)
    } + incoming
}

/**
 * 把时间戳/时间字符串精简到「分钟」级，尽量短以节省气泡下方空间。
 * 支持毫秒时间戳、ISO 8601（含时区 Z/+HH:MM）、已格式化字符串三种输入。
 * 自动按手机时区显示：例 2026-07-10T06:30:45.123Z（UTC）在 UTC+8 设备上 → "14:30"
 */
internal fun compactTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim()
    return try {
        when {
            // 纯数字时间戳（秒/毫秒）
            s.matches(Regex("^\\d{10,13}$")) -> {
                val ms = if (s.length == 10) s.toLong() * 1000 else s.toLong()
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(ms))
            }
            // ISO 8601 带时区标识（Z 或 +HH:MM[:SS]）：按手机时区转换
            s.contains('T') && (s.endsWith('Z') || s.contains(Regex("[+\\-]\\d{2}:?\\d{2}"))) -> {
                val instant = parseIsoInstant(s) ?: return null
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(instant.toEpochMilli()))
            }
            // ISO 或带 T 的时间（无时区标识）：直接提取 HH:mm（视为本地时区）
            s.contains('T') -> {
                val timePart = s.substringAfter('T').take(5)
                if (timePart.matches(Regex("\\d{2}:\\d{2}"))) timePart else null
            }
            // 含空格分隔日期时间：取时间部分前 5 位（视为本地时区）
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

/** 解析 ISO 8601 字符串为 java.time.Instant，支持 Z 后缀与 ±HH:MM[:SS] 偏移。 */
private fun parseIsoInstant(s: String): java.time.Instant? {
    return try {
        // Instant.parse 仅支持 Z 后缀；偏移格式用 OffsetDateTime 解析
        if (s.endsWith('Z')) {
            java.time.Instant.parse(s)
        } else {
            // 尝试解析带偏移的 ISO 字符串
            java.time.OffsetDateTime.parse(s).toInstant()
        }
    } catch (_: Exception) {
        // 兜底：尝试 LocalDateTime + 系统默认时区（虽然此分支理论上不应触达）
        try {
            java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant()
        } catch (_: Exception) {
            null
        }
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
    pendingPlotChoiceId: String? = null,
    layoutMode: ChatInputLayoutMode = ChatInputLayoutMode.MERGED,
    onLayoutModeChange: (ChatInputLayoutMode) -> Unit = {},
    onSelectPlotChoice: (PlotChoice) -> Unit = {},
    onRegeneratePlotChoices: () -> Unit = {},
    onScrollToBottom: () -> Unit = {},
    onShowMyMessages: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onCompress: () -> Unit,
    onSendFile: () -> Unit = {},
    onUploadToWorkspace: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onVoiceInput: () -> Unit = {},
    onShowFavorites: () -> Unit = {},
    onShowSearch: () -> Unit = {},
    fileBusy: Boolean = false
) {
    var panelExpanded by remember { mutableStateOf(false) }
    var inputExpanded by remember { mutableStateOf(false) }
    // 字符数与 token 估算：中文字符约 1 token/字，英文约 0.25 token/字符
    val charCount = input.length
    val chineseCount = input.count { it.code in 0x4E00..0x9FFF }
    val otherCount = charCount - chineseCount
    val tokenEstimate = (chineseCount + otherCount / 4).coerceAtLeast(if (charCount > 0) 1 else 0)
    val hasPlotSurface = plotChoicesLoading || plotChoices.isNotEmpty()
    val inputVisible = shouldShowChatInput(
        layoutMode = layoutMode,
        inputExpanded = inputExpanded,
        hasPlotSurface = hasPlotSurface,
        hasDraft = input.isNotBlank()
    )
    val toggleInput = {
        if (input.isBlank()) inputExpanded = !inputVisible
    }
    val toggleLayout = {
        onLayoutModeChange(
            if (layoutMode == ChatInputLayoutMode.MERGED) ChatInputLayoutMode.SEPARATE
            else ChatInputLayoutMode.MERGED
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 剧情模式选项（输入框上方，最多展示 3 个）
        if (plotChoicesLoading) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                PlotChoicesHeader(
                    title = stringResource(R.string.chat_plot_choices_loading),
                    layoutMode = layoutMode,
                    inputVisible = inputVisible,
                    panelExpanded = panelExpanded,
                    sending = sending,
                    refreshEnabled = false,
                    onToggleInput = toggleInput,
                    onTogglePanel = { panelExpanded = !panelExpanded },
                    onToggleLayout = toggleLayout,
                    onRegenerate = onRegeneratePlotChoices,
                    onStop = onStop
                )
            }
            PlotChoicesSkeleton()
        } else if (plotChoices.isNotEmpty()) {
            PlotChoicesBar(
                choices = plotChoices.take(3),
                pendingChoiceId = pendingPlotChoiceId,
                onSelect = {
                    inputExpanded = true
                    onSelectPlotChoice(it)
                },
                onRegenerate = onRegeneratePlotChoices,
                enabled = !sending,
                layoutMode = layoutMode,
                inputVisible = inputVisible,
                panelExpanded = panelExpanded,
                sending = sending,
                onToggleInput = toggleInput,
                onTogglePanel = { panelExpanded = !panelExpanded },
                onToggleLayout = toggleLayout,
                onStop = onStop
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
                        Text(stringResource(R.string.chat_context), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.chat_context_count, messageCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    // 操作按钮网格（每行 2 个，按钮更大）
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.KeyboardDoubleArrowDown,
                            label = stringResource(R.string.chat_scroll_to_bottom),
                            enabled = messageCount > 0,
                            onClick = onScrollToBottom,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            icon = Icons.Outlined.AccountTree,
                            label = stringResource(R.string.chat_my_messages),
                            enabled = messageCount > 0,
                            onClick = onShowMyMessages,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.Compress,
                            label = stringResource(R.string.chat_compress_context),
                            enabled = !sending,
                            onClick = onCompress,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            icon = Icons.Filled.CleaningServices,
                            label = stringResource(R.string.chat_clear_messages),
                            enabled = !sending,
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 工作区操作：发送文件 / 上传到工作区
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.AttachFile,
                            label = stringResource(R.string.chat_send_file),
                            enabled = !fileBusy,
                            onClick = onSendFile,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            icon = Icons.Filled.CloudUpload,
                            label = stringResource(R.string.chat_upload_workspace),
                            enabled = !fileBusy,
                            onClick = onUploadToWorkspace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 查看工作区（整行）
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.Folder,
                            label = stringResource(R.string.chat_view_workspace),
                            enabled = true,
                            onClick = onOpenWorkspace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 收藏夹 & 搜索对话
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(
                            icon = Icons.Filled.Star,
                            label = stringResource(R.string.chat_favorites),
                            enabled = true,
                            onClick = onShowFavorites,
                            modifier = Modifier.weight(1f)
                        )
                        ActionChip(
                            icon = Icons.Filled.Search,
                            label = stringResource(R.string.chat_search_dialog),
                            enabled = messageCount > 0,
                            onClick = onShowSearch,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = inputVisible) {
            Column {
                if (input.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            stringResource(R.string.chat_draft_stats, charCount, tokenEstimate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                            contentDescription = stringResource(R.string.chat_more_actions),
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
                        placeholder = { Text(if (sending) stringResource(R.string.chat_ai_thinking) else stringResource(R.string.chat_input_placeholder)) },
                        enabled = !sending,
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    val showSend = sending || input.isNotBlank()
                    val primaryAction: () -> Unit = when {
                        sending -> onStop
                        input.isNotBlank() -> {{ inputExpanded = false; onSend() }}
                        else -> onVoiceInput
                    }
                    IconButton(
                        onClick = primaryAction,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (sending) Color(0xFFFF6B6B)
                                else if (showSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {
                        if (sending) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop), tint = Color.White, modifier = Modifier.size(22.dp))
                        } else if (input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send), tint = Color.White, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.chat_voice_input), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                    }
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

/** 从 Uri 读取文件名与字节数组，用于上传到工作区。 */
private fun readUriFile(context: android.content.Context, uri: android.net.Uri): Pair<String, ByteArray>? {
    return try {
        val name = queryFileName(context, uri) ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        name to bytes
    } catch (e: Exception) {
        null
    }
}

private fun queryFileName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
        }
    } catch (e: Exception) {
        uri.lastPathSegment
    }
}

private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
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
        else -> "application/octet-stream"
    }
}

internal fun isAutoGeneratedTitlePending(name: String?): Boolean {
    val normalized = name?.trim().orEmpty()
    if (normalized.isEmpty()) return true
    val defaultPrefixes = listOf(
        "新会话", "新对话", "Web 会话", "Agent 会话", "群聊",
        "New session", "New conversation", "Web session", "Agent chat", "Group chat",
        "新しい会話", "新規会話", "エージェント会話", "グループ会話",
        "새 대화", "새 세션", "에이전트 대화", "그룹 대화"
    )
    return defaultPrefixes.any { normalized.startsWith(it, ignoreCase = true) } ||
        normalized.endsWith("的对话")
}

internal fun buildChatMessageContent(
    text: String,
    attachments: List<Map<String, Any>>
): String {
    val normalizedText = text.trim()
    val fileReferences = attachments
        .mapNotNull { attachment ->
            ((attachment["name"] as? String) ?: (attachment["filename"] as? String))
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .map { name -> "[File: $name]" }
        .filterNot(normalizedText::contains)
    return buildList {
        normalizedText.takeIf { it.isNotEmpty() }?.let(::add)
        addAll(fileReferences)
    }.joinToString("\n")
}

/**
 * 将实时进度卡片挂到父用户消息。
 *
 * 本地发送先插入没有数据库 id 的乐观用户消息，因此按 parentMessageId 找不到时，
 * 回退到最后一条用户消息；同一卡片的后续百分比更新会原位替换。
 */
internal fun attachThinkingCardToMessages(
    messages: List<Message>,
    card: com.nekobot.app.data.model.ThinkingCard
): List<Message> {
    val parentIndex = card.parentMessageId?.let { parentId ->
        messages.indexOfFirst { it.id == parentId && it.isUser }
    } ?: -1
    val targetIndex = if (parentIndex >= 0) {
        parentIndex
    } else {
        messages.indexOfLast { it.isUser }
    }
    if (targetIndex < 0) return messages

    val parent = messages[targetIndex]
    val existing = parent.thinkingCards.orEmpty()
    val updated = if (existing.any { it.id == card.id }) {
        existing.map { if (it.id == card.id) card else it }
    } else {
        existing + card
    }
    return messages.toMutableList().apply {
        set(targetIndex, parent.copy(thinkingCards = updated))
    }
}

internal fun shouldApplyThinkingCardUpdate(
    sessionMode: String?,
    isAgentCard: Boolean
): Boolean = isAgentCard || sessionMode.equals("agent", ignoreCase = true)

@Composable
private fun TtsGenerationBar(
    state: MessageTtsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 12,
        containerColor = when (state.status) {
            MessageTtsStatus.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.status == MessageTtsStatus.Generating) {
                val transition = rememberInfiniteTransition(label = "tts_generating")
                Row(
                    modifier = Modifier.height(22.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(7) { index ->
                        val scale by transition.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(420, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(index * 70)
                            ),
                            label = "tts_wave_$index"
                        )
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height((5f + scale * 15f).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "语音生成中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "语音生成失败",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    state.error?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "重新生成语音",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 与原仓库消息 TTS 相同的纯文本清洗：移除 Markdown 标记与代码，再限制为 2000 字。
 */
internal fun prepareTtsText(raw: String): String = raw
    .replace(Regex("```[\\s\\S]*?```"), "")
    .replace(Regex("`[^`]*`"), "")
    .replace(Regex("[#*_~>|\\-\\[\\]()!]"), "")
    .replace(Regex("\\n{2,}"), "\n")
    .trim()
    .take(2000)

/**
 * 对话页 ViewModel：管理消息、会话信息与发送状态。
 *
 * 服务器模式：通过 Socket.IO 接收 AI 的流式回复与消息推送。
 * 本地模式：通过 [UnifiedRepository.chatStream] 返回的 Flow 接收流式分片，不走 Socket。
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

    val execConfirmation: StateFlow<ExecConfirmationRequest?> = _runtime
        .map { it.execConfirmation }
        .distinctUntilChanged()
        .flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _execConfirmation: MutableStateFlow<ExecConfirmationRequest?> get() = runtime.execConfirmation

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
    private fun applyThinkingCardUpdate(card: com.nekobot.app.data.model.ThinkingCard) {
        _messages.value = attachThinkingCardToMessages(_messages.value, card)
    }

    // 多选模式状态
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()
    private val _selectedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedMessageIds: StateFlow<Set<String>> = _selectedMessageIds.asStateFlow()

    private var currentSessionId: String = ""

    /** 流式生成中的临时消息内容累加器（引用 runtime，跨 VM 共享） */
    private val streamingContent: StringBuilder get() = runtime.streamingContent
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
        // 获取（或创建）跨 VM 共享的运行时状态，引用计数 +1
        // 通过 _runtime.value 赋值使 Compose 的 flatMapLatest 自动切换到新 runtime
        _runtime.value = ChatSessionManager.acquire(sessionId)
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
        // 同时收集两路：
        // 1. hookExecutor.events → HookNotificationEvent
        // 2. localRepository.execConfirmationEvents → 高风险工具（删除角色卡等）的确认请求
        //    修复"删除角色卡卡住"：原实现把确认事件 emit 到 LocalPipelineCallbacks.eventChannel
        //    但 eventChannel 没人 collect，导致 requestAuthorization 的 runBlocking 永远等待。
        eventsJob = ServiceContainer.applicationScope.launch {
            kotlinx.coroutines.flow.merge(
                com.nekobot.app.ServiceContainer.localRepository.hookExecutor.events,
                com.nekobot.app.ServiceContainer.localRepository.execConfirmationEvents
                    .map { request -> RealtimeEvent.ExecConfirmationRequired(request) }
            ).collect { event -> handleRealtimeEvent(event) }
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
    private fun handleRealtimeEvent(event: RealtimeEvent) {
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
                    event is RealtimeEvent.ThinkingCardUpdate
                )
        ) {
            // 注意：保留 ExecConfirmationRequired 的处理，否则删除角色卡等高风险工具
            // 会因为旧 generation 已停止导致用户收不到确认弹窗 → 工具卡 10 分钟。
            return
        }
        when (event) {
            is RealtimeEvent.StreamStart -> {
                _sending.value = true
                streamingContent.setLength(0)
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
                    _messages.value = _messages.value.map {
                        if (it.id == streamingId) it.copy(content = streamingContent.toString())
                        else it
                    }
                }
            }
            is RealtimeEvent.StreamEnd -> {
                _sending.value = false
                // 本地流程在 StreamEnd 前已完成 Room 持久化，直接刷新数据库即可。
                // 若再生成随机 ID 的正式消息，刷新时会因时间戳不同同时保留两条相同气泡。
                val finalContent = streamingContent.toString()
                _messages.value = finalizeStreamEndMessages(
                    current = _messages.value,
                    streamingId = streamingId,
                    finalContent = finalContent,
                    materializeFallback = !isLocalMode
                )
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
            }
            is RealtimeEvent.PlotChoices -> {
                // 服务端推送新剧情选项，直接解析更新
                _plotChoices.value = parsePlotChoices(event.choices)
                _plotChoicesLoading.value = false
            }
            is RealtimeEvent.AiResponse -> {
                _sending.value = false
                _execConfirmation.value = null
                val msg = event.message
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
            }
            is RealtimeEvent.NewMessage -> {
                val msg = event.message
                // 过滤进度卡片（thinking_card），不展示在聊天列表
                if (msg.isThinkingCard) return
                _messages.value = mergeRealtimeNewMessage(
                    current = _messages.value,
                    incoming = msg,
                    isSending = _sending.value,
                    streamingId = streamingId
                )
                if (!msg.isUser) {
                    _sending.value = false
                    if (!isLocalMode) {
                        scheduleTtsForMessage(msg)
                    }
                }
            }
            is RealtimeEvent.Filtered -> {
                _sending.value = false
                showToast(event.message ?: string(R.string.chat_message_filtered))
            }
            is RealtimeEvent.Error -> {
                _sending.value = false
                showError(event.message)
            }
            is RealtimeEvent.Usage -> {
                // 本地模式 token 用量已由 LocalRepository 保存到消息，UI 无需额外处理
            }
            is RealtimeEvent.ExecConfirmationRequired -> {
                val request = event.request
                if (request.sessionId.isBlank() || request.sessionId == currentSessionId) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    _execConfirmation.value = request.copy(
                        sessionId = request.sessionId.ifBlank { currentSessionId }
                    )
                }
            }
            is RealtimeEvent.ExecConfirmationResolved -> {
                if (event.sessionId.isNullOrBlank() || event.sessionId == currentSessionId) {
                    _sending.value = false
                    _execConfirmation.value = null
                }
            }
            is RealtimeEvent.ThinkingCardUpdate -> {
                // 本地 Agent 的首张卡片可能早于 loadSession 返回；此时以卡片自身的 isAgent
                // 标记为准，不能因为 _session 暂时为空而丢掉整轮进度事件。
                if (shouldApplyThinkingCardUpdate(_session.value?.sessionMode, event.card.isAgent)) {
                    applyThinkingCardUpdate(event.card)
                    _sending.value = !event.card.isComplete
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
        launchResult(
            block = { unified.listMessages(currentSessionId) },
            onSuccess = { fresh ->
                // 合并：保留现有 thinking_cards，避免被刷新覆盖（对齐原仓库 nbot-methods.js:6221）
                val current = _messages.value
                val byId = current.associateBy { it.id }
                val byUserContent = current.filter { it.isUser }
                    .associateBy { it.content to it.timestamp }
                // 当前 UI 中非占位的 assistant 消息（按内容+时间戳匹配，避免刚生成的回复被 fresh 覆盖丢失）
                val currentAssistantByContent = current
                    .filter { !it.isUser && it.id != streamingId && !it.content.isNullOrBlank() }
                    .associateBy { it.content to it.timestamp }

                val merged = (fresh ?: emptyList()).filterNot { msg -> msg.isThinkingCard }.map { newMsg ->
                    // 历史加载的 thinking_cards 必定已完成（否则为数据不一致），
                    // 强制最后一张卡片 isComplete=true，避免重进会话还在转圈
                    val normalizedCards = newMsg.thinkingCards?.map { card ->
                        if (!card.isComplete) card.copy(isComplete = true) else card
                    }
                    val withCards = if (normalizedCards != null && normalizedCards != newMsg.thinkingCards) {
                        newMsg.copy(thinkingCards = normalizedCards)
                    } else newMsg

                    val existing = withCards.id?.let { byId[it] }
                    val mergedCard = if (existing?.thinkingCards != null && withCards.thinkingCards == null) {
                        withCards.copy(thinkingCards = existing.thinkingCards)
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
                    (msg.content to msg.timestamp) !in freshAssistantKeys &&
                        !(msg.id?.startsWith(STREAM_FALLBACK_PREFIX) == true &&
                            msg.content in freshAssistantContents)
                }

                val nextMessages = if (orphanAssistants.isEmpty()) merged else merged + orphanAssistants
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
        )
    }

    private data class ActiveTtsConfig(
        val modelId: String?,
        val voice: String,
        val speed: Float,
        val pitch: Float,
        val volume: Float
    )

    private fun activeTtsConfig(session: Session? = _session.value): ActiveTtsConfig? {
        val config = session?.ttsConfig
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val enabled = runCatching {
            config.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean
        }.getOrNull() == true
        if (!enabled) return null

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
    private suspend fun resolveLatestTtsConfig(sessionId: String): ActiveTtsConfig? {
        val latest = when (val result = unified.getSession(sessionId)) {
            is Resource.Success -> result.data
            else -> null
        }
        if (latest != null) {
            if (currentSessionId == sessionId) _session.value = latest
            return activeTtsConfig(latest)
        }
        return activeTtsConfig()
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
        excludedMessageIds: Set<String> = emptySet()
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

        val lookupKey = "_tts_lookup_${expectedContent.hashCode()}_${excludedMessageIds.hashCode()}"
        if (target.ttsJobs[lookupKey]?.isActive == true) return
        val lookupJob = ServiceContainer.applicationScope.launch(
            start = kotlinx.coroutines.CoroutineStart.LAZY
        ) {
            val config = resolveLatestTtsConfig(sessionId) ?: return@launch
            findCandidate(target.messages.value)?.let {
                startMessageTts(target, sessionId, it, config)
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
                    startMessageTts(target, sessionId, mergedCandidate, config)
                    return@launch
                }
                kotlinx.coroutines.delay(250)
            }
        }
        target.ttsJobs[lookupKey] = lookupJob
        lookupJob.invokeOnCompletion { target.ttsJobs.remove(lookupKey, lookupJob) }
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
        prepareJob.invokeOnCompletion { target.ttsJobs.remove(prepareKey, prepareJob) }
        prepareJob.start()
    }

    private fun startMessageTts(
        target: ChatSessionState,
        sessionId: String,
        message: Message,
        config: ActiveTtsConfig,
        force: Boolean = false
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
                waitForRemoteAutoTitle(sessionId)
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
                        is Resource.Loading -> throw IllegalStateException("TTS 合成未完成")
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
                                throw IllegalStateException(response.message ?: "TTS 服务未返回音频")
                            }
                            response.audioUrl
                        }
                        is Resource.Error -> throw IllegalStateException(result.message)
                        is Resource.Loading -> throw IllegalStateException("TTS 合成未完成")
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
                        error = e.message ?: "TTS 合成失败"
                    )
                )
            }
        }
        target.ttsJobs[messageId] = ttsJob
        ttsJob.invokeOnCompletion { target.ttsJobs.remove(messageId, ttsJob) }
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
                    MessageTtsUiState(MessageTtsStatus.Error, "当前会话未开启 TTS")
                )
                return@launch
            }
            startMessageTts(target, sessionId, message, config, force = true)
        }
        target.ttsJobs[prepareKey] = prepareJob
        prepareJob.invokeOnCompletion { target.ttsJobs.remove(prepareKey, prepareJob) }
        prepareJob.start()
    }

    /**
     * 发送消息：
     * - 本地模式：调用 [UnifiedRepository.chatStream] 返回的 Flow，直接收集事件
     * - 服务器模式：优先通过 Socket.IO send_message 触发 AI（服务端会推送流式回复），
     *   Socket 未连接时回退到 HTTP /chat
     */
    fun sendMessage(text: String, plotChoiceId: String? = null, attachments: List<Map<String, Any>> = emptyList()) {
        val content = text.trim()
        val messageContent = buildChatMessageContent(content, attachments)
        if (messageContent.isBlank()) return
        if (_sending.value || currentSessionId.isBlank()) return
        if (plotChoiceId != null) {
            viewModelScope.launch {
                commitPlotChoiceSelection(plotChoiceId)
                sendMessage(content, attachments = attachments)
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
            localChatJob?.cancel()
            localChatJob = ServiceContainer.applicationScope.launch {
                val flow = try {
                    unified.chatStream(currentSessionId, messageContent, attachments)
                } catch (e: Exception) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    showError(e.message ?: string(R.string.chat_send_failed))
                    return@launch
                }
                if (flow == null) {
                    _sending.value = false
                    _messages.value = _messages.value.filter { it.id != streamingId }
                    showError(string(R.string.chat_no_ai_model))
                    return@launch
                }
                try {
                    flow.collect { event -> handleRealtimeEvent(event) }
                } catch (e: Exception) {
                    if (
                        e is kotlinx.coroutines.CancellationException &&
                        generationStopRequested
                    ) return@launch
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
            socket.sendMessage(currentSessionId, messageContent, attachments)
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
            launchHttpChat(messageContent, attachments)
        }
    }

    /** HTTP /chat 回退路径：触发后等待 socket 推送或轮询。 */
    private fun launchHttpChat(
        content: String,
        attachments: List<Map<String, Any>> = emptyList()
    ) {
        val previousAssistantIds = _messages.value
            .filterNot { it.isUser }
            .mapNotNull { it.id }
            .toSet()
        // 创建流式占位消息，显示骨架动画（等待第一个 chunk）
        streamingContent.setLength(0)
        val placeholder = Message(
            id = streamingId,
            role = "assistant",
            content = "",
            timestamp = System.currentTimeMillis().toString()
        )
        _messages.value = _messages.value.filter { it.id != streamingId } + placeholder
        launchResult(
            block = { unified.chat(currentSessionId, content, attachments) },
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
                    .setContentTitle("[$sessionName] 生成失败")
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
        if (_sending.value || currentSessionId.isBlank()) return
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
            localChatJob?.cancel()
            localChatJob = ServiceContainer.applicationScope.launch {
                val flow = try {
                    unified.regenerateStream(currentSessionId, messageId)
                } catch (e: Exception) {
                    _sending.value = false
                    showError(e.message ?: string(R.string.chat_regenerate_failed))
                    return@launch
                }
                if (flow == null) {
                    _sending.value = false
                    showError(string(R.string.chat_no_ai_model))
                    return@launch
                }
                try {
                    flow.collect { event -> handleRealtimeEvent(event) }
                } catch (e: Exception) {
                    if (
                        e is kotlinx.coroutines.CancellationException &&
                        generationStopRequested
                    ) return@launch
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
                block = { unified.regenerate(currentSessionId, messageId) },
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
        launchResult(
            block = { unified.compressContext(currentSessionId) },
            onSuccess = { json ->
                // 后端返回 archive_session_id，写回当前 session 状态
                val archiveId = json?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("archive_session_id")?.asString
                if (archiveId != null) {
                    _session.value = _session.value?.copy(archiveSessionId = archiveId)
                }
                showToast(string(R.string.chat_context_compressed))
                loadMessages()
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
        launchWith(
            onError = {
                // 回滚
                _session.value = _session.value?.copy(
                    plotMode = current,
                    plotRealTimeSync = if (current) _session.value?.plotRealTimeSync else false
                )
                showToast(it)
            },
            block = {
                unified.updateSession(
                    sid,
                    UpdateSessionRequest(
                        plotMode = !current,
                        plotRealTimeSync = if (current) false else _session.value?.plotRealTimeSync
                    )
                )
            }
        )
        // 远程模式开启后异步拉取一次剧情选项
        if (!current && !isLocalMode) {
            loadPlotChoices()
        }
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
                    val raw = ServiceContainer.localRepository
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
                    ServiceContainer.localRepository.savePlotChoices(currentSessionId, payload.toString())
                    com.nekobot.app.data.local.ai.getGlobalPlotGraphManager().selectChoice(choiceId)
                    ServiceContainer.localRepository.persistPlotGraph()
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
data class PlotChoice(
    val id: String,
    val title: String,
    val description: String,
    val selected: Boolean = false,
    val level: String = "normal"
)
