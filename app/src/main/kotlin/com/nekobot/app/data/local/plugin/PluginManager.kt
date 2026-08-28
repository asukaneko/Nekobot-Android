package com.nekobot.app.data.local.plugin

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.LocalSlashCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit

class PluginInstallException(message: String) : IllegalArgumentException(message)

/**
 * 本地插件管理器。
 *
 * 插件不是 APK，也不会被当作 Kotlin/Java 类加载；ZIP 中的 JS 入口在无文件访问的
 * WebView 中运行，所有 Android 能力都必须通过清单权限和下方 Bridge 显式调用。
 */
class PluginManager(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val pluginRoot = File(appContext.filesDir, "plugins").apply { mkdirs() }
    private val storage = appContext.getSharedPreferences("nekobot_plugin_storage", Context.MODE_PRIVATE)
    private val settings = appContext.getSharedPreferences("nekobot_plugin_settings", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _installed.value = discoverPlugins()
    }

    internal fun commandSuggestions(query: String): List<PluginCommandBinding> {
        val normalized = query.trim().lowercase()
        return commandBindings()
            .filter { binding ->
                normalized.isBlank() || normalized == "/" ||
                    binding.trigger.startsWith(normalized) ||
                    binding.trigger.contains(normalized.removePrefix("/"))
            }
    }

    internal fun findCommand(input: String): PluginCommandBinding? {
        val normalized = PluginManifestValidator.normalizeCommand(input)
        return commandBindings().firstOrNull { it.trigger == normalized }
    }

    suspend fun install(uri: Uri, acceptedThirdPartyAgreement: Boolean): InstalledPlugin =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!acceptedThirdPartyAgreement) {
                    throw PluginInstallException("安装第三方插件前必须同意免责协议")
                }
                installBlocking(uri)
            }
        }

    /**
     * 以源码方式直接创建并安装插件（供 Agent 的 plugin_use 工具使用）。
     *
     * 与 ZIP 安装走同一套清单校验和落位流程；同 ID 插件会被覆盖更新，
     * 并保留其原有启用状态。
     */
    suspend fun installFromSource(
        manifestJson: String,
        entrySource: String,
        extraFiles: Map<String, String> = emptyMap()
    ): InstalledPlugin = installMutex.withLock {
        withContext(Dispatchers.IO) {
            installFromSourceBlocking(manifestJson, entrySource, extraFiles)
        }
    }

    /** 从 HTTPS 地址下载插件 ZIP 并安装；调用方负责第三方免责协议确认。 */
    suspend fun installFromUrl(url: String, acceptedThirdPartyAgreement: Boolean): InstalledPlugin =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!acceptedThirdPartyAgreement) {
                    throw PluginInstallException("安装第三方插件前必须同意免责协议")
                }
                installFromUrlBlocking(url)
            }
        }

    /** 修改已安装插件的清单和/或入口源码；保留启用状态与安装时间。 */
    suspend fun updatePlugin(
        pluginId: String,
        manifestJson: String? = null,
        entrySource: String? = null,
        extraFiles: Map<String, String> = emptyMap()
    ): InstalledPlugin = installMutex.withLock {
        withContext(Dispatchers.IO) {
            updatePluginBlocking(pluginId, manifestJson, entrySource, extraFiles)
        }
    }

    /** 读取非内置插件的清单原文与入口源码；不存在时返回 null。 */
    fun readPluginDetail(pluginId: String): PluginDetail? {
        if (BuiltInPlugins.isBuiltIn(pluginId)) return null
        val directory = pluginDirectory(pluginId)?.takeIf { it.isDirectory } ?: return null
        val plugin = _installed.value.firstOrNull { it.id == pluginId } ?: return null
        val manifestJson = runCatching {
            File(directory, MANIFEST_ENTRY).readText(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val entrySource = runCatching {
            val entry = File(directory, plugin.entry).canonicalFile
            if (!entry.path.startsWith(directory.canonicalPath + File.separator) || !entry.isFile) null
            else entry.readText(Charsets.UTF_8)
        }.getOrNull()
        return PluginDetail(plugin, manifestJson, entrySource)
    }

    /** 按插件 ID + 命令名/别名查找命令绑定；包含停用插件，便于调用方给出准确错误。 */
    internal fun findPluginCommand(pluginId: String, command: String): Pair<InstalledPlugin, PluginCommandBinding>? {
        val plugin = _installed.value.firstOrNull { it.id == pluginId } ?: return null
        val normalized = PluginManifestValidator.normalizeCommand(command)
        val binding = pluginCommandBindings(listOf(plugin), includeDisabled = true)
            .firstOrNull { normalized in it.aliases }
            ?: return null
        return plugin to binding
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean) =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                if (BuiltInPlugins.isBuiltIn(pluginId)) {
                    settings.edit().putBoolean(builtInEnabledKey(pluginId), enabled).apply()
                } else {
                    val directory = pluginDirectory(pluginId) ?: return@withContext
                    val state = readState(directory)
                    writeState(directory, state.copy(enabled = enabled))
                }
                reload()
            }
        }

    suspend fun uninstall(pluginId: String) =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                if (BuiltInPlugins.isBuiltIn(pluginId)) {
                    throw PluginInstallException("内置插件不能卸载，只能停用")
                }
                val directory = pluginDirectory(pluginId) ?: return@withContext
                if (directory.exists()) directory.deleteRecursively()
                removePluginStorage(pluginId)
                reload()
            }
        }

    /** 执行一条插件命令；返回值会作为本地会话中的 assistant 回复。 */
    internal suspend fun execute(
        binding: PluginCommandBinding,
        sessionId: String,
        args: String,
        repository: LocalRepository
    ): String {
        val plugin = installed.value.firstOrNull { it.id == binding.pluginId && it.enabled }
            ?: return "插件未安装或已停用：${binding.pluginId}"
        val directory = pluginDirectory(plugin.id)
            ?: return "插件目录不存在：${plugin.id}"
        val entry = File(directory, plugin.entry).canonicalFile
        val root = directory.canonicalFile
        if (!entry.path.startsWith(root.path + File.separator) || !entry.isFile) {
            return "插件入口文件不存在：${plugin.entry}"
        }
        val source = runCatching { entry.readText(Charsets.UTF_8) }.getOrElse {
            return "无法读取插件入口：${it.message ?: "未知错误"}"
        }
        if (source.length > MAX_SCRIPT_CHARS) return "插件入口文件过大"

        return try {
            withTimeout(RUNTIME_TIMEOUT_MS) {
                executeInWebView(plugin, binding, sessionId, args, source, repository)
            }
        } catch (_: TimeoutCancellationException) {
            "插件执行超时（${RUNTIME_TIMEOUT_MS / 1000} 秒）"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            "插件执行失败：${error.message ?: "未知错误"}"
        }
    }

    private fun installBlocking(uri: Uri): InstalledPlugin {
        val tempZip = File.createTempFile("nekobot-plugin-", ".zip", appContext.cacheDir)
        try {
            copyUriToFile(uri, tempZip)
            return installZipBlocking(tempZip)
        } finally {
            tempZip.delete()
        }
    }

    /**
     * 从已下载的 ZIP 安装插件。
     *
     * 安装成功时 staging 目录会被整体重命名为插件目录，finally 的清理不会影响
     * 已安装内容；任何失败都会清理 staging，保持插件根目录干净。
     */
    private fun installZipBlocking(zip: File): InstalledPlugin {
        val staging = File(pluginRoot, ".staging-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            unzipToStaging(zip, staging)
            if (!File(staging, MANIFEST_ENTRY).isFile) {
                throw PluginInstallException("ZIP 根目录缺少 plugin.json")
            }
            return installStaging(staging)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** 解压 ZIP 到 staging，执行路径安全、数量和大小检查。 */
    private fun unzipToStaging(zip: File, staging: File) {
        var totalBytes = 0L
        val names = mutableSetOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { zipStream ->
            var entry = zipStream.nextEntry
            var count = 0
            while (entry != null) {
                if (++count > MAX_ENTRIES) throw PluginInstallException("插件文件数量超过 $MAX_ENTRIES")
                val name = entry.name.replace('\\', '/')
                if (!PluginManifestValidator.isSafeRelativePath(name)) {
                    throw PluginInstallException("ZIP 包含不安全路径：${entry.name}")
                }
                if (!names.add(name)) throw PluginInstallException("ZIP 包含重复文件：$name")
                if (!entry.isDirectory) {
                    val target = File(staging, name).canonicalFile
                    val root = staging.canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) {
                        throw PluginInstallException("ZIP 路径越界：$name")
                    }
                    target.parentFile?.mkdirs()
                    val limit = when {
                        name == MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                        name.endsWith(".js", ignoreCase = true) -> MAX_SCRIPT_BYTES
                        else -> MAX_RESOURCE_BYTES
                    }
                    target.outputStream().use { output ->
                        totalBytes += copyLimited(zipStream, output, limit, totalBytes)
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
    }

    /** 校验 staging 中的清单并落位为正式插件目录；调用方负责清理 staging。 */
    private fun installStaging(staging: File): InstalledPlugin {
        val manifest = parseManifest(File(staging, MANIFEST_ENTRY))
        if (BuiltInPlugins.isBuiltIn(manifest.id)) {
            throw PluginInstallException("插件 ID 已被内置插件保留：${manifest.id}")
        }
        val old = _installed.value.firstOrNull { it.id == manifest.id }
        val errors = PluginManifestValidator.validate(manifest, manifestValidationScopes(manifest.id))
        if (errors.isNotEmpty()) throw PluginInstallException(errors.joinToString("；"))

        val entryFile = File(staging, manifest.entry).canonicalFile
        if (!entryFile.path.startsWith(staging.canonicalPath + File.separator) || !entryFile.isFile) {
            throw PluginInstallException("插件入口文件不存在：${manifest.entry}")
        }
        val installedAt = old?.installedAt ?: System.currentTimeMillis()
        writeState(
            staging,
            PluginState(enabled = old?.enabled ?: true, installedAt = installedAt)
        )
        val target = File(pluginRoot, manifest.id)
        if (target.exists()) target.deleteRecursively()
        if (!staging.renameTo(target)) throw PluginInstallException("无法保存插件文件")
        reload()
        return _installed.value.firstOrNull { it.id == manifest.id }
            ?: throw PluginInstallException("插件安装后加载失败")
    }

    /** 其他插件与内置命令占用的命令名集合；安装/更新校验时排除插件自身。 */
    private fun manifestValidationScopes(excludePluginId: String): Set<String> {
        val reserved = LocalSlashCommands.reservedCommandAliases()
        val existing = commandBindings(includeDisabled = true)
            .filter { it.pluginId != excludePluginId }
            .flatMap { listOf(it.trigger) + it.aliases }
        return reserved + existing
    }

    private fun installFromSourceBlocking(
        manifestJson: String,
        entrySource: String,
        extraFiles: Map<String, String>
    ): InstalledPlugin {
        val staging = File(pluginRoot, ".staging-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            // 先完整解析并校验清单，再写任何文件，避免恶意 entry 路径在校验前写出 staging。
            val manifest = parseManifestText(manifestJson)
            if (BuiltInPlugins.isBuiltIn(manifest.id)) {
                throw PluginInstallException("插件 ID 已被内置插件保留：${manifest.id}")
            }
            if (!PluginManifestValidator.isSafeRelativePath(manifest.entry) ||
                !manifest.entry.endsWith(".js", ignoreCase = true)
            ) {
                throw PluginInstallException("插件 entry 必须是安全的 .js 相对路径")
            }
            if (manifestJson.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
                throw PluginInstallException("plugin.json 超过大小限制")
            }
            if (entrySource.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
                throw PluginInstallException("插件入口源码超过大小限制")
            }
            File(staging, MANIFEST_ENTRY).writeText(
                PluginManifestValidator.sanitizeManifestJson(manifestJson),
                Charsets.UTF_8
            )
            val entry = File(staging, manifest.entry).canonicalFile
            entry.parentFile?.mkdirs()
            entry.writeText(entrySource, Charsets.UTF_8)
            extraFiles.forEach { (path, content) -> writePluginFile(staging, path, content, manifest.entry) }
            return installStaging(staging)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun installFromUrlBlocking(url: String): InstalledPlugin {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            throw PluginInstallException("只允许从 HTTPS 地址安装插件")
        }
        val tempZip = File.createTempFile("nekobot-plugin-", ".zip", appContext.cacheDir)
        try {
            val request = Request.Builder().url(trimmed).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw PluginInstallException("插件下载失败：HTTP ${response.code}")
                val body = response.body ?: throw PluginInstallException("插件下载失败：响应为空")
                body.byteStream().use { input ->
                    tempZip.outputStream().use { output ->
                        copyLimited(input, output, MAX_ARCHIVE_BYTES, 0L)
                    }
                }
            }
            return installZipBlocking(tempZip)
        } finally {
            tempZip.delete()
        }
    }

    private fun updatePluginBlocking(
        pluginId: String,
        manifestJson: String?,
        entrySource: String?,
        extraFiles: Map<String, String>
    ): InstalledPlugin {
        if (BuiltInPlugins.isBuiltIn(pluginId)) {
            throw PluginInstallException("内置插件不能修改")
        }
        val directory = pluginDirectory(pluginId)?.takeIf { it.isDirectory }
            ?: throw PluginInstallException("插件不存在：$pluginId")
        val manifestFile = File(directory, MANIFEST_ENTRY)
        val current = parseManifest(manifestFile)
        val manifest = if (manifestJson != null) parseManifestText(manifestJson) else current
        if (manifest.id != pluginId) {
            throw PluginInstallException("修改清单时插件 id 不能变更（当前 $pluginId，清单 ${manifest.id}）")
        }
        if (manifestJson != null && manifestJson.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            throw PluginInstallException("plugin.json 超过大小限制")
        }
        if (!PluginManifestValidator.isSafeRelativePath(manifest.entry) ||
            !manifest.entry.endsWith(".js", ignoreCase = true)
        ) {
            throw PluginInstallException("插件 entry 必须是安全的 .js 相对路径")
        }
        val errors = PluginManifestValidator.validate(manifest, manifestValidationScopes(pluginId))
        if (errors.isNotEmpty()) throw PluginInstallException(errors.joinToString("；"))
        if (entrySource != null && entrySource.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            throw PluginInstallException("插件入口源码超过大小限制")
        }
        if (manifestJson != null) {
            manifestFile.writeText(
                PluginManifestValidator.sanitizeManifestJson(manifestJson),
                Charsets.UTF_8
            )
        }
        if (entrySource != null) {
            val entry = File(directory, manifest.entry).canonicalFile
            entry.parentFile?.mkdirs()
            entry.writeText(entrySource, Charsets.UTF_8)
        } else {
            val entryFile = File(directory, manifest.entry).canonicalFile
            if (!entryFile.path.startsWith(directory.canonicalPath + File.separator) || !entryFile.isFile) {
                throw PluginInstallException("插件入口文件不存在：${manifest.entry}")
            }
        }
        extraFiles.forEach { (path, content) -> writePluginFile(directory, path, content, manifest.entry) }
        reload()
        return _installed.value.firstOrNull { it.id == pluginId }
            ?: throw PluginInstallException("插件修改后加载失败，请用 view 检查插件内容")
    }

    /** 写入插件包内的附加文件；清单与入口必须分别通过 manifest_json / main_js 提供。 */
    private fun writePluginFile(root: File, relativePath: String, content: String, entryPath: String) {
        if (!PluginManifestValidator.isSafeRelativePath(relativePath)) {
            throw PluginInstallException("不安全的文件路径：$relativePath")
        }
        if (relativePath == MANIFEST_ENTRY || relativePath == entryPath) {
            throw PluginInstallException("该文件必须通过 manifest_json / main_js 提供：$relativePath")
        }
        val limit = if (relativePath.endsWith(".js", ignoreCase = true)) MAX_SCRIPT_BYTES else MAX_RESOURCE_BYTES
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) throw PluginInstallException("文件超过大小限制：$relativePath")
        val target = File(root, relativePath).canonicalFile
        if (!target.path.startsWith(root.canonicalPath + File.separator)) {
            throw PluginInstallException("文件路径越界：$relativePath")
        }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw PluginInstallException("无法读取所选 ZIP 文件")
        input.use { source ->
            target.outputStream().use { output ->
                copyLimited(source, output, MAX_ARCHIVE_BYTES, 0L)
            }
        }
    }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        existingTotal: Long
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            if (written > limit || existingTotal + written > MAX_UNCOMPRESSED_BYTES) {
                throw PluginInstallException("插件文件超过大小限制")
            }
            output.write(buffer, 0, count)
        }
        return written
    }

    private fun discoverPlugins(): List<InstalledPlugin> {
        val reserved = (LocalSlashCommands.reservedCommandAliases() + BuiltInPlugins.reservedCommandAliases()).toMutableSet()
        val result = BuiltInPlugins.installed(::isBuiltInEnabled).toMutableList()
        pluginRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { directory ->
                val manifest = runCatching { parseManifest(File(directory, MANIFEST_ENTRY)) }.getOrNull()
                    ?: return@forEach
                val errors = PluginManifestValidator.validate(manifest, reserved)
                if (errors.isNotEmpty()) return@forEach
                val state = readState(directory)
                val plugin = manifest.toInstalledPlugin(state)
                result += plugin
                reserved += pluginCommandBindings(listOf(plugin), includeDisabled = true)
                    .flatMap { listOf(it.trigger) + it.aliases }
            }
        return result
    }

    private fun commandBindings(includeDisabled: Boolean = false): List<PluginCommandBinding> =
        pluginCommandBindings(installed.value, includeDisabled)

    private fun pluginDirectory(pluginId: String): File? {
        if (!PluginManifestValidator.isSafeRelativePath(pluginId)) return null
        val directory = File(pluginRoot, pluginId).canonicalFile
        val root = pluginRoot.canonicalFile
        return directory.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun parseManifest(file: File): PluginManifest {
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) {
            throw PluginInstallException("plugin.json 不存在或过大")
        }
        return parseManifestText(file.readText(Charsets.UTF_8))
    }

    private fun parseManifestText(raw: String): PluginManifest =
        runCatching {
            gson.fromJson(PluginManifestValidator.sanitizeManifestJson(raw), PluginManifest::class.java)
        }.getOrElse { throw PluginInstallException("plugin.json 格式无效：${it.message ?: "未知错误"}") }

    private fun readState(directory: File): PluginState = runCatching {
        gson.fromJson(directory.resolve(STATE_ENTRY).readText(Charsets.UTF_8), PluginState::class.java)
    }.getOrDefault(PluginState())

    private fun writeState(directory: File, state: PluginState) {
        directory.resolve(STATE_ENTRY).writeText(gson.toJson(state), Charsets.UTF_8)
    }

    private fun isBuiltInEnabled(pluginId: String): Boolean =
        settings.getBoolean(builtInEnabledKey(pluginId), true)

    private fun builtInEnabledKey(pluginId: String): String = "builtin_enabled:$pluginId"

    private fun removePluginStorage(pluginId: String) {
        val prefix = "$pluginId:"
        val keys = storage.all.keys.filter { it.startsWith(prefix) }
        if (keys.isNotEmpty()) storage.edit().apply {
            keys.forEach { key -> remove(key) }
        }.apply()
    }

    private fun PluginManifest.toInstalledPlugin(state: PluginState) = InstalledPlugin(
        id = id,
        name = name,
        version = version,
        author = author,
        description = description,
        entry = entry,
        permissions = permissions.distinct(),
        commands = commands,
        enabled = state.enabled,
        installedAt = state.installedAt
    )

    private suspend fun executeInWebView(
        plugin: InstalledPlugin,
        binding: PluginCommandBinding,
        sessionId: String,
        args: String,
        source: String,
        repository: LocalRepository
    ): String = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        val token = UUID.randomUUID().toString()
        val contextJson = gson.toJson(
            mapOf(
                "pluginId" to plugin.id,
                "pluginName" to plugin.name,
                "command" to binding.trigger,
                "handler" to binding.name,
                "args" to args.trim().split(Regex("\\s+")).filter(String::isNotBlank),
                "argsText" to args,
                "raw" to (binding.trigger + if (args.isBlank()) "" else " $args"),
                "sessionId" to sessionId,
                "appMode" to "LOCAL"
            )
        )

        fun finish(success: Boolean, value: String) {
            if (!finished.compareAndSet(false, true)) return
            mainHandler.post {
                webViewRef.getAndSet(null)?.let { view ->
                    view.stopLoading()
                    view.removeJavascriptInterface(BRIDGE_NAME)
                    view.destroy()
                }
            }
            if (continuation.isActive) continuation.resume(
                if (success) value.take(MAX_REPLY_CHARS) else "插件执行失败：${value.take(MAX_REPLY_CHARS)}"
            )
        }

        mainHandler.post {
            if (finished.get()) return@post
            try {
                val webView = WebView(appContext)
                webViewRef.set(webView)
                webView.settings.javaScriptEnabled = true
                // 第三方脚本不能使用 fetch、XHR、图片或导航绕过 Bridge 的 network 权限。
                webView.settings.blockNetworkLoads = true
                webView.settings.blockNetworkImage = true
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                webView.settings.domStorageEnabled = false
                webView.settings.javaScriptCanOpenWindowsAutomatically = false
                webView.settings.setSupportMultipleWindows(false)
                webView.settings.allowFileAccessFromFileURLs = false
                webView.settings.allowUniversalAccessFromFileURLs = false
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!finished.get()) {
                            view.evaluateJavascript(
                                buildExecutionScript(source, contextJson, binding.name, token),
                                null
                            )
                        }
                    }
                }
                webView.addJavascriptInterface(
                    RuntimeBridge(plugin, sessionId, repository, token, webView, ::finish),
                    BRIDGE_NAME
                )
                webView.loadDataWithBaseURL(
                    "https://plugin.invalid/",
                    "<html><head><meta charset=\"utf-8\"></head><body></body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )
            } catch (error: Exception) {
                finish(false, error.message ?: "无法启动插件运行时")
            }
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) {
                mainHandler.post {
                    webViewRef.getAndSet(null)?.let { view ->
                        view.stopLoading()
                        view.removeJavascriptInterface(BRIDGE_NAME)
                        view.destroy()
                    }
                }
            }
        }
    }

    private fun buildExecutionScript(
        source: String,
        contextJson: String,
        handlerName: String,
        token: String
    ): String {
        val sourceLiteral = gson.toJson(source)
        val handlerLiteral = gson.toJson(handlerName)
        val tokenLiteral = gson.toJson(token)
        return """
            (function() {
              "use strict";
              var __handlers = Object.create(null);
              var __pending = Object.create(null);
              var __nextRequestId = 0;
              function __commandName(value) {
                return String(value || "").trim().replace(/^\/+/, "").toLowerCase();
              }
              function __api(name, payload) {
                return new Promise(function(resolve, reject) {
                  var id = String(++__nextRequestId);
                  __pending[id] = { resolve: resolve, reject: reject };
                  try {
                    NekoAndroid.api(id, name, JSON.stringify(payload || {}));
                  } catch (error) {
                    delete __pending[id];
                    reject(error);
                  }
                });
              }
              window.__nekoApiResult = function(id, ok, payload) {
                var pending = __pending[String(id)];
                if (!pending) return;
                delete __pending[String(id)];
                var value;
                try { value = JSON.parse(payload || "null"); } catch (_) { value = payload; }
                if (ok) pending.resolve(value);
                else pending.reject(new Error(String(value && value.error || value || "API 调用失败")));
              };
              var api = {
                getSession: function() { return __api("get_session", {}); },
                getMessages: function(limit) { return __api("get_messages", { limit: limit }); },
                notify: function(message) { return __api("notify", { message: message }); },
                httpGet: function(url) { return __api("http_get", { url: url }); },
                storage: {
                  get: function(key) { return __api("storage_get", { key: key }); },
                  set: function(key, value) { return __api("storage_set", { key: key, value: value }); },
                  remove: function(key) { return __api("storage_remove", { key: key }); },
                  list: function() { return __api("storage_list", {}); }
                }
              };
              var NekoPlugin = {
                apiVersion: 1,
                api: api,
                registerCommand: function(name, handler) {
                  if (typeof handler !== "function") throw new Error("命令处理器必须是函数");
                  __handlers[__commandName(name)] = handler;
                },
                register: function(definition) {
                  if (!definition || !definition.commands) return;
                  Object.keys(definition.commands).forEach(function(name) {
                    NekoPlugin.registerCommand(name, definition.commands[name]);
                  });
                }
              };
              window.NekoPlugin = NekoPlugin;
              var __ctx = Object.assign($contextJson, { api: api });
              var __source = $sourceLiteral;
              try {
                (0, eval)(__source);
              } catch (error) {
                NekoAndroid.fail($tokenLiteral, String(error && error.message || error));
                return;
              }
              Promise.resolve().then(function() {
                var handler = __handlers[__commandName($handlerLiteral)];
                if (typeof handler !== "function") throw new Error("插件没有注册命令 /" + $handlerLiteral);
                return handler(__ctx);
              }).then(function(result) {
                var value = result == null ? "" : (typeof result === "string" ? result : JSON.stringify(result));
                NekoAndroid.complete($tokenLiteral, String(value || ""));
              }).catch(function(error) {
                NekoAndroid.fail($tokenLiteral, String(error && error.message || error));
              });
            })();
        """.trimIndent()
    }

    private inner class RuntimeBridge(
        private val plugin: InstalledPlugin,
        private val sessionId: String,
        private val repository: LocalRepository,
        private val token: String,
        private val webView: WebView,
        private val finish: (Boolean, String) -> Unit
    ) {
        @JavascriptInterface
        fun complete(callbackToken: String?, value: String?) {
            if (callbackToken == token) finish(true, value.orEmpty())
        }

        @JavascriptInterface
        fun fail(callbackToken: String?, message: String?) {
            if (callbackToken == token) finish(false, message.orEmpty())
        }

        @JavascriptInterface
        fun api(requestId: String?, name: String?, payloadJson: String?) {
            if (requestId.isNullOrBlank() || name.isNullOrBlank()) return
            runtimeScope.launch {
                try {
                    val value = handleApi(plugin, sessionId, repository, name, payloadJson.orEmpty())
                    sendApiResult(requestId, true, gson.toJson(value))
                } catch (error: Exception) {
                    sendApiResult(
                        requestId,
                        false,
                        gson.toJson(mapOf("error" to (error.message ?: "API 调用失败")))
                    )
                }
            }
        }

        @JavascriptInterface
        fun log(level: String?, message: String?) {
            android.util.Log.i("NekoPlugin", "[${plugin.id}][${level ?: "info"}] ${message.orEmpty().take(500)}")
        }

        private fun sendApiResult(requestId: String, success: Boolean, payload: String) {
            val script = "window.__nekoApiResult(${gson.toJson(requestId)},$success,${gson.toJson(payload)});"
            mainHandler.post {
                runCatching { webView.evaluateJavascript(script, null) }
            }
        }
    }

    private suspend fun handleApi(
        plugin: InstalledPlugin,
        sessionId: String,
        repository: LocalRepository,
        name: String,
        payloadJson: String
    ): Any? {
        val payload = runCatching { JsonParser.parseString(payloadJson).takeIf { it.isJsonObject }?.asJsonObject }
            .getOrNull() ?: JsonObject()
        return when (name) {
            "get_session" -> {
                requirePermission(plugin, "chat.read")
                repository.getSession(sessionId) ?: throw IllegalStateException("会话不存在")
            }
            "get_messages" -> {
                requirePermission(plugin, "chat.read")
                val limit = payload.int("limit", 30).coerceIn(1, 100)
                repository.listMessages(sessionId).takeLast(limit)
            }
            "storage_get" -> {
                requirePermission(plugin, "storage")
                val key = storageKey(plugin.id, payload.string("key"))
                storage.getString(key, null)?.let { raw -> runCatching { JsonParser.parseString(raw) }.getOrNull() }
                    ?: JsonNull.INSTANCE
            }
            "storage_set" -> {
                requirePermission(plugin, "storage")
                val key = storageKey(plugin.id, payload.string("key"))
                val value = payload.get("value") ?: JsonNull.INSTANCE
                storage.edit().putString(key, value.toString()).apply()
                true
            }
            "storage_remove" -> {
                requirePermission(plugin, "storage")
                storage.edit().remove(storageKey(plugin.id, payload.string("key"))).apply()
                true
            }
            "storage_list" -> {
                requirePermission(plugin, "storage")
                val prefix = "${plugin.id}:"
                JsonObject().apply {
                    storage.all
                        .filterKeys { it.startsWith(prefix) }
                        .forEach { (key, raw) ->
                            if (raw is String) add(key.removePrefix(prefix), runCatching { JsonParser.parseString(raw) }.getOrDefault(JsonNull.INSTANCE))
                        }
                }
            }
            "notify" -> {
                requirePermission(plugin, "notify")
                val message = payload.string("message").take(500)
                mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
                true
            }
            "http_get" -> {
                requirePermission(plugin, "network")
                val url = payload.string("url").trim()
                if (!url.startsWith("https://", ignoreCase = true)) {
                    throw IllegalArgumentException("插件网络请求只允许 HTTPS")
                }
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.byteStream()?.use { input -> readLimitedText(input, MAX_HTTP_BYTES) }.orEmpty()
                    mapOf("status" to response.code, "body" to body)
                }
            }
            else -> throw IllegalArgumentException("未知插件 API：$name")
        }
    }

    private fun requirePermission(plugin: InstalledPlugin, permission: String) {
        if (permission !in plugin.permissions) throw SecurityException("插件未声明权限：$permission")
    }

    private fun storageKey(pluginId: String, raw: String): String {
        val key = raw.trim()
        require(key.isNotEmpty() && key.length <= 128 && '\n' !in key && '\r' !in key) { "storage key 无效" }
        return "$pluginId:$key"
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

    private fun readLimitedText(input: InputStream, limit: Long): String {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, limit, 0L)
        return output.toString(Charsets.UTF_8.name())
    }

    private data class PluginState(
        val enabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis()
    )

    private companion object {
        const val MANIFEST_ENTRY = "plugin.json"
        const val STATE_ENTRY = ".plugin-state.json"
        const val BRIDGE_NAME = "NekoAndroid"
        const val MAX_ENTRIES = 128
        const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 128L * 1024
        const val MAX_SCRIPT_BYTES = 512L * 1024
        const val MAX_RESOURCE_BYTES = 4L * 1024 * 1024
        const val MAX_HTTP_BYTES = 512L * 1024
        const val MAX_REPLY_CHARS = 20_000
        const val MAX_SCRIPT_CHARS = MAX_SCRIPT_BYTES / 2
        const val RUNTIME_TIMEOUT_MS = 20_000L
    }
}
