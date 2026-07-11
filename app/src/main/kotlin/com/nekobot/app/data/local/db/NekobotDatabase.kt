package com.nekobot.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 3,
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

        fun get(context: Context): NekobotDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NekobotDatabase::class.java,
                    "nekobot_local.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
