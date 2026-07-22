package com.nekobot.app.ui.screens.chat

import androidx.compose.runtime.Immutable
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Message

@Immutable
internal data class GroupMessageIdentity(
    val name: String? = null,
    val portraitUrl: String? = null
)

/**
 * 解析群聊角色消息的展示身份。
 *
 * 服务器消息可直接携带 name/avatar；本地消息只持久化 sender，需按角色 ID 或名称回查角色卡。
 */
internal fun resolveGroupMessageIdentity(
    message: Message,
    characters: List<CharacterPreset>
): GroupMessageIdentity {
    val keys = listOf(message.name, message.sender)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val character = characters.firstOrNull { candidate ->
        keys.any { key ->
            key.equals(candidate.id, ignoreCase = true) ||
                key.equals(candidate.displayName, ignoreCase = true)
        }
    }
    val explicitName = keys.firstOrNull { it.lowercase() !in GENERIC_ASSISTANT_NAMES }
    return GroupMessageIdentity(
        name = character?.displayName ?: explicitName,
        portraitUrl = message.avatar?.trim()?.takeIf(String::isNotEmpty) ?: character?.avatarUrl
    )
}

private val GENERIC_ASSISTANT_NAMES = setOf("assistant", "ai", "bot")
