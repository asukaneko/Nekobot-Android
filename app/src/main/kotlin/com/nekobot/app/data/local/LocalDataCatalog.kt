package com.nekobot.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.util.UUID

/** 数据资产的稳定作用域；设备运行状态不进入可移植目录。 */
internal enum class LocalDataScope {
    PROFILE,
    APP_SHARED,
    PROFILE_AND_SHARED,
    DEVICE
}

internal data class LocalDataDescriptor(
    val category: PortableDataCategory,
    val scope: LocalDataScope,
    val dependencies: Set<String> = emptySet(),
    val requiresArchiveEncryption: Boolean = false,
    val derivedData: Boolean = false
) {
    val id: String get() = category.id
    val tables: List<String> get() = category.tables
}

/**
 * Portable、WebDAV 和数据库档案共用的持久数据范围声明。
 * Room 表仍由 PortableDataCategory 声明，目录在启动导出前验证它与实际 schema 一致。
 */
internal object LocalDataCatalog {
    val descriptors: List<LocalDataDescriptor> = PortableDataCategory.entries.map { category ->
        LocalDataDescriptor(
            category = category,
            scope = scopeOf(category),
            dependencies = dependenciesOf(category),
            requiresArchiveEncryption = category == PortableDataCategory.CREDENTIALS,
            derivedData = category == PortableDataCategory.KNOWLEDGE
        )
    }

    fun descriptor(category: PortableDataCategory): LocalDataDescriptor =
        descriptors.first { it.category == category }

    /** 以档案数据库名为本机键，显示名变化不会生成新的同步身份。 */
    fun stableProfileId(context: Context, profileName: String): String {
        require(profileName.isNotBlank()) { "档案名不能为空" }
        val preferences = context.applicationContext.getSharedPreferences(
            "local_profile_identities",
            Context.MODE_PRIVATE
        )
        val key = "profile_$profileName"
        preferences.getString(key, null)?.takeIf(String::isNotBlank)?.let { return it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString(key, id).commit()) { "无法保存档案稳定 ID" }
        return id
    }

    fun adoptProfileIdIfAbsent(context: Context, profileName: String, sourceProfileId: String) {
        if (!sourceProfileId.matches(Regex("[0-9a-fA-F-]{36}"))) return
        val preferences = context.applicationContext.getSharedPreferences(
            "local_profile_identities",
            Context.MODE_PRIVATE
        )
        val key = "profile_$profileName"
        if (!preferences.contains(key)) {
            check(preferences.edit().putString(key, sourceProfileId.lowercase()).commit()) {
                "无法恢复档案稳定 ID"
            }
        }
    }

    fun stableSyncGroupId(context: Context, profileName: String): String {
        val preferences = context.applicationContext.getSharedPreferences(
            "local_profile_identities",
            Context.MODE_PRIVATE
        )
        val key = "sync_group_$profileName"
        preferences.getString(key, null)?.takeIf(String::isNotBlank)?.let { return it }
        val id = stableProfileId(context, profileName)
        check(preferences.edit().putString(key, id).commit()) { "无法保存同步集合 ID" }
        return id
    }

    fun adoptSyncGroupIdIfAbsent(context: Context, profileName: String, sourceSyncGroupId: String) {
        if (!sourceSyncGroupId.matches(Regex("[0-9a-fA-F-]{36}"))) return
        val preferences = context.applicationContext.getSharedPreferences(
            "local_profile_identities",
            Context.MODE_PRIVATE
        )
        val key = "sync_group_$profileName"
        if (!preferences.contains(key)) {
            check(preferences.edit().putString(key, sourceSyncGroupId.lowercase()).commit()) {
                "无法恢复同步集合 ID"
            }
        }
    }

    fun fileRoots(context: Context, category: PortableDataCategory): List<Pair<String, File>> {
        val files = context.applicationContext.filesDir
        val cache = context.applicationContext.cacheDir
        return when (category) {
            PortableDataCategory.WORLD_BOOKS -> listOf(
                "worldbook_covers" to File(files, "worldbook_covers")
            )
            PortableDataCategory.MEDIA -> listOf(
                "portraits" to File(files, "portraits"),
                "cached_portraits" to File(cache, "portraits"),
                "tts_audio" to File(files, "tts"),
                "chat_backgrounds" to File(files, "chat_backgrounds"),
                "fonts" to File(files, "fonts")
            )
            PortableDataCategory.WORKSPACE -> listOf(
                "workspace" to File(files, "workspace")
            )
            PortableDataCategory.EXTENSIONS -> listOf(
                "skills" to File(files, "skills"),
                "plugin_packages" to File(files, "plugins"),
                "plugin_files" to File(files, "plugin_files")
            )
            PortableDataCategory.STICKERS -> listOf(
                "stickers" to File(files, "stickers")
            )
            else -> emptyList()
        }
    }

    fun validateRoomCoverage(database: SupportSQLiteDatabase) {
        val assigned = descriptors.flatMap(LocalDataDescriptor::tables)
        val duplicateAssignments = assigned.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateAssignments.isEmpty()) {
            "数据归档目录重复登记数据库表：${duplicateAssignments.sorted().joinToString()}"
        }

        val databaseTables = database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        } - INTERNAL_DATABASE_TABLES
        val unclassified = databaseTables - assigned.toSet()
        val missing = assigned.toSet() - databaseTables
        require(unclassified.isEmpty() && missing.isEmpty()) {
            buildList {
                if (unclassified.isNotEmpty()) add("未登记 ${unclassified.sorted().joinToString()}")
                if (missing.isNotEmpty()) add("数据库中缺少 ${missing.sorted().joinToString()}")
            }.joinToString("；", prefix = "数据归档目录与数据库不一致：")
        }
    }

    private fun scopeOf(category: PortableDataCategory): LocalDataScope = when (category) {
        PortableDataCategory.CONVERSATIONS,
        PortableDataCategory.CHARACTERS,
        PortableDataCategory.WORLD_BOOKS,
        PortableDataCategory.MEMORIES,
        PortableDataCategory.KNOWLEDGE,
        PortableDataCategory.ANALYTICS,
        PortableDataCategory.GLOBAL_MEMORY -> LocalDataScope.PROFILE
        PortableDataCategory.WORKSPACE -> LocalDataScope.PROFILE_AND_SHARED
        PortableDataCategory.EXTENSIONS,
        PortableDataCategory.MEDIA,
        PortableDataCategory.STICKERS -> LocalDataScope.APP_SHARED
        PortableDataCategory.AI_CONFIG,
        PortableDataCategory.APP_SETTINGS,
        PortableDataCategory.CREDENTIALS -> LocalDataScope.DEVICE
    }

    private fun dependenciesOf(category: PortableDataCategory): Set<String> = when (category) {
        PortableDataCategory.CONVERSATIONS -> emptySet()
        PortableDataCategory.WORLD_BOOKS -> emptySet()
        PortableDataCategory.MEMORIES -> setOf(PortableDataCategory.CHARACTERS.id)
        PortableDataCategory.KNOWLEDGE -> emptySet()
        else -> emptySet()
    }

    private val INTERNAL_DATABASE_TABLES = setOf("android_metadata", "room_master_table")
}
