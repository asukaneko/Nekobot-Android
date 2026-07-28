package com.nekobot.app.data.local.ai

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** 命令行界面使用的稳定结果模型。 */
data class LocalSandboxCommandResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val timedOut: Boolean,
    val error: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null && !timedOut && exitCode == 0
}

/**
 * Agent 模式使用的 Alpine Linux 沙盒。
 *
 * rootfs 在应用内全局共享，软件安装和 /root 数据可以跨 Agent 会话保留；
 * 每个会话拥有独立的持久 shell，并把自己的 Android 工作区挂载到 /workspace。
 */
internal object LocalLinuxSandboxCoordinator {
    private const val TAG = "LocalLinuxSandbox"

    private val shells = ConcurrentHashMap<String, LocalPersistentLinuxShell>()
    private val sessionLocks = ConcurrentHashMap<String, Any>()

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val timedOut: Boolean,
        val stopped: Boolean,
    )

    fun execute(
        context: Context,
        sessionId: String,
        workspace: File,
        command: String,
        timeoutMs: Long,
        shouldStop: () -> Boolean,
    ): CommandResult {
        val candidateLock = Any()
        val lock = sessionLocks.putIfAbsent(sessionId, candidateLock) ?: candidateLock
        return synchronized(lock) {
            val startedAt = System.currentTimeMillis()
            if (shouldStop()) {
                return@synchronized CommandResult("", -1, 0L, timedOut = false, stopped = true)
            }

            val runtime = LocalLinuxRootfsManager.getInstance(context).ensureReady()
            val shell = shells[sessionId]
                ?.takeIf { it.isAlive && it.workspace == workspace.canonicalFile }
                ?: createShell(context, sessionId, workspace, runtime)

            val shellResult = shell.execute(command, timeoutMs, shouldStop)
            if (shellResult.timedOut || shellResult.stopped || !shell.isAlive) {
                shells.remove(sessionId, shell)
                shell.stop()
            }

            CommandResult(
                output = shellResult.output,
                exitCode = shellResult.exitCode,
                durationMs = System.currentTimeMillis() - startedAt,
                timedOut = shellResult.timedOut,
                stopped = shellResult.stopped,
            )
        }
    }

    private fun createShell(
        context: Context,
        sessionId: String,
        workspace: File,
        runtime: LocalLinuxRuntime,
    ): LocalPersistentLinuxShell {
        shells.remove(sessionId)?.stop()
        val shell = LocalPersistentLinuxShell(
            context = context.applicationContext,
            sessionId = sessionId,
            workspace = workspace.canonicalFile,
            runtime = runtime,
        )
        shell.start()
        shells[sessionId] = shell
        return shell
    }

    /** 删除会话或停止生成时终止进程；rootfs 和工作区文件仍保留在磁盘。 */
    fun stopSession(sessionId: String) {
        shells.remove(sessionId)?.stop()
        sessionLocks.remove(sessionId)
    }

    fun closeAll() {
        shells.values.forEach(LocalPersistentLinuxShell::stop)
        shells.clear()
        sessionLocks.clear()
    }
}

internal data class LocalLinuxRuntime(
    val rootfs: File,
    val proot: File,
    val nativeLibraryDir: File,
    val loader64: File?,
    val loader32: File?,
    val prootTempDir: File,
)

/**
 * 安装随 APK 附带的 Alpine minirootfs，并定位从 jniLibs 解压出的 PRoot。
 *
 * 安装过程先写入 staging 目录，只有完整解包后才切换为正式 rootfs，
 * 避免应用在首次初始化中断后留下看似可用的半成品环境。
 */
