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
    val pinned: Boolean = false,
    val archived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "message_count") val messageCount: Int = 0,
    @ColumnInfo(name = "plot_mode") val plotMode: Boolean = false,
    @ColumnInfo(name = "plot_realtime_sync") val plotRealTimeSync: Boolean = false,
    /** 剧情选项风格（回复风格）文本 */
    @ColumnInfo(name = "plot_choice_style") val plotChoiceStyle: String? = null,
    @ColumnInfo(name = "auto_state_interval") val autoStateInterval: Int = 2,
    /** 禁用的提示词注入项 key 列表，逗号分隔 */
    @ColumnInfo(name = "disabled_prompt_keys") val disabledPromptKeys: String? = null,
    /** 自定义提示词列表，JSON 数组字符串，每项含 order/title/content */
    @ColumnInfo(name = "custom_prompts") val customPrompts: String? = null,
    /** 运行时提示词注入栈调试信息（JSON 字符串），每次对话后更新 */
    @ColumnInfo(name = "prompt_stack_debug") val promptStackDebug: String? = null,
    /** 运行时合成后的完整系统提示词（每次对话后更新，只读展示） */
    @ColumnInfo(name = "composed_system_prompt") val composedSystemPrompt: String? = null,
    /** 是否公开分享 */
    @ColumnInfo(name = "is_public") val isPublic: Boolean = false,
    /** 主动聊天配置 JSON 字符串 {"enabled":bool,"interval_minutes":int} */
    @ColumnInfo(name = "proactive_chat") val proactiveChat: String? = null,
    /** TTS 配置 JSON 字符串 {"enabled":bool,"model_id":str,"voice":str} */
    @ColumnInfo(name = "tts_config") val ttsConfig: String? = null,
    /** 公开分享配置 JSON 字符串 {expires_days,password,include_character,include_user_messages,message_start,message_end} */
    @ColumnInfo(name = "share_config") val shareConfig: String? = null,
    /** 压缩上下文时产生的归档会话 ID（用于"提取归档 N 轮"功能） */
    @ColumnInfo(name = "archive_session_id") val archiveSessionId: String? = null,
    /** 会话模式：character（默认）/ agent / group；用于 agent 模式进度卡片显示等场景 */
    @ColumnInfo(name = "session_mode") val sessionMode: String? = "character"
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
    @ColumnInfo(name = "created_at") val createdAt: String,
    /** 该用户消息关联的进度卡片 JSON（ThinkingCard 列表序列化），agent 模式持久化 */
    @ColumnInfo(name = "thinking_cards") val thinkingCards: String? = null
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
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "token_limit_daily") val tokenLimitDaily: Long = 0,
    @ColumnInfo(name = "token_limit_weekly") val tokenLimitWeekly: Long = 0,
    @ColumnInfo(name = "failover_timeout") val failoverTimeout: Int = 0,
    @ColumnInfo(name = "input_price") val inputPrice: Double? = null,
    @ColumnInfo(name = "output_price") val outputPrice: Double? = null
)

/**
 * 本地故障转移健康状态：追踪每个模型的连续失败次数、冷却期等。
 * 由 [com.nekobot.app.data.local.ai.FailoverCoordinator] 读写。
 */
@Entity(tableName = "local_failover_health")
data class LocalFailoverHealthEntity(
    @PrimaryKey @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int = 0,
    @ColumnInfo(name = "last_failure_code") val lastFailureCode: Int = 0,
    @ColumnInfo(name = "last_failure_at_ms") val lastFailureAtMs: Long = 0,
    @ColumnInfo(name = "cooldown_until_ms") val cooldownUntilMs: Long = 0,
    @ColumnInfo(name = "daily_failures") val dailyFailures: Int = 0,
    @ColumnInfo(name = "daily_failures_date") val dailyFailuresDate: String = ""
)

// ==================== 角色运行时存储 (Stage 4) ====================

/**
 * 角色运行时状态。每个 (character_id, scope_id) 独立。
 * data_json 存储 [com.nekobot.app.data.local.ai.CharacterState] 的完整 JSON。
 */
@Entity(
    tableName = "local_character_states",
    indices = [Index(value = ["character_id", "scope_id"], unique = true)]
)
data class LocalCharacterStateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "data_json") val dataJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * 关系状态。每个 (character_id, target_id) 独立。
 * data_json 存储 [com.nekobot.app.data.local.ai.RelationshipState] 的完整 JSON。
 */
@Entity(
    tableName = "local_relationship_states",
    indices = [Index(value = ["character_id", "target_id"], unique = true)]
)
data class LocalRelationshipStateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "data_json") val dataJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * 角色记忆条目。
 * type: long / short / flash
 * category: user_persona / character_persona / important_event / recent_digest
 */
