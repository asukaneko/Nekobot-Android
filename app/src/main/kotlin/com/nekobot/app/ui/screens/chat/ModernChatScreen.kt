package com.nekobot.app.ui.screens.chat

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border as actualBorder
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ChatInputLayoutMode
import com.nekobot.app.data.local.LocalCommandAction
import com.nekobot.app.data.local.LocalCommandSuggestion
import com.nekobot.app.data.local.LocalSlashCommands
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
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
    onOpenContextAnalysis: (String) -> Unit = {},
    onOpenSessionDetail: (String) -> Unit = {},
    onOpenWorkspace: (String) -> Unit = {},
    onOpenStoryGraph: (String) -> Unit = {},
    onOpenWenku8Login: () -> Unit = {},
    onJumpToLatest: () -> Unit = {}
) {
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val plotChoices by viewModel.plotChoices.collectAsStateWithLifecycle()
    val plotChoicesLoading by viewModel.plotChoicesLoading.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val agentRecovery by viewModel.agentRecovery.collectAsStateWithLifecycle()
    val isAgentSession = session?.sessionMode.equals("agent", ignoreCase = true)
    val yoloAvailable = isAgentSession && ServiceContainer.prefs.isLocalMode
    var yoloEnabled by remember(sessionId, yoloAvailable) {
        mutableStateOf(yoloAvailable && ServiceContainer.unified.isYoloEnabled(sessionId))
    }
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
        Column {
            if (agentRecovery != null && !sending) {
                AgentRecoveryBar(
                    state = agentRecovery!!,
                    onResume = viewModel::resumeAgentRun,
                    onDiscard = viewModel::discardAgentRun
                )
            }
            ModernChatComposer(
            modifier = Modifier.fillMaxWidth(),
            sessionId = sessionId,
            messages = messages,
            sending = sending,
            plotChoices = plotChoices,
            plotChoicesLoading = plotChoicesLoading,
            plotMode = session?.plotMode == true,
            plotRealTimeSync = session?.plotRealTimeSync == true,
            isAgentSession = isAgentSession,
            yoloEnabled = yoloEnabled,
            yoloAvailable = yoloAvailable,
            onToggleYolo = {
                val enabled = !yoloEnabled
                ServiceContainer.unified.setYoloEnabled(sessionId, enabled)
                yoloEnabled = enabled
            },
            skillsEnabled = isAgentSession,
            onSend = { text, plotChoiceId, attachments, reasoningEffort ->
                val command = LocalSlashCommands.parse(text)
                if (
                    command?.action == LocalCommandAction.WENKU8_LOGIN &&
                    command.args.isEmpty() &&
                    attachments.isEmpty()
                ) {
                    onOpenWenku8Login()
                } else {
                    viewModel.sendMessage(text, plotChoiceId, attachments, reasoningEffort)
                }
            },
            onStop = viewModel::stop,
            onCompress = viewModel::compressContext,
            onOpenContextAnalysis = { onOpenContextAnalysis(sessionId) },
            onClear = { viewModel.clearMessages(sessionId) },
            onRegeneratePlotChoices = viewModel::regeneratePlotChoices,
            onOpenWorkspace = { onOpenWorkspace(sessionId) },
            onJumpToLatest = onJumpToLatest,
            onTogglePlotMode = viewModel::togglePlotMode,
            onTogglePlotRealTimeSync = viewModel::togglePlotRealTimeSync,
            onJumpToMessage = { msg ->
                val idx = messages.indexOfFirst { it.id == msg.id }
                if (idx >= 0) composerScope.launch { listState.animateScrollToItem(idx + 1) }
            }
        )
        }
    }
)
}

private enum class ModernComposerAction {
    VOICE,
    SEND,
    STOP
}

private sealed interface ChatCommandCandidate {
    val key: String
    val insertion: String

    data class Command(val suggestion: LocalCommandSuggestion) : ChatCommandCandidate {
        override val key: String = "command:${suggestion.command}"
        override val insertion: String = suggestion.command + if (suggestion.takesArguments) " " else ""
    }

    data class SkillCommand(val skill: Skill) : ChatCommandCandidate {
        override val key: String = "skill:${skill.id ?: skill.name}"
        override val insertion: String = "/skill \"${skill.name.replace("\"", "\\\"")}\" "
    }
}

private fun buildChatCommandCandidates(
    input: String,
    skills: List<Skill>,
    skillsEnabled: Boolean
): List<ChatCommandCandidate> {
    val value = input.trimStart()
    if (!value.startsWith('/') || '\n' in value) return emptyList()
    val token = value.substringBefore(' ').lowercase()
    if (token == "/skill") {
        if (!skillsEnabled) return emptyList()
        val query = value.substringAfter(' ', "").trim().trim('"').lowercase()
        return skills.asSequence()
            .filter { skill ->
                query.isBlank() || skill.name.contains(query, ignoreCase = true) ||
                    skill.aliases.any { it.contains(query, ignoreCase = true) }
            }
            .take(8)
            .map { ChatCommandCandidate.SkillCommand(it) }
            .toList()
    }
    if (' ' in value) return emptyList()

    val commands = LocalSlashCommands.suggestions(token)
        .map(ChatCommandCandidate::Command)
    val showAllCommands = token == "/"
    if (!skillsEnabled) return if (showAllCommands) commands else commands.take(8)
    val skillQuery = token.removePrefix("/")
    val skillCommands = skills.asSequence()
        .filter { skill ->
            skillQuery.isBlank() || skill.name.contains(skillQuery, ignoreCase = true) ||
                skill.aliases.any { it.contains(skillQuery, ignoreCase = true) }
        }
        .map { ChatCommandCandidate.SkillCommand(it) }
        .toList()
    return if (showAllCommands) {
        commands + skillCommands
    } else if (skillQuery.isBlank() && skillCommands.isNotEmpty()) {
        (commands.take(5) + skillCommands.take(3)).take(8)
    } else {
        (commands + skillCommands).take(8)
    }
}

