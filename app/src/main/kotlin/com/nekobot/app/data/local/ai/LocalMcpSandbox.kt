package com.nekobot.app.data.local.ai

import android.content.Context
import com.nekobot.app.data.local.LocalWorkspaceStorage
import java.io.File

/**
 * 在本地 Alpine 沙盒（PRoot）内启动 MCP stdio 服务。
 *
 * Android 应用进程里没有 npx/node/python 这类运行时（不在 PATH 中），
 * 应用私有目录中的二进制也无法执行（W^X/SELinux 限制），
 * 因此 stdio 服务统一交给沙盒运行；运行时需用户先在沙盒终端安装（如 apk add nodejs npm）。
 */
internal object LocalMcpSandbox {

    private const val SANDBOX_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** PRoot 自身依赖的环境变量，不允许被用户配置的 env 覆盖。 */
    private val PROTECTED_ENV_KEYS = setOf(
        "PROOT_TMP_DIR", "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_LOADER_32"
    )

    /** 只有完全由这些字符组成的参数才省略引号。 */
    private const val SAFE_SHELL_CHARS = "-_./:@%+=,"

    /** stdio 服务的沙盒工作目录（挂载到 /workspace，同时作为 cwd）。 */
    private const val MCP_WORKSPACE_DIR = "mcp-workspace"

    /**
     * 启动沙盒内的 stdio 服务进程。
     *
     * rootfs 未安装时会先解包（可能耗时数秒），调用方必须放在 IO 线程并处理失败回退。
     */
    fun startProcess(
        context: Context,
        command: String,
        args: List<String>,
        env: Map<String, String>,
    ): Process {
        val appContext = context.applicationContext
        val runtime = LocalLinuxRootfsManager.getInstance(appContext).ensureReady()
        val sharedWorkspace = LocalWorkspaceStorage.resolveShared(appContext.filesDir)
        val workspace = File(appContext.filesDir, MCP_WORKSPACE_DIR).apply { mkdirs() }
        // PRoot 的绑定目标必须已存在，旧 rootfs 可能缺少这些目录。
        File(runtime.rootfs, "workspace").mkdirs()
        if (sharedWorkspace != null) File(runtime.rootfs, "shared").mkdirs()

        val argv = buildLocalMcpSandboxCommand(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
            command = command,
            args = args,
            sharedWorkspace = sharedWorkspace,
        )
        val builder = ProcessBuilder(argv).directory(appContext.filesDir)
        builder.environment().apply {
            this["PROOT_TMP_DIR"] = runtime.prootTempDir.absolutePath
            this["LD_LIBRARY_PATH"] = runtime.nativeLibraryDir.absolutePath
            runtime.loader64?.let { this["PROOT_LOADER"] = it.absolutePath }
            runtime.loader32?.let { this["PROOT_LOADER_32"] = it.absolutePath }
            this["HOME"] = "/root"
            this["PATH"] = SANDBOX_PATH
            this["LANG"] = "C.UTF-8"
            this["LC_ALL"] = "C.UTF-8"
            this["TERM"] = "dumb"
            this["TZ"] = localPosixTimezone()
            putAll(sandboxEnvOverrides(env))
        }
        return builder.start()
    }

    /** 用户 env 中允许生效的部分（剔除空 key 与 PRoot 内部变量）。 */
    internal fun sandboxEnvOverrides(env: Map<String, String>): Map<String, String> =
        env.filterKeys { it.isNotBlank() && it !in PROTECTED_ENV_KEYS }

    /** 拼接 sh -c 的命令串：command 与 args 逐个做 POSIX 引号转义。 */
    internal fun buildShellCommand(command: String, args: List<String>): String =
        (listOf(command) + args).joinToString(" ") { localPosixQuote(it) }

    /** POSIX 单引号转义，保证含空格/引号/shell 特殊字符的参数原样传入沙盒。 */
    internal fun localPosixQuote(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in SAFE_SHELL_CHARS }) {
            value
        } else {
            "'" + value.replace("'", "'\\''") + "'"
        }
}

/**
 * 生成沙盒内 MCP stdio 服务的启动参数：PRoot 前缀 + /bin/sh -c "<command> <args>"。
 *
 * 经沙盒内 shell 启动，让 node/npx/python 按沙盒 PATH（apk 安装到 /usr/bin）解析。
 * 保持为纯函数以便 JVM 单元测试覆盖。
 */
internal fun buildLocalMcpSandboxCommand(
    proot: File,
    rootfs: File,
    workspace: File,
    command: String,
    args: List<String>,
    sharedWorkspace: File? = null,
): List<String> = buildLocalProotPrefix(
    proot = proot,
    rootfs = rootfs,
    workspace = workspace,
    sharedWorkspace = sharedWorkspace,
) + listOf("/bin/sh", "-c", LocalMcpSandbox.buildShellCommand(command, args))
