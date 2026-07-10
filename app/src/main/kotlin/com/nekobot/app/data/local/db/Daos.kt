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

    @Delete
    suspend fun delete(session: LocalSessionEntity)

    @Query("DELETE FROM local_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_sessions SET last_message = :lastMessage, message_count = :count, updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, lastMessage: String, count: Int, updatedAt: String)
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
}

@Dao
interface WorldBookDao {
    @Query("SELECT * FROM local_world_books ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<LocalWorldBookEntity>>

    @Query("SELECT * FROM local_world_books ORDER BY updated_at DESC")
    suspend fun listAll(): List<LocalWorldBookEntity>

    @Query("SELECT * FROM local_world_books WHERE id = :id")
    suspend fun getById(id: String): LocalWorldBookEntity?

    @Query("SELECT * FROM local_world_books WHERE character_id = :characterId")
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

    @Query("SELECT * FROM local_world_book_entries WHERE book_id IN (SELECT id FROM local_world_books WHERE enabled = 1 AND (character_id = :characterId OR character_id IS NULL))")
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

    @Query("SELECT * FROM local_ai_models WHERE active = 1 LIMIT 1")
    suspend fun getActive(): LocalAiModelEntity?

    @Query("SELECT * FROM local_ai_models WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<LocalAiModelEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalAiModelEntity)

    @Query("UPDATE local_ai_models SET active = (id = :id)")
    suspend fun setActive(id: String)

    @Query("DELETE FROM local_ai_models WHERE id = :id")
    suspend fun deleteById(id: String)
}
