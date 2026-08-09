package com.nekobot.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.PlotGraphManager
import com.nekobot.app.data.local.ai.getGlobalPlotGraphManager

/** 导入前的 profile 剧情存储原貌，用于失败时精确恢复（包括“尚未迁移”状态）。 */
internal data class LocalPlotStoryProfileSnapshot(
    val initialized: Boolean,
    val graphJson: String?,
    val plotChoices: Map<String, String>,
    val legacyMigration: LocalPlotStoryLegacyMigrationSnapshot?
)

/** 仅在目标仍处于 legacy 迁移范围时保存，用于导入失败后恢复全局迁移状态。 */
internal data class LocalPlotStoryLegacyMigrationSnapshot(
    val version: Int,
    val eligibleFrozen: Boolean,
    val eligibleProfiles: Set<String>,
    val graphJson: String?,
    val plotChoices: Map<String, String>
)

/**
 * Room 数据库之外、按数据库 profile 隔离的故事地图存储。
 *
 * 旧版把全部 profile 聚合在 `plot_graph` / `plot_choices`。应用启动时会冻结当时已存在的
 * profile 集合，按各数据库的 sessionId 拆分到独立 scope；完成后新建 profile 只会得到
 * 空 scope，不可能误继承 legacy 数据。
 */
internal object LocalPlotStoryStore {
    private const val LEGACY_GRAPH_PREFERENCES = "plot_graph"
    private const val LEGACY_GRAPH_KEY = "graph"
    private const val LEGACY_CHOICES_PREFERENCES = "plot_choices"
    private const val PROFILE_PREFERENCES_PREFIX = "plot_story_"
    private const val MIGRATION_PREFERENCES = "plot_story_migration"
    private const val MIGRATION_VERSION_KEY = "version"
    private const val MIGRATION_FROZEN_KEY = "eligible_frozen"
    private const val MIGRATION_ELIGIBLE_KEY = "eligible_profiles"
    private const val MIGRATION_VERSION = 1
    private const val INITIALIZED_KEY = "initialized"
    private const val GRAPH_KEY = "graph"
    private const val CHOICE_KEY_PREFIX = "choice:"
    private val gson = Gson()
    private val activeProfileLock = Any()
    @Volatile
    private var activeDatabaseName: String? = null
    @Volatile
    private var activeOwner: Any? = null

    /** 切换 scope 与重载全局图谱必须和迟到的旧 profile 写入互斥。 */
    fun activateProfile(databaseName: String, owner: Any, initialize: () -> Unit) {
        synchronized(activeProfileLock) {
            activeDatabaseName = normalizedDatabaseName(databaseName)
            activeOwner = owner
            try {
                initialize()
            } catch (error: Throwable) {
                if (activeOwner === owner) {
                    activeOwner = null
                    activeDatabaseName = null
                    getGlobalPlotGraphManager().clear()
                }
                throw error
            }
        }
    }

    /** close 后立即围住迟到任务；同名 profile 被覆盖重建时旧 owner 也不会重新获得写权限。 */
    fun deactivateProfile(owner: Any) {
        synchronized(activeProfileLock) {
            if (activeOwner === owner) {
                activeOwner = null
                activeDatabaseName = null
            }
        }
    }

    /** 仅当调用方仍属于当前 profile 时原子执行图谱/选项写入。 */
    fun runIfActiveProfile(databaseName: String, owner: Any, action: () -> Unit): Boolean =
        synchronized(activeProfileLock) {
            if (
                activeOwner !== owner ||
                activeDatabaseName != normalizedDatabaseName(databaseName)
            ) return@synchronized false
            action()
            true
        }

    fun needsLegacyMigration(context: Context): Boolean = context
        .getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(MIGRATION_VERSION_KEY, 0) < MIGRATION_VERSION

