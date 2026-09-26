package com.nekobot.app.data.local.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalCommandProgressReporter
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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
class PluginManager(
    context: Context,
    private val grants: PluginGrants? = null,
    private val metaStore: PluginMetaStore? = null
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val pluginRoot = File(appContext.filesDir, "plugins").apply { mkdirs() }
    private val storage = appContext.getSharedPreferences("nekobot_plugin_storage", Context.MODE_PRIVATE)
    private val settings = appContext.getSharedPreferences("nekobot_plugin_settings", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 插件私有文件目录；页面文件上传与 files.* API 共用。 */
    internal val pluginFiles: PluginFileStore = PluginFileStore(appContext)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 命令运行时与插件页面共用的宿主 API 分派器。
     *
     * 页面宿主直接复用该实例，保证权限校验、网络总开关与存储隔离只有一份实现。
     */
    internal val apiDispatcher: PluginApiDispatcher = PluginApiDispatcher(
        appContext = appContext,
        storage = storage,
        grants = grants,
        repositoryProvider = { runCatching { com.nekobot.app.ServiceContainer.localRepository }.getOrNull() },
        memoryProvider = {
            runCatching { com.nekobot.app.ServiceContainer.globalAgentMemory.read().content }
                .getOrDefault("")
        },
        memoryWriter = { content, append, oldText ->
            val store = com.nekobot.app.ServiceContainer.globalAgentMemory
            when {
                oldText != null -> store.replaceText(oldText, content).charCount
                append -> store.append(content).charCount
                else -> store.replace(content).charCount
            }
        },
        networkAllowed = {
            runCatching { com.nekobot.app.ServiceContainer.prefs.agentNetworkAccessEnabled }
                .getOrDefault(false)
        },
        appVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty(),
        fileStore = pluginFiles
    )

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

    /**
     * 从 ZIP 安装插件。
     *
     * @param grantedPermissions 用户在安装对话框勾选的授权集合；null 表示调用方未收集
     *   授权（按非危险权限默认授予）。
     */
    suspend fun install(
        uri: Uri,
        acceptedThirdPartyAgreement: Boolean,
        grantedPermissions: Set<String>? = null
    ): InstalledPlugin = installMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!acceptedThirdPartyAgreement) {
                throw PluginInstallException("安装第三方插件前必须同意免责协议")
            }
            installBlocking(uri, grantedPermissions)
        }
    }

    /**
     * 读取 ZIP 内 plugin.json（不落盘安装），供安装授权对话框展示权限清单。
     * 读取失败或清单无效时返回 null。
     */
    suspend fun peekManifest(uri: Uri): PluginManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val tempZip = File.createTempFile("nekobot-plugin-peek-", ".zip", appContext.cacheDir)
            try {
                copyUriToFile(uri, tempZip)
                readManifestFromZip(tempZip)
            } finally {
                tempZip.delete()
            }
        }.getOrNull()
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
                pluginFiles.clear(pluginId)
                removePluginStorage(pluginId)
                grants?.clear(pluginId)
                metaStore?.clear(pluginId)
                reload()
            }
        }

    /** 执行一条插件命令；返回值会作为本地会话中的 assistant 回复。 */
    internal suspend fun execute(
        binding: PluginCommandBinding,
        sessionId: String,
        args: String,
        repository: LocalRepository,
        progressReporter: LocalCommandProgressReporter? = null
    ): String {
        if (binding.openPage.isNotBlank()) {
            return "该命令用于打开插件页面（${binding.openPage}），请在 App 内直接输入 ${binding.trigger}。"
        }
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

        // 声明 ai.call 的插件可能等待模型生成，单次调用上限放宽到 120 秒。
        val runtimeTimeoutMs = if ("ai.call" in plugin.permissions) {
            RUNTIME_TIMEOUT_WITH_AI_MS
        } else {
            RUNTIME_TIMEOUT_MS
        }
        return try {
            withTimeout(runtimeTimeoutMs) {
                executeInWebView(plugin, binding, sessionId, args, source, repository, progressReporter)
            }
        } catch (_: TimeoutCancellationException) {
            "插件执行超时（${runtimeTimeoutMs / 1000} 秒）"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            "插件执行失败：${error.message ?: "未知错误"}"
        }
    }

    private fun installBlocking(uri: Uri, grantedPermissions: Set<String>?): InstalledPlugin {
        val tempZip = File.createTempFile("nekobot-plugin-", ".zip", appContext.cacheDir)
        try {
            copyUriToFile(uri, tempZip)
            return installZipBlocking(tempZip, grantedPermissions)
        } finally {
            tempZip.delete()
        }
    }

    /** 从 ZIP 读取根目录 plugin.json；不安装、不校验命令冲突。 */
    private fun readManifestFromZip(zip: File): PluginManifest? {
        ZipInputStream(zip.inputStream().buffered()).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && name == MANIFEST_ENTRY) {
                    val output = ByteArrayOutputStream()
                    copyLimited(zipStream, output, MAX_MANIFEST_BYTES, 0L)
                    return parseManifestText(output.toString(Charsets.UTF_8.name()))
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
        return null
    }

    /**
     * 从已下载的 ZIP 安装插件。
     *
     * 安装成功时 staging 目录会被整体重命名为插件目录，finally 的清理不会影响
     * 已安装内容；任何失败都会清理 staging，保持插件根目录干净。
     */
    private fun installZipBlocking(zip: File, grantedPermissions: Set<String>?): InstalledPlugin {
        val staging = File(pluginRoot, ".staging-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            unzipToStaging(zip, staging)
            if (!File(staging, MANIFEST_ENTRY).isFile) {
                throw PluginInstallException("ZIP 根目录缺少 plugin.json")
            }
            return installStaging(staging, grantedPermissions)
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
    private fun installStaging(staging: File, grantedPermissions: Set<String>?): InstalledPlugin {
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
        // 页面入口必须在安装时真实存在，避免运行时才发现 404。
        manifest.pages.forEach { page ->
            val pageFile = File(staging, page.entry).canonicalFile
            if (!pageFile.path.startsWith(staging.canonicalPath + File.separator) || !pageFile.isFile) {
                throw PluginInstallException("插件页面入口文件不存在：${page.entry}")
            }
        }
        val installedAt = old?.installedAt ?: System.currentTimeMillis()
        writeState(
            staging,
            PluginState(enabled = old?.enabled ?: true, installedAt = installedAt)
        )
        val target = File(pluginRoot, manifest.id)
        if (target.exists()) target.deleteRecursively()
        if (!staging.renameTo(target)) throw PluginInstallException("无法保存插件文件")
        if (grants != null) {
            if (grantedPermissions != null) {
                grants.setGranted(manifest.id, grantedPermissions.intersect(manifest.permissions.toSet()))
            } else if (!grants.hasRecord(manifest.id)) {
                grants.initializeDefaults(manifest.id, manifest.permissions)
            }
        }
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
            // Agent 创建的插件不能替用户点授权：只授予非危险权限，危险权限等待用户确认。
            return installStaging(staging, null)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun installFromUrlBlocking(url: String): InstalledPlugin {
        requireNetworkAccessAllowed()
        val trimmed = url.trim()
        requirePublicHttpsUrl(trimmed, "插件安装")
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
            // Agent 安装的第三方 ZIP 与 create 同路径：只默认授予非危险权限。
            return installZipBlocking(tempZip, null)
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
        manifest.pages.forEach { page ->
            val pageFile = File(directory, page.entry).canonicalFile
            if (!pageFile.path.startsWith(directory.canonicalPath + File.separator) || !pageFile.isFile) {
                throw PluginInstallException("插件页面入口文件不存在：${page.entry}")
            }
        }
        if (grants != null && !grants.hasRecord(pluginId)) {
            grants.initializeDefaults(pluginId, manifest.permissions)
        }
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

    private fun PluginManifest.toInstalledPlugin(state: PluginState): InstalledPlugin {
        val meta = metaStore?.meta(id)
        return InstalledPlugin(
            id = id,
            name = name,
            version = version,
            author = author,
            description = description,
            entry = entry,
            permissions = permissions.distinct(),
            commands = commands,
            enabled = state.enabled,
            installedAt = state.installedAt,
            pages = pages,
            hooks = hooks.distinct(),
            compat = meta?.compat ?: PluginCompatLevel.NATIVE,
            compatNote = meta?.note.orEmpty()
        )
    }

    /** 声明了指定钩子的已启用插件（含目录可用性检查）。 */
    private fun hookPlugins(hook: String): List<InstalledPlugin> = _installed.value.filter { plugin ->
        plugin.enabled &&
            hook in plugin.hooks &&
            pluginDirectory(plugin.id)?.isDirectory == true
    }

    /**
     * `message.beforeSend`：按插件顺序串联改写用户消息。
     *
     * 任一插件失败或超时都保持当前文本（发送永远不被插件阻塞），单插件超时 [HOOK_TIMEOUT_MS]。
     */
    suspend fun runMessageBeforeSendHooks(sessionId: String, content: String): String {
        val plugins = hookPlugins(HOOK_MESSAGE_BEFORE_SEND)
        if (plugins.isEmpty()) return content
        var current = content
        plugins.forEach { plugin ->
            val resultJson = try {
                withTimeout(HOOK_TIMEOUT_MS) {
                    val payloadJson = gson.toJson(
                        mapOf("sessionId" to sessionId, "content" to current, "role" to "user")
                    )
                    runHookInWebView(plugin, HOOK_MESSAGE_BEFORE_SEND, payloadJson, sessionId)
                }
            } catch (_: TimeoutCancellationException) {
                return@forEach
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@forEach
            }
            val next = runCatching {
                JsonParser.parseString(resultJson)
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("content")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }.getOrNull()
            if (!next.isNullOrBlank()) current = next.take(MAX_HOOK_CONTENT_CHARS)
        }
        return current
    }

    /** `app.lifecycle`：通知声明了该钩子的插件（`event` 见 [LIFECYCLE_EVENTS]），结果丢弃。 */
    suspend fun runLifecycleHook(event: String, sessionId: String? = null) {
        if (event !in LIFECYCLE_EVENTS) return
        val payloadJson = gson.toJson(
            mapOf("event" to event, "sessionId" to sessionId)
        )
        hookPlugins(HOOK_APP_LIFECYCLE).forEach { plugin ->
            try {
                withTimeout(HOOK_TIMEOUT_MS) {
                    runHookInWebView(plugin, HOOK_APP_LIFECYCLE, payloadJson, sessionId.orEmpty())
                }
            } catch (_: TimeoutCancellationException) {
                return@forEach
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@forEach
            }
        }
    }

    /** 读取插件入口源码；目录/文件缺失或过大时返回 null。 */
    private fun readEntrySource(plugin: InstalledPlugin): String? {
        val directory = pluginDirectory(plugin.id)?.takeIf { it.isDirectory } ?: return null
        val entry = File(directory, plugin.entry).canonicalFile
        val root = directory.canonicalFile
        if (!entry.path.startsWith(root.path + File.separator) || !entry.isFile) return null
        val source = runCatching { entry.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return source.takeIf { it.length <= MAX_SCRIPT_CHARS }
    }

    /** 在无界面运行时里执行入口脚本并触发钩子，返回钩子返回值的 JSON 文本。 */
    private suspend fun runHookInWebView(
        plugin: InstalledPlugin,
        hook: String,
        payloadJson: String,
        sessionId: String
    ): String = suspendCancellableCoroutine { continuation ->
        val source = readEntrySource(plugin) ?: run {
            continuation.resume("null")
            return@suspendCancellableCoroutine
        }
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        val token = UUID.randomUUID().toString()
        val contextJson = gson.toJson(
            mapOf(
                "pluginId" to plugin.id,
                "pluginName" to plugin.name,
                "hook" to hook,
                "sessionId" to sessionId,
                "payload" to runCatching { JsonParser.parseString(payloadJson) }.getOrNull()
            )
        )
        val payloadLiteral = gson.toJson(payloadJson)
        val hookLiteral = gson.toJson(hook)

        fun finish(success: Boolean, value: String) {
            if (!finished.compareAndSet(false, true)) return
            mainHandler.post {
                webViewRef.getAndSet(null)?.let { view ->
                    view.stopLoading()
                    view.removeJavascriptInterface(BRIDGE_NAME)
                    view.destroy()
                }
            }
            if (continuation.isActive) continuation.resume(if (success) value else "null")
        }

        mainHandler.post {
            if (finished.get()) return@post
            try {
                val webView = createRuntimeWebView()
                webViewRef.set(webView)
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

                    override fun onPageFinished(view: WebView, url: String) {
                        if (finished.get()) return
                        // 先执行入口脚本（注册钩子），再触发本次钩子调用
                        view.evaluateJavascript(buildExecutionScript(source, contextJson, "", token), null)
                        view.evaluateJavascript(
                            "window.__nekoInvokeHook($hookLiteral,$payloadLiteral);",
                            null
                        )
                    }
                }
                webView.addJavascriptInterface(
                    RuntimeBridge(
                        plugin = plugin,
                        sessionId = sessionId,
                        repository = ServiceContainer.localRepository,
                        progressReporter = null,
                        token = token,
                        webView = webView,
                        finish = ::finish
                    ),
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

    /** 命令运行时与钩子运行时共用的沙盒配置。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createRuntimeWebView(): WebView {
        val webView = WebView(appContext)
        webView.settings.javaScriptEnabled = true
        // 第三方脚本不能使用 fetch、XHR、图片或导航绕过 Bridge 的 network 权限。
        // blockNetworkLoads 已在网络层拦截外部请求；不要开启 blockNetworkImage，
        // 那会让 Blink 直接丢弃全部图片资源（页面宿主展示虚拟源图片时会被误伤）。
        webView.settings.blockNetworkLoads = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.domStorageEnabled = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
        return webView
    }

    /** 解析插件页面为宿主可用的描述；插件停用、页面不存在或文件缺失时返回 null。 */
    fun resolvePage(pluginId: String, pageId: String): ResolvedPluginPage? {
        val plugin = _installed.value.firstOrNull { it.id == pluginId } ?: return null
        val page = plugin.pages.firstOrNull { it.id == pageId } ?: return null
        val directory = pluginDirectory(pluginId)?.takeIf { it.isDirectory } ?: return null
        val entry = File(directory, page.entry).canonicalFile
        val root = directory.canonicalFile
        if (!entry.path.startsWith(root.path + File.separator) || !entry.isFile) return null
        return ResolvedPluginPage(plugin = plugin, page = page, directory = directory)
    }

    /** 插件目录（只读场景，如移植自检）；不存在时返回 null。 */
    fun pluginDirectoryPath(pluginId: String): File? =
        pluginDirectory(pluginId)?.takeIf { it.isDirectory }

    /** 插件目录内的相对资源（如页面图标）；路径越界或文件缺失时返回 null。 */
    fun resolvePluginAsset(pluginId: String, relativePath: String): File? {
        if (relativePath.isBlank() || !PluginManifestValidator.isSafeRelativePath(relativePath)) return null
        val directory = pluginDirectory(pluginId)?.takeIf { it.isDirectory } ?: return null
        val target = File(directory, relativePath).canonicalFile
        val root = directory.canonicalFile
        if (!target.path.startsWith(root.path + File.separator) || !target.isFile) return null
        return target
    }

    private suspend fun executeInWebView(
        plugin: InstalledPlugin,
        binding: PluginCommandBinding,
        sessionId: String,
        args: String,
        source: String,
        repository: LocalRepository,
        progressReporter: LocalCommandProgressReporter?
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
                val webView = createRuntimeWebView()
                webViewRef.set(webView)
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
                    RuntimeBridge(
                        plugin = plugin,
                        sessionId = sessionId,
                        repository = repository,
                        progressReporter = progressReporter,
                        token = token,
                        webView = webView,
                        finish = ::finish
                    ),
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
                contextUsage: function(options) { return __api("chat.context", options || {}); },
                sessionConfig: function(options) { return __api("chat.session.config", options || {}); },
                promptStack: function(options) { return __api("chat.prompt.stack", options || {}); },
                toolCalls: function(options) { return __api("chat.tool.calls", options || {}); },
                notify: function(message) { return __api("notify", { message: message }); },
                httpGet: function(url) { return __api("http_get", { url: url }); },
                aiComplete: function(options) { return __api("ai_complete", options || {}); },
                appendMessage: function(options) { return __api("append_message", options || {}); },
                sendMessage: function(options) { return __api("chat_send", options || {}); },
                createSession: function(options) { return __api("create_session", options || {}); },
                switchSession: function(id) { return __api("switch_session", { id: id }); },
                render: function(template, data) { return __api("ui_render", { template: template, data: data }); },
                createCharacter: function(options) { return __api("create_character", options || {}); },
                updateCharacter: function(options) { return __api("update_character", options || {}); },
                memoryRead: function() { return __api("memory_read", {}); },
                memoryWrite: function(content) { return __api("memory_write", { content: content }); },
                memoryAppend: function(content) { return __api("memory_append", { content: content }); },
                memoryEdit: function(oldText, newText) {
                  return __api("memory_edit", { oldText: oldText, newText: newText });
                },
                progress: function(options) { return __api("progress", options || {}); },
                workspace: {
                  save: function(path, content) { return __api("workspace_save", { path: path, content: content }); },
                  list: function(path) { return __api("workspace_list", { path: path || "" }); },
                  read: function(path) { return __api("workspace_read", { path: path }); },
                  delete: function(path) { return __api("workspace_delete", { path: path }); }
                },
                files: {
                  list: function() { return __api("files_list", {}); },
                  read: function(name, encoding) {
                    return __api("files_read", { name: name, encoding: encoding });
                  },
                  delete: function(name) { return __api("files_delete", { name: name }); }
                },
                storage: {
                  get: function(key) { return __api("storage_get", { key: key }); },
                  set: function(key, value) { return __api("storage_set", { key: key, value: value }); },
                  remove: function(key) { return __api("storage_remove", { key: key }); },
                  list: function() { return __api("storage_list", {}); }
                }
              };
              var __hooks = Object.create(null);
              var NekoPlugin = {
                apiVersion: 1,
                api: api,
                registerCommand: function(name, handler) {
                  if (typeof handler !== "function") throw new Error("命令处理器必须是函数");
                  __handlers[__commandName(name)] = handler;
                },
                on: function(name, handler) {
                  if (typeof handler !== "function") throw new Error("钩子处理器必须是函数");
                  __hooks[String(name || "").trim()] = handler;
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
              // 钩子运行时：宿主在脚本求值后调用，返回值以 JSON 文本回传
              window.__nekoInvokeHook = function(name, payloadJson) {
                var payload = null;
                try { payload = JSON.parse(payloadJson || "null"); } catch (_) { payload = null; }
                Promise.resolve().then(function() {
                  var handler = __hooks[String(name || "").trim()];
                  if (typeof handler !== "function") return null;
                  return handler(Object.assign({ hook: name, payload: payload }, __ctx));
                }).then(function(result) {
                  var value = result == null ? "null" : JSON.stringify(result);
                  NekoAndroid.hookResult($tokenLiteral, String(value == null ? "null" : value));
                }).catch(function(error) {
                  NekoAndroid.fail($tokenLiteral, String(error && error.message || error));
                });
              };
              var __source = $sourceLiteral;
              try {
                (0, eval)(__source);
              } catch (error) {
                NekoAndroid.fail($tokenLiteral, String(error && error.message || error));
                return;
              }
              if ($handlerLiteral) {
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
              }
            })();
        """.trimIndent()
    }

    private inner class RuntimeBridge(
        private val plugin: InstalledPlugin,
        private val sessionId: String,
        private val repository: LocalRepository,
        private val progressReporter: LocalCommandProgressReporter?,
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

        /** 钩子返回值（JSON 文本）；未注册处理器时为空。 */
        @JavascriptInterface
        fun hookResult(callbackToken: String?, value: String?) {
            if (callbackToken == token) finish(true, value?.takeIf { it.isNotBlank() } ?: "null")
        }

        @JavascriptInterface
        fun api(requestId: String?, name: String?, payloadJson: String?) {
            if (requestId.isNullOrBlank() || name.isNullOrBlank()) return
            runtimeScope.launch {
                try {
                    val value = apiDispatcher.dispatch(
                        PluginApiDispatcher.CallContext(
                            plugin = plugin,
                            sessionId = sessionId,
                            progressReporter = progressReporter,
                            isPage = false,
                            repositoryOverride = repository
                        ),
                        name,
                        payloadJson.orEmpty()
                    )
                    sendApiResult(requestId, true, gson.toJson(value))
                } catch (error: Exception) {
                    val envelope = when (error) {
                        is PluginApiException -> mapOf(
                            "error" to (error.message ?: "API 调用失败"),
                            "code" to error.code
                        )
                        else -> mapOf("error" to (error.message ?: "API 调用失败"))
                    }
                    sendApiResult(requestId, false, gson.toJson(envelope))
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

    /**
     * 插件发起的网络请求同样受 Agent 设置里的「网络访问」总开关约束。
     *
     * 插件由 AI 通过 plugin_use 创建/安装，能力边界必须与内置联网工具一致，否则
     * 关闭网络访问后仍可借插件外发数据。
     */
    private fun requireNetworkAccessAllowed() {
        if (!com.nekobot.app.ServiceContainer.prefs.agentNetworkAccessEnabled) {
            throw SecurityException("Agent 网络访问已在设置中关闭，插件无法发起网络请求")
        }
    }

    /**
     * 只允许访问公网 HTTPS 地址。
     *
     * 插件由 AI 通过 plugin_use 创建，「从 URL 安装」与 http_get 的目标都不可信：
     * 仅校验 scheme 时可以用 `https://127.0.0.1/`、`https://192.168.x.x/` 探测内网服务
     * （SSRF）。这里同时解析域名，拦住「域名解析到内网」这种常见绕过。
     *
     * 局限：DNS 重绑定（先解析为公网、连接时再解析为内网）无法在此彻底杜绝，
     * 要完全解决需要把校验过的 IP 绑定到连接层。
     */
    private fun requirePublicHttpsUrl(raw: String, what: String) {
        val url = runCatching { raw.toHttpUrlOrNull() }.getOrNull()
            ?: throw IllegalArgumentException("$what 地址无效")
        if (!url.isHttps) throw IllegalArgumentException("$what 只允许 HTTPS")
        val host = url.host
        if (isBlockedHostName(host)) {
            throw IllegalArgumentException("$what 不允许访问内网或本机地址：$host")
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw IllegalArgumentException("$what 不允许访问内网或本机地址：$host")
        }
    }

    private fun isBlockedHostName(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true)

    private fun isBlockedAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            isUniqueLocalIpv6(address)

    /** IPv6 唯一本地地址 fc00::/7。 */
    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
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

        /** 单个 JavaScript 文件与入口脚本的运行上限（1 MiB）。 */
        const val MAX_SCRIPT_BYTES = 1024L * 1024
        const val MAX_RESOURCE_BYTES = 4L * 1024 * 1024
        const val MAX_REPLY_CHARS = 20_000
        const val MAX_SCRIPT_CHARS = 1024 * 1024
        const val RUNTIME_TIMEOUT_MS = 20_000L
        /** 声明 ai.call 的插件命令需要等待模型生成，放宽总超时。 */
        const val RUNTIME_TIMEOUT_WITH_AI_MS = 120_000L

        /** 钩子在消息发送路径上执行，超时后保持原文，避免拖慢发送。 */
        const val HOOK_TIMEOUT_MS = 8_000L
        const val HOOK_MESSAGE_BEFORE_SEND = "message.beforeSend"
        const val HOOK_APP_LIFECYCLE = "app.lifecycle"

        /** 钩子可改写的内容上限。 */
        const val MAX_HOOK_CONTENT_CHARS = 20_000

        /** app.lifecycle 支持的事件。 */
        val LIFECYCLE_EVENTS: Set<String> = setOf("app.start", "chat.open", "chat.close")
    }
}
