package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.model.PublicShareRequest
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.Tertiary
import com.nekobot.app.ui.theme.WarningAmber
import com.nekobot.app.data.repository.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会话详情 ViewModel：加载单个会话、编辑名称 / 标签 / 置顶 / 收藏 / 系统提示词 /
 * 自动状态间隔 / 剧情模式等，支持保存、删除。
 */
class SessionDetailViewModel : BaseViewModel() {

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val name = MutableStateFlow("")
    val tagsText = MutableStateFlow("")
    val pinned = MutableStateFlow(false)
    val favorite = MutableStateFlow(false)
    val systemPrompt = MutableStateFlow("")
    val autoStateInterval = MutableStateFlow<Int?>(null)
    val plotMode = MutableStateFlow(false)
    val plotRealTimeSync = MutableStateFlow(false)
    val plotChoiceStyle = MutableStateFlow("")
    // TTS / 主动聊天 / 公开分享
    val ttsEnabled = MutableStateFlow(false)
    val ttsModelId = MutableStateFlow("")
    val ttsVoice = MutableStateFlow("")
    val proactiveChatEnabled = MutableStateFlow(false)
    val proactiveChatInterval = MutableStateFlow(60)
    val isPublic = MutableStateFlow(false)
    // 通知提醒（本地 SharedPreferences 存储）
    val notificationEnabled = MutableStateFlow(false)
    // 公开分享配置
    val shareExpiresDays = MutableStateFlow(30)
    val sharePassword = MutableStateFlow("")
    val shareMessageStart = MutableStateFlow("")
    val shareMessageEnd = MutableStateFlow("")
    val shareIncludeCharacter = MutableStateFlow(true)
    val shareIncludeUserMessages = MutableStateFlow(true)
    val publicShareUrl = MutableStateFlow("")
    val publicSharePasswordRequired = MutableStateFlow(false)
    val publicShareExpiresAt = MutableStateFlow<Double?>(null)
    val isLoadingPublic = MutableStateFlow(false)

    /** 可用 AI 模型列表（用于 TTS 模型选择） */
    private val _aiModels = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val aiModels: StateFlow<List<Pair<String, String>>> = _aiModels.asStateFlow()

    /** 自定义提示词列表（可编辑，每项含 order/title/content） */
    private val _customPrompts = MutableStateFlow<List<CustomPromptItem>>(emptyList())
    val customPrompts: StateFlow<List<CustomPromptItem>> = _customPrompts.asStateFlow()

    /** 运行时提示词注入栈调试信息（只读，从 session.promptStackDebug 解析） */
    private val _promptStackDebug = MutableStateFlow<List<PromptStackItem>>(emptyList())
    val promptStackDebug: StateFlow<List<PromptStackItem>> = _promptStackDebug.asStateFlow()

    /** 运行时合成的完整系统提示词（只读，每次对话后更新） */
    private val _composedSystemPrompt = MutableStateFlow("")
    val composedSystemPrompt: StateFlow<String> = _composedSystemPrompt.asStateFlow()

    /** 已禁用的注入项 key 集合 */
    private val _disabledPromptKeys = MutableStateFlow<Set<String>>(emptySet())
    val disabledPromptKeys: StateFlow<Set<String>> = _disabledPromptKeys.asStateFlow()

    /** 绑定角色详情（远程模式后端可能不返回 characterName，需二次查询） */
    private val _characterDetail = MutableStateFlow<com.nekobot.app.data.model.CharacterPreset?>(null)
    val characterDetail: StateFlow<com.nekobot.app.data.model.CharacterPreset?> = _characterDetail.asStateFlow()

    /** 角色列表（用于绑定角色选择） */
    private val _characters = MutableStateFlow<List<com.nekobot.app.data.model.CharacterPreset>>(emptyList())
    val characters: StateFlow<List<com.nekobot.app.data.model.CharacterPreset>> = _characters.asStateFlow()

    fun init(id: String) {
        load(id)
        loadAiModels()
        loadCharacters()
    }

