package com.nekobot.app.ui.screens.sessions

import androidx.compose.runtime.Immutable
import com.nekobot.app.data.local.ai.AgentAttentionKind
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session

@Immutable
data class SessionOverview(
    val total: Int = 0,
    val characterSessions: Int = 0,
    val agentSessions: Int = 0,
    val agentCharacterSessions: Int = 0,
    val pinned: Int = 0,
    val favorite: Int = 0,
    val archived: Int = 0
)

/**
 * 会话列表行只保留绘制和交互需要的稳定字段，避免 LazyColumn 直接接收包含
 * JsonElement / List 的完整 Session，并把角色回退查找移出滚动热路径。
 */
@Immutable
data class SessionListRow(
    val key: String,
    val id: String?,
    val name: String,
    val displayName: String,
    val portraitUrl: String?,
    val characterLabel: String?,
    val updatedAt: String?,
    val lastMessage: String?,
    val messageCount: Int?,
    /** 会话标签（详情页设置，列表展示）。 */
    val tags: List<String> = emptyList(),
    val pinned: Boolean,
    val favorite: Boolean,
    val archived: Boolean,
    /** Agent 会话：列表项无头像时显示 Agent 图标。 */
    val isAgentSession: Boolean = false,
    /** Agent 会话开启「继承完整角色能力」：按角色会话展示绑定角色名与立绘。 */
    val inheritCharacter: Boolean = false,
    /** 群聊会话：列表项无立绘时回退到群聊图标而非默认聊天图标。 */
    val isGroupSession: Boolean = false,
    val modeLabel: SessionModeLabel = SessionModeLabel.CHARACTER,
    /** 绑定的角色 ID（角色会话/单角色会话），首页「固定角色」按它聚合。 */
    val characterId: String? = null,
    val listTitleOverride: String? = null,
    /** 临时会话：仅当前进程保留，关闭后清除。 */
    val isTemporary: Boolean = false
) {
    /** Tool chats are tasks; companion and character chats are people. */
    val listTitle: String get() = com.nekobot.app.data.model.sessionTitle(listTitleOverride, when (modeLabel) {
        SessionModeLabel.CHARACTER, SessionModeLabel.COMPANION -> characterLabel?.takeIf(String::isNotBlank) ?: displayName
        SessionModeLabel.AGENT, SessionModeLabel.GROUP -> displayName
    })
    val listDate: String? get() = updatedAt?.let {
        if (it.length == 10 && it[4] == '-' && it[7] == '-') it.substring(5) else it
    }
}

/**
 * 会话行上的「等待用户处理」状态（命令授权 / 提问 / 插件安装）。
 *
 * 文案直接占用原来放最后一条消息的位置；[kind] 决定行首图标。
 */
@Immutable
data class SessionAttentionStatus(
    val kind: AgentAttentionKind,
    val text: String
)

/**
 * 列表只展示日期部分，避免完整 ISO 时间戳挤占会话标题和消息预览的空间。
 * 无法识别的值保持原样，兼容服务端的非标准时间格式。
 */
internal fun formatSessionListDate(value: String?): String? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hasIsoDatePrefix = text.length >= 10 &&
        text[4] == '-' &&
        text[7] == '-' &&
        text.take(4).all(Char::isDigit) &&
        text.substring(5, 7).all(Char::isDigit) &&
        text.substring(8, 10).all(Char::isDigit)
    return if (hasIsoDatePrefix) text.take(10) else text
}

val QUICK_SESSION_FILTERS = listOf(
    SessionFilter.CHARACTER_SESSIONS,
    SessionFilter.AGENT_SESSIONS,
    SessionFilter.AGENT_CHARACTERS,
    SessionFilter.FAVORITE,
    SessionFilter.ARCHIVED
)

fun buildSessionOverview(sessions: List<Session>): SessionOverview {
    var total = 0
    var characterSessions = 0
    var agentSessions = 0
    var agentCharacterSessions = 0
    var pinned = 0
    var favorite = 0
    var archived = 0
    sessions.forEach { session ->
        if (session.isArchive == true) return@forEach
        total++
        if (session.archived != true) {
            when {
                session.isAgentCharacterSession() -> agentCharacterSessions++
                session.isAgentSession() -> agentSessions++
                session.isCharacterSession() -> characterSessions++
            }
        }
        if (session.pinned == true) pinned++
        if (session.favorite == true) favorite++
        if (session.archived == true) archived++
    }
    return SessionOverview(
        total = total,
        characterSessions = characterSessions,
        agentSessions = agentSessions,
        agentCharacterSessions = agentCharacterSessions,
        pinned = pinned,
        favorite = favorite,
        archived = archived
    )
}