@Composable
private fun CommandSuggestionPanel(
    candidates: List<ChatCommandCandidate>,
    showAllCommands: Boolean,
    onPick: (ChatCommandCandidate) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(candidates) {
        listState.scrollToItem(0)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = null
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (showAllCommands) 360.dp else 288.dp),
            state = listState,
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(candidates, key = ChatCommandCandidate::key) { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(candidate) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isSkill = candidate is ChatCommandCandidate.SkillCommand
                    Icon(
                        imageVector = if (isSkill) Icons.Filled.Extension else Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = if (isSkill) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (candidate) {
                                is ChatCommandCandidate.Command -> candidate.suggestion.command
                                is ChatCommandCandidate.SkillCommand -> stringResource(
                                    R.string.command_suggestion_skill_prefix,
                                    candidate.skill.name
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (candidate) {
                                is ChatCommandCandidate.Command -> candidate.suggestion.description
                                    .takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.command_suggestion_hint)
                                is ChatCommandCandidate.SkillCommand -> candidate.skill.description
                                    ?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.command_suggestion_skill_hint)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = stringResource(
                            if (isSkill) R.string.command_suggestion_skill
                            else R.string.command_suggestion_command
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSkill) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
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
    plotMode: Boolean,
    plotRealTimeSync: Boolean,
    isAgentSession: Boolean,
    yoloEnabled: Boolean,
    yoloAvailable: Boolean,
    skillsEnabled: Boolean,
    onToggleYolo: () -> Unit,
    onSend: (String, String?, List<Map<String, Any>>, ReasoningEffort) -> Unit,
    onStop: () -> Unit,
    onCompress: () -> Unit,
    onOpenContextAnalysis: () -> Unit,
    onClear: () -> Unit,
    onRegeneratePlotChoices: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onJumpToLatest: () -> Unit,
    onTogglePlotMode: () -> Unit,
    onTogglePlotRealTimeSync: () -> Unit,
    onJumpToMessage: (Message) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var inputField by remember(sessionId) {
        mutableStateOf(
            TextFieldValue(ServiceContainer.prefs.getChatInputDraft(sessionId))
        )
    }
    val input = inputField.text
    // 程序化更新输入内容时光标置于末尾，方便继续输入
    fun updateInput(text: String) {
        inputField = TextFieldValue(text, TextRange(text.length))
    }
    // 输入框草稿持久化：退出会话后保留
    LaunchedEffect(input, sessionId) {
        ServiceContainer.prefs.setChatInputDraft(sessionId, input)
    }
    var panelExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var reasoningEffort by remember(sessionId, isAgentSession) {
        mutableStateOf(ServiceContainer.prefs.getReasoningEffort(isAgentSession))
    }
    var inputExpanded by remember { mutableStateOf(false) }
    var chatInputLayout by remember {
        mutableStateOf(ServiceContainer.prefs.chatInputLayoutMode)
    }
    var pendingPlotChoiceId by remember { mutableStateOf<String?>(null) }
    var filePickMode by remember { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    var pendingImageAttachments by remember(sessionId) {
        mutableStateOf<List<Map<String, Any>>>(emptyList())
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showMyMessages by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFavorites by remember { mutableStateOf(false) }
    var favoritesLoading by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var favoritesVersion by remember { mutableStateOf(0) }
    var enabledSkills by remember(sessionId) { mutableStateOf<List<Skill>>(emptyList()) }
    var dismissedCommandCandidateInput by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, skillsEnabled) {
        enabledSkills = if (skillsEnabled) {
            when (val result = ServiceContainer.unified.listSkills()) {
                is Resource.Success -> result.data.orEmpty()
                    .filter { it.enabled && it.name.isNotBlank() }
                    .sortedBy { it.name.lowercase() }
                else -> emptyList()
            }
        } else {
            emptyList()
        }
    }

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
            toast(context.getString(R.string.chat_recording_start_failed, e.message ?: context.getString(R.string.common_unknown_error)))
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
                            updateInput(if (input.isBlank()) text else "$input $text")
                        } else {
                            toast(context.getString(R.string.chat_voice_no_text))
                        }
                    }
                    is Resource.Error -> toast(context.getString(R.string.chat_voice_recognize_failed, result.message))
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                toast(context.getString(R.string.chat_voice_recognize_failed, e.message ?: context.getString(R.string.common_unknown_error)))
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
        if (granted) startVoiceRecording() else toast(context.getString(R.string.chat_voice_permission_required))
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
                    modernReadUriFile(context, uri) ?: error(context.getString(R.string.chat_read_file_failed))
                }
                val body = bytes.toRequestBody(modernGuessMime(name).toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", name, body)
                when (val result = ServiceContainer.unified.uploadWorkspaceFile(sessionId, part)) {
                    is Resource.Success -> {
                        if (mode == "send") {
                            val baseAttachment = buildWorkspaceChatAttachment(
                                uploadResult = result.data,
                                sessionId = sessionId,
                                originalName = name,
                                fallbackMime = modernGuessMime(name)
                            )
                            val uploadedName = baseAttachment["name"]?.toString() ?: name
                            val mime = baseAttachment["type"]?.toString() ?: modernGuessMime(uploadedName)
                            if (mime.startsWith("image/")) {
                                val localFile = if (ServiceContainer.prefs.isLocalMode) {
                                    resolveLocalWorkspaceFile(context, sessionId, uploadedName)
                                } else {
                                    null
                                }
                                pendingImageAttachments = pendingImageAttachments + buildWorkspaceChatAttachment(
                                    uploadResult = result.data,
                                    sessionId = sessionId,
                                    originalName = uploadedName,
                                    fallbackMime = mime,
                                    localPath = localFile?.absolutePath
                                )
                                toast(context.getString(R.string.chat_image_attached, uploadedName))
                            } else {
                                updateInput(buildString {
                                    if (input.isNotBlank()) append(input).append('\n')
                                    append(context.getString(R.string.chat_file_uploaded_ref_inline, uploadedName))
                                })
                                toast(context.getString(R.string.chat_file_uploaded_ref))
                            }
                        } else {
                            toast(context.getString(R.string.chat_uploaded_to_workspace_colon, name))
                        }
                    }
                    is Resource.Error -> toast(context.getString(R.string.chat_upload_failed, result.message))
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                toast(context.getString(R.string.chat_operation_failed, e.message ?: context.getString(R.string.common_unknown_error)))
            } finally {
                fileBusy = false
            }
        }
    }

    val pendingShare by ServiceContainer.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId, pendingShare?.id) {
        val share = pendingShare ?: return@LaunchedEffect
        fileBusy = share.attachments.isNotEmpty()
        try {
            if (share.text.isNotBlank()) {
                updateInput(buildString {
                    if (input.isNotBlank()) append(input).append('\n')
                    append(share.text)
                })
            }
            share.attachments.forEach { attachment ->
                val file = java.io.File(attachment.localPath)
                if (!file.isFile) return@forEach
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val mime = attachment.mimeType.ifBlank { modernGuessMime(attachment.name) }
                val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", attachment.name, body)
                when (val result = ServiceContainer.unified.uploadWorkspaceFile(sessionId, part)) {
                    is Resource.Success -> {
                        val baseAttachment = buildWorkspaceChatAttachment(
                            uploadResult = result.data,
                            sessionId = sessionId,
                            originalName = attachment.name,
                            fallbackMime = mime
                        )
                        val uploadedName = baseAttachment["name"]?.toString() ?: attachment.name
                        val uploadedMime = baseAttachment["type"]?.toString() ?: mime
                        if (uploadedMime.startsWith("image/")) {
                            val localFile = if (ServiceContainer.prefs.isLocalMode) {
                                resolveLocalWorkspaceFile(context, sessionId, uploadedName)
                            } else {
                                null
                            }
                            pendingImageAttachments = pendingImageAttachments + buildWorkspaceChatAttachment(
                                uploadResult = result.data,
                                sessionId = sessionId,
                                originalName = uploadedName,
                                fallbackMime = uploadedMime,
                                localPath = localFile?.absolutePath
                            )
                        } else {
                            updateInput(buildString {
                                if (input.isNotBlank()) append(input).append('\n')
                                append(context.getString(R.string.chat_file_uploaded_ref_inline, uploadedName))
                            })
                        }
                    }
                    is Resource.Error -> toast(context.getString(R.string.chat_upload_failed, result.message))
                    is Resource.Loading -> Unit
                }
                withContext(Dispatchers.IO) { runCatching { file.delete() } }
            }
            if (share.attachments.isNotEmpty()) {
                toast(context.getString(R.string.share_imported, share.attachments.size))
            }
        } catch (e: Exception) {
            toast(context.getString(R.string.chat_operation_failed, e.message ?: context.getString(R.string.common_unknown_error)))
        } finally {
            fileBusy = false
            ServiceContainer.consumePendingShare(share.id)
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000)
            recordingDuration++
        }
    }

    LaunchedEffect(showFavorites, favoritesVersion) {
        if (!showFavorites) return@LaunchedEffect
        favoritesLoading = true
        favorites = when (val result = ServiceContainer.unified.listMessageFavorites(sessionId)) {
            is Resource.Success -> {
                val obj = result.data?.takeIf { it.isJsonObject }?.asJsonObject
                val array = obj?.getAsJsonArray("collections") ?: obj?.getAsJsonArray("favorites")
                array?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }.orEmpty()
            }
            is Resource.Error -> {
                toast(context.getString(R.string.chat_load_favorites_failed, result.message))
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
    // 压缩不会改变可见消息数量，只会更新隐藏摘要的边界 source。
    // 将该边界纳入 key，压缩完成后立即重新计算 + 面板中的上下文用量与比例。
    val contextRevision = messages.asReversed()
        .firstOrNull { it.isAgentContextSummary() }
        ?.let { message -> "${message.id}:${message.source}" }
        .orEmpty()

    // 加载激活模型的 maxTokens，用于上下文圆环进度条百分比计算
    var maxTokens by remember { mutableStateOf<Int?>(null) }
    // 当前上下文 Token：本地模式取最近一次完整 prompt usage，避免把每轮累计计费用量重复相加
    var usedTokens by remember { mutableStateOf(0L) }
    // 消息条数、压缩边界或发送状态变化时刷新（远程模式发送后服务端会先写 token 记录）。
    LaunchedEffect(sessionId, messageCount, contextRevision, sending) {
        // 进度条分母：当前激活聊天模型的上下文窗口长度（max_context_length）
        // 本地模式和远程模式均通过 unified.getActiveContextLength() 统一获取
        maxTokens = withContext(Dispatchers.IO) {
            ServiceContainer.unified.getActiveContextLength()
        }
        usedTokens = withContext(Dispatchers.IO) {
            ServiceContainer.unified.sessionContextTokenUsage(sessionId)
        }
    }
    val hasPlotSurface = plotChoicesLoading || plotChoices.isNotEmpty()
    val inputVisible = shouldShowChatInput(
        layoutMode = chatInputLayout,
        inputExpanded = inputExpanded,
        hasPlotSurface = hasPlotSurface,
        hasDraft = input.isNotBlank() || pendingImageAttachments.isNotEmpty()
    )
    // 跳过输入框首次退出动画：进入剧情模式会话时避免选项框从上方滑下的效果
    var skipInputExit by remember { mutableStateOf(true) }
    LaunchedEffect(hasPlotSurface) {
        if (hasPlotSurface) skipInputExit = false
    }
    val toggleInput = {
        if (input.isBlank() && pendingImageAttachments.isEmpty()) {
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
    val commandCandidates = remember(
        input,
        enabledSkills,
        skillsEnabled,
        dismissedCommandCandidateInput
    ) {
        if (input == dismissedCommandCandidateInput) {
            emptyList()
        } else {
            buildChatCommandCandidates(input, enabledSkills, skillsEnabled)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (plotChoicesLoading || plotChoices.isNotEmpty()) {
                // 剧情选项开启时，把草稿统计胶囊挪到选项栏按钮那一排
                val draftStats = if (input.isNotBlank()) {
                    stringResource(R.string.chat_draft_stats, charCount, tokenEstimate)
                } else null
                ModernPlotChoices(
                    loading = plotChoicesLoading,
                    choices = plotChoices,
                    selectedId = pendingPlotChoiceId,
                    enabled = !sending,
                    layoutMode = chatInputLayout,
                    inputVisible = inputVisible,
                    panelExpanded = panelExpanded,
                    sending = sending,
                    draftStats = draftStats,
                    onSelect = { choice ->
                        pendingPlotChoiceId = choice.id
                        updateInput(choice.title)
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
                    usedTokens = usedTokens,
                    maxTokens = maxTokens,
                    sending = sending,
                    fileBusy = fileBusy,
                    plotMode = plotMode,
                    plotRealTimeSync = plotRealTimeSync,
                    reasoningEffort = reasoningEffort,
                    yoloEnabled = yoloEnabled,
                    yoloAvailable = yoloAvailable,
                    onReasoningEffortChange = { effort ->
                        reasoningEffort = effort
                        ServiceContainer.prefs.setReasoningEffort(isAgentSession, effort)
                    },
                    onToggleYolo = onToggleYolo,
                    onCompress = { onCompress() },
                    onOpenContextAnalysis = {
                        panelExpanded = true
                        onOpenContextAnalysis()
                    },
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
                    onTogglePlotMode = onTogglePlotMode,
                    onTogglePlotRealTimeSync = onTogglePlotRealTimeSync,
                    onClear = { showClearConfirm = true }
                )
            }

            AnimatedVisibility(
                visible = inputVisible,
                enter = expandVertically() + fadeIn(),
                exit = if (skipInputExit) ExitTransition.None else shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val composerShape = RoundedCornerShape(30.dp)
                    val composerGlassFill = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            0.52f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                            1f to MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                        )
                    )
                    val composerGlassBorder = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.50f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                    )

                    if (pendingImageAttachments.isNotEmpty()) {
                        PendingImageAttachments(
                            sessionId = sessionId,
                            attachments = pendingImageAttachments,
                            onRemove = { target ->
                                pendingImageAttachments = pendingImageAttachments.filterNot { it === target }
                            }
                        )
                    }

                    if (commandCandidates.isNotEmpty()) {
                        CommandSuggestionPanel(
                            candidates = commandCandidates,
                            showAllCommands = input.trimStart() == "/",
                            onPick = { candidate ->
                                updateInput(candidate.insertion)
                                dismissedCommandCandidateInput = candidate.insertion
                                inputExpanded = true
                                pendingPlotChoiceId = null
                            }
                        )
                    }

                    // 草稿统计：胶囊样式，右对齐悬浮于输入框上方
                    // 剧情选项开启时挪到选项栏按钮排，这里不再显示
                    if (
                        input.isNotBlank() && commandCandidates.isEmpty() &&
                        plotChoices.isEmpty() && !plotChoicesLoading
                    ) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 20.dp, top = 4.dp),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = stringResource(R.string.chat_draft_stats, charCount, tokenEstimate),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .shadow(
                                elevation = 12.dp,
                                shape = composerShape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.06f),
                                spotColor = Color.Black.copy(alpha = 0.09f)
                            )
                            .clip(composerShape)
                            .background(composerGlassFill, composerShape)
                            .actualBorder(1.dp, composerGlassBorder, composerShape)
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
                                        contentDescription = if (expanded) stringResource(R.string.chat_close_action_menu) else stringResource(R.string.chat_more_actions),
                                        tint = panelBtnTint,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp, max = 128.dp)
                                    .clip(RoundedCornerShape(22.dp)),
                                contentAlignment = Alignment.TopStart
                            ) {
                                BasicTextField(
                                    value = inputField,
                                    onValueChange = {
                                        if (panelExpanded) closePanel()
                                        if (it.text != dismissedCommandCandidateInput) {
                                            dismissedCommandCandidateInput = null
                                        }
                                        inputField = it
                                        if (pendingPlotChoiceId != null && it.text != plotChoices.firstOrNull { c -> c.id == pendingPlotChoiceId }?.title) {
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
                                                    text = if (sending) stringResource(R.string.chat_ai_thinking) else stringResource(R.string.chat_input_placeholder),
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
                                input.isNotBlank() || pendingImageAttachments.isNotEmpty() -> ModernComposerAction.SEND
                                else -> ModernComposerAction.VOICE
                            }
                            val idleActionColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                            val actionStartColor by animateColorAsState(
                                targetValue = when (action) {
                                    ModernComposerAction.VOICE -> idleActionColor
                                    ModernComposerAction.SEND -> MaterialTheme.colorScheme.primary
                                    ModernComposerAction.STOP -> MaterialTheme.colorScheme.error
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "composer_action_start"
                            )
                            val actionEndColor by animateColorAsState(
                                targetValue = when (action) {
                                    ModernComposerAction.VOICE -> idleActionColor
                                    ModernComposerAction.SEND -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                                    ModernComposerAction.STOP -> MaterialTheme.colorScheme.error
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
                                            val attachments = pendingImageAttachments
                                            updateInput("")
                                            inputExpanded = false
                                            pendingPlotChoiceId = null
                                            pendingImageAttachments = emptyList()
                                            closePanel()
                                            keyboard?.hide()
                                            onSend(text, choiceId, attachments, reasoningEffort)
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
                                            contentDescription = stringResource(R.string.chat_stop_generation),
                                            tint = Color.White,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        ModernComposerAction.SEND -> Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.chat_send),
                                            tint = Color.White,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        ModernComposerAction.VOICE -> Icon(
                                            Icons.Filled.Mic,
                                            contentDescription = stringResource(R.string.chat_voice_input),
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
            title = { Text(stringResource(R.string.chat_clear_current_session)) },
            text = { Text(stringResource(R.string.chat_clear_current_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClear()
                    }
                ) { Text(stringResource(R.string.common_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showMyMessages) {
        ModernMessageListDialog(
            title = stringResource(R.string.chat_my_messages),
            messages = messages.filter { it.isUser },
            emptyText = stringResource(R.string.chat_no_user_messages_current),
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
            onDismiss = { showSearch = false },
            onResultClick = { msg -> onJumpToMessage(msg) }
        )
    }

    if (showFavorites) {
        ModernFavoritesDialog(
            favorites = favorites,
            loading = favoritesLoading,
            sessionId = sessionId,
            onDismiss = { showFavorites = false },
            onFavoritesChanged = { favoritesVersion++ }
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
            title = { Text(stringResource(R.string.chat_recording_now)) },
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
                TextButton(onClick = { stopAndTranscribe() }) { Text(stringResource(R.string.chat_end_and_recognize)) }
            },
            dismissButton = {
                TextButton(onClick = { cancelRecording() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (voiceTranscribing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.chat_voice_recognizing)) },
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
private fun PendingImageAttachments(
    sessionId: String,
    attachments: List<Map<String, Any>>,
    onRemove: (Map<String, Any>) -> Unit
) {
    val context = LocalContext.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = attachments,
            key = { attachment ->
                "${attachment["name"]}:${attachment["path"]}:${System.identityHashCode(attachment)}"
            }
        ) { attachment ->
            val name = attachment["name"] as? String ?: return@items
            val localFile = (attachment["path"] as? String)
                ?.let { java.io.File(it) }
                ?.takeIf { it.isFile }
                ?: resolveLocalWorkspaceFile(context, sessionId, name)
            val model: Any? = localFile ?: buildWorkspaceFileUrl(sessionId, name)

            Surface(
                modifier = Modifier.size(76.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = null
            ) {
                Box {
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    IconButton(
                        onClick = { onRemove(attachment) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.56f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_remove),
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    draftStats: String? = null,
    onSelect: (PlotChoice) -> Unit,
    onToggleInput: () -> Unit,
    onTogglePanel: () -> Unit,
    onToggleLayout: () -> Unit,
    onRegenerate: () -> Unit,
    onStop: () -> Unit
) {
    var detailChoice by remember { mutableStateOf<PlotChoice?>(null) }
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
                if (loading) stringResource(R.string.chat_plot_choices_loading) else stringResource(R.string.chat_plot_choices),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            // 草稿统计胶囊：剧情选项开启时挪到这里，与按钮组在同一排
            if (draftStats != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = draftStats,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (layoutMode == ChatInputLayoutMode.MERGED) {
                IconButton(onClick = onTogglePanel, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (panelExpanded) Icons.Filled.MoreVert else Icons.Filled.Add,
                        contentDescription = if (panelExpanded) stringResource(R.string.chat_collapse_actions) else stringResource(R.string.chat_expand_actions),
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
                        contentDescription = if (inputVisible) stringResource(R.string.chat_hide_input) else stringResource(R.string.chat_show_input),
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
                        stringResource(R.string.chat_switch_separated)
                    } else {
                        stringResource(R.string.chat_switch_merged)
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            if (sending) {
                IconButton(onClick = onStop, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.chat_stop_generation),
                        tint = MaterialTheme.colorScheme.error,
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
                        contentDescription = stringResource(R.string.chat_regenerate_group),
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
                        "turning_point" -> MaterialTheme.colorScheme.error
                        "important" -> com.nekobot.app.ui.theme.accentWarning()
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val selected = choice.id == selectedId || choice.selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .combinedClickable(
                                enabled = enabled,
                                onClick = { onSelect(choice) },
                                onLongClick = { detailChoice = choice }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) levelColor.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        border = null
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
                                        "turning_point" -> stringResource(R.string.chat_plot_turning)
                                        "important" -> stringResource(R.string.chat_plot_important)
                                        else -> stringResource(R.string.chat_plot_normal)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = levelColor
                                )
                            }
                            Spacer(Modifier.height(3.dp))
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
    }

    // 长按查看完整内容
    detailChoice?.let { choice ->
        val levelColor = when (choice.level) {
            "turning_point" -> MaterialTheme.colorScheme.error
            "important" -> com.nekobot.app.ui.theme.accentWarning()
            else -> MaterialTheme.colorScheme.primary
        }
        AlertDialog(
            onDismissRequest = { detailChoice = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(levelColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (choice.level) {
                            "turning_point" -> stringResource(R.string.chat_plot_turning_option)
                            "important" -> stringResource(R.string.chat_plot_important_option)
                            else -> stringResource(R.string.chat_plot_normal_option)
                        },
                        color = levelColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.chat_plot_title_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        choice.title.ifBlank { stringResource(R.string.chat_plot_no_title) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (choice.description.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.chat_plot_intent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            choice.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailChoice = null }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }
}

@Composable
private fun ReasoningEffortSelector(
    selected: ReasoningEffort,
    enabled: Boolean,
    onSelected: (ReasoningEffort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun label(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.NONE -> stringResource(R.string.reasoning_effort_none)
        ReasoningEffort.MINIMAL -> stringResource(R.string.reasoning_effort_minimal)
        ReasoningEffort.LOW -> stringResource(R.string.reasoning_effort_low)
        ReasoningEffort.MEDIUM -> stringResource(R.string.reasoning_effort_medium)
        ReasoningEffort.HIGH -> stringResource(R.string.reasoning_effort_high)
        ReasoningEffort.MAX -> stringResource(R.string.reasoning_effort_max)
    }

    @Composable
    fun description(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.NONE -> stringResource(R.string.reasoning_effort_none_desc)
        ReasoningEffort.MINIMAL -> stringResource(R.string.reasoning_effort_minimal_desc)
        ReasoningEffort.LOW -> stringResource(R.string.reasoning_effort_low_desc)
        ReasoningEffort.MEDIUM -> stringResource(R.string.reasoning_effort_medium_desc)
        ReasoningEffort.HIGH -> stringResource(R.string.reasoning_effort_high_desc)
        ReasoningEffort.MAX -> stringResource(R.string.reasoning_effort_max_desc)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        border = null
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = !expanded }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reasoning_effort_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = description(selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = selected.wireValue,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    ReasoningEffort.entries.forEach { effort ->
                        val active = effort == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) {
                                    onSelected(effort)
                                    expanded = false
                                }
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (active) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label(effort),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = description(effort),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Text(
                                text = effort.wireValue,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
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
    usedTokens: Long,
    maxTokens: Int?,
    sending: Boolean,
    fileBusy: Boolean,
    plotMode: Boolean,
    plotRealTimeSync: Boolean,
    reasoningEffort: ReasoningEffort,
    yoloEnabled: Boolean,
    yoloAvailable: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onToggleYolo: () -> Unit,
    onCompress: () -> Unit,
    onOpenContextAnalysis: () -> Unit,
    onSendFile: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onSearch: () -> Unit,
    onFavorites: () -> Unit,
    onJumpToLatest: () -> Unit,
    onMyMessages: () -> Unit,
    onUploadOnly: () -> Unit,
    onTogglePlotMode: () -> Unit,
    onTogglePlotRealTimeSync: () -> Unit,
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
                    usedTokens = usedTokens,
                    maxTokens = maxTokens,
                    sending = sending,
                    onCompress = onCompress,
                    onOpenAnalysis = onOpenContextAnalysis
                )
            }
            item { ModernSectionTitle(stringResource(R.string.chat_common_section)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModernQuickAction(
                        icon = Icons.Filled.AttachFile,
                        title = stringResource(R.string.chat_send_file),
                        subtitle = stringResource(R.string.chat_send_file_subtitle),
                        enabled = !fileBusy,
                        onClick = onSendFile,
                        modifier = Modifier.weight(1f)
                    )
                    ModernQuickAction(
                        icon = Icons.Filled.Folder,
                        title = stringResource(R.string.chat_workspace),
                        subtitle = stringResource(R.string.chat_browse_files),
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
                        title = stringResource(R.string.chat_search_dialog),
                        subtitle = stringResource(R.string.chat_search_subtitle),
                        enabled = messageCount > 0,
                        onClick = onSearch,
                        modifier = Modifier.weight(1f)
                    )
                    ModernQuickAction(
                        icon = Icons.Filled.Star,
                        title = stringResource(R.string.chat_favorites),
                        subtitle = stringResource(R.string.chat_favorites_subtitle),
                        enabled = true,
                        onClick = onFavorites,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                ReasoningEffortSelector(
                    selected = reasoningEffort,
                    enabled = !sending,
                    onSelected = onReasoningEffortChange
                )
            }
            if (yoloAvailable) {
                item {
                    ModernToggleRow(
                        icon = Icons.Filled.Terminal,
                        title = stringResource(R.string.chat_yolo_mode),
                        subtitle = stringResource(R.string.chat_yolo_mode_subtitle),
                        checked = yoloEnabled,
                        enabled = !sending,
                        onCheckedChange = { onToggleYolo() }
                    )
                }
            }
            item { ModernSectionTitle(stringResource(R.string.chat_session_tools)) }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.43f)
                ) {
                    Column {
                        ModernToolRow(
                            icon = Icons.Filled.KeyboardDoubleArrowDown,
                            title = stringResource(R.string.chat_jump_to_latest),
                            subtitle = stringResource(R.string.chat_jump_to_latest_subtitle),
                            enabled = messageCount > 0,
                            onClick = onJumpToLatest
                        )
                        ModernMenuDivider()
                        ModernToolRow(
                            icon = Icons.Outlined.AccountTree,
                            title = stringResource(R.string.chat_my_messages),
                            subtitle = stringResource(R.string.chat_my_messages_subtitle),
                            enabled = messageCount > 0,
                            onClick = onMyMessages
                        )
                        ModernMenuDivider()
                        ModernToolRow(
                            icon = Icons.Filled.CloudUpload,
                            title = stringResource(R.string.chat_upload_only),
                            subtitle = stringResource(R.string.chat_upload_only_subtitle),
                            enabled = !fileBusy,
                            onClick = onUploadOnly
                        )
                        ModernMenuDivider()
                        ModernToggleRow(
                            icon = Icons.Filled.AutoAwesome,
                            title = stringResource(R.string.chat_plot_mode),
                            subtitle = stringResource(R.string.chat_plot_mode_subtitle),
                            checked = plotMode,
                            enabled = !sending,
                            onCheckedChange = { onTogglePlotMode() }
                        )
                        if (plotMode) {
                            ModernMenuDivider()
                            ModernToggleRow(
                                icon = Icons.Filled.Schedule,
                                title = stringResource(R.string.chat_plot_realtime_sync),
                                subtitle = stringResource(R.string.chat_plot_realtime_sync_subtitle),
                                checked = plotRealTimeSync,
                                enabled = !sending,
                                onCheckedChange = { onTogglePlotRealTimeSync() }
                            )
                        }
                    }
                }
            }
            item { ModernSectionTitle(stringResource(R.string.chat_danger_zone)) }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.07f)
                ) {
                    ModernToolRow(
                        icon = Icons.Filled.CleaningServices,
                        title = stringResource(R.string.chat_clear_current_session),
                        subtitle = stringResource(R.string.chat_clear_current_subtitle),
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
    usedTokens: Long,
    maxTokens: Int?,
    sending: Boolean,
    onCompress: () -> Unit,
    onOpenAnalysis: () -> Unit
) {
    // 计算已用 Token 占最大上下文的比例；maxTokens 缺省时百分比置 0
    val progress = if (maxTokens != null && maxTokens > 0) {
        (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val percent = (progress * 100).toInt()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        border = null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 圆环进度条：外圈 CircularProgressIndicator，内圈百分比数字
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenAnalysis)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(42.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(42.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                        Text(
                            text = "$percent%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_context_metric),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.chat_context_analysis_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onCompress, enabled = !sending && messageCount > 0) {
                    Icon(Icons.Filled.Compress, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_compress))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernMetric(stringResource(R.string.chat_messages_metric), messageCount.toString(), Modifier.weight(1f))
                ModernMetric(stringResource(R.string.chat_draft_chars), charCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernMetric(stringResource(R.string.chat_used_tokens), usedTokens.toString(), Modifier.weight(1f))
                ModernMetric(
                    label = stringResource(R.string.chat_status),
                    value = if (sending) stringResource(R.string.chat_status_generating) else stringResource(R.string.chat_status_ready),
                    modifier = Modifier.weight(1f)
                )
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

/**
 * 会话工具区带 Switch 的开关行：图标 + 标题/副标题 + 右侧 Switch。
 * 视觉与 [ModernToolRow] 对齐，仅把右箭头换成 Switch。
 */
@Composable
private fun ModernToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
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
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
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
                    itemsIndexed(messages, key = { index, message -> chatMessageItemKey(index, message) }) { _, message ->
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
                                    text = message.displayContent.ifBlank { stringResource(R.string.chat_empty_message) },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } }
    )
}

@Composable
private fun ModernSearchDialog(
    messages: List<Message>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultClick: (Message) -> Unit
) {
    val results = remember(query, messages) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) emptyList()
        else messages.filter {
            it.role != "system" && it.displayContent.lowercase().contains(normalized)
        }.take(80)
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
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.chat_search_placeholder)) },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        when {
            query.isBlank() -> Text(
                stringResource(R.string.chat_search_keyword_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            results.isEmpty() -> Text(
                stringResource(R.string.chat_no_match),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            else -> {
                Text(
                    stringResource(R.string.chat_search_results_count, results.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(results, key = { index, message -> chatMessageItemKey(index, message) }) { _, message ->
                        val role = message.role ?: "unknown"
                        val content = message.displayContent
                        val idx = content.lowercase().indexOf(query.trim().lowercase())
                        val preview = if (idx >= 0) {
                            val start = maxOf(0, idx - 36)
                            val end = minOf(content.length, idx + query.length + 72)
                            (if (start > 0) "..." else "") + content.slice(start until end).replace("\n", " ").trim() + (if (end < content.length) "..." else "")
                        } else content.take(100)
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onResultClick(message)
                                    onDismiss()
                                },
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
                                    Text(
                                        if (message.isUser) stringResource(R.string.chat_me) else stringResource(R.string.chat_ai_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        preview,
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
        }
    }
}

@Composable
private fun ModernFavoritesDialog(
    favorites: List<JsonObject>,
    loading: Boolean,
    sessionId: String,
    onDismiss: () -> Unit,
    onFavoritesChanged: () -> Unit
) {
    var selectedCollection by remember(favorites) { mutableStateOf<JsonObject?>(null) }
    var deletingCollection by remember { mutableStateOf<JsonObject?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 删除确认弹窗
    if (deletingCollection != null) {
        val coll = deletingCollection!!
        val collTitle = coll.get("title")?.takeIf { !it.isJsonNull }?.asString
            ?: coll.get("name")?.takeIf { !it.isJsonNull }?.asString
            ?: stringResource(R.string.chat_unnamed_favorite)
        NekoDialog(
            onDismiss = { if (!deleting) deletingCollection = null },
            title = stringResource(R.string.chat_delete_favorite_title),
            message = stringResource(R.string.chat_delete_favorite_confirm, collTitle),
            confirmText = stringResource(R.string.common_delete),
            confirmEnabled = !deleting,
            onConfirm = {
                val id = coll.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: coll.get("collection_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                if (id.isBlank()) {
                    deletingCollection = null
                    return@NekoDialog
                }
                deleting = true
                scope.launch {
                    val result = runCatching {
                        ServiceContainer.unified.deleteMessageFavorite(sessionId, id)
                    }
                    deleting = false
                    val ok = result.isSuccess && result.getOrNull() is Resource.Success
                    if (ok) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_delete_favorite_success),
                            Toast.LENGTH_SHORT
                        ).show()
                        deletingCollection = null
                        selectedCollection = null
                        onFavoritesChanged()
                    } else {
                        val err = (result.getOrNull() as? Resource.Error)?.message
                            ?: result.exceptionOrNull()?.message
                            ?: context.getString(R.string.chat_favorite_failed)
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_delete_favorite_failed, err),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            cancelText = if (deleting) null else stringResource(R.string.common_cancel),
            onCancel = { deletingCollection = null }
        )
    }

    // 主弹窗
    val selectedTitle = selectedCollection?.let {
        it.get("title")?.takeIf { !it.isJsonNull }?.asString
            ?: it.get("name")?.takeIf { !it.isJsonNull }?.asString
            ?: stringResource(R.string.chat_unnamed_favorite)
    }
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
            // 第二层：消息详情列表
            val collection = selectedCollection!!
            val messages = collection.getAsJsonArray("messages")
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                .orEmpty()
            TextButton(onClick = { selectedCollection = null }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_back_to_favorites))
            }
            if (messages.isEmpty()) {
                Text(
                    stringResource(R.string.chat_favorite_no_messages),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(messages, key = { index, msg ->
                        msg.get("message_id")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                            ?: msg.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                            ?: "favorite-message:$index:${msg.get("timestamp")?.asString.orEmpty()}"
                    }) { _, msg ->
                        ModernFavoriteMessageCard(msg)
                    }
                }
            }
        } else if (favorites.isEmpty()) {
            Text(
                stringResource(R.string.chat_no_favorites_content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            // 第一层：收藏夹列表
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(favorites, key = { index, item ->
                    item.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                        ?: item.get("collection_id")?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                        ?: "favorite:$index:${item.get("title")?.asString.orEmpty()}"
                }) { _, collection ->
                    ModernFavoriteCollectionCard(
                        collection = collection,
                        onClick = { selectedCollection = collection },
                        onDelete = { deletingCollection = collection }
                    )
                }
            }
        }
    }
}

/** 收藏夹列表项：标题 + 创建日期 + 消息数 + 前 2 条消息预览 + 删除按钮。 */
@Composable
private fun ModernFavoriteCollectionCard(
    collection: JsonObject,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val title = collection.get("title")?.takeIf { !it.isJsonNull }?.asString
        ?: collection.get("name")?.takeIf { !it.isJsonNull }?.asString
        ?: stringResource(R.string.chat_unnamed_favorite)
    val createdAt = collection.get("created_at")?.takeIf { !it.isJsonNull }?.asString?.take(10).orEmpty()
    val messages = collection.getAsJsonArray("messages")
        ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
        .orEmpty()
    val count = if (messages.isNotEmpty()) messages.size
        else collection.getAsJsonArray("message_ids")?.size() ?: 0

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 12
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (createdAt.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.chat_favorite_created_at, createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chat_favorite_messages_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            // 前 2 条消息预览
            messages.take(2).forEach { msg ->
                val role = msg.get("role")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val content = msg.get("content")?.takeIf { !it.isJsonNull }?.let {
                    runCatching { it.asString }.getOrElse { it.toString() }
                }.orEmpty()
                Text(
                    "[$role] ${content.take(60)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 收藏夹消息详情卡片：显示角色标签、时间戳、可复制内容。 */
@Composable
private fun ModernFavoriteMessageCard(message: JsonObject) {
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
