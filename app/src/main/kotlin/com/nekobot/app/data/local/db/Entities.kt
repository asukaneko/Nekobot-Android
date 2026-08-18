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
    /** 剧情大纲文本（用户导入/粘贴，AI 生成选项时围绕此走向推进） */
    @ColumnInfo(name = "plot_outline") val plotOutline: String? = null,
    /** 本会话用户人设/背景提示词（注入到 PromptStack 的 user.persona 项） */
    @ColumnInfo(name = "user_persona") val userPersona: String? = null,
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
    @ColumnInfo(name = "session_mode", defaultValue = "character") val sessionMode: String = "character",
    /** 群聊会话 ID；本地模式与原仓库一样单独保留 gc_* 标识。 */
    @ColumnInfo(name = "group_id") val groupId: String? = null,
    /** 群聊成员角色 ID，JSON 数组。 */
    @ColumnInfo(name = "character_ids") val characterIds: String? = null,
    /** 群聊配置，JSON 对象（speaker_strategy / round_robin_mode 等）。 */
    @ColumnInfo(name = "group_config") val groupConfig: String? = null,
    /** 上一个发言角色 ID，供轮询策略跨轮次续接。 */
    @ColumnInfo(name = "group_active_speaker") val groupActiveSpeaker: String? = null,
    /** 已完成的群聊用户轮次数。 */
    @ColumnInfo(name = "group_turn_count", defaultValue = "0") val groupTurnCount: Int = 0
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
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    val sender: String? = null,
    val timestamp: String,
    val model: String? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "audio_url") val audioUrl: String? = null,
    /** 聊天 TTS 音频最后一次生成/更新的时间，用于跨设备增量同步冲突处理。 */
    @ColumnInfo(name = "audio_updated_at") val audioUpdatedAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    /** 该用户消息关联的进度卡片 JSON（ThinkingCard 列表序列化），agent 模式持久化 */
    @ColumnInfo(name = "thinking_cards") val thinkingCards: String? = null,
    /** 该助手消息当前轮产生的完整 assistant/tool 调用历史 JSON，供后续轮次恢复 */
    @ColumnInfo(name = "tool_call_history") val toolCallHistory: String? = null,
    /** 消息来源：普通聊天为空；后台任务/主动聊天分别使用 task_center / proactive_chat。 */
    val source: String? = null,
    /** RAG 引用来源 JSON（KnowledgeSearchResult 列表序列化） */
    @ColumnInfo(name = "knowledge_citations") val knowledgeCitations: String? = null,
    /** 路由决策日志 ID，关联 routing_decision_logs 表 */
    @ColumnInfo(name = "routing_decision_id") val routingDecisionId: String? = null
)

/**
 * 由某条聊天消息触发的 AI 生图任务。
 *
 * 图片文件存放在应用私有的 portraits 目录中，以便沿用角色立绘既有的数据导出、
 * 数据库档案迁移和 WebDAV 备份能力。该表不建立消息外键：远程模式的消息并不在
 * 本地 local_messages 表内，但同样需要在当前设备保存并展示生成结果。
 */
@Entity(
    tableName = "local_message_images",
    indices = [Index("session_id"), Index("message_id")]
)
data class LocalMessageImageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    val prompt: String,
    @ColumnInfo(name = "reference_image_path") val referenceImagePath: String? = null,
    @ColumnInfo(name = "reference_image_mime_type") val referenceImageMimeType: String? = null,
    /** queued / running / completed / failed */
    val status: String,
    @ColumnInfo(name = "file_name") val fileName: String? = null,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "model_name") val modelName: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/**
 * 本地 Agent 单会话运行检查点。
 *
 * 只保留尚未正常完成的一轮任务；完整工具批次结束后更新 checkpointHistory，
 * Android 进程被回收时可从最后一个协议安全边界继续，而不会重放已经确认完成的工具批次。
 */
