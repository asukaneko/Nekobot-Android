package com.nekobot.app.data.local.ai

import com.nekobot.app.ServiceContainer
import java.io.File

/**
 * Linux 沙盒的镜像源配置（「设置 → Agent 设置 → 沙箱管理」）。
 *
 * 三个字段都允许留空，语义是「不要写这份配置」：
 * - [apkBase] 留空表示把 `etc/apk/repositories` 恢复成首次修改前的原始内容；
 * - [pipIndexUrl] / [npmRegistry] 留空表示删除由本应用写入的 pip.conf / .npmrc。
 *
 * 配置只在 Sandbox 启动或用户手动点「保存并应用」时落盘到 rootfs，
 * 因此不会因为改配置就重启正在运行的会话。
 */
data class LocalSandboxMirrors(
    /** apk 镜像基地址，例如 `https://mirrors.tuna.tsinghua.edu.cn/alpine`。 */
    val apkBase: String = "",
    /** pip 索引地址，例如 `https://pypi.tuna.tsinghua.edu.cn/simple`。 */
    val pipIndexUrl: String = "",
    /** npm registry 地址，例如 `https://registry.npmmirror.com`。 */
    val npmRegistry: String = "",
) {
    /** 用于判断「配置是否变化」的指纹。 */
    internal val signature: String
        get() = "$apkBase\u0000$pipIndexUrl\u0000$npmRegistry"

    companion object {
        fun current(): LocalSandboxMirrors = runCatching {
            val prefs = ServiceContainer.prefs
            LocalSandboxMirrors(
                apkBase = prefs.sandboxApkMirror,
                pipIndexUrl = prefs.sandboxPipMirror,
                npmRegistry = prefs.sandboxNpmMirror,
            )
        }.getOrDefault(LocalSandboxMirrors())
    }
}

/**
 * 把镜像源写进 rootfs 的真实文件。
 *
 * apk 走官方 `etc/apk/repositories`；pip 走全局 `etc/pip.conf`（对沙箱内所有用户生效，
 * 不依赖 HOME）；npm 走 `root/.npmrc`（沙箱进程 HOME=/root）。
 *
 * 幂等性靠 [MARKER] 标记文件：配置没变就不重复写盘，避免每条命令都触碰 rootfs。
 */
internal object LocalSandboxMirrorFiles {
    const val APK_REPOSITORIES = "etc/apk/repositories"
    const val APK_REPOSITORIES_BACKUP = "etc/apk/repositories.nekobot-orig"
    const val PIP_CONF = "etc/pip.conf"
    const val NPMRC = "root/.npmrc"
    const val ALPINE_RELEASE = "etc/alpine-release"

    private const val MARKER = ".nekobot-mirrors"

    /**
     * 应用镜像源配置。
     *
     * @return 本次实际写入或删除的相对路径列表；配置未变化时为空。
     */
    fun apply(
        sandboxDir: File,
        rootfsDir: File,
        mirrors: LocalSandboxMirrors = LocalSandboxMirrors.current(),
    ): List<String> {
        val marker = File(sandboxDir, MARKER)
        if (runCatching { marker.readText() }.getOrNull() == mirrors.signature) return emptyList()

        val changed = mutableListOf<String>()
        applyApkMirror(rootfsDir, mirrors.apkBase, changed)
        applySingleFileMirror(
            target = File(rootfsDir, PIP_CONF),
            content = buildPipConf(mirrors.pipIndexUrl).takeIf { mirrors.pipIndexUrl.isNotBlank() },
            relativePath = PIP_CONF,
            changed = changed,
        )
        applySingleFileMirror(
            target = File(rootfsDir, NPMRC),
            content = buildNpmrc(mirrors.npmRegistry).takeIf { mirrors.npmRegistry.isNotBlank() },
            relativePath = NPMRC,
            changed = changed,
        )

        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(mirrors.signature)
        }
        return changed
    }

    /** 读取当前 rootfs 内三个镜像文件的内容，供设置界面展示「实际生效值」。 */
    fun readApplied(rootfsDir: File): SandboxMirrorSnapshot {
        fun read(relative: String): String =
            runCatching { File(rootfsDir, relative).readText().trim() }.getOrDefault("")
        return SandboxMirrorSnapshot(
            apkRepositories = read(APK_REPOSITORIES),
            pipConf = read(PIP_CONF),
            npmrc = read(NPMRC),
        )
    }

    /**
     * 清掉「已应用」标记。
     *
     * rootfs 被重新解包（重置或首次安装）后，镜像配置必须重新写入，
     * 否则残留的标记会让新 rootfs 停留在默认源。
     */
    fun resetMarker(sandboxDir: File) {
        runCatching { File(sandboxDir, MARKER).delete() }
    }

    private fun applyApkMirror(rootfsDir: File, rawBase: String, changed: MutableList<String>) {
        val repositories = File(rootfsDir, APK_REPOSITORIES)
        val backup = File(rootfsDir, APK_REPOSITORIES_BACKUP)
        val base = normalizeApkMirrorBase(rawBase)
        if (base.isEmpty()) {
            // 用户清空了镜像源：把首次修改前的原始 repositories 还原回去。
            if (!backup.isFile) return
            runCatching {
                repositories.parentFile?.mkdirs()
                backup.copyTo(repositories, overwrite = true)
                backup.delete()
            }.onSuccess { changed += APK_REPOSITORIES }
            return
        }

        val existing = runCatching { repositories.readText() }.getOrNull()
        // 只在第一次改之前备份，之后的 base 变更都从备份以外的当前内容重写。
        if (!backup.exists() && existing != null) {
            runCatching {
                backup.parentFile?.mkdirs()
                repositories.copyTo(backup, overwrite = true)
            }
        }
        val rewritten = rewriteApkRepositories(
            existing = existing,
            apkBase = base,
            fallbackBranch = detectAlpineBranch(rootfsDir),
        ) ?: return
        runCatching {
            repositories.parentFile?.mkdirs()
            repositories.writeText(rewritten)
        }.onSuccess { changed += APK_REPOSITORIES }
    }

    private fun applySingleFileMirror(
        target: File,
        content: String?,
        relativePath: String,
        changed: MutableList<String>,
    ) {
        if (content == null) {
            if (target.isFile) {
                runCatching { target.delete() }.onSuccess { changed += relativePath }
            }
            return
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.onSuccess { changed += relativePath }
    }
}

