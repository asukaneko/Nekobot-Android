package com.nekobot.app.data.local.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 跨生态插件移植辅助：安全解压、生态识别、文件清单与静态自检。
 *
 * 只做确定性的文件与文本分析，不执行插件代码；结果供 Agent 改写与用户确认参考。
 */
object PluginPortInspector {
    /** 识别出的插件生态。 */
    enum class Ecosystem(val wire: String) {
        NEKOBOT("nekobot"),
        SILLY_TAVERN("sillytavern"),
        OPERIT_TOOLPKG("operit-toolpkg"),
        DEEPSEEK_HARNESS("dsh"),
        UNKNOWN("unknown");

        val label: String
            get() = when (this) {
                NEKOBOT -> "Nekobot 原生插件"
                SILLY_TAVERN -> "SillyTavern 扩展"
                OPERIT_TOOLPKG -> "Operit ToolPkg"
                DEEPSEEK_HARNESS -> "DeepSeek Harness 插件（建议走 MCP，不移植）"
                UNKNOWN -> "未知生态"
            }
    }

    data class FileEntry(val path: String, val size: Long)

    data class Inspection(
        val ecosystem: Ecosystem,
        val manifestFile: String?,
        val manifestJson: String?,
        val files: List<FileEntry>,
        val totalBytes: Long,
        val warnings: List<String>,
        val suggestedPermissions: List<String>,
        val extractedDir: File
    )

    data class CheckResult(
        val manifestErrors: List<String>,
        val pageErrors: List<String>,
        val unsupportedApis: List<String>,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = manifestErrors.isEmpty() && pageErrors.isEmpty() && unsupportedApis.isEmpty()
    }

    // ==================== 解压与识别 ====================

    /**
     * 解压（或直接采用目录）到 [outputDir] 并分析。
     *
     * ZIP 解压沿用插件安装的路径安全规则：拒绝绝对路径、`..`、重复条目与超限包。
     */
    fun inspect(source: File, outputDir: File): Inspection {
        require(source.exists()) { "路径不存在：${source.name}" }
        if (source.isDirectory) {
            return analyze(source, outputDir)
        }
        require(source.isFile && source.name.endsWith(".zip", ignoreCase = true)) {
            "inspect 仅支持 .zip 文件或目录"
        }
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()
        unzipSafely(source, outputDir)
        return analyze(outputDir, outputDir)
    }

