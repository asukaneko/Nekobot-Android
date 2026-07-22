package com.nekobot.app.ui.screens.sessions

import androidx.compose.runtime.Immutable
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Session

@Immutable
data class SessionOverview(
    val total: Int = 0,
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
    val pinned: Boolean,
    val favorite: Boolean,
    val archived: Boolean,
    /** 群聊会话：列表项无立绘时回退到群聊图标而非默认聊天图标。 */
    val isGroupSession: Boolean = false
)

val QUICK_SESSION_FILTERS = listOf(
    SessionFilter.ALL,
    SessionFilter.PINNED,
    SessionFilter.FAVORITE,
    SessionFilter.ARCHIVED
)

fun buildSessionOverview(sessions: List<Session>): SessionOverview {
    var total = 0
    var pinned = 0
    var favorite = 0
    var archived = 0
    sessions.forEach { session ->
        if (session.isArchive == true) return@forEach
        total++
        if (session.pinned == true) pinned++
        if (session.favorite == true) favorite++
        if (session.archived == true) archived++
    }
    return SessionOverview(
        total = total,
        pinned = pinned,
        favorite = favorite,
        archived = archived
    )
}

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
        val isAgentSession = session.sessionMode == "agent"
        val isGroupSession =
            session.sessionMode == "group" || !session.characterIds.isNullOrEmpty()
        val characterLabel = when {
            isAgentSession -> null
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

        SessionListRow(
            key = sessionId
                ?: "session:${session.createdAt.orEmpty()}:${session.name.orEmpty()}:$index",
            id = sessionId,
            name = session.name.orEmpty(),
            displayName = session.displayName,
            portraitUrl = rawPortraitUrl?.let(portraitUrlResolver),
            characterLabel = characterLabel,
            updatedAt = session.updatedAt?.takeIf { it.isNotBlank() },
            lastMessage = session.lastMessage?.takeIf { it.isNotBlank() },
            messageCount = session.messageCount,
            pinned = session.pinned == true,
            favorite = session.favorite == true,
            archived = session.archived == true,
            isGroupSession = isGroupSession
        )
    }
}
