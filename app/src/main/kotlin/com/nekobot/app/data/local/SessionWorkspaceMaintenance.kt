package com.nekobot.app.data.local

import android.content.Context
import com.nekobot.app.data.local.db.NekobotDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 磁盘上的一个会话工作区目录及其占用情况。 */
internal data class SessionWorkspaceEntry(
    val sessionId: String,
    /** 会话仍存在时为会话名称；会话已被删除时为 null（此时界面只显示 id）。 */
    val sessionName: String?,
    /** 命中会话所在的档案显示名；未命中为 null。 */
    val profileLabel: String?,
    /** 旧版 `agent_workspaces/` 下的目录（不再被新代码写入）。 */
    val legacy: Boolean,
    val fileCount: Int,
    val sizeBytes: Long,
    val lastModified: Long
) {
    /** 会话是否仍存在。 */
    val sessionExists: Boolean get() = sessionName != null
}

/** 工作区列表的排序字段。 */
internal enum class WorkspaceSortKey {
    /** 按会话时间（工作区最后修改时间）。 */
    SESSION_TIME,

    /** 按会话占用大小。 */
    SIZE
}

/**
 * 工作区列表排序：字段 + 方向。
 *
 * 默认时间从新到旧、大小从大到小；再次选中同一字段时翻转方向，
 * 切换到另一字段时回到该字段的默认方向。
 */
internal data class WorkspaceSortOrder(
    val key: WorkspaceSortKey = WorkspaceSortKey.SESSION_TIME,
    val descending: Boolean = true
) {
    /** 点击某个排序字段后得到的新排序。 */
    fun selecting(key: WorkspaceSortKey): WorkspaceSortOrder =
        if (this.key == key) WorkspaceSortOrder(key, descending = !descending)
        else WorkspaceSortOrder(key, descending = true)
}

/** 按 [order] 排序工作区；取值相同时按会话 id / 旧版标记兜底，保证顺序稳定。 */
internal fun sortWorkspaceEntries(
    entries: List<SessionWorkspaceEntry>,
    order: WorkspaceSortOrder
): List<SessionWorkspaceEntry> {
    val byField = when (order.key) {
        WorkspaceSortKey.SESSION_TIME -> compareBy<SessionWorkspaceEntry> { it.lastModified }
        WorkspaceSortKey.SIZE -> compareBy<SessionWorkspaceEntry> { it.sizeBytes }
    }
    // 只翻转主字段方向，同值时仍按会话 id 升序，避免切换方向后同大小项来回跳动
    val comparator = if (order.descending) byField.reversed() else byField
    return entries.sortedWith(
        comparator.thenBy { it.sessionId }.thenBy { it.legacy }
    )
}

/** 工作区内的一个文件或文件夹。 */
internal data class WorkspaceItemEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
) {
    val name: String get() = relativePath.substringAfterLast('/')
}

/** 单个工作区最多统计/展示的条目数，避免异常目录拖垮界面。 */
internal const val WORKSPACE_ENTRY_LIMIT = 20_000

/**
 * 会话工作区的纯文件系统操作，只认两层目录：
 * `filesDir/workspace/<会话 id>`（当前）与 `filesDir/agent_workspaces/<会话 id>`（旧版）。
 *
 * 不做任何 mkdir / 迁移副作用，因此可以安全地用于"只读扫描 + 按需删除"的维护界面。
 */
internal object SessionWorkspaceFs {

    private const val WORKSPACE_DIR = "workspace"
    private const val LEGACY_DIR = "agent_workspaces"
    private const val SHARED_DIR = "shared"

    data class Root(val dir: File, val legacy: Boolean)

    data class ScannedWorkspace(
        val sessionId: String,
        val legacy: Boolean,
        val fileCount: Int,
        val sizeBytes: Long,
        val lastModified: Long
    )

    fun roots(filesDir: File): List<Root> = listOf(
        Root(File(filesDir, WORKSPACE_DIR), legacy = false),
        Root(File(filesDir, LEGACY_DIR), legacy = true)
    )