@Entity(
    tableName = "local_agent_runs",
    indices = [Index("user_message_id")],
    foreignKeys = [
        ForeignKey(
            entity = LocalSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LocalAgentRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "user_message_id") val userMessageId: String,
    val prompt: String,
    @ColumnInfo(name = "attachments_json") val attachmentsJson: String?,
    val status: String,
    val stage: String,
    @ColumnInfo(name = "checkpoint_history") val checkpointHistory: String?,
    @ColumnInfo(name = "completed_tool_calls") val completedToolCalls: Int,
    @ColumnInfo(name = "last_tool_name") val lastToolName: String?,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
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
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
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
    @ColumnInfo(name = "display_index") val displayIndex: Int = 0,
    /** 允许的召回来源 JSON 数组；空值表示使用全部来源。 */
    @ColumnInfo(name = "trigger_sources_json") val triggerSourcesJson: String? = null,
    /** 动态状态条件 JSON 对象，例如 {"affection":[">=60"],"location":["白塔"]}。 */
    @ColumnInfo(name = "state_triggers_json") val stateTriggersJson: String? = null,
    /** any：任一条件命中；all：全部条件命中。 */
    @ColumnInfo(name = "match_mode") val matchMode: String = "any",
    /** lore/location/relationship/event/rule 等，用于同分时排序。 */
    @ColumnInfo(name = "entry_type") val entryType: String = "lore"
)

/**
 * 本地 AI 模型配置。
 *
 * protocol 取值："openai_chat" / "openai_responses" / "anthropic_messages" / "gemini_native"。
 * active 表示当前是否为激活模型（本地模式仅使用 active=true 的一个）。
 */
@Entity(
    tableName = "local_ai_models",
    indices = [Index(value = ["oauth_account_id"])]
)
data class LocalAiModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,                      // openai_chat / openai_responses / anthropic_messages / gemini_native
    val provider: String? = null,
    @ColumnInfo(name = "api_key") val apiKey: String,
    @ColumnInfo(name = "proxy_url") val proxyUrl: String = "",
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
    val purpose: String = "chat",
    val priority: Int = 0,
    val active: Boolean = false,
    val temperature: Float? = null,
    @ColumnInfo(name = "max_tokens") val maxTokens: Int? = null,
    @ColumnInfo(name = "max_context_length") val maxContextLength: Int? = null,
    @ColumnInfo(name = "top_p") val topP: Float? = null,
    @ColumnInfo(name = "append_base_url_path") val appendBaseUrlPath: Boolean = true,
    @ColumnInfo(name = "supports_tools") val supportsTools: Boolean = true,
    @ColumnInfo(name = "supports_reasoning") val supportsReasoning: Boolean = true,
    @ColumnInfo(name = "supports_stream") val supportsStream: Boolean = true,
    @ColumnInfo(name = "tts_provider") val ttsProvider: String = "openai",
    @ColumnInfo(name = "tts_url") val ttsUrl: String = "",
    @ColumnInfo(name = "tts_model") val ttsModel: String = "",
    @ColumnInfo(name = "tts_voice") val ttsVoice: String = "default",
    @ColumnInfo(name = "tts_speed") val ttsSpeed: Float = 1.0f,
    @ColumnInfo(name = "tts_pitch") val ttsPitch: Float = 1.0f,
    @ColumnInfo(name = "tts_volume") val ttsVolume: Float = 1.0f,
    @ColumnInfo(name = "tts_format") val ttsFormat: String = "mp3",
    @ColumnInfo(name = "tts_upload_url") val ttsUploadUrl: String = "",
    @ColumnInfo(name = "tts_headers") val ttsHeaders: String = "",
    @ColumnInfo(name = "tts_body_template") val ttsBodyTemplate: String = "",
    @ColumnInfo(name = "tts_resource_id") val ttsResourceId: String = "",
    @ColumnInfo(name = "tts_ref_audio") val ttsRefAudio: String = "",
    @ColumnInfo(name = "tts_user") val ttsUser: String = "",
    val language: String = "zh",
    @ColumnInfo(name = "stt_provider") val sttProvider: String = "",
    @ColumnInfo(name = "stt_url") val sttUrl: String = "",
    @ColumnInfo(name = "stt_model") val sttModel: String = "",
    @ColumnInfo(name = "stt_headers") val sttHeaders: String = "",
    val dimensions: Int = 1536,
    val size: String = "1024x1024",
    @ColumnInfo(name = "prompt_template") val promptTemplate: String = "",
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "token_limit_daily") val tokenLimitDaily: Long = 0,
    @ColumnInfo(name = "token_limit_weekly") val tokenLimitWeekly: Long = 0,
    @ColumnInfo(name = "failover_timeout") val failoverTimeout: Int = 0,
    @ColumnInfo(name = "input_price") val inputPrice: Double? = null,
    @ColumnInfo(name = "output_price") val outputPrice: Double? = null,
    /** OAuth 模型只保存账号引用；真实 access/refresh token 由 Keystore 加密账号仓库管理。 */
    @ColumnInfo(name = "oauth_account_id") val oauthAccountId: String? = null
)

