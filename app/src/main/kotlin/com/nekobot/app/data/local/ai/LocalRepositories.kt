package com.nekobot.app.data.local.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.CharacterStateDao
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalCharacterMemoryEntity
import com.nekobot.app.data.local.db.LocalCharacterStateEntity
import com.nekobot.app.data.local.db.LocalRelationshipStateEntity
import com.nekobot.app.data.local.db.MemoryDao
import com.nekobot.app.data.local.db.RelationshipDao
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
    private val aiModelProvider: (suspend () -> com.nekobot.app.data.local.db.LocalAiModelEntity?)? = null
) : MemoryService {

    companion object {
        private const val TAG = "LocalMemoryService"
        /** 记忆抽取频率：每 N 轮对话抽取一次 */
        private const val EXTRACT_INTERVAL = 6
    }

    /** 每个角色的对话轮次计数器（内存中，重启后重置） */
    private val turnCounters = mutableMapOf<String, Int>()
    /** 每个角色的对话缓冲区 */
    private val turnBuffers = mutableMapOf<String, MutableList<Map<String, String>>>()

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
        val key = "${turnContext.profile.id}:${chatRequest.userId ?: "default"}"
        val count = (turnCounters[key] ?: 0) + 1
        turnCounters[key] = count

        // 累积对话缓冲
        val buffer = turnBuffers.getOrPut(key) { mutableListOf() }
        buffer.add(mapOf(
            "user" to chatRequest.content,
            "assistant" to response.finalContent
        ))

        // 每 EXTRACT_INTERVAL 轮抽取一次
        if (count < EXTRACT_INTERVAL) return
        if (buffer.isEmpty()) return

        try {
            val memories = extractMemoriesWithAI(
                characterId = turnContext.profile.id,
                targetId = chatRequest.userId ?: "default",
                characterName = turnContext.profile.name,
                dialogues = buffer.toList()
            )
            if (memories.isNotEmpty()) {
                val entities = memories.map { memoryToEntity(it) }
                memoryDao.upsertAll(entities)
                Log.d(TAG, "抽取并保存 ${memories.size} 条记忆 (key=$key)")
            }
            // 清空缓冲区
            buffer.clear()
            turnCounters[key] = 0
        } catch (e: Exception) {
            Log.w(TAG, "记忆抽取失败: ${e.message}")
        }
    }

    /** 使用 AI 模型从对话中抽取记忆 */
    private suspend fun extractMemoriesWithAI(
        characterId: String,
        targetId: String,
        characterName: String,
        dialogues: List<Map<String, String>>
    ): List<CharacterMemory> {
        val client = aiClient ?: return emptyList()
        val model = aiModelProvider?.invoke() ?: return emptyList()

        val dialogText = dialogues.joinToString("\n") { d ->
            "用户: ${d["user"] ?: ""}\n$characterName: ${d["assistant"] ?: ""}"
        }

        val prompt = """请从以下对话中提取值得长期记忆的信息，返回 JSON 数组。
每条记忆格式：{"category":"user_persona|character_persona|important_event|recent_digest","title":"简短标题","summary":"一句话摘要","content":"详细内容","importance":1-10}

只提取有长期价值的信息，忽略寒暄和闲聊。最多提取 5 条。

对话内容：
$dialogText

只返回 JSON 数组，不要其他文字。"""

        val messages = listOf(
            mapOf("role" to "system", "content" to "你是一个记忆抽取助手，只返回JSON数组。"),
            mapOf("role" to "user", "content" to prompt)
        )

        val result = client.chatOnce(model, messages)
        if (result.error != null || result.content.isBlank()) return emptyList()

        return parseMemoryResponse(result.content, characterId, targetId)
    }

    /** 解析 AI 返回的记忆 JSON */
    private fun parseMemoryResponse(text: String, characterId: String, targetId: String): List<CharacterMemory> {
        val cleaned = cleanResponseContent(text)
        if (cleaned.isEmpty() || !cleaned.startsWith("[")) return emptyList()
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            @Suppress("UNCHECKED_CAST")
            val list = repoGson.fromJson<List<Map<String, Any>>>(cleaned, type) ?: return emptyList()
            list.mapNotNull { item ->
                val category = (item["category"] as? String) ?: "recent_digest"
                val title = (item["title"] as? String) ?: return@mapNotNull null
                val summary = (item["summary"] as? String) ?: ""
                val content = (item["content"] as? String) ?: ""
                val importance = (item["importance"] as? Number)?.toInt() ?: 5
                CharacterMemory(
                    id = UUID.randomUUID().toString(),
                    characterId = characterId,
                    targetId = targetId,
                    type = "long",
                    title = title,
                    summary = summary,
                    content = content,
                    importance = importance.coerceIn(1, 10)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
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

    /** CharacterMemory → Entity */
    private fun memoryToEntity(memory: CharacterMemory): LocalCharacterMemoryEntity {
        return LocalCharacterMemoryEntity(
            id = memory.id.ifEmpty { UUID.randomUUID().toString() },
            characterId = memory.characterId,
            targetId = memory.targetId,
            type = memory.type,
            title = memory.title,
            summary = memory.summary,
            content = memory.content,
            importance = memory.importance,
            emotionImpact = if (memory.emotionImpact.isNotEmpty()) repoGson.toJson(memory.emotionImpact) else null,
            sourceTurnId = memory.sourceTurnId,
            createdAt = memory.createdAt.ifEmpty { Instant.now().toString() },
            expiresAt = memory.expiresAt
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
        val entries = worldBookDao.listActiveEntriesForCharacter(characterId)
        if (entries.isEmpty()) return emptyList()

        val results = mutableListOf<CharacterRuntime.WorldBookMatch>()
        val combinedText = (userMessage + " " + recentMessages.joinToString(" ")).lowercase()

        for (entry in entries) {
            if (!entry.enabled) continue
            val keysJson = entry.keys ?: continue
            if (keysJson.isBlank()) continue

            val keys = try {
                val type = object : TypeToken<List<String>>() {}.type
                repoGson.fromJson<List<String>>(keysJson, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            if (keys.isEmpty()) continue

            // 常驻条目或关键词匹配
            val matched = if (entry.constant) {
                true
            } else if (entry.selective) {
                keys.any { key ->
                    if (entry.caseSensitive) key in userMessage
                    else key.lowercase() in combinedText
                }
            } else {
                keys.any { key ->
                    if (entry.caseSensitive) key in userMessage
                    else key.lowercase() in combinedText
                }
            }

            if (matched) {
                results.add(CharacterRuntime.WorldBookMatch(
                    content = entry.content ?: "",
                    comment = entry.comment ?: "",
                    priority = entry.priority
                ))
            }
        }

        return results.sortedBy { it.priority }
    }
}
