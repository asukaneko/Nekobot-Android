package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalHookEntity
import com.nekobot.app.data.local.db.LocalSkillEntity
import com.nekobot.app.data.local.db.LocalTaskEntity
import com.nekobot.app.data.local.db.LocalWorkflowEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 本地数据库操作工具集 IDs。
 *
 * 这些工具让本地 Agent 模式下的 AI 能够直接增删改查本地数据库中的角色卡、世界书、
 * Token 用量、角色记忆 / 状态历程、Hook、工作流任务、Skill、AI 模型等系统性资源。
 *
 * 命名前缀统一为 `db_`，与 [localSkillToolIds] 的 `skill_` 前缀风格保持一致。
 */
internal val localDbToolIds: Set<String> = setOf(
    // 角色卡
    "db_list_characters", "db_get_character", "db_create_character", "db_update_character", "db_delete_character",
    // 世界书
    "db_list_world_books", "db_get_world_book", "db_create_world_book", "db_update_world_book", "db_delete_world_book",
    "db_upsert_world_book_entry", "db_delete_world_book_entry",
    // Token 用量
    "db_token_stats", "db_token_rankings", "db_session_token_usage",
    // 角色记忆 / 状态历程
    "db_list_memories", "db_save_memory", "db_delete_memory", "db_list_state_history", "db_get_latest_state",
    // Hook
    "db_list_hooks", "db_create_hook", "db_update_hook", "db_delete_hook", "db_toggle_hook",
    // 工作流任务
    "db_list_workflows", "db_create_workflow", "db_update_workflow", "db_delete_workflow",
    "db_list_tasks", "db_create_task", "db_update_task", "db_delete_task",
    // Skill
    "db_list_skills", "db_create_skill", "db_update_skill", "db_delete_skill", "db_toggle_skill",
    // AI 模型
    "db_list_ai_models", "db_get_ai_model", "db_create_ai_model", "db_update_ai_model",
    "db_delete_ai_model", "db_set_active_model", "db_get_ai_config", "db_update_ai_config"
)

/** 需要用户授权确认的高风险删除工具。 */
private val localDbHighRiskToolIds: Set<String> = setOf(
    "db_delete_character", "db_delete_world_book", "db_delete_world_book_entry",
    "db_delete_memory", "db_delete_hook", "db_delete_workflow", "db_delete_task",
    "db_delete_skill", "db_delete_ai_model"
)