internal fun Session.isAgentSession(): Boolean =
    sessionMode.equals("agent", ignoreCase = true)

internal fun Session.isAgentCharacterSession(): Boolean =
    isAgentSession() && companionMode == true

internal fun Session.isCharacterSession(): Boolean =
    sessionMode.isNullOrBlank() || sessionMode.equals("character", ignoreCase = true)

private val GENERIC_SENDER_NAMES = setOf("AI", "Agent", "群聊")

/**
 * 一次性把会话和角色列表合并成轻量展示模型。
 *
 * 角色表先建立索引，因此即使群聊包含多个角色，也不会在每个可见行里反复线性查找。
 */
fun buildSessionListRows(
    sessions: List<Session>,
    characters: List<CharacterPreset>,
    portraitUrlResolver: (String) -> String? = { it }
): List<SessionListRow> {
    val characterById = HashMap<String, CharacterPreset>(characters.size)
    characters.forEach { character ->
        character.id
            ?.takeIf { it.isNotBlank() }
            ?.let { characterById.putIfAbsent(it, character) }
    }

    return sessions.mapIndexed { index, session ->
        val relatedCharacterIds = LinkedHashSet<String>()
        session.characterId
            ?.takeIf { it.isNotBlank() }
            ?.let(relatedCharacterIds::add)
        session.characterIds.orEmpty().forEach { characterId ->
            if (characterId.isNotBlank()) relatedCharacterIds.add(characterId)
        }

        val fallbackCharacterName = relatedCharacterIds
            .joinToString("、") { characterId ->
                characterById[characterId]?.displayName ?: characterId
            }
            .takeIf { it.isNotBlank() }
        val senderCharacterName = session.senderName?.takeIf {
            it.isNotBlank() && it !in GENERIC_SENDER_NAMES
        }
        val isAgentSession = session.isAgentSession()
        val inheritCharacter = isAgentSession && session.inheritCharacter == true
        val isGroupSession =
            session.sessionMode == "group" || !session.characterIds.isNullOrEmpty()
        val characterLabel = when {
            isAgentSession && !inheritCharacter && !session.isAgentCharacterSession() -> null
            isGroupSession -> fallbackCharacterName
            else -> session.characterName?.takeIf { it.isNotBlank() }
                ?: senderCharacterName
                ?: fallbackCharacterName?.takeIf { it != "未命名角色" }
        }

        val fallbackCharacter = session.characterId?.let(characterById::get)
        val rawPortraitUrl = session.portraitUrl
            ?.takeIf { it.isNotBlank() }
            ?: fallbackCharacter?.avatarUrl?.takeIf { it.isNotBlank() }
        val sessionId = session.id?.takeIf { it.isNotBlank() }
        val modeLabel = when {
            session.isAgentCharacterSession() -> SessionModeLabel.COMPANION
            isAgentSession -> SessionModeLabel.AGENT
            isGroupSession -> SessionModeLabel.GROUP
            else -> SessionModeLabel.CHARACTER
        }

        SessionListRow(
            key = sessionId
                ?: "session:${session.createdAt.orEmpty()}:${session.name.orEmpty()}:$index",
            id = sessionId,
            name = session.name.orEmpty(),
            displayName = session.displayName,
            listTitleOverride = session.listTitleOverride,
            portraitUrl = rawPortraitUrl?.let(portraitUrlResolver),
            characterLabel = characterLabel,
            updatedAt = formatSessionListDate(session.updatedAt),
            lastMessage = session.lastMessage?.takeIf { it.isNotBlank() },
            messageCount = session.messageCount,
            tags = session.tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            pinned = session.pinned == true,
            favorite = session.favorite == true,
            archived = session.archived == true,
            isAgentSession = isAgentSession,
            inheritCharacter = inheritCharacter,
            isGroupSession = isGroupSession,
            modeLabel = modeLabel,
            characterId = session.characterId?.takeIf {
                it.isNotBlank() && (modeLabel == SessionModeLabel.CHARACTER || modeLabel == SessionModeLabel.COMPANION)
            },
            isTemporary = com.nekobot.app.data.local.TemporarySession.isTemporary(sessionId)
        )
    }
}
