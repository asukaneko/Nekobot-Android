package com.nekobot.app.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.screens.chat.ChatPendingPrompt
import com.nekobot.app.ui.screens.sessions.SessionListRow
import com.nekobot.app.ui.screens.sessions.buildSessionListRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 模式首页的轻量 ViewModel：只负责「会话 + 角色」快照与模式内的新建会话。
 *
 * 不复用 [com.nekobot.app.ui.screens.sessions.SessionsViewModel]：模式页不承担筛选、
 * 多选、统计等能力，保持一份干净的数据源，也方便后续给某个模式单独扩展。
 */
class ModeHomeViewModel : BaseViewModel() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    private val _creating = MutableStateFlow(false)

    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()

    /** 新建会话提交中：页面据此禁用输入与按钮，避免重复创建。 */
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    /** 全部可见会话（排除归档），按最近更新排序。 */
    val rows: StateFlow<List<SessionListRow>> = combine(_sessions, _characters) { sessions, characters ->
        buildSessionListRows(
            sessions = sessions
                .filter { it.isArchive != true && it.archived != true }
                .sortedByDescending { it.updatedAt ?: it.createdAt.orEmpty() },
            characters = characters,
            portraitUrlResolver = { resolveAvatarUrl(it) }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun load() {
        launchResult(
            block = { unified.listSessionSummaries() },
            onSuccess = { _sessions.value = it ?: emptyList() }
        )
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { _characters.value = it ?: emptyList() }
        )
    }

    /**
     * 创建 Agent 会话，并把用户输入的第一句话交给会话页发送。
     *
     * 欢迎页本身不建会话：只有点了发送才走到这里。
     */
    fun createAgentSession(firstText: String, onReady: (String) -> Unit) {
        if (_creating.value) return
        _creating.value = true
        viewModelScope.launch {
            try {
                val request = CreateSessionRequest(
                    name = string(R.string.sessions_agent_chat),
                    sessionMode = "agent",
                    systemPrompt = "",
                    firstMessage = "",
                    senderName = "Agent",
                    characterId = "",
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                when (val result = unified.createSession(request)) {
                    is Resource.Success -> {
                        val sessionId = result.data?.id?.takeIf { it.isNotBlank() }
                        if (sessionId == null) {
                            showError(string(R.string.common_unknown_error))
                            return@launch
                        }
                        ChatPendingPrompt.put(sessionId, firstText)
                        onReady(sessionId)
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.common_unknown_error))
            } finally {
                _creating.value = false
            }
        }
    }

    /** 为角色新建一个会话，成功后回调 sessionId。 */
    fun createSessionForCharacter(characterId: String, characterName: String, onReady: (String) -> Unit) {
        if (_creating.value) return
        _creating.value = true
        viewModelScope.launch {
            try {
                val request = CreateSessionRequest(
                    name = characterName,
                    sessionMode = "character",
                    characterId = characterId,
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                when (val result = unified.createSession(request)) {
                    is Resource.Success -> {
                        val sessionId = result.data?.id?.takeIf { it.isNotBlank() }
                        if (sessionId == null) {
                            showError(string(R.string.common_unknown_error))
                            return@launch
                        }
                        showToast(string(R.string.sessions_created_toast))
                        onReady(sessionId)
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.common_unknown_error))
            } finally {
                _creating.value = false
            }
        }
    }
}