/** 将本地数据库工具集转换为 OpenAI function-calling 定义。 */
internal fun buildLocalDbToolDefinitions(): List<Map<String, Any>> {
    fun params(properties: Map<String, Map<String, Any>>, required: List<String> = emptyList()): Map<String, Any> =
        buildMap {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", required)
        }

    fun definition(name: String, description: String, parameters: Map<String, Any>): Map<String, Any> =
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters
            )
        )

    fun str(desc: String) = mapOf("type" to "string", "description" to desc)
    fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
    fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
    fun num(desc: String) = mapOf("type" to "number", "description" to desc)
    fun arr(desc: String) = mapOf("type" to "array", "description" to desc)
    fun obj(desc: String) = mapOf("type" to "object", "description" to desc)

    return listOf(
        // ===== 角色卡 =====
        definition("db_list_characters", "列出所有本地角色卡（仅返回摘要字段）。", params(emptyMap())),
        definition("db_get_character", "查看指定角色卡完整字段。", params(mapOf("character_id" to str("角色卡 ID")), listOf("character_id"))),
        definition(
            "db_create_character",
            "创建一张新的本地角色卡。name 必填；其它字段可选。",
            params(
                mapOf(
                    "name" to str("角色名（必填）"),
                    "description" to str("角色简介"),
                    "personality" to str("性格描述"),
                    "scenario" to str("场景设定"),
                    "first_message" to str("开场白"),
                    "system_prompt" to str("系统提示词"),
                    "greeting" to str("问候语"),
                    "basic_info" to str("基本信息（身高/年龄/职业等，多行文本）"),
                    "example_dialogues" to str("对话示例"),
                    "response_format" to str("回复格式约束"),
                    "tags" to arr("标签数组（字符串）"),
                    "alternate_greetings" to arr("备选开场白数组（字符串）"),
                    "rules" to arr("规则数组（字符串）")
                ),
                listOf("name")
            )
        ),
        definition(
            "db_update_character",
            "修改指定角色卡的可变字段。仅传入需要修改的字段。",
            params(
                mapOf(
                    "character_id" to str("角色卡 ID（必填）"),
                    "name" to str("角色名"),
                    "description" to str("角色简介"),
                    "personality" to str("性格描述"),
                    "scenario" to str("场景设定"),
                    "first_message" to str("开场白"),
                    "system_prompt" to str("系统提示词"),
                    "greeting" to str("问候语"),
                    "basic_info" to str("基本信息"),
                    "example_dialogues" to str("对话示例"),
                    "response_format" to str("回复格式约束"),
                    "tags" to arr("标签数组"),
                    "alternate_greetings" to arr("备选开场白数组"),
                    "rules" to arr("规则数组")
                ),
                listOf("character_id")
            )
        ),
        definition("db_delete_character", "删除指定角色卡（高风险，需用户确认）。", params(mapOf("character_id" to str("角色卡 ID")), listOf("character_id"))),

        // ===== 世界书 =====
        definition("db_list_world_books", "列出所有本地世界书。", params(emptyMap())),
        definition("db_get_world_book", "查看指定世界书及其所有条目。", params(mapOf("book_id" to str("世界书 ID")), listOf("book_id"))),
        definition(
            "db_create_world_book",
            "创建一本新的本地世界书。",
            params(
                mapOf(
                    "name" to str("世界书名称（必填）"),
                    "description" to str("世界书描述"),
                    "character_id" to str("绑定的角色卡 ID（可选）"),
                    "enabled" to bool("是否启用，默认 true")
                ),
                listOf("name")
            )
        ),
        definition(
            "db_update_world_book",
            "修改指定世界书的可变字段。",
            params(
                mapOf(
                    "book_id" to str("世界书 ID（必填）"),
                    "name" to str("世界书名称"),
                    "description" to str("世界书描述"),
                    "character_id" to str("绑定的角色卡 ID"),
                    "enabled" to bool("是否启用")
                ),
                listOf("book_id")
            )
        ),
        definition("db_delete_world_book", "删除指定世界书及其所有条目（高风险，需用户确认）。", params(mapOf("book_id" to str("世界书 ID")), listOf("book_id"))),
        definition(
            "db_upsert_world_book_entry",
            "新增或修改世界书条目。传入 entry_id 则修改，否则新增。",
            params(
                mapOf(
                    "book_id" to str("所属世界书 ID（必填）"),
                    "entry_id" to str("条目 ID（修改时必填，新增时留空）"),
                    "keys" to arr("触发关键词数组（字符串）"),
                    "content" to str("条目内容"),
                    "comment" to str("备注"),
                    "enabled" to bool("是否启用"),
                    "constant" to bool("是否常驻（始终注入）"),
                    "selective" to bool("是否选择性触发"),
                    "priority" to int("优先级，默认 0"),
                    "insertion_order" to int("插入顺序，默认 0"),
                    "position" to str("注入位置"),
                    "case_sensitive" to bool("是否区分大小写"),
                    "display_index" to int("显示索引，默认 0")
                ),
                listOf("book_id")
            )
        ),
        definition("db_delete_world_book_entry", "删除指定世界书条目（高风险，需用户确认）。", params(mapOf("entry_id" to str("条目 ID")), listOf("entry_id"))),

        // ===== Token 用量 =====
        definition("db_token_stats", "查看本地 Token 用量统计（按日/月/总量）。", params(emptyMap())),
        definition("db_token_rankings", "查看本地 Token 用量排行（按会话/模型/用途）。", params(emptyMap())),
        definition("db_session_token_usage", "查看指定会话累计 Token 用量。", params(mapOf("session_id" to str("会话 ID（可空，默认当前会话）")))),

        // ===== 角色记忆 / 状态历程 =====
        definition(
            "db_list_memories",
            "列出本地角色记忆。character_id 可空（空则返回全部）。",
            params(mapOf("character_id" to str("角色卡 ID（可空）")))
        ),
        definition(
            "db_save_memory",
            "新增或更新本地角色记忆。id 传入则更新，否则新增。",
            params(
                mapOf(
                    "id" to str("记忆 ID（更新时传入，新增时留空）"),
                    "title" to str("标题（必填）"),
                    "content" to str("记忆内容（必填）"),
                    "summary" to str("摘要"),
                    "type" to str("类型：long / short，默认 long"),
                    "priority" to str("优先级：high / normal / low，默认 normal"),
                    "character_id" to str("所属角色卡 ID（可空）")
                ),
                listOf("title", "content")
            )
        ),
        definition("db_delete_memory", "删除指定角色记忆（高风险，需用户确认）。", params(mapOf("memory_id" to str("记忆 ID")), listOf("memory_id"))),
        definition("db_list_state_history", "查看当前会话的状态历程（情绪/关系八维随时间的演变）。", params(emptyMap())),
        definition("db_get_latest_state", "查看当前会话最新角色运行时状态快照。", params(emptyMap())),

        // ===== Hook =====
        definition("db_list_hooks", "列出所有本地 Hook。", params(emptyMap())),
        definition(
            "db_create_hook",
            "创建一个新的本地 Hook。name/event 必填。",
            params(
                mapOf(
                    "name" to str("Hook 名称（必填）"),
                    "event" to str("触发事件，如 character.before_turn / character.after_turn（必填）"),
                    "description" to str("描述"),
                    "enabled" to bool("是否启用，默认 true"),
                    "scope" to str("作用域：global / character / conversation / user，默认 global"),
                    "priority" to int("优先级，默认 100"),
                    "actions" to arr("动作数组（JSON 对象列表）"),
                    "conditions" to obj("条件 JSON"),
                    "permissions" to obj("权限 JSON"),
                    "timeout_ms" to int("超时毫秒，默认 3000"),
                    "max_retries" to int("最大重试次数，默认 0"),
                    "trigger_mode" to str("触发模式：always / once / cooldown，默认 always"),
                    "condition_logic" to str("条件逻辑：and / or，默认 and"),
                    "character_id" to str("绑定角色卡 ID（scope=character 时）"),
                    "conversation_id" to str("绑定会话 ID（scope=conversation 时）"),
                    "user_id" to str("绑定用户 ID（scope=user 时）")
                ),
                listOf("name", "event")
            )
        ),
        definition(
            "db_update_hook",
            "修改指定 Hook 的可变字段。",
            params(
                mapOf(
                    "hook_id" to str("Hook ID（必填）"),
                    "name" to str("Hook 名称"),
                    "event" to str("触发事件"),
                    "description" to str("描述"),
                    "enabled" to bool("是否启用"),
                    "scope" to str("作用域"),
                    "priority" to int("优先级"),
                    "actions" to arr("动作数组"),
                    "conditions" to obj("条件 JSON"),
                    "permissions" to obj("权限 JSON"),
                    "timeout_ms" to int("超时毫秒"),
                    "max_retries" to int("最大重试次数"),
                    "trigger_mode" to str("触发模式"),
                    "condition_logic" to str("条件逻辑"),
                    "character_id" to str("绑定角色卡 ID"),
                    "conversation_id" to str("绑定会话 ID"),
                    "user_id" to str("绑定用户 ID")
                ),
                listOf("hook_id")
            )
        ),
        definition("db_delete_hook", "删除指定 Hook（高风险，需用户确认）。", params(mapOf("hook_id" to str("Hook ID")), listOf("hook_id"))),
        definition("db_toggle_hook", "切换 Hook 启用状态。", params(mapOf("hook_id" to str("Hook ID")), listOf("hook_id"))),

        // ===== 工作流 =====
        definition("db_list_workflows", "列出所有本地工作流。", params(emptyMap())),
        definition(
            "db_create_workflow",
            "创建一个新的本地工作流。",
            params(
                mapOf(
                    "name" to str("工作流名称（必填）"),
                    "description" to str("描述"),
                    "enabled" to bool("是否启用，默认 true"),
                    "trigger" to str("触发方式：manual / cron，默认 manual"),
                    "config" to obj("配置 JSON（节点+连线）")
                ),
                listOf("name")
            )
        ),
        definition(
            "db_update_workflow",
            "修改指定工作流。",
            params(
                mapOf(
                    "workflow_id" to str("工作流 ID（必填）"),
                    "name" to str("工作流名称"),
                    "description" to str("描述"),
                    "enabled" to bool("是否启用"),
                    "trigger" to str("触发方式"),
                    "config" to obj("配置 JSON")
                ),
                listOf("workflow_id")
            )
        ),
        definition("db_delete_workflow", "删除指定工作流（高风险，需用户确认）。", params(mapOf("workflow_id" to str("工作流 ID")), listOf("workflow_id"))),

        // ===== 任务 =====
        definition("db_list_tasks", "列出所有本地任务。", params(emptyMap())),
        definition(
            "db_create_task",
            "创建一个新的本地任务。",
            params(
                mapOf(
                    "name" to str("任务名称（必填）"),
                    "description" to str("描述"),
                    "enabled" to bool("是否启用，默认 true"),
                    "trigger" to str("触发方式：manual / interval / cron / run_at，默认 manual"),
                    "config" to obj("配置 JSON"),
                    "target_session_id" to str("目标会话 ID"),
                    "prompt" to str("任务提示词")
                ),
                listOf("name")
            )
        ),
        definition(
            "db_update_task",
            "修改指定任务。",
            params(
                mapOf(
                    "task_id" to str("任务 ID（必填）"),
                    "name" to str("任务名称"),
                    "description" to str("描述"),
                    "enabled" to bool("是否启用"),
                    "trigger" to str("触发方式"),
                    "config" to obj("配置 JSON"),
                    "target_session_id" to str("目标会话 ID"),
                    "prompt" to str("任务提示词")
                ),
                listOf("task_id")
            )
        ),
        definition("db_delete_task", "删除指定任务（高风险，需用户确认）。", params(mapOf("task_id" to str("任务 ID")), listOf("task_id"))),

        // ===== Skill =====
        definition("db_list_skills", "列出所有本地 Skill 元数据。", params(emptyMap())),
        definition(
            "db_create_skill",
            "创建一个新的本地 Skill（仅元数据；如需 SKILL.md 内容请通过 UI 上传）。",
            params(
                mapOf(
                    "name" to str("Skill 名称（必填，唯一）"),
                    "description" to str("描述"),
                    "aliases" to arr("别名数组（字符串）"),
                    "enabled" to bool("是否启用，默认 true"),
                    "parameters" to obj("参数 JSON"),
                    "skill_md" to str("SKILL.md 内容（可选）"),
                    "reference_md" to str("reference.md 内容（可选）")
                ),
                listOf("name")
            )
        ),
        definition(
            "db_update_skill",
            "修改指定 Skill。",
            params(
                mapOf(
                    "skill_id" to str("Skill ID（必填）"),
                    "name" to str("Skill 名称"),
                    "description" to str("描述"),
                    "aliases" to arr("别名数组"),
                    "enabled" to bool("是否启用"),
                    "parameters" to obj("参数 JSON"),
                    "skill_md" to str("SKILL.md 内容"),
                    "reference_md" to str("reference.md 内容")
                ),
                listOf("skill_id")
            )
        ),
        definition("db_delete_skill", "删除指定 Skill（高风险，需用户确认）。", params(mapOf("skill_id" to str("Skill ID")), listOf("skill_id"))),
        definition("db_toggle_skill", "切换 Skill 启用状态。", params(mapOf("skill_id" to str("Skill ID")), listOf("skill_id"))),

        // ===== AI 模型 =====
        definition("db_list_ai_models", "列出所有本地 AI 模型配置。", params(emptyMap())),
        definition("db_get_ai_model", "查看指定 AI 模型详情（含 api_key 等敏感字段）。", params(mapOf("model_id" to str("模型 ID")), listOf("model_id"))),
        definition(
            "db_create_ai_model",
            "创建一个新的本地 AI 模型配置。注意：api_key/base_url/model 必填。",
            params(
                mapOf(
                    "name" to str("模型配置名称（必填）"),
                    "protocol" to str("协议：openai_chat / anthropic_messages，默认 openai_chat"),
                    "provider" to str("供应商"),
                    "api_key" to str("API Key（必填）"),
                    "base_url" to str("API Base URL（必填）"),
                    "model" to str("模型名（必填，如 gpt-4o-mini）"),
                    "enabled" to bool("是否启用，默认 true"),
                    "purpose" to str("用途：chat / vision / tts / image_generation 等，默认 chat"),
                    "priority" to int("优先级，默认 0"),
                    "active" to bool("是否设为激活模型，默认 false"),
                    "temperature" to num("温度"),
                    "max_tokens" to int("最大输出 tokens"),
                    "top_p" to num("top_p"),
                    "supports_stream" to bool("是否支持流式，默认 true"),
                    "append_base_url_path" to bool("是否追加 /v1 等路径，默认 true"),
                    "token_limit_daily" to int("每日 token 限额"),
                    "token_limit_weekly" to int("每周 token 限额"),
                    "input_price" to num("输入价格（每 1M tokens）"),
                    "output_price" to num("输出价格（每 1M tokens）")
                ),
                listOf("name", "api_key", "base_url", "model")
            )
        ),
        definition(
            "db_update_ai_model",
            "修改指定 AI 模型配置的可变字段。",
            params(
                mapOf(
                    "model_id" to str("模型 ID（必填）"),
                    "name" to str("名称"),
                    "protocol" to str("协议"),
                    "provider" to str("供应商"),
                    "api_key" to str("API Key"),
                    "base_url" to str("Base URL"),
                    "model" to str("模型名"),
                    "enabled" to bool("是否启用"),
                    "purpose" to str("用途"),
                    "priority" to int("优先级"),
                    "temperature" to num("温度"),
                    "max_tokens" to int("最大 tokens"),
                    "top_p" to num("top_p"),
                    "supports_stream" to bool("是否支持流式"),
                    "append_base_url_path" to bool("是否追加 base url 路径"),
                    "token_limit_daily" to int("每日限额"),
                    "token_limit_weekly" to int("每周限额"),
                    "input_price" to num("输入价格"),
                    "output_price" to num("输出价格")
                ),
                listOf("model_id")
            )
        ),
        definition("db_delete_ai_model", "删除指定 AI 模型配置（高风险，需用户确认）。", params(mapOf("model_id" to str("模型 ID")), listOf("model_id"))),
        definition("db_set_active_model", "将指定模型设为激活（同 purpose 下唯一）。", params(mapOf("model_id" to str("模型 ID"), "purpose" to str("用途，默认 chat")), listOf("model_id"))),
        definition("db_get_ai_config", "查看当前激活模型的 AI 配置摘要。", params(emptyMap())),
        definition(
            "db_update_ai_config",
            "修改当前激活模型的配置（temperature/max_tokens/top_p/model）。",
            params(
                mapOf(
                    "model" to str("模型名"),
                    "temperature" to num("温度"),
                    "max_tokens" to int("最大 tokens"),
                    "top_p" to num("top_p")
                )
            )
        )
    )
}

