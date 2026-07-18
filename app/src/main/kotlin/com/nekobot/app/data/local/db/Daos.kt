package com.nekobot.app.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM local_sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<LocalSessionEntity>>

    @Query("SELECT * FROM local_sessions ORDER BY updated_at DESC")
    suspend fun listAll(): List<LocalSessionEntity>

    @Query("SELECT * FROM local_sessions WHERE id = :id")
    suspend fun getById(id: String): LocalSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: LocalSessionEntity)

    /**
     * 更新已存在的会话：使用 @Update 而非 @Insert(REPLACE)。
     *
     * 原因：@Insert(onConflict = REPLACE) 在主键冲突时会先 DELETE 旧行再 INSERT 新行，
     * 而 local_messages 对 local_sessions 有 ForeignKey(onDelete = CASCADE)，
     * 这会导致会话的所有消息被级联删除——会话详情保存按钮"清空对话内容"的根因。
     * @Update 只生成 UPDATE ... WHERE id=? 语句，不会触发 DELETE/CASCADE。
     */
    @Update
    suspend fun update(session: LocalSessionEntity)

    @Delete
    suspend fun delete(session: LocalSessionEntity)

    @Query("DELETE FROM local_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_sessions SET last_message = :lastMessage, message_count = :count, updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, lastMessage: String, count: Int, updatedAt: String)

    @Query("UPDATE local_sessions SET custom_prompts = :customPrompts, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCustomPrompts(id: String, customPrompts: String?, updatedAt: String)

    @Query("UPDATE local_sessions SET prompt_stack_debug = :debugJson WHERE id = :id")
    suspend fun updatePromptStackDebug(id: String, debugJson: String)

    @Query("UPDATE local_sessions SET composed_system_prompt = :prompt WHERE id = :id")
    suspend fun updateComposedSystemPrompt(id: String, prompt: String)

    @Query("UPDATE local_sessions SET is_public = :isPublic, share_config = :shareConfig, tts_config = :ttsConfig, proactive_chat = :proactiveChat, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePublicShareConfig(id: String, isPublic: Boolean, shareConfig: String?, ttsConfig: String?, proactiveChat: String?, updatedAt: String)

    /** nbotcfg 导入后批量改写立绘 URL 为本地路径（portrait / sender_avatar / character_avatar）。 */
    @Query("UPDATE local_sessions SET portrait = :portrait, sender_avatar = :senderAvatar, character_avatar = :characterAvatar WHERE id = :id")
    suspend fun updatePortraits(id: String, portrait: String?, senderAvatar: String?, characterAvatar: String?)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun listBySession(sessionId: String): List<LocalMessageEntity>

    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeBySession(sessionId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT COUNT(*) FROM local_messages WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: LocalMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<LocalMessageEntity>)

    @Query("DELETE FROM local_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_messages WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM local_messages WHERE session_id = :sessionId AND created_at >= :createdAt")
    suspend fun deleteFromMessageOnwards(sessionId: String, createdAt: String)

    /** 更新指定消息的进度卡片 JSON（agent 模式持久化 thinking_cards）。 */
    @Query("UPDATE local_messages SET thinking_cards = :json WHERE id = :id")
    suspend fun updateThinkingCards(id: String, json: String?)

    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId AND created_at < :createdAt ORDER BY created_at ASC")
    suspend fun listBefore(sessionId: String, createdAt: String): List<LocalMessageEntity>

    // ===== Token 用量统计 =====

    /** 今日 assistant 消息的 token 用量（input/output/total）。 */
    @Query("SELECT COALESCE(SUM(input_tokens),0) AS total FROM local_messages WHERE role='assistant' AND created_at >= :todayStart")
    suspend fun todayInputTokens(todayStart: String): Int

    @Query("SELECT COALESCE(SUM(output_tokens),0) FROM local_messages WHERE role='assistant' AND created_at >= :todayStart")
    suspend fun todayOutputTokens(todayStart: String): Int

    @Query("SELECT COALESCE(SUM(input_tokens),0)+COALESCE(SUM(output_tokens),0) FROM local_messages WHERE role='assistant' AND created_at >= :monthStart")
    suspend fun monthTokens(monthStart: String): Int

    @Query("SELECT COALESCE(SUM(input_tokens),0)+COALESCE(SUM(output_tokens),0) FROM local_messages WHERE role='assistant'")
    suspend fun totalTokens(): Int

    @Query("SELECT COUNT(*) FROM local_messages WHERE role='assistant'")
    suspend fun assistantMessageCount(): Int

    /** 全部 assistant 消息（含 token / model），用于排行榜与明细。 */
    @Query("SELECT * FROM local_messages WHERE role='assistant' ORDER BY created_at DESC")
    suspend fun listAllAssistant(): List<LocalMessageEntity>

    @Query("SELECT * FROM local_messages WHERE role='assistant' AND created_at >= :start AND created_at < :end ORDER BY created_at DESC")
    suspend fun listAssistantInRange(start: String, end: String): List<LocalMessageEntity>
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM local_characters ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<LocalCharacterEntity>>

    @Query("SELECT * FROM local_characters ORDER BY updated_at DESC")
    suspend fun listAll(): List<LocalCharacterEntity>

    @Query("SELECT * FROM local_characters WHERE id = :id")
    suspend fun getById(id: String): LocalCharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(character: LocalCharacterEntity)

    @Update
    suspend fun update(character: LocalCharacterEntity)

    @Query("DELETE FROM local_characters WHERE id = :id")
    suspend fun deleteById(id: String)

    /** nbotcfg 导入后批量改写立绘 URL 为本地路径（portrait / avatar）。 */
    @Query("UPDATE local_characters SET portrait = :portrait, avatar = :avatar WHERE id = :id")
    suspend fun updatePortraits(id: String, portrait: String?, avatar: String?)
}

@Dao
interface WorldBookDao {
    @Query("SELECT * FROM local_world_books ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<LocalWorldBookEntity>>

    @Query("SELECT * FROM local_world_books ORDER BY updated_at DESC")
    suspend fun listAll(): List<LocalWorldBookEntity>

    @Query("SELECT * FROM local_world_books WHERE id = :id")
    suspend fun getById(id: String): LocalWorldBookEntity?

    @Query("SELECT * FROM local_world_books WHERE character_id IS NOT NULL AND ',' || character_id || ',' LIKE '%,' || :characterId || ',%'")
    suspend fun listByCharacter(characterId: String): List<LocalWorldBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: LocalWorldBookEntity)

    @Query("DELETE FROM local_world_books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: LocalWorldBookEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<LocalWorldBookEntryEntity>)

    @Query("SELECT * FROM local_world_book_entries WHERE book_id = :bookId ORDER BY display_index ASC, insertion_order ASC")
    suspend fun listEntries(bookId: String): List<LocalWorldBookEntryEntity>

    @Query("SELECT * FROM local_world_book_entries WHERE book_id IN (SELECT id FROM local_world_books WHERE enabled = 1 AND (character_id IS NULL OR ',' || character_id || ',' LIKE '%,' || :characterId || ',%'))")
    suspend fun listActiveEntriesForCharacter(characterId: String): List<LocalWorldBookEntryEntity>

    @Query("SELECT * FROM local_world_book_entries WHERE book_id IN (SELECT id FROM local_world_books WHERE enabled = 1)")
    suspend fun listAllActiveEntries(): List<LocalWorldBookEntryEntity>

    @Query("DELETE FROM local_world_book_entries WHERE id = :id")
    suspend fun deleteEntryById(id: String)
}

@Dao
interface AiModelDao {
    @Query("SELECT * FROM local_ai_models ORDER BY priority ASC, created_at ASC")
    fun observeAll(): Flow<List<LocalAiModelEntity>>

    @Query("SELECT * FROM local_ai_models ORDER BY priority ASC, created_at ASC")
    suspend fun listAll(): List<LocalAiModelEntity>

    @Query("SELECT * FROM local_ai_models WHERE id = :id")
    suspend fun getById(id: String): LocalAiModelEntity?

    /**
     * 默认激活模型按 purpose='chat' 过滤。
     * 远程数据库导入后每个 purpose 都会有一个 active=1 的模型（chat/tts/vision/...），
     * 若不过滤 purpose，LIMIT 1 可能返回 TTS/vision 等非 chat 模型，
     * 导致聊天代码用错模型并触发 404。
     * 其他 purpose 的激活模型请使用 [getActiveByPurpose]。
     */
    @Query("SELECT * FROM local_ai_models WHERE purpose = 'chat' AND active = 1 LIMIT 1")
    suspend fun getActive(): LocalAiModelEntity?

    @Query("SELECT * FROM local_ai_models WHERE purpose = 'chat' AND active = 1 LIMIT 1")
    fun observeActive(): Flow<LocalAiModelEntity?>

    @Query("SELECT * FROM local_ai_models WHERE purpose = :purpose AND active = 1 LIMIT 1")
    suspend fun getActiveByPurpose(purpose: String): LocalAiModelEntity?

    @Query("SELECT * FROM local_ai_models WHERE purpose = :purpose AND enabled = 1 ORDER BY priority ASC, created_at ASC")
    suspend fun listByPurpose(purpose: String): List<LocalAiModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalAiModelEntity)

    @Query("UPDATE local_ai_models SET active = (id = :id)")
    suspend fun setActive(id: String)

    /** 将指定 purpose 下其他模型的 active 全部置 0，再置目标模型 active = 1。 */
    @Query("UPDATE local_ai_models SET active = CASE WHEN id = :id THEN 1 ELSE 0 END WHERE purpose = :purpose")
    suspend fun setActiveForPurpose(id: String, purpose: String)

    @Query("DELETE FROM local_ai_models WHERE id = :id")
    suspend fun deleteById(id: String)
}

/**
 * 故障转移健康状态 DAO：持久化模型失败/冷却记录，跨重启保留。
 */
@Dao
interface FailoverHealthDao {
    @Query("SELECT * FROM local_failover_health")
    suspend fun listAll(): List<LocalFailoverHealthEntity>

    @Query("SELECT * FROM local_failover_health WHERE model_id = :modelId")
    suspend fun get(modelId: String): LocalFailoverHealthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: LocalFailoverHealthEntity)

    @Query("DELETE FROM local_failover_health WHERE model_id = :modelId")
    suspend fun delete(modelId: String)

    @Query("DELETE FROM local_failover_health")
    suspend fun clear()
}

// ==================== 角色运行时 DAOs (Stage 4) ====================

@Dao
interface CharacterStateDao {
    @Query("SELECT * FROM local_character_states WHERE character_id = :characterId AND scope_id = :scopeId LIMIT 1")
    suspend fun get(characterId: String, scopeId: String): LocalCharacterStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LocalCharacterStateEntity)

    @Query("DELETE FROM local_character_states WHERE character_id = :characterId AND scope_id = :scopeId")
    suspend fun delete(characterId: String, scopeId: String)
}

