package com.nekobot.app.data.local.db

import androidx.room.ColumnInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.model.Session

/** 首页只读取展示与统计字段，避免把提示词和调试快照装入列表。 */
data class SessionSummaryRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "character_id") val characterId: String?,
    @ColumnInfo(name = "character_ids") val characterIds: String?,
    @ColumnInfo(name = "character_name") val characterName: String?,
    @ColumnInfo(name = "character_avatar") val characterAvatar: String?,
    @ColumnInfo(name = "sender_name") val senderName: String?,
    @ColumnInfo(name = "sender_avatar") val senderAvatar: String?,
    val portrait: String?,
    val tags: String?,
    val favorite: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "last_message") val lastMessage: String?,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "is_public") val isPublic: Boolean,
    @ColumnInfo(name = "session_mode") val sessionMode: String,
    @ColumnInfo(name = "inherit_character") val inheritCharacter: Boolean,
    @ColumnInfo(name = "companion_mode") val companionMode: Boolean,
    @ColumnInfo(name = "list_title_override") val listTitleOverride: String?
) {
    fun toSession(): Session = Session(
        id = id, name = name, type = "local", characterId = characterId,
        characterIds = characterIds?.let {
            runCatching { gson.fromJson<List<String>>(it, idsType) }.getOrNull()
        },
        characterName = characterName, characterAvatar = characterAvatar,
        senderName = senderName, senderAvatar = senderAvatar, portrait = portrait,
        tags = tags?.split(',')?.filter(String::isNotEmpty),
        favorite = favorite, pinned = pinned, archived = archived,
        createdAt = createdAt, updatedAt = updatedAt, lastMessage = lastMessage,
        messageCount = messageCount, isPublic = isPublic, sessionMode = sessionMode,
        inheritCharacter = inheritCharacter, companionMode = companionMode,
        listTitleOverride = listTitleOverride
    )

    private companion object {
        val gson = Gson()
        val idsType = object : TypeToken<List<String>>() {}.type
    }
}
