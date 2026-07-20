package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.CharacterStateDao
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalCharacterMemoryEntity
import com.nekobot.app.data.local.db.LocalCharacterStateEntity
import com.nekobot.app.data.local.db.LocalRelationshipStateEntity
import com.nekobot.app.data.local.db.LocalStateSnapshotEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.MemoryDao
import com.nekobot.app.data.local.db.RelationshipDao
import com.nekobot.app.data.local.db.StateSnapshotDao
import com.nekobot.app.data.local.db.CharacterDao
import java.time.Instant
import java.util.UUID

/**
 * Room 实现的角色运行时仓库，对应原仓库 nbot/character/repository.py。
 *
 * 实现 Stage 2 中定义的四个接口，用 Room DAO 持久化角色状态/关系/记忆。
 */

private val repoGson = Gson()

// ============================================================================
// ProfileRepository 实现
// ============================================================================

/**
 * 本地角色卡仓库。
 * 从 LocalCharacterEntity 转换为 CharacterProfile。
 */
class LocalProfileRepository(
    private val characterDao: CharacterDao
) : ProfileRepository {

    override suspend fun getById(id: String): CharacterProfile? {
        val entity = characterDao.getById(id) ?: return null
        return entityToProfile(entity)
    }

    /** 将 LocalCharacterEntity 转换为 CharacterProfile */
    private fun entityToProfile(entity: LocalCharacterEntity): CharacterProfile {
        val data = mutableMapOf<String, Any>(
            "id" to entity.id,
            "name" to entity.name,
            "description" to (entity.description ?: ""),
            "avatar" to (entity.avatar ?: ""),
            "portrait" to (entity.portrait ?: ""),
            "basicInfo" to (entity.basicInfo ?: ""),
            "personality" to (entity.personality ?: ""),
            "scenario" to (entity.scenario ?: ""),
            "firstMessage" to (entity.firstMessage ?: ""),
            "exampleDialogues" to (entity.exampleDialogues ?: ""),
            "responseFormat" to (entity.responseFormat ?: ""),
            "systemPrompt" to (entity.systemPrompt ?: ""),
            "greeting" to (entity.greeting ?: "")
        )
        // tags
        entity.tags?.let {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                data["tags"] = repoGson.fromJson(it, type) ?: emptyList<String>()
            } catch (e: Exception) {
                data["tags"] = emptyList<String>()
            }
        } ?: run { data["tags"] = emptyList<String>() }
        // rules
        entity.rules?.let {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                data["rules"] = repoGson.fromJson(it, type) ?: emptyList<String>()
            } catch (e: Exception) {
                data["rules"] = emptyList<String>()
            }
        } ?: run { data["rules"] = emptyList<String>() }
        // state (initialState)
        entity.state?.let {
            try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                @Suppress("UNCHECKED_CAST")
                data["state"] = (repoGson.fromJson(it, type) as? Map<String, Any>) ?: emptyMap<String, Any>()
            } catch (e: Exception) {
                data["state"] = emptyMap<String, Any>()
            }
        } ?: run { data["state"] = emptyMap<String, Any>() }

        return CharacterProfile.fromPersonalityDict(data)
    }
}

// ============================================================================
// CharacterStateRepository 实现
// ============================================================================

/**
 * 本地角色状态仓库。
 * 使用 JSON 序列化存储 CharacterState。
 */
class LocalCharacterStateRepository(
    private val stateDao: CharacterStateDao
) : CharacterStateRepository {

    override suspend fun get(characterId: String, scopeId: String): CharacterState? {
        val entity = stateDao.get(characterId, scopeId) ?: return null
        return CharacterState.fromJson(entity.dataJson)
    }

    override suspend fun save(state: CharacterState) {
        val now = Instant.now().toString()
        val entity = LocalCharacterStateEntity(
            id = "${state.characterId}:${state.scopeId}",
            characterId = state.characterId,
            scopeId = state.scopeId,
            dataJson = state.toJson(),
            updatedAt = now
        )
        stateDao.upsert(entity)
    }
}

// ============================================================================
// RelationshipRepository 实现
// ============================================================================

