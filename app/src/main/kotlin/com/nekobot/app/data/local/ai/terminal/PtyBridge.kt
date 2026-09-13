package com.nekobot.app.data.local.ai.terminal

import android.util.Log

/**
 * 原生 PTY 桥接（libpty_bridge.so）的 Kotlin 入口。
 *
 * 沙箱终端需要真正的 TTY：只有挂在伪终端上，shell 才会打印提示符、
 * 按 termios 回显与处理 Ctrl+C，vi/top 这类全屏程序也才能工作。
 * 具体实现见 `app/src/main/cpp/pty_bridge.c`，重新编译脚本见 `tools/build_pty_bridge.ps1`。
 *
 * 设备缺少 arm64 原生库时 [isAvailable] 为 false，调用方应回退到进程管道方案。
 */
internal object PtyBridge {
    private const val TAG = "NekoPty"
    private const val LIBRARY = "pty_bridge"

    /** 动态库是否加载成功（只在首次访问时尝试一次）。 */
    val isAvailable: Boolean = runCatching { System.loadLibrary(LIBRARY) }
        .onFailure { Log.w(TAG, "PTY 原生库不可用：${it.message}") }
        .isSuccess

    /**
     * 在新建的 PTY 上 fork 并 execve [command]。
     *
     * @param argv argv[0] 约定为程序名，需与 [command] 一起传入。
     * @param envp "KEY=VALUE" 形式的环境变量。
     * @param cwd 子进程工作目录，null 表示继承。
     * @param outPid 长度 1 的数组，成功时写入子进程 pid。
     * @return 成功返回 PTY master fd，失败返回负的 errno。
     */
    external fun forkExec(
        command: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        cols: Int,
        rows: Int,
        outPid: IntArray,
    ): Int

    /** 从 PTY master 读取；返回字节数、0 表示 EOF、负值为 -errno。 */
    external fun readBytes(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    /** 向 PTY master 写入；返回写入字节数或负的 errno。 */
    external fun writeBytes(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    /** ioctl(TIOCSWINSZ)，返回 0 或 -errno。 */
    external fun setWindowSize(fd: Int, cols: Int, rows: Int): Int

    /** 关闭 master fd。 */
    external fun closeFd(fd: Int): Int

    /** kill(pid, sig)，返回 0 或 -errno。 */
    external fun sendSignal(pid: Int, signal: Int): Int

    /**
     * 阻塞等待子进程结束。
     * 正常退出返回退出码，被信号终止返回 -(128+signal)，失败返回 -errno。
     */
    external fun waitFor(pid: Int): Int
}
