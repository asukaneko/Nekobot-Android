package com.nekobot.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地模式 Room 数据库。
 *
 * 仅在 AppMode = LOCAL 时使用；服务器模式继续走 Retrofit/Socket。
 */
@Database(
    entities = [
        LocalSessionEntity::class,
        LocalMessageEntity::class,
        LocalCharacterEntity::class,
        LocalWorldBookEntity::class,
        LocalWorldBookEntryEntity::class,
        LocalAiModelEntity::class,
        LocalCharacterStateEntity::class,
        LocalRelationshipStateEntity::class,
        LocalCharacterMemoryEntity::class,
        LocalStateSnapshotEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class NekobotDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun characterDao(): CharacterDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun characterStateDao(): CharacterStateDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun memoryDao(): MemoryDao
    abstract fun stateSnapshotDao(): StateSnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: NekobotDatabase? = null

        /**
         * v1 → v2：local_sessions 新增 custom_prompts 列。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN custom_prompts TEXT")
            }
        }

        /**
         * v2 → v3：local_sessions 新增 plot_mode 列（剧情模式开关）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN plot_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 → v4：local_sessions 新增 pinned / plot_realtime_sync / auto_state_interval / disabled_prompt_keys / prompt_stack_debug 列。
         *
         * 使用 ALTER TABLE ADD COLUMN 保留现有数据（会话/消息/角色卡/世界书/记忆等全部保留）。
         * 替代之前 .fallbackToDestructiveMigration() 的破坏性迁移，避免升级时清空所有本地数据。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN plot_realtime_sync INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN auto_state_interval INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN disabled_prompt_keys TEXT")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN prompt_stack_debug TEXT")
            }
        }

        /**
         * v4 → v5：新增 local_state_snapshots 表（角色状态历史快照）。
         *
         * 追加式历史表，供「状态历程」界面呈现情绪/精力/关系六维随时间的演变，
         * 与单行覆盖的 local_character_states 互补。使用 CREATE TABLE 保留现有数据。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_state_snapshots (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        character_id TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        mood TEXT NOT NULL,
                        mood_intensity REAL NOT NULL,
                        energy INTEGER NOT NULL,
                        affection INTEGER NOT NULL,
                        trust INTEGER NOT NULL,
                        familiarity INTEGER NOT NULL,
                        dependency INTEGER NOT NULL,
                        security INTEGER NOT NULL,
                        jealousy INTEGER NOT NULL,
                        quality_scores_json TEXT,
                        trigger_type TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_state_snapshots_session_id ON local_state_snapshots (session_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_state_snapshots_character_id_target_id ON local_state_snapshots (character_id, target_id)")
            }
        }

        /**
         * v5 → v6：local_sessions 新增 composed_system_prompt 列（运行时合成的完整系统提示词）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN composed_system_prompt TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN is_public INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN proactive_chat TEXT")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN tts_config TEXT")
            }
        }

        fun get(context: Context): NekobotDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NekobotDatabase::class.java,
                    "nekobot_local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    // 仅当迁移脚本未覆盖的未来版本变更时才回退到破坏性迁移（保护现有数据）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
