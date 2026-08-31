package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.model.SkillFileInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal data class DownloadedSkillPackage(
    val name: String,
    val description: String?,
    val aliases: List<String>,
    val skillMd: String,
    val referenceMd: String?,
    val sourceUrl: String,
    /**
     * 该 Skill 目录下的全部文件（任意同名文件与文件夹，含 SKILL.md 同级的所有内容）。
     * 安装时会原样写盘，不做固定目录白名单过滤。
     */
    val files: Map<String, ByteArray>
)

internal fun validateSkillNameValue(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotBlank()) { "Skill 名称不能为空" }
    require(trimmed.length <= 100) { "Skill 名称不能超过 100 个字符" }
    require(trimmed != "." && trimmed != ".." && '/' !in trimmed && '\\' !in trimmed) {
        "Skill 名称不能包含路径分隔符"
    }
    require(trimmed.none { it.code < 32 }) { "Skill 名称不能包含控制字符" }
    return trimmed
}

internal fun skillDirectoryName(name: String): String {
    val safe = validateSkillNameValue(name)
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
        .trim('-', '.', '_')
        .take(100)
    require(safe.isNotBlank()) { "Skill 名称无法生成有效目录" }
    return safe
}

/**
 * 从 GitHub 仓库、GitHub 子目录、SKILL.md 直链或 ZIP 下载 Skill 包。
 *
 * 一个 ZIP 中存在多个同级 SKILL.md 时会解析为多个独立 Skill。所有限制均在解压写盘前执行，
 * 避免超大响应、ZIP 炸弹和路径穿越。
 */
