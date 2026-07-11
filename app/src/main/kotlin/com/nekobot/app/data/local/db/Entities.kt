package com.nekobot.app.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地会话。对应远程 [com.nekobot.app.data.model.Session]，仅保留本地模式所需字段。
 *
 * id 由 App 端用 UUID 生成；character_id 关联本地角色卡（可为空，表示无角色）。
 */
@Entity(tableName = "local_sessions")
data class LocalSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "character_id") val characterId: String? = null,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String? = null,
    @ColumnInfo(name = "first_message") val firstMessage: String? = null,
    val scenario: String? = null,
    @ColumnInfo(name = "sender_name") val senderName: String? = null,
    @ColumnInfo(name = "sender_avatar") val senderAvatar: String? = null,
    @ColumnInfo(name = "character_name") val characterName: String? = null,
    @ColumnInfo(name = "character_avatar") val characterAvatar: String? = null,
    val portrait: String? = null,
    val tags: String? = null,                  // 逗号分隔
    val favorite: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
    @ColumnInfo(name = "plot_mode") val plotMode: Boolean = false,
    /** 自定义提示词列表，JSON 数组字符串，每项含 order/title/content */
    @ColumnInfo(name = "custom_prompts") val customPrompts: String? = null
)

/**
 * 本地消息。一条消息 = 一行；session_id 建索引。
 */
@Entity(
    tableName = "local_messages",
    indices = [Index("session_id")],
    foreignKeys = [
        ForeignKey(
            entity = LocalSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LocalMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,                          // user / assistant / system
    val content: String,
    val sender: String? = null,
    val timestamp: String,
    val model: String? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: String
)

/**
 * 本地角色卡。字段对齐后端 CharacterPreset 完整字段。
 *
 * tags / alternateGreetings / rules / state 以 JSON 字符串存储，由 Dao 层转换。
 */
@Entity(tableName = "local_characters")
data class LocalCharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val avatar: String? = null,
    val portrait: String? = null,
    val tags: String? = null,                  // JSON 数组字符串
    @ColumnInfo(name = "basic_info") val basicInfo: String? = null,
    val personality: String? = null,
    val scenario: String? = null,
    @ColumnInfo(name = "first_message") val firstMessage: String? = null,
    @ColumnInfo(name = "alternate_greetings") val alternateGreetings: String? = null, // JSON 数组
    @ColumnInfo(name = "example_dialogues") val exampleDialogues: String? = null,
    @ColumnInfo(name = "response_format") val responseFormat: String? = null,
    val rules: String? = null,                 // JSON 数组
    val state: String? = null,                 // JSON 对象
    @ColumnInfo(name = "system_prompt") val systemPrompt: String? = null,
    val greeting: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * 本地世界书。
 */
@Entity(tableName = "local_world_books")
data class LocalWorldBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "character_id") val characterId: String? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * 本地世界书条目。book_id 建索引，跟随世界书级联删除。
 */
@Entity(
    tableName = "local_world_book_entries",
    indices = [Index("book_id")],
    foreignKeys = [
        ForeignKey(
            entity = LocalWorldBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LocalWorldBookEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val keys: String? = null,                  // JSON 数组
    val content: String? = null,
    val comment: String? = null,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    @ColumnInfo(name = "insertion_order") val insertionOrder: Int = 0,
    val priority: Int = 0,
    val position: String? = null,
    @ColumnInfo(name = "case_sensitive") val caseSensitive: Boolean = false,
    @ColumnInfo(name = "display_index") val displayIndex: Int = 0
)

/**
 * 本地 AI 模型配置。
 *
 * protocol 取值："openai_chat" / "anthropic_messages"。
 * active 表示当前是否为激活模型（本地模式仅使用 active=true 的一个）。
 */
@Entity(tableName = "local_ai_models")
data class LocalAiModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,                      // openai_chat / anthropic_messages
    val provider: String? = null,
    @ColumnInfo(name = "api_key") val apiKey: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
    val purpose: String = "chat",
    val priority: Int = 0,
    val active: Boolean = false,
    val temperature: Float? = null,
    @ColumnInfo(name = "max_tokens") val maxTokens: Int? = null,
    @ColumnInfo(name = "top_p") val topP: Float? = null,
    @ColumnInfo(name = "append_base_url_path") val appendBaseUrlPath: Boolean = true,
    @ColumnInfo(name = "supports_stream") val supportsStream: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: String
)