    fun hasLegacyData(context: Context): Boolean =
        !context.getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LEGACY_GRAPH_KEY, null).isNullOrBlank() ||
            context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
                .all.values.any { it is String }

    /** 在启动早期冻结旧 profile 名单；之后创建的 profile 永远不参与 legacy 迁移。 */
    @Synchronized
    fun freezeLegacyProfiles(context: Context, databaseNames: Collection<String>) {
        if (!needsLegacyMigration(context)) return
        val migration = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
        if (migration.getBoolean(MIGRATION_FROZEN_KEY, false)) {
            if (migration.getStringSet(MIGRATION_ELIGIBLE_KEY, emptySet()).isNullOrEmpty()) {
                finishLegacyMigration(context)
            }
            return
        }
        if (!hasLegacyData(context)) {
            finishLegacyMigration(context)
            return
        }
        val eligible = databaseNames.mapTo(linkedSetOf(), ::normalizedDatabaseName)
        if (eligible.isEmpty()) {
            finishLegacyMigration(context)
            return
        }
        check(
            migration.edit()
                .putBoolean(MIGRATION_FROZEN_KEY, true)
                .putStringSet(MIGRATION_ELIGIBLE_KEY, eligible)
                .commit()
        ) { "无法冻结旧故事地图迁移范围" }
    }

    fun isLegacyProfilePending(context: Context, databaseName: String): Boolean {
        if (!needsLegacyMigration(context)) return false
        val migration = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
        return normalizedDatabaseName(databaseName) in
            migration.getStringSet(MIGRATION_ELIGIBLE_KEY, emptySet()).orEmpty()
    }

    /** 只迁移一个实际被访问的旧 profile；scope 已提交时重试绝不会覆盖新数据。 */
    @Synchronized
    fun migrateLegacyProfile(
        context: Context,
        databaseName: String,
        sessionIds: Set<String>
    ) {
        if (!isLegacyProfilePending(context, databaseName)) return
        val target = profilePreferences(context, databaseName)
        if (target.getBoolean(INITIALIZED_KEY, false)) {
            resolveLegacyProfile(context, databaseName)
            return
        }
        val legacyGraph = context
            .getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LEGACY_GRAPH_KEY, null)
        val legacyChoices = readLegacyChoices(
            context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE).all
        )
        val legacyManager = PlotGraphManager()
        legacyGraph?.let(legacyManager::fromJson)
        val editor = target.edit()
            .clear()
            .putBoolean(INITIALIZED_KEY, true)
            .putString(GRAPH_KEY, legacyManager.toJsonForConversations(sessionIds))
        sessionIds.sorted().forEach { sessionId ->
            legacyChoices[sessionId]?.let { json ->
                editor.putString(choiceKey(sessionId), json)
            }
        }
        check(editor.commit()) { "无法迁移数据库 $databaseName 的故事地图" }
        resolveLegacyProfile(context, databaseName)
    }

    fun loadGraph(context: Context, databaseName: String): String? {
        if (usesLegacyStorage(context, databaseName)) {
            return context.getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
                .getString(LEGACY_GRAPH_KEY, null)
        }
        ensureInitialized(context, databaseName)
        return profilePreferences(context, databaseName).getString(GRAPH_KEY, null)
    }

    fun persistGraph(
        context: Context,
        databaseName: String,
        synchronous: Boolean = false
    ): Boolean {
        if (usesLegacyStorage(context, databaseName)) {
            val editor = context
                .getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(LEGACY_GRAPH_KEY, getGlobalPlotGraphManager().toJson())
            return if (synchronous) editor.commit() else {
                editor.apply()
                true
            }
        }
        ensureInitialized(context, databaseName)
        val editor = profilePreferences(context, databaseName)
            .edit()
            .putString(GRAPH_KEY, getGlobalPlotGraphManager().toJson())
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    fun savePlotChoices(
        context: Context,
        databaseName: String,
        sessionId: String,
        choicesJson: String
    ) {
        if (usesLegacyStorage(context, databaseName)) {
            check(context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(sessionId, choicesJson)
                .commit()
            ) { "无法保存旧剧情选项" }
            return
        }
        ensureInitialized(context, databaseName)
        check(profilePreferences(context, databaseName)
            .edit()
            .putString(choiceKey(sessionId), choicesJson)
            .commit()
        ) { "无法保存剧情选项" }
    }

    fun getPlotChoices(context: Context, databaseName: String, sessionId: String): String? {
        if (usesLegacyStorage(context, databaseName)) {
            return context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
                .getString(sessionId, null)
        }
        ensureInitialized(context, databaseName)
        return profilePreferences(context, databaseName).getString(choiceKey(sessionId), null)
    }

    fun removePlotChoices(context: Context, databaseName: String, sessionId: String) {
        if (usesLegacyStorage(context, databaseName)) {
            check(context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(sessionId)
                .commit()
            ) { "无法删除旧剧情选项" }
            return
        }
        ensureInitialized(context, databaseName)
        check(profilePreferences(context, databaseName)
            .edit()
            .remove(choiceKey(sessionId))
            .commit()
        ) { "无法删除剧情选项" }
    }

    /** 在同一 scope commit 中保存选项缓存和对应的完整图谱。 */
    fun persistGraphAndPlotChoices(
        context: Context,
        databaseName: String,
        sessionId: String,
        choicesJson: String
    ): Boolean {
        if (usesLegacyStorage(context, databaseName)) {
            val choicesSaved = context
                .getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(sessionId, choicesJson)
                .commit()
            val graphSaved = context
                .getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(LEGACY_GRAPH_KEY, getGlobalPlotGraphManager().toJson())
                .commit()
            return choicesSaved && graphSaved
        }
        ensureInitialized(context, databaseName)
        return profilePreferences(context, databaseName)
            .edit()
            .putString(choiceKey(sessionId), choicesJson)
            .putString(GRAPH_KEY, getGlobalPlotGraphManager().toJson())
            .commit()
    }

    /** 从目标 profile 的持久快照中只截取其数据库实际拥有的会话。 */
    fun capture(
        context: Context,
        databaseName: String,
        sessionIds: Set<String>
    ): DbProfileStoryData {
        val useLegacy = usesLegacyStorage(context, databaseName)
        if (!useLegacy) ensureInitialized(context, databaseName)
        val preferences = if (useLegacy) {
            context.getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
        } else {
            profilePreferences(context, databaseName)
        }
        val manager = PlotGraphManager()
        preferences.getString(
            if (useLegacy) LEGACY_GRAPH_KEY else GRAPH_KEY,
            null
        )?.let(manager::fromJson)
        val choicesPreferences = if (useLegacy) {
            context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
        } else {
            preferences
        }
        val choices = sessionIds.sorted().mapNotNull { sessionId ->
            choicesPreferences.getString(
                if (useLegacy) sessionId else choiceKey(sessionId),
                null
            )
                ?.takeIf(::isJsonObject)
                ?.let { sessionId to it }
        }.toMap(linkedMapOf())
        return DbProfileStoryData(
            graphJson = manager.toJsonForConversations(sessionIds),
            plotChoices = choices
        )
    }

    /** 不触发 legacy 迁移地记录当前 profile sidecar，供整个导入事务失败时恢复。 */
    fun snapshot(context: Context, databaseName: String): LocalPlotStoryProfileSnapshot {
        val preferences = profilePreferences(context, databaseName)
        val legacyMigration = if (isLegacyProfilePending(context, databaseName)) {
            val migration = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
            LocalPlotStoryLegacyMigrationSnapshot(
                version = migration.getInt(MIGRATION_VERSION_KEY, 0),
                eligibleFrozen = migration.getBoolean(MIGRATION_FROZEN_KEY, false),
                eligibleProfiles = migration.getStringSet(MIGRATION_ELIGIBLE_KEY, emptySet())
                    .orEmpty()
                    .toSet(),
                graphJson = context
                    .getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
                    .getString(LEGACY_GRAPH_KEY, null),
                plotChoices = readStringValues(
                    context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE).all
                )
            )
        } else {
            null
        }
        return LocalPlotStoryProfileSnapshot(
            initialized = preferences.getBoolean(INITIALIZED_KEY, false),
            graphJson = preferences.getString(GRAPH_KEY, null),
            plotChoices = readChoices(preferences.all),
            legacyMigration = legacyMigration
        )
    }

    /** 使用一次 SharedPreferences commit 精确恢复导入前状态。 */
    fun restore(
        context: Context,
        databaseName: String,
        snapshot: LocalPlotStoryProfileSnapshot
    ) {
        synchronized(activeProfileLock) {
            if (activeDatabaseName == normalizedDatabaseName(databaseName)) {
                activeOwner = null
                activeDatabaseName = null
                getGlobalPlotGraphManager().clear()
            }
            val editor = profilePreferences(context, databaseName).edit().clear()
            if (snapshot.initialized) editor.putBoolean(INITIALIZED_KEY, true)
            snapshot.graphJson?.let { editor.putString(GRAPH_KEY, it) }
            snapshot.plotChoices.forEach { (sessionId, json) ->
                editor.putString(choiceKey(sessionId), json)
            }
            check(editor.commit()) { "无法恢复故事地图" }

            snapshot.legacyMigration?.let { legacy ->
                val graphEditor = context
                    .getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                legacy.graphJson?.let { graphEditor.putString(LEGACY_GRAPH_KEY, it) }
                check(graphEditor.commit()) { "无法恢复旧故事地图" }

                val choicesEditor = context
                    .getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                legacy.plotChoices.forEach { (sessionId, json) ->
                    choicesEditor.putString(sessionId, json)
                }
                check(choicesEditor.commit()) { "无法恢复旧剧情选项" }

                // 最后恢复 pending 标记，避免其他读取者先看到尚未写回完整的 legacy 数据。
                check(
                    context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .putInt(MIGRATION_VERSION_KEY, legacy.version)
                        .putBoolean(MIGRATION_FROZEN_KEY, legacy.eligibleFrozen)
                        .putStringSet(MIGRATION_ELIGIBLE_KEY, legacy.eligibleProfiles)
                        .commit()
                ) { "无法恢复旧故事地图迁移状态" }
            }
        }
    }

    /**
     * 用导入包完整替换目标 profile 的剧情 sidecar，不修改当前活动 profile 的内存管理器。
     */
    fun replace(
        context: Context,
        databaseName: String,
        allowedImportedSessionIds: Set<String>,
        story: DbProfileStoryData?
    ) {
        val pendingLegacy = isLegacyProfilePending(context, databaseName)
        val importedChoices = story?.plotChoices.orEmpty().toMutableMap()
        require(importedChoices.keys.all { it in allowedImportedSessionIds }) {
            "剧情选项包含不属于当前数据库的会话"
        }

        val manager = PlotGraphManager()
        manager.replaceConversationsFromJson(
            conversationIdsToReplace = allowedImportedSessionIds,
            graphJson = story?.graphJson,
            allowedConversationIds = allowedImportedSessionIds
        )

        // 缓存缺失时由已校验图谱重建，避免地图已恢复但聊天页看不到当前剧情选项。
        if (story != null) {
            allowedImportedSessionIds.sorted().forEach { sessionId ->
                if (sessionId !in importedChoices) {
                    val graphNodes = manager.getGraph(sessionId)["nodes"] as? List<*>
                    if (!graphNodes.isNullOrEmpty()) {
                        importedChoices[sessionId] = gson.toJson(
                            mapOf(
                                "choices" to manager.getLatestChoices(sessionId).map { it.toDict() }
                            )
                        )
                    }
                }
            }
        }

        synchronized(activeProfileLock) {
            if (activeDatabaseName == normalizedDatabaseName(databaseName)) {
                activeOwner = null
                activeDatabaseName = null
                getGlobalPlotGraphManager().clear()
            }
            val editor = profilePreferences(context, databaseName)
                .edit()
                .clear()
                .putBoolean(INITIALIZED_KEY, true)
                .putString(GRAPH_KEY, manager.toJson())
            importedChoices.forEach { (sessionId, json) ->
                editor.putString(choiceKey(sessionId), json)
            }
            check(editor.commit()) { "无法保存故事地图" }
            if (pendingLegacy) {
                // scope 已完整提交即可使用；marker 失败会在下一次 load 时重试，不能反向判定导入失败。
                runCatching { resolveLegacyProfile(context, databaseName) }
            }
        }
    }

    fun clearProfile(context: Context, databaseName: String) {
        synchronized(activeProfileLock) {
            if (activeDatabaseName == normalizedDatabaseName(databaseName)) {
                activeOwner = null
                activeDatabaseName = null
                getGlobalPlotGraphManager().clear()
            }
            check(profilePreferences(context, databaseName).edit().clear().commit()) {
                "无法清理故事地图"
            }
            if (isLegacyProfilePending(context, databaseName)) {
                resolveLegacyProfile(context, databaseName)
            }
        }
    }

    fun clearAllProfiles(context: Context, databaseNames: Collection<String>) {
        synchronized(activeProfileLock) {
            activeOwner = null
            activeDatabaseName = null
            getGlobalPlotGraphManager().clear()
            databaseNames.distinct().forEach { databaseName ->
                check(profilePreferences(context, databaseName).edit().clear().commit()) {
                    "无法清理数据库 $databaseName 的故事地图"
                }
            }
            finishLegacyMigration(context)
        }
    }

    /** 迁移完成后，任何缺少 scope 的 profile 都是新 profile，必须初始化为空。 */
    @Synchronized
    private fun ensureInitialized(context: Context, databaseName: String) {
        val target = profilePreferences(context, databaseName)
        if (target.getBoolean(INITIALIZED_KEY, false)) {
            if (isLegacyProfilePending(context, databaseName)) {
                // scope 已完整落盘后即以它为准；marker 推进失败只重试，不回退到 stale legacy。
                runCatching { resolveLegacyProfile(context, databaseName) }
            }
            return
        }
        check(!isLegacyProfilePending(context, databaseName)) {
            "数据库 $databaseName 的旧故事地图尚未迁移"
        }
        val editor = target.edit()
            .clear()
            .putBoolean(INITIALIZED_KEY, true)
            .putString(GRAPH_KEY, PlotGraphManager().toJson())
        check(editor.commit()) { "无法迁移故事地图" }
    }

    private fun profilePreferences(context: Context, databaseName: String) =
        context.getSharedPreferences(profilePreferencesName(databaseName), Context.MODE_PRIVATE)

    internal fun profilePreferencesName(databaseName: String): String {
        return "$PROFILE_PREFERENCES_PREFIX${normalizedDatabaseName(databaseName)}"
    }

    private fun normalizedDatabaseName(databaseName: String): String =
        databaseName.trim().let { name ->
            if (name.endsWith(".db", ignoreCase = true)) name else "$name.db"
        }

    /** target scope 一旦初始化成功，即使迁移 marker 暂时提交失败也不得回读旧全局数据。 */
    private fun usesLegacyStorage(context: Context, databaseName: String): Boolean =
        isLegacyProfilePending(context, databaseName) &&
            !profilePreferences(context, databaseName).getBoolean(INITIALIZED_KEY, false)

    @Synchronized
    private fun resolveLegacyProfile(context: Context, databaseName: String) {
        if (!needsLegacyMigration(context)) return
        val migration = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
        val eligible = migration.getStringSet(MIGRATION_ELIGIBLE_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        if (!eligible.remove(normalizedDatabaseName(databaseName))) return
        if (eligible.isEmpty()) {
            finishLegacyMigration(context)
        } else {
            check(migration.edit().putStringSet(MIGRATION_ELIGIBLE_KEY, eligible).commit()) {
                "无法更新旧故事地图迁移进度"
            }
        }
    }

    @Synchronized
    private fun finishLegacyMigration(context: Context) {
        check(
            context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putInt(MIGRATION_VERSION_KEY, MIGRATION_VERSION)
                .commit()
        ) { "无法完成故事地图迁移" }
        context.getSharedPreferences(LEGACY_GRAPH_PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(LEGACY_CHOICES_PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun choiceKey(sessionId: String): String = "$CHOICE_KEY_PREFIX$sessionId"

    private fun readChoices(values: Map<String, *>): Map<String, String> = values
        .mapNotNull { (key, value) ->
            if (key.startsWith(CHOICE_KEY_PREFIX) && value is String) {
                key.removePrefix(CHOICE_KEY_PREFIX) to value
            } else {
                null
            }
        }
        .toMap(linkedMapOf())

    private fun readLegacyChoices(values: Map<String, *>): Map<String, String> = values
        .mapNotNull { (sessionId, value) ->
            (value as? String)
                ?.takeIf(::isJsonObject)
                ?.let { sessionId to it }
        }
        .toMap(linkedMapOf())

    private fun readStringValues(values: Map<String, *>): Map<String, String> = values
        .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        .toMap(linkedMapOf())

    private fun isJsonObject(json: String): Boolean =
        runCatching { JsonParser.parseString(json).isJsonObject }.getOrDefault(false)
}