    /**
     * 扫描所有"含文件"的工作区目录；共享工作区 `workspace/shared` 与空目录不返回。
     * 符号链接目录会被跳过，不做任何跟随。
     */
    fun scan(filesDir: File): List<ScannedWorkspace> {
        val scanned = mutableListOf<ScannedWorkspace>()
        for (root in roots(filesDir)) {
            val children = root.dir.listFiles() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                if (Files.isSymbolicLink(child.toPath())) continue
                val sessionId = child.name
                if (!isSafeSessionDirName(sessionId)) continue
                if (!root.legacy && sessionId == SHARED_DIR) continue
                val files = collectFiles(child)
                if (files.isEmpty()) continue
                scanned += ScannedWorkspace(
                    sessionId = sessionId,
                    legacy = root.legacy,
                    fileCount = files.size,
                    sizeBytes = files.sumOf(File::length),
                    lastModified = child.lastModified()
                )
            }
        }
        return scanned
    }

    /** 解析单个工作区目录；id 不安全、越界、是链接或不存在时返回 null。 */
    fun resolveWorkspace(filesDir: File, sessionId: String, legacy: Boolean): File? {
        if (!isSafeSessionDirName(sessionId)) return null
        val base = runCatching { File(filesDir, if (legacy) LEGACY_DIR else WORKSPACE_DIR).canonicalFile }
            .getOrNull() ?: return null
        val dir = runCatching { File(base, sessionId).canonicalFile }.getOrNull() ?: return null
        if (dir.parentFile != base) return null
        return dir.takeIf { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
    }

    /** 递归列出工作区内全部条目（文件夹在前，再按路径排序）。 */
    fun items(root: File): List<WorkspaceItemEntry> {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        val result = mutableListOf<WorkspaceItemEntry>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(canonicalRoot to "")
        while (queue.isNotEmpty() && result.size < WORKSPACE_ENTRY_LIMIT) {
            val (dir, prefix) = queue.removeFirst()
            val children = dir.listFiles()?.sortedWith(compareBy { it.name.lowercase() }) ?: continue
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                val relativePath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    result += WorkspaceItemEntry(relativePath, true, directorySize(child))
                    queue.add(child to relativePath)
                } else {
                    result += WorkspaceItemEntry(relativePath, false, child.length())
                }
            }
        }
        return result.sortedBy { it.relativePath.lowercase() }
    }

    /** 删除整个工作区目录。 */
    fun deleteWorkspace(filesDir: File, sessionId: String, legacy: Boolean): Boolean {
        val root = resolveWorkspace(filesDir, sessionId, legacy) ?: return false
        if (Files.isSymbolicLink(root.toPath())) return runCatching { root.delete() }.getOrDefault(false)
        return runCatching {
            deleteTreeWithoutFollowingLinks(root)
            !root.exists()
        }.getOrDefault(false)
    }

    /** 删除工作区内的某个文件或文件夹；路径必须落在该工作区内。 */
    fun deleteItem(root: File, relativePath: String): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val normalized = relativePath.trim().replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return false
        val target = runCatching { File(canonicalRoot, normalized).canonicalFile }.getOrNull() ?: return false
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) return false
        if (Files.isSymbolicLink(target.toPath())) return runCatching { target.delete() }.getOrDefault(false)
        if (!target.exists()) return false
        return runCatching {
            if (target.isDirectory) {
                deleteTreeWithoutFollowingLinks(target)
                !target.exists()
            } else {
                target.delete()
            }
        }.getOrDefault(false)
    }

    private fun collectFiles(dir: File): List<File> {
        val result = ArrayList<File>()
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty() && result.size < WORKSPACE_ENTRY_LIMIT) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) queue.add(child) else if (result.size < WORKSPACE_ENTRY_LIMIT) result.add(child)
            }
        }
        return result
    }

    private fun directorySize(dir: File): Long = collectFiles(dir).sumOf(File::length)

    private fun isSafeSessionDirName(name: String): Boolean =
        name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name
}

/**
 * 设置 → 数据维护 使用的工作区维护入口。
 *
 * 工作区目录按 `filesDir` 存放，**跨档案共用**，所以会话名要在全部档案里查，
 * 否则别的档案的会话会被误判成"已删除"。
 */
internal class SessionWorkspaceMaintenance(
    context: Context,
    private val prefs: PrefsManager
) {
    private val appContext = context.applicationContext
    private val filesDir: File get() = appContext.filesDir

    suspend fun list(): List<SessionWorkspaceEntry> = withContext(Dispatchers.IO) {
        val scanned = SessionWorkspaceFs.scan(filesDir)
        val names = lookupSessionNames(scanned.map { it.sessionId })
        scanned.map { workspace ->
            val hit = names[workspace.sessionId]
            SessionWorkspaceEntry(
                sessionId = workspace.sessionId,
                sessionName = hit?.first,
                profileLabel = hit?.second,
                legacy = workspace.legacy,
                fileCount = workspace.fileCount,
                sizeBytes = workspace.sizeBytes,
                lastModified = workspace.lastModified
            )
        }.let { entries ->
            // 会话仍存在的排在前面；组内按默认排序（时间从新到旧）
            sortWorkspaceEntries(entries.sortedByDescending { it.sessionExists }, WorkspaceSortOrder())
        }
    }

    suspend fun listItems(entry: SessionWorkspaceEntry): List<WorkspaceItemEntry> = withContext(Dispatchers.IO) {
        SessionWorkspaceFs.resolveWorkspace(filesDir, entry.sessionId, entry.legacy)
            ?.let(SessionWorkspaceFs::items)
            .orEmpty()
    }

    suspend fun deleteWorkspace(entry: SessionWorkspaceEntry): Boolean = withContext(Dispatchers.IO) {
        SessionWorkspaceFs.deleteWorkspace(filesDir, entry.sessionId, entry.legacy)
    }

    suspend fun deleteItem(entry: SessionWorkspaceEntry, relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val root = SessionWorkspaceFs.resolveWorkspace(filesDir, entry.sessionId, entry.legacy)
                ?: return@withContext false
            SessionWorkspaceFs.deleteItem(root, relativePath)
        }

    /** 返回 会话 id → (会话名, 档案显示名)；当前档案优先查询。 */
    private suspend fun lookupSessionNames(ids: List<String>): Map<String, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        val remaining = ids.toMutableSet()
        val found = mutableMapOf<String, Pair<String, String>>()
        val activeName = prefs.activeDbName.removeSuffix(".db")
        val profiles = prefs.listDbProfiles().sortedByDescending { it.name.removeSuffix(".db") == activeName }
        profiles.forEach { profile ->
            if (remaining.isEmpty()) return@forEach
            val db = runCatching { NekobotDatabase.get(appContext, profile.name) }.getOrNull() ?: return@forEach
            val dao = db.sessionDao()
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next()
                val session = runCatching { dao.getById(id) }.getOrNull() ?: continue
                found[id] = (session.name.ifBlank { id }) to profile.displayName
                iterator.remove()
            }
        }
        return found
    }
}
