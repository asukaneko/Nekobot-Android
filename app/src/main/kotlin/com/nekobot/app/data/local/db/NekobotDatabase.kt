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
        LocalFailoverHealthEntity::class,
        LocalCharacterStateEntity::class,
        LocalRelationshipStateEntity::class,
        LocalCharacterMemoryEntity::class,
        LocalStateSnapshotEntity::class,
        LocalHookEntity::class,
        LocalTaskEntity::class,
        LocalWorkflowEntity::class,
        LocalSkillEntity::class,
        LocalToolEntity::class,
        LocalMcpServerEntity::class,
        LocalApiKeyEntity::class,
        LocalMessageFavoriteEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class NekobotDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun characterDao(): CharacterDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun failoverHealthDao(): FailoverHealthDao
    abstract fun characterStateDao(): CharacterStateDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun memoryDao(): MemoryDao
    abstract fun stateSnapshotDao(): StateSnapshotDao
    abstract fun hookDao(): HookDao
    abstract fun taskDao(): TaskDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun skillDao(): SkillDao
    abstract fun toolDao(): ToolDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun messageFavoriteDao(): MessageFavoriteDao

    /**
     * 当前 db 文件名（含 .db 扩展），用于派生 SharedPreferences 文件名（如 token 用量隔离）。
     * 由 [get] / [switchProfile] 在创建实例时通过反射注入。
     */
    @Volatile
    var dbName: String = com.nekobot.app.data.local.PrefsManager.DEFAULT_DB_NAME + ".db"
        private set

    companion object {
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

        /**
         * v7 → v8：local_sessions 新增 share_config 列（公开分享配置）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN share_config TEXT")
            }
        }

        /**
         * v8 → v9：local_sessions 新增 archived / plot_choice_style 列。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN plot_choice_style TEXT")
            }
        }

        /**
         * v9 → v10：
         * 1) local_sessions 新增 archive_session_id 列（用于"提取归档 N 轮"功能）。
         * 2) 新增 8 张扩展功能表（hooks / tasks / workflows / skills / tools / mcp_servers / api_keys / message_favorites），
         *    让本地模式具备与远程模式同款的扩展能力。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN archive_session_id TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_hooks (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        event TEXT NOT NULL,
                        description TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        scope TEXT NOT NULL DEFAULT 'global',
                        priority INTEGER NOT NULL DEFAULT 100,
                        actions_json TEXT NOT NULL DEFAULT '[]',
                        conditions_json TEXT,
                        permissions_json TEXT,
                        timeout_ms INTEGER NOT NULL DEFAULT 3000,
                        max_retries INTEGER NOT NULL DEFAULT 0,
                        trigger_mode TEXT NOT NULL DEFAULT 'always',
                        condition_logic TEXT NOT NULL DEFAULT 'and',
                        character_id TEXT,
                        conversation_id TEXT,
                        user_id TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        kind TEXT NOT NULL DEFAULT 'custom',
                        name TEXT NOT NULL,
                        description TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        trigger TEXT NOT NULL DEFAULT 'manual',
                        config_json TEXT,
                        target_session_id TEXT,
                        prompt TEXT,
                        created_at TEXT NOT NULL,
                        last_run TEXT,
                        next_run TEXT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_workflows (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        trigger TEXT NOT NULL DEFAULT 'manual',
                        config_json TEXT,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_skills (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT,
                        aliases_json TEXT NOT NULL DEFAULT '[]',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        parameters_json TEXT,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_tools (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        parameters_json TEXT,
                        implementation_json TEXT,
                        builtin INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_mcp_servers (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        transport TEXT NOT NULL DEFAULT 'streamable-http',
                        description TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        auto_connect INTEGER NOT NULL DEFAULT 0,
                        connected INTEGER NOT NULL DEFAULT 0,
                        tool_count INTEGER NOT NULL DEFAULT 0,
                        url TEXT,
                        command TEXT,
                        args_json TEXT,
                        env_json TEXT,
                        builtin INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        last_connected_at TEXT
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_api_keys (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        key TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_message_favorites (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        message_ids_json TEXT NOT NULL DEFAULT '[]',
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_message_favorites_session_id ON local_message_favorites (session_id)")
            }
        }

        /**
         * v10 → v11：
         * 1) local_ai_models 新增故障转移策略列：token_limit_daily / token_limit_weekly / failover_timeout / input_price / output_price。
         * 2) 新增 local_failover_health 表：持久化模型健康状态（连续失败/冷却期），跨重启保留。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_ai_models ADD COLUMN token_limit_daily INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_ai_models ADD COLUMN token_limit_weekly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_ai_models ADD COLUMN failover_timeout INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_ai_models ADD COLUMN input_price REAL")
                db.execSQL("ALTER TABLE local_ai_models ADD COLUMN output_price REAL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_failover_health (
                        model_id TEXT NOT NULL PRIMARY KEY,
                        consecutive_failures INTEGER NOT NULL DEFAULT 0,
                        last_failure_code INTEGER NOT NULL DEFAULT 0,
                        last_failure_at_ms INTEGER NOT NULL DEFAULT 0,
                        cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
                        daily_failures INTEGER NOT NULL DEFAULT 0,
                        daily_failures_date TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }

        /** 数据库实例缓存：按 db 名（含扩展）区分，支持多 profile 切换。 */
        private val INSTANCES = mutableMapOf<String, NekobotDatabase>()

        /**
         * v11 → v12：local_sessions 新增 session_mode 列（会话模式：character/agent/group）。
         * 用于 agent 模式进度卡片显示等场景，默认 'character' 与现有会话一致。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_sessions ADD COLUMN session_mode TEXT NOT NULL DEFAULT 'character'")
            }
        }

        /**
         * v12 → v13：local_messages 新增 thinking_cards 列（agent 模式持久化进度卡片 JSON）。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_messages ADD COLUMN thinking_cards TEXT")
            }
        }

        fun get(context: Context): NekobotDatabase =
            get(context, com.nekobot.app.data.local.PrefsManager.DEFAULT_DB_NAME)

        /** 按指定 profile 名获取数据库实例。同名 db 复用缓存。 */
        fun get(context: Context, profileName: String): NekobotDatabase = synchronized(this) {
            val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
            INSTANCES[dbName]?.let { return@synchronized it }
            Room.databaseBuilder(
                context.applicationContext,
                NekobotDatabase::class.java,
                dbName
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                // 仅当迁移脚本未覆盖的未来版本变更时才回退到破坏性迁移（保护现有数据）
                .fallbackToDestructiveMigration()
                .build()
                .also {
                    it.dbName = dbName
                    INSTANCES[dbName] = it
                }
        }

        /** 切换激活的数据库：关闭并移除当前实例缓存，下次 get() 时按新名重建。 */
        fun switchProfile(context: Context, profileName: String) = synchronized(this) {
            val newName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
            // 关闭并移除所有已缓存的实例（避免持有旧 db 连接）
            INSTANCES.values.forEach { runCatching { it.close() } }
            INSTANCES.clear()
            // 预热新 profile
            get(context, profileName)
        }

        /** 关闭并清理指定 profile（用于删除 db 文件前）。 */
        fun closeProfile(profileName: String) = synchronized(this) {
            val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
            INSTANCES.remove(dbName)?.run { runCatching { close() } }
        }

        /** 删除指定 profile 的 db 文件（需先关闭连接）。 */
        fun deleteProfileFile(context: Context, profileName: String): Boolean = synchronized(this) {
            if (profileName == com.nekobot.app.data.local.PrefsManager.DEFAULT_DB_NAME) return@synchronized false
            closeProfile(profileName)
            val dbName = if (profileName.endsWith(".db")) profileName else "$profileName.db"
            val files = listOf(
                context.getDatabasePath(dbName),
                context.getDatabasePath("$dbName-journal"),
                context.getDatabasePath("$dbName-wal"),
                context.getDatabasePath("$dbName-shm")
            )
            files.forEach { runCatching { if (it.exists()) it.delete() } }
            true
        }
    }
}