    /** 加载角色列表（用于绑定角色选择） */
    fun loadCharacters() {
        viewModelScope.launch {
            try {
                if (isLocalMode) {
                    _characters.value = com.nekobot.app.ServiceContainer.localRepository.listCharacters()
                } else {
                    when (val r = repo.listCharacters()) {
                        is Resource.Success -> _characters.value = r.data ?: emptyList()
                        else -> {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** 绑定角色到当前会话 */
    fun bindCharacter(character: com.nekobot.app.data.model.CharacterPreset, onSuccess: () -> Unit) {
        val s = _session.value ?: return
        launchResult(
            block = {
                unified.bindCharacter(s.id.orEmpty(), com.nekobot.app.data.model.BindCharacterRequest(
                    senderName = character.displayName,
                    characterId = character.id,
                    senderAvatar = character.avatar,
                    senderPortrait = character.portrait,
                    scenario = character.scenario,
                    systemPrompt = character.systemPrompt
                ))
            },
            onSuccess = {
                showToast(string(R.string.sessions_detail_bound_character, character.displayName))
                load(s.id.orEmpty()) // 重新加载会话
                onSuccess()
            }
        )
    }

    /** 加载可用 AI 模型列表（用于 TTS 模型选择下拉） */
    private fun loadAiModels() {
        viewModelScope.launch {
            try {
                if (isLocalMode) {
                    val models = com.nekobot.app.ServiceContainer.localRepository.listAiModels()
                    _aiModels.value = models.map { it.id to it.name }
                } else {
                    when (val r = repo.listAiModels()) {
                        is Resource.Success -> {
                            _aiModels.value = (r.data ?: emptyList()).mapNotNull { m ->
                                m.id?.let { it to (m.name ?: m.model ?: it) }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (_: Exception) { /* 忽略模型列表加载失败 */ }
        }
    }

    fun load(id: String) {
        launchResult(
            block = { unified.getSession(id) },
            onSuccess = { s ->
                _session.value = s
                name.value = s.name.orEmpty()
                tagsText.value = s.tags?.joinToString(", ").orEmpty()
                pinned.value = s.pinned == true
                favorite.value = s.favorite == true
                systemPrompt.value = s.systemPrompt.orEmpty()
                autoStateInterval.value = s.autoStateInterval
                plotMode.value = s.plotMode == true
                plotRealTimeSync.value = s.plotRealTimeSync == true
                plotChoiceStyle.value = s.plotChoiceStyle ?: ""
                // 解析 TTS / 主动聊天 / 公开分享
                android.util.Log.d("SessionDetail", "load: s.isPublic=${s.isPublic}, s.ttsConfig=${s.ttsConfig}, s.shareConfig=${s.shareConfig}")
                isPublic.value = false
                // 通知提醒从本地 PrefsManager 读取
                notificationEnabled.value = com.nekobot.app.ServiceContainer.prefs.isSessionNotificationEnabled(s.id.orEmpty())
                // proactive_chat: {"enabled":bool,"interval_minutes":int}
                runCatching {
                    val pc = s.proactiveChat?.asJsonObject
                    proactiveChatEnabled.value = pc?.get("enabled")?.asBoolean == true
                    proactiveChatInterval.value = pc?.get("interval_minutes")?.takeIf { !it.isJsonNull }?.asInt ?: 60
                }
                // tts_config: {"enabled":bool,"model_id":str,"voice":str}
                runCatching {
                    val tts = s.ttsConfig?.asJsonObject
                    ttsEnabled.value = tts?.get("enabled")?.asBoolean == true
                    ttsModelId.value = tts?.get("model_id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    ttsVoice.value = tts?.get("voice")?.takeIf { !it.isJsonNull }?.asString ?: ""
                }
                // share_config: {"expires_days":int,"password":str,"include_character":bool,"include_user_messages":bool,"message_start":int?,"message_end":int?}
                if (isLocalMode) {
                    resetPublicShareState(includeCharacter = s.sessionMode != "agent")
                } else {
                    resetPublicShareState(includeCharacter = s.sessionMode != "agent")
                    loadPublicShareStatus(s.id.orEmpty())
                }
                // 解析 custom_prompts
                _customPrompts.value = parseCustomPrompts(s.customPrompts)
                // 解析 prompt_stack_debug
                _promptStackDebug.value = parsePromptStackDebug(s.promptStackDebug)
                _composedSystemPrompt.value = s.composedSystemPrompt.orEmpty()
                _disabledPromptKeys.value = s.disabledPromptKeys?.toSet() ?: emptySet()
                // 查询绑定角色卡：用于回填 characterName（后端可能不返回）
                val cid = s.characterId
                if (!cid.isNullOrBlank() && s.characterName.isNullOrBlank()) {
                    launchResult(
                        block = { unified.getCharacter(cid) },
                        onSuccess = { char -> _characterDetail.value = char },
                        onError = { /* 忽略角色查询失败 */ }
                    )
                } else {
                    _characterDetail.value = null
                }
            }
        )
    }

    /** 远程公开状态不属于 Session 更新接口，必须从独立 public/status 接口读取。 */
    private fun loadPublicShareStatus(sessionId: String) {
        viewModelScope.launch {
            when (val result = repo.getSessionPublicStatus(sessionId)) {
                is Resource.Success -> {
                    if (_session.value?.id != sessionId) return@launch
                    val status = result.data
                    isPublic.value = status.isPublic
                    publicShareUrl.value = status.publicUrl.orEmpty()
                    publicSharePasswordRequired.value = status.passwordRequired
                    publicShareExpiresAt.value = status.expiresAt
                    status.options?.let { options ->
                        shareExpiresDays.value = options.expiresDays
                        shareIncludeCharacter.value = options.includeCharacter
                        shareIncludeUserMessages.value = options.includeUserMessages
                        shareMessageStart.value = options.messageStart?.toString().orEmpty()
                        shareMessageEnd.value = options.messageEnd?.toString().orEmpty()
                    }
                    // 服务端只返回是否设置密码，不会回传密码明文。
                    sharePassword.value = ""
                }
                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }

    private fun resetPublicShareState(includeCharacter: Boolean) {
        isPublic.value = false
        publicShareUrl.value = ""
        publicSharePasswordRequired.value = false
        publicShareExpiresAt.value = null
        shareExpiresDays.value = 30
        sharePassword.value = ""
        shareMessageStart.value = ""
        shareMessageEnd.value = ""
        shareIncludeCharacter.value = includeCharacter
        shareIncludeUserMessages.value = true
    }

    /** 与原仓库一致：公开分享是独立操作，不跟随会话详情保存。 */
    fun makeSessionPublic() {
        val s = _session.value ?: return
        if (isLocalMode) {
            showToast(string(R.string.sessions_detail_local_no_share))
            return
        }
        val request = PublicShareRequest(
            expiresDays = shareExpiresDays.value,
            password = sharePassword.value,
            includeCharacter = s.sessionMode != "agent" && shareIncludeCharacter.value,
            includeUserMessages = shareIncludeUserMessages.value,
            messageStart = shareMessageStart.value.trim().toIntOrNull(),
            messageEnd = shareMessageEnd.value.trim().toIntOrNull()
        )
        viewModelScope.launch {
            isLoadingPublic.value = true
            try {
                when (val result = repo.makeSessionPublic(s.id.orEmpty(), request)) {
                    is Resource.Success -> {
                        val status = result.data
                        isPublic.value = true
                        publicShareUrl.value = status.publicUrl.orEmpty()
                        publicSharePasswordRequired.value = status.passwordRequired
                        publicShareExpiresAt.value = status.expiresAt
                        sharePassword.value = ""
                        showToast(string(R.string.sessions_detail_published_toast))
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } finally {
                isLoadingPublic.value = false
            }
        }
    }

    fun removeSessionPublic() {
        val s = _session.value ?: return
        if (isLocalMode) return
        viewModelScope.launch {
            isLoadingPublic.value = true
            try {
                when (val result = repo.removeSessionPublic(s.id.orEmpty())) {
                    is Resource.Success -> {
                        resetPublicShareState(includeCharacter = s.sessionMode != "agent")
                        showToast(string(R.string.sessions_detail_unpublished_toast))
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } finally {
                isLoadingPublic.value = false
            }
        }
    }

    fun save(onSuccess: () -> Unit) {
        val s = _session.value ?: return
        val nameVal = name.value.trim()
        if (nameVal.isBlank()) {
            showToast(string(R.string.sessions_detail_name_empty_toast))
            return
        }
        android.util.Log.d("SessionDetail", "save: isPublic=${isPublic.value}, ttsEnabled=${ttsEnabled.value}, sessionId=${s.id}")
        val tagsList = tagsText.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // 构建 proactive_chat / tts_config JSON
        val proactiveJson = com.google.gson.JsonObject().apply {
            addProperty("enabled", proactiveChatEnabled.value)
            addProperty("interval_minutes", proactiveChatInterval.value)
        }
        val ttsJson = com.google.gson.JsonObject().apply {
            addProperty("enabled", ttsEnabled.value)
            addProperty("model_id", ttsModelId.value)
            addProperty("voice", ttsVoice.value.ifBlank { "" })
        }
        launchResult(
            block = {
                unified.updateSession(
                    s.id.orEmpty(),
                    UpdateSessionRequest(
                        name = nameVal,
                        tags = tagsList,
                        pinned = pinned.value,
                        favorite = favorite.value,
                        systemPrompt = systemPrompt.value.ifBlank { null },
                        autoStateInterval = autoStateInterval.value,
                        plotMode = plotMode.value,
                        plotRealTimeSync = plotRealTimeSync.value,
                        plotChoiceStyle = plotChoiceStyle.value.ifBlank { null },
                        disabledPromptKeys = _disabledPromptKeys.value.toList(),
                        proactiveChat = proactiveJson,
                        ttsConfig = ttsJson
                    )
                )
            },
            onSuccess = {
                android.util.Log.d("SessionDetail", "save onSuccess: starting reload")
                showToast(string(R.string.sessions_detail_saved_toast))
                load(s.id.orEmpty())
                onSuccess()
            }
        )
    }

    /** 设置某注入项是否关闭，并立即持久化到会话。 */
    fun setPromptKeyDisabled(key: String, disabled: Boolean) {
        val s = _session.value ?: return
        val previous = _disabledPromptKeys.value
        val updated = previous.toMutableSet().apply {
            if (disabled) add(key) else remove(key)
        }
        if (updated == previous) return
        _disabledPromptKeys.value = updated
        launchResult(
            block = {
                unified.updateSession(
                    s.id.orEmpty(),
                    UpdateSessionRequest(disabledPromptKeys = updated.toList())
                )
            },
            onSuccess = {
                showToast(if (disabled) string(R.string.sessions_detail_injection_disabled, key) else string(R.string.sessions_detail_injection_enabled, key))
            },
            onError = { message ->
                _disabledPromptKeys.value = previous
                showError(message)
            }
        )
    }

    // ---- custom_prompts 编辑 ----
    fun addCustomPrompt() {
        val list = _customPrompts.value.toMutableList()
        list.add(CustomPromptItem(order = list.size + 1, title = "", content = ""))
        _customPrompts.value = list
    }

    fun updateCustomPrompt(index: Int, title: String, content: String) {
        val list = _customPrompts.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(title = title, content = content)
            _customPrompts.value = list
        }
    }

    fun removeCustomPrompt(index: Int) {
        val list = _customPrompts.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            // 重新编号 order
            list.forEachIndexed { i, item -> list[i] = item.copy(order = i + 1) }
            _customPrompts.value = list
        }
    }

    /** 保存自定义提示词到后端 */
    fun saveCustomPrompts() {
        val s = _session.value ?: return
        val payload = _customPrompts.value
            .filter { it.content.isNotBlank() }
            .mapIndexed { idx, item ->
                mapOf(
                    "order" to (idx + 1),
                    "title" to item.title.trim(),
                    "content" to item.content.trim()
                )
            }
        launchResult(
            block = { unified.updateCustomPrompts(s.id.orEmpty(), payload) },
            onSuccess = {
                showToast(string(R.string.sessions_detail_custom_prompts_saved))
                load(s.id.orEmpty())
            }
        )
    }

    fun delete(onSuccess: () -> Unit) {
        val s = _session.value ?: return
        launchResult(
            block = { unified.deleteSession(s.id.orEmpty()) },
            onSuccess = {
                showToast(string(R.string.sessions_detail_deleted_toast))
                onSuccess()
            }
        )
    }

    // ---- JSON 解析辅助 ----
    private fun parseCustomPrompts(el: JsonElement?): List<CustomPromptItem> {
        if (el == null || !el.isJsonArray) return emptyList()
        return el.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            CustomPromptItem(
                order = obj.get("order")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "",
                content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
        }.sortedBy { it.order }
    }

    private fun parsePromptStackDebug(el: JsonElement?): List<PromptStackItem> {
        if (el == null || !el.isJsonArray) return emptyList()
        return el.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            PromptStackItem(
                key = obj.get("key")?.takeIf { !it.isJsonNull }?.asString ?: "",
                content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "",
                priority = obj.get("priority")?.takeIf { !it.isJsonNull }?.asInt ?: 100,
                role = obj.get("role")?.takeIf { !it.isJsonNull }?.asString ?: "system",
                scope = obj.get("scope")?.takeIf { !it.isJsonNull }?.asString ?: "turn",
                enabled = obj.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            )
        }.sortedBy { it.priority }
    }
}

/** 自定义提示词条目 */
data class CustomPromptItem(
    val order: Int = 0,
    val title: String = "",
    val content: String = ""
)

/** 运行时提示词注入栈条目（只读展示） */
data class PromptStackItem(
    val key: String = "",
    val content: String = "",
    val priority: Int = 100,
    val role: String = "system",
    val scope: String = "turn",
    val enabled: Boolean = true
)

/**
 * 会话详情页：参考 webui 会话详情弹窗，展示并编辑会话的全部元信息。
 * 分区：基本信息 / 标记 / 系统提示词 / 角色绑定 / 运行时状态 / 剧情模式 / 自动状态 / 只读信息。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val vm: SessionDetailViewModel = viewModel(key = "session_detail_$sessionId")
    val session by vm.session.collectAsState()
    val name by vm.name.collectAsState()
    val tagsText by vm.tagsText.collectAsState()
    val pinned by vm.pinned.collectAsState()
    val favorite by vm.favorite.collectAsState()
    val systemPrompt by vm.systemPrompt.collectAsState()
    val autoStateInterval by vm.autoStateInterval.collectAsState()
    val plotMode by vm.plotMode.collectAsState()
    val plotRealTimeSync by vm.plotRealTimeSync.collectAsState()
    val plotChoiceStyle by vm.plotChoiceStyle.collectAsState()
    val ttsEnabled by vm.ttsEnabled.collectAsState()
    val ttsModelId by vm.ttsModelId.collectAsState()
    val ttsVoice by vm.ttsVoice.collectAsState()
    val proactiveChatEnabled by vm.proactiveChatEnabled.collectAsState()
    val proactiveChatInterval by vm.proactiveChatInterval.collectAsState()
    val isPublic by vm.isPublic.collectAsState()
    val notificationEnabled by vm.notificationEnabled.collectAsState()
    val shareExpiresDays by vm.shareExpiresDays.collectAsState()
    val sharePassword by vm.sharePassword.collectAsState()
    val shareMessageStart by vm.shareMessageStart.collectAsState()
    val shareMessageEnd by vm.shareMessageEnd.collectAsState()
    val shareIncludeCharacter by vm.shareIncludeCharacter.collectAsState()
    val shareIncludeUserMessages by vm.shareIncludeUserMessages.collectAsState()
    val publicShareUrl by vm.publicShareUrl.collectAsState()
    val publicSharePasswordRequired by vm.publicSharePasswordRequired.collectAsState()
    val publicShareExpiresAt by vm.publicShareExpiresAt.collectAsState()
    val isLoadingPublic by vm.isLoadingPublic.collectAsState()
    val aiModels by vm.aiModels.collectAsState()
    val customPrompts by vm.customPrompts.collectAsState()
    val promptStackDebug by vm.promptStackDebug.collectAsState()
    val composedSystemPrompt by vm.composedSystemPrompt.collectAsState()
    val disabledPromptKeys by vm.disabledPromptKeys.collectAsState()
    val characterDetail by vm.characterDetail.collectAsState()
    val characters by vm.characters.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBindCharacterDialog by remember { mutableStateOf(false) }
    var selectedPromptStackItem by remember { mutableStateOf<PromptStackItem?>(null) }

    // 通知权限请求 launcher
    val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val sid = vm.session.value?.id
        if (!granted) {
            vm.notificationEnabled.value = false
            sid?.let { com.nekobot.app.ServiceContainer.prefs.setSessionNotificationEnabled(it, false) }
        }
    }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    // 提取本地化字符串
    val detailTitle = stringResource(R.string.sessions_detail_title)
    val backDesc = stringResource(R.string.common_back)
    val enterChatDesc = stringResource(R.string.sessions_detail_enter_chat)
    val saveDesc = stringResource(R.string.common_save)
    val deleteDesc = stringResource(R.string.common_delete)
    val portraitDesc = stringResource(R.string.sessions_detail_portrait)
    val characterColonFmt = stringResource(R.string.sessions_detail_character_colon)
    val pinnedBadge = stringResource(R.string.sessions_detail_pinned_badge)
    val favoriteBadge = stringResource(R.string.sessions_detail_favorite_badge)
    val archivedBadgeText = stringResource(R.string.sessions_detail_archived_badge)
    val readonlyBadge = stringResource(R.string.sessions_detail_readonly_badge)
    val messageCountFmt = stringResource(R.string.sessions_detail_message_count)
    val updatedFmt = stringResource(R.string.sessions_detail_updated)
    val basicInfoTitle = stringResource(R.string.sessions_detail_basic_info)
    val sessionNameLabel = stringResource(R.string.sessions_detail_name)
    val tagsLabel = stringResource(R.string.sessions_detail_tags)
    val pinnedLabel = stringResource(R.string.sessions_detail_pinned)
    val pinLabel = stringResource(R.string.sessions_detail_pin)
    val favoritedLabel = stringResource(R.string.sessions_detail_favorited)
    val favoriteLabel = stringResource(R.string.sessions_detail_favorite)
    val systemPromptTitle = stringResource(R.string.sessions_detail_system_prompt)
    val runtimeSystemPromptTitle = stringResource(R.string.sessions_detail_runtime_system_prompt)
    val runtimePromptDesc = stringResource(R.string.sessions_detail_runtime_prompt_desc)
    val customPromptsTitle = stringResource(R.string.sessions_detail_custom_prompts)
    val savePromptsDesc = stringResource(R.string.sessions_detail_save_prompts)
    val noCustomPromptsHint = stringResource(R.string.sessions_detail_no_custom_prompts)
    val promptStackTitle = stringResource(R.string.sessions_detail_prompt_stack)
    val promptStackDesc = stringResource(R.string.sessions_detail_prompt_stack_desc)
    val disabledPromptsCountFmt = stringResource(R.string.sessions_detail_disabled_prompts_count)
    val characterBindingTitle = stringResource(R.string.sessions_detail_character_binding)
    val characterIdLabel = stringResource(R.string.sessions_detail_character_id)
    val characterNameLabelText = stringResource(R.string.sessions_detail_character_name_label)
    val senderNameLabel = stringResource(R.string.sessions_detail_sender_name)
    val scenarioLabel = stringResource(R.string.sessions_detail_scenario)
    val groupCharactersLabel = stringResource(R.string.sessions_detail_group_characters)
    val changeCharacterLabel = stringResource(R.string.sessions_detail_change_character)
    val runtimeStateTitle = stringResource(R.string.sessions_detail_runtime_state)
    val moodLabel = stringResource(R.string.sessions_detail_mood)
    val intensityLabel = stringResource(R.string.sessions_detail_intensity)
    val energyLabel = stringResource(R.string.sessions_detail_energy)
    val affectionLabel = stringResource(R.string.sessions_detail_affection)
    val trustLabel = stringResource(R.string.sessions_detail_trust)
    val familiarityLabel = stringResource(R.string.sessions_detail_familiarity)
    val dependencyLabel = stringResource(R.string.sessions_detail_dependency)
    val securityLabel = stringResource(R.string.sessions_detail_security)
    val jealousyLabel = stringResource(R.string.sessions_detail_jealousy)
    val surfaceEmotionLabel = stringResource(R.string.sessions_detail_surface_emotion)
    val innerEmotionLabel = stringResource(R.string.sessions_detail_inner_emotion)
    val advancedTitle = stringResource(R.string.sessions_detail_advanced)
    val plotModeOnLabel = stringResource(R.string.sessions_detail_plot_mode_on)
    val plotModeOffLabel = stringResource(R.string.sessions_detail_plot_mode_off)
    val realtimeSyncOnLabel = stringResource(R.string.sessions_detail_realtime_sync_on)
    val realtimeSyncOffLabel = stringResource(R.string.sessions_detail_realtime_sync_off)
    val replyStyleLabel = stringResource(R.string.sessions_detail_reply_style)
    val autoStateIntervalLabel = stringResource(R.string.sessions_detail_auto_state_interval)
    val ttsProactiveTitle = stringResource(R.string.sessions_detail_tts_proactive)
    val ttsOnLabel = stringResource(R.string.sessions_detail_tts_on)
    val ttsOffLabel = stringResource(R.string.sessions_detail_tts_off)
    val ttsModelLabel = stringResource(R.string.sessions_detail_tts_model)
    val voiceLabel = stringResource(R.string.sessions_detail_voice)
    val proactiveOnLabel = stringResource(R.string.sessions_detail_proactive_on)
    val proactiveOffLabel = stringResource(R.string.sessions_detail_proactive_off)
    val proactiveIntervalLabel = stringResource(R.string.sessions_detail_proactive_interval)
    val publicShareTitle = stringResource(R.string.sessions_detail_public_share)
    val publicLocalUnsupported = stringResource(R.string.sessions_detail_public_local_unsupported)
    val publicDesc = stringResource(R.string.sessions_detail_public_desc)
    val expiryLabel = stringResource(R.string.sessions_detail_expiry)
    val passwordLabel = stringResource(R.string.sessions_detail_password)
    val startMsgLabel = stringResource(R.string.sessions_detail_start_msg)
    val endMsgLabel = stringResource(R.string.sessions_detail_end_msg)
    val agentNoCharacterLabel = stringResource(R.string.sessions_detail_agent_no_character)
    val characterShowLabel = stringResource(R.string.sessions_detail_character_show)
    val characterHideLabel = stringResource(R.string.sessions_detail_character_hide)
    val userMsgShowLabel = stringResource(R.string.sessions_detail_user_msg_show)
    val userMsgHideLabel = stringResource(R.string.sessions_detail_user_msg_hide)
    val processingText = stringResource(R.string.sessions_detail_processing)
    val publishText = stringResource(R.string.sessions_detail_publish)
    val publishedText = stringResource(R.string.sessions_detail_published)
    val copyLinkText = stringResource(R.string.sessions_detail_copy_link)
    val openLinkText = stringResource(R.string.sessions_detail_open_link)
    val unpublishText = stringResource(R.string.sessions_detail_unpublish)
    val linkCopiedToast = stringResource(R.string.sessions_detail_link_copied)
    val readonlyViewText = stringResource(R.string.sessions_detail_readonly_view)
    val passwordRequiredText = stringResource(R.string.sessions_detail_password_required)
    val expiresAtFmt = stringResource(R.string.sessions_detail_expires_at)
    val notificationTitle = stringResource(R.string.sessions_detail_notification)
    val notificationSubtitle = stringResource(R.string.sessions_detail_notification_subtitle)
    val notificationOnLabel = stringResource(R.string.sessions_detail_notification_on)
    val notificationOffLabel = stringResource(R.string.sessions_detail_notification_off)
    val notificationDesc = stringResource(R.string.sessions_detail_notification_desc)
    val metaTitle = stringResource(R.string.sessions_detail_meta)
    val sessionIdLabel = stringResource(R.string.sessions_detail_session_id)
    val userIdLabel = stringResource(R.string.sessions_detail_user_id)
    val createdAtLabel = stringResource(R.string.sessions_detail_created_at)
    val updatedAtLabel = stringResource(R.string.sessions_detail_updated_at)
    val publicLabel = stringResource(R.string.sessions_detail_public_label)
    val archivedLabel = stringResource(R.string.sessions_detail_archived_label)
    val readonlyLabel = stringResource(R.string.sessions_detail_readonly_label)
    val lastMessageLabel = stringResource(R.string.sessions_detail_last_message)
    val deleteDialogTitle = stringResource(R.string.sessions_detail_delete_title)
    val deleteConfirmFmt = stringResource(R.string.sessions_detail_delete_confirm)
    val copyDesc = stringResource(R.string.common_copy)
    val addDesc = stringResource(R.string.common_add)
    val yesText = stringResource(R.string.common_yes)
    val noText = stringResource(R.string.common_no)
    val doneText = stringResource(R.string.common_done)
    val cancelText = stringResource(R.string.common_cancel)
    val okText = stringResource(R.string.common_ok)

    LaunchedEffect(sessionId) { vm.init(sessionId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(detailTitle, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc, tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { session?.id?.let(onOpenChat) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = enterChatDesc, tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Filled.Save, contentDescription = saveDesc, tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = deleteDesc, tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (loading && session == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    session?.let { s ->
                        // === 1. 顶部：立绘 + 基本信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val portraitUrl = resolveAvatarUrl(s.portraitUrl)
                                Box(
                                    modifier = Modifier
                                        .size(width = 80.dp, height = 104.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!portraitUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = portraitUrl,
                                            contentDescription = s.characterName ?: portraitDesc,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                                    }
                                }
                                Spacer(Modifier.size(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(s.displayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!s.characterName.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(characterColonFmt.format(s.characterName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    // 类型 / 模式 badge
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        s.type?.let { BadgeChip(it) }
                                        s.sessionMode?.let { BadgeChip(it) }
                                        if (s.pinned == true) BadgeChip(pinnedBadge, MaterialTheme.colorScheme.primary)
                                        if (s.favorite == true) BadgeChip(favoriteBadge, WarningAmber)
                                        if (s.archived == true) BadgeChip(archivedBadgeText, MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (s.readOnly == true) BadgeChip(readonlyBadge, MaterialTheme.colorScheme.error)
                                    }
                                    s.messageCount?.let { count ->
                                        Spacer(Modifier.height(4.dp))
                                        Text(messageCountFmt.format(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    s.updatedAt?.let { time ->
                                        Text(updatedFmt.format(time.take(19)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        // === 2. 编辑基本信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = basicInfoTitle)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { vm.name.value = it },
                                label = { Text(sessionNameLabel) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tagsText,
                                onValueChange = { vm.tagsText.value = it },
                                label = { Text(tagsLabel) },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ToggleChipRow(
                                    label = if (pinned) pinnedLabel else pinLabel,
                                    selected = pinned,
                                    onClick = { vm.pinned.value = !pinned },
                                    modifier = Modifier.weight(1f)
                                )
                                ToggleChipRow(
                                    label = if (favorite) favoritedLabel else favoriteLabel,
                                    selected = favorite,
                                    onClick = { vm.favorite.value = !favorite },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // === 3. 系统提示词 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = systemPromptTitle,
                                trailing = {
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(systemPrompt)) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = copyDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = systemPrompt,
                                onValueChange = { vm.systemPrompt.value = it },
                                label = { Text("System Prompt") },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // === 3.4 运行时系统提示词（composed_system_prompt，只读） ===
                        if (composedSystemPrompt.isNotBlank()) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = runtimeSystemPromptTitle,
                                    trailing = {
                                        IconButton(onClick = { clipboard.setText(AnnotatedString(composedSystemPrompt)) }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = copyDesc, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    runtimePromptDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = composedSystemPrompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(8.dp)
                                )
                            }
                        }

                        // === 3.5 自定义提示词（custom_prompts，可编辑） ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = customPromptsTitle,
                                trailing = {
                                    Row {
                                        IconButton(onClick = { vm.addCustomPrompt() }) {
                                            Icon(Icons.Filled.Add, contentDescription = addDesc, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { vm.saveCustomPrompts() }) {
                                            Icon(Icons.Filled.Save, contentDescription = savePromptsDesc, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            if (customPrompts.isEmpty()) {
                                Text(
                                    noCustomPromptsHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                customPrompts.forEachIndexed { idx, item ->
                                    CustomPromptEditor(
                                        item = item,
                                        onTitleChange = { vm.updateCustomPrompt(idx, it, item.content) },
                                        onContentChange = { vm.updateCustomPrompt(idx, item.title, it) },
                                        onRemove = { vm.removeCustomPrompt(idx) }
                                    )
                                    if (idx < customPrompts.lastIndex) Spacer(Modifier.height(8.dp))
                                }
                            }
                        }

                        // === 3.6 提示词注入栈（prompt_stack_debug，只读 + 可禁用） ===
                        if (promptStackDebug.isNotEmpty()) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = promptStackTitle)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    promptStackDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                promptStackDebug.forEach { inj ->
                                    PromptStackChip(
                                        item = inj,
                                        disabled = inj.key in disabledPromptKeys,
                                        onClick = { selectedPromptStackItem = inj }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (disabledPromptKeys.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        disabledPromptsCountFmt.format(disabledPromptKeys.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarningAmber
                                    )
                                }
                            }
                        }

                        // === 4. 角色绑定信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = characterBindingTitle)
                            Spacer(Modifier.height(8.dp))
                            DetailLine(label = characterIdLabel, value = s.characterId ?: "—")
                            // 优先用后端返回的 characterName，否则用二次查询的角色详情
                            DetailLine(label = characterNameLabelText, value = s.characterName ?: characterDetail?.name ?: "—")
                            DetailLine(label = senderNameLabel, value = s.senderName ?: "—")
                            DetailLine(label = scenarioLabel, value = s.scenario?.take(60) ?: "—")
                            s.characterIds?.takeIf { it.isNotEmpty() }?.let {
                                DetailLine(label = groupCharactersLabel, value = it.joinToString(", "))
                            }
                            Spacer(Modifier.height(12.dp))
                            // 更改绑定角色按钮
                            OutlinedButton(
                                onClick = { showBindCharacterDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Filled.SwapHoriz,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    changeCharacterLabel,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // === 5. 角色运行时状态 ===
                        s.characterRuntimeSnapshot?.takeIf { it.isJsonObject }?.asJsonObject?.let { snap ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = runtimeStateTitle)
                                Spacer(Modifier.height(8.dp))
                                // 心情 / 能量
                                val mood = snap.get("mood")?.asString
                                val intensity = snap.get("mood_intensity")?.let { if (it.isJsonPrimitive) it.asFloat else null }
                                val energy = snap.get("energy")?.let { if (it.isJsonPrimitive) it.asInt else null }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StateMiniCard(moodLabel, mood, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    StateMiniCard(intensityLabel, intensity?.let { "${(it * 100).toInt()}%" }, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                    StateMiniCard(energyLabel, energy?.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(8.dp))
                                // 关系数值
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RelBar(affectionLabel, snap.get("affection")?.asInt, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    RelBar(trustLabel, snap.get("trust")?.asInt, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                    RelBar(familiarityLabel, snap.get("familiarity")?.asInt, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                                    RelBar(dependencyLabel, snap.get("dependency")?.asInt, WarningAmber, Modifier.weight(1f))
                                    RelBar(securityLabel, snap.get("security")?.asInt, SuccessGreen, Modifier.weight(1f))
                                    RelBar(jealousyLabel, snap.get("jealousy")?.asInt, MaterialTheme.colorScheme.error, Modifier.weight(1f))
                                }
                                // 表情
                                snap.get("visible_emotion")?.asString?.let {
                                    Spacer(Modifier.height(8.dp))
                                    DetailLine(label = surfaceEmotionLabel, value = it)
                                }
                                snap.get("hidden_emotion")?.asString?.let {
                                    DetailLine(label = innerEmotionLabel, value = it)
                                }
                            }
                        }

                        // === 6. 剧情模式 / 自动状态 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = advancedTitle)
                            Spacer(Modifier.height(8.dp))
                            ToggleChipRow(
                                label = if (plotMode) plotModeOnLabel else plotModeOffLabel,
                                selected = plotMode,
                                onClick = { vm.plotMode.value = !plotMode },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (plotMode) {
                                Spacer(Modifier.height(8.dp))
                                ToggleChipRow(
                                    label = if (plotRealTimeSync) realtimeSyncOnLabel else realtimeSyncOffLabel,
                                    selected = plotRealTimeSync,
                                    onClick = { vm.plotRealTimeSync.value = !plotRealTimeSync },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                // 回复风格编辑器
                                var showStyleDialog by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        replyStyleLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(onClick = { showStyleDialog = true }) {
                                        Text(
                                            text = stringResource(plotStyleLabelResId(plotChoiceStyle)),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                if (showStyleDialog) {
                                    PlotStylePickerDialog(
                                        currentStyle = plotChoiceStyle,
                                        onConfirm = { newStyle ->
                                            vm.plotChoiceStyle.value = newStyle
                                            showStyleDialog = false
                                        },
                                        onDismiss = { showStyleDialog = false }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            // 自动状态间隔下拉
                            Text(autoStateIntervalLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            AutoStateIntervalSelector(
                                value = autoStateInterval,
                                onChange = { vm.autoStateInterval.value = it }
                            )
                        }

                        // === 6.5 TTS / 主动聊天 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = ttsProactiveTitle)
                            Spacer(Modifier.height(8.dp))

                            // TTS 开关
                            ToggleChipRow(
                                label = if (ttsEnabled) ttsOnLabel else ttsOffLabel,
                                selected = ttsEnabled,
                                onClick = { vm.ttsEnabled.value = !ttsEnabled },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (ttsEnabled) {
                                Spacer(Modifier.height(8.dp))
                                // TTS 模型选择下拉
                                Text(ttsModelLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                TtsModelSelector(
                                    modelId = ttsModelId,
                                    models = aiModels,
                                    onChange = { vm.ttsModelId.value = it }
                                )
                                Spacer(Modifier.height(8.dp))
                                // 音色选择
                                Text(voiceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                TtsVoiceSelector(
                                    voice = ttsVoice,
                                    onChange = { vm.ttsVoice.value = it }
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // 主动聊天开关
                            ToggleChipRow(
                                label = if (proactiveChatEnabled) proactiveOnLabel else proactiveOffLabel,
                                selected = proactiveChatEnabled,
                                onClick = { vm.proactiveChatEnabled.value = !proactiveChatEnabled },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (proactiveChatEnabled) {
                                Spacer(Modifier.height(8.dp))
                                Text(proactiveIntervalLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(4.dp))
                                ProactiveIntervalSelector(
                                    value = proactiveChatInterval,
                                    onChange = { vm.proactiveChatInterval.value = it }
                                )
                            }

                        }

                        // === 6.6 公开分享：与原仓库一致，独立于会话详情保存 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = publicShareTitle)
                            Spacer(Modifier.height(8.dp))
                            if (com.nekobot.app.ServiceContainer.prefs.isLocalMode) {
                                Text(
                                    publicLocalUnsupported,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (!isPublic) {
                                Text(
                                    publicDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(expiryLabel, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                ShareExpirySelector(
                                    value = shareExpiresDays,
                                    onChange = { vm.shareExpiresDays.value = it }
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = sharePassword,
                                    onValueChange = { vm.sharePassword.value = it },
                                    label = { Text(passwordLabel, style = MaterialTheme.typography.labelSmall) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = shareMessageStart,
                                        onValueChange = { vm.shareMessageStart.value = it.filter(Char::isDigit) },
                                        label = { Text(startMsgLabel, style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = shareMessageEnd,
                                        onValueChange = { vm.shareMessageEnd.value = it.filter(Char::isDigit) },
                                        label = { Text(endMsgLabel, style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ToggleChipRow(
                                        label = if (s.sessionMode == "agent") agentNoCharacterLabel else if (shareIncludeCharacter) characterShowLabel else characterHideLabel,
                                        selected = shareIncludeCharacter && s.sessionMode != "agent",
                                        onClick = { if (s.sessionMode != "agent") vm.shareIncludeCharacter.value = !shareIncludeCharacter },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ToggleChipRow(
                                        label = if (shareIncludeUserMessages) userMsgShowLabel else userMsgHideLabel,
                                        selected = shareIncludeUserMessages,
                                        onClick = { vm.shareIncludeUserMessages.value = !shareIncludeUserMessages },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = vm::makeSessionPublic,
                                    enabled = !isLoadingPublic,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isLoadingPublic) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (isLoadingPublic) processingText else publishText)
                                }
                            } else {
                                Text(publishedText, style = MaterialTheme.typography.titleSmall, color = SuccessGreen)
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(
                                        publicShareUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(publicShareUrl))
                                            vm.showToast(linkCopiedToast)
                                        },
                                        enabled = publicShareUrl.isNotBlank(),
                                        modifier = Modifier.weight(1f)
                                    ) { Text(copyLinkText) }
                                    OutlinedButton(
                                        onClick = { uriHandler.openUri(publicShareUrl) },
                                        enabled = publicShareUrl.isNotBlank(),
                                        modifier = Modifier.weight(1f)
                                    ) { Text(openLinkText) }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = vm::removeSessionPublic,
                                    enabled = !isLoadingPublic,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isLoadingPublic) processingText else unpublishText, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    buildString {
                                        append(readonlyViewText)
                                        if (publicSharePasswordRequired) append(" · $passwordRequiredText")
                                        publicShareExpiresAt?.let { append(" · " + expiresAtFmt.format(formatPublicExpiresAt(it))) }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // === 6.7 通知提醒 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = notificationTitle, subtitle = notificationSubtitle)
                            Spacer(Modifier.height(8.dp))
                            ToggleChipRow(
                                label = if (notificationEnabled) notificationOnLabel else notificationOffLabel,
                                selected = notificationEnabled,
                                onClick = {
                                    val newVal = !notificationEnabled
                                    if (newVal && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                        // 开启通知时先请求运行时权限
                                        vm.notificationEnabled.value = true
                                        s.id?.let { com.nekobot.app.ServiceContainer.prefs.setSessionNotificationEnabled(it, true) }
                                        notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        vm.notificationEnabled.value = newVal
                                        s.id?.let { com.nekobot.app.ServiceContainer.prefs.setSessionNotificationEnabled(it, newVal) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (notificationEnabled) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    notificationDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // === 7. 只读元信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = metaTitle)
                            Spacer(Modifier.height(8.dp))
                            DetailLine(label = sessionIdLabel, value = s.id.orEmpty())
                            DetailLine(label = userIdLabel, value = s.userId ?: "—")
                            DetailLine(label = createdAtLabel, value = s.createdAt?.take(19) ?: "—")
                            DetailLine(label = updatedAtLabel, value = s.updatedAt?.take(19) ?: "—")
                            DetailLine(label = publicLabel, value = if (isPublic) yesText else noText)
                            DetailLine(label = archivedLabel, value = if (s.archived == true) yesText else noText)
                            DetailLine(label = readonlyLabel, value = if (s.readOnly == true) yesText else noText)
                            s.lastMessage?.let {
                                DetailLine(label = lastMessageLabel, value = it.take(50))
                            }
                        }

                        error?.let {
                            ErrorBanner(message = it, onRetry = {
                                vm.clearError()
                                vm.load(sessionId)
                            })
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        NekoDialog(
            onDismiss = { showDeleteDialog = false },
            title = deleteDialogTitle,
            message = deleteConfirmFmt.format(session?.displayName ?: ""),
            confirmText = deleteDesc,
            onConfirm = {
                showDeleteDialog = false
                vm.delete(onBack)
            }
        )
    }

    selectedPromptStackItem?.let { item ->
        PromptStackDetailDialog(
            item = item,
            disabled = item.key in disabledPromptKeys,
            onDisabledChange = { vm.setPromptKeyDisabled(item.key, it) },
            onDismiss = { selectedPromptStackItem = null }
        )
    }

    if (showBindCharacterDialog) {
        BindCharacterPickerDialog(
            characters = characters,
            onDismiss = { showBindCharacterDialog = false },
            onSelect = { char ->
                vm.bindCharacter(char) {
                    showBindCharacterDialog = false
                }
            }
        )
    }
}

/** 提示词注入详情：展示完整内容，并允许直接关闭或重新启用该项。 */
@Composable
private fun PromptStackDetailDialog(
    item: PromptStackItem,
    disabled: Boolean,
    onDisabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val injectionDetailTitle = stringResource(R.string.sessions_detail_injection_detail_title)
    val doneText = stringResource(R.string.common_done)
    val priorityFmt = stringResource(R.string.sessions_detail_priority)
    val scopeFmt = stringResource(R.string.sessions_detail_scope)
    val roleBadgeFmt = stringResource(R.string.sessions_detail_role_badge)
    val injectionContentLabel = stringResource(R.string.sessions_detail_injection_content)
    val injectionEmptyText = stringResource(R.string.sessions_detail_injection_empty)
    val disableInjectionLabel = stringResource(R.string.sessions_detail_disable_injection)
    val disableInjectionDesc = stringResource(R.string.sessions_detail_disable_injection_desc)

    NekoDialog(
        onDismiss = onDismiss,
        title = injectionDetailTitle,
        confirmText = doneText,
        onConfirm = onDismiss
    ) {
        Text(item.key, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BadgeChip(priorityFmt.format(item.priority))
            BadgeChip(scopeFmt.format(item.scope))
            BadgeChip(roleBadgeFmt.format(item.role))
        }
        Spacer(Modifier.height(12.dp))
        Text(injectionContentLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = item.content.ifBlank { injectionEmptyText },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDisabledChange(!disabled) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(disableInjectionLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(disableInjectionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = disabled, onCheckedChange = onDisabledChange)
        }
    }
}

// ==================== 辅助组件 ====================

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 绑定角色选择弹窗：展示角色列表，点击即选择 */
@Composable
private fun BindCharacterPickerDialog(
    characters: List<com.nekobot.app.data.model.CharacterPreset>,
    onDismiss: () -> Unit,
    onSelect: (com.nekobot.app.data.model.CharacterPreset) -> Unit
) {
    var selectedCharacter by remember(characters) {
        mutableStateOf<com.nekobot.app.data.model.CharacterPreset?>(null)
    }
    val selectCharacterTitle = stringResource(R.string.sessions_detail_select_character)
    val bindText = stringResource(R.string.sessions_detail_bind)
    val cancelText = stringResource(R.string.common_cancel)
    val noCharactersText = stringResource(R.string.sessions_detail_no_characters)
    val portraitDesc = stringResource(R.string.sessions_detail_portrait)
    val selectedDesc = stringResource(R.string.sessions_detail_selected)

    NekoDialog(
        onDismiss = onDismiss,
        title = selectCharacterTitle,
        confirmText = bindText,
        confirmEnabled = selectedCharacter != null,
        onConfirm = { selectedCharacter?.let(onSelect) },
        cancelText = cancelText,
        onCancel = onDismiss
    ) {
        if (characters.isEmpty()) {
            Text(noCharactersText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(characters, key = { it.id ?: it.name ?: "" }) { char ->
                    val selected = selectedCharacter?.id == char.id
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedCharacter = char },
                        cornerRadius = 12,
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val avatarUrl = resolveAvatarUrl(char.avatarUrl)
                            if (avatarUrl != null) {
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = "${char.displayName} $portraitDesc",
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    char.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                char.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = selectedDesc, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleChipRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun StateMiniCard(
    label: String,
    value: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value ?: "—", style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RelBar(
    label: String,
    value: Int?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 剧情选项风格预设列表（对应原仓库 _STYLE_PRESETS） */
private val PLOT_STYLE_PRESETS = listOf(
    "" to R.string.sessions_detail_style_default,
    "sweet" to R.string.sessions_detail_style_sweet,
    "suspense" to R.string.sessions_detail_style_suspense,
    "daily" to R.string.sessions_detail_style_daily,
    "dramatic" to R.string.sessions_detail_style_dramatic
)

/** 根据风格文本返回显示标签的资源 ID */
private fun plotStyleLabelResId(style: String): Int {
    return PLOT_STYLE_PRESETS.firstOrNull { it.first == style }?.second
        ?: if (style.isNotBlank()) R.string.sessions_detail_style_custom else R.string.sessions_detail_style_unset
}

/** 回复风格选择弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlotStylePickerDialog(
    currentStyle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember { mutableStateOf(PLOT_STYLE_PRESETS.firstOrNull { it.first == currentStyle }?.first ?: "custom") }
    var customText by remember { mutableStateOf(if (selectedPreset == "custom") currentStyle else "") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_detail_edit_reply_style), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.sessions_detail_reply_style_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                PLOT_STYLE_PRESETS.forEach { (key, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedPreset = key }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedPreset == key,
                            onClick = { selectedPreset = key }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                // 自定义选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedPreset = "custom" }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = selectedPreset == "custom",
                        onClick = { selectedPreset = "custom" }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessions_detail_style_custom), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (selectedPreset == "custom") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        label = { Text(stringResource(R.string.sessions_detail_custom_style_desc)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val result = when (selectedPreset) {
                    "custom" -> customText.trim()
                    else -> selectedPreset
                }
                onConfirm(result)
            }) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoStateIntervalSelector(
    value: Int?,
    onChange: (Int?) -> Unit
) {
    val options = listOf(
        null to R.string.sessions_detail_interval_global,
        0 to R.string.sessions_detail_interval_off,
        1 to R.string.sessions_detail_interval_every_1,
        2 to R.string.sessions_detail_interval_every_2,
        3 to R.string.sessions_detail_interval_every_3,
        5 to R.string.sessions_detail_interval_every_5,
        8 to R.string.sessions_detail_interval_every_8,
        10 to R.string.sessions_detail_interval_every_10
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (interval, labelRes) ->
            FilterChip(
                selected = value == interval,
                onClick = { onChange(interval) },
                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProactiveIntervalSelector(
    value: Int,
    onChange: (Int) -> Unit
) {
    val options = listOf(
        5 to R.string.sessions_detail_duration_5m,
        10 to R.string.sessions_detail_duration_10m,
        15 to R.string.sessions_detail_duration_15m,
        30 to R.string.sessions_detail_duration_30m,
        60 to R.string.sessions_detail_duration_1h,
        120 to R.string.sessions_detail_duration_2h,
        240 to R.string.sessions_detail_duration_4h,
        480 to R.string.sessions_detail_duration_8h
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (minutes, labelRes) ->
            FilterChip(
                selected = value == minutes,
                onClick = { onChange(minutes) },
                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/** TTS 模型选择器：下拉菜单展示可用 AI 模型 */
@Composable
private fun TtsModelSelector(
    modelId: String,
    models: List<Pair<String, String>>,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val notSelectedLabel = stringResource(R.string.sessions_detail_not_selected)
    val selectedName = models.firstOrNull { it.first == modelId }?.second
        ?: if (modelId.isBlank()) notSelectedLabel else modelId
    Box {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable { expanded = true },
            cornerRadius = 12,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxSize()
            ) {
                Text(
                    text = selectedName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (modelId.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(notSelectedLabel, color = if (modelId.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                onClick = { onChange(""); expanded = false }
            )
            models.forEach { (id, name) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            color = if (id == modelId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (id == modelId) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onChange(id); expanded = false }
                )
            }
            if (models.isEmpty()) {
                Text(stringResource(R.string.sessions_detail_no_models), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** TTS 音色选择器：常用音色 FilterChips + 自定义输入 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsVoiceSelector(
    voice: String,
    onChange: (String) -> Unit
) {
    val commonVoices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral", "verse", "ballad", "ash", "sage")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        commonVoices.forEach { v ->
            FilterChip(
                selected = voice == v,
                onClick = { onChange(v) },
                label = { Text(v, style = MaterialTheme.typography.labelSmall) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = voice,
        onValueChange = { onChange(it) },
        label = { Text(stringResource(R.string.sessions_detail_custom_voice), style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 公开分享有效期选择器 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareExpirySelector(
    value: Int,
    onChange: (Int) -> Unit
) {
    val options = listOf(
        1 to R.string.sessions_detail_expiry_1d,
        7 to R.string.sessions_detail_expiry_7d,
        30 to R.string.sessions_detail_expiry_30d,
        90 to R.string.sessions_detail_expiry_90d,
        365 to R.string.sessions_detail_expiry_365d
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (days, labelRes) ->
            FilterChip(
                selected = value == days,
                onClick = { onChange(days) },
                label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/** 自定义提示词编辑器：标题 + 内容 + 删除按钮 */
@Composable
private fun CustomPromptEditor(
    item: CustomPromptItem,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${item.order}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = item.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.sessions_detail_title_field), style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = item.content,
            onValueChange = onContentChange,
            label = { Text(stringResource(R.string.sessions_detail_content_field), style = MaterialTheme.typography.labelSmall) },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 提示词注入栈条目：展示 key/priority/scope/content，点击切换启用 */
@Composable
private fun PromptStackChip(
    item: PromptStackItem,
    disabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (disabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 优先级
        Text(
            "#${item.priority}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.key,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                // scope badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(item.scope, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
            if (item.content.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 启用/禁用指示
        Icon(
            imageVector = if (disabled) Icons.Filled.Close else Icons.Filled.Check,
            contentDescription = if (disabled) stringResource(R.string.sessions_detail_disabled) else stringResource(R.string.sessions_detail_enabled),
            tint = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else SuccessGreen,
            modifier = Modifier.size(14.dp)
        )
    }
}

private fun formatPublicExpiresAt(expiresAt: Double): String {
    val epochMillis = if (expiresAt > 10_000_000_000) expiresAt.toLong() else (expiresAt * 1000).toLong()
    return runCatching {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(epochMillis))
    }.getOrDefault("")
}
