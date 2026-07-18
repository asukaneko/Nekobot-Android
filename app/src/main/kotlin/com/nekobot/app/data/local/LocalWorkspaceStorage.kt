package com.nekobot.app.data.local

import java.io.File

/**
 * 本地会话工作区的唯一目录入口。
 *
 * UI 上传、下载和 Agent 工具必须共用 filesDir/workspace/<sessionId>。
 * 旧版 Agent 曾使用 agent_workspaces/<sessionId>，首次访问时将其中缺失的文件复制到新目录。
 */
internal object LocalWorkspaceStorage {
    private val migrationLock = Any()

    fun resolve(filesDir: File, sessionId: String): File? = synchronized(migrationLock) {
        val safeSessionId = sessionId.trim()
        if (safeSessionId.isEmpty()) return@synchronized null

        runCatching {
            val workspaceBase = File(filesDir, "workspace").canonicalFile
            val workspace = File(workspaceBase, safeSessionId).canonicalFile
            if (!workspace.isSameOrChildOf(workspaceBase)) return@runCatching null
            workspace.mkdirs()

            migrateLegacyWorkspace(filesDir, safeSessionId, workspace)
            workspace
        }.getOrNull()
    }

    private fun migrateLegacyWorkspace(filesDir: File, sessionId: String, workspace: File) {
        val legacyBase = File(filesDir, "agent_workspaces").canonicalFile
        val legacy = File(legacyBase, sessionId).canonicalFile
        if (!legacy.isSameOrChildOf(legacyBase) || !legacy.isDirectory) return

        legacy.walkTopDown().forEach { source ->
            if (source == legacy) return@forEach
            val relativePath = source.relativeTo(legacy).path
            val target = File(workspace, relativePath).canonicalFile
            if (!target.isSameOrChildOf(workspace)) return@forEach

            if (source.isDirectory) {
                target.mkdirs()
            } else if (!target.exists()) {
                target.parentFile?.mkdirs()
                runCatching { source.copyTo(target, overwrite = false) }
            }
        }
    }

    private fun File.isSameOrChildOf(parent: File): Boolean =
        path == parent.path || path.startsWith(parent.path + File.separator)
}
