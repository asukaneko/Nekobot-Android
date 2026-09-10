package com.nekobot.app.data.local.ai

import java.io.File

/**
 * 工作区检索工具（`grep` / `glob`）的纯实现，便于单元测试。
 *
 * 背景：此前 Agent 只能"整份读取文件"再自行找内容，长文件既浪费上下文，
 * 又容易让小窗口模型直接放弃。检索原语让模型先用低成本的方式定位，再精确读取。
 */

/** 检索结果条数上限（grep 命中行 / glob 命中文件）。 */
internal const val DEFAULT_SEARCH_LIMIT = 50
internal const val MAX_SEARCH_LIMIT = 500

/** grep 单行展示上限：超长行（压缩文件、单行 JSON）截断，避免一条命中吃掉整轮上下文。 */
private const val MAX_GREP_LINE_CHARS = 400

/** 默认跳过的目录：这些目录内容庞大且几乎与用户任务无关。 */
internal val DEFAULT_SEARCH_EXCLUDED_DIRS = setOf(
    ".git", "node_modules", "build", ".gradle", "__pycache__", ".idea", "dist", "target", ".venv"
)

/** 单个文件参与检索的最大字节数（超过则跳过，避免把二进制/大文件读进内存）。 */
internal const val MAX_SEARCH_FILE_BYTES = 512L * 1024L

internal data class GrepMatch(
    val relativePath: String,
    val lineNumber: Int,
    val line: String
)

internal data class GrepOutcome(
    val matches: List<GrepMatch>,
    val scannedFiles: Int,
    val truncated: Boolean,
    val error: String? = null
)

/**
 * 在工作区内按正则检索文本。
 *
 * @param root 检索根目录
 * @param pattern 正则表达式（调用方负责校验合法性）
 * @param pathPrefix 相对根目录的子路径过滤（空表示整个工作区）
 * @param fileGlob 文件名通配（如 `*.kt`），为空表示所有文本文件
 * @param limit 命中上限
 * @param caseSensitive 是否区分大小写
 */
internal fun grepWorkspace(
    root: File,
    pattern: String,
    pathPrefix: String = "",
    fileGlob: String = "",
    limit: Int = DEFAULT_SEARCH_LIMIT,
    caseSensitive: Boolean = false
): GrepOutcome {
    val regex = runCatching {
        Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }.getOrElse { error ->
        return GrepOutcome(emptyList(), 0, false, error.message ?: "正则表达式无效")
    }
    val globRegex = fileGlob.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
    val matches = mutableListOf<GrepMatch>()
    var scanned = 0
    var truncated = false

    walkWorkspace(root, pathPrefix) { file, relative ->
        if (truncated) return@walkWorkspace
        if (globRegex != null && !globRegex.matches(file.name)) return@walkWorkspace
        if (file.length() > MAX_SEARCH_FILE_BYTES) return@walkWorkspace
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@walkWorkspace
        scanned++
        text.lineSequence().forEachIndexed { index, line ->
            if (truncated) return@forEachIndexed
            if (!regex.containsMatchIn(line)) return@forEachIndexed
            matches.add(
                GrepMatch(
                    relativePath = relative,
                    lineNumber = index + 1,
                    line = line.trim().take(MAX_GREP_LINE_CHARS)
                )
            )
            if (matches.size >= limit) truncated = true
        }
    }
    return GrepOutcome(matches, scanned, truncated)
}

internal data class GlobOutcome(
    val files: List<Pair<String, Long>>,
    val truncated: Boolean
)

/**
 * 在工作区内按 glob 模式列出文件（`**` 跨目录，`*` 不跨目录）。
 */
internal fun globWorkspace(
    root: File,
    pattern: String,
    pathPrefix: String = "",
    limit: Int = DEFAULT_SEARCH_LIMIT
): GlobOutcome {
    val matcher = globToRegex(pattern)
    val files = mutableListOf<Pair<String, Long>>()
    var truncated = false
    walkWorkspace(root, pathPrefix) { file, relative ->
        if (truncated) return@walkWorkspace
        if (!matcher.matches(relative) && !matcher.matches(file.name)) return@walkWorkspace
        files.add(relative to file.length())
        if (files.size >= limit) truncated = true
    }
    return GlobOutcome(files.sortedBy { it.first }, truncated)
}

/** 遍历工作区文件，跳过隐藏/构建目录与超过大小上限的文件。 */
private inline fun walkWorkspace(
    root: File,
    pathPrefix: String,
    onFile: (file: File, relativePath: String) -> Unit
) {
    val startDir = if (pathPrefix.isBlank()) {
        root
    } else {
        File(root, pathPrefix.trim('/')).let { candidate ->
            if (candidate.isDirectory) candidate else root
        }
    }
    val rootPath = root.canonicalPath
    startDir.walkTopDown()
        .onEnter { dir ->
            dir.name !in DEFAULT_SEARCH_EXCLUDED_DIRS && !dir.name.startsWith(".")
        }
        .filter { it.isFile }
        .forEach { file ->
            val relative = runCatching {
                file.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
            }.getOrNull() ?: return@forEach
            onFile(file, relative)
        }
}

/**
 * glob → 正则：`**` 跨目录、`*` 不跨目录、`?` 单字符。
 * 未包含 `/` 的模式只匹配文件名（与常见 Glob 工具一致）。
 */
internal fun globToRegex(pattern: String): Regex {
    val normalized = pattern.trim().replace('\\', '/').removePrefix("./")
    val builder = StringBuilder()
    var index = 0
    while (index < normalized.length) {
        val char = normalized[index]
        when {
            char == '*' && normalized.getOrNull(index + 1) == '*' -> {
                builder.append(".*")
                index += 2
                if (normalized.getOrNull(index) == '/') index++
            }
            char == '*' -> {
                builder.append("[^/]*")
                index++
            }
            char == '?' -> {
                builder.append("[^/]")
                index++
            }
            char in ".+()^$|{}[]\\" -> {
                builder.append('\\').append(char)
                index++
            }
            else -> {
                builder.append(char)
                index++
            }
        }
    }
    return Regex(builder.toString())
}

/** 把 grep 结果格式化为紧凑文本（模型读起来省 token，且天然带定位信息）。 */
internal fun formatGrepOutcome(pattern: String, outcome: GrepOutcome, limit: Int): String {
    outcome.error?.let { return "正则表达式无效: $it" }
    if (outcome.matches.isEmpty()) {
        return "未找到匹配 \"$pattern\" 的内容（已扫描 ${outcome.scannedFiles} 个文件）。"
    }
    val body = outcome.matches.joinToString("\n") { match ->
        "${match.relativePath}:${match.lineNumber}: ${match.line}"
    }
    val suffix = if (outcome.truncated) "\n（已达到条数上限 $limit，请缩小 path/glob 范围或提高 max_results）" else ""
    return "匹配 ${outcome.matches.size} 条（扫描 ${outcome.scannedFiles} 个文件）：\n$body$suffix"
}

/** 把 glob 结果格式化为紧凑文本。 */
internal fun formatGlobOutcome(pattern: String, outcome: GlobOutcome, limit: Int): String {
    if (outcome.files.isEmpty()) return "未找到匹配 \"$pattern\" 的文件。"
    val body = outcome.files.joinToString("\n") { (path, size) -> "$path (${size} B)" }
    val suffix = if (outcome.truncated) "\n（已达到条数上限 $limit，请缩小范围或提高 max_results）" else ""
    return "匹配 ${outcome.files.size} 个文件：\n$body$suffix"
}