/**
 * 本地关系状态仓库。
 * 使用 JSON 序列化存储 RelationshipState。
 */
class LocalRelationshipRepository(
    private val relationshipDao: RelationshipDao
) : RelationshipRepository {

    override suspend fun get(characterId: String, targetId: String): RelationshipState? {
        val entity = relationshipDao.get(characterId, targetId) ?: return null
        return RelationshipState.fromJson(entity.dataJson)
    }

    override suspend fun save(relationship: RelationshipState) {
        val now = Instant.now().toString()
        val entity = LocalRelationshipStateEntity(
            id = "${relationship.characterId}:${relationship.targetId}",
            characterId = relationship.characterId,
            targetId = relationship.targetId,
            dataJson = relationship.toJson(),
            updatedAt = now
        )
        relationshipDao.upsert(entity)
    }
}

// ============================================================================
// StateSnapshotRepository 实现
// ============================================================================

/**
 * 本地状态历史快照仓库。
 * 每轮 after_turn 追加一条，供「状态历程」界面呈现随时间的演变。
 */
class LocalStateSnapshotRepository(
    private val snapshotDao: StateSnapshotDao
) : StateSnapshotRepository {

    override suspend fun append(
        sessionId: String,
        state: CharacterState,
        relationship: RelationshipState,
        qualityScores: Map<String, Float>?,
        triggerType: String
    ) {
        val entity = LocalStateSnapshotEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            characterId = state.characterId,
            targetId = relationship.targetId,
            timestamp = Instant.now().toString(),
            mood = state.mood,
            moodIntensity = state.moodIntensity,
            energy = state.energy,
            affection = relationship.affection,
            trust = relationship.trust,
            familiarity = relationship.familiarity,
            dependency = relationship.dependency,
            security = relationship.security,
            jealousy = relationship.jealousy,
            qualityScoresJson = qualityScores?.takeIf { it.isNotEmpty() }?.let { repoGson.toJson(it) },
            triggerType = triggerType
        )
        snapshotDao.insert(entity)
    }
}

// ============================================================================
// MemoryService 实现
// ============================================================================

/**
 * 本地记忆服务。
 *
 * 提供基于 LIKE 的关键词搜索和简化的自动记忆抽取。
 * 自动记忆抽取每 6 轮触发一次，通过 AI 模型从对话中提取结构化记忆。
 *
 * @param memoryDao 记忆 DAO
 * @param aiClient 本地 AI 客户端（可选，用于记忆抽取）
 * @param aiModelProvider 提供 AI 模型配置（可选）
 */