/**
 * 本地 OAuth 提供商账号。
 *
 * encryptedCredentials 只包含 Android Keystore AES-GCM 密文，不保存明文 token。
 * metadataJson 仅保存非敏感展示信息（邮箱、区域、模型缓存等）。
 */
@Entity(
    tableName = "local_oauth_accounts",
    indices = [Index(value = ["provider"])]
)
data class LocalOAuthAccountEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val label: String,
    @ColumnInfo(name = "encrypted_credentials") val encryptedCredentials: String,
    @ColumnInfo(name = "metadata_json") val metadataJson: String = "{}",
    val status: String = "connected",
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
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
        Index(value = ["character_id", "target_id"]),
        Index(value = ["character_id", "category"]),
        Index(value = ["character_id", "target_id", "category"]),
        Index("memory_path")
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
    @ColumnInfo(name = "expires_at") val expiresAt: String? = null,
    /**
     * 逻辑路径（对齐原仓库 memoryfs 概念）：
     * - user_persona:     characters/{charId}/users/{targetId}/user_persona.md
     * - character_persona:characters/{charId}/users/{targetId}/character_persona.md
     * - important_event:  characters/{charId}/events/{conversationId}.md
     * - timeline:         characters/{charId}/timeline.md
     * - life_sim:         characters/{charId}/life_sim/{conversationId}.md
     * - recent_digest:    characters/{charId}/users/{targetId}/recent_digest.md
     *
     * 同 path 的多条记忆在 append/replace 时按 path 聚合。
     * 旧数据（无 path）保留兼容，按 category + targetId + conversationId 推断。
     */
    @ColumnInfo(name = "memory_path") val memoryPath: String? = null,
    /** 同 path 的写入版本号，每次 append/replace 自增 */
    @ColumnInfo(name = "version") val version: Int = 1,
    /** 最近一次写入时间（ISO），用于排序最新 N 条 */
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null,
    /** 该记忆所属会话（important_event/life_sim 按 conversationId 隔离） */
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null
)

/**
 * 角色状态历史快照。每轮 after_turn 写入一条，记录情绪/精力/关系六维随时间的演变。
 *
 * 与 [LocalCharacterStateEntity]（单行覆盖当前状态）不同，本表按时间追加，
 * 供「状态历程」界面呈现真实演变曲线。
 *
 * triggerType: state_machine（规则状态机）/ auto_state（LLM 评估）
 * qualityScoresJson: 可空，AutoState 产出的质量评分（character_fidelity/immersion/world_consistency/risk）JSON。
 * userMessage/assistantMessage: 可空，本轮对话原文，供「状态历程」底部对话回放展示。
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
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    /** 本轮用户消息原文，供对话回放展示（v17 新增）。 */
    @ColumnInfo(name = "user_message") val userMessage: String? = null,
    /** 本轮 AI 回复原文，供对话回放展示（v17 新增）。 */
    @ColumnInfo(name = "assistant_message") val assistantMessage: String? = null
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

