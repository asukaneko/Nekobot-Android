package com.nekobot.app.data.local.ai

import java.io.File
import java.net.URI

/** 授权检查与文件执行共用的路径解析，避免参数别名或输出路径绕过确认。 */
internal class AgentWorkspacePaths(workspaceRoot: File?, sharedWorkspaceRoot: File?) {
    private val workspace = workspaceRoot?.canonicalFile
    private val shared = sharedWorkspaceRoot?.canonicalFile

    fun resolve(path: String, allowRoot: Boolean = false): File? {
        val trimmed = path.trim().replace('\\', '/')
        val sharedPath = trimmed.startsWith("shared://", ignoreCase = true)
        val root = (if (sharedPath) shared else workspace) ?: return null
        val relative = if (sharedPath) trimmed.substring("shared://".length).removePrefix("/")
        else trimmed.removePrefix("/workspace/").let { if (it == "/workspace") "" else it }
        if (relative.isBlank()) return root.takeIf { allowRoot }
        val target = File(root, relative).canonicalFile
        return target.takeIf { it.isWithin(root) }
    }

    fun relative(file: File): String {
        shared?.takeIf { file.isWithin(it) }?.let {
            return "shared://" + file.relativeTo(it).invariantSeparatorsPath.ifBlank { "." }
        }
        return workspace?.let { file.relativeTo(it).invariantSeparatorsPath.ifBlank { "." } } ?: file.name
    }

    fun downloadTarget(args: Map<String, Any>): File? {
        val defaultName = runCatching {
            File(URI(args["url"]?.toString().orEmpty()).path).name.takeIf(String::isNotBlank)
        }.getOrNull() ?: "download_${System.currentTimeMillis()}"
        return resolve(args["save_path"]?.toString().orEmpty().ifBlank { defaultName })
    }

    fun epubOutput(args: Map<String, Any>): File? {
        val source = resolve(args.agentWorkspacePath()) ?: return null
        val requested = args["output_path"]?.toString().orEmpty()
        return resolve(requested.ifBlank { relative(File(source.parentFile, "${source.nameWithoutExtension}.txt")) })
    }

    fun sharedMutationPath(toolName: String, args: Map<String, Any>): String? {
        val target = when (toolName) {
            "workspace_create_file", "workspace_edit_file", "workspace_delete_file", "file_write", "file_edit" ->
                resolve(args.agentWorkspacePath())
            "download_file" -> downloadTarget(args)
            "workspace_extract_epub" -> epubOutput(args)
            else -> null
        } ?: return null
        return target.takeIf { shared != null && it.isWithin(shared) }?.let(::relative)
    }

    private fun File.isWithin(root: File): Boolean =
        path == root.path || path.startsWith(root.path + File.separator)
}

internal fun Map<String, Any>.agentWorkspacePath(): String =
    sequenceOf("path", "filename", "file_path")
        .map { this[it]?.toString().orEmpty() }
        .firstOrNull(String::isNotBlank).orEmpty()
