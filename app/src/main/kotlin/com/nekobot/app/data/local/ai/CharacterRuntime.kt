package com.nekobot.app.data.local.ai

import java.time.Instant

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
    private val autoState: AutoState? = null
) {

    /** 世界书存储接口 */
    interface WorldBookStore {
        /** 按角色 ID 和用户消息匹配世界书条目 */
        suspend fun match(characterId: String, userMessage: String, state: CharacterState?, recentMessages: List<String>): List<WorldBookMatch>
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

        // 7. 世界书匹配
        val worldBookEntries = matchWorldBooks(identity, chatRequest.content, state, recentMessages)

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

        val promptText = promptStack.render(basePrompt = buildBasePrompt(profile))

        return CharacterTurnContext(
            profile = profile,
            state = state,
            relationship = relationship,
            memories = memories,
            signals = signals,
            plan = plan,
            promptText = promptText,
            worldBookEntries = worldBookEntries
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

        // 更新 turnContext 中的状态（供后续流程使用）
        turnContext.state = newState
        turnContext.relationship = newRelationship

        // AutoState：LLM 驱动的状态评估（每 2 轮一次）
        if (autoState != null) {
            try {
                val (aiState, aiRel, updated) = autoState.updateStateFromRecentTurns(
                    profile = turnContext.profile,
                    state = newState,
                    relationship = newRelationship,
                    userMessage = chatRequest.content,
                    assistantMessage = finalContent,
                    conversationId = chatRequest.conversationId
                )
                if (updated) {
                    stateRepo?.save(aiState)
                    relationshipRepo?.save(aiRel)
                    turnContext.state = aiState
                    turnContext.relationship = aiRel
                }
            } catch (e: Exception) {
                // AutoState 失败不影响主流程
            }
        }

        // 记忆抽取
        if (memoryService != null) {
            try {
                val response = ChatResponse(finalContent = finalContent)
                memoryService.extractIfNeeded(chatRequest, response, turnContext)
            } catch (e: Exception) {
                // 记忆抽取失败不影响主流程
            }
        }
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
        // 创建初始状态
        val now = Instant.now().toString()
        return CharacterState(
            characterId = identity.characterId,
            scopeId = identity.scopeId,
            mood = "平静",
            moodIntensity = 0.5f,
            energy = 70,
            lastActiveAt = now,
            updatedAt = now
        )
    }

    private suspend fun getOrCreateRelationship(identity: CharacterIdentity, profile: CharacterProfile): RelationshipState {
        relationshipRepo?.let { repo ->
            repo.get(identity.characterId, identity.targetId)?.let { return it }
        }
        // 从角色卡初始状态读取关系初值
        val initial = profile.initialState
        val now = Instant.now().toString()
        fun getInt(key: String, default: Int): Int = (initial[key] as? Number)?.toInt() ?: default

        return RelationshipState(
            characterId = identity.characterId,
            targetId = identity.targetId,
            affection = getInt("affection", 50),
            trust = getInt("trust", 50),
            familiarity = getInt("familiarity", 30),
            dependency = getInt("dependency", 30),
            security = getInt("security", 50),
            jealousy = getInt("jealousy", 0),
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
        recentMessages: List<String>
    ): List<WorldBookMatch> {
        worldBookStore?.let { return it.match(identity.characterId, userMessage, state, recentMessages) }
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
