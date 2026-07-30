package com.nekobot.app.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM local_sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<LocalSessionEntity>>

    @Query("SELECT * FROM local_sessions ORDER BY updated_at DESC")
    suspend fun listAll(): List<LocalSessionEntity>

    @Query("SELECT COUNT(*) FROM local_sessions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM local_sessions WHERE favorite = 1 AND archived = 0")
    suspend fun countFavorites(): Int

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

    /** 自动命名：仅更新 name 字段（不触碰 last_message/count） */
    @Query("UPDATE local_sessions SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: String)

    @Query("UPDATE local_sessions SET custom_prompts = :customPrompts, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCustomPrompts(id: String, customPrompts: String?, updatedAt: String)

    @Query("UPDATE local_sessions SET prompt_stack_debug = :debugJson WHERE id = :id")
    suspend fun updatePromptStackDebug(id: String, debugJson: String)

    @Query("UPDATE local_sessions SET composed_system_prompt = :prompt WHERE id = :id")
    suspend fun updateComposedSystemPrompt(id: String, prompt: String)

    /** 群聊每轮结束后保存调度游标，保证 round_robin 跨轮次连续。 */
    @Query("UPDATE local_sessions SET group_active_speaker = :speakerId, group_turn_count = group_turn_count + 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun advanceGroupTurn(id: String, speakerId: String, updatedAt: String)

    @Query("UPDATE local_sessions SET is_public = :isPublic, share_config = :shareConfig, tts_config = :ttsConfig, proactive_chat = :proactiveChat, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePublicShareConfig(id: String, isPublic: Boolean, shareConfig: String?, ttsConfig: String?, proactiveChat: String?, updatedAt: String)

    /** nbotcfg 导入后批量改写立绘 URL 为本地路径（portrait / sender_avatar / character_avatar）。 */
    @Query("UPDATE local_sessions SET portrait = :portrait, sender_avatar = :senderAvatar, character_avatar = :characterAvatar WHERE id = :id")
    suspend fun updatePortraits(id: String, portrait: String?, senderAvatar: String?, characterAvatar: String?)

    /**
     * 角色卡立绘变更后，同步刷新所有以该角色为主角的会话立绘快照。
     *
     * 仅更新 character_id = :characterId 的普通会话；群聊会话（character_ids 数组）
     * 不在此更新，因为群聊列表不显示单一角色立绘，消息头像在运行时回查角色卡。
     * - portrait：只在角色卡 portrait 非空时覆盖，避免清空 senderPortrait 兜底值
     * - character_avatar：始终同步角色卡 avatar
     */
    @Query("UPDATE local_sessions SET character_avatar = :characterAvatar, portrait = CASE WHEN :portrait IS NULL OR :portrait = '' THEN portrait ELSE :portrait END WHERE character_id = :characterId")
    suspend fun updatePortraitsByCharacterId(characterId: String, portrait: String?, characterAvatar: String?)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun listBySession(sessionId: String): List<LocalMessageEntity>

    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeBySession(sessionId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT COUNT(*) FROM local_messages WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId AND role = 'user' ORDER BY created_at DESC LIMIT 1")
    suspend fun latestUserBySession(sessionId: String): LocalMessageEntity?

    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId AND source = :source ORDER BY created_at DESC LIMIT 1")
    suspend fun latestBySource(sessionId: String, source: String): LocalMessageEntity?

    @Query("SELECT COUNT(*) FROM local_messages WHERE role = 'user'")
    suspend fun countUserMessages(): Int

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

    @Query("UPDATE local_messages SET audio_url = :audioUrl WHERE id = :id")
    suspend fun updateAudioUrl(id: String, audioUrl: String?)

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

    @Query("SELECT COUNT(*) FROM local_characters")
    suspend fun count(): Int

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

    @Query("SELECT COUNT(*) FROM local_world_books")
    suspend fun count(): Int

    @Query("SELECT * FROM local_world_books WHERE id = :id")
    suspend fun getById(id: String): LocalWorldBookEntity?

    @Query("SELECT * FROM local_world_books WHERE character_id IS NOT NULL AND ',' || character_id || ',' LIKE '%,' || :characterId || ',%'")
    suspend fun listByCharacter(characterId: String): List<LocalWorldBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: LocalWorldBookEntity)

    /**
     * 更新已存在的世界书：使用 @Update 而非 @Insert(REPLACE)。
     *
     * Room 的 REPLACE 策略底层是 DELETE + INSERT，会触发 local_world_book_entries
     * 的外键级联删除，导致更新世界书时条目全部丢失。@Update 只生成 UPDATE SQL，
     * 不影响关联表。
     */
    @Update
    suspend fun update(book: LocalWorldBookEntity)

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

    @Query("SELECT * FROM local_ai_models WHERE oauth_account_id = :accountId ORDER BY priority ASC, created_at ASC")
    suspend fun listByOAuthAccount(accountId: String): List<LocalAiModelEntity>

    @Query("DELETE FROM local_ai_models WHERE oauth_account_id = :accountId")
    suspend fun deleteByOAuthAccount(accountId: String)
}

@Dao
interface OAuthAccountDao {
    @Query("SELECT * FROM local_oauth_accounts ORDER BY created_at ASC")
    fun observeAll(): Flow<List<LocalOAuthAccountEntity>>

    @Query("SELECT * FROM local_oauth_accounts ORDER BY created_at ASC")
    suspend fun listAll(): List<LocalOAuthAccountEntity>

    @Query("SELECT * FROM local_oauth_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LocalOAuthAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: LocalOAuthAccountEntity)

    @Query("UPDATE local_oauth_accounts SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: String)

    @Query("DELETE FROM local_oauth_accounts WHERE id = :id")
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

    @Query("SELECT * FROM local_relationship_states WHERE character_id = :characterId AND target_id != :excludedTargetId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestForCharacter(characterId: String, excludedTargetId: String): LocalRelationshipStateEntity?

    @Query("SELECT * FROM local_relationship_states")
    suspend fun listAll(): List<LocalRelationshipStateEntity>

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

    @Query("SELECT * FROM local_state_snapshots WHERE character_id = :characterId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestForCharacter(characterId: String): LocalStateSnapshotEntity?

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

    /**
     * 按 path 精确查询（对齐原仓库 memoryfs 的 path 概念）。
     * 用于 append/replace 语义时获取同 path 的已有记忆。
     */
    @Query("SELECT * FROM local_character_memories WHERE memory_path = :path ORDER BY version DESC, created_at DESC")
    suspend fun listByPath(path: String): List<LocalCharacterMemoryEntity>

    /**
     * 按 characterId + category 查询所有会话的记忆（用于 timeline 跨会话聚合）。
     */
    @Query("SELECT * FROM local_character_memories WHERE character_id = :characterId AND category = :category ORDER BY updated_at DESC, created_at DESC LIMIT :limit")
    suspend fun listByCharacterAndCategory(characterId: String, category: String, limit: Int = 80): List<LocalCharacterMemoryEntity>

    /**
     * 按 characterId + category + conversationId 查询（用于 events/life_sim 会话隔离读取）。
     */
    @Query("SELECT * FROM local_character_memories WHERE character_id = :characterId AND category = :category AND conversation_id = :conversationId ORDER BY updated_at DESC, created_at DESC LIMIT :limit")
    suspend fun listByCharacterCategoryAndConversation(characterId: String, category: String, conversationId: String, limit: Int = 30): List<LocalCharacterMemoryEntity>

    /**
     * 删除指定 path 的所有记忆（用于 recent_digest replace 前清空）。
     */
    @Query("DELETE FROM local_character_memories WHERE memory_path = :path")
    suspend fun deleteByPath(path: String)

    /**
     * 原子替换某个角色和玩家下的单槽记忆类别。
     * 会清理早期版本中 path 为空或重复写入产生的旧记录。
     */
    @Query("DELETE FROM local_character_memories WHERE character_id = :characterId AND target_id = :targetId AND category = :category")
    suspend fun deleteByCharacterTargetAndCategory(characterId: String, targetId: String, category: String)

    @Transaction
    suspend fun replaceByCharacterTargetAndCategory(memory: LocalCharacterMemoryEntity) {
        deleteByCharacterTargetAndCategory(memory.characterId, memory.targetId, memory.category)
        upsert(memory)
    }

    /**
     * 删除指定 characterId + category + conversationId 的所有记忆（用于 events/life_sim 替换前清空）。
     */
    @Query("DELETE FROM local_character_memories WHERE character_id = :characterId AND category = :category AND conversation_id = :conversationId")
    suspend fun deleteByCharacterCategoryAndConversation(characterId: String, category: String, conversationId: String)

    /**
     * 保留指定 characterId + category 的最新 N 条，删除更早的（用于 timeline 截断）。
     */
    @Query("DELETE FROM local_character_memories WHERE id IN (SELECT id FROM local_character_memories WHERE character_id = :characterId AND category = :category ORDER BY updated_at DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimByCharacterAndCategory(characterId: String, category: String, keep: Int)

    /**
     * 保留指定 path 的最新 N 条，删除更早的（用于 append 后截断）。
     */
    @Query("DELETE FROM local_character_memories WHERE id IN (SELECT id FROM local_character_memories WHERE memory_path = :path ORDER BY version DESC, created_at DESC LIMIT -1 OFFSET :keep)")
    suspend fun trimByPath(path: String, keep: Int)

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

/**
 * Hook 执行日志 DAO。每次 hook 触发（成功/失败/部分成功）追加一条日志。
 * HooksScreen 的"查看日志"功能通过 [listByHook] 按 hook_id 倒序查询。
 */
@Dao
interface HookLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LocalHookLogEntity)

    /** 按 hook 查询执行日志，最新在前。 */
    @Query("SELECT * FROM local_hook_logs WHERE hook_id = :hookId ORDER BY created_at DESC LIMIT :limit")
    suspend fun listByHook(hookId: String, limit: Int): List<LocalHookLogEntity>

    /** 全局查询最近日志（用于"全部日志"视图）。 */
    @Query("SELECT * FROM local_hook_logs ORDER BY created_at DESC LIMIT :limit")
    suspend fun listAll(limit: Int): List<LocalHookLogEntity>

    @Query("DELETE FROM local_hook_logs WHERE hook_id = :hookId")
    suspend fun deleteByHook(hookId: String)

    @Query("DELETE FROM local_hook_logs")
    suspend fun clearAll()

    /** 截断每个 hook 的日志数量到最近 :keep 条，防止无限增长。 */
    @Query("DELETE FROM local_hook_logs WHERE hook_id = :hookId AND id NOT IN (SELECT id FROM local_hook_logs WHERE hook_id = :hookId ORDER BY created_at DESC LIMIT :keep)")
    suspend fun trimByHook(hookId: String, keep: Int)
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

    @Query("UPDATE local_tasks SET next_run = :nextRun WHERE id = :id")
    suspend fun updateNextRun(id: String, nextRun: String?)

    @Query("UPDATE local_tasks SET status = :status, last_error = :error, last_run = :lastRun WHERE id = :id")
    suspend fun updateExecutionState(id: String, status: String, error: String?, lastRun: String?)
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

    @Query("UPDATE local_workflows SET next_run = :nextRun WHERE id = :id")
    suspend fun updateNextRun(id: String, nextRun: String?)

    @Query("UPDATE local_workflows SET session_id = :sessionId WHERE id = :id")
    suspend fun updateSessionId(id: String, sessionId: String?)

    @Query("UPDATE local_workflows SET status = :status, last_error = :error, last_run = :lastRun WHERE id = :id")
    suspend fun updateExecutionState(id: String, status: String, error: String?, lastRun: String?)
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

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM local_knowledge_documents ORDER BY updated_at DESC")
    suspend fun listDocuments(): List<LocalKnowledgeDocumentEntity>

    @Query("SELECT * FROM local_knowledge_documents WHERE id = :id")
    suspend fun getDocument(id: String): LocalKnowledgeDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: LocalKnowledgeDocumentEntity)

    @Query("DELETE FROM local_knowledge_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("UPDATE local_knowledge_documents SET indexed = :indexed, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateIndexed(id: String, indexed: Boolean, updatedAt: String)

    @Query("SELECT COUNT(*) FROM local_knowledge_documents")
    suspend fun documentCount(): Int

    @Query("SELECT COUNT(*) FROM local_knowledge_documents WHERE indexed = 1")
    suspend fun indexedCount(): Int

    @Query("SELECT * FROM local_knowledge_chunks ORDER BY document_id, chunk_index")
    suspend fun listAllChunks(): List<LocalKnowledgeChunkEntity>

    @Query("SELECT * FROM local_knowledge_chunks WHERE document_id = :documentId ORDER BY chunk_index")
    suspend fun listChunks(documentId: String): List<LocalKnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<LocalKnowledgeChunkEntity>)

    @Query("DELETE FROM local_knowledge_chunks WHERE document_id = :documentId")
    suspend fun deleteChunks(documentId: String)
}
