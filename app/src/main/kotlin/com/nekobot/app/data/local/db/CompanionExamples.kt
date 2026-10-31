package com.nekobot.app.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 缺少记录的老会话/转换/导入会话默认没有邀请资格。 */
@Entity(tableName = "companion_examples", foreignKeys = [ForeignKey(entity = LocalSessionEntity::class,
    parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class CompanionExamplesEntity(
    @PrimaryKey val sessionId: String,
    val state: String,
    val pairsJson: String = "[]",
    val noticeAfterId: String? = null,
    @ColumnInfo(defaultValue = "0") val optedIn: Boolean = false
)

@Dao
interface CompanionExamplesDao {
    @Query("SELECT * FROM companion_examples WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): CompanionExamplesEntity?
    @Query("SELECT * FROM companion_examples WHERE sessionId = :sessionId")
    fun observe(sessionId: String): Flow<CompanionExamplesEntity?>
    @Upsert suspend fun save(row: CompanionExamplesEntity)
    /** 先落库再调用；双击、重进页面或进程中断均不能再次挑选。 */
    @Query(CompanionExamplesSql.CLAIM)
    suspend fun claim(sessionId: String): Int
    @Query(CompanionExamplesSql.HISTORY_PAGE)
    suspend fun historyPage(sessionId: String, createdAt: String, rowId: Long, limit: Int): List<LocalMessageRow>
}

internal object CompanionExamplesSql {
    const val CLAIM = "UPDATE companion_examples SET state = 'selecting', optedIn = 1 WHERE sessionId = :sessionId AND state = 'offered'"
    // 两个索引范围合并；同秒大量消息也能按 rowid 继续，不用 OFFSET 重扫已读前缀。
    // 保留删除/系统/未完成消息作为断点，不能先过滤后把无关问答拼成一轮。
    const val HISTORY_PAGE = "SELECT *, rowid AS row_id FROM local_messages " +
        "WHERE session_id = :sessionId AND created_at = :createdAt AND rowid > :rowId " +
        "UNION ALL SELECT *, rowid AS row_id FROM local_messages " +
        "WHERE session_id = :sessionId AND created_at > :createdAt " +
        "ORDER BY created_at, row_id LIMIT :limit"
}

internal object CompanionExamplesSchema {
    fun create(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_examples (sessionId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, pairsJson TEXT NOT NULL, noticeAfterId TEXT, optedIn INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(sessionId) REFERENCES local_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