/**
 * 本地 Hook 执行日志。每次 hook 触发（无论成功/失败/部分成功）追加一条。
 *
 * 字段对齐 [com.nekobot.app.data.model.HookExecutionLog]，供 HooksScreen 的"查看日志"功能展示。
 * 通过 hook_id 索引加速按 hook 查询；通过 created_at 索引加速时间排序。
 */
@Entity(
    tableName = "local_hook_logs",
    indices = [Index("hook_id"), Index("created_at")]
)
data class LocalHookLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "hook_id") val hookId: String,
    @ColumnInfo(name = "event_id") val eventId: String? = null,
    val status: String,                      // success / partial / failed
    @ColumnInfo(name = "actions_executed") val actionsExecuted: Int = 0,
    val error: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Int = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "event_type") val eventType: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String
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
    @ColumnInfo(name = "next_run") val nextRun: String? = null,
    val status: String = "idle",
    @ColumnInfo(name = "last_error") val lastError: String? = null
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
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    @ColumnInfo(name = "last_run") val lastRun: String? = null,
    @ColumnInfo(name = "next_run") val nextRun: String? = null,
    val status: String = "idle",
    @ColumnInfo(name = "last_error") val lastError: String? = null
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

/** 本地 MCP 服务。headers/args/env 以 JSON 字符串存储。 */
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
    @ColumnInfo(name = "headers_json") val headersJson: String? = null,
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

/** 本地知识库文档。正文保留原文，索引状态由 chunk 表重建。 */
@Entity(tableName = "local_knowledge_documents")
data class LocalKnowledgeDocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val source: String? = null,
    @ColumnInfo(name = "tags_json") val tagsJson: String = "[]",
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    val indexed: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)

/** 本地知识库切片。embedding_json 为空时仍可使用本地词法检索。 */
@Entity(
    tableName = "local_knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = LocalKnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("document_id"), Index(value = ["document_id", "chunk_index"], unique = true)]
)
data class LocalKnowledgeChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
    @ColumnInfo(name = "embedding_json") val embeddingJson: String? = null,
    /** 切片在原文中的字符起始偏移量 */
    @ColumnInfo(name = "char_offset") val charOffset: Int = 0,
    /** 切片在原文中的字符结束偏移量 */
    @ColumnInfo(name = "char_end") val charEnd: Int = 0
)

/** 路由决策日志，记录每次模型路由的分项得分、选择原因、费用和延迟。 */
@Entity(tableName = "routing_decision_logs", indices = [Index("session_id"), Index("created_at"), Index("selected_model_id")])
data class RoutingDecisionLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    /** 决策 JSON（RoutingDecision 序列化） */
    @ColumnInfo(name = "decision_json") val decisionJson: String,
    @ColumnInfo(name = "selected_model_id") val selectedModelId: String,
    @ColumnInfo(name = "selected_model_name") val selectedModelName: String,
    @ColumnInfo(name = "estimated_cost_usd") val estimatedCostUsd: Double,
    @ColumnInfo(name = "actual_cost_usd") val actualCostUsd: Double? = null,
    @ColumnInfo(name = "actual_duration_ms") val actualDurationMs: Long? = null,
    @ColumnInfo(name = "actual_ttft_ms") val actualTtftMs: Long? = null,
    val success: Boolean = false,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    /** 用户质量评分（-1=差, 0=未评, 1=好） */
    @ColumnInfo(name = "quality_score") val qualityScore: Int = 0,
    @ColumnInfo(name = "is_ab_test") val isAbTest: Boolean = false,
    @ColumnInfo(name = "ab_test_group") val abTestGroup: String? = null
)
