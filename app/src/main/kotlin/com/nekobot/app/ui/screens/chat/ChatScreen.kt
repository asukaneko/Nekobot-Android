package com.nekobot.app.ui.screens.chat

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
import com.nekobot.app.data.model.AgentTodo
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import com.nekobot.app.data.local.ChatInputLayoutMode
import com.nekobot.app.data.local.db.LocalMessageImageEntity
import com.nekobot.app.data.local.LivePipelineMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.VISION_FAILURE_MARKER
import com.nekobot.app.data.local.agentContextSummaryBoundaryId
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.local.isLocalCommandMessage
import com.nekobot.app.data.local.ai.LocalSandboxCommandResult
import com.nekobot.app.data.local.ai.AgentRecoveryState
import com.nekobot.app.data.local.ai.toRecoveryState
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
    customBottomBar: (@Composable () -> Unit)? = null,
    // 平板双栏嵌入会话页时，底部悬浮导航栏盖住覆盖层输入区，需要整体抬升避让
    embeddedBottomBarClearance: Dp = 0.dp
) {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageImages by viewModel.messageImages.collectAsStateWithLifecycle()
    val ttsStates by viewModel.ttsStates.collectAsStateWithLifecycle()
    val liveStreamingSubtitle by viewModel.streamingContentPreview.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val groupCharacters by viewModel.groupCharacters.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val plotChoices by viewModel.plotChoices.collectAsStateWithLifecycle()
    val plotChoicesLoading by viewModel.plotChoicesLoading.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedMessageIds.collectAsStateWithLifecycle()
    val execConfirmation by viewModel.execConfirmation.collectAsStateWithLifecycle()
    val askUserQuestion by viewModel.askUserQuestion.collectAsStateWithLifecycle()
    val hookNotifications by viewModel.hookNotifications.collectAsStateWithLifecycle()
    val agentRecovery by viewModel.agentRecovery.collectAsStateWithLifecycle()
    val agentContextCompressionInProgress by viewModel.agentContextCompressionInProgress.collectAsStateWithLifecycle()
    val agentTodos by viewModel.agentTodos.collectAsStateWithLifecycle()
    // Agent 会话目标/规格任务（/goal、/spec 命令设置，输入框上方横幅展示）
    val agentGoal by viewModel.agentGoal.collectAsStateWithLifecycle()
    val agentSpec by viewModel.agentSpec.collectAsStateWithLifecycle()
    // 摘要可能先于会话元数据加载完成；直接以消息自身的压缩边界驱动分隔线。
    val agentCompressionBoundaryIds = messages.mapNotNull { it.agentContextSummaryBoundaryId() }.toSet()
    // 摘要本身仅供请求上下文使用，聊天列表仍展示完整原始历史。
    val visibleMessages = messages.filterNot { it.isAgentContextSummary() }
    // 不把完整 Message 列表作为 remember key：Agent 历史可能携带较大的嵌套进度数据，
    // Compose 对 key 做 equals 时会递归比较整棵工具结果。
    val latestBrowserProgressCardId = messages.asReversed().firstNotNullOfOrNull { message ->
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
    val messageImagesByMessage = remember(messageImages) {
        messageImages.groupBy(LocalMessageImageEntity::messageId)
    }

    var input by rememberSaveable(sessionId) {
        mutableStateOf(ServiceContainer.prefs.getChatInputDraft(sessionId))
    }
    // 输入框草稿持久化：退出会话后保留
    LaunchedEffect(input, sessionId) {
        ServiceContainer.prefs.setChatInputDraft(sessionId, input)
    }
    var pendingPlotChoiceId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    // Agent 进度卡片步骤详情弹窗目标（点击 step 时填充）
    var stepDetailTarget by remember { mutableStateOf<com.nekobot.app.data.model.ThinkingStep?>(null) }
    var chatInputLayout by remember {
        mutableStateOf(ServiceContainer.prefs.chatInputLayoutMode)
    }
    var menuExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<Message?>(null) }
    var editingMessage by remember(sessionId) { mutableStateOf<Message?>(null) }
    var messageActionTarget by remember(sessionId) { mutableStateOf<Message?>(null) }
    var previewGeneratedImage by remember(sessionId) {
        mutableStateOf<LocalMessageImageEntity?>(null)
    }
    var selectingTextMessage by remember(sessionId) { mutableStateOf<Message?>(null) }
    var showClearConfirm by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showMyMessages by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showRestoreArchiveDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showArchiveViewer by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showSandboxTerminal by rememberSaveable(sessionId) { mutableStateOf(false) }
    // 沙盒文件浏览器：覆盖在终端之上，复用同一会话沙盒 shell 的命令通道
    var showSandboxFiles by rememberSaveable(sessionId) { mutableStateOf(false) }
    var sandboxTerminalEntries by remember(sessionId) {
        mutableStateOf<List<SandboxTerminalEntry>>(emptyList())
    }
    var sandboxTerminalRunning by remember(sessionId) { mutableStateOf(false) }
    // 交互式会话（python3 等持续程序）运行状态：运行中输入直通程序 stdin
    var interactiveRunning by remember(sessionId) { mutableStateOf(false) }
    var interactiveEntryId by remember(sessionId) { mutableStateOf<Long?>(null) }
    // 展开状态必须高于 LazyColumn item：工具步骤更新或卡片离屏回收后仍保留用户选择。
    val progressCardExpansionOverrides = remember(sessionId) {
        mutableStateMapOf<String, Boolean>()
    }
    val listState = externalListState ?: rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(toast) {
        toast?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    var pendingDownloadImage by remember { mutableStateOf<LocalMessageImageEntity?>(null) }
    val downloadPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val image = pendingDownloadImage
        pendingDownloadImage = null
        if (granted && image != null) {
            scope.launch {
                val fileName = saveGeneratedImageToDownloads(context, image)
                Toast.makeText(
                    context,
                    if (fileName != null) {
                        context.getString(R.string.workspace_downloaded_to, fileName)
                    } else {
                        context.getString(R.string.workspace_download_failed)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else if (image != null) {
            Toast.makeText(
                context,
                context.getString(R.string.workspace_download_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun downloadGeneratedImage(image: LocalMessageImageEntity) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadImage = image
            downloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            val fileName = saveGeneratedImageToDownloads(context, image)
            Toast.makeText(
                context,
                if (fileName != null) {
                    context.getString(R.string.workspace_downloaded_to, fileName)
                } else {
                    context.getString(R.string.workspace_download_failed)
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // 文件选择模式：null=未选择, "send"=发送文件(上传+插入引用), "upload"=仅上传到工作区
    var filePickMode by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    // 待发送的图片附件：每项含 name/path/type，发送消息时一并传入聊天管线进行视觉识别
    var pendingImageAttachments by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

    // ===== 收藏夹 & 搜索 =====
    var showFavoritesDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showAddFavoritesDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    var favTitleInput by rememberSaveable(sessionId) { mutableStateOf("") }
    var showSearchDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var favoritesLoading by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable(sessionId) { mutableStateOf("") }

    // ===== 语音输入（录音 + STT 识别）=====
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var voiceTranscribing by remember { mutableStateOf(false) }
    var showLiveMode by rememberSaveable(sessionId) { mutableStateOf(false) }
    var liveConfigChecking by remember { mutableStateOf(false) }
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

    val requestLiveMicPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showLiveMode = true
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_voice_permission_required))
            }
        }
    }

    fun startLiveConversation() {
        if (liveConfigChecking) return
        liveConfigChecking = true
        scope.launch {
            try {
                when (
                    val result = ServiceContainer.unified.validateLiveConversationConfig(
                        requiresRealtimeModel =
                            ServiceContainer.prefs.livePipelineMode == LivePipelineMode.REALTIME
                    )
                ) {
                    is Resource.Success -> {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            showLiveMode = true
                        } else {
                            requestLiveMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    is Resource.Error -> snackbarHostState.showSnackbar(
                        result.message ?: "请先配置 STT、TTS 和 Live 模型"
                    )
                    is Resource.Loading -> Unit
                }
            } finally {
                liveConfigChecking = false
            }
        }
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
    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) {
            if (initialLoad) {
                // 首次加载：直接跳到底部，无动画
                listState.scrollToItem(visibleMessages.lastIndex)
                initialLoad = false
            } else {
                listState.animateScrollToItem(visibleMessages.lastIndex)
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

    val chatBackgroundModel = resolveAvatarUrl(
        PrefsManager.selectChatBackgroundPath(
            mode = ServiceContainer.prefs.chatBackgroundMode,
            portraitPath = session?.portraitUrl,
            customPath = ServiceContainer.prefs.customChatBackgroundPath
        )
    )
    val chatBackgroundOpacity = ServiceContainer.prefs.chatBackgroundOpacity

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
                                    fallbackIcon = if (session?.sessionMode == "group") Icons.Outlined.Group else Icons.Outlined.SmartToy,
                                    fallbackPainter = if (session?.sessionMode.equals("agent", ignoreCase = true)) {
                                        painterResource(R.drawable.ic_agent_neko)
                                    } else {
                                        null
                                    }
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
                            if (session != null && isLiveConversationSession(session?.sessionMode)) {
                                IconButton(
                                    onClick = { startLiveConversation() },
                                    enabled = !sending && !isRecording && !voiceTranscribing && !liveConfigChecking
                                ) {
                                    Icon(
                                        Icons.Filled.Phone,
                                        contentDescription = stringResource(R.string.live_start),
                                        tint = if (!sending && !isRecording && !voiceTranscribing && !liveConfigChecking) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        }
                                    )
                                }
                            }
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
                    Column {
                        if (agentRecovery != null && !sending) {
                            AgentRecoveryBar(
                                state = agentRecovery!!,
                                onResume = viewModel::resumeAgentRun,
                                onDiscard = viewModel::discardAgentRun
                            )
                        }
                        // Agent 会话目标/规格任务横幅（/goal、/spec 命令设置）
                        GoalSpecBanner(goal = agentGoal, spec = agentSpec)
                        // Agent 任务列表（todo_write 工具写入，输入框上方可折叠面板）
                        AgentTodosPanel(todos = agentTodos)
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
                        if (visibleMessages.isNotEmpty()) {
                            scope.launch { listState.animateScrollToItem(visibleMessages.lastIndex) }
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
            }
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (chatBackgroundModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(chatBackgroundModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = chatBackgroundOpacity,
                    modifier = Modifier.fillMaxSize()
                )
            }
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
            if (visibleMessages.isEmpty() && loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (visibleMessages.isEmpty()) {
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
                            if (session?.sessionMode.equals("agent", ignoreCase = true)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_agent_neko),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Group,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
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
                    modifier = Modifier
                        .fillMaxSize()
                        .liveRegion(),
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
                        visibleMessages,
                        key = { index, message -> chatMessageItemKey(index, message) }
                    ) { index, msg ->
                        // 注意：LazyColumn 单个 item 内的多个平级节点会像 Box 一样叠放，
                        // 因此日期分隔条与气泡必须包在 Column 里纵向排布
                        Column {
                            // 跨天消息之间插入日期分隔条
                            val day = dayKey(msg.timestamp)
                            val prevDay = visibleMessages.getOrNull(index - 1)?.let { dayKey(it.timestamp) }
                            if (day != null && day != prevDay) {
                                DateSeparatorChip(label = dayLabel(day))
                                Spacer(Modifier.height(2.dp))
                            }
                            if (msg.id == ChatViewModel.STREAMING_ID) {
                                StreamingAssistantBubble(
                                    placeholder = msg,
                                    contentFlow = viewModel.streamingContentPreview,
                                    reasoningFlow = viewModel.streamingReasoningPreview,
                                    portraitUrl = session?.portraitUrl,
                                    showAiAvatar = session?.sessionMode != "agent",
                                    fillAiWidth = session?.sessionMode == "agent",
                                    fallbackIcon = if (session?.sessionMode == "group") Icons.Outlined.Group else Icons.Outlined.SmartToy,
                                    sessionId = sessionId
                                )
                            } else {
                                val groupIdentity = if (session?.sessionMode.equals("group", ignoreCase = true)) {
                                    resolveGroupMessageIdentity(msg, groupCharacters)
                                } else {
                                    GroupMessageIdentity()
                                }
                                MessageBubble(
                                    message = msg,
                                    generatedImages = messageImagesByMessage[msg.id].orEmpty(),
                                    onGeneratedImageClick = { previewGeneratedImage = it },
                                    onFailedGeneratedImageLongClick = { viewModel.deleteMessageImage(it.id) },
                                    ttsState = msg.id?.let { ttsStates[it] },
                                    portraitUrl = groupIdentity.portraitUrl ?: session?.portraitUrl,
                                    senderName = groupIdentity.name,
                                    showAiAvatar = session?.sessionMode != "agent",
                                    fillAiWidth = session?.sessionMode == "agent",
                                    onLongClick = {
                                        if (selectionMode) {
                                            msg.id?.let(viewModel::toggleSelection)
                                        } else {
                                            messageActionTarget = msg
                                        }
                                    },
                                    onRegenerate = {
                                        // 首条 AI 消息（开场白）使用专用开场白重新生成
                                        if (index == 0 && !msg.isUser) {
                                            viewModel.regenerateGreeting()
                                        } else {
                                            viewModel.regenerate()
                                        }
                                    },
                                    onRegenerateTts = { viewModel.regenerateMessageTts(msg) },
                                    onFork = { msg.id?.let { mid -> viewModel.forkFromMessage(mid) { onOpenChat(it) } } },
                                    onCopy = { msg.displayContent },
                                    onEdit = if (msg.isUser && !sending) {
                                        { editingMessage = msg }
                                    } else {
                                        null
                                    },
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
                            if (msg.id != null && msg.id in agentCompressionBoundaryIds) {
                                Spacer(Modifier.height(4.dp))
                                AgentContextCompressionDivider(inProgress = false)
                            }
                            // 远程模式只有 Agent 会话显示进度卡片；本地模式还需要支持角色/群聊的耗时命令。
                            if (
                                index == visibleMessages.lastIndex &&
                                agentContextCompressionInProgress &&
                                // 本地模式手动压缩已改为后台执行，普通会话也需要可见的压缩进度反馈。
                                (session?.sessionMode.equals("agent", ignoreCase = true) ||
                                    ServiceContainer.prefs.isLocalMode)
                            ) {
                                Spacer(Modifier.height(4.dp))
                                AgentContextCompressionDivider(inProgress = true)
                            }
                            if (
                                msg.isUser &&
                                shouldRenderProgressCards(
                                    isLocalMode = ServiceContainer.prefs.isLocalMode,
                                    sessionMode = session?.sessionMode
                                ) &&
                                !msg.thinkingCards.isNullOrEmpty()
                            ) {
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

        previewGeneratedImage?.let { image ->
            Dialog(
                onDismissRequest = { previewGeneratedImage = null },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { previewGeneratedImage = null }
                ) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(image.filePath)
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.chat_message_image_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        IconButton(onClick = { downloadGeneratedImage(image) }) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = stringResource(R.string.chat_message_image_download),
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { previewGeneratedImage = null }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.chat_message_image_close_preview),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
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
                text = stringResource(R.string.chat_message_generate_image),
                icon = Icons.Filled.AutoAwesome,
                enabled = targetId != null && target.displayContent.isNotBlank(),
                onClick = {
                    messageActionTarget = null
                    viewModel.generateImageForMessage(target)
                }
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

    editingMessage?.let { message ->
        var editedContent by remember(message.id, message.content) {
            mutableStateOf(message.displayContent)
        }
        var showEditValidationError by remember(message.id) { mutableStateOf(false) }
        NekoDialog(
            onDismiss = { editingMessage = null },
            title = stringResource(R.string.chat_edit_message_title),
            confirmText = stringResource(R.string.chat_edit_message_resend),
            onConfirm = {
                if (editedContent.isBlank()) {
                    showEditValidationError = true
                } else {
                    viewModel.editUserMessage(message, editedContent)
                    editingMessage = null
                }
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { editingMessage = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = editedContent,
                    onValueChange = {
                        editedContent = it
                        showEditValidationError = false
                    },
                    label = { Text(stringResource(R.string.chat_edit_message_input)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showEditValidationError) {
                    Text(
                        text = stringResource(R.string.chat_edit_message_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = stringResource(R.string.chat_edit_message_discard_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

    // ask_user_question：AI 向用户发起结构化提问，回答作为工具结果回传
    askUserQuestion?.let { questionRequest ->
        AskUserQuestionDialog(
            request = questionRequest,
            onAnswer = { answers -> viewModel.respondToAskUserQuestion(answers) },
            onSkip = { viewModel.skipAskUserQuestion() }
        )
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
        var turnsText by rememberSaveable(sessionId) { mutableStateOf("5") }
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
    if (showLiveMode) {
        LiveConversationDialog(
            sessionId = sessionId,
            sessionName = session?.displayName ?: stringResource(R.string.chat_conversation),
            portraitUrl = session?.portraitUrl,
            messages = messages,
            sending = sending,
            streamingSubtitle = liveStreamingSubtitle,
            ttsStates = ttsStates,
            onSendMessage = { viewModel.sendMessage(it) },
            onPrepareTts = viewModel::prepareMessageTtsForLive,
            onStartRealtimeTurn = viewModel::startRealtimeLiveTurn,
            onStopRealtimeTurn = viewModel::stopRealtimeLiveTurn,
            onStopGeneration = viewModel::stop,
            onValidatePipeline = { pipeline ->
                when (
                    val result = ServiceContainer.unified.validateLiveConversationConfig(
                        requiresRealtimeModel = pipeline == LivePipelineMode.REALTIME
                    )
                ) {
                    is Resource.Success -> null
                    is Resource.Error -> result.message
                        ?: context.getString(R.string.live_realtime_failed)
                    is Resource.Loading -> context.getString(R.string.live_connecting)
                }
            },
            onDismiss = { showLiveMode = false }
        )
    }

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

    // ===== 沙箱交互式会话（python3 等持续程序） =====
    val interactiveFailedText = stringResource(R.string.chat_sandbox_terminal_interactive_failed)

    fun isInteractiveSandboxCommand(command: String): Boolean {
        val program = command.trim().substringBefore(' ').substringAfterLast('/').lowercase()
        return program in interactiveSandboxPrograms
    }

    /** 把一段文本追加到当前交互式会话对应的终端条目输出上。 */
    fun appendInteractiveOutput(text: String) {
        val targetId = interactiveEntryId ?: return
        sandboxTerminalEntries = sandboxTerminalEntries.map { entry ->
            if (entry.id == targetId) entry.copy(output = entry.output + text) else entry
        }
    }

    /** 启动交互式会话：新增运行中条目，输出流式写入，退出时补退出码。 */
    fun startInteractiveSession(rawCommand: String) {
        if (interactiveRunning) return
        val command = normalizeInteractiveCommand(rawCommand)
        val entryId = System.nanoTime()
        interactiveEntryId = entryId
        sandboxTerminalEntries = sandboxTerminalEntries + SandboxTerminalEntry(
            id = entryId,
            command = command,
            isRunning = true,
        )
        interactiveRunning = true
        val started = viewModel.startSandboxInteractiveSession(
            command = command,
            onOutput = { chunk -> appendInteractiveOutput(chunk) },
            onExit = { code ->
                interactiveRunning = false
                interactiveEntryId = null
                sandboxTerminalEntries = sandboxTerminalEntries.map { entry ->
                    if (entry.id == entryId) {
                        entry.copy(isRunning = false, exitCode = code)
                    } else {
                        entry
                    }
                }
            },
        )
        if (!started) {
            interactiveRunning = false
            interactiveEntryId = null
            sandboxTerminalEntries = sandboxTerminalEntries.map { entry ->
                if (entry.id == entryId) {
                    entry.copy(isRunning = false, error = interactiveFailedText)
                } else {
                    entry
                }
            }
        }
    }

    if (showSandboxTerminal) {
        SandboxTerminalOverlay(
            entries = sandboxTerminalEntries,
            running = sandboxTerminalRunning,
            interactiveRunning = interactiveRunning,
            onRunCommand = { rawCommand ->
                val command = rawCommand.trim()
                when {
                    command.isEmpty() -> Unit
                    command == "clear" && !interactiveRunning -> sandboxTerminalEntries = emptyList()
                    command == "exit" && !interactiveRunning -> showSandboxTerminal = false
                    interactiveRunning -> {
                        // 交互式会话中：输入直通程序 stdin，并本地回显一行
                        appendInteractiveOutput("$command\n")
                        viewModel.sendSandboxInteractiveInput(command)
                    }
                    isInteractiveSandboxCommand(command) -> startInteractiveSession(command)
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
                if (interactiveRunning) {
                    // 交互式会话：终止进程（退出码经 onExit 回写条目）
                    viewModel.stopSandboxInteractiveSession()
                } else {
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
                }
            },
            onClear = {
                if (!sandboxTerminalRunning && !interactiveRunning) sandboxTerminalEntries = emptyList()
            },
            onOpenFiles = { showSandboxFiles = true },
            onDismiss = {
                showSandboxTerminal = false
                // 关闭终端时终止仍在运行的交互式会话，避免进程残留
                if (interactiveRunning) viewModel.stopSandboxInteractiveSession()
            },
            bottomClearance = embeddedBottomBarClearance,
        )
    }
    if (showSandboxFiles) {
        SandboxFileBrowserOverlay(
            onRunCommand = viewModel::executeSandboxCommand,
            onDismiss = { showSandboxFiles = false },
            bottomClearance = embeddedBottomBarClearance,
        )
    }
    // 离开聊天页或切换会话时终止交互式会话
    DisposableEffect(sessionId) {
        onDispose { viewModel.stopSandboxInteractiveSession() }
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

/** 需要持续交互的沙盒程序（解释器/REPL 类），命中后走交互式会话通道。 */
private val interactiveSandboxPrograms = setOf(
    "python", "python3", "node", "irb", "sqlite3", "bc",
    "sh", "bash", "zsh", "fish",
)

/**
 * python/node 在管道 stdin 下默认按“脚本”读取（不打印提示符、不回显表达式结果），
 * 无 -i/-c/其他参数时补上 -i 强制以 REPL 方式运行。
 */
private fun normalizeInteractiveCommand(command: String): String {
    val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return command
    val program = tokens.first().substringAfterLast('/').lowercase()
    val hasModeFlag = tokens.drop(1).any { it == "-i" || it == "-c" || it.startsWith("-") }
    return when {
        program in setOf("python", "python3", "node") && !hasModeFlag -> "$command -i"
        else -> command
    }
}

/**
 * 当前 Agent 会话的全屏沙箱终端。
 *
 * 终端只负责展示和输入，命令状态由 ChatScreen 提升持有，因此关闭再打开时
 * 本次页面生命周期内的输出仍在；底层 shell 则由会话级 coordinator 长期持有。
 *
 * 实现说明：使用与主界面同窗口的全屏覆盖层而非独立 Dialog 窗口。
 * Dialog 窗口对 navigationBars/IME insets 的派发不可靠——正常态导航栏 inset
 * 丢失导致输入框底边贴出屏幕，键盘弹出时系统位移又与 ime inset 叠加产生
 * 双重空隙。覆盖层与主聊天输入栏共用同一套 insets 行为，表现一致。
 */
@Composable
private fun SandboxTerminalOverlay(
    entries: List<SandboxTerminalEntry>,
    running: Boolean,
    interactiveRunning: Boolean,
    onRunCommand: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onOpenFiles: () -> Unit,
    onDismiss: () -> Unit,
    bottomClearance: Dp = 0.dp,
) {
    val background = Color(0xFF0B0F14)
    val panel = Color(0xFF111820)
    val foreground = Color(0xFFD8DEE9)
    val muted = Color(0xFF7F8B99)
    val prompt = Color(0xFF73D99F)
    val errorColor = Color(0xFFFF7B72)
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        val command = input.trim()
        if (command.isBlank()) return
        // 普通命令运行中不允许再提交；交互式会话中运行标记为 false，可直接提交
        if (running && !interactiveRunning) return
        input = ""
        onRunCommand(command)
    }

    // 覆盖层不是独立窗口，返回键需自行接管以关闭终端
    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        delay(120)
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // 输出流式增长时也自动滚到底部
    LaunchedEffect(
        entries.size,
        entries.lastOrNull()?.isRunning,
        entries.lastOrNull()?.output?.length,
    ) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    // 全屏覆盖层：与主界面同一窗口，导航栏/键盘 insets 派发可靠；
    // 拦截空白区域点击，避免透传到下层聊天界面
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .clickable(
                interactionSource = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                },
                indication = null,
            ) {}
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // 平板双栏嵌入时整体抬升，避开底部悬浮导航栏
                .padding(bottom = bottomClearance)
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
                    IconButton(onClick = onOpenFiles) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = stringResource(R.string.chat_sandbox_files_open),
                            tint = foreground,
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
                                        // 交互式会话：运行中实时展示流式输出
                                        if (entry.output.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            SelectionContainer {
                                                Text(
                                                    text = entry.output,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = foreground,
                                                )
                                            }
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

                // 交互式会话运行中的提示横幅
                if (interactiveRunning) {
                    Text(
                        text = stringResource(R.string.chat_sandbox_terminal_interactive),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(panel)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = panel,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 与主聊天输入栏一致：导航栏 padding 叠加键盘 padding
                            // （imePadding 会扣除已消费部分），键盘弹出时输入框
                            // 紧贴键盘上方，无双重空隙
                            .navigationBarsPadding()
                            .imePadding()
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
                        val anyRunning = running || interactiveRunning
                        IconButton(
                            onClick = if (anyRunning) onStop else ::submit,
                        ) {
                            Icon(
                                imageVector = if (anyRunning) {
                                    Icons.Filled.Stop
                                } else {
                                    Icons.AutoMirrored.Filled.Send
                                },
                                contentDescription = stringResource(
                                    if (anyRunning) {
                                        R.string.chat_sandbox_terminal_stop
                                    } else {
                                        R.string.chat_sandbox_terminal_run
                                    }
                                ),
                                tint = if (anyRunning) errorColor else prompt,
                            )
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
                itemsIndexed(
                    favorites,
                    key = { index, collection ->
                        collection.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                            ?: collection.get("collection_id")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                            ?: "favorite:$index:${collection.get("title")?.asString.orEmpty()}"
                    }
                ) { _, collection ->
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
                itemsIndexed(results, key = { index, message -> chatMessageItemKey(index, message) }) { _, msg ->
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

@Composable
private fun StreamingAssistantBubble(
    placeholder: Message,
    contentFlow: StateFlow<String>,
    reasoningFlow: StateFlow<String>,
    portraitUrl: String?,
    showAiAvatar: Boolean,
    fillAiWidth: Boolean,
    fallbackIcon: ImageVector,
    sessionId: String
) {
    val content by contentFlow.collectAsStateWithLifecycle()
    val reasoning by reasoningFlow.collectAsStateWithLifecycle()
    if (content.isBlank() && reasoning.isBlank()) {
        ThinkingIndicator(
            portraitUrl = portraitUrl,
            showAiAvatar = showAiAvatar,
            fillAiWidth = fillAiWidth,
            fallbackIcon = fallbackIcon
        )
        return
    }
    val liveMessage = remember(placeholder, content, reasoning) {
        placeholder.copy(
            content = content,
            reasoningContent = reasoning.takeIf(String::isNotBlank)
        )
    }
    MessageBubble(
        message = liveMessage,
        portraitUrl = portraitUrl,
        showAiAvatar = showAiAvatar,
        fillAiWidth = fillAiWidth,
        onLongClick = {},
        sessionId = sessionId
    )
}

@Composable
private fun SafePlainMessageText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val pageSize = 16_000
    var visibleChars by remember(text) {
        mutableStateOf(text.length.coerceAtMost(pageSize))
    }
    val visibleText = remember(text, visibleChars) {
        text.take(visibleChars)
    }
    Column(modifier = modifier) {
        SelectionContainer {
            Text(
                text = visibleText,
                modifier = Modifier.fillMaxWidth(),
                color = color,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (visibleChars < text.length) {
            TextButton(
                onClick = { visibleChars = (visibleChars + pageSize).coerceAtMost(text.length) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.chat_continue_remaining, text.length - visibleChars))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    generatedImages: List<LocalMessageImageEntity> = emptyList(),
    onGeneratedImageClick: (LocalMessageImageEntity) -> Unit = {},
    onFailedGeneratedImageLongClick: (LocalMessageImageEntity) -> Unit = {},
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
    onEdit: (() -> Unit)? = null,
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
    // 流式预览有长度上限，可安全使用 MarkdownText 并在每次刷新时重解析。
    val useSafePlainText = shouldUseSafePlainText(
        isUser = isUser,
        contentLength = message.displayContent.length,
        isStreaming = isStreamingPlaceholder
    )
    val emptyMessageParen = stringResource(R.string.chat_empty_message_paren)
    val segments = remember(message.content) {
        message.displayContent
            .split("<||>")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { if (it.isEmpty() && !isStreamingPlaceholder) listOf(emptyMessageParen) else it }
    }
    // 解析每段的多媒体内容段
    val parsedSegments = remember(segments, useSafePlainText) {
        if (useSafePlainText) {
            segments.map { listOf(ContentSegment(type = SegmentType.TEXT, text = it)) }
        } else {
            segments.map { parseContentSegments(it) }
        }
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
            },
            // 用户气泡始终右对齐：即使下方工具栏（时间/操作按钮）比气泡更宽，
            // 气泡本身也紧贴右边缘，工具栏向左延伸
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
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
            if (!isUser && !fillAiWidth && !message.reasoningContent.isNullOrBlank()) {
                var reasoningExpanded by remember(message.id) {
                    mutableStateOf(isStreamingPlaceholder)
                }
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                    .padding(bottom = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                    border = null
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reasoningExpanded = !reasoningExpanded }
                                .padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = if (isStreamingPlaceholder) stringResource(R.string.chat_thinking)
                                else stringResource(R.string.chat_reasoning_process),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = if (reasoningExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (reasoningExpanded) stringResource(R.string.common_collapse)
                                else stringResource(R.string.common_expand),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = reasoningExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            SelectionContainer {
                                Text(
                                    text = message.reasoningContent.orEmpty(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 11.dp, end = 11.dp, bottom = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
                                )
                            }
                        }
                    }
                }
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
                                        sessionId = sessionId,
                                        chatMode = true,
                                        processParens = !isUser
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
                                            sessionId = sessionId,
                                            chatMode = true,
                                            processParens = !isUser
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
                            sessionId = sessionId,
                            chatMode = true,
                            processParens = !isUser
                        )
                    } else {
                        // 文本内容：用 Markdown 渲染
                        // 用户气泡：宽度跟随实际内容（短消息不撑满）；AI 气泡：填满最大宽度
                        if (useSafePlainText) {
                            SafePlainMessageText(
                                text = segment,
                                color = textColor,
                                modifier = if (isUser) Modifier.widthIn(max = maxBubbleWidth) else Modifier.fillMaxWidth()
                            )
                        } else {
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
                }
                if (!isLast) {
                    val fileAdjacent = contentSegments.any { it.type == SegmentType.FILE } ||
                        parsedSegments.getOrNull(idx + 1).orEmpty().any { it.type == SegmentType.FILE }
                    Spacer(Modifier.height(if (fileAdjacent) 4.dp else 10.dp))
                }
            }

            // 流式生成中：气泡下方追加打字圆点，提示仍在输出
            if (generatedImages.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                generatedImages.forEachIndexed { index, image ->
                    MessageGeneratedImageCard(
                        image = image,
                        onClick = onGeneratedImageClick,
                        onFailedLongClick = onFailedGeneratedImageLongClick
                    )
                    if (index < generatedImages.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }

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
                            onEdit?.let { edit ->
                                Spacer(Modifier.width(4.dp))
                                IconActionButton(
                                    icon = Icons.Filled.Edit,
                                    description = stringResource(R.string.common_edit),
                                    onClick = edit
                                )
                            }
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

/** 消息下方的 AI 生图结果或任务状态。 */
private suspend fun saveGeneratedImageToDownloads(
    context: android.content.Context,
    image: LocalMessageImageEntity
): String? = withContext(Dispatchers.IO) {
    val reference = image.filePath?.takeIf { it.isNotBlank() } ?: return@withContext null
    val sourceUri = runCatching { Uri.parse(reference) }.getOrNull() ?: return@withContext null
    val mimeType = image.mimeType?.takeIf { it.isNotBlank() } ?: "image/png"
    val extension = mimeType.substringAfter('/', "png")
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        ?: "png"
    val fileName = "nekobot_image_${System.currentTimeMillis()}.$extension"
    val input = runCatching {
        when (sourceUri.scheme?.lowercase()) {
            "file" -> sourceUri.path?.let(::File)?.inputStream()
            else -> context.contentResolver.openInputStream(sourceUri)
        }
    }.getOrNull() ?: return@withContext null

    var destinationUri: Uri? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val targetUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return@withContext null
            destinationUri = targetUri
            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                input.use { it.copyTo(output) }
            } ?: return@withContext null
            context.contentResolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val target = File(downloadsDir, fileName)
            FileOutputStream(target).use { output -> input.use { it.copyTo(output) } }
        }
        fileName
    } catch (_: Exception) {
        destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        null
    } finally {
        runCatching { input.close() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageGeneratedImageCard(
    image: LocalMessageImageEntity,
    onClick: (LocalMessageImageEntity) -> Unit = {},
    onFailedLongClick: (LocalMessageImageEntity) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .then(
                if (image.status == "failed") {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { onFailedLongClick(image) }
                    )
                } else {
                    Modifier
                }
            )
            .padding(8.dp)
    ) {
        when (image.status) {
            "completed" -> {
                if (!image.modelName.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.chat_message_image_model, image.modelName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(image.filePath)
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.chat_message_image_content_description),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onClick(image) }
                )
            }

            "failed" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.chat_message_image_failed,
                            image.errorMessage ?: stringResource(R.string.common_unknown_error)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (image.status == "running") {
                            stringResource(R.string.chat_message_image_generating)
                        } else {
                            stringResource(R.string.chat_message_image_queued)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
        borderWidth = 0,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 头部：图标 + 内容文本 + 展开开关（禁用水波纹遮罩）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null
                ) { onExpandedChange(!expanded) },
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
                text = card.content.stripEmoji().ifBlank { stringResource(R.string.chat_ai_processing) },
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

        // 文件变更 git 摘要区域：始终渲染（不随卡片折叠），逐文件 diff 可展开
        val gitSummary = card.steps.firstNotNullOfOrNull { it.gitDiff }
        if (gitSummary != null && gitSummary.hasChanges) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(6.dp))
            GitDiffSummarySection(summary = gitSummary)
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
                    // git_diff 步骤的内容已在卡片头部的摘要区展示，避免重复
                    if (!step.type.equals("git_diff", ignoreCase = true)) {
                        ProgressStepRow(step, onStepClick = onStepClick)
                    }
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

/** 进度卡片只属于本地执行链路或远程 Agent 工具链路，远程角色/群聊不展示。 */
internal fun shouldRenderProgressCards(
    isLocalMode: Boolean,
    sessionMode: String?
): Boolean = isLocalMode || sessionMode.equals("agent", ignoreCase = true)

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
    val name = step.name?.stripEmoji()?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_step)
    val detail = step.detail?.stripEmoji()?.takeIf { it.isNotBlank() }
    // 工具执行耗时（本地 Agent 模式在工具完成后写入；远程/运行中为 null 时不展示）
    val durationLabel = step.durationMs?.let { formatToolDuration(it) }
    val isStreamingThinking = step.type.equals("thinking", ignoreCase = true) &&
        (step.status.equals("running", ignoreCase = true) ||
            step.status.equals("active", ignoreCase = true))
    val liveThinkingPreview = if (isStreamingThinking) {
        step.thinkingContent
            ?.takeLast(1_200)
            ?.stripEmoji()
            ?.takeIf(String::isNotBlank)
    } else null
    // 含任一详情字段时可点击查看详情（对齐原仓库 has-detail 判定）
    val hasDetail = step.arguments != null || step.fullResult != null || !step.thinkingContent.isNullOrBlank()
    val canOpenDetail = hasDetail && !isStreamingThinking

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canOpenDetail) Modifier.clickable { onStepClick(step) } else Modifier),
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
                if (durationLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_step_duration, durationLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (canOpenDetail) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            if (!liveThinkingPreview.isNullOrBlank()) {
                androidx.compose.material3.Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = liveThinkingPreview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (!detail.isNullOrBlank()) {
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
 * 工具耗时格式化：不足 1 秒显示毫秒，否则以秒显示（保留 1 位小数）。
 * 单位 ms / s 为通用写法，不随语言变化，仅标签部分走多语言资源。
 */
internal fun formatToolDuration(durationMs: Long): String {
    val ms = durationMs.coerceAtLeast(0L)
    return if (ms < 1_000) "${ms}ms" else "%.1f".format(ms / 1000.0) + "s"
}

/**
 * 文件变更 git 摘要卡片区域：始终显示于进度卡片头部之下。
 *
 * 顶部展示仓库名/分支与总增减行数；下方列出变更文件，每个文件可展开查看
 * 完整的统一 diff 内容（上下文 3 行）。超过上限或被截断的文件显示提示。
 */
@Composable
private fun GitDiffSummarySection(
    summary: com.nekobot.app.data.model.GitDiffSummary
) {
    val addColor = Color(0xFF2EA043)
    val delColor = Color(0xFFCF6679)
    Column(modifier = Modifier.fillMaxWidth()) {
        // 头部：标题 + 仓库/分支 + 总增减
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = summary.repoName.ifBlank { "git" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!summary.branch.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = summary.branch,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (summary.totalAdditions > 0 || summary.totalDeletions > 0) {
                Spacer(Modifier.width(10.dp))
                DiffStatText(
                    additions = summary.totalAdditions,
                    deletions = summary.totalDeletions,
                    addColor = addColor,
                    delColor = delColor
                )
            }
        }

        if (summary.filesTruncated) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.agent_git_files_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            summary.files.forEach { file ->
                GitDiffFileRow(file, addColor = addColor, delColor = delColor)
            }
        }
    }
}

/** 新增/删除行数统计，绿色 + 红色。 */
@Composable
private fun DiffStatText(
    additions: Int,
    deletions: Int,
    addColor: Color,
    delColor: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (additions > 0) {
            Text(
                text = "+$additions",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = addColor
            )
        }
        if (deletions > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "−$deletions",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = delColor
            )
        }
    }
}

/** 单个变更文件行：路径 + 状态标签 + 增删计数，可展开查看 diff 块。 */
@Composable
private fun GitDiffFileRow(
    file: com.nekobot.app.data.model.GitDiffFile,
    addColor: Color,
    delColor: Color
) {
    var expanded by remember(file.path) { mutableStateOf(false) }
    // 禁用点击时的白色圆形水波纹遮罩（ripple），仅保留展开/收起行为
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val (statusLabel, statusColor) = when (file.status) {
        com.nekobot.app.data.model.GitDiffFile.STATUS_ADDED -> {
            stringResource(R.string.agent_git_added) to addColor
        }
        com.nekobot.app.data.model.GitDiffFile.STATUS_DELETED -> {
            stringResource(R.string.agent_git_deleted) to delColor
        }
        else -> stringResource(R.string.agent_git_modified) to MaterialTheme.colorScheme.primary
    }
    val hasContent = file.hunks.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
            .then(
                if (hasContent) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { expanded = !expanded }
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (file.additions > 0 || file.deletions > 0) {
                Spacer(Modifier.width(6.dp))
                DiffStatText(file.additions, file.deletions, addColor, delColor)
            }
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(4.dp),
                color = statusColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (hasContent) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (expanded && hasContent) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                thickness = 0.5.dp
            )
            // LazyColumn 子项以无限高度测量：内嵌滚动必须给出有界最大高度，
            // 否则触发 "Vertically scrollable component was measured with an infinity
            // maximum height constraints" 崩溃。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                file.hunks.forEach { hunk ->
                    Text(
                        text = hunk.header,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    hunk.lines.forEach { line ->
                        val bg = when (line.kind) {
                            com.nekobot.app.data.model.GitDiffLine.KIND_ADD -> addColor.copy(alpha = 0.14f)
                            com.nekobot.app.data.model.GitDiffLine.KIND_DEL -> delColor.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        }
                        val textColor = when (line.kind) {
                            com.nekobot.app.data.model.GitDiffLine.KIND_ADD -> addColor
                            com.nekobot.app.data.model.GitDiffLine.KIND_DEL -> delColor
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val prefix = when (line.kind) {
                            com.nekobot.app.data.model.GitDiffLine.KIND_ADD -> "+"
                            com.nekobot.app.data.model.GitDiffLine.KIND_DEL -> "−"
                            else -> " "
                        }
                        Text(
                            text = prefix + " " + line.text,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (file.truncated) {
                Text(
                    text = stringResource(R.string.agent_git_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        } else if (expanded || !hasContent) {
            // 二进制/不可读/被截断：即使没有可展开内容也给出状态说明
            val note = when {
                file.binary -> stringResource(R.string.agent_git_binary)
                file.unavailable -> stringResource(R.string.agent_git_unavailable)
                else -> null
            }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
    val name = step.name?.stripEmoji()?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_step_details)
    val detail = step.detail?.stripEmoji()?.takeIf { it.isNotBlank() }
    val rawThinkingContent = step.thinkingContent?.takeIf(String::isNotBlank)
    val thinkingWasTruncated = rawThinkingContent?.length?.let { it > 20_000 } == true
    val thinkingContent = rawThinkingContent
        ?.takeLast(20_000)
        ?.stripEmoji()
        ?.takeIf(String::isNotBlank)
    val argumentsJson = step.arguments?.let { formatJsonForDisplay(it) }
    val fullResultJson = step.fullResult?.let { formatJsonForDisplay(it) }
    val toolOutputWasTruncated = step.resultTruncated == true ||
        fullResultIndicatesTruncation(step.fullResult)
    val durationLabel = step.durationMs?.let { formatToolDuration(it) }
    val hasAny = detail != null || thinkingContent != null ||
        !argumentsJson.isNullOrBlank() || !fullResultJson.isNullOrBlank()

    NekoDialog(
        onDismiss = onDismiss,
        title = name,
        confirmText = stringResource(R.string.common_close),
        onConfirm = null,
        cancelText = null,
        onCancel = null,
        borderWidth = 0
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (durationLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_step_duration, durationLabel),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!hasAny) {
                    Text(
                        text = stringResource(R.string.chat_step_no_details),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!detail.isNullOrBlank()) {
                            StepDetailSection(
                                label = stringResource(R.string.chat_step_description),
                                content = detail
                            )
                        }
                        if (!thinkingContent.isNullOrBlank()) {
                            StepDetailSection(
                                label = if (thinkingWasTruncated) {
                                    stringResource(R.string.chat_ai_reasoning_truncated)
                                } else {
                                    stringResource(R.string.chat_ai_reasoning)
                                },
                                icon = Icons.Filled.Psychology,
                                content = thinkingContent,
                                accent = true
                            )
                        }
                        if (!argumentsJson.isNullOrBlank()) {
                            StepDetailSection(
                                label = stringResource(R.string.chat_step_arguments),
                                icon = Icons.Filled.Key,
                                content = argumentsJson,
                                isCode = true
                            )
                        }
                        if (!fullResultJson.isNullOrBlank()) {
                            StepDetailSection(
                                label = stringResource(R.string.chat_step_result),
                                icon = Icons.Filled.CheckCircle,
                                content = fullResultJson,
                                isCode = true,
                                notice = if (toolOutputWasTruncated) {
                                    stringResource(R.string.chat_step_result_truncated)
                                } else {
                                    null
                                }
                            )
                        }
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
    accent: Boolean = false,
    notice: String? = null
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
        if (!notice.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

/** 兼容旧进度卡：早期版本只在结果预览文本中保留 truncated=true。 */
private fun fullResultIndicatesTruncation(fullResult: Any?): Boolean {
    return when (fullResult) {
        is Map<*, *> -> fullResult.entries.any { (key, value) ->
            key?.toString()?.equals("truncated", ignoreCase = true) == true &&
                when (value) {
                    is Boolean -> value
                    is String -> value.equals("true", ignoreCase = true)
                    is Number -> value.toInt() == 1
                    else -> false
                }
        }
        is String -> Regex("\\btruncated\\s*[=:]\\s*true\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(fullResult)
        else -> false
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
    // 折叠多余空格与首尾空白，处理 "AI 正在处理... (1/150)" 前缀被剥离后的残余空格
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

/** 角色头像：圆形立绘，可带主题色光环；加载中/失败时显示指定的回退图标。 */
@Composable
private fun ChatAvatar(
    portraitUrl: String?,
    size: androidx.compose.ui.unit.Dp,
    ring: Boolean = false,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.SmartToy,
    fallbackPainter: androidx.compose.ui.graphics.painter.Painter? = null
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
            if (fallbackPainter != null) {
                Icon(
                    painter = fallbackPainter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.55f)
                )
            } else {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.55f)
                )
            }
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
private fun AgentContextCompressionDivider(inProgress: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = color.copy(alpha = 0.38f)
        )
        Spacer(Modifier.width(10.dp))
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = color
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Compress,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (inProgress) R.string.chat_agent_context_compressing
                else R.string.chat_agent_context_compressed
            ),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = color.copy(alpha = 0.38f)
        )
    }
}

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
 * 流式阶段只向 Compose 暴露一个有界窗口；完整正文仍保留在累加器并最终写入消息。
 * 这样长回复不会让单个 Text/Markdown 布局随 token 无限增长。
 */
internal fun buildStreamingDisplayPreview(
    content: CharSequence,
    maxChars: Int = 16_000,
    omittedPrefix: String = "…\n"
): String {
    if (content.length <= maxChars) return content.toString()
    return omittedPrefix +
        content.subSequence(content.length - maxChars, content.length).toString()
}

/** 流式预览已限制长度，保持 Markdown 渲染；仅普通超长 AI 消息降级为分页纯文本。 */
internal fun shouldUseSafePlainText(
    isUser: Boolean,
    contentLength: Int,
    isStreaming: Boolean
): Boolean = !isStreaming && !isUser && contentLength > 24_000

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
    finalReasoning: String = "",
    materializeFallback: Boolean,
    fallbackId: String = ChatViewModel.STREAM_FALLBACK_PREFIX + java.util.UUID.randomUUID(),
    fallbackTimestamp: String = System.currentTimeMillis().toString()
): List<Message> {
    val withoutPlaceholder = current.filter { it.id != streamingId }
    if (!materializeFallback || (finalContent.isBlank() && finalReasoning.isBlank())) return withoutPlaceholder
    if (withoutPlaceholder.any {
            !it.isUser && it.content == finalContent && it.reasoningContent == finalReasoning.takeIf(String::isNotBlank)
        }) return withoutPlaceholder
    return withoutPlaceholder + Message(
        id = fallbackId,
        role = "assistant",
        content = finalContent,
        reasoningContent = finalReasoning.takeIf(String::isNotBlank),
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
    val normalizedCurrent = deduplicateMessagesById(current)
    val incomingId = incoming.id
    if (!incomingId.isNullOrBlank()) {
        val existingIndex = normalizedCurrent.indexOfFirst { it.id == incomingId }
        if (existingIndex >= 0) {
            val existing = normalizedCurrent[existingIndex]
            return normalizedCurrent.toMutableList().apply {
                this[existingIndex] = incoming.copy(
                    thinkingCards = incoming.thinkingCards ?: existing.thinkingCards,
                    audioUrl = incoming.audioUrl ?: existing.audioUrl
                )
            }
        }
    }

    if (incoming.isUser) {
        val optimisticIndex = if (isSending) {
            normalizedCurrent.indexOfLast {
                it.isUser &&
                    it.id.isNullOrBlank() &&
                    it.content == incoming.content
            }
        } else {
            -1
        }
        if (optimisticIndex >= 0) {
            val optimistic = normalizedCurrent[optimisticIndex]
            return normalizedCurrent.toMutableList().apply {
                this[optimisticIndex] = incoming.copy(
                    thinkingCards = incoming.thinkingCards ?: optimistic.thinkingCards
                )
            }
        }
        return normalizedCurrent + incoming
    }

    return normalizedCurrent.filter {
        it.id != streamingId &&
            !(it.id?.startsWith(fallbackPrefix) == true && it.content == incoming.content)
    } + incoming
}

/**
 * Room 主键本身唯一，但页面运行时状态与刚落库数据合并时可能短暂包含同一消息两份。
 * 所有进入 LazyColumn 的列表都在边界处按非空 ID 去重；无 ID 的乐观消息必须保留。
 */
internal fun deduplicateMessagesById(messages: List<Message>): List<Message> {
    val seenIds = HashSet<String>()
    var changed = false
    val result = ArrayList<Message>(messages.size)
    messages.forEach { message ->
        val id = message.id?.takeIf(String::isNotBlank)
        if (id != null && !seenIds.add(id)) {
            changed = true
        } else {
            result.add(message)
        }
    }
    return if (changed) result else messages
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

@Composable
internal fun AgentRecoveryBar(
    state: AgentRecoveryState,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    val title = when (state.status) {
        "failed" -> stringResource(R.string.chat_agent_run_failed)
        "paused" -> stringResource(R.string.chat_agent_run_paused)
        else -> stringResource(R.string.chat_agent_run_interrupted)
    }
    val detail = when {
        state.mayHaveUncommittedToolEffect -> stringResource(
            R.string.chat_agent_run_tool_uncertain,
            state.lastToolName ?: stringResource(R.string.chat_tool)
        )
        state.canContinueFromCheckpoint -> stringResource(
            R.string.chat_agent_run_checkpoint_detail,
            state.completedToolCalls
        )
        !state.lastError.isNullOrBlank() -> state.lastError.take(180)
        else -> stringResource(R.string.chat_agent_run_retry_detail)
    }
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.chat_agent_run_discard))
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = onResume) {
                    Text(
                        stringResource(
                            if (state.canContinueFromCheckpoint) {
                                R.string.chat_agent_run_continue
                            } else {
                                R.string.chat_agent_run_retry
                            }
                        )
                    )
                }
            }
        }
    }
}

/** 任务列表展开时的最大显示高度（dp），超过后内部垂直滚动。 */
private val AGENT_TODOS_MAX_HEIGHT = 280.dp

/**
 * Agent 会话目标/规格任务横幅：/goal 与 /spec 命令设置的持久状态在输入框上方的可视化展示。
 * 参考 Claude Code 会话内目标横幅与 Kiro 规格状态展示；目标与规格均不存在时不渲染。
 * 同时挂载在 ChatScreen 内置 bottomBar 与 ModernChatScreen 的 customBottomBar 中。
 */
@Composable
internal fun GoalSpecBanner(
    goal: String?,
    spec: com.nekobot.app.data.model.AgentSessionSpec?
) {
    val goalText = goal?.trim().orEmpty()
    if (goalText.isEmpty() && spec == null) return
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (goalText.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.agent_goal_banner_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = goalText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (goalText.isNotEmpty() && spec != null) {
                Spacer(Modifier.height(6.dp))
            }
            if (spec != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.TaskAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.agent_spec_banner_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = spec.feature,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (spec.status == com.nekobot.app.data.model.AgentSessionSpec.STATUS_APPROVED) {
                                R.string.agent_spec_status_approved
                            } else {
                                R.string.agent_spec_status_draft
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Agent 任务列表面板（输入框上方，可折叠）。
 *
 * 数据由 todo_write 工具全量写入并持久化到会话；设计参考 opencode / Claude Code
 * 的任务列表可视化：头部显示完成进度，折叠时仅显示进行中的任务，展开查看全部。
 * 同时挂载在 ChatScreen 内置 bottomBar 与 ModernChatScreen 的 customBottomBar 中。
 */
@Composable
internal fun AgentTodosPanel(todos: List<AgentTodo>) {
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val completedCount = todos.count { it.status == AgentTodo.STATUS_COMPLETED }
    val cancelledCount = todos.count { it.status == AgentTodo.STATUS_CANCELLED }
    val activeCount = todos.size - completedCount - cancelledCount
    val activeTodo = todos.firstOrNull { it.status == AgentTodo.STATUS_IN_PROGRESS }
    val progress = if (todos.isEmpty()) 0f
    else (completedCount + cancelledCount).toFloat() / todos.size
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // 头部：进度概览 + 折叠开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.agent_todos_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.agent_todos_progress,
                        completedCount,
                        todos.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (activeTodo != null) {
                    Text(
                        text = activeTodo.content,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2.2f, fill = false)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            // 完成度进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            AnimatedVisibility(visible = expanded) {
                // 任务较多时限制高度，超出部分用垂直滚动展示，避免面板撑满整个屏幕。
                Column(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .heightIn(max = AGENT_TODOS_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    todos.forEachIndexed { index, todo ->
                        AgentTodoRow(todo = todo, index = index)
                    }
                    if (activeCount == 0) {
                        Text(
                            text = stringResource(R.string.agent_todos_all_done),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 任务列表单行：状态图标 + 内容（高优先级红点标注，取消项删除线）。 */
@Composable
private fun AgentTodoRow(todo: AgentTodo, index: Int) {
    val contentColor = when (todo.status) {
        AgentTodo.STATUS_COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentTodo.STATUS_CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val decoration = when (todo.status) {
        AgentTodo.STATUS_COMPLETED -> TextDecoration.LineThrough
        AgentTodo.STATUS_CANCELLED -> TextDecoration.LineThrough
        else -> TextDecoration.None
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            when (todo.status) {
                AgentTodo.STATUS_COMPLETED -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                AgentTodo.STATUS_IN_PROGRESS -> CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp
                )
                AgentTodo.STATUS_CANCELLED -> Icon(
                    Icons.Filled.RemoveCircleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                else -> Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            textDecoration = decoration,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (todo.priority == AgentTodo.PRIORITY_HIGH && todo.status != AgentTodo.STATUS_COMPLETED) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF6B6B))
            )
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
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var inputExpanded by rememberSaveable { mutableStateOf(false) }
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
    card: ThinkingCard
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

/**
 * 聊天列表 key 不得调用整条 Message.hashCode()：Agent 用户消息会携带完整思考卡和工具结果，
 * 递归计算嵌套对象既昂贵，也可能在退出重进时触发异常。持久化消息使用数据库 ID；
 * 尚未落库的乐观消息只使用轻量字段和当前位置生成页面内唯一 key。
 */
internal fun chatMessageItemKey(index: Int, message: Message): String {
    // index 是最后一道防线：即使旧状态或并发合并意外产生重复数据库 ID，Compose 也不能崩溃。
    message.id?.takeIf(String::isNotBlank)?.let { return "message:$it:$index" }
    return buildString {
        append("pending:")
        append(index)
        append(':')
        append(message.role.orEmpty())
        append(':')
        append(message.timestamp.orEmpty())
        append(':')
        append(message.content.orEmpty().hashCode())
    }
}

internal fun mergeThinkingCardReasoning(
    freshCards: List<ThinkingCard>?,
    currentCards: List<ThinkingCard>?
): List<ThinkingCard>? {
    if (freshCards == null) return currentCards
    if (currentCards.isNullOrEmpty()) return freshCards

    return freshCards.map { freshCard ->
        val currentCard = currentCards.firstOrNull { it.id == freshCard.id } ?: return@map freshCard
        val currentThinking = currentCard.steps.lastOrNull {
            it.type.equals("thinking", ignoreCase = true) && !it.thinkingContent.isNullOrBlank()
        } ?: return@map freshCard
        val freshThinkingIndex = freshCard.steps.indexOfLast {
            it.type.equals("thinking", ignoreCase = true)
        }
        if (freshThinkingIndex < 0) {
            freshCard.copy(steps = freshCard.steps + currentThinking)
        } else {
            val freshThinking = freshCard.steps[freshThinkingIndex]
            val freshContent = freshThinking.thinkingContent.orEmpty()
            val currentContent = currentThinking.thinkingContent.orEmpty()
            if (freshContent.length >= currentContent.length) {
                freshCard
            } else {
                freshCard.copy(
                    steps = freshCard.steps.toMutableList().apply {
                        set(
                            freshThinkingIndex,
                            freshThinking.copy(
                                detail = currentThinking.detail ?: freshThinking.detail,
                                thinkingContent = currentThinking.thinkingContent
                            )
                        )
                    }
                )
            }
        }
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
                    stringResource(R.string.audio_generating),
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
                        stringResource(R.string.audio_generation_failed),
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
                        contentDescription = stringResource(R.string.audio_regenerate),
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

data class PlotChoice(
    val id: String,
    val title: String,
    val description: String,
    val selected: Boolean = false,
    val level: String = "normal"
)