class LocalMemoryService(
    private val memoryDao: MemoryDao,
    private val aiClient: LocalAiClient? = null,
    private val aiModelProvider: (suspend () -> com.nekobot.app.data.local.db.LocalAiModelEntity?)? = null,
    /** 二级 LLM 调用 token 记账回调：(source, model, inputTokens, outputTokens) */
    private val onTokenUsage: ((String, String, Int, Int) -> Unit)? = null
) : MemoryService {

    companion object {
        private const val TAG = "LocalMemoryService"
    }

    /**
     * 完整版记忆抽取引擎（复用其缓冲累积 / 失败回滚 / 分类归一化 / token 记账）。
     * 仅当 aiClient 存在时可用；search 路径不依赖它。
     */
    private val autoMemory: AutoMemory? by lazy {
        aiClient?.let { AutoMemory(memoryDao, it, aiModelProvider, onTokenUsage) }
    }

    override suspend fun search(identity: CharacterIdentity, content: String, limit: Int): List<CharacterMemory> {
        if (content.isBlank()) {
            return memoryDao.listByCharacterAndTarget(identity.characterId, identity.targetId, limit)
                .map { entityToMemory(it) }
        }
        return memoryDao.search(identity.characterId, identity.targetId, content, limit)
            .map { entityToMemory(it) }
    }

    override suspend fun extractIfNeeded(
        chatRequest: ChatRequest,
        response: ChatResponse,
        turnContext: CharacterTurnContext
    ) {
        val engine = autoMemory ?: run {
            LocalLogger.w(TAG, "记忆抽取跳过：AI 客户端未配置")
            return
        }
        LocalLogger.i(TAG, "extractIfNeeded 入口: characterId=${turnContext.profile.id} sessionId=${chatRequest.conversationId} " +
            "userId=${chatRequest.userId} userMsgLen=${chatRequest.content.length} aiMsgLen=${response.finalContent.length}")
        // 委托给完整版 AutoMemory，统一触发/缓冲/归一化行为。
        // scope 与 AutoState 一致：characterId:sessionId:targetId。
        // userPersona 来自会话配置（SessionDetailScreen 中编辑），传给 AutoMemory 作为玩家身份描述，
        // 让 LLM 从中识别玩家姓名，避免在记忆里写"用户"泛称。
        // 注意：senderName 是 AI 扮演的角色名，不是玩家名，不能用作玩家标签。
        val userPersona = (chatRequest.metadata["user_persona"] as? String).orEmpty()
        engine.extractAndSave(
            characterId = turnContext.profile.id,
            characterName = turnContext.profile.name,
            targetId = chatRequest.userId ?: "local-user",
            sessionId = chatRequest.conversationId,
            userMessage = chatRequest.content,
            assistantMessage = response.finalContent,
            userPersona = userPersona
        )
    }

    /** Entity → CharacterMemory */
    private fun entityToMemory(entity: LocalCharacterMemoryEntity): CharacterMemory {
        val emotionImpact: Map<String, Any> = if (entity.emotionImpact != null) {
            try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                @Suppress("UNCHECKED_CAST")
                repoGson.fromJson(entity.emotionImpact, type) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        } else emptyMap()

        return CharacterMemory(
            id = entity.id,
            characterId = entity.characterId,
            targetId = entity.targetId,
            type = entity.type,
            title = entity.title,
            summary = entity.summary,
            content = entity.content,
            importance = entity.importance,
            emotionImpact = emotionImpact,
            sourceTurnId = entity.sourceTurnId,
            createdAt = entity.createdAt,
            expiresAt = entity.expiresAt
        )
    }

}

// ============================================================================
// WorldBookStore 实现
// ============================================================================

/**
 * 本地世界书存储实现。
 * 从 Room WorldBookDao 加载世界书条目并匹配关键词。
 */
class LocalWorldBookStore(
    private val characterDao: CharacterDao,
    private val worldBookDao: com.nekobot.app.data.local.db.WorldBookDao
) : CharacterRuntime.WorldBookStore {

    override suspend fun match(
        characterId: String,
        userMessage: String,
        state: CharacterState?,
        recentMessages: List<String>
    ): List<CharacterRuntime.WorldBookMatch> {
        val books = worldBookDao.listByCharacter(characterId).filter { it.enabled }
        if (books.isEmpty()) return emptyList()

        // 加载每本书的条目
        val entriesByBook = mutableMapOf<String, List<LocalWorldBookEntryEntity>>()
        for (book in books) {
            val entries = worldBookDao.listEntries(book.id).filter { it.enabled }
            if (entries.isNotEmpty()) entriesByBook[book.id] = entries
        }
        if (entriesByBook.isEmpty()) return emptyList()

        // 构建召回上下文
        val recentMsgMaps = recentMessages.mapIndexed { idx, text ->
            mapOf("role" to if (idx % 2 == 0) "user" else "assistant", "content" to text)
        }
        val context = WorldBookRecallContext(
            latestUserMessage = userMessage,
            recentMessages = recentMsgMaps,
            scene = state?.scene ?: emptyMap(),
            characterId = characterId
        )

        // 使用 V2 多源召回匹配器
        val matchResults = WorldBookMatcher.matchEntriesV2(
            context = context,
            worldBooks = books,
            entriesByBook = entriesByBook,
            characterId = characterId
        )

        return matchResults.map { r ->
            CharacterRuntime.WorldBookMatch(
                content = r.entry.content ?: "",
                comment = r.entry.comment ?: "",
                priority = r.entry.priority
            )
        }
    }
}