@Dao
interface RelationshipDao {
    @Query("SELECT * FROM local_relationship_states WHERE character_id = :characterId AND target_id = :targetId LIMIT 1")
    suspend fun get(characterId: String, targetId: String): LocalRelationshipStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: LocalRelationshipStateEntity)

    @Query("DELETE FROM local_relationship_states WHERE character_id = :characterId AND target_id = :targetId")
    suspend fun delete(characterId: String, targetId: String)
}

@Dao
interface StateSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: LocalStateSnapshotEntity)

    @Query("SELECT * FROM local_state_snapshots WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun listBySession(sessionId: String): List<LocalStateSnapshotEntity>

    @Query("DELETE FROM local_state_snapshots WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM local_character_memories WHERE character_id = :characterId AND target_id = :targetId ORDER BY importance DESC, created_at DESC LIMIT :limit")
    suspend fun listByCharacterAndTarget(characterId: String, targetId: String, limit: Int = 20): List<LocalCharacterMemoryEntity>

    @Query("SELECT * FROM local_character_memories WHERE character_id = :characterId AND target_id = :targetId AND (title LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY importance DESC, created_at DESC LIMIT :limit")
    suspend fun search(characterId: String, targetId: String, query: String, limit: Int = 8): List<LocalCharacterMemoryEntity>

    @Query("SELECT * FROM local_character_memories WHERE character_id = :characterId AND target_id = :targetId AND category = :category ORDER BY created_at DESC LIMIT :limit")
    suspend fun listByCategory(characterId: String, targetId: String, category: String, limit: Int = 10): List<LocalCharacterMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: LocalCharacterMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memories: List<LocalCharacterMemoryEntity>)

    @Query("DELETE FROM local_character_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_character_memories WHERE character_id = :characterId AND target_id = :targetId")
    suspend fun deleteByCharacterAndTarget(characterId: String, targetId: String)

    @Query("SELECT COUNT(*) FROM local_character_memories WHERE character_id = :characterId AND target_id = :targetId")
    suspend fun count(characterId: String, targetId: String): Int

    @Query("SELECT * FROM local_character_memories ORDER BY importance DESC, created_at DESC")
    suspend fun listAll(): List<LocalCharacterMemoryEntity>

    @Query("SELECT * FROM local_character_memories WHERE character_id = :characterId ORDER BY importance DESC, created_at DESC")
    suspend fun listByCharacter(characterId: String): List<LocalCharacterMemoryEntity>
}

