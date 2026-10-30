package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.platform.LocalResources

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border as actualBorder
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountTree
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
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonOutline
import com.nekobot.app.ui.components.GlassAlertDialog as AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AgentLiveContextUsage
import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsagePartTokens
import com.nekobot.app.data.local.ai.AgentRunStage
import com.nekobot.app.data.local.ai.isCharacterThinkingSession
import com.nekobot.app.data.local.ChatInputLayoutMode
import com.nekobot.app.data.local.LocalCommandAction
import com.nekobot.app.data.local.LocalCommandSuggestion
import com.nekobot.app.data.local.LocalSlashCommands
import com.nekobot.app.data.local.db.BuiltinTools
import com.nekobot.app.data.local.db.LocalStickerEntity
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.SessionCacheStats
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.AgentToolSetPickerDialog
import com.nekobot.app.ui.components.CharacterThinkingSettingsControls
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.toolSetModeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    onOpenCompanionMemory: (String) -> Unit = {},
    onOpenSessionDetail: (String) -> Unit = {},
    onOpenWorkspace: (String) -> Unit = {},
    onOpenStoryGraph: (String) -> Unit = {},
    onOpenEnvironmentVariables: (String) -> Unit = {},
    onOpenWenku8Login: () -> Unit = {},
    /** 打开表情包管理页（聊天输入区表情面板的「管理」入口）。 */
    onOpenStickers: () -> Unit = {},
    /** 内置浏览器「更多 → 浏览器设置」入口。 */
    onOpenBrowserSettings: () -> Unit = {},
    /**
     * 「跳到最新」处理：null 表示由本页自行滚动到底部。
     *
     * 独立聊天页（NavGraph）传入导航回调以重建页面回到最新；平板双栏嵌入会话页时
     * 不传（null），改为直接滚动右侧消息列表，避免双栏下点击无响应。
     */
    onJumpToLatest: (() -> Unit)? = null,
    /**
     * 底部悬浮导航栏避让间距：平板双栏嵌入会话页时，底栏胶囊悬浮在输入框之上，
     * 传入 `rememberLiquidGlassBottomBarClearance()` 让输入区整体抬升（底栏在平板下更高）；
     * 独立聊天页为 0。
     */
    embeddedBottomBarClearance: Dp = 0.dp
) {
    val textResources = LocalResources.current
    val viewModel: ChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    // 会话级"AI 正在运行"（含后台收尾/唤醒运行）：主操作按钮据此保留停止入口
    val sessionGenerating by viewModel.sessionGenerating.collectAsStateWithLifecycle()
    val delayReplyPending by viewModel.delayReplyPending.collectAsStateWithLifecycle()
    val replyBusy = sending || delayReplyPending
    val compressing by viewModel.agentContextCompressionInProgress.collectAsStateWithLifecycle()
    val plotChoices by viewModel.plotChoices.collectAsStateWithLifecycle()
    val plotChoicesLoading by viewModel.plotChoicesLoading.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    if (session?.companionMode == true && ServiceContainer.prefs.isLocalMode) {
        val stateService = remember { ServiceContainer.localRepository.companionRelationship }
        val relationship by remember(sessionId) { stateService.observe(sessionId) }.collectAsStateWithLifecycle(null)
        val evaluating by stateService.running.collectAsStateWithLifecycle()
        val noticeContext = LocalContext.current
        LaunchedEffect(relationship?.version, relationship?.notice, evaluating) {
            relationship?.let { state ->
                ServiceContainer.localRepository.claimCompanionRelationshipNotice(state, sessionId in evaluating)?.let { reason ->
                    android.widget.Toast.makeText(noticeContext, reason, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val agentRecovery by viewModel.agentRecovery.collectAsStateWithLifecycle()
    val queuedMessages by viewModel.queuedMessages.collectAsStateWithLifecycle()
    // 已导入表情包：名称 → 图片地址，供消息气泡把 `[名称]` 渲染为原图
    val stickers by viewModel.stickers.collectAsStateWithLifecycle()
    val stickerLookup = remember(stickers) {
        buildMap {
            stickers.forEach { sticker -> putIfAbsent(sticker.name, sticker.filePath) }
        }
    }
    // Agent 任务列表（todo_write 工具写入；输入框上方可折叠面板）
    val agentTodos by viewModel.agentTodos.collectAsStateWithLifecycle()
    // Agent 会话目标/规格任务（/goal、/spec 命令设置，输入框上方横幅展示）
    val agentGoal by viewModel.agentGoal.collectAsStateWithLifecycle()
    val agentSpec by viewModel.agentSpec.collectAsStateWithLifecycle()
    val companionName = if (session?.companionMode == true) companionChatTitle(session) else null
    var companionSettingsOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var companionSearchRequest by remember(sessionId) { mutableStateOf(0) }
    var companionFavoritesRequest by remember(sessionId) { mutableStateOf(0) }
    val settingsKeyboard = LocalSoftwareKeyboardController.current
    var companionShowReasoning by remember(sessionId, companionName != null) {
        mutableStateOf(companionName != null && ServiceContainer.localRepository.companionShowReasoning(sessionId))
    }
    val characterThinkingSettings by ServiceContainer.prefs.characterThinkingSettings.collectAsStateWithLifecycle()
    val companionReasoningRevision by ServiceContainer.prefs.reasoningSettingsRevision.collectAsStateWithLifecycle()
    val companionEffort = companionReasoningRevision.let {
        if (companionName != null) ServiceContainer.localRepository.companionReasoningEffort(sessionId) else ReasoningEffort.NONE
    }
    val savedContext = LocalContext.current
    val savedNotice = remember(savedContext) { Toast.makeText(savedContext, textResources.getString(R.string.localai_saved), Toast.LENGTH_SHORT) }
    var showInnerMonologue by remember(sessionId) {
        mutableStateOf(ServiceContainer.localRepository.companionShowInnerMonologue(sessionId))
    }
    var companionUtilityRequest by remember(sessionId) { mutableStateOf<CompanionChatUtility?>(null) }
    val isAgentSession = session?.sessionMode.equals("agent", ignoreCase = true)
    val yoloAvailable = isAgentSession && ServiceContainer.prefs.isLocalMode
    var yoloEnabled by remember(sessionId, yoloAvailable) {
        mutableStateOf(yoloAvailable && ServiceContainer.unified.isYoloEnabled(sessionId))
    }
    val listState = rememberLazyListState()
    val composerScope = rememberCoroutineScope()
    // 「跳到最新」：外部接管时走回调（独立聊天页重建页面回到底部），
    // 否则（平板双栏嵌入会话页）直接滚动右侧消息列表到最后一条。
    val jumpToLatest: () -> Unit = onJumpToLatest ?: {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) composerScope.launch { listState.animateScrollToItem(lastIndex) }
    }

    CompositionLocalProvider(
        LocalStickerLookup provides stickerLookup,
        LocalCompanionChatName provides companionName,
        LocalCompanionShowReasoning provides companionThoughtsEnabled(companionShowReasoning, characterThinkingSettings.enabled),
        com.nekobot.app.ui.components.LocalShowInnerMonologue provides showInnerMonologue,
        com.nekobot.app.ui.components.LocalCompactInnerMonologue provides (companionName != null),
        LocalOpenCompanionMemory provides if (companionName != null) ({ onOpenCompanionMemory(sessionId) }) else null
    ) {
    Box(Modifier.fillMaxSize()) {
    // Keep the chat and its list/composer state alive beneath the full page, but hide it from accessibility.
    Box(if (companionName != null && companionSettingsOpen) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
    ChatScreen(
    sessionId = sessionId,
    onBack = onBack,
    onOpenChat = onOpenChat,
    onOpenSessionDetail = onOpenSessionDetail,
    onOpenWorkspace = onOpenWorkspace,
    onOpenStoryGraph = onOpenStoryGraph,
    onOpenEnvironmentVariables = onOpenEnvironmentVariables,
    onOpenBrowserSettings = onOpenBrowserSettings,
    externalListState = listState,
    onOpenCompanionSettings = { settingsKeyboard?.hide(); companionSettingsOpen = true },
    companionUtilityRequest = companionUtilityRequest,
    onCompanionUtilityHandled = { companionUtilityRequest = null },
    embeddedBottomBarClearance = embeddedBottomBarClearance,
    customBottomBar = {
        Column(
            modifier = Modifier.padding(bottom = embeddedBottomBarClearance)
        ) {
            if (agentRecovery != null && !replyBusy) {
                if (companionName != null) CompanionReplyRecovery(
                    onResume = viewModel::resumeAgentRun,
                    onDismiss = viewModel::discardAgentRun
                ) else AgentRecoveryBar(
                    state = agentRecovery!!,
                    onResume = viewModel::resumeAgentRun,
                    onDiscard = viewModel::discardAgentRun
                )
            }
            // Agent 会话目标/规格任务横幅（/goal、/spec 命令设置）
            if (session?.companionMode != true) GoalSpecBanner(goal = agentGoal, spec = agentSpec)
            // Agent 任务列表（todo_write 工具写入，输入框上方可折叠面板）
            if (session?.companionMode != true) AgentTodosPanel(todos = agentTodos)
            ModernChatComposer(
            modifier = Modifier.fillMaxWidth(),
            sessionId = sessionId,
            session = session,
            companionSearchRequest = companionSearchRequest,
            companionFavoritesRequest = companionFavoritesRequest,
            onDismissCompanionSettings = { companionSettingsOpen = false },
            messages = messages,
            sending = sending,
            delayReplyPending = delayReplyPending,
            sessionGenerating = sessionGenerating,
            compressing = compressing,
            plotChoices = plotChoices,
            plotChoicesLoading = plotChoicesLoading,
            plotMode = session?.plotMode == true,
            plotRealTimeSync = session?.plotRealTimeSync == true,
            isAgentSession = isAgentSession,
            queuedMessages = queuedMessages,
            onSendQueuedNow = viewModel::sendQueuedNow,
            onRemoveQueued = viewModel::removeQueuedMessage,
            yoloEnabled = yoloEnabled,
            yoloAvailable = yoloAvailable,
            onToggleYolo = {
                val enabled = !yoloEnabled
                ServiceContainer.unified.setYoloEnabled(sessionId, enabled)
                yoloEnabled = enabled
            },
            skillsEnabled = isAgentSession,
            stickers = stickers,
            onOpenStickers = onOpenStickers,
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
            onDelayInputContentChanged = viewModel::onChatInputContentChanged,
            shouldKeepComposerOpen = viewModel::shouldUseDelayReplyForInput,
            onStop = viewModel::stop,
            onCompress = viewModel::compressContext,
            onOpenContextAnalysis = { onOpenContextAnalysis(sessionId) },
            onClear = { viewModel.clearMessages(sessionId) },
            onRegeneratePlotChoices = viewModel::regeneratePlotChoices,
            onOpenWorkspace = { onOpenWorkspace(sessionId) },
            onJumpToLatest = jumpToLatest,
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
    if (companionName != null && companionSettingsOpen) {
        CompanionSettingsScreen(
            characterName = companionName,
            portraitUrl = session?.portraitUrl,
            checked = companionShowReasoning,
            onCheckedChange = { show ->
                ServiceContainer.localRepository.setCompanionShowReasoning(sessionId, show)
                companionShowReasoning = show
            },
            onDismiss = { companionSettingsOpen = false },
            onDetails = { onOpenSessionDetail(sessionId) },
            onSearch = { companionSearchRequest++ },
            onFavorites = { companionFavoritesRequest++ },
            onUsage = { onOpenContextAnalysis(sessionId) },
            showInnerMonologue = showInnerMonologue,
            onShowInnerMonologueChange = { show ->
                ServiceContainer.localRepository.setCompanionShowInnerMonologue(sessionId, show)
                showInnerMonologue = show
            },
            reasoningEffort = companionEffort,
            customReasoning = ServiceContainer.localRepository.companionCustomReasoning(sessionId),
            onReasoningEffortChange = { ServiceContainer.localRepository.setCompanionReasoningEffort(sessionId, it) },
            onCustomReasoningChange = { ServiceContainer.localRepository.setCompanionCustomReasoning(sessionId, it) },
            onSaved = { savedNotice.cancel(); savedNotice.show() },
            onArchive = if (!session?.archiveSessionId.isNullOrBlank()) {
                { companionUtilityRequest = CompanionChatUtility.VIEW_ARCHIVE }
            } else null
        )
    }
    }
    }
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

private data class WorkspaceMentionTrigger(
    val start: Int,
    val end: Int,
    val query: String
)

private fun findWorkspaceMention(value: TextFieldValue): WorkspaceMentionTrigger? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val start = value.text.lastIndexOf('@', cursor - 1)
    if (start < 0) return null
    val query = value.text.substring(start + 1, cursor)
    if (query.any(Char::isWhitespace)) return null
    return WorkspaceMentionTrigger(start, cursor, query)
}

private fun replaceWorkspaceMention(
    value: TextFieldValue,
    trigger: WorkspaceMentionTrigger,
    replacement: String
): TextFieldValue {
    val before = value.text.substring(0, trigger.start)
    val after = value.text.substring(trigger.end)
    return TextFieldValue(
        text = before + replacement + after,
        selection = TextRange(before.length + replacement.length)
    )
}

private fun parseWorkspaceMentionFiles(element: JsonElement): List<WorkspaceFile> {
    val files = when {
        element.isJsonArray -> element.asJsonArray
        element.isJsonObject -> element.asJsonObject.getAsJsonArray("files") ?: return emptyList()
        else -> return emptyList()
    }
    return files.mapNotNull { entry ->
        runCatching {
            val file = entry.asJsonObject
            WorkspaceFile(
                name = file.get("name")?.asString.orEmpty(),
                type = file.get("type")?.asString ?: "file",
                size = runCatching { file.get("size")?.asLong ?: 0L }.getOrDefault(0L),
                path = file.get("path")?.asString.orEmpty(),
                mimeType = file.get("mime_type")?.asString.orEmpty(),
                modifiedAt = runCatching { file.get("modified_at")?.asLong ?: 0L }.getOrDefault(0L)
            ).takeIf { it.name.isNotBlank() && it.path.isNotBlank() }
        }.getOrNull()
    }
}

private fun buildChatCommandCandidates(
    input: String,
    skills: List<Skill>,
    skillsEnabled: Boolean,
    context: android.content.Context
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

    val commands = LocalSlashCommands.suggestions(token, context)
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

/**
 * Agent 排队消息条：展示 AI 生成期间排队的待发送消息。
 * 队顶消息提供“立即发送”按钮（本地模式注入下一次模型调用，先于下一次工具调用被 AI 看到）。
 */
@Composable
private fun QueuedMessagesBar(
    items: List<QueuedChatMessage>,
    sending: Boolean,
    onSendNow: (String?) -> Unit,
    onRemove: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.chat_queue_title, items.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.content.ifBlank {
                            stringResource(R.string.chat_queue_attachment_only, item.attachments.size)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (index == 0) {
                        TextButton(
                            onClick = { onSendNow(item.id) },
                            enabled = true,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                stringResource(R.string.chat_queue_send_now),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    IconButton(
                        onClick = { onRemove(item.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.chat_queue_remove),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (index != items.lastIndex) Spacer(Modifier.height(4.dp))
            }
            if (sending) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_queue_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommandSuggestionPanel(
    candidates: List<ChatCommandCandidate>,
    showAllCommands: Boolean,
    onPick: (ChatCommandCandidate) -> Unit
) {
    val listState = rememberLazyListState()
    // 长按选项后展示完整命令说明的弹窗状态
    var detailCandidate by remember { mutableStateOf<ChatCommandCandidate?>(null) }
    LaunchedEffect(candidates) {
        listState.scrollToItem(0)
        if (detailCandidate != null &&
            candidates.none { it.key == detailCandidate!!.key }
        ) {
            detailCandidate = null
        }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            // 长按查看完整说明的提示，帮助用户发现交互方式
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.command_suggestion_detail_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (showAllCommands) 300.dp else 240.dp),
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
            items(candidates, key = ChatCommandCandidate::key) { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPick(candidate) },
                            onLongClick = { detailCandidate = candidate }
                        )
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

    CommandDetailDialog(
        candidate = detailCandidate,
        onDismiss = { detailCandidate = null }
    )
}

@Composable
private fun WorkspaceMentionPanel(
    files: List<WorkspaceFile>,
    currentPath: String,
    query: String,
    loading: Boolean,
    loadFailed: Boolean,
    onNavigateUp: () -> Unit,
    onOpenFolder: (WorkspaceFile) -> Unit,
    onPickFile: (WorkspaceFile) -> Unit
) {
    val visibleFiles = remember(files, query) {
        files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_workspace_mention_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (currentPath.isBlank()) {
                            stringResource(R.string.chat_workspace_mention_hint)
                        } else {
                            currentPath
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (currentPath.isNotBlank()) {
                    IconButton(onClick = onNavigateUp, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_workspace_mention_parent),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (loadFailed) {
                Text(
                    text = stringResource(R.string.chat_workspace_mention_load_failed),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!loading && visibleFiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_workspace_mention_empty),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(visibleFiles, key = WorkspaceFile::path) { file ->
                        val isDirectory = file.isDirectory
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (isDirectory) onOpenFolder(file) else onPickFile(file) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDirectory) Icons.Filled.Folder else Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = if (isDirectory) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isDirectory) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!isDirectory) {
                                    Text(
                                        text = file.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
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

/**
 * 命令/ Skill 选项的完整说明弹窗：长按建议面板条目时展示。
 * [candidate] 为 null 时不渲染。
 */
@Composable
private fun CommandDetailDialog(
    candidate: ChatCommandCandidate?,
    onDismiss: () -> Unit
) {
    if (candidate == null) return
    val title = when (candidate) {
        is ChatCommandCandidate.Command -> candidate.suggestion.command
        is ChatCommandCandidate.SkillCommand -> stringResource(
            R.string.command_suggestion_skill_prefix,
            candidate.skill.name
        )
    }
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.command_suggestion_detail_title),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        contentScrollable = true
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

        // 完整命令说明：短描述 + (命令) 用法/别名
        when (candidate) {
            is ChatCommandCandidate.Command -> {
                val suggestion = candidate.suggestion
                val description = suggestion.detail
                    .takeIf(String::isNotBlank) ?: suggestion.description
                    .takeIf(String::isNotBlank)
                    ?: stringResource(R.string.command_suggestion_hint)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // 别名
                if (suggestion.aliases.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.command_suggestion_aliases,
                            suggestion.aliases.joinToString(" / ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is ChatCommandCandidate.SkillCommand -> {
                val description = candidate.skill.description
                    ?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.command_suggestion_skill_hint)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModernChatComposer(
    modifier: Modifier,
    sessionId: String,
    session: Session?,
    companionSearchRequest: Int,
    companionFavoritesRequest: Int,
    onDismissCompanionSettings: () -> Unit,
    messages: List<Message>,
    sending: Boolean,
    delayReplyPending: Boolean,
    /** 会话级"AI 正在运行"（前台发送 ∨ 全局生成登记）：后台收尾/唤醒运行阶段也显示停止按钮。 */
    sessionGenerating: Boolean,
    /** 上下文压缩进行中：压缩按钮切换为进行中样式。 */
    compressing: Boolean,
    plotChoices: List<PlotChoice>,
    plotChoicesLoading: Boolean,
    plotMode: Boolean,
    plotRealTimeSync: Boolean,
    isAgentSession: Boolean,
    queuedMessages: List<QueuedChatMessage> = emptyList(),
    onSendQueuedNow: (String?) -> Unit = {},
    onRemoveQueued: (String) -> Unit = {},
    yoloEnabled: Boolean,
    yoloAvailable: Boolean,
    skillsEnabled: Boolean,
    /** 已导入的表情包（点选后独立发送 `[名称]`，保留输入框草稿）。 */
    stickers: List<LocalStickerEntity> = emptyList(),
    onOpenStickers: () -> Unit = {},
    onToggleYolo: () -> Unit,
    onSend: (String, String?, List<Map<String, Any>>, ReasoningEffort) -> Unit,
    onDelayInputContentChanged: (Boolean) -> Unit,
    shouldKeepComposerOpen: (String, Boolean) -> Boolean,
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
    // 输入法弹起高度：超过阈值即视为弹起。取动画中的实时高度（而不是等完全收起），
    // 收起后半程就切回单行，避免键盘落下去后输入区还停在双行。
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > with(density) { 64.dp.roundToPx() }
    // 底部避让取「导航栏 ∪ 输入法」而不是两者相加，否则键盘弹起时
    // 输入框与键盘之间会多出一条背景色长条。
    val composerBottomInsets = WindowInsets.navigationBars.union(WindowInsets.ime)

    var inputField by remember(sessionId) {
        mutableStateOf(
            TextFieldValue(ServiceContainer.prefs.getChatInputDraft(sessionId))
        )
    }
    val input = inputField.text
    val workspaceMentionTrigger = remember(inputField) { findWorkspaceMention(inputField) }
    // 命令胶囊：/goal、/spec 前缀在输入框内替换为彩色胶囊（视觉变换隐藏命令字符，
    // 胶囊由 decorationBox 在同样位置绘制；发送内容不受影响，仍是原始文本）
    val commandCapsule = remember(input) { matchCommandCapsule(input) }
    val commandCapsuleTransformation = remember { commandCapsuleVisualTransformation() }
    // 程序化更新输入内容时光标置于末尾，方便继续输入
    fun updateInput(text: String) {
        inputField = TextFieldValue(text, TextRange(text.length))
    }
    // 输入框草稿持久化：退出会话后保留
    LaunchedEffect(input, sessionId) {
        ServiceContainer.prefs.setChatInputDraft(sessionId, input)
    }
    LaunchedEffect(input.isNotBlank()) {
        onDelayInputContentChanged(input.isNotBlank())
    }
    val companionChat = session?.companionMode == true
    var panelExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    // P0 模型弹窗开关：状态提到输入区外层，输入法收起导致工具行折叠时弹窗不会被一起销毁
    var showChatModelDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    // 表情包选择面板开关
    var stickerPickerOpen by remember { mutableStateOf(false) }
    // 输入框下方的快捷工具栏：按钮配置与折叠状态全局持久化
    var quickActions by remember {
        mutableStateOf(
            ChatQuickAction.normalize(
                ServiceContainer.prefs.getChatQuickActions() ?: ChatQuickAction.defaultIds
            )
        )
    }
    var quickActionsCollapsed by remember { mutableStateOf(ServiceContainer.prefs.chatQuickActionsCollapsed) }
    var showQuickActionEditor by remember { mutableStateOf(false) }
    val reasoningPrefs = ServiceContainer.prefs
    val reasoningRevision by reasoningPrefs.reasoningSettingsRevision.collectAsStateWithLifecycle()
    val companionReasoningRepository = if (companionChat && reasoningPrefs.isLocalMode) ServiceContainer.localRepository else null
    val readReasoningEffort = {
        companionReasoningRepository?.companionReasoningEffort(sessionId) ?: reasoningPrefs.getReasoningEffort(isAgentSession)
    }
    val reasoningEffort = reasoningRevision.let { readReasoningEffort() }
    var inputExpanded by remember { mutableStateOf(false) }
    var chatInputLayout by remember {
        mutableStateOf(ServiceContainer.prefs.chatInputLayoutMode)
    }
    var pendingPlotChoiceId by remember { mutableStateOf<String?>(null) }
    var filePickMode by remember { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    // 待发送附件：图片显示缩略图，其他文件显示文件卡片（不再把引用写进输入框）
    // 只暂存在应用私有目录并按会话持久化，退出会话重进后依然保留；发送时才真正上传到工作区
    var pendingAttachments by remember(sessionId) {
        mutableStateOf(PendingAttachmentStore.load(context, sessionId))
    }

    // 统一的候选附件写入：同步持久化，并删除被移除附件的暂存文件
    fun applyPendingAttachments(next: List<Map<String, Any>>) {
        val removed = pendingAttachments.filter { previous -> next.none { it === previous } }
        pendingAttachments = next
        PendingAttachmentStore.save(context, sessionId, next)
        removed.forEach { previous -> PendingAttachmentStore.deleteStaged(context, previous["path"] as? String) }
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showMyMessages by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFavorites by remember { mutableStateOf(false) }
    LaunchedEffect(companionSearchRequest) {
        if (companionSearchRequest > 0) showSearch = true
    }
    LaunchedEffect(companionFavoritesRequest) {
        if (companionFavoritesRequest > 0) showFavorites = true
    }
    var favoritesLoading by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var favoritesVersion by remember { mutableStateOf(0) }
    // Agent 会话工具集选择弹窗（+ 面板 → Agent 工具）
    var showToolSetDialog by remember(sessionId, isAgentSession) { mutableStateOf(false) }
    // 工具集改动后的展示摘要，供 + 面板入口行动态刷新
    var toolSetRevision by remember(sessionId) { mutableStateOf(0) }
    var enabledSkills by remember(sessionId) { mutableStateOf<List<Skill>>(emptyList()) }
    var dismissedCommandCandidateInput by remember(sessionId) { mutableStateOf<String?>(null) }
    var workspaceMentionPath by rememberSaveable(sessionId) { mutableStateOf("") }
    var workspaceMentionFiles by remember(sessionId) { mutableStateOf<List<WorkspaceFile>>(emptyList()) }
    var workspaceMentionLoading by remember(sessionId) { mutableStateOf(false) }
    var workspaceMentionLoadFailed by remember(sessionId) { mutableStateOf(false) }

    LaunchedEffect(sessionId, workspaceMentionPath, workspaceMentionTrigger != null) {
        if (workspaceMentionTrigger == null) return@LaunchedEffect
        workspaceMentionFiles = emptyList()
        workspaceMentionLoading = true
        workspaceMentionLoadFailed = false
        try {
            when (val result = ServiceContainer.unified.listWorkspaceFiles(
                sessionId,
                workspaceMentionPath.ifBlank { null }
            )) {
                is Resource.Success -> workspaceMentionFiles = parseWorkspaceMentionFiles(result.data)
                is Resource.Error -> workspaceMentionLoadFailed = true
                Resource.Loading -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            workspaceMentionLoadFailed = true
        } finally {
            workspaceMentionLoading = false
        }
    }

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

    // 批量上传：逐个上传选中的文件，全部结束后按结果汇总提示一次
    // 选中的图片/文件只复制到应用私有暂存目录，作为候选附件等待发送（不立即上传工作区）
    fun stagePickedUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        fileBusy = true
        scope.launch {
            var addedCount = 0
            var failedCount = 0
            try {
                val staged = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val payload = modernReadUriFile(context, uri)
                        if (payload == null) {
                            failedCount++
                            return@mapNotNull null
                        }
                        val (name, bytes) = payload
                        PendingAttachmentStore.stage(context, sessionId, name, modernGuessMime(name), bytes)
                            ?: run {
                                failedCount++
                                null
                            }
                    }
                }
                addedCount = staged.size
                if (staged.isNotEmpty()) applyPendingAttachments(pendingAttachments + staged)
            } finally {
                fileBusy = false
            }
            if (addedCount > 0) toast(context.getString(R.string.chat_attachments_added, addedCount))
            if (failedCount > 0) toast(context.getString(R.string.chat_attachments_failed, failedCount))
        }
    }

    // 快捷工具栏的“上传到工作区”：用户主动上传，立即传到工作区，不进入候选附件
    fun uploadPickedUrisToWorkspace(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        fileBusy = true
        scope.launch {
            var uploadedCount = 0
            var failedCount = 0
            try {
                uris.forEach { uri ->
                    try {
                        val payload = withContext(Dispatchers.IO) { modernReadUriFile(context, uri) }
                        if (payload == null) {
                            failedCount++
                            return@forEach
                        }
                        val (name, bytes) = payload
                        val part = MultipartBody.Part.createFormData(
                            "file",
                            name,
                            bytes.toRequestBody(modernGuessMime(name).toMediaTypeOrNull())
                        )
                        when (ServiceContainer.unified.uploadWorkspaceFile(sessionId, part)) {
                            is Resource.Success -> uploadedCount++
                            is Resource.Error -> failedCount++
                            Resource.Loading -> Unit
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedCount++
                    }
                }
            } finally {
                fileBusy = false
            }
            if (uploadedCount > 0) toast(context.getString(R.string.chat_attachments_uploaded, uploadedCount))
            if (failedCount > 0) toast(context.getString(R.string.chat_attachments_failed, failedCount))
        }
    }

    // 发送前把候选附件真正上传到工作区；任一个失败则返回 null（保留候选附件，输入内容不丢）
    suspend fun uploadPendingAttachments(source: List<Map<String, Any>>): List<Map<String, Any>>? {
        if (source.isEmpty()) return emptyList()
        val uploaded = mutableListOf<Map<String, Any>>()
        var failedCount = 0
        for (attachment in source) {
            val name = attachment["name"] as? String ?: continue
            val path = attachment["path"] as? String
            val file = path?.let { java.io.File(it) }?.takeIf { it.isFile }
            if (file == null) {
                failedCount++
                continue
            }
            try {
                val mime = attachment["type"] as? String ?: modernGuessMime(name)
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val part = MultipartBody.Part.createFormData(
                    "file",
                    name,
                    bytes.toRequestBody(mime.toMediaTypeOrNull())
                )
                when (val result = ServiceContainer.unified.uploadWorkspaceFile(sessionId, part)) {
                    is Resource.Success -> {
                        val baseAttachment = buildWorkspaceChatAttachment(
                            uploadResult = result.data,
                            sessionId = sessionId,
                            originalName = name,
                            fallbackMime = mime
                        )
                        val uploadedName = baseAttachment["name"]?.toString() ?: name
                        val uploadedMime = baseAttachment["type"]?.toString() ?: mime
                        // 本地模式记录工作区绝对路径，供文件卡片与附件解析使用
                        val localFile = if (ServiceContainer.prefs.isLocalMode) {
                            resolveLocalWorkspaceFile(context, sessionId, uploadedName)
                        } else {
                            null
                        }
                        uploaded += buildWorkspaceChatAttachment(
                            uploadResult = result.data,
                            sessionId = sessionId,
                            originalName = uploadedName,
                            fallbackMime = uploadedMime,
                            localPath = localFile?.absolutePath
                        )
                    }
                    is Resource.Error -> failedCount++
                    Resource.Loading -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedCount++
            }
        }
        if (failedCount > 0) {
            toast(context.getString(R.string.chat_attachments_failed, failedCount))
            return null
        }
        return uploaded
    }

    // 图片：系统照片选择器，可一次多选
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH_ATTACHMENT_PICK)
    ) { uris ->
        val mode = filePickMode ?: "send"
        filePickMode = null
        if (mode == "upload") uploadPickedUrisToWorkspace(uris) else stagePickedUris(uris)
    }

    // 文件：系统文档选择器，可一次多选
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val mode = filePickMode ?: "send"
        filePickMode = null
        if (mode == "upload") uploadPickedUrisToWorkspace(uris) else stagePickedUris(uris)
    }

    val pendingShare by ServiceContainer.pendingShare.collectAsStateWithLifecycle()
    var confirmedIncomingShareId by remember { mutableStateOf<String?>(null) }
    val shareAwaitingUploadConfirmation = pendingShare?.takeIf {
        it.attachments.isNotEmpty() && confirmedIncomingShareId != it.id
    }
    if (shareAwaitingUploadConfirmation != null) {
        AlertDialog(
            onDismissRequest = { ServiceContainer.consumePendingShare(shareAwaitingUploadConfirmation.id) },
            title = { Text(context.getString(R.string.share_upload_confirmation_title)) },
            text = {
                Text(
                    context.getString(
                        R.string.share_upload_confirmation_message,
                        shareAwaitingUploadConfirmation.attachments.size
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmedIncomingShareId = shareAwaitingUploadConfirmation.id }) {
                    Text(context.getString(R.string.share_upload_confirmation_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { ServiceContainer.consumePendingShare(shareAwaitingUploadConfirmation.id) }) {
                    Text(context.getString(R.string.common_cancel))
                }
            }
        )
    }
    LaunchedEffect(sessionId, pendingShare?.id, confirmedIncomingShareId) {
        val share = pendingShare ?: return@LaunchedEffect
        if (share.attachments.isNotEmpty() && confirmedIncomingShareId != share.id) return@LaunchedEffect
        fileBusy = share.attachments.isNotEmpty()
        try {
            if (share.text.isNotBlank()) {
                updateInput(buildString {
                    if (input.isNotBlank()) append(input).append('\n')
                    append(share.text)
                })
            }
            // 分享进来的文件只复制到暂存目录，作为候选附件等待发送，不立即上传工作区
            val staged = withContext(Dispatchers.IO) {
                share.attachments.mapNotNull { attachment ->
                    val file = java.io.File(attachment.localPath)
                    if (!file.isFile) {
                        return@mapNotNull null
                    }
                    val mime = attachment.mimeType.ifBlank { modernGuessMime(attachment.name) }
                    PendingAttachmentStore
                        .stageFromFile(context, sessionId, file, attachment.name, mime)
                        ?.also { runCatching { file.delete() } }
                }
            }
            if (staged.isNotEmpty()) applyPendingAttachments(pendingAttachments + staged)
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
    val replyBusy = sending || delayReplyPending
    // 快捷工具栏动作分发：复用 + 面板与现有回调的同一批行为
    val handleQuickAction: (String) -> Unit = { id ->
        when (id) {
            ChatQuickAction.STICKER -> {
                closePanel()
                keyboard?.hide()
                stickerPickerOpen = true
            }
            ChatQuickAction.IMAGE -> {
                filePickMode = "send"
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
            ChatQuickAction.FILE -> {
                filePickMode = "send"
                pickFile.launch("*/*")
            }
            ChatQuickAction.UPLOAD -> {
                filePickMode = "upload"
                pickFile.launch("*/*")
            }
            ChatQuickAction.WORKSPACE -> onOpenWorkspace()
            ChatQuickAction.SEARCH -> showSearch = true
            ChatQuickAction.FAVORITES -> showFavorites = true
            ChatQuickAction.MY_MESSAGES -> showMyMessages = true
            ChatQuickAction.CONTEXT -> onOpenContextAnalysis()
            ChatQuickAction.COMPRESS -> {
                // AI 持续 loop 期间禁用（按钮已置灰，这里兜底防御）
                if (session?.companionMode != true && !compressing && !replyBusy) onCompress()
            }
            ChatQuickAction.CLEAR -> showClearConfirm = true
            ChatQuickAction.LATEST -> onJumpToLatest()
        }
    }
    // 分页后 messages 只含已加载窗口；消息总数优先取会话计数（由写入路径维护），保证面板展示完整总数
    val messageCount = maxOf(messages.count { !it.isThinkingCard }, session?.messageCount ?: 0)
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
    // Agent 运行中的实时上下文快照（stage / 工具名 / 已完成工具数 / 上下文构成）
    var agentLiveContext by remember { mutableStateOf<AgentLiveContextUsage?>(null) }
    // + 面板内的上下文构成分析（类型占比）；Agent 会话始终与圆环同源
    var contextBreakdown by remember { mutableStateOf<ContextUsageBreakdown?>(null) }
    // 会话缓存命中统计：命中率 = 命中缓存的输入 token / 完整输入 token
    var cacheStats by remember { mutableStateOf<SessionCacheStats?>(null) }
    // 轮询时始终读取最新消息/会话，避免 LaunchedEffect 闭包捕获过期列表
    val latestMessages by rememberUpdatedState(messages)
    val latestSession by rememberUpdatedState(session)
    // Agent 运行中的上下文刷新间隔。运行中每轮工具调用都会即时落库，
    // 因此这里周期性读取的就是最新轨迹，而不是等落库才更新。
    val liveContextRefreshIntervalMs = 2000L
    // 消息条数、压缩边界或发送状态变化时刷新（远程模式发送后服务端会先写 token 记录）。
    // Agent 会话始终使用 agentLiveContextUsage（含工具定义与工具轨迹），圆环与
    // + 面板占比分母同源；运行期间按 [liveContextRefreshIntervalMs] 持续重算。
    LaunchedEffect(sessionId, messageCount, contextRevision, sending) {
        // 进度条分母：当前激活聊天模型的上下文窗口长度（max_context_length）
        // 本地模式和远程模式均通过 unified.getActiveContextLength() 统一获取
        maxTokens = withContext(Dispatchers.IO) {
            ServiceContainer.unified.getActiveContextLength()
        }
        while (true) {
            // 运行中持续刷新；空闲时只算一次（含中断后仍留在库里的工具轨迹）
            val keepPolling = sending
            withContext(Dispatchers.IO) {
                if (isAgentSession) {
                    val liveUsage = ServiceContainer.unified.agentLiveContextUsage(sessionId)
                    // 服务端模式没有本地工具信息，构成回退为按消息估算
                    val liveBreakdown = liveUsage.breakdown.takeIf { liveUsage.companionSnapshotMode || !it.isEmpty }
                    usedTokens = if (liveBreakdown != null) {
                        liveUsage.totalTokens
                    } else {
                        ServiceContainer.unified.sessionContextTokenUsage(sessionId)
                    }
                    agentLiveContext = liveUsage
                    contextBreakdown = liveBreakdown
                        ?: fallbackContextUsageBreakdown(latestSession, latestMessages)
                            .takeIf { !it.isEmpty }
                } else {
                    usedTokens = ServiceContainer.unified.sessionContextTokenUsage(sessionId)
                    agentLiveContext = null
                    contextBreakdown = fallbackContextUsageBreakdown(latestSession, latestMessages)
                }
                // 缓存命中率与上方用量同源刷新：本地模式读本地用量记录，服务商未上报时为 null
                cacheStats = ServiceContainer.unified.sessionCacheStats(sessionId)
            }
            if (!keepPolling) break
            delay(liveContextRefreshIntervalMs)
        }
    }
    val hasPlotSurface = plotChoicesLoading || plotChoices.isNotEmpty()
    val inputVisible = companionChat || shouldShowChatInput(
        layoutMode = chatInputLayout,
        inputExpanded = inputExpanded,
        hasPlotSurface = hasPlotSurface,
        hasDraft = input.isNotBlank() || pendingAttachments.isNotEmpty()
    )
    // 跳过输入框首次退出动画：进入剧情模式会话时避免选项框从上方滑下的效果
    var skipInputExit by remember { mutableStateOf(true) }
    LaunchedEffect(hasPlotSurface) {
        if (hasPlotSurface) skipInputExit = false
    }
    val toggleInput = {
        if (input.isBlank() && pendingAttachments.isEmpty()) {
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
        dismissedCommandCandidateInput,
        workspaceMentionTrigger
    ) {
        if (input == dismissedCommandCandidateInput || workspaceMentionTrigger != null) {
            emptyList()
        } else {
            buildChatCommandCandidates(input, enabledSkills, skillsEnabled, context)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (companionChat) companionChatPalette().background else Color.Transparent,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(composerBottomInsets)
        ) {
            if (!companionChat && (plotChoicesLoading || plotChoices.isNotEmpty())) {
                ModernPlotChoices(
                    loading = plotChoicesLoading,
                    choices = plotChoices,
                    selectedId = pendingPlotChoiceId,
                    enabled = !replyBusy,
                    layoutMode = chatInputLayout,
                    inputVisible = inputVisible,
                    panelExpanded = panelExpanded,
                    sending = replyBusy,
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

            if (isAgentSession && queuedMessages.isNotEmpty()) {
                // Agent 排队消息条：生成期间发送的消息在此排队，队顶可“立即发送”注入
                QueuedMessagesBar(
                    items = queuedMessages,
                    sending = replyBusy,
                    onSendNow = onSendQueuedNow,
                    onRemove = onRemoveQueued
                )
            }

            val actionPanel: @Composable (Boolean) -> Unit = { inSheet ->
                ModernChatActionPanel(
                    showClearAction = !companionChat,
                    inSheet = inSheet,
                    sessionId = sessionId,
                    modelSwitchEnabled = !sessionGenerating && !replyBusy && !compressing,
                    messageCount = messageCount,
                    cacheHitRate = cacheStats?.hitRate,
                    usedTokens = usedTokens,
                    maxTokens = maxTokens,
                    agentLiveContext = agentLiveContext,
                    contextBreakdown = contextBreakdown,
                    sending = sending,
                    delayPending = delayReplyPending,
                    compressing = compressing,
                    fileBusy = fileBusy,
                    plotMode = plotMode,
                    plotRealTimeSync = plotRealTimeSync,
                    reasoningEffort = reasoningEffort,
                    yoloEnabled = yoloEnabled,
                    yoloAvailable = yoloAvailable,
                    toolSetSummary = rememberToolSetSummary(sessionId, toolSetRevision),
                    toolSetAvailable = isAgentSession && ServiceContainer.prefs.isLocalMode,
                    onReasoningEffortChange = { effort ->
                        companionReasoningRepository?.setCompanionReasoningEffort(sessionId, effort)
                            ?: reasoningPrefs.setReasoningEffort(isAgentSession, effort)
                    },
                    customReasoning = companionReasoningRepository?.companionCustomReasoning(sessionId) ?: reasoningPrefs.getCustomReasoning(isAgentSession),
                    onCustomReasoningChange = {
                        companionReasoningRepository?.setCompanionCustomReasoning(sessionId, it)
                            ?: reasoningPrefs.setCustomReasoning(isAgentSession, it)
                    },
                    showCharacterThinkingSettings = isCharacterThinkingSession(session),
                    onToggleYolo = onToggleYolo,
                    onOpenToolSet = {
                        if (companionChat) onDismissCompanionSettings()
                        closePanel()
                        showToolSetDialog = true
                    },
                    onCompress = { onCompress() },
                    allowCompression = session?.companionMode != true,
                    onOpenContextAnalysis = {
                        if (companionChat) onDismissCompanionSettings()
                        closePanel()
                        onOpenContextAnalysis()
                    },
                    onSendFile = {
                        if (companionChat) onDismissCompanionSettings()
                        closePanel()
                        filePickMode = "send"
                        pickFile.launch("*/*")
                    },
                    onOpenWorkspace = { if (companionChat) onDismissCompanionSettings(); closePanel(); onOpenWorkspace() },
                    onSearch = { if (companionChat) onDismissCompanionSettings(); closePanel(); showSearch = true },
                    onFavorites = { if (companionChat) onDismissCompanionSettings(); closePanel(); showFavorites = true },
                    onJumpToLatest = { if (companionChat) onDismissCompanionSettings(); closePanel(); onJumpToLatest() },
                    onMyMessages = { if (companionChat) onDismissCompanionSettings(); closePanel(); showMyMessages = true },
                    onUploadOnly = {
                        if (companionChat) onDismissCompanionSettings()
                        closePanel()
                        filePickMode = "upload"
                        pickFile.launch("*/*")
                    },
                    onTogglePlotMode = onTogglePlotMode,
                    onTogglePlotRealTimeSync = onTogglePlotRealTimeSync,
                    onClear = { closePanel(); showClearConfirm = true }
                )
            }
            // + 号菜单：改为抽屉（底部弹层）弹出，不再内联展开挤压消息列表
            if (!companionChat && panelExpanded) {
                ModalBottomSheet(
                    onDismissRequest = closePanel,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.surface,
                    dragHandle = { BottomSheetDefaults.DragHandle() }
                ) {
                    actionPanel(true)
                }
            }

            AnimatedVisibility(
                visible = inputVisible,
                enter = expandVertically() + fadeIn(),
                exit = if (skipInputExit) ExitTransition.None else shrinkVertically() + fadeOut()
            ) {
                if (companionChat) {
                    Column(Modifier.fillMaxWidth()) {
                        if (pendingAttachments.isNotEmpty()) {
                            PendingChatAttachments(sessionId = sessionId, attachments = pendingAttachments,
                                onRemove = { target ->
                                    applyPendingAttachments(pendingAttachments.filterNot { it === target })
                                })
                        }
                        CompanionChatInput(
                            value = inputField,
                            onValueChange = {
                                closePanel()
                                inputField = it
                                if (pendingPlotChoiceId != null && it.text != plotChoices.firstOrNull { c -> c.id == pendingPlotChoiceId }?.title) {
                                    pendingPlotChoiceId = null
                                }
                            },
                            hasAttachments = pendingAttachments.isNotEmpty(),
                            busy = replyBusy || sessionGenerating,
                            enabled = delayReplyPending || !sending || isAgentSession,
                            attachmentsOpen = panelExpanded,
                            onAttachments = togglePanel,
                            onSticker = { handleQuickAction(ChatQuickAction.STICKER) },
                            onVoice = { requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                            onStop = onStop,
                            onSend = send@{
                                // 附件上传中：忽略重复点击，避免重复发送
                                if (fileBusy) return@send
                                val text = input
                                val choiceId = pendingPlotChoiceId
                                val attachments = pendingAttachments
                                // 候选附件在点击发送时才上传到工作区；上传失败则保留草稿与附件
                                val dispatch: (List<Map<String, Any>>) -> Unit = { ready ->
                                    val keepComposerOpen = shouldKeepComposerOpen(text, choiceId == null)
                                    updateInput("")
                                    inputExpanded = keepComposerOpen
                                    pendingPlotChoiceId = null
                                    applyPendingAttachments(emptyList())
                                    closePanel()
                                    onSend(text, null, ready, readReasoningEffort())
                                }
                                if (attachments.isEmpty()) {
                                    dispatch(emptyList())
                                } else {
                                    fileBusy = true
                                    scope.launch {
                                        val ready = uploadPendingAttachments(attachments)
                                        fileBusy = false
                                        if (ready != null) dispatch(ready)
                                    }
                                }
                            }
                        )
                        AnimatedVisibility(visible = panelExpanded) {
                            CompanionAttachmentPanel(enabled = !fileBusy,
                                onImage = { closePanel(); handleQuickAction(ChatQuickAction.IMAGE) },
                                onFile = { closePanel(); handleQuickAction(ChatQuickAction.FILE) })
                        }
                    }
                } else Column(modifier = Modifier.fillMaxWidth()) {
                    val composerShape = RoundedCornerShape(30.dp)
                    val composerGlassFill = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            0.52f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                            1f to MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                        )
                    )
                    // 输入框描边：与底栏液态玻璃一致——深色模式下白边必须非常克制
                    // （淡化 + 收细），否则整块输入框像被镶了一圈银边。
                    val composerDark = isSystemInDarkTheme()
                    val composerBorderWidth = if (composerDark) 0.6.dp else 1.dp
                    val composerGlassBorder = Brush.verticalGradient(
                        if (composerDark) {
                            listOf(
                                Color.White.copy(alpha = 0.14f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                Color.White.copy(alpha = 0.03f)
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.50f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        }
                    )

                    if (pendingAttachments.isNotEmpty()) {
                        PendingChatAttachments(
                            sessionId = sessionId,
                            attachments = pendingAttachments,
                            onRemove = { target ->
                                applyPendingAttachments(pendingAttachments.filterNot { it === target })
                            }
                        )
                    }

                    if (workspaceMentionTrigger != null) {
                        WorkspaceMentionPanel(
                            files = workspaceMentionFiles,
                            currentPath = workspaceMentionPath,
                            query = workspaceMentionTrigger.query,
                            loading = workspaceMentionLoading,
                            loadFailed = workspaceMentionLoadFailed,
                            onNavigateUp = {
                                workspaceMentionPath = workspaceMentionPath.substringBeforeLast('/', "")
                                inputField = replaceWorkspaceMention(inputField, workspaceMentionTrigger, "@")
                            },
                            onOpenFolder = { folder ->
                                workspaceMentionPath = folder.path
                                inputField = replaceWorkspaceMention(inputField, workspaceMentionTrigger, "@")
                                inputExpanded = true
                            },
                            onPickFile = { file ->
                                inputField = replaceWorkspaceMention(
                                    inputField,
                                    workspaceMentionTrigger,
                                    "@${file.path} "
                                )
                                dismissedCommandCandidateInput = null
                                inputExpanded = true
                                pendingPlotChoiceId = null
                            }
                        )
                    } else if (commandCandidates.isNotEmpty()) {
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

                    // 输入框 + 工具栏底衬：工具栏展开时表现为输入框底部延伸出的圆角阴影层。
                    // 收起/展开时底衬高度跟着内容一起伸缩，看起来像阴影矩形在输入框下方上下移动。
                    val quickBarVisible = !quickActionsCollapsed && !imeVisible
                    // 只动画透明度：如果直接插值到 Color.Transparent（透明黑），中途会闪过一层暗色。
                    val quickBarShellAlpha by animateFloatAsState(
                        targetValue = if (quickBarVisible) 0.62f else 0f,
                        animationSpec = tween(200),
                        label = "quick_bar_shell_alpha"
                    )
                    val quickBarShell = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = quickBarShellAlpha)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                // 输入法弹起时输入区直接贴住键盘，底部不再留出背景色长条
                                bottom = if (imeVisible) 0.dp else 8.dp
                            )
                            .background(quickBarShell, composerShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(
                                    elevation = 12.dp,
                                    shape = composerShape,
                                    clip = false,
                                    ambientColor = Color.Black.copy(alpha = 0.06f),
                                    spotColor = Color.Black.copy(alpha = 0.09f)
                                )
                                .clip(composerShape)
                                .background(composerGlassFill, composerShape)
                                .actualBorder(composerBorderWidth, composerGlassBorder, composerShape)
                        ) {
                            // 主操作按钮：背景和图标同步过渡，避免语音/发送/停止状态生硬跳变
                            // Agent 会话生成中：已输入内容时切换为发送（消息进入排队队列），否则保持停止
                            // 后台生成（标题总结/剧情选项/记忆沉淀/唤醒运行等）进行中：无草稿时也提供停止入口
                            val hasDraft = input.isNotBlank() || pendingAttachments.isNotEmpty()
                            val action = when {
                                delayReplyPending && hasDraft -> ModernComposerAction.SEND
                                delayReplyPending -> ModernComposerAction.STOP
                                sending && isAgentSession && hasDraft -> ModernComposerAction.SEND
                                sending -> ModernComposerAction.STOP
                                hasDraft -> ModernComposerAction.SEND
                                sessionGenerating -> ModernComposerAction.STOP
                                else -> ModernComposerAction.VOICE
                            }
                            val onPrimaryAction: () -> Unit = {
                                when (action) {
                                    ModernComposerAction.STOP -> onStop()
                                    // 附件上传中：忽略重复点击，避免重复发送
                                    ModernComposerAction.SEND -> if (!fileBusy) {
                                        val text = input
                                        val choiceId = pendingPlotChoiceId
                                        val attachments = pendingAttachments
                                        // 候选附件在点击发送时才上传到工作区；上传失败则保留草稿与附件
                                        val dispatch: (List<Map<String, Any>>) -> Unit = { ready ->
                                            val keepComposerOpen = shouldKeepComposerOpen(
                                                text,
                                                choiceId == null
                                            )
                                            updateInput("")
                                            inputExpanded = keepComposerOpen
                                            pendingPlotChoiceId = null
                                            applyPendingAttachments(emptyList())
                                            closePanel()
                                            if (!keepComposerOpen) keyboard?.hide()
                                            onSend(text, choiceId, ready, readReasoningEffort())
                                        }
                                        if (attachments.isEmpty()) {
                                            dispatch(emptyList())
                                        } else {
                                            fileBusy = true
                                            scope.launch {
                                                val ready = uploadPendingAttachments(attachments)
                                                fileBusy = false
                                                if (ready != null) dispatch(ready)
                                            }
                                        }
                                    }
                                    ModernComposerAction.VOICE -> requestMicPermission.launch(
                                        android.Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            }
                            Column(modifier = Modifier.padding(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 面板开关按钮：展开/收起时背景与图标颜色平滑过渡。
                                    // 用 AnimatedVisibility 包裹（调用点始终存在），输入法弹起后按钮收进下方工具行；
                                    // 这里只做尺寸动画，后面的输入框节点不会被重建，焦点与输入法状态因此不会丢失。
                                    // 淡出先于收宽结束，避免看到硬边裁切（「多边形」）的过渡。
                                    AnimatedVisibility(
                                        visible = !imeVisible,
                                        enter = expandHorizontally(animationSpec = tween(200)) +
                                            fadeIn(animationSpec = tween(150, delayMillis = 60)),
                                        exit = shrinkHorizontally(animationSpec = tween(140)) +
                                            fadeOut(animationSpec = tween(70))
                                    ) {
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
                                                val nextMention = findWorkspaceMention(it)
                                                if (workspaceMentionTrigger == null && nextMention != null) {
                                                    workspaceMentionPath = ""
                                                } else if (nextMention == null) {
                                                    workspaceMentionPath = ""
                                                }
                                                inputField = it
                                                if (pendingPlotChoiceId != null && it.text != plotChoices.firstOrNull { c -> c.id == pendingPlotChoiceId }?.title) {
                                                    pendingPlotChoiceId = null
                                                }
                                            },
                                            // Agent 会话生成中仍可输入，发送的消息会进入排队队列
                                            enabled = delayReplyPending || !sending || isAgentSession,
                                            maxLines = 5,
                                            visualTransformation = commandCapsuleTransformation,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            decorationBox = { innerTextField ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // 命令胶囊：视觉变换已隐藏原文中的 /goal、/spec 字符，这里原位绘制胶囊
                                                    if (commandCapsule != null) {
                                                        CommandCapsuleChip(kind = commandCapsule.kind)
                                                        Spacer(Modifier.width(6.dp))
                                                    }
                                                    Box(
                                                        modifier = Modifier.weight(1f),
                                                        contentAlignment = Alignment.TopStart
                                                    ) {
                                                        if (input.isEmpty()) {
                                                            Text(
                                                                text = when {
                                                                    sending && isAgentSession ->
                                                                        stringResource(R.string.chat_queue_input_hint)
                                                                    sending -> stringResource(R.string.chat_ai_thinking)
                                                                    else -> stringResource(R.string.chat_input_placeholder)
                                                                },
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                                            )
                                                        }
                                                        innerTextField()
                                                    }
                                                }
                                            }
                                        )
                                    }

                                    // 快捷工具栏折叠时：在操作行内提供展开入口（不占额外纵向空间）
                                    AnimatedVisibility(
                                        visible = !imeVisible && quickActionsCollapsed,
                                        enter = expandHorizontally(animationSpec = tween(200)) +
                                            fadeIn(animationSpec = tween(150, delayMillis = 60)),
                                        exit = shrinkHorizontally(animationSpec = tween(140)) +
                                            fadeOut(animationSpec = tween(70))
                                    ) {
                                        ChatQuickActionExpandButton(
                                            onExpand = {
                                                quickActionsCollapsed = false
                                                ServiceContainer.prefs.chatQuickActionsCollapsed = false
                                            }
                                        )
                                    }

                                    // 主操作按钮：单行状态下贴在输入框右侧，输入法弹起后移入工具行
                                    AnimatedVisibility(
                                        visible = !imeVisible,
                                        enter = expandHorizontally(animationSpec = tween(200)) +
                                            fadeIn(animationSpec = tween(150, delayMillis = 60)),
                                        exit = shrinkHorizontally(animationSpec = tween(140)) +
                                            fadeOut(animationSpec = tween(70))
                                    ) {
                                        ComposerPrimaryActionButton(action = action, onClick = onPrimaryAction)
                                    }
                                }

                                // 输入法弹起后的工具行：+ 号菜单、当前模型、语音输入、发送/停止
                                AnimatedVisibility(
                                    visible = imeVisible,
                                    enter = expandVertically(animationSpec = tween(200)) +
                                        fadeIn(animationSpec = tween(150, delayMillis = 50)),
                                    exit = shrinkVertically(animationSpec = tween(140)) +
                                        fadeOut(animationSpec = tween(90))
                                ) {
                                    ComposerToolRow(
                                        sessionId = sessionId,
                                        modelSwitchEnabled = !sessionGenerating && !replyBusy && !compressing,
                                        action = action,
                                        onTogglePanel = togglePanel,
                                        onModelClick = { showChatModelDialog = true },
                                        onVoice = {
                                            requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                        },
                                        onAction = onPrimaryAction
                                    )
                                }
                            }
                        }

                        // 快捷工具栏：内容锚定在输入区底部，随底衬一起上下伸缩
                        AnimatedVisibility(
                            visible = quickBarVisible,
                            enter = expandVertically(
                                animationSpec = spring(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeIn(tween(120)),
                            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(tween(90))
                        ) {
                            ChatQuickActionBar(
                                actions = quickActions.filter { session?.companionMode != true || it != ChatQuickAction.COMPRESS },
                                onCollapse = {
                                    quickActionsCollapsed = true
                                    ServiceContainer.prefs.chatQuickActionsCollapsed = true
                                },
                                onEdit = { showQuickActionEditor = true },
                                // AI 执行中或延迟回复等待期间禁用「压缩上下文」：压缩会重写历史边界，
                                // 正在运行的任务/待发批次会丢失工作记忆；达到阈值的自动压缩不受影响。
                                isActionEnabled = { id ->
                                    id != ChatQuickAction.COMPRESS || (!replyBusy && !compressing)
                                },
                                onAction = handleQuickAction
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        if (companionChat) CompanionClearMessagesDialog(
            enabled = !replyBusy && !sessionGenerating,
            onDismiss = { showClearConfirm = false },
            onConfirm = { if (!replyBusy && !sessionGenerating) { showClearConfirm = false; onClear() } }
        ) else AlertDialog(
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
        if (companionChat) CompanionMessageSearch(
            messages, searchQuery, { searchQuery = it }, companionChatTitle(session),
            onDismiss = { showSearch = false }, onResultClick = { message ->
                onDismissCompanionSettings()
                onJumpToMessage(message)
            }
        ) else ModernSearchDialog(
            allowRoleFilter = companionChat,
            messages = messages,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showSearch = false },
            onResultClick = { msg -> onJumpToMessage(msg) }
        )
    }

    if (showFavorites) {
        ModernFavoritesDialog(
            companion = companionChat,
            characterName = if (companionChat) companionChatTitle(session) else session?.characterName,
            favorites = favorites,
            loading = favoritesLoading,
            sessionId = sessionId,
            onDismiss = { showFavorites = false },
            onFavoritesChanged = { favoritesVersion++ }
        )
    }

    if (showToolSetDialog) {
        AgentToolSetDialog(
            sessionId = sessionId,
            onDismiss = { showToolSetDialog = false },
            onChanged = { toolSetRevision++ }
        )
    }

    // 输入工具行的「当前模型」入口：弹窗挂在输入区外层，不随工具行折叠而消失
    if (showChatModelDialog) {
        SessionChatModelDialogHost(
            sessionId = sessionId,
            enabled = !sessionGenerating && !replyBusy && !compressing,
            onDismiss = { showChatModelDialog = false }
        )
    }

    if (showQuickActionEditor) {
        ChatQuickActionEditorDialog(
            actions = quickActions,
            onActionsChange = { updated ->
                val normalized = ChatQuickAction.normalize(updated)
                quickActions = normalized
                ServiceContainer.prefs.setChatQuickActions(normalized)
            },
            onDismiss = { showQuickActionEditor = false }
        )
    }

    if (stickerPickerOpen) {
        StickerPickerSheet(
            stickers = stickers,
            onPick = { sticker ->
                stickerPickerOpen = false
                // 复用正常发送流程；草稿、剧情选项和待发送附件留在输入区。
                onSend("[${sticker.name}]", null, emptyList(), readReasoningEffort())
            },
            onManage = {
                stickerPickerOpen = false
                onOpenStickers()
            },
            onDismiss = { stickerPickerOpen = false }
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

/**
 * 输入区主操作按钮（语音/发送/停止）：单行输入行与双行工具行共用同一套配色过渡。
 *
 * @param idleAsSend 空闲（无草稿）时仍画出发送图标而不是麦克风：
 *   工具行里已经有语音按钮，这里固定显示发送位，靠颜色变化过渡，避免按钮凭空出现/消失。
 */
@Composable
private fun ComposerPrimaryActionButton(
    action: ModernComposerAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    idleAsSend: Boolean = false
) {
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
        // 工具行里的空闲发送位只做展示，点击交给旁边的语音按钮
        onClick = onClick,
        enabled = !(idleAsSend && action == ModernComposerAction.VOICE),
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(actionStartColor, actionEndColor)))
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
                    if (idleAsSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                    contentDescription = stringResource(
                        if (idleAsSend) R.string.chat_send else R.string.chat_voice_input
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

/**
 * 输入法弹起后的工具行：+ 号菜单、当前模型（显示模型 id，点击打开 P0 模型弹窗）、语音输入、发送/停止。
 * 与上方纯输入行共同组成双行输入区；发送位常驻（不做出现/消失动画），右侧图标不会左右跳动。
 */
@Composable
private fun ComposerToolRow(
    sessionId: String,
    modelSwitchEnabled: Boolean,
    action: ModernComposerAction,
    onTogglePanel: () -> Unit,
    onModelClick: () -> Unit,
    onVoice: () -> Unit,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onTogglePanel,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chat_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        // 模型胶囊放在占满剩余宽度的容器里并靠左：胶囊长短只影响自身，
        // 右侧语音/发送按钮始终钉在行末，不会因为模型 id 变短而左移。
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            SessionChatModelChip(
                sessionId = sessionId,
                enabled = modelSwitchEnabled,
                onClick = onModelClick
            )
        }
        IconButton(onClick = onVoice, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = stringResource(R.string.chat_voice_input),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
        }
        ComposerPrimaryActionButton(
            action = action,
            onClick = onAction,
            idleAsSend = true
        )
    }
}

/**
 * 输入框上方的待发送附件预览：图片显示缩略图（可点击查看大图），其他文件显示文件卡片（类型图标 + 文件名）。
 */
@Composable
private fun PendingChatAttachments(
    sessionId: String,
    attachments: List<Map<String, Any>>,
    onRemove: (Map<String, Any>) -> Unit
) {
    val context = LocalContext.current
    // 点击候选图片 → 全屏查看
    var previewImage by remember { mutableStateOf<Pair<String, Any>?>(null) }
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
            val mime = attachment["type"] as? String ?: ""
            val localFile = (attachment["path"] as? String)
                ?.let { java.io.File(it) }
                ?.takeIf { it.isFile }
                ?: resolveLocalWorkspaceFile(context, sessionId, name)
            val isImage = classifyFilePreview(name, mime) == FilePreviewType.IMAGE

            Surface(
                modifier = if (isImage) Modifier.size(76.dp) else Modifier.height(76.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = null
            ) {
                Box {
                    if (isImage) {
                        val model: Any? = localFile ?: buildWorkspaceFileUrl(sessionId, name)
                        if (model != null) {
                            val previewModel = model
                            AsyncImage(
                                model = previewModel,
                                contentDescription = name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { previewImage = name to previewModel }
                            )
                        }
                    } else {
                        // 文件卡片：与消息气泡里的文件卡片使用同一套类型图标与配色
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(76.dp)
                                .padding(start = 10.dp, end = 32.dp, top = 8.dp, bottom = 8.dp)
                        ) {
                            FileCardIcon(fileName = name, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp)
                            )
                        }
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

    // 候选图片全屏查看：点击图片打开，点击空白或关闭按钮退出
    previewImage?.let { (name, model) ->
        Dialog(
            onDismissRequest = { previewImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .clickable { previewImage = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 56.dp)
                )
                IconButton(
                    onClick = { previewImage = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                )
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
    onSelected: (ReasoningEffort) -> Unit,
    customReasoning: com.nekobot.app.data.model.CustomReasoningParam,
    onCustomReasoningChange: (com.nekobot.app.data.model.CustomReasoningParam) -> Unit,
    showCharacterThinkingSettings: Boolean
) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun label(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.NONE -> stringResource(R.string.reasoning_effort_none)
        ReasoningEffort.MINIMAL -> stringResource(R.string.reasoning_effort_minimal)
        ReasoningEffort.LOW -> stringResource(R.string.reasoning_effort_low)
        ReasoningEffort.MEDIUM -> stringResource(R.string.reasoning_effort_medium)
        ReasoningEffort.HIGH -> stringResource(R.string.reasoning_effort_high)
        ReasoningEffort.MAX -> stringResource(R.string.reasoning_effort_max)
        ReasoningEffort.CUSTOM -> stringResource(R.string.aimodel_editor_provider_custom)
    }

    @Composable
    fun description(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.NONE -> stringResource(R.string.reasoning_effort_none_desc)
        ReasoningEffort.MINIMAL -> stringResource(R.string.reasoning_effort_minimal_desc)
        ReasoningEffort.LOW -> stringResource(R.string.reasoning_effort_low_desc)
        ReasoningEffort.MEDIUM -> stringResource(R.string.reasoning_effort_medium_desc)
        ReasoningEffort.HIGH -> stringResource(R.string.reasoning_effort_high_desc)
        ReasoningEffort.MAX -> stringResource(R.string.reasoning_effort_max_desc)
        ReasoningEffort.CUSTOM -> stringResource(R.string.single_role_replace_automatically_derived_thinking_parameters_with_these_valu)
    }

    ChatSettingEntry(
        icon = Icons.Outlined.Psychology,
        title = stringResource(R.string.reasoning_effort_title),
        value = label(selected),
        enabled = enabled,
        onClick = { open = true }
    )
    if (open) {
        NekoDialog(
            onDismiss = { open = false },
            title = stringResource(R.string.reasoning_effort_title),
            message = stringResource(R.string.chat_setting_reasoning_hint),
            confirmText = stringResource(R.string.common_close),
            onConfirm = { open = false },
            cancelText = null,
            contentScrollable = true
        ) {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReasoningEffort.entries.forEach { effort ->
                    ChatSettingChoice(
                        title = label(effort),
                        subtitle = description(effort),
                        selected = effort == selected,
                        enabled = enabled,
                        onClick = {
                            onSelected(effort)
                            open = false
                        }
                    )
                }
            }
        }
    }
    if (selected == ReasoningEffort.CUSTOM) {
        CustomReasoningFields(
            customReasoning,
            enabled,
            onChange = onCustomReasoningChange,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
        )
    }
    if (showCharacterThinkingSettings) {
        CharacterThinkingSettingsControls(
            effort = selected,
            menuRows = { switchModifier, promptModifier, enabled, checked ->
                Column {
                    Row(
                        modifier = switchModifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.13f else 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PersonOutline,
                                contentDescription = null,
                                tint = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.single_role_character_perspective_thinking_beta),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.single_role_think_in_chinese_in_the_character_s_first_person_voice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                    }
                    ModernMenuDivider()
                    Row(
                        modifier = promptModifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.13f else 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.single_role_custom_thinking_prompt_beta),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.single_role_leave_blank_to_use_the_default),
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
            }
        )
    }
}

@Composable
private fun ModernChatActionPanel(
    allowCompression: Boolean = true,
    showClearAction: Boolean = true,
    /** 抽屉（底部弹层）展开：外层容器由抽屉提供，这里只保留贴边内容。 */
    inSheet: Boolean = false,
    sessionId: String,
    modelSwitchEnabled: Boolean,
    messageCount: Int,
    cacheHitRate: Double?,
    usedTokens: Long,
    maxTokens: Int?,
    sending: Boolean,
    delayPending: Boolean,
    compressing: Boolean,
    agentLiveContext: AgentLiveContextUsage?,
    contextBreakdown: ContextUsageBreakdown?,
    fileBusy: Boolean,
    plotMode: Boolean,
    plotRealTimeSync: Boolean,
    reasoningEffort: ReasoningEffort,
    yoloEnabled: Boolean,
    yoloAvailable: Boolean,
    toolSetSummary: String,
    toolSetAvailable: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    customReasoning: com.nekobot.app.data.model.CustomReasoningParam,
    onCustomReasoningChange: (com.nekobot.app.data.model.CustomReasoningParam) -> Unit,
    showCharacterThinkingSettings: Boolean,
    onToggleYolo: () -> Unit,
    onOpenToolSet: () -> Unit,
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
            .padding(horizontal = if (inSheet) 0.dp else 12.dp, vertical = if (inSheet) 0.dp else 4.dp),
        cornerRadius = if (inSheet) 0 else 24,
        // 抽屉里去掉卡片外观，内容直接铺满弹层；内联面板仍保留玻璃卡片
        containerColor = if (inSheet) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentPadding = PaddingValues(0.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (inSheet) 520.dp else 470.dp),
            contentPadding = PaddingValues(
                start = if (inSheet) 16.dp else 14.dp,
                end = if (inSheet) 16.dp else 14.dp,
                top = 14.dp,
                bottom = if (inSheet) 24.dp else 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ModernContextCard(
                    allowCompression = allowCompression,
                    messageCount = messageCount,
                    cacheHitRate = cacheHitRate,
                    usedTokens = usedTokens,
                    maxTokens = maxTokens,
                    sending = sending,
                    delayPending = delayPending,
                    compressing = compressing,
                    agentLiveContext = agentLiveContext,
                    contextBreakdown = contextBreakdown,
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
            if (ServiceContainer.prefs.isLocalMode) {
                item {
                    SessionChatModelPickerEntry(sessionId, enabled = modelSwitchEnabled)
                }
            }
            item {
                ReasoningEffortSelector(
                    selected = reasoningEffort,
                    enabled = !sending,
                    onSelected = onReasoningEffortChange,
                    customReasoning = customReasoning,
                    onCustomReasoningChange = onCustomReasoningChange,
                    showCharacterThinkingSettings = showCharacterThinkingSettings
                )
            }
            if (toolSetAvailable) {
                item {
                    ModernToolRow(
                        icon = Icons.Filled.Extension,
                        title = stringResource(R.string.toolset_entry_title),
                        subtitle = toolSetSummary,
                        enabled = true,
                        onClick = onOpenToolSet
                    )
                }
            }
            if (yoloAvailable) {
                item {
                    ModernToggleRow(
                        icon = Icons.Filled.Terminal,
                        title = stringResource(R.string.chat_yolo_mode),
                        subtitle = stringResource(R.string.chat_yolo_mode_subtitle),
                        checked = yoloEnabled,
                        enabled = true,
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
            if (showClearAction) {
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
}

/**
 * 读取某会话工具集的自定义状态并生成 + 面板入口副标题。
 * 未自定义且未设置默认工具集时显示“全部启用”；
 * 未自定义但跟随「新会话默认工具集」时显示“跟随默认”；
 * 已单独自定义且正好等于某个模式时显示模式名，否则显示已启用/总大类数。
 */
@Composable
private fun rememberToolSetSummary(sessionId: String, revision: Int): String {
    val enabledIds = remember(sessionId, revision) {
        ServiceContainer.unified.enabledSessionToolIds(sessionId)
    }
    val customized = remember(sessionId, revision) {
        ServiceContainer.unified.isSessionToolSetCustomized(sessionId)
    }
    // 已套用某个模式时直接显示模式名，用户一眼能看出当前会话在用哪套工具
    val matchedMode = remember(sessionId, revision) {
        ServiceContainer.unified.sessionToolSetModeId(sessionId)?.let { modeId ->
            ServiceContainer.unified.toolSetModes().firstOrNull { it.id == modeId }
        }
    }
    val categories = remember { ServiceContainer.unified.toolCategories() }
    if (enabledIds == null) {
        return stringResource(R.string.toolset_summary_all)
    }
    val enabledCount = categories.count { (_, toolIds) ->
        toolIds.all { it in enabledIds }
    }
    if (matchedMode != null) {
        return stringResource(
            R.string.toolset_summary_mode,
            toolSetModeLabel(matchedMode),
            enabledCount,
            categories.size
        )
    }
    val customizedLabel = if (customized) {
        R.string.toolset_summary_customized
    } else {
        R.string.toolset_summary_default
    }
    return stringResource(customizedLabel, enabledCount, categories.size)
}

/**
 * 会话当前对应的模式 id：已单独自定义时用自己的匹配结果（null=自定义），
 * 未自定义（跟随默认）时用默认工具集匹配到的模式，便于弹窗里点亮对应芯片。
 */
private fun resolveSessionModeId(sessionId: String): String? =
    if (ServiceContainer.unified.isSessionToolSetCustomized(sessionId)) {
        ServiceContainer.unified.sessionToolSetModeId(sessionId)
    } else {
        ServiceContainer.unified.defaultToolSetModeId()
    }

/**
 * 会话 Agent 工具集选择弹窗：读写当前会话的工具集选择。
 * 顶部可一键套用模式（极简/标准/安卓/角色卡/全能或自定义模式），
 * 下方仍保留按大类、按单个工具的细化开关。界面复用 [AgentToolSetPickerDialog]。
 */
@Composable
private fun AgentToolSetDialog(
    sessionId: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val categories = remember { ServiceContainer.unified.toolCategories() }
    val modes = remember { ServiceContainer.unified.toolSetModes() }
    // 设置了「新会话默认工具集」时，恢复动作的语义是“恢复为默认”而非“全部启用”。
    val hasDefault = remember { ServiceContainer.unified.defaultSessionToolIds() != null }
    var enabled by remember(sessionId) {
        mutableStateOf(ServiceContainer.unified.effectiveSessionToolIds(sessionId))
    }
    var activeModeId by remember(sessionId) {
        mutableStateOf(resolveSessionModeId(sessionId))
    }
    var followDefault by remember(sessionId) {
        mutableStateOf(!ServiceContainer.unified.isSessionToolSetCustomized(sessionId))
    }

    // 任何改动后统一从仓库重读“实际生效集合”：运行期动态工具（MCP）由注册表
    // 的默认放行规则决定，手写增删很容易和真实注入结果不一致。
    fun reload() {
        enabled = ServiceContainer.unified.effectiveSessionToolIds(sessionId)
        activeModeId = resolveSessionModeId(sessionId)
        followDefault = !ServiceContainer.unified.isSessionToolSetCustomized(sessionId)
        onChanged()
    }

    AgentToolSetPickerDialog(
        title = stringResource(R.string.toolset_dialog_title),
        subtitle = stringResource(R.string.toolset_dialog_subtitle),
        resetLabel = stringResource(
            if (hasDefault) R.string.toolset_reset_default else R.string.toolset_reset_all
        ),
        categories = categories,
        enabled = enabled,
        onToggleCategory = { categoryId, on ->
            ServiceContainer.unified.setSessionCategoryEnabled(sessionId, categoryId, on)
            reload()
        },
        onToggleTool = { toolId, on ->
            ServiceContainer.unified.setSessionToolEnabled(sessionId, toolId, on)
            reload()
        },
        onReset = {
            ServiceContainer.unified.resetSessionToolSet(sessionId)
            reload()
        },
        onDismiss = onDismiss,
        modes = modes,
        activeModeId = activeModeId,
        showFollowDefaultChip = true,
        followDefault = followDefault,
        onSelectMode = { modeId ->
            ServiceContainer.unified.applySessionToolSetMode(sessionId, modeId)
            reload()
        },
        onFollowDefault = {
            ServiceContainer.unified.resetSessionToolSet(sessionId)
            reload()
        }
    )
}

@Composable
private fun ModernContextCard(
    allowCompression: Boolean = true,
    messageCount: Int,
    cacheHitRate: Double?,
    usedTokens: Long,
    maxTokens: Int?,
    sending: Boolean,
    delayPending: Boolean,
    compressing: Boolean,
    agentLiveContext: AgentLiveContextUsage?,
    contextBreakdown: ContextUsageBreakdown?,
    onCompress: () -> Unit,
    onOpenAnalysis: () -> Unit
) {
    // 计算已用 Token 占最大上下文的比例；maxTokens 缺省时百分比置 0
    val progress = if (maxTokens != null && maxTokens > 0) {
        (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val percent = (progress * 100).toInt()
    val liveRunning = agentLiveContext?.hasActiveRun == true && sending

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
                            if (liveRunning) {
                                stringResource(R.string.chat_agent_context_live_desc)
                            } else {
                                stringResource(R.string.chat_context_analysis_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (allowCompression) TextButton(
                    onClick = onCompress,
                    // 延迟回复等待期间同样禁止压缩：压缩会重写历史边界，待发批次会因此错乱
                    enabled = !sending && !delayPending && messageCount > 0 && !compressing
                ) {
                    if (compressing) {
                        // 进行中：图标换成进度圈，标签切换为“压缩中”。
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Compress, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(
                            if (compressing) R.string.chat_compressing else R.string.chat_compress
                        )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernMetric(stringResource(R.string.chat_messages_metric), messageCount.toString(), Modifier.weight(1f))
                // 缓存命中率：命中缓存的输入 token / 完整输入 token；服务商未上报时显示 —
                ModernMetric(
                    label = stringResource(R.string.chat_cache_hit_rate),
                    value = formatCacheHitRate(cacheHitRate),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernMetric(stringResource(R.string.chat_used_tokens), usedTokens.toString(), Modifier.weight(1f))
                ModernMetric(
                    label = stringResource(R.string.chat_status),
                    value = agentStatusText(agentLiveContext, sending),
                    modifier = Modifier.weight(1f)
                )
            }
            // 类型占比仅在 Agent 实时记录期间展示（随轮询动态刷新，含 LIVE 徽标）；
            // 实时记录完成后自动隐藏，不再持续显示静态占比，仅保留上方的分析二级入口。
            if (liveRunning && contextBreakdown?.parts?.isNotEmpty() == true) {
                Spacer(Modifier.height(12.dp))
                ModernContextAnalysisSection(
                    breakdown = contextBreakdown,
                    liveRunning = liveRunning
                )
            }
        }
    }
}

/** Agent 运行中时展示实时阶段（准备/思考/调用工具），与发送状态联动。 */
@Composable
private fun agentStatusText(agentLiveContext: AgentLiveContextUsage?, sending: Boolean): String {
    if (!sending) return stringResource(R.string.chat_status_ready)
    val live = agentLiveContext
    if (live == null) return stringResource(R.string.chat_status_generating)
    return when (live.stage) {
        AgentRunStage.PREPARING -> stringResource(R.string.chat_agent_status_preparing)
        AgentRunStage.THINKING -> stringResource(R.string.chat_agent_status_thinking)
        AgentRunStage.TOOL ->
            if (live.lastToolName.isNullOrBlank()) {
                stringResource(R.string.chat_status_generating)
            } else {
                stringResource(R.string.chat_agent_status_tool_running, live.lastToolName)
            }
        else -> stringResource(R.string.chat_status_generating)
    }
}

/**
 * + 菜单内的上下文构成分析：类型占比精简列表。
 * Agent 运行期间随轮询动态刷新，工具调用占比包含尚未落库的进行中历史。
 */
@Composable
private fun ModernContextAnalysisSection(breakdown: ContextUsageBreakdown, liveRunning: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chat_context_analysis_type_breakdown),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (liveRunning) {
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(R.string.chat_context_live_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            breakdown.parts.forEach { part ->
                ModernContextPartRow(part, breakdown.totalTokens)
            }
        }
        if (liveRunning) {
            Spacer(Modifier.height(7.dp))
            Text(
                stringResource(R.string.chat_agent_context_live_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModernContextPartRow(part: ContextUsagePartTokens, totalTokens: Int) {
    val color = part.part.displayColor(MaterialTheme.colorScheme)
    val share = if (totalTokens > 0) part.tokens.toFloat() / totalTokens else 0f
    val percent = (share * 100).toInt()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                contextPartLabel(part.part),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                stringResource(
                    R.string.chat_context_analysis_part_detail,
                    part.tokens,
                    part.itemCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
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
internal fun ModernToolRow(
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
    allowRoleFilter: Boolean = false,
    messages: List<Message>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultClick: (Message) -> Unit
) {
    val textResources = LocalResources.current
    var onlyMine by remember { mutableStateOf(false) }
    val results = remember(query, messages, onlyMine) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank() && !onlyMine) emptyList()
        else messages.filter {
            it.role != "system" && (!onlyMine || it.role == "user") && it.displayContent.lowercase().contains(normalized)
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
        if (allowRoleFilter) FilterChip(selected = onlyMine, onClick = { onlyMine = !onlyMine }, label = { Text(textResources.getString(R.string.single_role_only_my_messages)) })
        Spacer(Modifier.height(12.dp))
        when {
            query.isBlank() && !onlyMine -> Text(
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
    companion: Boolean = false,
    characterName: String? = null,
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

    if (companion) {
        CompanionFavoritesReading(favorites, loading, selectedCollection, characterName,
            onBack = { if (selectedCollection != null) selectedCollection = null else onDismiss() },
            onSelect = { selectedCollection = it }, onDelete = { deletingCollection = it })
        return
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

/** 单次批量上传的附件数量上限（图片/文件选择器） */
private const val MAX_BATCH_ATTACHMENT_PICK = 20

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
