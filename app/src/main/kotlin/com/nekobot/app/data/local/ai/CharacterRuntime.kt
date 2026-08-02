package com.nekobot.app.data.local.ai

import android.util.Log
import com.nekobot.app.data.local.shouldInjectWorldBooks
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * 角色运行时引擎，对应原仓库 nbot/character/runtime.py。
 *
 * CharacterRuntime 是角色模拟的编排中心，负责：
 * - before_turn: 读取角色卡/状态/关系/记忆，分析信号，生成 ReactionPlan，编译提示词
 * - after_turn: 更新情绪/关系，写入事件，抽取记忆
 *
 * 不直接处理 HTTP / Socket / UI，仅依赖统一请求对象和抽象存储接口。
 *
 * @param profileRepo 角色卡仓库
 * @param stateRepo 状态仓库
 * @param relationshipRepo 关系仓库
 * @param memoryService 记忆服务（可空，阶段4实现）
 * @param worldBookStore 世界书存储（可空，本地模式可复用现有 Room 世界书）
 */
class CharacterRuntime(
    private val profileRepo: ProfileRepository? = null,
    private val stateRepo: CharacterStateRepository? = null,
    private val relationshipRepo: RelationshipRepository? = null,
    private val memoryService: MemoryService? = null,
    private val worldBookStore: WorldBookStore? = null,
    private val autoState: AutoState? = null,
    private val memoryFS: MemoryFS? = null,
    private val snapshotRepo: StateSnapshotRepository? = null,
    private val onAchievementProgress: ((
        com.nekobot.app.data.local.AchievementManager.Target.Metric,
        Long
    ) -> Unit)? = null
) {

    companion object {
        private const val TAG = "CharacterRuntime"
    }

    /** 世界书存储接口 */
    interface WorldBookStore {
        /** 按角色 ID 和用户消息匹配世界书条目 */
        suspend fun match(
            characterId: String,
            userMessage: String,
            state: CharacterState?,
            relationship: RelationshipState?,
            scopeId: String,
            recentMessages: List<String>
        ): List<WorldBookMatch>
    }

    /** 世界书匹配结果 */
    data class WorldBookMatch(
        val content: String,
        val comment: String = "",
        val priority: Int = 0
    )

    /**
     * 每轮对话前的角色模拟编排。
     *
     * 1. 读取角色卡
     * 2. 读取/创建角色运行时状态
     * 3. 读取/创建关系状态
     * 4. 检索相关记忆
     * 5. 分析用户输入信号
     * 6. 生成反应计划
     * 7. 世界书关键词匹配
     * 8. 编译提示词
     *
     * @param chatRequest 聊天请求
     * @param identity 角色身份标识
     * @param recentMessages 最近消息列表（用于世界书多源召回）
     * @return CharacterTurnContext 本轮所有角色上下文
     */
    suspend fun beforeTurn(
        chatRequest: ChatRequest,
        identity: CharacterIdentity,
        recentMessages: List<String> = emptyList()
    ): CharacterTurnContext {
        // 1. 读取角色卡
        val profile = getProfile(identity)

        // 2. 读取或创建状态
        val state = getOrCreateState(identity, profile)
        ensureCurrentActivity(state)

        // 3. 读取或创建关系
        val relationship = getOrCreateRelationship(identity, profile)

        // 4. 检索记忆
        val memories = searchMemories(identity, chatRequest.content)

        // 5. 分析信号
        val signals = SignalAnalyzer.analyze(chatRequest.content, state, relationship)

        // 6. 生成反应计划
        val plan = ReactionPlanner.plan(profile, state, relationship, memories, signals, chatRequest.content)

        // 7. 世界书匹配。Agent 是通用工具会话，即使意外绑定了角色也不注入世界书。
        val worldBookEntries = if (shouldInjectWorldBooks(chatRequest.metadata["session_mode"] as? String)) {
            matchWorldBooks(
                identity,
                chatRequest.content,
                state,
                relationship,
                recentMessages
            )
        } else {
            emptyList()
        }

        // 8. 编译提示词
        val promptStack = PromptStack()
        buildCharacterInjections(
            stack = promptStack,
            profile = profile,
            state = state,
            relationship = relationship,
            memories = memories,
            plan = plan
        )
        // 世界书注入
        if (worldBookEntries.isNotEmpty()) {
            val worldBookText = worldBookEntries
                .sortedBy { it.priority }
                .joinToString("\n\n") { entry ->
                    if (entry.comment.isNotEmpty()) "[${entry.comment}]\n${entry.content}"
                    else entry.content
                }
            promptStack.add("world_book", worldBookText, priority = PromptStack.Priority.WORLD_BOOK)
        }
        // MemoryFS 结构化记忆注入（按类别分组：【用户人格】【角色人格】等）
        if (memoryFS != null) {
            try {
                val memoryContext = memoryFS.buildPromptContext(
                    characterId = identity.characterId,
                    targetId = identity.targetId,
                    conversationId = chatRequest.conversationId
                )
                if (memoryContext.isNotBlank()) {
                    promptStack.add("memory_fs", memoryContext, priority = PromptStack.Priority.CHARACTER_MEMORIES)
                }
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "MemoryFS 提示词注入失败（不影响主流程）: ${e.message}")
            }
        }

        // 应用会话级禁用注入项（从 chatRequest.metadata 读取）
        @Suppress("UNCHECKED_CAST")
        val disabledKeys = (chatRequest.metadata["disabled_prompt_keys"] as? List<String>) ?: emptyList()
        if (disabledKeys.isNotEmpty()) {
            promptStack.disableKeys(disabledKeys)
        }

        val promptText = promptStack.render(basePrompt = buildBasePrompt(profile))

        return CharacterTurnContext(
            profile = profile,
            state = state,
            relationship = relationship,
            memories = memories,
            signals = signals,
            plan = plan,
            promptText = promptText,
            worldBookEntries = worldBookEntries,
            promptStackItems = promptStack.getItems()
        )
    }

    /**
     * 每轮对话后的状态更新。
     *
     * 1. 应用状态变化（状态机）
     * 2. 保存状态和关系
     * 3. 记忆抽取（如配置）
     *
     * @param chatRequest 聊天请求
     * @param finalContent AI 回复文本
     * @param turnContext before_turn 返回的上下文
     */
    suspend fun afterTurn(
        chatRequest: ChatRequest,
        finalContent: String,
        turnContext: CharacterTurnContext
    ) {
        val oldState = turnContext.state
        val oldRelationship = turnContext.relationship

        // 应用状态变化
        val (newState, newRelationship) = StateMachine.apply(
            oldState = oldState,
            oldRelationship = oldRelationship,
            signals = turnContext.signals as? UserSignals,
            plan = turnContext.plan,
            userMessage = chatRequest.content,
            assistantMessage = finalContent
        )

        // 保存状态
        stateRepo?.save(newState)
        relationshipRepo?.save(newRelationship)
        com.nekobot.app.data.local.LocalLogger.i(TAG, "afterTurn 状态已保存: mood=${newState.mood} energy=${newState.energy} " +
            "affection=${newRelationship.affection} trust=${newRelationship.trust}")

        // 更新 turnContext 中的状态（供后续流程使用）
        turnContext.state = newState
        turnContext.relationship = newRelationship

        // AutoState：LLM 驱动的状态评估（每 2 轮一次）
        var triggerType = "state_machine"
        var qualityScores: Map<String, Float>? = null
        if (autoState != null) {
            try {
                val (aiState, aiRel, updated) = autoState.updateStateFromRecentTurns(
                    profile = turnContext.profile,
                    state = newState,
                    relationship = newRelationship,
                    userMessage = chatRequest.content,
                    assistantMessage = finalContent,
                    metadata = chatRequest.metadata,
                    conversationId = chatRequest.conversationId
                )
                if (updated) {
                    stateRepo?.save(aiState)
                    relationshipRepo?.save(aiRel)
                    turnContext.state = aiState
                    turnContext.relationship = aiRel
                    triggerType = "auto_state"
                    qualityScores = autoState.getQualityScores(
                        characterId = turnContext.profile.id.ifEmpty { turnContext.profile.name },
                        targetId = aiRel.targetId,
                        conversationId = chatRequest.conversationId
                    ).takeIf { it.isNotEmpty() }
                    com.nekobot.app.data.local.LocalLogger.i(TAG, "AutoState 已应用 LLM 评估: mood=${aiState.mood} " +
                        "moodIntensity=${aiState.moodIntensity} energy=${aiState.energy}")
                } else {
                    com.nekobot.app.data.local.LocalLogger.d(TAG, "AutoState 本轮未更新（未达触发间隔或无变化）")
                }
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "AutoState 状态评估失败（不影响主流程）: ${e.message}", e)
            }
        }

        relationshipRepo?.let { repo ->
            val highAffectionCharacterCount = repo.countCharactersAtOrAboveAffection(90)
            onAchievementProgress?.invoke(
                com.nekobot.app.data.local.AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS,
                highAffectionCharacterCount.toLong()
            )
        }

        // 写入状态历史快照（供「状态历程」界面呈现随时间演变，并保留本轮对话原文供底部回放）
        if (snapshotRepo != null) {
            try {
                snapshotRepo.append(
                    sessionId = turnContext.state.scopeId,
                    state = turnContext.state,
                    relationship = turnContext.relationship,
                    qualityScores = qualityScores,
                    triggerType = triggerType,
                    userMessage = chatRequest.content,
                    assistantMessage = finalContent
                )
                com.nekobot.app.data.local.LocalLogger.d(TAG, "状态快照已写入 (trigger=$triggerType, hasQuality=${qualityScores != null})")
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "状态快照写入失败（不影响主流程）: ${e.message}", e)
            }
        }

        // 记忆抽取：异步执行，不阻塞 afterTurn 主流程，也不受 viewModelScope 生命周期影响
        // （记忆抽取含 3 次 LLM 重试，可能耗时 30-60 秒，不应因用户退出聊天界面而被取消）
        if (memoryService != null) {
            val response = ChatResponse(finalContent = finalContent)
            val capturedRequest = chatRequest
            val capturedTurn = turnContext
            com.nekobot.app.ServiceContainer.applicationScope.launch {
                try {
                    memoryService.extractIfNeeded(capturedRequest, response, capturedTurn)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // applicationScope 不会被 UI 生命周期取消，仅在 app 进程结束时取消
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "记忆抽取被取消（应用进程退出）: ${e.message}")
                } catch (e: Exception) {
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "记忆抽取失败（不影响主流程）: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 应用对话审查产出的关系增量并保存（对应原仓库 review pipeline 的关系回写）。
     *
     * 合并顺序为「先 StateMachine → 再 AutoState → 再 review 增量」，此方法为链路最后一环。
     * 读取最新持久化的关系（避免覆盖 AutoState 结果），叠加增量后钳制在 0~100 并保存。
     *
     * @param characterId 角色 ID
     * @param targetId 目标（用户）ID
     * @param deltas 六维增量映射（affection/trust/familiarity/dependency/security/jealousy）
     * @return 是否有实际变更并已保存
     */
    suspend fun applyRelationshipDelta(
        characterId: String,
        targetId: String,
        deltas: Map<String, Int>
    ): Boolean {
        val repo = relationshipRepo ?: return false
        if (deltas.values.all { it == 0 }) return false

        val current = repo.get(characterId, targetId) ?: return false
        fun clamp(base: Int, key: String) = (base + (deltas[key] ?: 0)).coerceIn(0, 100)

        val updated = current.copy(
            affection = clamp(current.affection, "affection"),
            trust = clamp(current.trust, "trust"),
            familiarity = clamp(current.familiarity, "familiarity"),
            dependency = clamp(current.dependency, "dependency"),
            security = clamp(current.security, "security"),
            jealousy = clamp(current.jealousy, "jealousy"),
            updatedAt = Instant.now().toString()
        )
        // 无实际变化则跳过保存
        if (updated == current) return false

        repo.save(updated)
        com.nekobot.app.data.local.LocalLogger.i(TAG, "审查关系增量已回写: $deltas → affection=${updated.affection} trust=${updated.trust}")
        // 成就触发：统计好感度达到 90 的不同角色数量。
        val highAffectionCharacterCount = repo.countCharactersAtOrAboveAffection(90)
        onAchievementProgress?.invoke(
            com.nekobot.app.data.local.AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS,
            highAffectionCharacterCount.toLong()
        )
        return true
    }

    // ==================== 内部方法 ====================

    private suspend fun getProfile(identity: CharacterIdentity): CharacterProfile {
        profileRepo?.let { repo ->
            repo.getById(identity.characterId)?.let { return it }
        }
        // 回退：返回空角色卡
        return CharacterProfile(id = identity.characterId)
    }

    private suspend fun getOrCreateState(identity: CharacterIdentity, profile: CharacterProfile): CharacterState {
        stateRepo?.let { repo ->
            repo.get(identity.characterId, identity.scopeId)?.let { return it }
        }
        // 创建初始状态：优先读取角色卡 initial_state 中的 mood/mood_intensity/energy
        // lastActiveAt 留空：首次对话时 real_time.continuity 会判定为 first_contact，
        // 与原仓库 build_current_real_time_context(previous_turn_time="") 行为一致。
        val now = Instant.now().toString()
        val init = profile.initialState
        val mood = (init["mood"] as? String)?.takeIf { it.isNotBlank() } ?: "平静"
        val moodIntensity = (init["mood_intensity"] as? Number)?.toFloat()
            ?: init["mood_intensity"]?.toString()?.toFloatOrNull()
            ?: 0.5f
        val energy = (init["energy"] as? Number)?.toInt()
            ?: init["energy"]?.toString()?.toIntOrNull()
            ?: 70
        return CharacterState(
            characterId = identity.characterId,
            scopeId = identity.scopeId,
            mood = mood,
            moodIntensity = moodIntensity.coerceIn(0f, 1f),
            energy = energy.coerceIn(0, 100),
            lastActiveAt = "",
            updatedAt = now
        )
    }

    private suspend fun getOrCreateRelationship(identity: CharacterIdentity, profile: CharacterProfile): RelationshipState {
        val storageTargetId = identity.relationshipTargetId.ifBlank { identity.targetId }
        relationshipRepo?.let { repo ->
            repo.get(identity.characterId, storageTargetId)?.let { return it }

            // 旧版本按角色 + 用户全局存储。已有会话首次打开时复制为会话级状态，避免升级后丢失关系进度。
            if (storageTargetId != identity.targetId) {
                repo.get(identity.characterId, identity.targetId)?.let { legacy ->
                    val migrated = legacy.copy(
                        characterId = identity.characterId,
                        targetId = storageTargetId,
                        updatedAt = Instant.now().toString()
                    )
                    repo.save(migrated)
                    return migrated
                }
            }
        }
        // 从角色卡初始状态读取关系初值
        val now = Instant.now().toString()
        return relationshipStateFromInitial(
            characterId = identity.characterId,
            targetId = storageTargetId,
            initialState = profile.initialState,
            updatedAt = now
        )
    }

    private suspend fun searchMemories(identity: CharacterIdentity, content: String): List<CharacterMemory> {
        memoryService?.let { return it.search(identity, content, limit = 8) }
        return emptyList()
    }

    private suspend fun matchWorldBooks(
        identity: CharacterIdentity,
        userMessage: String,
        state: CharacterState,
        relationship: RelationshipState,
        recentMessages: List<String>
    ): List<WorldBookMatch> {
        worldBookStore?.let {
            return it.match(
                identity.characterId,
                userMessage,
                state,
                relationship,
                identity.scopeId,
                recentMessages
            )
        }
        return emptyList()
    }

    /** 确保状态中有当前活动信息 */
    private fun ensureCurrentActivity(state: CharacterState) {
        if (state.scene.isEmpty()) {
            state.scene = mapOf("current_activity" to "待机")
        }
    }

    /** 构建基础提示词（角色卡编译） */
    private fun buildBasePrompt(profile: CharacterProfile): String {
        val parts = mutableListOf<String>()

        // systemPrompt 优先
        if (profile.systemPrompt.isNotBlank()) {
            parts.add(profile.systemPrompt)
        }

        // 基本信息
        if (profile.basicInfo.isNotBlank()) {
            parts.add("【基本信息】\n${profile.basicInfo}")
        }
        // 性格
        if (profile.personality.isNotBlank()) {
            parts.add("【性格】\n${profile.personality}")
        }
        // 场景
        if (profile.scenario.isNotBlank()) {
            parts.add("【场景】\n${profile.scenario}")
        }
        // 对话示例
        if (profile.exampleDialogues.isNotBlank()) {
            parts.add("【对话示例】\n${profile.exampleDialogues}")
        }
        // 回复格式
        if (profile.responseFormat.isNotBlank()) {
            parts.add("【回复格式】\n${profile.responseFormat}")
        }
        // 规则
        if (profile.rules.isNotEmpty()) {
            parts.add("【规则】\n${profile.rules.joinToString("\n")}")
        }

        return parts.joinToString("\n\n")
    }
}
