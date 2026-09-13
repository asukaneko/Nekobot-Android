package com.nekobot.app.data.local.ai.terminal

import android.content.Context
import android.util.Log
import com.nekobot.app.data.local.LocalWorkspaceStorage
import com.nekobot.app.data.local.ai.LocalLinuxRootfsManager
import com.nekobot.app.data.local.ai.LocalLinuxRuntime
import com.nekobot.app.data.local.ai.buildLocalProotPrefix
import com.nekobot.app.data.local.ai.localPosixTimezone
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 用户可交互的沙箱终端会话：PRoot + Alpine `/bin/sh -l -i` 跑在一个真正的 PTY 上。
 *
 * 与 Agent 用的 [LocalPersistentLinuxShell] 分开：Agent 那条链路靠 stdout 结束标记
 * 解析命令结果，用户在这里的实时按键会污染该协议，所以各自持有独立 shell，
 * 但共用同一份 rootfs，并把工作区/共享目录以相同路径挂载。
 *
 * shell 挂在伪终端上意味着：提示符、行编辑、Tab 补全、Ctrl+C、SIGWINCH 尺寸变化、
 * vi/top 等全屏程序都由内核和 shell 自己处理，这一层只搬运原始字节。
 */
internal class LocalTerminalSession(
    context: Context,
    val sessionId: String,
    private val workspace: File,
) {
    enum class State { IDLE, STARTING, RUNNING, STOPPED, UNAVAILABLE }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** PTY 原始输出字节，按到达顺序流式发出，直接喂给 [TerminalEmulator]。 */
    private val _output = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    /** 进程退出时回调退出码（读线程）。 */
    var onExit: ((Int) -> Unit)? = null

    @Volatile
    private var masterFd: Int = -1

    @Volatile
    private var childPid: Int = 0

    @Volatile
    private var cols: Int = TerminalEmulator.DEFAULT_COLS

    @Volatile
    private var rows: Int = TerminalEmulator.DEFAULT_ROWS

    @Volatile
    private var stopping: Boolean = false

    private var readerJob: Job? = null
    private var waitJob: Job? = null
    private val writeLock = Any()

    val isRunning: Boolean get() = _state.value == State.RUNNING

    /**
     * 启动 PTY shell；重复调用（启动中/运行中）直接返回 true。
     *
     * rootfs 解包等重活在线程池里做，界面通过 [state] 观察进度。
     */
    fun start(initialCols: Int = cols, initialRows: Int = rows): Boolean {
        when (_state.value) {
            State.RUNNING, State.STARTING -> return true
            else -> Unit
        }
        if (!PtyBridge.isAvailable) {
            _state.value = State.UNAVAILABLE
            return false
        }
        cols = initialCols.coerceAtLeast(2)
        rows = initialRows.coerceAtLeast(2)
        stopping = false
        _state.value = State.STARTING
        scope.launch { boot() }
        return true
    }

    /** 写入原始字节（按键序列、控制码等）。 */
    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val fd = masterFd
        if (fd < 0) return
        synchronized(writeLock) {
            var offset = 0
            while (offset < bytes.size) {
                val chunk = minOf(WRITE_CHUNK, bytes.size - offset)
                val written = PtyBridge.writeBytes(fd, bytes, offset, chunk)
                if (written <= 0) {
                    Log.w(TAG, "PTY 写入失败：$written")
                    return
                }
                offset += written
            }
        }
    }

    /**
     * 写入文本。
     *
     * PTY 的 Enter 是回车（CR），换行交给 termios 的 ICRNL 处理，
     * 因此这里把 \r\n 与 \n 统一折叠成 \r，否则 shell 会把 \n 当成字面换行。
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendBytes(normalizeLineEndings(text).toByteArray(Charsets.UTF_8))
    }

    /** 窗口尺寸变化：写入 TIOCSWINSZ，内核会向 shell 发 SIGWINCH。 */
    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        val fd = masterFd
        if (fd >= 0) PtyBridge.setWindowSize(fd, newCols, newRows)
    }

    /** 终止 shell：关闭 master fd 并逐步升级信号，随后可重新 [start]。 */
    fun stop() {
        stopping = true
        readerJob?.cancel()
        readerJob = null
        val fd = masterFd
        val pid = childPid
        masterFd = -1
        childPid = 0
        if (fd >= 0) runCatching { PtyBridge.closeFd(fd) }
        if (pid > 0) {
            runCatching { PtyBridge.sendSignal(pid, SIGNAL_SIGHUP) }
            runCatching { PtyBridge.sendSignal(pid, SIGNAL_SIGTERM) }
            scope.launch {
                delay(FORCE_KILL_DELAY_MS)
                runCatching { PtyBridge.sendSignal(pid, SIGNAL_SIGKILL) }
            }
        }
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
    }

    /** 释放会话（协程作用域与文件描述符），此后对象不可复用。 */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private suspend fun boot() {
        try {
            val runtime = LocalLinuxRootfsManager.getInstance(appContext).ensureReady()
            val sharedWorkspace = LocalWorkspaceStorage.resolveShared(appContext.filesDir)
            if (sharedWorkspace != null) {
                File(runtime.rootfs, "shared").mkdirs()
            }
            workspace.mkdirs()

            val argv = buildLocalProotPrefix(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                sharedWorkspace = sharedWorkspace,
            ) + listOf(SHELL, "-l", "-i")

            val outPid = IntArray(1)
            val fd = PtyBridge.forkExec(
                command = argv.first(),
                argv = argv.toTypedArray(),
                envp = buildEnvironment(runtime, sharedWorkspace),
                cwd = appContext.filesDir.absolutePath,
                cols = cols,
                rows = rows,
                outPid = outPid,
            )
            if (fd < 0) {
                Log.e(TAG, "forkExec 失败：errno=${-fd}")
                _output.emit("\r\n终端启动失败（errno=${-fd}）\r\n".toByteArray(Charsets.UTF_8))
                _state.value = State.STOPPED
                return
            }

            masterFd = fd
            childPid = outPid[0]
            _state.value = State.RUNNING
            Log.i(TAG, "终端已启动 pid=$childPid fd=$fd ${cols}x$rows")

            readerJob = scope.launch { readLoop(fd) }
            waitJob = scope.launch { awaitExit(childPid) }
        } catch (error: Throwable) {
            Log.e(TAG, "终端启动异常", error)
            _output.emit(
                "\r\n终端启动失败：${error.message ?: error.javaClass.simpleName}\r\n"
                    .toByteArray(Charsets.UTF_8),
            )
            _state.value = State.STOPPED
        }
    }

    private suspend fun readLoop(fd: Int) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (scope.isActive && masterFd == fd) {
            val read = PtyBridge.readBytes(fd, buffer, 0, buffer.size)
            if (read <= 0) {
                if (read < 0) Log.d(TAG, "PTY 读结束：errno=${-read}")
                break
            }
            _output.emit(buffer.copyOf(read))
        }
    }

    private suspend fun awaitExit(pid: Int) {
        val status = PtyBridge.waitFor(pid)
        if (stopping) return
        _output.emit("\r\n[shell 已退出：$status]\r\n".toByteArray(Charsets.UTF_8))
        _state.value = State.STOPPED
        runCatching { onExit?.invoke(status) }
    }

    private fun buildEnvironment(
        runtime: LocalLinuxRuntime,
        sharedWorkspace: File?,
    ): Array<String> {
        val environment = linkedMapOf(
            "PROOT_TMP_DIR" to runtime.prootTempDir.absolutePath,
            "LD_LIBRARY_PATH" to runtime.nativeLibraryDir.absolutePath,
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TZ" to localPosixTimezone(),
            "NEKOBOT_SESSION_ID" to sessionId,
        )
        runtime.loader64?.let { environment["PROOT_LOADER"] = it.absolutePath }
        runtime.loader32?.let { environment["PROOT_LOADER_32"] = it.absolutePath }
        if (sharedWorkspace != null) {
            environment["NEKOBOT_SHARED_DIR"] = "/shared"
        }
        return environment.map { (key, value) -> "$key=$value" }.toTypedArray()
    }

    private fun normalizeLineEndings(text: String): String {
        if ('\n' !in text) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                builder.append('\r')
                index += 2
                continue
            }
            builder.append(if (char == '\n') '\r' else char)
            index++
        }
        return builder.toString()
    }

    private companion object {
        const val TAG = "NekoTerminal"
        const val SHELL = "/bin/sh"
        const val READ_BUFFER_SIZE = 8192
        const val WRITE_CHUNK = 2048
        const val CARRIAGE_RETURN = 0x0D.toByte()
        const val FORCE_KILL_DELAY_MS = 400L
        const val SIGNAL_SIGHUP = 1
        const val SIGNAL_SIGTERM = 15
        const val SIGNAL_SIGKILL = 9
    }
}
