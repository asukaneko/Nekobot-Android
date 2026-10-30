package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.LocalCharacterMemoryEntity
import com.nekobot.app.data.local.db.LocalHookLogEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.model.Hook
import com.nekobot.app.data.remote.HookNotification
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.nekobot.app.R

/**
 * 本地模式 Hook 执行引擎。
 *
 * 对应原仓库 nbot/hooks/manager.py + nbot/hooks/actions.py。
 * 在 AI 管线的关键节点触发 hook（事件清单见 [HookEventCatalog]）：
 *   - 消息：message.before_send / message.after_receive
 *   - 角色：character.before_turn.finished / character.after_turn.finished
 *   - 模型：model.after_call
 *   - Agent：agent.session.start / agent.session.end / agent.iteration.start /
 *     agent.tool.before_call / agent.tool.after_call / agent.context.trimmed / agent.error
 *
 * 评估条件并执行 10 种内置 action：
 *   - log：写日志 + 触发 UI 成就式弹窗
 *   - notify：推送一条聊天内联提示（可单独控制提示正文与级别）
 *   - memory_write：写入角色记忆（MemoryDao）
 *   - prompt_inject：向 PromptStack 注入额外提示词
 *   - state_delta：修改角色状态（mood/energy/mood_intensity）
 *   - relationship_delta：修改关系六维（affection/trust/...）
 *   - message：追加 hook_messages 到上下文（不直接发送）
 *   - workflow：本地不支持，记日志
 *   - world_book_add：添加世界书条目
 *   - tool_block：拦截本次 Agent 工具调用（仅 agent.tool.before_call 生效）
 *
 * Hook 执行成功后通过 [events] 推送 [RealtimeEvent.HookNotificationEvent]，
 * 由 ChatViewModel 收集并显示成就式弹窗（与远程模式 `hook_notification` 事件一致）。
 *
 * 触发模式（trigger_mode）：
 *   - always：每次事件都触发
 *   - once_per_conversation：每个会话仅触发一次（按 hookId+conversationId 记录）
 */