internal class LocalLinuxRootfsManager private constructor(
    private val context: Context,
) {
    private val sandboxDir = File(context.filesDir, SANDBOX_DIR)
    private val rootfsDir = File(sandboxDir, ROOTFS_DIR)
    private val stagingDir = File(sandboxDir, "$ROOTFS_DIR.installing")
    private val markerFile get() = File(rootfsDir, INSTALL_MARKER)

    fun ensureReady(): LocalLinuxRuntime = synchronized(installLock) {
        requireSupportedAbi()

        val nativeLibraryDir = File(
            context.applicationInfo.nativeLibraryDir
                ?: throw IllegalStateException("无法定位 Android 原生库目录")
        )
        val proot = File(nativeLibraryDir, PROOT_LIBRARY)
        if (!proot.isFile || !proot.canExecute()) {
            throw IllegalStateException("PRoot 运行时不可用：${proot.absolutePath}")
        }

        if (!isInstalled()) installRootfs()
        refreshDns(rootfsDir)

        val tempDir = File(context.cacheDir, "nekobot-proot-tmp").apply { mkdirs() }
        LocalLinuxRuntime(
            rootfs = rootfsDir,
            proot = proot,
            nativeLibraryDir = nativeLibraryDir,
            loader64 = File(nativeLibraryDir, PROOT_LOADER_64).takeIf(File::isFile),
            loader32 = File(nativeLibraryDir, PROOT_LOADER_32).takeIf(File::isFile),
            prootTempDir = tempDir,
        )
    }

    private fun requireSupportedAbi() {
        if (Build.SUPPORTED_ABIS.none { it == SUPPORTED_ABI }) {
            throw UnsupportedOperationException(
                "Linux 沙盒目前仅支持 arm64-v8a，当前设备为 ${Build.SUPPORTED_ABIS.joinToString()}"
            )
        }
    }

    private fun isInstalled(): Boolean {
        val shell = File(rootfsDir, "bin/sh")
        return rootfsDir.isDirectory &&
            (shell.isFile || Files.isSymbolicLink(shell.toPath())) &&
            runCatching { markerFile.readText().trim() == ROOTFS_REVISION }.getOrDefault(false)
    }

    private fun installRootfs() {
        Log.i(TAG, "Installing bundled Alpine rootfs")
        sandboxDir.mkdirs()
        deleteTreeWithoutFollowingLinks(stagingDir)
        stagingDir.mkdirs()

        try {
            val assetName = runCatching {
                context.assets.open(ROOTFS_TAR).close()
                ROOTFS_TAR
            }.getOrElse { ROOTFS_TAR_GZ }

            context.assets.open(assetName).use { asset ->
                if (assetName.endsWith(".gz")) {
                    GZIPInputStream(asset).use { LocalSafeTarExtractor.extract(it, stagingDir) }
                } else {
                    LocalSafeTarExtractor.extract(asset, stagingDir)
                }
            }

            listOf("workspace", "root", "tmp", "var/tmp").forEach { relative ->
                File(stagingDir, relative).mkdirs()
            }
            refreshDns(stagingDir)
            File(stagingDir, INSTALL_MARKER).writeText(ROOTFS_REVISION)

            deleteTreeWithoutFollowingLinks(rootfsDir)
            check(stagingDir.renameTo(rootfsDir)) { "无法启用已解包的 Alpine rootfs" }
            check(isInstalled()) { "Alpine rootfs 完整性校验失败" }
            Log.i(TAG, "Bundled Alpine rootfs installed at ${rootfsDir.absolutePath}")
        } catch (error: Throwable) {
            deleteTreeWithoutFollowingLinks(stagingDir)
            Log.e(TAG, "Failed to install Alpine rootfs", error)
            throw IllegalStateException("初始化 Alpine Linux 沙盒失败：${error.message}", error)
        }
    }

    /** 删除旧 rootfs 时不跟随其中的绝对或目录符号链接。 */
    private fun deleteTreeWithoutFollowingLinks(file: File) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            file.listFiles()?.forEach(::deleteTreeWithoutFollowingLinks)
        }
        check(file.delete() || (!file.exists() && !Files.isSymbolicLink(file.toPath()))) {
            "无法删除旧沙盒文件：${file.absolutePath}"
        }
    }

    private fun refreshDns(targetRootfs: File) {
        val servers = linkedSetOf<String>()
        runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivity?.activeNetwork
            if (activeNetwork != null) {
                connectivity.getLinkProperties(activeNetwork)
                    ?.dnsServers
                    ?.mapNotNullTo(servers) { it.hostAddress }
            }
        }.onFailure { Log.w(TAG, "读取系统 DNS 失败：${it.message}") }

        if (servers.isEmpty()) {
            servers += "8.8.8.8"
            servers += "1.1.1.1"
        }
        File(targetRootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText(servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" })
        }
    }

    companion object {
        private const val TAG = "LocalLinuxRootfs"
        private const val SANDBOX_DIR = "linux_sandbox"
        private const val ROOTFS_DIR = "alpine-rootfs"
        private const val ROOTFS_TAR = "alpine-minirootfs.tar"
        private const val ROOTFS_TAR_GZ = "alpine-minirootfs.tar.gz"
        private const val INSTALL_MARKER = ".nekobot-rootfs"
        private const val ROOTFS_REVISION =
            "alpine-3.21.3-arm64-5651126278f52f29"
        private const val SUPPORTED_ABI = "arm64-v8a"
        private const val PROOT_LIBRARY = "libproot.so"
        private const val PROOT_LOADER_64 = "libproot-loader.so"
        private const val PROOT_LOADER_32 = "libproot-loader32.so"

        private val installLock = Any()

        @Volatile
        private var instance: LocalLinuxRootfsManager? = null

        fun getInstance(context: Context): LocalLinuxRootfsManager =
            instance ?: synchronized(this) {
                instance ?: LocalLinuxRootfsManager(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 单个 Agent 会话持有一个长期运行的 /bin/sh。
 *
 * shell 的 cwd、export 和后台进程在同一会话的后续工具调用中继续存在；
 * 命令结束标记使用随机 token，并支持标记被拆分到多个 stdout 数据块。
 */
internal class LocalPersistentLinuxShell(
    private val context: Context,
    private val sessionId: String,
    val workspace: File,
    private val runtime: LocalLinuxRuntime,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var pending: PendingCommand? = null

    val isAlive: Boolean
        get() = process?.isAlive == true

    @Synchronized
    fun start() {
        if (isAlive) return
        workspace.mkdirs()

        val command = buildLocalProotCommand(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
        )
        val builder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            this["PROOT_TMP_DIR"] = runtime.prootTempDir.absolutePath
            this["LD_LIBRARY_PATH"] = runtime.nativeLibraryDir.absolutePath
            runtime.loader64?.let { this["PROOT_LOADER"] = it.absolutePath }
            runtime.loader32?.let { this["PROOT_LOADER_32"] = it.absolutePath }
            this["HOME"] = "/root"
            this["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            this["LANG"] = "C.UTF-8"
            this["LC_ALL"] = "C.UTF-8"
            this["TERM"] = "dumb"
            this["PS1"] = ""
            this["TZ"] = localPosixTimezone()
            this["NEKOBOT_SESSION_ID"] = sessionId
        }

        val started = builder.start()
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        Thread({ readLoop(started) }, "NekobotLinux-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    fun execute(
        command: String,
        timeoutMs: Long,
        shouldStop: () -> Boolean,
    ): ShellResult {
        if (!isAlive) start()
        val activeWriter = writer
            ?: return ShellResult("Linux shell 未运行", -1, timedOut = false, stopped = false)
        val token = UUID.randomUUID().toString().replace("-", "")
        val callback = PendingCommand(token)
        pending = callback

        try {
            activeWriter.write(command)
            activeWriter.write("\n__nekobot_exit=\$?\n")
            activeWriter.write("printf '${callback.markerPrefix}%s__\\n' \"\$__nekobot_exit\"\n")
            activeWriter.flush()
        } catch (error: Exception) {
            pending = null
            callback.fail()
            stop()
            return ShellResult("写入 Linux shell 失败：${error.message}", -1, false, false)
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if (callback.latch.await(80, TimeUnit.MILLISECONDS)) {
                pending = null
                return ShellResult(
                    output = callback.renderOutput(),
                    exitCode = callback.exitCode,
                    timedOut = false,
                    stopped = false,
                )
            }
            if (shouldStop()) {
                pending = null
                stop()
                return ShellResult(callback.renderOutput(), -1, timedOut = false, stopped = true)
            }
            if (System.nanoTime() >= deadline) {
                pending = null
                stop()
                return ShellResult(callback.renderOutput(), 124, timedOut = true, stopped = false)
            }
            if (!isAlive) {
                pending = null
                return ShellResult(callback.renderOutput(), -1, timedOut = false, stopped = false)
            }
        }
    }

    private fun readLoop(activeProcess: Process) {
        try {
            activeProcess.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    pending?.accept(String(buffer, 0, count))
                }
            }
        } catch (error: Exception) {
            Log.d(TAG, "Linux shell output ended: ${error.message}")
        } finally {
            if (process === activeProcess) {
                process = null
                writer = null
            }
            pending?.fail()
        }
    }

    @Synchronized
    fun stop() {
        val active = process
        process = null
        runCatching { writer?.close() }
        writer = null
        runCatching { active?.destroy() }
        if (active?.isAlive == true) runCatching { active.destroyForcibly() }
        pending?.fail()
        pending = null
    }

    data class ShellResult(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val stopped: Boolean,
    )

    private class PendingCommand(token: String) {
        val markerPrefix = "__NEKOBOT_DONE_${token}_EXIT_"
        val latch = CountDownLatch(1)
        private val collector = LocalLinuxCommandOutputCollector(markerPrefix, MAX_OUTPUT_CHARS)

        @Volatile
        var exitCode: Int = -1
            private set

        @Synchronized
        fun accept(text: String) {
            val parsedExitCode = collector.accept(text) ?: return
            exitCode = parsedExitCode
            latch.countDown()
        }

        fun fail() {
            latch.countDown()
        }

        fun renderOutput(): String = collector.renderOutput()
    }

    companion object {
        private const val TAG = "LocalPersistentLinux"
        private const val MAX_OUTPUT_CHARS = 20_000
    }
}

/**
 * 流式解析 shell 完成标记。保留不足一个标记长度的尾部，
 * 因而标记即使跨 stdout 数据块也不会泄露到命令输出。
 */
internal class LocalLinuxCommandOutputCollector(
    private val markerPrefix: String,
    private val maxOutputChars: Int,
) {
    private val scanBuffer = StringBuilder()
    private val output = StringBuilder()
    private var truncated = false
    private var completed = false

    @Synchronized
    fun accept(text: String): Int? {
        if (completed) return null
        scanBuffer.append(text)

        val markerIndex = scanBuffer.indexOf(markerPrefix)
        if (markerIndex >= 0) {
            val codeStart = markerIndex + markerPrefix.length
            val codeEnd = scanBuffer.indexOf("__", codeStart)
            if (codeEnd >= 0) {
                appendOutput(scanBuffer.substring(0, markerIndex))
                val exitCode = scanBuffer.substring(codeStart, codeEnd).toIntOrNull() ?: -1
                scanBuffer.clear()
                completed = true
                return exitCode
            }
        }

        val safeLength = (scanBuffer.length - markerPrefix.length - 12).coerceAtLeast(0)
        if (safeLength > 0) {
            appendOutput(scanBuffer.substring(0, safeLength))
            scanBuffer.delete(0, safeLength)
        }
        return null
    }

    @Synchronized
    fun renderOutput(): String {
        if (!completed && scanBuffer.isNotEmpty()) {
            appendOutput(scanBuffer.toString())
            scanBuffer.clear()
        }
        return buildString {
            append(output)
            if (truncated) {
                if (isNotEmpty() && last() != '\n') appendLine()
                append("[输出已截断，最多返回 $maxOutputChars 个字符]")
            }
        }
    }

    private fun appendOutput(text: String) {
        val remaining = maxOutputChars - output.length
        if (remaining <= 0) {
            if (text.isNotEmpty()) truncated = true
            return
        }
        output.append(text, 0, minOf(remaining, text.length))
        if (text.length > remaining) truncated = true
    }
}

/** 生成 PRoot 启动参数，保持为纯函数以便 JVM 单元测试覆盖挂载边界。 */
internal fun buildLocalProotCommand(
    proot: File,
    rootfs: File,
    workspace: File,
): List<String> = listOf(
    proot.absolutePath,
    "-0",
    "--link2symlink",
    "-r",
    rootfs.absolutePath,
    "-b",
    "/dev",
    "-b",
    "/proc",
    "-b",
    "/sys",
    "-b",
    "${workspace.absolutePath}:/workspace",
    "-w",
    "/workspace",
    "/bin/sh",
)

private fun localPosixTimezone(): String {
    val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
    if (offsetMs == 0) return "UTC0"
    val absoluteMinutes = kotlin.math.abs(offsetMs / 60_000)
    val hours = absoluteMinutes / 60
    val minutes = absoluteMinutes % 60
    val sign = if (offsetMs > 0) "-" else "+"
    return if (minutes == 0) "LCL$sign$hours" else "LCL$sign$hours:$minutes"
}

/**
 * 不依赖第三方库的 POSIX tar 解包器。
 *
 * 所有输出路径都先做词法归一化，并拒绝绝对路径、.. 逃逸以及经由已创建
 * symlink 目录继续写入，避免恶意归档覆盖应用私有目录之外的文件。
 */
internal object LocalSafeTarExtractor {
    fun extract(input: InputStream, targetDir: File) {
        val header = ByteArray(TAR_BLOCK_SIZE)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead == 0) break
            check(bytesRead == TAR_BLOCK_SIZE) { "tar header 不完整" }
            if (header.all { it == 0.toByte() }) break

            val name = pendingLongName ?: tarString(header, 0, 100)
            pendingLongName = null
            val prefix = tarString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty() && !name.startsWith(prefix)) "$prefix/$name" else name
            val mode = tarString(header, 100, 8).trim().toIntOrNull(8) ?: 0
            val size = tarString(header, 124, 12).trim().toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            val linkName = pendingLongLink ?: tarString(header, 157, 100)
            pendingLongLink = null

            when (type) {
                'L' -> {
                    pendingLongName = readEntryText(input, size)
                    continue
                }
                'K' -> {
                    pendingLongLink = readEntryText(input, size)
                    continue
                }
                'x', 'g' -> {
                    skipEntry(input, size)
                    continue
                }
            }

            val outputFile = resolveEntry(targetDir, fullName)
            when (type) {
                '5', 'D' -> outputFile.mkdirs()
                '2' -> createSymlink(outputFile, linkName)
                '1' -> createHardLinkCopy(targetDir, outputFile, linkName)
                '0', '\u0000' -> {
                    outputFile.parentFile?.mkdirs()
                    outputFile.outputStream().use { output ->
                        copyExactly(input, output, size)
                    }
                    if (mode and 0b001_001_001 != 0) outputFile.setExecutable(true, false)
                    skipPadding(input, size)
                    continue
                }
            }
            skipEntry(input, size)
        }
    }

    internal fun resolveEntry(targetDir: File, rawName: String): File {
        val normalizedName = rawName
            .replace('\\', '/')
            .removePrefix("./")
            .trimEnd('/')
        if (normalizedName.isEmpty()) return targetDir
        require(!normalizedName.startsWith('/')) { "tar 包含绝对路径：$rawName" }

        val root = targetDir.toPath().toAbsolutePath().normalize()
        val output = root.resolve(normalizedName).normalize()
        require(output.startsWith(root)) { "tar 路径越界：$rawName" }

        var parent = output.parent
        while (parent != null && parent != root) {
            require(!Files.isSymbolicLink(parent)) { "tar 路径经过符号链接目录：$rawName" }
            parent = parent.parent
        }
        return output.toFile()
    }

    private fun createSymlink(outputFile: File, linkName: String) {
        outputFile.parentFile?.mkdirs()
        runCatching {
            Files.deleteIfExists(outputFile.toPath())
            Files.createSymbolicLink(outputFile.toPath(), Paths.get(linkName))
        }.getOrElse { throw IllegalStateException("创建符号链接失败：${outputFile.path} -> $linkName", it) }
    }

    private fun createHardLinkCopy(targetDir: File, outputFile: File, linkName: String) {
        val target = resolveEntry(targetDir, linkName)
        require(target.isFile) { "tar 硬链接目标不存在：$linkName" }
        outputFile.parentFile?.mkdirs()
        target.copyTo(outputFile, overwrite = true)
    }

    private fun readEntryText(input: InputStream, size: Long): String {
        require(size <= 1024 * 1024) { "tar 长路径字段过大" }
        val bytes = ByteArray(size.toInt())
        check(readFully(input, bytes) == bytes.size) { "tar 长路径字段不完整" }
        skipPadding(input, size)
        return String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun skipEntry(input: InputStream, size: Long) {
        skipFully(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val remainder = size % TAR_BLOCK_SIZE
        if (remainder != 0L) skipFully(input, TAR_BLOCK_SIZE - remainder)
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(count >= 0) { "tar 文件内容不完整" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun tarString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (index in offset until end) {
            if (header[index] == 0.toByte()) break
            actualEnd = index + 1
        }
        return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) return offset
            offset += count
        }
        return offset
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(read >= 0) { "tar 数据不完整" }
            remaining -= read
        }
    }

    private const val TAR_BLOCK_SIZE = 512
}