/**
 * 本地数据库工具执行器。
 *
 * 通过 [NekobotDatabase] 直接调用 DAO 实现 8 类资源的 CRUD。
 * 高风险删除操作复用 [LocalExecAuthorizationManager] 走用户确认流程；
 * 其余读写操作直接执行。
 *
 * 执行入口 [execute] 与 [LocalAgentToolExecutor.execute] 保持同步签名，
 * 内部使用 `runBlocking(Dispatchers.IO)` 桥接 suspend 的 DAO 调用，
 * 与 `executeLocalSkillTool` 一致。
 */
internal class LocalDbToolExecutor(
    private val db: NekobotDatabase,
    private val sessionId: String,
    private val authorizationManager: LocalExecAuthorizationManager,
    private val onConfirmationRequired: (ExecConfirmationRequest) -> Unit,
    private val generationController: LocalGenerationController = LocalGenerationController()
) {
    private val gson = Gson()

    fun execute(toolName: String, args: Map<String, Any>): Map<String, Any> {
        if (generationController.isStopped) return stoppedFailure()
        if (toolName !in localDbToolIds) {
            return failure("未知数据库工具: $toolName")
        }
        // 高风险删除操作：先请求授权
        if (toolName in localDbHighRiskToolIds) {
            val auth = authorizationManager.requestAuthorization(
                sessionId = sessionId,
                command = "$toolName ${describeTarget(toolName, args)}",
                mainCommand = toolName,
                onRequest = onConfirmationRequired
            )
            when (auth) {
                ExecAuthorization.Reject -> return failure("用户已拒绝执行: $toolName", "rejected" to true)
                ExecAuthorization.Once, ExecAuthorization.Always -> { /* 继续 */ }
            }
            if (generationController.isStopped) return stoppedFailure()
        }
        return runBlocking(Dispatchers.IO) {
            try {
                when (toolName) {
                    // 角色卡
                    "db_list_characters" -> listCharacters()
                    "db_get_character" -> getCharacter(args)
                    "db_create_character" -> createCharacter(args)
                    "db_update_character" -> updateCharacter(args)
                    "db_delete_character" -> deleteCharacter(args)
                    // 世界书
                    "db_list_world_books" -> listWorldBooks()
                    "db_get_world_book" -> getWorldBook(args)
                    "db_create_world_book" -> createWorldBook(args)
                    "db_update_world_book" -> updateWorldBook(args)
                    "db_delete_world_book" -> deleteWorldBook(args)
                    "db_upsert_world_book_entry" -> upsertWorldBookEntry(args)
                    "db_delete_world_book_entry" -> deleteWorldBookEntry(args)
                    // Token 用量
                    "db_token_stats" -> tokenStats()
                    "db_token_rankings" -> tokenRankings()
                    "db_session_token_usage" -> sessionTokenUsage(args)
                    // 记忆 / 状态
                    "db_list_memories" -> listMemories(args)
                    "db_save_memory" -> saveMemory(args)
                    "db_delete_memory" -> deleteMemory(args)
                    "db_list_state_history" -> listStateHistory()
                    "db_get_latest_state" -> getLatestState()
                    // Hook
                    "db_list_hooks" -> listHooks()
                    "db_create_hook" -> createHook(args)
                    "db_update_hook" -> updateHook(args)
                    "db_delete_hook" -> deleteHook(args)
                    "db_toggle_hook" -> toggleHook(args)
                    // 工作流
                    "db_list_workflows" -> listWorkflows()
                    "db_create_workflow" -> createWorkflow(args)
                    "db_update_workflow" -> updateWorkflow(args)
                    "db_delete_workflow" -> deleteWorkflow(args)
                    // 任务
                    "db_list_tasks" -> listTasks()
                    "db_create_task" -> createTask(args)
                    "db_update_task" -> updateTask(args)
                    "db_delete_task" -> deleteTask(args)
                    // Skill
                    "db_list_skills" -> listSkills()
                    "db_create_skill" -> createSkill(args)
                    "db_update_skill" -> updateSkill(args)
                    "db_delete_skill" -> deleteSkill(args)
                    "db_toggle_skill" -> toggleSkill(args)
                    // AI 模型
                    "db_list_ai_models" -> listAiModels()
                    "db_get_ai_model" -> getAiModel(args)
                    "db_create_ai_model" -> createAiModel(args)
                    "db_update_ai_model" -> updateAiModel(args)
                    "db_delete_ai_model" -> deleteAiModel(args)
                    "db_set_active_model" -> setActiveModel(args)
                    "db_get_ai_config" -> getAiConfig()
                    "db_update_ai_config" -> updateAiConfig(args)
                    else -> failure("未实现的数据库工具: $toolName")
                }
            } catch (e: Exception) {
                if (generationController.isStopped) stoppedFailure()
                else failure(e.message ?: "数据库工具执行失败")
            }
        }
    }

    /** 为高风险工具的授权弹窗生成可读描述。 */
    private fun describeTarget(toolName: String, args: Map<String, Any>): String {
        return when (toolName) {
            "db_delete_character" -> "character_id=${args.string("character_id")}"
            "db_delete_world_book" -> "book_id=${args.string("book_id")}"
            "db_delete_world_book_entry" -> "entry_id=${args.string("entry_id")}"
            "db_delete_memory" -> "memory_id=${args.string("memory_id")}"
            "db_delete_hook" -> "hook_id=${args.string("hook_id")}"
            "db_delete_workflow" -> "workflow_id=${args.string("workflow_id")}"
            "db_delete_task" -> "task_id=${args.string("task_id")}"
            "db_delete_skill" -> "skill_id=${args.string("skill_id")}"
            "db_delete_ai_model" -> "model_id=${args.string("model_id")}"
            else -> ""
        }
    }

    // ==================== 角色卡 ====================

    private suspend fun listCharacters(): Map<String, Any> {
        val list = db.characterDao().listAll()
        return success(
            "count" to list.size,
            "characters" to list.map { it.toSummary() }
        )
    }

    private suspend fun getCharacter(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("character_id").ifBlank { return failure("character_id 不能为空") }
        val entity = db.characterDao().getById(id) ?: return failure("角色卡不存在: $id")
        return success("character" to entity.toDetail())
    }

    private suspend fun createCharacter(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        val now = nowIso()
        val entity = LocalCharacterEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = args.string("description").ifBlank { null },
            basicInfo = args.string("basic_info").ifBlank { null },
            personality = args.string("personality").ifBlank { null },
            scenario = args.string("scenario").ifBlank { null },
            firstMessage = args.string("first_message").ifBlank { null },
            alternateGreetings = args.stringList("alternate_greetings")?.let { gson.toJson(it) },
            exampleDialogues = args.string("example_dialogues").ifBlank { null },
            responseFormat = args.string("response_format").ifBlank { null },
            rules = args.stringList("rules")?.let { gson.toJson(it) },
            tags = args.stringList("tags")?.let { gson.toJson(it) },
            systemPrompt = args.string("system_prompt").ifBlank { null },
            greeting = args.string("greeting").ifBlank { null },
            createdAt = now,
            updatedAt = now
        )
        db.characterDao().upsert(entity)
        return success("character" to entity.toDetail(), "character_id" to entity.id)
    }

    private suspend fun updateCharacter(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("character_id").ifBlank { return failure("character_id 不能为空") }
        val existing = db.characterDao().getById(id) ?: return failure("角色卡不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name },
            description = args.optString("description", existing.description),
            basicInfo = args.optString("basic_info", existing.basicInfo),
            personality = args.optString("personality", existing.personality),
            scenario = args.optString("scenario", existing.scenario),
            firstMessage = args.optString("first_message", existing.firstMessage),
            alternateGreetings = args.stringList("alternate_greetings")?.let { gson.toJson(it) } ?: existing.alternateGreetings,
            exampleDialogues = args.optString("example_dialogues", existing.exampleDialogues),
            responseFormat = args.optString("response_format", existing.responseFormat),
            rules = args.stringList("rules")?.let { gson.toJson(it) } ?: existing.rules,
            tags = args.stringList("tags")?.let { gson.toJson(it) } ?: existing.tags,
            systemPrompt = args.optString("system_prompt", existing.systemPrompt),
            greeting = args.optString("greeting", existing.greeting),
            updatedAt = nowIso()
        )
        db.characterDao().upsert(updated)
        return success("character" to updated.toDetail())
    }

    private suspend fun deleteCharacter(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("character_id").ifBlank { return failure("character_id 不能为空") }
        db.characterDao().deleteById(id)
        return success("deleted" to true, "character_id" to id)
    }

    // ==================== 世界书 ====================

    private suspend fun listWorldBooks(): Map<String, Any> {
        val list = db.worldBookDao().listAll()
        return success(
            "count" to list.size,
            "world_books" to list.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "description" to (it.description ?: ""),
                    "character_id" to (it.characterId ?: ""),
                    "enabled" to it.enabled,
                    "created_at" to it.createdAt,
                    "updated_at" to it.updatedAt
                )
            }
        )
    }

    private suspend fun getWorldBook(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("book_id").ifBlank { return failure("book_id 不能为空") }
        val book = db.worldBookDao().getById(id) ?: return failure("世界书不存在: $id")
        val entries = db.worldBookDao().listEntries(id)
        return success(
            "world_book" to mapOf(
                "id" to book.id,
                "name" to book.name,
                "description" to (book.description ?: ""),
                "character_id" to (book.characterId ?: ""),
                "enabled" to book.enabled,
                "created_at" to book.createdAt,
                "updated_at" to book.updatedAt
            ),
            "entries" to entries.map { it.toMap() },
            "entries_count" to entries.size
        )
    }

    private suspend fun createWorldBook(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        val now = nowIso()
        val entity = LocalWorldBookEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = args.string("description").ifBlank { null },
            characterId = args.string("character_id").ifBlank { null },
            enabled = args.bool("enabled", true),
            createdAt = now,
            updatedAt = now
        )
        db.worldBookDao().upsert(entity)
        return success("world_book_id" to entity.id, "name" to entity.name)
    }

    private suspend fun updateWorldBook(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("book_id").ifBlank { return failure("book_id 不能为空") }
        val existing = db.worldBookDao().getById(id) ?: return failure("世界书不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name },
            description = args.optString("description", existing.description),
            characterId = args.optString("character_id", existing.characterId),
            enabled = args.optBool("enabled", existing.enabled),
            updatedAt = nowIso()
        )
        // 使用 @Update 而非 upsert：upsert(REPLACE) 会触发外键级联删除 entries
        db.worldBookDao().update(updated)
        return success("world_book_id" to updated.id)
    }

    private suspend fun deleteWorldBook(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("book_id").ifBlank { return failure("book_id 不能为空") }
        db.worldBookDao().deleteById(id) // 外键级联删除条目
        return success("deleted" to true, "book_id" to id)
    }

    private suspend fun upsertWorldBookEntry(args: Map<String, Any>): Map<String, Any> {
        val bookId = args.string("book_id").ifBlank { return failure("book_id 不能为空") }
        if (db.worldBookDao().getById(bookId) == null) return failure("世界书不存在: $bookId")
        val entryId = args.string("entry_id").ifBlank { UUID.randomUUID().toString() }
        val entity = LocalWorldBookEntryEntity(
            id = entryId,
            bookId = bookId,
            keys = args.stringList("keys")?.let { gson.toJson(it) },
            content = args.string("content").ifBlank { null },
            comment = args.string("comment").ifBlank { null },
            enabled = args.bool("enabled", true),
            constant = args.bool("constant", false),
            selective = args.bool("selective", false),
            insertionOrder = args.int("insertion_order", 0),
            priority = args.int("priority", 0),
            position = args.string("position").ifBlank { null },
            caseSensitive = args.bool("case_sensitive", false),
            displayIndex = args.int("display_index", 0)
        )
        db.worldBookDao().upsertEntry(entity)
        return success("entry_id" to entryId, "book_id" to bookId)
    }

    private suspend fun deleteWorldBookEntry(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("entry_id").ifBlank { return failure("entry_id 不能为空") }
        db.worldBookDao().deleteEntryById(id)
        return success("deleted" to true, "entry_id" to id)
    }

    // ==================== Token 用量 ====================

    private fun tokenStats(): Map<String, Any> {
        val stats = com.nekobot.app.data.local.ai.getGlobalTokenStatsManager()
            .getStats(dateRange = null, startDate = null, endDate = null)
        return success("stats" to stats)
    }

    private fun tokenRankings(): Map<String, Any> {
        val rankings = com.nekobot.app.data.local.ai.getGlobalTokenStatsManager()
            .getRankings(limit = 10)
        return success("rankings" to rankings)
    }

    private suspend fun sessionTokenUsage(args: Map<String, Any>): Map<String, Any> {
        val sid = args.string("session_id").ifBlank { sessionId }
        // 优先从持久化 SharedPreferences 汇总
        val total = readSessionTokenTotal(sid)
        return success("session_id" to sid, "total_tokens" to total)
    }

    /** 从 token_usage_$dbName SharedPreferences 读取会话累计 token。 */
    private suspend fun readSessionTokenTotal(sid: String): Long {
        // NekobotDatabase 暴露 dbName；通过反射读取 prefs 不优雅，这里直接走 DAO：messages 表已有 input/output tokens
        val messages = db.messageDao().listBySession(sid)
        return messages.sumOf { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }.toLong()
    }

    // ==================== 记忆 / 状态 ====================

    private suspend fun listMemories(args: Map<String, Any>): Map<String, Any> {
        val charId = args.string("character_id").ifBlank { null }
        val list = if (charId != null) db.memoryDao().listByCharacter(charId)
        else db.memoryDao().listAll()
        return success(
            "count" to list.size,
            "memories" to list.map {
                mapOf(
                    "id" to it.id,
                    "character_id" to it.characterId,
                    "target_id" to it.targetId,
                    "type" to it.type,
                    "category" to it.category,
                    "title" to it.title,
                    "summary" to it.summary,
                    "content" to it.content,
                    "importance" to it.importance,
                    "memory_path" to (it.memoryPath ?: ""),
                    "conversation_id" to (it.conversationId ?: ""),
                    "created_at" to it.createdAt,
                    "updated_at" to (it.updatedAt ?: "")
                )
            }
        )
    }

    private suspend fun saveMemory(args: Map<String, Any>): Map<String, Any> {
        val title = args.string("title").ifBlank { return failure("title 不能为空") }
        val content = args.string("content").ifBlank { return failure("content 不能为空") }
        val id = args.string("id").ifBlank { null }
        val now = nowIso()
        val importance = when (args.string("priority").ifBlank { "normal" }) {
            "high" -> 8
            "low" -> 1
            else -> 4
        }
        val memId = id ?: UUID.randomUUID().toString()
        val existing = id?.let { db.memoryDao().listAll().firstOrNull { e -> e.id == id } }
        val entity = com.nekobot.app.data.local.db.LocalCharacterMemoryEntity(
            id = memId,
            characterId = existing?.characterId ?: args.string("character_id").ifBlank { "" },
            targetId = existing?.targetId ?: "local-user",
            type = args.string("type").ifBlank { "long" }.let { if (it == "short") "short" else "long" },
            category = existing?.category ?: "legacy",
            title = title,
            summary = args.string("summary").ifBlank { "" },
            content = content,
            importance = existing?.importance ?: importance,
            emotionImpact = existing?.emotionImpact,
            sourceTurnId = existing?.sourceTurnId,
            createdAt = existing?.createdAt ?: now,
            expiresAt = existing?.expiresAt,
            memoryPath = existing?.memoryPath,
            version = (existing?.version ?: 1) + if (id != null) 1 else 1,
            updatedAt = now,
            conversationId = existing?.conversationId
        )
        db.memoryDao().upsert(entity)
        return success("memory_id" to memId)
    }

    private suspend fun deleteMemory(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("memory_id").ifBlank { return failure("memory_id 不能为空") }
        db.memoryDao().deleteById(id)
        return success("deleted" to true, "memory_id" to id)
    }

    private suspend fun listStateHistory(): Map<String, Any> {
        val snapshots = db.stateSnapshotDao().listBySession(sessionId)
        return success(
            "count" to snapshots.size,
            "history" to snapshots.mapIndexed { idx, snap ->
                buildMap {
                    put("index", idx + 1)
                    put("timestamp", snap.timestamp)
                    put("trigger_type", snap.triggerType)
                    put("mood", snap.mood)
                    put("mood_intensity", snap.moodIntensity)
                    put("energy", snap.energy)
                    put("affection", snap.affection)
                    put("trust", snap.trust)
                    put("familiarity", snap.familiarity)
                    put("dependency", snap.dependency)
                    put("security", snap.security)
                    put("jealousy", snap.jealousy)
                    snap.userMessage?.let { put("user_message", it) }
                    snap.assistantMessage?.let { put("assistant_message", it) }
                }
            }
        )
    }

    private suspend fun getLatestState(): Map<String, Any> {
        val snapshots = db.stateSnapshotDao().listBySession(sessionId)
        val latest = snapshots.lastOrNull() ?: return success("empty" to true, "message" to "当前会话暂无状态快照")
        return success(
            "latest" to mapOf(
                "timestamp" to latest.timestamp,
                "trigger_type" to latest.triggerType,
                "mood" to latest.mood,
                "mood_intensity" to latest.moodIntensity,
                "energy" to latest.energy,
                "affection" to latest.affection,
                "trust" to latest.trust,
                "familiarity" to latest.familiarity,
                "dependency" to latest.dependency,
                "security" to latest.security,
                "jealousy" to latest.jealousy
            )
        )
    }

    // ==================== Hook ====================

    private suspend fun listHooks(): Map<String, Any> {
        val list = db.hookDao().listAll()
        return success(
            "count" to list.size,
            "hooks" to list.map { it.toMap() }
        )
    }

    private suspend fun createHook(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        val event = args.string("event").ifBlank { return failure("event 不能为空") }
        val now = nowIso()
        val entity = LocalHookEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            event = event,
            description = args.string("description").ifBlank { null },
            enabled = args.bool("enabled", true),
            scope = args.string("scope").ifBlank { "global" },
            priority = args.int("priority", 100),
            actionsJson = args.anyList("actions")?.let { gson.toJson(it) } ?: "[]",
            conditionsJson = args.any("conditions")?.let { gson.toJson(it) },
            permissionsJson = args.any("permissions")?.let { gson.toJson(it) },
            timeoutMs = args.int("timeout_ms", 3000),
            maxRetries = args.int("max_retries", 0),
            triggerMode = args.string("trigger_mode").ifBlank { "always" },
            conditionLogic = args.string("condition_logic").ifBlank { "and" },
            characterId = args.string("character_id").ifBlank { null },
            conversationId = args.string("conversation_id").ifBlank { null },
            userId = args.string("user_id").ifBlank { null },
            createdAt = now,
            updatedAt = now
        )
        db.hookDao().upsert(entity)
        return success("hook_id" to entity.id, "hook" to entity.toMap())
    }

    private suspend fun updateHook(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("hook_id").ifBlank { return failure("hook_id 不能为空") }
        val existing = db.hookDao().getById(id) ?: return failure("Hook 不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name },
            event = args.string("event").ifBlank { existing.event },
            description = args.optString("description", existing.description),
            enabled = args.optBool("enabled", existing.enabled),
            scope = args.optStringNN("scope", existing.scope),
            priority = args.optInt("priority", existing.priority),
            actionsJson = args.anyList("actions")?.let { gson.toJson(it) } ?: existing.actionsJson,
            conditionsJson = args.any("conditions")?.let { gson.toJson(it) } ?: existing.conditionsJson,
            permissionsJson = args.any("permissions")?.let { gson.toJson(it) } ?: existing.permissionsJson,
            timeoutMs = args.optInt("timeout_ms", existing.timeoutMs),
            maxRetries = args.optInt("max_retries", existing.maxRetries),
            triggerMode = args.optStringNN("trigger_mode", existing.triggerMode),
            conditionLogic = args.optStringNN("condition_logic", existing.conditionLogic),
            characterId = args.optString("character_id", existing.characterId),
            conversationId = args.optString("conversation_id", existing.conversationId),
            userId = args.optString("user_id", existing.userId),
            updatedAt = nowIso()
        )
        db.hookDao().upsert(updated)
        return success("hook_id" to updated.id, "hook" to updated.toMap())
    }

    private suspend fun deleteHook(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("hook_id").ifBlank { return failure("hook_id 不能为空") }
        db.hookDao().deleteById(id)
        return success("deleted" to true, "hook_id" to id)
    }

    private suspend fun toggleHook(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("hook_id").ifBlank { return failure("hook_id 不能为空") }
        val existing = db.hookDao().getById(id) ?: return failure("Hook 不存在: $id")
        db.hookDao().setEnabled(id, !existing.enabled)
        return success("hook_id" to id, "enabled" to !existing.enabled)
    }

    // ==================== 工作流 ====================

    private suspend fun listWorkflows(): Map<String, Any> {
        val list = db.workflowDao().listAll()
        return success(
            "count" to list.size,
            "workflows" to list.map { it.toMap() }
        )
    }

    private suspend fun createWorkflow(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        val entity = LocalWorkflowEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = args.string("description").ifBlank { null },
            enabled = args.bool("enabled", true),
            trigger = args.string("trigger").ifBlank { "manual" },
            configJson = args.any("config")?.let { gson.toJson(it) },
            createdAt = nowIso()
        )
        db.workflowDao().upsert(entity)
        return success("workflow_id" to entity.id, "workflow" to entity.toMap())
    }

    private suspend fun updateWorkflow(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("workflow_id").ifBlank { return failure("workflow_id 不能为空") }
        val existing = db.workflowDao().getById(id) ?: return failure("工作流不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name },
            description = args.optString("description", existing.description),
            enabled = args.optBool("enabled", existing.enabled),
            trigger = args.optStringNN("trigger", existing.trigger),
            configJson = args.any("config")?.let { gson.toJson(it) } ?: existing.configJson
        )
        db.workflowDao().upsert(updated)
        return success("workflow_id" to updated.id, "workflow" to updated.toMap())
    }

    private suspend fun deleteWorkflow(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("workflow_id").ifBlank { return failure("workflow_id 不能为空") }
        db.workflowDao().deleteById(id)
        return success("deleted" to true, "workflow_id" to id)
    }

    // ==================== 任务 ====================

    private suspend fun listTasks(): Map<String, Any> {
        val list = db.taskDao().listAll()
        return success(
            "count" to list.size,
            "tasks" to list.map { it.toMap() }
        )
    }

    private suspend fun createTask(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        val entity = LocalTaskEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = args.string("description").ifBlank { null },
            enabled = args.bool("enabled", true),
            trigger = args.string("trigger").ifBlank { "manual" },
            configJson = args.any("config")?.let { gson.toJson(it) },
            targetSessionId = args.string("target_session_id").ifBlank { null },
            prompt = args.string("prompt").ifBlank { null },
            createdAt = nowIso()
        )
        db.taskDao().upsert(entity)
        return success("task_id" to entity.id, "task" to entity.toMap())
    }

    private suspend fun updateTask(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("task_id").ifBlank { return failure("task_id 不能为空") }
        val existing = db.taskDao().getById(id) ?: return failure("任务不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name },
            description = args.optString("description", existing.description),
            enabled = args.optBool("enabled", existing.enabled),
            trigger = args.optStringNN("trigger", existing.trigger),
            configJson = args.any("config")?.let { gson.toJson(it) } ?: existing.configJson,
            targetSessionId = args.optString("target_session_id", existing.targetSessionId),
            prompt = args.optString("prompt", existing.prompt)
        )
        db.taskDao().upsert(updated)
        return success("task_id" to updated.id, "task" to updated.toMap())
    }

    private suspend fun deleteTask(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("task_id").ifBlank { return failure("task_id 不能为空") }
        db.taskDao().deleteById(id)
        return success("deleted" to true, "task_id" to id)
    }

    // ==================== Skill ====================

    private suspend fun listSkills(): Map<String, Any> {
        val list = db.skillDao().listAll()
        return success(
            "count" to list.size,
            "skills" to list.map { it.toMap() }
        )
    }

    private suspend fun createSkill(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        if (db.skillDao().listAll().any { it.name.equals(name, ignoreCase = true) }) {
            return failure("Skill 名称已存在: $name")
        }
        val entity = LocalSkillEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            description = args.string("description").ifBlank { null },
            aliasesJson = args.stringList("aliases")?.let { gson.toJson(it) } ?: "[]",
            enabled = args.bool("enabled", true),
            parametersJson = args.any("parameters")?.let { gson.toJson(it) },
            createdAt = nowIso()
        )
        db.skillDao().upsert(entity)
        return success("skill_id" to entity.id, "skill" to entity.toMap())
    }

    private suspend fun updateSkill(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("skill_id").ifBlank { return failure("skill_id 不能为空") }
        val existing = db.skillDao().getById(id) ?: return failure("Skill 不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name }.trim(),
            description = args.optString("description", existing.description),
            aliasesJson = args.stringList("aliases")?.let { gson.toJson(it) } ?: existing.aliasesJson,
            enabled = args.optBool("enabled", existing.enabled),
            parametersJson = args.any("parameters")?.let { gson.toJson(it) } ?: existing.parametersJson
        )
        db.skillDao().upsert(updated)
        return success("skill_id" to updated.id, "skill" to updated.toMap())
    }

    private suspend fun deleteSkill(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("skill_id").ifBlank { return failure("skill_id 不能为空") }
        db.skillDao().deleteById(id)
        return success("deleted" to true, "skill_id" to id)
    }

    private suspend fun toggleSkill(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("skill_id").ifBlank { return failure("skill_id 不能为空") }
        val existing = db.skillDao().getById(id) ?: return failure("Skill 不存在: $id")
        db.skillDao().setEnabled(id, !existing.enabled)
        return success("skill_id" to id, "enabled" to !existing.enabled)
    }

    // ==================== AI 模型 ====================

    private suspend fun listAiModels(): Map<String, Any> {
        val list = db.aiModelDao().listAll()
        return success(
            "count" to list.size,
            "models" to list.map { it.toSummary() }
        )
    }

    private suspend fun getAiModel(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("model_id").ifBlank { return failure("model_id 不能为空") }
        val entity = db.aiModelDao().getById(id) ?: return failure("AI 模型不存在: $id")
        return success("model" to entity.toDetail())
    }

    private suspend fun createAiModel(args: Map<String, Any>): Map<String, Any> {
        val name = args.string("name").ifBlank { return failure("name 不能为空") }
        val apiKey = args.string("api_key").ifBlank { return failure("api_key 不能为空") }
        val baseUrl = args.string("base_url").ifBlank { return failure("base_url 不能为空") }
        val model = args.string("model").ifBlank { return failure("model 不能为空") }
        val entity = LocalAiModelEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = args.string("protocol").ifBlank { "openai_chat" },
            provider = args.string("provider").ifBlank { null },
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            enabled = args.bool("enabled", true),
            purpose = args.string("purpose").ifBlank { "chat" },
            priority = args.int("priority", 0),
            active = args.bool("active", false),
            temperature = args.float("temperature"),
            maxTokens = args.intOrNull("max_tokens"),
            topP = args.float("top_p"),
            appendBaseUrlPath = args.bool("append_base_url_path", true),
            supportsStream = args.bool("supports_stream", true),
            createdAt = nowIso(),
            tokenLimitDaily = args.long("token_limit_daily", 0L),
            tokenLimitWeekly = args.long("token_limit_weekly", 0L),
            inputPrice = args.doubleOrNull("input_price"),
            outputPrice = args.doubleOrNull("output_price")
        )
        db.aiModelDao().upsert(entity)
        if (entity.active) db.aiModelDao().setActiveForPurpose(entity.id, entity.purpose)
        return success("model_id" to entity.id, "model" to entity.toSummary())
    }

    private suspend fun updateAiModel(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("model_id").ifBlank { return failure("model_id 不能为空") }
        val existing = db.aiModelDao().getById(id) ?: return failure("AI 模型不存在: $id")
        val updated = existing.copy(
            name = args.string("name").ifBlank { existing.name },
            protocol = args.optStringNN("protocol", existing.protocol),
            provider = args.optString("provider", existing.provider),
            apiKey = args.optStringNN("api_key", existing.apiKey),
            baseUrl = args.optStringNN("base_url", existing.baseUrl),
            model = args.optStringNN("model", existing.model),
            enabled = args.optBool("enabled", existing.enabled),
            purpose = args.optStringNN("purpose", existing.purpose),
            priority = args.optInt("priority", existing.priority),
            temperature = args.optFloatOrNull("temperature", existing.temperature),
            maxTokens = args.optIntOrNull("max_tokens", existing.maxTokens),
            topP = args.optFloatOrNull("top_p", existing.topP),
            appendBaseUrlPath = args.optBool("append_base_url_path", existing.appendBaseUrlPath),
            supportsStream = args.optBool("supports_stream", existing.supportsStream),
            tokenLimitDaily = args.optLong("token_limit_daily", existing.tokenLimitDaily),
            tokenLimitWeekly = args.optLong("token_limit_weekly", existing.tokenLimitWeekly),
            inputPrice = args.optDoubleOrNull("input_price", existing.inputPrice),
            outputPrice = args.optDoubleOrNull("output_price", existing.outputPrice)
        )
        db.aiModelDao().upsert(updated)
        return success("model_id" to updated.id, "model" to updated.toSummary())
    }

    private suspend fun deleteAiModel(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("model_id").ifBlank { return failure("model_id 不能为空") }
        db.aiModelDao().deleteById(id)
        return success("deleted" to true, "model_id" to id)
    }

    private suspend fun setActiveModel(args: Map<String, Any>): Map<String, Any> {
        val id = args.string("model_id").ifBlank { return failure("model_id 不能为空") }
        val purpose = args.string("purpose").ifBlank { "chat" }
        val existing = db.aiModelDao().getById(id) ?: return failure("AI 模型不存在: $id")
        db.aiModelDao().setActiveForPurpose(id, purpose)
        return success("model_id" to id, "purpose" to purpose, "active" to true, "model_name" to existing.name)
    }

    private suspend fun getAiConfig(): Map<String, Any> {
        val active = db.aiModelDao().getActive()
        return if (active == null) {
            success("empty" to true, "message" to "未配置激活的 AI 模型")
        } else {
            success(
                "ai_config" to mapOf(
                    "model" to active.model,
                    "temperature" to (active.temperature ?: 0.7f),
                    "max_tokens" to (active.maxTokens ?: 2048),
                    "max_context_length" to 100000,
                    "top_p" to (active.topP ?: 1.0f),
                    "purpose" to active.purpose,
                    "model_name" to active.name
                )
            )
        }
    }

    private suspend fun updateAiConfig(args: Map<String, Any>): Map<String, Any> {
        val active = db.aiModelDao().getActive()
            ?: return failure("未配置激活的 AI 模型，无法更新配置")
        val updated = active.copy(
            model = args.string("model").ifBlank { active.model },
            temperature = args.floatOrNull("temperature") ?: active.temperature,
            maxTokens = args.intOrNull("max_tokens") ?: active.maxTokens,
            topP = args.floatOrNull("top_p") ?: active.topP
        )
        db.aiModelDao().upsert(updated)
        return success("model_id" to updated.id, "message" to "已同步到当前激活的 AI 模型")
    }

    // ==================== 辅助 ====================

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun success(vararg values: Pair<String, Any>): Map<String, Any> = buildMap {
        put("success", true)
        values.forEach { (k, v) -> put(k, v) }
    }

    private fun failure(message: String, vararg values: Pair<String, Any>): Map<String, Any> = buildMap {
        put("success", false)
        put("error", message)
        values.forEach { (k, v) -> put(k, v) }
    }

    private fun stoppedFailure(vararg values: Pair<String, Any>): Map<String, Any> =
        failure("生成已停止", "stopped" to true, *values)

    // ---- 参数提取 helpers ----

    private fun Map<String, Any>.string(key: String): String = this[key]?.toString().orEmpty()

    private fun Map<String, Any>.optString(key: String, default: String?): String? =
        this[key]?.toString()?.takeIf { it.isNotBlank() } ?: default

    /** 非空默认值重载：返回类型也为非空 String，用于 entity 中非空字段（如 scope/trigger/protocol 等）。 */
    private fun Map<String, Any>.optStringNN(key: String, default: String): String =
        this[key]?.toString()?.takeIf { it.isNotBlank() } ?: default

    private fun Map<String, Any>.optBool(key: String, default: Boolean): Boolean =
        (this[key] as? Boolean) ?: (this[key]?.toString()?.toBooleanStrictOrNull() ?: default)

    private fun Map<String, Any>.bool(key: String, default: Boolean): Boolean = optBool(key, default)

    private fun Map<String, Any>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    private fun Map<String, Any>.optInt(key: String, default: Int): Int = int(key, default)

    private fun Map<String, Any>.intOrNull(key: String): Int? =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull()

    private fun Map<String, Any>.optIntOrNull(key: String, default: Int?): Int? =
        intOrNull(key) ?: default

    private fun Map<String, Any>.long(key: String, default: Long): Long =
        (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: default

    private fun Map<String, Any>.optLong(key: String, default: Long): Long = long(key, default)

    private fun Map<String, Any>.float(key: String): Float? =
        (this[key] as? Number)?.toFloat() ?: this[key]?.toString()?.toFloatOrNull()

    private fun Map<String, Any>.floatOrNull(key: String): Float? = float(key)

    private fun Map<String, Any>.optFloatOrNull(key: String, default: Float?): Float? =
        float(key) ?: default

    private fun Map<String, Any>.doubleOrNull(key: String): Double? =
        (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull()

    private fun Map<String, Any>.optDoubleOrNull(key: String, default: Double?): Double? =
        doubleOrNull(key) ?: default

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.stringList(key: String): List<String>? =
        (this[key] as? List<String>) ?: (this[key] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any>.any(key: String): Any? = this[key]

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.anyList(key: String): List<Any>? =
        (this[key] as? List<Any>)?.takeIf { it.isNotEmpty() }

    // ---- Entity → Map 转换 ----

    private fun LocalCharacterEntity.toSummary(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "description" to (description ?: ""),
        "avatar" to (avatar ?: ""),
        "tags" to (tags ?: "[]"),
        "created_at" to createdAt,
        "updated_at" to updatedAt
    )

    private fun LocalCharacterEntity.toDetail(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "description" to (description ?: ""),
        "avatar" to (avatar ?: ""),
        "portrait" to (portrait ?: ""),
        "tags" to (tags ?: "[]"),
        "basic_info" to (basicInfo ?: ""),
        "personality" to (personality ?: ""),
        "scenario" to (scenario ?: ""),
        "first_message" to (firstMessage ?: ""),
        "alternate_greetings" to (alternateGreetings ?: "[]"),
        "example_dialogues" to (exampleDialogues ?: ""),
        "response_format" to (responseFormat ?: ""),
        "rules" to (rules ?: "[]"),
        "state" to (state ?: "{}"),
        "system_prompt" to (systemPrompt ?: ""),
        "greeting" to (greeting ?: ""),
        "created_at" to createdAt,
        "updated_at" to updatedAt
    )

    private fun LocalWorldBookEntryEntity.toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "book_id" to bookId,
        "keys" to (keys ?: "[]"),
        "content" to (content ?: ""),
        "comment" to (comment ?: ""),
        "enabled" to enabled,
        "constant" to constant,
        "selective" to selective,
        "insertion_order" to insertionOrder,
        "priority" to priority,
        "position" to (position ?: ""),
        "case_sensitive" to caseSensitive,
        "display_index" to displayIndex
    )

    private fun LocalHookEntity.toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "event" to event,
        "description" to (description ?: ""),
        "enabled" to enabled,
        "scope" to scope,
        "priority" to priority,
        "actions" to actionsJson,
        "conditions" to (conditionsJson ?: "null"),
        "permissions" to (permissionsJson ?: "null"),
        "timeout_ms" to timeoutMs,
        "max_retries" to maxRetries,
        "trigger_mode" to triggerMode,
        "condition_logic" to conditionLogic,
        "character_id" to (characterId ?: ""),
        "conversation_id" to (conversationId ?: ""),
        "user_id" to (userId ?: ""),
        "created_at" to createdAt,
        "updated_at" to updatedAt
    )

    private fun LocalWorkflowEntity.toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "description" to (description ?: ""),
        "enabled" to enabled,
        "trigger" to trigger,
        "config" to (configJson ?: "null"),
        "created_at" to createdAt
    )

    private fun LocalTaskEntity.toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "kind" to kind,
        "name" to name,
        "description" to (description ?: ""),
        "enabled" to enabled,
        "trigger" to trigger,
        "config" to (configJson ?: "null"),
        "target_session_id" to (targetSessionId ?: ""),
        "prompt" to (prompt ?: ""),
        "created_at" to createdAt,
        "last_run" to (lastRun ?: ""),
        "next_run" to (nextRun ?: "")
    )

    private fun LocalSkillEntity.toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "description" to (description ?: ""),
        "aliases" to aliasesJson,
        "enabled" to enabled,
        "parameters" to (parametersJson ?: "null"),
        "created_at" to createdAt
    )

    private fun LocalAiModelEntity.toSummary(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "protocol" to protocol,
        "provider" to (provider ?: ""),
        "model" to model,
        "enabled" to enabled,
        "purpose" to purpose,
        "priority" to priority,
        "active" to active,
        "base_url" to baseUrl,
        "created_at" to createdAt
    )

    private fun LocalAiModelEntity.toDetail(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "protocol" to protocol,
        "provider" to (provider ?: ""),
        "api_key" to apiKey,
        "base_url" to baseUrl,
        "model" to model,
        "enabled" to enabled,
        "purpose" to purpose,
        "priority" to priority,
        "active" to active,
        "temperature" to (temperature ?: 0.7f),
        "max_tokens" to (maxTokens ?: 2048),
        "top_p" to (topP ?: 1.0f),
        "append_base_url_path" to appendBaseUrlPath,
        "supports_stream" to supportsStream,
        "token_limit_daily" to tokenLimitDaily,
        "token_limit_weekly" to tokenLimitWeekly,
        "failover_timeout" to failoverTimeout,
        "input_price" to (inputPrice ?: 0.0),
        "output_price" to (outputPrice ?: 0.0),
        "created_at" to createdAt
    )
}
