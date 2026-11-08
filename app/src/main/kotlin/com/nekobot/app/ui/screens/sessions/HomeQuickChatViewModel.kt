package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.HomePlotChoice
import com.nekobot.app.data.local.HomeQuickChatDraftCodec
import com.nekobot.app.data.local.HomeRelationSnapshot
import com.nekobot.app.data.local.parseHomePlotChoices
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.remote.RealtimeEvent
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.screens.chat.ChatSessionManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 首页场景卡发起的轻聊请求。 */
data class HomeQuickChatRequest(
    val characterId: String,
    val characterName: String,
    val portraitUrl: String? = null,
    /** 当前场景开场白：新建场景会话时作为第一条角色消息。 */
    val openingLine: String = "",
    /** true = 接着该角色最近一段会话（24 小时内），false = 开新场景。 */
    val preferExisting: Boolean = true
)

/** 轻聊面板展示来源。 */
enum class HomeQuickChatOrigin { NEW_SCENE, CONTINUE }

/** 首页轻聊面板的界面状态。 */
data class HomeQuickChatUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val characterId: String = "",
    val characterName: String = "",
    val portraitUrl: String? = null,
    val openingLine: String = "",
    val sessionId: String = "",
    val origin: HomeQuickChatOrigin = HomeQuickChatOrigin.NEW_SCENE,
    val messages: List<Message> = emptyList(),
    val streamingText: String = "",
    val sending: Boolean = false,
    /** 该会话正在别处生成（例如完整会话页），面板只读。 */
    val busyElsewhere: Boolean = false,
    /** 绑定的不是单角色会话时，可以随时换一个新场景。 */
    val canStartNewScene: Boolean = false,
    val plotChoices: List<HomePlotChoice> = emptyList(),
    val relationDelta: String? = null,
    val error: String? = null,
    val hintVisible: Boolean = false
)

/** 面板里一次最多取多少条消息。 */
private const val HOME_QUICK_CHAT_PAGE_SIZE = 30
/** 面板里最多显示几个剧情选项。 */
private const val HOME_QUICK_CHAT_PLOT_LIMIT = 3

/**
 * 首页场景轻聊的轻量 ViewModel。
 *
 * 只负责「绑定会话 → 读取最近消息 → 发送 → 收集流式事件」，不接管完整会话页的
 * socket/唤醒/压缩等后台事件；聊天本身仍走 `chatStream` 同一条角色管线，
 * 因此关系评估、剧情选项与记忆抽取都会照常发生。
 */
class HomeQuickChatViewModel : BaseViewModel() {

    private val _state = MutableStateFlow(HomeQuickChatUiState())
    val state: StateFlow<HomeQuickChatUiState> = _state.asStateFlow()

    private var lastRequest: HomeQuickChatRequest? = null
    private var openJob: Job? = null
    private var sendJob: Job? = null

    /** 每个角色最近一次真实显示的问候语：面板可能在问候生成期间打开，用它对齐开场白。 */
    private val latestGreetings = mutableMapOf<String, String>()