/** rootfs 内三个镜像文件的当前内容（未配置时为空串）。 */
internal data class SandboxMirrorSnapshot(
    val apkRepositories: String,
    val pipConf: String,
    val npmrc: String,
)

/**
 * 规范化 apk 镜像基地址。
 *
 * 允许用户粘贴 `mirrors.tuna.tsinghua.edu.cn`、`https://mirrors.aliyun.com/alpine/`
 * 等各种写法，统一成 `https://主机/alpine`；末尾多余的 `/alpine` 不会重复追加。
 */
internal fun normalizeApkMirrorBase(raw: String): String {
    var base = raw.trim().trimEnd('/')
    if (base.isEmpty()) return ""
    if (!base.startsWith("http://", ignoreCase = true) &&
        !base.startsWith("https://", ignoreCase = true)
    ) {
        base = "https://$base"
    }
    if (!base.endsWith("/alpine")) base = "$base/alpine"
    return base
}

/** 从一行 repositories 中取出 `/vX.Y/main` 这类分支后缀；无法识别时返回 null。 */
internal fun apkRepositorySuffix(line: String): String? {
    val match = APK_REPOSITORY_LINE.find(line) ?: return null
    val suffix = match.groupValues[1]
    return suffix.ifBlank { "/main" }
}

private val APK_REPOSITORY_LINE = Regex("""^https?://[^/\s]+/alpine(/[^\s#]*)?$""")

/**
 * 用新的镜像基地址重写 `etc/apk/repositories`。
 *
 * - 保留注释行与非 apk 镜像行（用户自建本地仓库）；
 * - 文件为空或没有可识别行时，按 [fallbackBranch] 生成 main + community 两行；
 * - [apkBase] 为空返回 null，表示不改动。
 */
internal fun rewriteApkRepositories(
    existing: String?,
    apkBase: String,
    fallbackBranch: String = "latest-stable",
): String? {
    val base = normalizeApkMirrorBase(apkBase)
    if (base.isEmpty()) return null

    val lines = existing.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val rewritten = mutableListOf<String>()
    var recognized = false
    lines.forEach { line ->
        if (line.startsWith("#")) {
            rewritten += line
            return@forEach
        }
        val suffix = apkRepositorySuffix(line)
        if (suffix == null) {
            rewritten += line
            return@forEach
        }
        rewritten += base + suffix
        recognized = true
    }
    if (!recognized) {
        rewritten.clear()
        rewritten += "$base/$fallbackBranch/main"
        rewritten += "$base/$fallbackBranch/community"
    }
    return rewritten.joinToString(separator = "\n", postfix = "\n")
}

/** 读取 rootfs 内 Alpine 版本并转成 apk 分支名（3.21.3 → v3.21）。 */
internal fun detectAlpineBranch(rootfsDir: File): String {
    val release = runCatching {
        File(rootfsDir, LocalSandboxMirrorFiles.ALPINE_RELEASE).readText().trim()
    }.getOrDefault("")
    val match = Regex("""^(\d+)\.(\d+)""").find(release) ?: return "latest-stable"
    return "v${match.groupValues[1]}.${match.groupValues[2]}"
}

/** 生成 pip 全局配置；HTTP 镜像额外写入 trusted-host 以跳过证书校验。 */
internal fun buildPipConf(indexUrl: String): String {
    val url = indexUrl.trim()
    if (url.isEmpty()) return ""
    return buildString {
        appendLine("[global]")
        appendLine("index-url = $url")
        pipTrustedHost(url)?.let { appendLine("trusted-host = $it") }
    }
}

/** 从索引地址提取 trusted-host；https 且为公网证书时也写上，便于镜像站用自签证书。 */
internal fun pipTrustedHost(indexUrl: String): String? {
    val match = Regex("""^https?://([^/\s:]+)""").find(indexUrl.trim()) ?: return null
    return match.groupValues[1].ifBlank { null }
}

/** 生成 npm 用户级配置（root/.npmrc）。 */
internal fun buildNpmrc(registry: String): String {
    val url = registry.trim()
    if (url.isEmpty()) return ""
    return "registry=$url\n"
}