@Entity(
    tableName = "local_character_memories",
    indices = [
        Index("character_id"),
        Index("target_id"),
        Index(value = ["character_id", "target_id"])
    ]
)
data class LocalCharacterMemoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val type: String,
    val category: String = "",
    val title: String,
    val summary: String,
    val content: String,
    val importance: Int,
    @ColumnInfo(name = "emotion_impact") val emotionImpact: String? = null,   // JSON
    @ColumnInfo(name = "source_turn_id") val sourceTurnId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "expires_at") val expiresAt: String? = null
)

/**
 * 角色状态历史快照。每轮 after_turn 写入一条，记录情绪/精力/关系六维随时间的演变。
 *
 * 与 [LocalCharacterStateEntity]（单行覆盖当前状态）不同，本表按时间追加，
 * 供「状态历程」界面呈现真实演变曲线。
 *
 * triggerType: state_machine（规则状态机）/ auto_state（LLM 评估）
 * qualityScoresJson: 可空，AutoState 产出的质量评分（character_fidelity/immersion/world_consistency/risk）JSON。
 */
@Entity(
    tableName = "local_state_snapshots",
    indices = [
        Index("session_id"),
        Index(value = ["character_id", "target_id"])
    ]
)
data class LocalStateSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "timestamp") val timestamp: String,
    val mood: String,
    @ColumnInfo(name = "mood_intensity") val moodIntensity: Float,
    val energy: Int,
    val affection: Int,
    val trust: Int,
    val familiarity: Int,
    val dependency: Int,
    val security: Int,
    val jealousy: Int,
    @ColumnInfo(name = "quality_scores_json") val qualityScoresJson: String? = null,
    @ColumnInfo(name = "trigger_type") val triggerType: String
)

// ==================== 扩展功能表（v10，本地模式补齐远程同款能力）====================

/** 本地 Hook 配置。actions/conditions/permissions 以 JSON 字符串存储。 */
@Entity(tableName = "local_hooks")
data class LocalHookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val event: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val scope: String = "global",
    val priority: Int = 100,
    @ColumnInfo(name = "actions_json") val actionsJson: String = "[]",
    @ColumnInfo(name = "conditions_json") val conditionsJson: String? = null,
    @ColumnInfo(name = "permissions_json") val permissionsJson: String? = null,
    @ColumnInfo(name = "timeout_ms") val timeoutMs: Int = 3000,
    @ColumnInfo(name = "max_retries") val maxRetries: Int = 0,
    @ColumnInfo(name = "trigger_mode") val triggerMode: String = "always",
    @ColumnInfo(name = "condition_logic") val conditionLogic: String = "and",
    @ColumnInfo(name = "character_id") val characterId: String? = null,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "user_id") val userId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/** 本地任务中心条目。config 为 JSON 字符串。 */
@Entity(tableName = "local_tasks")
data class LocalTaskEntity(
    @PrimaryKey val id: String,
    val kind: String = "custom",
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: String = "manual",
    @ColumnInfo(name = "config_json") val configJson: String? = null,
    @ColumnInfo(name = "target_session_id") val targetSessionId: String? = null,
    val prompt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "last_run") val lastRun: String? = null,
    @ColumnInfo(name = "next_run") val nextRun: String? = null
)

/** 本地工作流。config 为 JSON 字符串（节点+连线）。 */
@Entity(tableName = "local_workflows")
data class LocalWorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: String = "manual",
    @ColumnInfo(name = "config_json") val configJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String
)

/** 本地 Skill 元数据。aliases/parameters 以 JSON 字符串存储。 */
@Entity(tableName = "local_skills")
data class LocalSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "aliases_json") val aliasesJson: String = "[]",
    val enabled: Boolean = true,
    @ColumnInfo(name = "parameters_json") val parametersJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String
)

/** 本地 Tool 配置。parameters/implementation 以 JSON 字符串存储。 */
@Entity(tableName = "local_tools")
data class LocalToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "parameters_json") val parametersJson: String? = null,
    @ColumnInfo(name = "implementation_json") val implementationJson: String? = null,
    val builtin: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String
)

/** 本地 MCP 服务。args/env 以 JSON 字符串存储。 */
@Entity(tableName = "local_mcp_servers")
data class LocalMcpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val transport: String = "streamable-http",
    val description: String? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "auto_connect") val autoConnect: Boolean = false,
    val connected: Boolean = false,
    @ColumnInfo(name = "tool_count") val toolCount: Int = 0,
    val url: String? = null,
    val command: String? = null,
    @ColumnInfo(name = "args_json") val argsJson: String? = null,
    @ColumnInfo(name = "env_json") val envJson: String? = null,
    val builtin: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: String? = null
)

/** 本地 API Key。key 仅在详情时返回。 */
@Entity(tableName = "local_api_keys")
data class LocalApiKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val key: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * 本地消息收藏夹。每条记录对应一个收藏集合（collection）。
 * message_ids_json 为该收藏夹包含的消息 ID 列表（JSON 数组字符串）。
 */
@Entity(
    tableName = "local_message_favorites",
    indices = [Index("session_id")]
)
data class LocalMessageFavoriteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val title: String,
    @ColumnInfo(name = "message_ids_json") val messageIdsJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: String
)