    private fun unzipSafely(zip: File, outputDir: File) {
        var totalBytes = 0L
        val names = mutableSetOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            var entry = stream.nextEntry
            var count = 0
            while (entry != null) {
                if (++count > MAX_ENTRIES) throw IllegalArgumentException("压缩包条目超过 $MAX_ENTRIES")
                val name = entry.name.replace('\\', '/')
                if (!PluginManifestValidator.isSafeRelativePath(name)) {
                    throw IllegalArgumentException("压缩包包含不安全路径：${entry.name}")
                }
                if (!names.add(name)) throw IllegalArgumentException("压缩包包含重复文件：$name")
                if (!entry.isDirectory) {
                    val target = File(outputDir, name).canonicalFile
                    val root = outputDir.canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) {
                        throw IllegalArgumentException("压缩包路径越界：$name")
                    }
                    target.parentFile?.mkdirs()
                    totalBytes += copyLimited(stream, target, MAX_SINGLE_FILE_BYTES, totalBytes)
                }
                stream.closeEntry()
                entry = stream.nextEntry
            }
        }
    }

    private fun copyLimited(
        input: InputStream,
        target: File,
        limit: Long,
        existingTotal: Long
    ): Long {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            written += read
            if (written > limit || existingTotal + written > MAX_TOTAL_BYTES) {
                throw IllegalArgumentException("压缩包解压后超过大小限制")
            }
            output.write(buffer, 0, read)
        }
        target.writeBytes(output.toByteArray())
        return written
    }

    private fun analyze(root: File, extractedDir: File): Inspection {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { FileEntry(it.relativeTo(root).path.replace('\\', '/'), it.length()) }
            .sortedBy { it.path }
            .toList()
        val manifestCandidates = listOf("plugin.json", "manifest.json", "package.json")
        var manifestFile: String? = null
        var manifestJson: String? = null
        manifestCandidates.forEach { candidate ->
            if (manifestJson == null) {
                val file = File(root, candidate)
                if (file.isFile && file.length() <= MAX_MANIFEST_BYTES) {
                    manifestFile = candidate
                    manifestJson = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                }
            }
        }
        val packageJson = if (manifestFile == "package.json") manifestJson else null
        val ecosystem = detectEcosystem(manifestJson, packageJson)
        val warnings = buildList {
            if (files.size > WARN_ENTRIES) add("文件数量较多（${files.size}），改写前建议先梳理入口")
            val totalBytes = files.sumOf { it.size }
            if (totalBytes > WARN_TOTAL_BYTES) {
                add("解压后体积较大（${totalBytes / 1024 / 1024} MB），注意插件包上限 32 MB")
            }
            files.filter { it.size > WARN_SINGLE_FILE_BYTES }.take(5).forEach {
                add("单文件过大：${it.path}（${it.size / 1024} KB），需拆分或精简")
            }
            if (ecosystem == Ecosystem.DEEPSEEK_HARNESS) {
                add("DSH 插件依赖 Node 运行时，无法移植；请改用 MCP 接入")
            }
        }
        val sourceTexts = files
            .filter { it.path.endsWith(".js", true) || it.path.endsWith(".html", true) }
            .take(MAX_SCAN_FILES)
            .mapNotNull { entry ->
                runCatching { File(root, entry.path).readText(Charsets.UTF_8) }.getOrNull()
            }
        return Inspection(
            ecosystem = ecosystem,
            manifestFile = manifestFile,
            manifestJson = manifestJson,
            files = files,
            totalBytes = files.sumOf { it.size },
            warnings = warnings,
            suggestedPermissions = suggestPermissions(sourceTexts.joinToString("\n"), ecosystem),
            extractedDir = extractedDir
        )
    }

    /** 生态识别规则（见设计文档 §7.4）。 */
    fun detectEcosystem(manifestJson: String?, packageJson: String?): Ecosystem {
        val root = runCatching { JsonParser.parseString(manifestJson.orEmpty()) as? JsonObject }.getOrNull()
        if (root != null) {
            if (root.has("toolpkg_id") || root.has("schema_version")) return Ecosystem.OPERIT_TOOLPKG
            if (root.has("display_name") || (root.has("js") && root.has("i18n"))) return Ecosystem.SILLY_TAVERN
            if (root.has("api_version") && root.has("id") && root.has("commands")) return Ecosystem.NEKOBOT
        }
        val packageText = packageJson.orEmpty().lowercase(Locale.ROOT)
        if (packageText.contains("\"cordis\"") || packageText.contains("deepseek-harness") || packageText.contains("\"dsh\"")) {
            return Ecosystem.DEEPSEEK_HARNESS
        }
        return Ecosystem.UNKNOWN
    }

    /** 按源码特征给出建议申请的权限（不替代人工判断）。 */
    fun suggestPermissions(source: String, ecosystem: Ecosystem): List<String> {
        if (source.isBlank()) return emptyList()
        val permissions = linkedSetOf<String>()
        if (Regex("extensionSettings|saveSettingsDebounced|storage\\.").containsMatchIn(source)) {
            permissions += "storage"
        }
        if (Regex("toastr|notify\\(").containsMatchIn(source)) permissions += "notify"
        if (Regex("getContext\\(\\)|context\\.chat|context\\.characters|chat\\.messages").containsMatchIn(source)) {
            permissions += "chat.read"
        }
        if (Regex("worldInfo|world_info|worldbooks").containsMatchIn(source)) permissions += "worldbooks.read"
        if (Regex("generateRaw|generateQuietPrompt|ai\\.complete|callAI").containsMatchIn(source)) {
            permissions += "ai.call"
        }
        if (Regex("fetch\\(|XMLHttpRequest|\\$\\.ajax|\\$\\.get\\(|httpGet|http\\.get").containsMatchIn(source)) {
            permissions += "network"
        }
        if (Regex("Tools\\.Files|workspace\\.(save|list|read|delete)|workspace_(save|list|read|delete)").containsMatchIn(source)) {
            permissions += "workspace"
        }
        if (ecosystem == Ecosystem.OPERIT_TOOLPKG) permissions += "storage"
        return permissions.toList()
    }

    // ==================== 静态自检 ====================

    /**
     * 对已安装插件做静态自检：清单校验、页面入口存在性、JS 调用的 API 名称、
     * 文件大小限额。不执行插件代码。
     */
    fun check(pluginDirectory: File, manifestJson: String): CheckResult {
        val manifest = runCatching {
            com.google.gson.Gson().fromJson(
                PluginManifestValidator.sanitizeManifestJson(manifestJson),
                PluginManifest::class.java
            )
        }.getOrNull()
        if (manifest == null) {
            return CheckResult(
                manifestErrors = listOf("plugin.json 无法解析"),
                pageErrors = emptyList(),
                unsupportedApis = emptyList(),
                warnings = emptyList()
            )
        }
        val manifestErrors = PluginManifestValidator.validate(manifest)
        val pageErrors = mutableListOf<String>()
        manifest.pages.forEach { page ->
            val entry = File(pluginDirectory, page.entry).canonicalFile
            val root = pluginDirectory.canonicalFile
            if (!entry.path.startsWith(root.path + File.separator) || !entry.isFile) {
                pageErrors += "页面 ${page.id} 的入口文件不存在：${page.entry}"
            }
        }
        val entryFile = File(pluginDirectory, manifest.entry).canonicalFile
        if (!entryFile.path.startsWith(pluginDirectory.canonicalPath + File.separator) || !entryFile.isFile) {
            pageErrors += "入口文件不存在：${manifest.entry}"
        }
        val warnings = mutableListOf<String>()
        val unsupported = linkedSetOf<String>()
        pluginDirectory.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".js", true) || it.name.endsWith(".html", true)) }
            .take(MAX_SCAN_FILES)
            .forEach { file ->
                val relative = file.relativeTo(pluginDirectory).path
                val limit = if (file.name.endsWith(".js", true)) MAX_JS_BYTES else MAX_HTML_FILE_BYTES
                if (file.length() > limit) {
                    warnings += "文件超过建议大小：$relative（${file.length() / 1024} KB）"
                }
                val source = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                unsupported += unsupportedApiCalls(source)
            }
        if (manifest.pages.isEmpty() && manifest.commands.isEmpty()) {
            warnings += "插件既没有命令也没有页面，安装后没有任何入口"
        }
        return CheckResult(
            manifestErrors = manifestErrors,
            pageErrors = pageErrors,
            unsupportedApis = unsupported.toList().sorted(),
            warnings = warnings
        )
    }

    /** 扫描源码中调用的插件 API，返回不在支持集合内的名称。 */
    fun unsupportedApiCalls(source: String): Set<String> {
        val found = linkedSetOf<String>()
        // ctx.api.xxx / NekoPlugin.api.xxx（命令运行时）
        Regex("(?:ctx|NekoPlugin)\\.api\\.([A-Za-z_][A-Za-z0-9_]*)")
            .findAll(source)
            .forEach { match ->
                val name = match.groupValues[1]
                val candidate = when (name) {
                    "getSession" -> "get_session"
                    "getMessages" -> "get_messages"
                    "notify" -> "notify"
                    "httpGet" -> "http_get"
                    "progress" -> "progress"
                    "aiComplete" -> "ai_complete"
                    "appendMessage" -> "append_message"
                    "sendMessage" -> "chat_send"
                    "createSession" -> "create_session"
                    "switchSession" -> "switch_session"
                    "render" -> "ui_render"
                    "createCharacter" -> "create_character"
                    "updateCharacter" -> "update_character"
                    "memoryRead" -> "memory_read"
                    "memoryWrite" -> "memory_write"
                    "memoryAppend" -> "memory_append"
                    "memoryEdit" -> "memory_edit"
                    "contextUsage" -> "chat.context"
                    "sessionConfig" -> "chat.session.config"
                    "promptStack" -> "chat.prompt.stack"
                    "toolCalls" -> "chat.tool.calls"
                    "storage" -> null
                    "workspace" -> null
                    else -> name
                }
                if (candidate != null && candidate !in PluginApiDispatcher.LEGACY_API_NAMES) {
                    found += "ctx.api.$name"
                }
            }
        // host.xxx.yyy（页面运行时）
        Regex("host\\.([A-Za-z_][A-Za-z0-9_]*\\.?[A-Za-z_][A-Za-z0-9_]*)")
            .findAll(source)
            .forEach { match ->
                val name = match.groupValues[1]
                val candidate = name.substringBefore('.')
                val supported = when (candidate) {
                    "system" -> name == "system.info"
                    "log" -> true
                    "storage" -> name in setOf("storage.get", "storage.set", "storage.remove", "storage.list")
                    "ui" -> name in setOf(
                        "ui.toast", "ui.close", "ui.render",
                        "ui.openPage", "ui.alert", "ui.confirm", "ui.prompt", "ui.select"
                    )
                    "chat" -> name.startsWith("chat.")
                    "characters" -> name.startsWith("characters.")
                    "worldbooks" -> name.startsWith("worldbooks.")
                    "memory" -> name in setOf(
                        "memory.read", "memory.write", "memory.append", "memory.edit"
                    )
                    "workspace" -> name in setOf(
                        "workspace.save", "workspace.list", "workspace.read", "workspace.delete"
                    )
                    "http" -> name == "http.get"
                    "progress" -> name == "progress.update"
                    "ai" -> name == "ai.complete"
                    else -> false
                }
                if (!supported) found += "host.$name"
            }
        return found
    }

    private const val MAX_ENTRIES = 256
    private const val MAX_SINGLE_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 256L * 1024
    private const val WARN_ENTRIES = 100
    private const val WARN_TOTAL_BYTES = 16L * 1024 * 1024
    private const val WARN_SINGLE_FILE_BYTES = 1L * 1024 * 1024
    private const val MAX_SCAN_FILES = 64
    private const val MAX_JS_BYTES = 512L * 1024
    private const val MAX_HTML_FILE_BYTES = 2L * 1024 * 1024
}