class HookExecutor(
    private val db: NekobotDatabase
) {
    companion object {
        private const val TAG = "HookExecutor"
        private const val PREF_NAME = "hook_triggered_keys"
        private const val PREF_KEY = "triggered_keys"
    }

    private val gson = Gson()
    private val hookDao get() = db.hookDao()
    private val hookLogDao get() = db.hookLogDao()
    private val memoryDao get() = db.memoryDao()
    private val worldBookDao get() = db.worldBookDao()
    private val stateDao get() = db.characterStateDao()
    private val relationshipDao get() = db.relationshipDao()

    /** Hook 执行事件流（UI 收集显示成就式弹窗） */
    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    /**
     * 已触发过的 hook（once_per_conversation 模式用），key = hookId|conversationId。
     * 同时持久化到 SharedPreferences（应用重启后仍能保持"每会话一次"语义）。
     */
    private val triggeredKeys = ConcurrentHashMap<String, Boolean>()

    /** SharedPreferences 用于持久化触发记录 */
    private val prefs: android.content.SharedPreferences? by lazy {
        com.nekobot.app.ServiceContainer.appContext?.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
    }

    init {
        // 从 SharedPreferences 加载已触发记录
        loadTriggeredKeys()
    }

    /** 从 SharedPreferences 加载已触发记录到内存 */
    private fun loadTriggeredKeys() {
        try {
            val json = prefs?.getString(PREF_KEY, null) ?: return
            val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
            val keys: Set<String> = gson.fromJson(json, type) ?: return
            for (key in keys) {
                triggeredKeys[key] = true
            }
            LocalLogger.d(TAG, R.string.log_hook_loaded_records, keys.size)
        } catch (e: Exception) {
            LocalLogger.w(TAG, R.string.log_hook_load_records_failed, e.message)
        }
    }

    /** 持久化当前触发记录到 SharedPreferences */
    private fun persistTriggeredKeys() {
        try {
            val json = gson.toJson(triggeredKeys.keys.toSet())
            prefs?.edit()?.putString(PREF_KEY, json)?.apply()
        } catch (e: Exception) {
            LocalLogger.w(TAG, R.string.log_hook_persist_records_failed, e.message)
        }
    }

    /**
     * 触发指定事件的所有 hook。
     *
     * @param eventType 事件类型，如 "character.before_turn.finished"
     * @param conversationId 会话 ID
     * @param characterId 角色 ID（可空）
     * @param ctx 上下文（含 state/relationship/promptStack 等，供 action 读写）
     */
    suspend fun triggerEvent(
        eventType: String,
        conversationId: String,
        characterId: String? = null,
        ctx: HookContext = HookContext()
    ): HookTriggerResult = withContext(Dispatchers.IO) {
        val hooks = try {
            hookDao.listEnabled()
        } catch (e: Exception) {
            LocalLogger.w(TAG, R.string.log_hook_load_hooks_failed, e.message)
            return@withContext HookTriggerResult()
        }

        // 附加会话/角色上下文
        val effCtx = ctx.copy(
            conversationId = conversationId,
            characterId = characterId ?: ctx.characterId,
            eventType = eventType
        )

        // 按事件匹配（支持通配符，如 character.* 匹配 character.before_turn.finished）
        // 并应用作用域过滤：character/conversation/user 作用域的 hook 只在上下文匹配时触发。
        val matched = hooks.map { it.toHook() }.filter { hook ->
            hook.enabled && matchesEvent(hook.event, eventType) && matchesScope(hook, effCtx)
        }
        if (matched.isEmpty()) return@withContext HookTriggerResult()

        var executed = 0

        for (hook in matched.sortedBy { it.priority }) {
            // once_per_conversation 模式：检查是否已触发
            if (hook.triggerMode == "once_per_conversation") {
                val key = "${hook.id}|$conversationId"
                if (triggeredKeys.containsKey(key)) continue
            }

            val hookId = hook.id ?: continue
            val t0 = System.currentTimeMillis()

            // 评估条件
            val conditionsMet = try {
                evaluateConditions(hook.conditions, effCtx, hook.conditionLogic)
            } catch (e: Exception) {
                LocalLogger.w(TAG, R.string.log_hook_condition_error, hook.name, e.message)
                recordHookLog(
                    hookId = hookId,
                    eventType = eventType,
                    status = "failed",
                    actionsExecuted = 0,
                    error = "条件评估异常: ${e.message}",
                    durationMs = (System.currentTimeMillis() - t0).toInt(),
                    conversationId = conversationId
                )
                continue
            }
            if (!conditionsMet) continue

            // 执行 actions
            val results = mutableListOf<ActionResult>()
            for (actionJson in hook.actions) {
                val result = try {
                    executeAction(actionJson, hook, effCtx)
                } catch (e: Exception) {
                    LocalLogger.e(TAG, R.string.log_hook_action_error, hook.name, e.message, throwable = e)
                    ActionResult(false, actionJson.safeType(), e.message ?: "执行异常")
                }
                results.add(result)
            }

            val successCount = results.count { it.success }
            val status = when {
                successCount == results.size -> "success"
                successCount == 0 -> "failed"
                else -> "partial"
            }
            val durationMs = (System.currentTimeMillis() - t0).toInt()
            val firstError = results.firstOrNull { !it.success }?.message

            // 无条件写入执行日志（含 failed），供 HooksScreen "查看日志"展示
            recordHookLog(
                hookId = hookId,
                eventType = eventType,
                status = status,
                actionsExecuted = results.size,
                error = firstError,
                durationMs = durationMs,
                conversationId = conversationId
            )

            // 仅在 success/partial 时触发通知（与后端 _notify_frontend 行为一致）
            if (status == "success" || status == "partial") {
                executed += 1
                if (hook.triggerMode == "once_per_conversation") {
                    triggeredKeys["${hook.id}|$conversationId"] = true
                    persistTriggeredKeys()
                }
                val displayMessage = extractDisplayMessage(hook)
                val notif = HookNotification(
                    hookId = hook.id,
                    hookName = hook.name,
                    eventType = eventType,
                    conversationId = conversationId,
                    status = status,
                    displayMessage = displayMessage
                )
                _events.tryEmit(RealtimeEvent.HookNotificationEvent(notif))
                LocalLogger.i(TAG, R.string.log_hook_triggered, hook.name, eventType, displayMessage)
            }
        }

        HookTriggerResult(
            matchedHooks = matched.size,
            executedHooks = executed,
            blockReason = effCtx.blockReason,
            notices = effCtx.notices.toList()
        )
    }

    /** 重置会话的 once_per_conversation 记录（用于会话重置场景） */
    fun resetConversation(conversationId: String) {
        val removed = triggeredKeys.keys.filter { it.endsWith("|$conversationId") }
        if (removed.isEmpty()) return
        removed.forEach { triggeredKeys.remove(it) }
        persistTriggeredKeys()
        LocalLogger.i(TAG, R.string.log_hook_records_reset, conversationId, removed.size)
    }

    /**
     * 直接执行指定 hook 的 actions（绕过数据库查询和条件评估）。
     *
     * 供 testHook API 调用：用户在 HooksScreen 点击"测试"时，构造临时 Hook 直接执行，
     * 无需先保存到数据库。执行成功后同样触发 [RealtimeEvent.HookNotificationEvent]。
     */
    suspend fun triggerHookDirectly(
        hook: Hook,
        conversationId: String,
        characterId: String? = null,
        ctx: HookContext = HookContext()
    ) = withContext(Dispatchers.IO) {
        val effCtx = ctx.copy(
            conversationId = conversationId,
            characterId = characterId ?: ctx.characterId,
            eventType = "test"
        )

        val t0 = System.currentTimeMillis()
        val results = mutableListOf<ActionResult>()
        for (actionJson in hook.actions) {
            val result = try {
                executeAction(actionJson, hook, effCtx)
            } catch (e: Exception) {
                LocalLogger.e(TAG, R.string.log_hook_action_error, hook.name, e.message, throwable = e)
                ActionResult(false, actionJson.safeType(), e.message ?: "执行异常")
            }
            results.add(result)
        }

        val successCount = results.count { it.success }
        val status = when {
            successCount == results.size -> "success"
            successCount == 0 -> "failed"
            else -> "partial"
        }
        val durationMs = (System.currentTimeMillis() - t0).toInt()
        val firstError = results.firstOrNull { !it.success }?.message

        // 测试触发也写入日志，方便用户在"查看日志"中立即看到结果
        hook.id?.let { hid ->
            recordHookLog(
                hookId = hid,
                eventType = "test",
                status = status,
                actionsExecuted = results.size,
                error = firstError,
                durationMs = durationMs,
                conversationId = conversationId
            )
        }

        if (status == "success" || status == "partial") {
            val displayMessage = extractDisplayMessage(hook)
            val notif = HookNotification(
                hookId = hook.id,
                hookName = hook.name,
                eventType = "test",
                conversationId = conversationId,
                status = status,
                displayMessage = displayMessage
            )
            _events.tryEmit(RealtimeEvent.HookNotificationEvent(notif))
            LocalLogger.i(TAG, R.string.log_hook_test_triggered, hook.name, displayMessage)
        }
    }

    /**
     * 写入一条 hook 执行日志到 DB，并自动截断到最近 200 条（防止无限增长）。
     * 无条件写入（含 failed），供 HooksScreen "查看日志"功能展示。
     */
    private suspend fun recordHookLog(
        hookId: String,
        eventType: String,
        status: String,
        actionsExecuted: Int,
        error: String?,
        durationMs: Int,
        conversationId: String
    ) {
        try {
            val log = LocalHookLogEntity(
                id = UUID.randomUUID().toString(),
                hookId = hookId,
                eventId = null,
                status = status,
                actionsExecuted = actionsExecuted,
                error = error,
                durationMs = durationMs,
                conversationId = conversationId,
                eventType = eventType,
                createdAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )
            hookLogDao.insert(log)
            // 每个 hook 保留最近 200 条日志
            hookLogDao.trimByHook(hookId, keep = 200)
        } catch (e: Exception) {
            LocalLogger.w(TAG, R.string.log_hook_log_write_failed, e.message)
        }
    }

    // ============== 事件匹配 ==============

    /** 支持通配符：character.* 匹配 character.before_turn.finished */
    private fun matchesEvent(pattern: String, event: String): Boolean {
        if (pattern.isBlank()) return false
        if (pattern == event) return true
        if (pattern.endsWith(".*")) {
            val prefix = pattern.dropLast(2)
            return event == prefix || event.startsWith("$prefix.")
        }
        if (pattern == "*") return true
        return false
    }

    /**
     * 作用域过滤。
     *
     * - global：总是匹配
     * - character：hook.characterId 为空或等于当前角色
     * - conversation：hook.conversationId 为空或等于当前会话
     * - user：hook.userId 为空或等于当前用户
     */
    private fun matchesScope(hook: Hook, ctx: HookContext): Boolean = when (hook.scope.lowercase()) {
        "character" -> hook.characterId.isNullOrBlank() || hook.characterId == ctx.characterId
        "conversation" -> hook.conversationId.isNullOrBlank() || hook.conversationId == ctx.conversationId
        "user" -> hook.userId.isNullOrBlank() || hook.userId == ctx.userId
        else -> true
    }

    // ============== 条件评估 ==============

    /**
     * 评估 hook 条件。
     *
     * 支持的字段（来自 HookTemplates.kt 与 HookEventCatalog）：
     *   - affection_gte / affection_lte：好感度阈值
     *   - trust_gte / trust_lte：信任阈值
     *   - energy_lte / energy_gte：精力阈值
     *   - familiarity_gte / dependency_gte / security_gte / jealousy_gte
     *   - mood_equals：心情字符串相等
     *   - tool_name_equals / tool_name_in / tool_name_contains：Agent 工具名匹配
     *   - iteration_gte / iteration_lte：Agent 工具循环轮次
     *   - tool_success：工具结果是否成功（true/false）
     *   - model_equals：模型名相等
     *   - message_contains / response_contains：用户输入 / 助手回复包含指定子串
     *   - subagent：是否来自子代理（true/false）
     *
     * condition_logic: and（默认，全部满足）/ or（任一满足）
     */
    private suspend fun evaluateConditions(
        conditions: JsonElement?,
        ctx: HookContext,
        conditionLogic: String? = null
    ): Boolean {
        if (conditions == null || conditions.isJsonNull) return true
        val obj = conditions.asJsonObject ?: return true
        if (obj.size() == 0) return true

        val rel = ctx.relationship
        val state = ctx.state
        val results = mutableListOf<Boolean>()

        obj.entrySet().forEach { (key, value) ->
            val passed = when {
                key.endsWith("_gte") -> {
                    val field = key.removeSuffix("_gte")
                    val threshold = value.asNumber.toInt()
                    when (field) {
                        "affection" -> (rel?.affection ?: 0) >= threshold
                        "trust" -> (rel?.trust ?: 0) >= threshold
                        "familiarity" -> (rel?.familiarity ?: 0) >= threshold
                        "dependency" -> (rel?.dependency ?: 0) >= threshold
                        "security" -> (rel?.security ?: 0) >= threshold
                        "jealousy" -> (rel?.jealousy ?: 0) >= threshold
                        "energy" -> (state?.energy ?: 0) >= threshold
                        "iteration" -> ctx.iteration >= threshold
                        else -> true
                    }
                }
                key.endsWith("_lte") -> {
                    val field = key.removeSuffix("_lte")
                    val threshold = value.asNumber.toInt()
                    when (field) {
                        "affection" -> (rel?.affection ?: 0) <= threshold
                        "trust" -> (rel?.trust ?: 0) <= threshold
                        "familiarity" -> (rel?.familiarity ?: 0) <= threshold
                        "dependency" -> (rel?.dependency ?: 0) <= threshold
                        "security" -> (rel?.security ?: 0) <= threshold
                        "jealousy" -> (rel?.jealousy ?: 0) <= threshold
                        "energy" -> (state?.energy ?: 0) <= threshold
                        "iteration" -> ctx.iteration <= threshold
                        else -> true
                    }
                }
                key == "mood_equals" -> state?.mood == value.asString
                key == "tool_name_equals" -> ctx.toolName == value.asString
                key == "tool_name_in" -> ctx.toolName != null && value.asStringList().contains(ctx.toolName)
                key == "tool_name_contains" ->
                    ctx.toolName?.contains(value.asString, ignoreCase = true) == true
                key == "tool_success" -> toolSucceeded(ctx.toolResult) == value.asBoolean
                key == "model_equals" -> ctx.modelName == value.asString
                key == "message_contains" -> ctx.messageText.contains(value.asString, ignoreCase = true)
                key == "response_contains" -> ctx.assistantContent.contains(value.asString, ignoreCase = true)
                key == "subagent" -> ctx.subagent == value.asBoolean
                else -> true  // 未知条件字段忽略
            }
            results.add(passed)
        }

        val logic = conditionLogic?.takeIf { it.isNotBlank() } ?: ctx.conditionLogic
        return if (results.isEmpty()) true
        else if (logic == "or") results.any { it }
        else results.all { it }
    }

    /** 工具结果是否视为成功（缺省视为成功）。 */
    private fun toolSucceeded(result: Map<String, Any>?): Boolean {
        val value = result?.get("success") ?: return true
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true)
            else -> value.toString().toBoolean()
        }
    }

    /** 条件值转为字符串列表：既接受 JSON 数组，也接受逗号分隔的单个字符串。 */
    private fun JsonElement.asStringList(): List<String> {
        if (isJsonNull) return emptyList()
        if (isJsonArray) return asJsonArray.mapNotNull { it.takeUnless(JsonElement::isJsonNull)?.asString }
        return asString.split(',').map { it.trim() }.filter(String::isNotBlank)
    }

    // ============== Action 执行 ==============

    private suspend fun executeAction(
        actionJson: JsonElement,
        hook: Hook,
        ctx: HookContext
    ): ActionResult {
        if (!actionJson.isJsonObject) {
            return ActionResult(false, "unknown", "action 不是 JSON 对象")
        }
        val action = actionJson.asJsonObject
        val type = action.get("type")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (type.isBlank()) return ActionResult(false, "unknown", "缺少 type 字段")

        return when (type) {
            "log" -> actionLog(action, hook, ctx)
            "notify" -> actionNotify(action, hook, ctx)
            "memory_write" -> actionMemoryWrite(action, hook, ctx)
            "prompt_inject" -> actionPromptInject(action, hook, ctx)
            "state_delta" -> actionStateDelta(action, hook, ctx)
            "relationship_delta" -> actionRelationshipDelta(action, hook, ctx)
            "message" -> actionMessage(action, hook, ctx)
            "workflow" -> {
                LocalLogger.w(TAG, R.string.log_hook_workflow_unsupported, hook.name)
                ActionResult(true, type, "本地模式跳过 workflow")
            }
            "world_book_add" -> actionWorldBookAdd(action, hook, ctx)
            "tool_block" -> actionToolBlock(action, hook, ctx)
            else -> {
                LocalLogger.w(TAG, R.string.log_hook_unknown_action, type, hook.name)
                ActionResult(false, type, "未知 action type")
            }
        }
    }

    /**
     * notify：推送一条聊天内联提示。
     *
     * 与 log 的区别：notify 会显式控制提示正文与级别（title/level），
     * 适合"Agent 任务完成"这类需要用户看到的提示；log 只写调试日志。
     */
    private fun actionNotify(action: JsonObject, hook: Hook, ctx: HookContext): ActionResult {
        val message = action.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (message.isBlank()) return ActionResult(false, "notify", "提示内容为空")
        val title = action.get("title")?.takeUnless { it.isJsonNull }?.asString
        val text = if (title.isNullOrBlank() || title == hook.name) message else "$title：$message"
        ctx.notices.add(text)
        LocalLogger.i(TAG, R.string.log_hook_notify, hook.name, text.take(60))
        return ActionResult(true, "notify", text)
    }

    /**
     * tool_block：拦截本次 Agent 工具调用。
     *
     * 只对 agent.tool.before_call 生效（含 `agent.*` 通配）。拦截原因会写回
     * [HookContext.blockReason]，由工具循环转成一次失败的工具结果返回给模型。
     */
    private fun actionToolBlock(action: JsonObject, hook: Hook, ctx: HookContext): ActionResult {
        if (!HookEventCatalog.isBlockableEvent(ctx.eventType)) {
            return ActionResult(false, "tool_block", "tool_block 仅支持 agent.tool.before_call")
        }
        val reason = action.get("reason")?.takeUnless { it.isJsonNull }?.asString
            ?: action.get("message")?.takeUnless { it.isJsonNull }?.asString
            ?: "已被 Hook「${hook.name}」拦截"
        ctx.blockReason = reason
        LocalLogger.i(TAG, R.string.log_hook_tool_blocked, hook.name, ctx.toolName ?: "?", reason)
        return ActionResult(true, "tool_block", reason)
    }

    /** log：写日志（不直接弹窗，弹窗由整体 hook 触发） */
    private fun actionLog(action: JsonObject, hook: Hook, ctx: HookContext): ActionResult {
        val level = action.get("level")?.takeUnless { it.isJsonNull }?.asString ?: "info"
        val message = action.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        when (level.lowercase()) {
            "warning", "warn" -> LocalLogger.w(TAG, "[Hook:${hook.name}] $message")
            "error" -> LocalLogger.e(TAG, "[Hook:${hook.name}] $message")
            "debug" -> LocalLogger.d(TAG, "[Hook:${hook.name}] $message")
            else -> LocalLogger.i(TAG, "[Hook:${hook.name}] $message")
        }
        return ActionResult(true, "log", message)
    }

    /** memory_write：写入角色记忆到 MemoryDao（按 memoryfs 路径规范写入） */
    private suspend fun actionMemoryWrite(
        action: JsonObject,
        hook: Hook,
        ctx: HookContext
    ): ActionResult {
        val characterId = ctx.characterId ?: return ActionResult(false, "memory_write", "无角色 ID")
        val targetId = ctx.targetId.ifEmpty { "local-user" }
        val conversationId = ctx.conversationId.ifBlank { "general" }
        val title = action.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val content = action.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val summary = action.get("summary")?.takeUnless { it.isJsonNull }?.asString
            ?: content.take(100)
        val category = action.get("category")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val importance = action.get("importance")?.takeUnless { it.isJsonNull }
            ?.asNumber?.toFloat() ?: 0.5f
        // importance 0.0-1.0 → 1-10
        val importanceInt = (importance * 10).toInt().coerceIn(1, 10)
        val memType = action.get("mem_type")?.takeUnless { it.isJsonNull }?.asString ?: "long"

        // 对齐 AutoMemory.buildMemoryPath：按 category 分发到不同 memoryfs 路径
        val memoryPath = buildMemoryPath(category, characterId, targetId, conversationId)
        val now = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
        // version：同 path 累积（对齐 AutoMemory.nextVersionForPath）
        val version = try {
            (memoryDao.listByPath(memoryPath).maxOfOrNull { it.version } ?: 0) + 1
        } catch (e: Exception) { 1 }

        val entity = LocalCharacterMemoryEntity(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            targetId = targetId,
            type = memType,
            category = category.ifBlank { "legacy" },
            title = title,
            summary = summary,
            content = content,
            importance = importanceInt,
            createdAt = now,
            memoryPath = memoryPath,
            version = version,
            updatedAt = now,
            conversationId = conversationId
        )
        try {
            if (isSingleSlotMemoryCategory(category)) {
                // 与 AutoMemory 一致：单槽类别始终替换，顺便收敛历史重复记录。
                memoryDao.replaceByCharacterTargetAndCategory(entity)
            } else {
                memoryDao.upsert(entity)
            }
            // important_event 同步派生 timeline 条目（对齐 AutoMemory 行为）
            if (category == "important_event") {
                val ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                val timelineContent = "[$ts] [conv:$conversationId] $title: ${content.take(120)}"
                val timelinePath = buildMemoryPath("timeline", characterId, "timeline", conversationId)
                val tlEntity = LocalCharacterMemoryEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = characterId,
                    targetId = "timeline",
                    type = "long",
                    category = "timeline",
                    title = title,
                    summary = title,
                    content = timelineContent,
                    importance = importanceInt,
                    createdAt = now,
                    memoryPath = timelinePath,
                    version = 1,
                    updatedAt = now,
                    conversationId = conversationId
                )
                memoryDao.upsert(tlEntity)
                memoryDao.trimByCharacterAndCategory(characterId, "timeline", keep = 80)
            }
            LocalLogger.i(TAG, R.string.log_hook_memory_write, hook.name, title, category, memoryPath)
            return ActionResult(true, "memory_write", "已写入记忆: $title")
        } catch (e: Exception) {
            return ActionResult(false, "memory_write", "写入记忆失败: ${e.message}")
        }
    }

    /**
     * 构造 memoryfs 逻辑路径（与 AutoMemory.buildMemoryPath 保持一致）。
     * Hook 写入的记忆需走相同路径规范，才能在 MemoryFS 注入时被正确读取。
     */
    private fun buildMemoryPath(category: String, characterId: String, targetId: String, conversationId: String): String {
        return when (category) {
            "user_persona" -> "characters/$characterId/users/$targetId/user_persona.md"
            "character_persona" -> "characters/$characterId/users/$targetId/character_persona.md"
            "important_event" -> "characters/$characterId/events/$conversationId.md"
            "timeline" -> "characters/$characterId/timeline.md"
            "life_sim" -> "characters/$characterId/life_sim/$conversationId.md"
            "recent_digest" -> "characters/$characterId/users/$targetId/recent_digest.md"
            else -> "characters/$characterId/users/$targetId/legacy.md"
        }
    }

    /** prompt_inject：向 PromptStack 注入额外提示词 */
    private fun actionPromptInject(action: JsonObject, hook: Hook, ctx: HookContext): ActionResult {
        val stack = ctx.promptStack ?: return ActionResult(false, "prompt_inject", "无 PromptStack")
        val content = action.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (content.isBlank()) return ActionResult(false, "prompt_inject", "注入内容为空")
        val key = action.get("key")?.takeUnless { it.isJsonNull }?.asString
            ?: "hook_${(hook.id ?: "unknown").take(8)}"
        val priority = action.get("priority")?.takeUnless { it.isJsonNull }?.asNumber?.toInt() ?: 55
        val scope = action.get("scope")?.takeUnless { it.isJsonNull }?.asString ?: "turn"
        stack.add(key, content, priority = priority, scope = scope)
        LocalLogger.i(TAG, R.string.log_hook_prompt_inject, hook.name, key)
        return ActionResult(true, "prompt_inject", "已注入: $key")
    }

    /** state_delta：修改角色状态字段（mood/energy/mood_intensity） */
    private suspend fun actionStateDelta(
        action: JsonObject,
        hook: Hook,
        ctx: HookContext
    ): ActionResult {
        val state = ctx.state ?: return ActionResult(false, "state_delta", "无角色状态")
        val characterId = ctx.characterId ?: return ActionResult(false, "state_delta", "无角色 ID")
        val scopeId = ctx.conversationId

        // 支持两种格式：{field, delta} 或 {payload: {field: delta, ...}}
        val payload = if (action.has("payload")) {
            action.get("payload").asJsonObject
        } else {
            action
        }

        // 应用变更（直接修改内存中的 state 对象）
        for ((field, value) in payload.entrySet()) {
            if (field == "type" || field == "payload") continue
            when (field) {
                "mood" -> state.mood = value.asString
                "mood_intensity" -> {
                    val delta = value.asFloat
                    state.moodIntensity = (state.moodIntensity + delta).coerceIn(0f, 1f)
                }
                "energy" -> {
                    val delta = value.asInt
                    state.energy = (state.energy + delta).coerceIn(0, 100)
                }
                else -> LocalLogger.w(TAG, R.string.log_hook_state_delta_unknown_field, field)
            }
        }

        // 持久化：dataJson 存放完整 CharacterState JSON
        try {
            val existing = stateDao.get(characterId, scopeId)
            val updated = (existing ?: com.nekobot.app.data.local.db.LocalCharacterStateEntity(
                id = "$characterId:$scopeId",
                characterId = characterId,
                scopeId = scopeId,
                dataJson = state.toJson(),
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )).copy(
                dataJson = state.toJson(),
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )
            stateDao.upsert(updated)
            LocalLogger.i(TAG, R.string.log_hook_state_updated, hook.name, state.mood, state.energy)
            return ActionResult(true, "state_delta", "状态已更新")
        } catch (e: Exception) {
            return ActionResult(false, "state_delta", "状态保存失败: ${e.message}")
        }
    }

    /** relationship_delta：修改关系六维（affection/trust/familiarity/dependency/security/jealousy） */
    private suspend fun actionRelationshipDelta(
        action: JsonObject,
        hook: Hook,
        ctx: HookContext
    ): ActionResult {
        val rel = ctx.relationship ?: return ActionResult(false, "relationship_delta", "无关系状态")
        val characterId = ctx.characterId ?: return ActionResult(false, "relationship_delta", "无角色 ID")
        val targetId = ctx.targetId.ifEmpty { "local-user" }

        val payload = if (action.has("payload")) {
            action.get("payload").asJsonObject
        } else {
            action
        }

        for ((field, value) in payload.entrySet()) {
            if (field == "type" || field == "payload") continue
            val delta = value.asInt
            when (field) {
                "affection" -> rel.affection = (rel.affection + delta).coerceIn(0, 100)
                "trust" -> rel.trust = (rel.trust + delta).coerceIn(0, 100)
                "familiarity" -> rel.familiarity = (rel.familiarity + delta).coerceIn(0, 100)
                "dependency" -> rel.dependency = (rel.dependency + delta).coerceIn(0, 100)
                "security" -> rel.security = (rel.security + delta).coerceIn(0, 100)
                "jealousy" -> rel.jealousy = (rel.jealousy + delta).coerceIn(0, 100)
                else -> LocalLogger.w(TAG, R.string.log_hook_rel_delta_unknown_field, field)
            }
        }

        // 持久化：dataJson 存放完整 RelationshipState JSON
        try {
            val existing = relationshipDao.get(characterId, targetId)
            val updated = (existing ?: com.nekobot.app.data.local.db.LocalRelationshipStateEntity(
                id = "$characterId:$targetId",
                characterId = characterId,
                targetId = targetId,
                dataJson = rel.toJson(),
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )).copy(
                dataJson = rel.toJson(),
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )
            relationshipDao.upsert(updated)
            LocalLogger.i(TAG, R.string.log_hook_rel_updated, hook.name, rel.affection, rel.trust)
            return ActionResult(true, "relationship_delta", "关系已更新")
        } catch (e: Exception) {
            return ActionResult(false, "relationship_delta", "关系保存失败: ${e.message}")
        }
    }

    /** message：追加 hook_messages 到上下文（不直接发送到聊天） */
    private fun actionMessage(action: JsonObject, hook: Hook, ctx: HookContext): ActionResult {
        val content = action.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (content.isBlank()) return ActionResult(false, "message", "消息内容为空")
        ctx.hookMessages.add(content)
        LocalLogger.i(TAG, R.string.log_hook_context_appended, hook.name, content.take(40))
        return ActionResult(true, "message", "已追加消息")
    }

    /** world_book_add：添加世界书条目 */
    private suspend fun actionWorldBookAdd(
        action: JsonObject,
        hook: Hook,
        ctx: HookContext
    ): ActionResult {
        val characterId = ctx.characterId ?: return ActionResult(false, "world_book_add", "无角色 ID")
        val name = action.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val content = action.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val priority = action.get("priority")?.takeUnless { it.isJsonNull }?.asNumber?.toInt() ?: 50
        val alwaysOn = action.get("always_on")?.takeUnless { it.isJsonNull }?.asBoolean == true

        // 找到或创建角色的世界书
        val books = worldBookDao.listByCharacter(characterId)
        val book = books.firstOrNull() ?: try {
            val newBook = LocalWorldBookEntity(
                id = UUID.randomUUID().toString(),
                name = "Hook 自动创建",
                characterId = characterId,
                enabled = true,
                createdAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic(),
                updatedAt = com.nekobot.app.data.local.LocalRepository.nowIsoStatic()
            )
            worldBookDao.upsert(newBook)
            newBook
        } catch (e: Exception) {
            return ActionResult(false, "world_book_add", "创建世界书失败: ${e.message}")
        }

        val keysJson = action.get("keywords")?.let { gson.toJson(it) }
        val entry = LocalWorldBookEntryEntity(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            keys = keysJson,
            content = content,
            comment = name,
            enabled = true,
            constant = alwaysOn,
            priority = priority
        )
        try {
            worldBookDao.upsertEntry(entry)
            LocalLogger.i(TAG, R.string.log_hook_worldbook_added, hook.name, name)
            return ActionResult(true, "world_book_add", "已添加: $name")
        } catch (e: Exception) {
            return ActionResult(false, "world_book_add", "添加条目失败: ${e.message}")
        }
    }

    // ============== 辅助 ==============

    /** 提取显示消息：优先 notify.message，其次 log.message，再 message.content，最后回退 hookName */
    private fun extractDisplayMessage(hook: Hook): String {
        for (action in hook.actions) {
            if (!action.isJsonObject) continue
            val obj = action.asJsonObject
            val type = obj.get("type")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (type == "notify") {
                val msg = obj.get("message")?.takeUnless { it.isJsonNull }?.asString
                if (!msg.isNullOrBlank()) return msg
            }
            if (type == "log") {
                val msg = obj.get("message")?.takeUnless { it.isJsonNull }?.asString
                if (!msg.isNullOrBlank()) return msg
            }
            if (type == "message") {
                val content = obj.get("content")?.takeUnless { it.isJsonNull }?.asString
                if (!content.isNullOrBlank()) return content
            }
        }
        return hook.name.ifBlank { "Hook" }
    }

    private fun JsonElement.safeType(): String =
        if (isJsonObject) asJsonObject.get("type")?.asString ?: "unknown" else "unknown"

    private fun com.nekobot.app.data.local.db.LocalHookEntity.toHook(): Hook = Hook(
        id = id,
        name = name,
        event = event,
        actions = runCatching { JsonParser.parseString(actionsJson).asJsonArray.map { it } }.getOrDefault(emptyList()),
        description = description,
        enabled = enabled,
        scope = scope,
        priority = priority,
        conditions = conditionsJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        permissions = permissionsJson?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        triggerMode = triggerMode,
        conditionLogic = conditionLogic,
        characterId = characterId,
        conversationId = conversationId,
        userId = userId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

/**
 * Hook 执行上下文。传递 state/relationship/promptStack 等，供 action 读写。
 *
 * Agent 事件额外携带工具名/参数/结果与迭代轮次，条件可据此过滤；
 * [blockReason] 是可变回传字段，由 tool_block 动作写入。
 */
data class HookContext(
    val eventType: String = "",
    val conversationId: String = "",
    val characterId: String? = null,
    val targetId: String = "local-user",
    val userId: String = "local-user",
    val state: com.nekobot.app.data.local.ai.CharacterState? = null,
    val relationship: com.nekobot.app.data.local.ai.RelationshipState? = null,
    val promptStack: PromptStack? = null,
    val conditionLogic: String = "and",
    val hookMessages: MutableList<String> = mutableListOf(),
    // ---- 事件载荷 ----
    /** 本轮用户输入（message.before_send 等事件使用） */
    val userMessage: String = "",
    /** 本轮助手最终回复（message.after_receive / agent.session.end 使用） */
    val assistantContent: String = "",
    /** 实际使用的模型名（model.after_call / agent.* 使用） */
    val modelName: String? = null,
    /** Agent 工具名（agent.tool.* 使用） */
    val toolName: String? = null,
    /** Agent 工具参数（已脱敏） */
    val toolArgs: Map<String, Any> = emptyMap(),
    /** Agent 工具结果 */
    val toolResult: Map<String, Any>? = null,
    /** Agent 工具循环轮次，从 0 开始 */
    val iteration: Int = 0,
    /** 是否来自子代理的工具循环 */
    val subagent: Boolean = false,
    /** notify 动作产生的提示文本 */
    val notices: MutableList<String> = mutableListOf(),
    /** tool_block 动作写入的拦截原因；非空表示拦截本次工具调用 */
    var blockReason: String? = null
) {
    /** 条件匹配用的"当前消息正文"（优先助手回复，其次用户输入）。 */
    val messageText: String get() = assistantContent.ifBlank { userMessage }
}

/** 一次事件触发的汇总结果。 */
data class HookTriggerResult(
    /** 命中的 hook 数 */
    val matchedHooks: Int = 0,
    /** 执行成功/部分成功的 hook 数 */
    val executedHooks: Int = 0,
    /** tool_block 写入的拦截原因（仅 agent.tool.before_call） */
    val blockReason: String? = null,
    /** notify 动作产生的提示文本 */
    val notices: List<String> = emptyList()
)

/** Action 执行结果 */
data class ActionResult(
    val success: Boolean,
    val type: String,
    val message: String
)