// ==================== 扩展功能 DAOs (v10) ====================

@Dao
interface HookDao {
    @Query("SELECT * FROM local_hooks ORDER BY priority ASC, created_at DESC")
    suspend fun listAll(): List<LocalHookEntity>

    @Query("SELECT * FROM local_hooks WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun listEnabled(): List<LocalHookEntity>

    @Query("SELECT * FROM local_hooks WHERE id = :id")
    suspend fun getById(id: String): LocalHookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hook: LocalHookEntity)

    @Query("DELETE FROM local_hooks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_hooks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM local_tasks ORDER BY created_at DESC")
    suspend fun listAll(): List<LocalTaskEntity>

    @Query("SELECT * FROM local_tasks WHERE id = :id")
    suspend fun getById(id: String): LocalTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: LocalTaskEntity)

    @Query("DELETE FROM local_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE local_tasks SET last_run = :lastRun WHERE id = :id")
    suspend fun touchRun(id: String, lastRun: String)
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM local_workflows ORDER BY created_at DESC")
    suspend fun listAll(): List<LocalWorkflowEntity>

    @Query("SELECT * FROM local_workflows WHERE id = :id")
    suspend fun getById(id: String): LocalWorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workflow: LocalWorkflowEntity)

    @Query("DELETE FROM local_workflows WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_workflows SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM local_skills ORDER BY created_at DESC")
    suspend fun listAll(): List<LocalSkillEntity>

    @Query("SELECT * FROM local_skills WHERE id = :id")
    suspend fun getById(id: String): LocalSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: LocalSkillEntity)

    @Query("DELETE FROM local_skills WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_skills SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface ToolDao {
    @Query("SELECT * FROM local_tools ORDER BY created_at DESC")
    suspend fun listAll(): List<LocalToolEntity>

    @Query("SELECT * FROM local_tools WHERE id = :id")
    suspend fun getById(id: String): LocalToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tool: LocalToolEntity)

    @Query("DELETE FROM local_tools WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_tools SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM local_mcp_servers ORDER BY created_at DESC")
    suspend fun listAll(): List<LocalMcpServerEntity>

    @Query("SELECT * FROM local_mcp_servers WHERE id = :id")
    suspend fun getById(id: String): LocalMcpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: LocalMcpServerEntity)

    @Query("DELETE FROM local_mcp_servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE local_mcp_servers
        SET connected = :connected,
            tool_count = :toolCount,
            last_connected_at = :lastConnectedAt
        WHERE id = :id
        """
    )
    suspend fun setRuntimeState(
        id: String,
        connected: Boolean,
        toolCount: Int,
        lastConnectedAt: String?
    )
}

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM local_api_keys ORDER BY created_at DESC")
    suspend fun listAll(): List<LocalApiKeyEntity>

    @Query("SELECT * FROM local_api_keys WHERE id = :id")
    suspend fun getById(id: String): LocalApiKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: LocalApiKeyEntity)

    @Query("DELETE FROM local_api_keys WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageFavoriteDao {
    @Query("SELECT * FROM local_message_favorites WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun listBySession(sessionId: String): List<LocalMessageFavoriteEntity>

    @Query("SELECT * FROM local_message_favorites WHERE id = :id")
    suspend fun getById(id: String): LocalMessageFavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: LocalMessageFavoriteEntity)

    @Query("DELETE FROM local_message_favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_message_favorites WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