    /**
     * 打开轻聊面板。
     *
     * @param restart true 表示用户主动换了场景/角色：先结算上一个草稿再重新绑定。
     */
    fun open(request: HomeQuickChatRequest, restart: Boolean = false) {
        if (!isLocalMode || request.characterId.isBlank()) return
        val current = _state.value
        if (!restart && current.visible && current.sessionId.isNotBlank() &&
            current.characterId == request.characterId
        ) {
            return
        }
        // 换角色/换场景前先结算旧草稿：没说过话就回收，说过话就保留为正常会话。
        if (current.visible && current.sessionId.isNotBlank()) {
            val old = current
            viewModelScope.launch {
                unified.settleHomeQuickChatDraft(
                    old.characterId,
                    old.sessionId,
                    // 正在生成时不能删：用户消息已发送，回复还要落库
                    keep = old.sending
                )
            }
        }
        // 以卡片上最新的问候为准：请求里的文案可能还是点按瞬间的旧一句
        val openingLine = latestGreetings[request.characterId]
            ?.takeIf { it.isNotBlank() }
            ?: request.openingLine
        lastRequest = request.copy(openingLine = openingLine)
        _state.value = HomeQuickChatUiState(
            visible = true,
            loading = true,
            characterId = request.characterId,
            characterName = request.characterName,
            portraitUrl = request.portraitUrl,
            openingLine = openingLine,
            hintVisible = !ServiceContainer.prefs.homeQuickChatHintDismissed
        )
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val result = runCatching {
                unified.resolveHomeQuickChatSession(
                    characterId = request.characterId,
                    preferExisting = request.preferExisting,
                    openingLine = openingLine
                )
            }.getOrNull()
            val binding = result.dataOrNull()
            if (binding == null) {
                _state.value = HomeQuickChatUiState()
                showError(
                    (result as? Resource.Error)?.message
                        ?: string(R.string.shell_quick_chat_open_failed)
                )
                return@launch
            }
            val sessionId = binding.session.id.orEmpty()
            if (binding.created && sessionId.isNotBlank()) {
                // 草稿：没有用户消息时关闭面板即回收
                ServiceContainer.prefs.homeQuickChatDraft = HomeQuickChatDraftCodec.encode(
                    request.characterId, sessionId, System.currentTimeMillis()
                )
            }
            val plotMode = runCatching { unified.getSession(sessionId) }.getOrNull()
                .dataOrNull()?.plotMode == true
            _state.update {
                it.copy(
                    loading = false,
                    sessionId = sessionId,
                    origin = if (binding.created) HomeQuickChatOrigin.NEW_SCENE else HomeQuickChatOrigin.CONTINUE,
                    canStartNewScene = !binding.companion,
                    busyElsewhere = ChatSessionManager.get(sessionId)?.hasRetainedWork() == true
                )
            }
            reload(sessionId, before = null)
            if (plotMode) loadPlotChoices(sessionId)
            // 绑定期间问候可能刚好被 AI 重写：补一次对齐，避免开场白停在旧文案
            latestGreetings[request.characterId]
                ?.takeIf { it.isNotBlank() && it != openingLine }
                ?.let { updateOpeningLine(request.characterId, it) }
        }
    }

    /** 同一角色换一个新场景。 */
    fun newScene() {
        val request = lastRequest ?: return
        open(request.copy(preferExisting = false), restart = true)
    }

    /**
     * 问候语异步生成完成后同步到面板。
     *
     * 问候语可能先显示本地候选、随后被 AI 重写；面板如果已用旧文案开场，
     * 在用户还没发言时跟随改成最新一句，避免两处显示不一致。
     */
    fun updateOpeningLine(characterId: String, line: String) {
        if (characterId.isBlank() || line.isBlank()) return
        // 先记录：面板可能正在绑定会话，绑定完成后会用最新一句补对齐
        latestGreetings[characterId] = line
        val current = _state.value
        if (!current.visible || current.sessionId.isBlank() || current.characterId != characterId) return
        if (current.sending || current.messages.any { it.isUser }) return
        if (current.openingLine == line) return
        _state.update { it.copy(openingLine = line) }
        val sessionId = current.sessionId
        viewModelScope.launch {
            unified.updateHomeQuickChatOpeningLine(sessionId, line)
            reload(sessionId, before = null)
        }
    }

    /** 发送一条消息。 */
    fun send(raw: String) {
        val text = raw.trim()
        val current = _state.value
        if (text.isBlank() || current.sessionId.isBlank() || current.sending) return
        if (current.busyElsewhere) return
        val sessionId = current.sessionId
        val optimistic = Message(
            id = "_home_quick_user_${UUID.randomUUID()}",
            role = "user",
            content = text,
            timestamp = System.currentTimeMillis().toString()
        )
        _state.update {
            it.copy(
                sending = true,
                streamingText = "",
                relationDelta = null,
                error = null,
                messages = it.messages + optimistic
            )
        }
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            try {
                val beforeSnapshot = runCatching {
                    unified.sessionRelationSnapshot(sessionId)
                }.getOrNull()
                val flow = withContext(Dispatchers.IO) {
                    unified.chatStream(sessionId, text)
                }
                if (flow == null) {
                    fail(string(R.string.chat_no_ai_model))
                    return@launch
                }
                flow.collect { event ->
                    when (event) {
                        is RealtimeEvent.StreamChunk ->
                            _state.update { it.copy(streamingText = it.streamingText + event.chunk) }

                        is RealtimeEvent.StreamEnd -> reload(sessionId, beforeSnapshot)

                        is RealtimeEvent.PlotChoices -> {
                            val parsed = parseHomePlotChoices(event.choices.toString())
                                .take(HOME_QUICK_CHAT_PLOT_LIMIT)
                            _state.update { it.copy(plotChoices = parsed) }
                        }

                        is RealtimeEvent.Error -> fail(
                            event.message.takeIf { it.isNotBlank() }
                                ?: string(R.string.shell_quick_chat_error)
                        )

                        else -> Unit
                    }
                }
                // 管线结束后再对齐一次数据库：确保最终回复与剧情选项都已落库
                reload(sessionId, beforeSnapshot)
                if (_state.value.plotChoices.isEmpty()) loadPlotChoices(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                fail(e.message ?: string(R.string.shell_quick_chat_error))
            } finally {
                _state.update { it.copy(sending = false, streamingText = "") }
            }
        }
    }

    /** 收起面板；没说过话的草稿会连同会话一起回收。 */
    fun close() {
        val current = _state.value
        if (!current.visible) return
        _state.value = current.copy(visible = false)
        // 生成中不取消：让回复落库，避免留下只有用户消息的半轮对话
        if (current.sessionId.isNotBlank()) {
            val characterId = current.characterId
            val sessionId = current.sessionId
            val keep = current.sending
            viewModelScope.launch {
                unified.settleHomeQuickChatDraft(characterId, sessionId, keep = keep)
            }
        }
    }

    /** 展开为完整会话：保留草稿并清掉标记，随后由界面跳转。 */
    fun keepSession(done: () -> Unit) {
        val current = _state.value
        _state.value = current.copy(visible = false)
        val characterId = current.characterId
        val sessionId = current.sessionId
        if (sessionId.isBlank()) {
            done()
            return
        }
        viewModelScope.launch {
            unified.settleHomeQuickChatDraft(characterId, sessionId, keep = true)
            done()
        }
    }

    fun dismissHint() {
        ServiceContainer.prefs.homeQuickChatHintDismissed = true
        _state.update { it.copy(hintVisible = false) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun reload(sessionId: String, before: HomeRelationSnapshot?) {
        val page = runCatching { unified.listRecentMessages(sessionId, HOME_QUICK_CHAT_PAGE_SIZE) }
            .getOrNull()
        val messages = page.dataOrNull()?.messages.orEmpty()
            .filterNot { it.isThinkingCard }
        val after = runCatching { unified.sessionRelationSnapshot(sessionId) }.getOrNull()
        val delta = relationDelta(before, after)
        _state.update {
            it.copy(
                messages = messages,
                streamingText = if (it.sending) it.streamingText else "",
                relationDelta = delta ?: it.relationDelta
            )
        }
    }

    private suspend fun loadPlotChoices(sessionId: String) {
        val json = withContext(Dispatchers.IO) { unified.sessionPlotChoices(sessionId) }
        _state.update { it.copy(plotChoices = parseHomePlotChoices(json).take(HOME_QUICK_CHAT_PLOT_LIMIT)) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(sending = false, streamingText = "", error = message) }
    }

    /**
     * 本轮关系变化（只取有变化的维度）。
     *
     * 数据来自 `local_state_snapshots`：每轮回复后由角色运行时写入，因此第一次发送
     * （还没有快照）不会显示变化，避免凭空编数字。
     */
    private fun relationDelta(before: HomeRelationSnapshot?, after: HomeRelationSnapshot?): String? {
        if (before == null || after == null) return null
        val parts = mutableListOf<String>()
        fun append(labelRes: Int, old: Int, new: Int) {
            val diff = new - old
            if (diff == 0) return
            parts += "${string(labelRes)} ${if (diff > 0) "+$diff" else diff.toString()}"
        }
        append(R.string.sessions_detail_affection, before.affection, after.affection)
        append(R.string.sessions_detail_trust, before.trust, after.trust)
        append(R.string.sessions_detail_familiarity, before.familiarity, after.familiarity)
        append(R.string.sessions_detail_dependency, before.dependency, after.dependency)
        append(R.string.sessions_detail_security, before.security, after.security)
        append(R.string.sessions_detail_jealousy, before.jealousy, after.jealousy)
        if (parts.isEmpty()) return null
        return string(R.string.shell_quick_chat_relation_delta, parts.joinToString(" · "))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Resource<T>?.dataOrNull(): T? = (this as? Resource.Success<*>)?.data as? T
}