internal class SkillPackageDownloader(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        internal const val MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024
        internal const val MAX_EXPANDED_BYTES = 50L * 1024 * 1024
        internal const val MAX_FILE_BYTES = 12L * 1024 * 1024
        internal const val MAX_FILES = 500
    }

    private data class DownloadCandidate(
        val url: String,
        val subpath: String? = null,
        val requestedSkillName: String? = null
    )

    fun download(sourceUrl: String): List<DownloadedSkillPackage> {
        val source = sourceUrl.trim()
        val uri = runCatching { URI(source) }
            .getOrElse { throw IllegalArgumentException("Skill URL 格式无效") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "仅支持 http/https URL"
        }
        require(!uri.host.isNullOrBlank()) { "Skill URL 缺少主机名" }

        val candidates = resolveCandidates(uri)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                val response = downloadBytes(candidate.url)
                return if (looksLikeZip(response.bytes, response.contentType, candidate.url)) {
                    parseZip(response.bytes, source, candidate.subpath, candidate.requestedSkillName)
                } else {
                    parseMarkdown(response.bytes, source, uri)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(lastError?.message ?: "无法下载 Skill")
    }

    private data class DownloadResponse(val bytes: ByteArray, val contentType: String?)

    private fun downloadBytes(url: String): DownloadResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Nekobot-Android-Skill-Installer")
            .header("Accept", "application/zip, application/octet-stream, text/markdown, text/plain")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败 (HTTP ${response.code})")
            }
            val body = response.body ?: throw IllegalStateException("下载内容为空")
            if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                throw IllegalStateException("Skill 包超过 25 MB 限制")
            }
            DownloadResponse(
                bytes = readBounded(body.byteStream(), MAX_DOWNLOAD_BYTES, "Skill 包超过 25 MB 限制"),
                contentType = body.contentType()?.toString()
            )
        }
    }

    private fun resolveCandidates(uri: URI): List<DownloadCandidate> {
        val host = uri.host.lowercase(Locale.ROOT)
        val path = uri.path.orEmpty()
        if (host == "skills.sh") {
            val parts = path.trim('/').split('/').filter { it.isNotBlank() }
            require(parts.size >= 3) { "skills.sh URL 应包含 owner/repo/skill" }
            val owner = parts[0]
            val repo = parts[1]
            val skillName = parts[2]
            return githubBranchCandidates(owner, repo, requestedSkillName = skillName)
        }

        if (host == "gitlab.com" && !path.endsWith(".zip", true)) {
            val parts = path.trim('/').split('/').filter { it.isNotBlank() }
            require(parts.size >= 2) { "GitLab URL 缺少仓库路径" }
            val marker = parts.indexOf("-")
            val repoEnd = if (marker >= 2) marker else 2
            val ownerPath = parts.take(repoEnd - 1).joinToString("/")
            val repo = parts[repoEnd - 1].removeSuffix(".git")
            if (marker >= 0 && parts.size >= marker + 4 && (parts[marker + 1] == "tree" || parts[marker + 1] == "blob")) {
                val ref = parts[marker + 2]
                val target = parts.drop(marker + 3).joinToString("/")
                val subpath = if (parts[marker + 1] == "blob") target.substringBeforeLast('/', "") else target
                return listOf(
                    DownloadCandidate(
                        "https://gitlab.com/$ownerPath/$repo/-/archive/$ref/$repo-$ref.zip",
                        subpath
                    )
                )
            }
            return listOf("main", "master").map { ref ->
                DownloadCandidate("https://gitlab.com/$ownerPath/$repo/-/archive/$ref/$repo-$ref.zip")
            }
        }

        if (host != "github.com" || path.endsWith(".zip", true)) {
            return listOf(DownloadCandidate(uri.toString()))
        }

        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return listOf(DownloadCandidate(uri.toString()))
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")

        if (parts.size >= 5 && (parts[2] == "tree" || parts[2] == "blob")) {
            val ref = parts[3]
            val targetPath = parts.drop(4).joinToString("/")
            val subpath = if (parts[2] == "blob") targetPath.substringBeforeLast('/', "") else targetPath
            return listOf(
                DownloadCandidate(
                    "https://codeload.github.com/$owner/$repo/zip/refs/heads/$ref",
                    subpath
                ),
                DownloadCandidate(
                    "https://codeload.github.com/$owner/$repo/zip/refs/tags/$ref",
                    subpath
                )
            )
        }

        return githubBranchCandidates(owner, repo)
    }

    private fun githubBranchCandidates(
        owner: String,
        repo: String,
        requestedSkillName: String? = null
    ): List<DownloadCandidate> {
        val defaultBranch = runCatching {
            val response = downloadBytes("https://api.github.com/repos/$owner/$repo")
            JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).asJsonObject
                .get("default_branch")?.asString
        }.getOrNull()
        return listOfNotNull(defaultBranch, "main", "master")
            .distinct()
            .map { branch ->
                DownloadCandidate(
                    url = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch",
                    requestedSkillName = requestedSkillName
                )
            }
    }

    private fun looksLikeZip(bytes: ByteArray, contentType: String?, url: String): Boolean {
        val signature = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4b.toByte() &&
            bytes[2] in listOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte()) &&
            bytes[3] in listOf(0x04.toByte(), 0x06.toByte(), 0x08.toByte())
        return signature ||
            url.substringBefore('?').endsWith(".zip", true) ||
            contentType?.contains("zip", true) == true
    }

    private fun parseMarkdown(bytes: ByteArray, sourceUrl: String, uri: URI): List<DownloadedSkillPackage> {
        require(bytes.size <= MAX_FILE_BYTES) { "SKILL.md 超过 12 MB 限制" }
        val content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require(content.isNotBlank()) { "SKILL.md 内容为空" }
        require(content.trimStart().startsWith("---") || content.lineSequence().any { it.trimStart().startsWith("# ") }) {
            "URL 返回的内容不是有效的 SKILL.md"
        }
        val fallback = uri.path.substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { uri.host.substringBefore('.') }
        val metadata = parseMetadata(content, fallback)
        return listOf(
            DownloadedSkillPackage(
                name = metadata.first,
                description = metadata.second,
                aliases = metadata.third,
                skillMd = content,
                referenceMd = null,
                sourceUrl = sourceUrl,
                files = mapOf("SKILL.md" to bytes)
            )
        )
    }

    private fun parseZip(
        zipBytes: ByteArray,
        sourceUrl: String,
        requestedSubpath: String?,
        requestedSkillName: String?
    ): List<DownloadedSkillPackage> {
        val rawEntries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val path = normalizeArchivePath(entry.name)
                if (path.isBlank() || shouldIgnore(path)) continue
                if (rawEntries.size >= MAX_FILES) throw IllegalStateException("Skill 包文件数超过 $MAX_FILES")
                val content = readBounded(zip, MAX_FILE_BYTES, "单个 Skill 文件超过 12 MB 限制")
                totalBytes += content.size
                if (totalBytes > MAX_EXPANDED_BYTES) {
                    throw IllegalStateException("Skill 包解压后超过 50 MB 限制")
                }
                rawEntries[path] = content
            }
        }
        require(rawEntries.isNotEmpty()) { "ZIP 中没有可用文件" }

        val entries = stripCommonArchiveRoot(rawEntries)
        val subpath = requestedSubpath
            ?.replace('\\', '/')
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
        val scopedEntries = if (subpath == null) {
            entries
        } else {
            entries.filterKeys { it == subpath || it.startsWith("$subpath/") }
        }
        require(scopedEntries.isNotEmpty()) { "仓库中找不到目录: $subpath" }

        val candidates = scopedEntries.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", true) }
            .map { it.substringBeforeLast('/', "") }
            .distinct()
        require(candidates.isNotEmpty()) { "Skill 包中缺少 SKILL.md" }

        val selectedCandidates = if (requestedSkillName.isNullOrBlank()) {
            candidates
        } else {
            candidates.filter {
                it.substringAfterLast('/').equals(requestedSkillName, ignoreCase = true)
            }.also {
                require(it.isNotEmpty()) { "仓库中找不到 Skill「$requestedSkillName」" }
            }
        }
        val roots = if ("" in selectedCandidates) {
            listOf("")
        } else {
            selectedCandidates.filter { candidate ->
                selectedCandidates.none { other ->
                    other != candidate && other.isNotBlank() && candidate.startsWith("$other/")
                }
            }
        }

        return roots.map { root ->
            val prefix = root.takeIf { it.isNotBlank() }?.plus("/") ?: ""
            // packageFiles 保留该 Skill 根目录下的所有文件与文件夹（含 SKILL.md 同级内容），
            // 不按 scripts/、resources/ 等固定目录做白名单过滤，安装后原样保存。
            val packageFiles = scopedEntries
                .filterKeys { root.isBlank() || it.startsWith(prefix) }
                .mapKeys { (path, _) -> path.removePrefix(prefix) }
            val skillEntry = packageFiles.entries.firstOrNull {
                it.key.equals("SKILL.md", true)
            } ?: throw IllegalStateException("$root 中缺少 SKILL.md")
            val skillMd = skillEntry.value.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            val folderName = root.substringAfterLast('/').ifBlank { "skill" }
            val metadata = parseMetadata(skillMd, folderName)
            val referenceMd = packageFiles.entries.firstOrNull {
                it.key.equals("reference.md", true)
            }?.value?.toString(Charsets.UTF_8)
            DownloadedSkillPackage(
                name = metadata.first,
                description = metadata.second,
                aliases = metadata.third,
                skillMd = skillMd,
                referenceMd = referenceMd,
                sourceUrl = sourceUrl,
                files = packageFiles
            )
        }
    }

    private fun normalizeArchivePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        require(!raw.startsWith("/") && !raw.startsWith("\\") && !Regex("^[A-Za-z]:").containsMatchIn(raw)) {
            "ZIP 包含绝对路径"
        }
        require(normalized.split('/').none { it == ".." }) { "ZIP 包含不安全路径" }
        return normalized.split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")
    }

    private fun shouldIgnore(path: String): Boolean {
        val segments = path.split('/')
        return segments.any { it == ".git" || it == "__pycache__" } ||
            path.endsWith(".pyc", true) ||
            path.endsWith("/.DS_Store", true) ||
            path == ".DS_Store"
    }

    private fun stripCommonArchiveRoot(entries: Map<String, ByteArray>): Map<String, ByteArray> {
        val firstSegments = entries.keys.map { it.substringBefore('/') }.distinct()
        if (firstSegments.size != 1 || entries.keys.any { '/' !in it }) return entries
        val prefix = "${firstSegments.single()}/"
        return entries.mapKeys { (path, _) -> path.removePrefix(prefix) }
    }

    private fun parseMetadata(content: String, fallbackName: String): Triple<String, String?, List<String>> {
        var frontMatter = emptyMap<String, String>()
        val listAliases = mutableListOf<String>()
        if (content.startsWith("---")) {
            val end = content.indexOf("\n---", startIndex = 3)
            if (end > 0) {
                val values = linkedMapOf<String, StringBuilder>()
                var currentKey: String? = null
                content.substring(3, end).lineSequence().forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator > 0 && !line.first().isWhitespace()) {
                        currentKey = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                        values[currentKey] = StringBuilder(
                            line.substring(separator + 1).trim().trim('"', '\'')
                        )
                    } else if (currentKey != null && line.isNotBlank()) {
                        val value = line.trim()
                        if (currentKey == "aliases" && value.startsWith("- ")) {
                            listAliases += value.removePrefix("- ").trim().trim('"', '\'')
                        } else {
                            val builder = values.getValue(currentKey)
                            if (builder.toString() in setOf(">", "|", ">-", "|-")) builder.clear()
                            if (builder.isNotEmpty()) builder.append(' ')
                            builder.append(value)
                        }
                    }
                }
                frontMatter = values.mapValues { it.value.toString().trim() }
            }
        }
        val heading = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
        val name = validateSkillNameValue(
            frontMatter["name"].orEmpty().ifBlank { heading.orEmpty() }.ifBlank { fallbackName }
        )

        val description = frontMatter["description"]
            ?.takeIf { it.isNotBlank() }
            ?: content.lineSequence()
                .map { it.trim() }
                .dropWhile { it.isBlank() || it == "---" || it.startsWith("#") || ':' in it && content.startsWith("---") }
                .firstOrNull { it.isNotBlank() }
                ?.take(500)
        val aliasesRaw = frontMatter["aliases"].orEmpty().trim().removeSurrounding("[", "]")
        val aliases = (aliasesRaw.split(',', '，') + listAliases)
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() }
            .distinct()
        return Triple(name, description, aliases)
    }

    private fun readBounded(input: InputStream, limit: Long, error: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IllegalStateException(error)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** Android 本地 Skills 目录存储，结构与原仓库 data/skills 保持一致。 */
internal class LocalSkillStorage(private val root: File) {
    private val gson = Gson()

    init {
        root.mkdirs()
    }

    /**
     * 保存/更新 Skill 的核心 Markdown 文件。
     *
     * 存储目录不再假设固定结构：SKILL.md 同级目录下的所有文件和文件夹（包括
     * 安装来源带来的任意目录）都会原样保留，本方法只写入传入的字段，
     * 不会删除或覆盖其他同级内容。
     */
    fun save(
        name: String,
        skillMd: String?,
        referenceMd: String?,
        sourceUrl: String? = null
    ) {
        val directory = directoryFor(name)
        directory.mkdirs()
        File(directory, "SKILL.md").writeText(
            skillMd?.takeIf { it.isNotBlank() } ?: defaultSkillMd(name),
            Charsets.UTF_8
        )
        if (referenceMd != null) {
            File(directory, "reference.md").writeText(referenceMd, Charsets.UTF_8)
        }
        saveSource(directory, sourceUrl)
    }

    fun install(pkg: DownloadedSkillPackage, overwrite: Boolean) {
        val target = directoryFor(pkg.name)
        if (target.exists() && !overwrite) throw IllegalStateException("Skill「${pkg.name}」已存在")
        val staging = File(root, ".install-${UUID.randomUUID()}")
        try {
            for ((relativePath, content) in pkg.files) {
                val file = resolveInside(staging, relativePath)
                file.parentFile?.mkdirs()
                file.writeBytes(content)
            }
            if (!File(staging, "SKILL.md").exists()) {
                File(staging, "SKILL.md").writeText(pkg.skillMd, Charsets.UTF_8)
            }
            // SKILL.md 同级的所有文件与文件夹（含自定义目录）都已由 pkg.files 原样写盘，
            // 这里仅保证 scripts/、resources/ 目录存在，即使源包未提供也不会重建/清空。
            File(staging, "scripts").mkdirs()
            File(staging, "resources").mkdirs()
            saveSource(staging, pkg.sourceUrl)
            if (target.exists()) target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun rename(oldName: String, newName: String) {
        val old = directoryFor(oldName)
        val target = directoryFor(newName)
        if (!old.exists() || old.canonicalPath == target.canonicalPath) return
        require(!target.exists()) { "Skill「$newName」的存储目录已存在" }
        if (!old.renameTo(target)) {
            old.copyRecursively(target, overwrite = false)
            old.deleteRecursively()
        }
    }

    fun delete(name: String) {
        val directory = directoryFor(name)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IllegalStateException("删除 Skill 存储失败")
        }
    }

    fun exists(name: String): Boolean = directoryFor(name).isDirectory

    fun skillMd(name: String): String? = readOptionalText(name, "SKILL.md")

    fun referenceMd(name: String): String? = readOptionalText(name, "reference.md")

    fun sourceUrl(name: String): String? {
        val config = File(directoryFor(name), "config.json")
        if (!config.isFile) return null
        return runCatching {
            JsonParser.parseString(config.readText(Charsets.UTF_8)).asJsonObject
                .get("source_url")?.asString
        }.getOrNull()
    }

    fun listFiles(name: String): List<SkillFileInfo> {
        val directory = directoryFor(name)
        if (!directory.isDirectory) return emptyList()
        return directory.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                SkillFileInfo(
                    name = file.name,
                    path = relative,
                    size = file.length(),
                    type = when {
                        relative == "SKILL.md" -> "core"
                        relative.equals("reference.md", true) -> "reference"
                        relative.equals("LICENSE.txt", true) -> "license"
                        relative.startsWith("scripts/") -> "script"
                        relative.startsWith("resources/") -> "resource"
                        else -> "other"
                    }
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    fun readText(name: String, relativePath: String): String {
        val file = resolveInside(directoryFor(name), relativePath)
        require(file.isFile) { "文件「$relativePath」不存在" }
        require(file.length() <= SkillPackageDownloader.MAX_FILE_BYTES) { "文件过大，无法读取" }
        val bytes = file.readBytes()
        require(bytes.take(4096).none { it == 0.toByte() }) { "二进制文件无法作为文本读取" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readOptionalText(name: String, relativePath: String): String? =
        runCatching { readText(name, relativePath) }.getOrNull()

    private fun directoryFor(name: String): File {
        return resolveInside(root, skillDirectoryName(name))
    }

    private fun resolveInside(base: File, relativePath: String): File {
        val target = File(base, relativePath.replace('\\', '/')).canonicalFile
        val canonicalBase = base.canonicalFile
        require(target.path == canonicalBase.path || target.path.startsWith(canonicalBase.path + File.separator)) {
            "Skill 文件路径越界"
        }
        return target
    }

    private fun saveSource(directory: File, sourceUrl: String?) {
        val config = File(directory, "config.json")
        val existing = if (config.isFile) {
            runCatching { JsonParser.parseString(config.readText()).asJsonObject }.getOrNull()
        } else null
        val data = existing ?: com.google.gson.JsonObject()
        if (sourceUrl.isNullOrBlank()) data.remove("source_url") else data.addProperty("source_url", sourceUrl)
        config.writeText(gson.toJson(data), Charsets.UTF_8)
    }

    private fun defaultSkillMd(name: String): String = """
        # $name

        ## 功能描述

        请描述这个技能解决的问题、适用场景和限制。

        ## 使用说明

        1. 说明何时应使用此技能。
        2. 说明需要读取的资源或执行步骤。
        3. 说明预期输出。

        ## 参考资料

        详细资料可写入 [reference.md](reference.md)，脚本放入 scripts/，附加文件放入 resources/；
        也可以把任意同级的文件与目录放在 SKILL.md 旁边，它们都会被原样保存并在运行时读取。
    """.trimIndent()
}
