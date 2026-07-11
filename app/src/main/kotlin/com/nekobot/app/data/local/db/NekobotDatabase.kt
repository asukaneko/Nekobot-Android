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
        LocalCharacterMemoryEntity::class
    ],
    version = 4,
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

        fun get(context: Context): NekobotDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NekobotDatabase::class.java,
                    "nekobot_local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // 仅当迁移脚本未覆盖的未来版本变更时才回退到破坏性迁移（保护现有数据）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
