package com.nekobot.app.data.local.ai

/**
 * 角色运行时存储接口，对应原仓库 nbot/character/repository.py。
 *
 * 阶段2仅定义接口，阶段4用 Room 实现。
 */

/** 角色卡仓库 */
interface ProfileRepository {
    suspend fun getById(id: String): CharacterProfile?
}

/** 角色状态仓库 */
interface CharacterStateRepository {
    suspend fun get(characterId: String, scopeId: String): CharacterState?
    suspend fun save(state: CharacterState)
}

/** 关系状态仓库 */
interface RelationshipRepository {
    suspend fun get(characterId: String, targetId: String): RelationshipState?
    suspend fun save(relationship: RelationshipState)
}

/** 记忆服务接口 */
interface MemoryService {
    /** 检索相关记忆，最多 limit 条 */
    suspend fun search(identity: CharacterIdentity, content: String, limit: Int = 8): List<CharacterMemory>

    /** 按需抽取并保存记忆（6 轮一次） */
    suspend fun extractIfNeeded(
        chatRequest: ChatRequest,
        response: ChatResponse,
        turnContext: CharacterTurnContext
    )
}
