package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalSandboxMirrorsTest {

    // ==================== apk 基地址规范化 ====================

    @Test
    fun `mirror base accepts bare host and appends alpine`() {
        assertEquals(
            "https://mirrors.tuna.tsinghua.edu.cn/alpine",
            normalizeApkMirrorBase("mirrors.tuna.tsinghua.edu.cn")
        )
        assertEquals(
            "https://mirrors.aliyun.com/alpine",
            normalizeApkMirrorBase("https://mirrors.aliyun.com/alpine/")
        )
        assertEquals(
            "https://mirrors.ustc.edu.cn/alpine",
            normalizeApkMirrorBase("  https://mirrors.ustc.edu.cn/alpine  ")
        )
        assertEquals("", normalizeApkMirrorBase("   "))
    }

    // ==================== apk repositories 重写 ====================

    @Test
    fun `repositories rewrite keeps branch and community suffix`() {
        val original = """
            https://dl-cdn.alpinelinux.org/alpine/v3.21/main
            https://dl-cdn.alpinelinux.org/alpine/v3.21/community
        """.trimIndent()

        val rewritten = rewriteApkRepositories(original, "https://mirrors.tuna.tsinghua.edu.cn/alpine")

        assertEquals(
            """
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/community
            """.trimIndent() + "\n",
            rewritten
        )
    }

    @Test
    fun `repositories rewrite keeps comments and unknown entries`() {
        val original = """
            # comment line
            https://dl-cdn.alpinelinux.org/alpine/v3.20/main
            /media/cdrom/apks
        """.trimIndent()

        val rewritten = rewriteApkRepositories(original, "mirrors.ustc.edu.cn/alpine").orEmpty()

        assertTrue(rewritten.contains("# comment line"))
        assertTrue(rewritten.contains("https://mirrors.ustc.edu.cn/alpine/v3.20/main"))
        assertTrue(rewritten.contains("/media/cdrom/apks"))
    }

    @Test
    fun `repositories rewrite falls back to branch entries when empty`() {
        val rewritten = rewriteApkRepositories("", "https://mirrors.aliyun.com", "v3.19").orEmpty()

        val lines = rewritten.trim().lines()
        assertEquals(2, lines.size)
        assertEquals("https://mirrors.aliyun.com/alpine/v3.19/main", lines[0])
        assertEquals("https://mirrors.aliyun.com/alpine/v3.19/community", lines[1])
    }

    @Test
    fun `repositories rewrite returns null for blank mirror`() {
        assertNull(rewriteApkRepositories("https://dl-cdn.alpinelinux.org/alpine/v3.21/main", " "))
    }

    @Test
    fun `alpine branch derives from release file`() {
        val rootfs = Files.createTempDirectory("nekobot-branch").toFile()
        try {
            val release = File(rootfs, LocalSandboxMirrorFiles.ALPINE_RELEASE)
            release.parentFile?.mkdirs()
            release.writeText("3.21.3\n")
            assertEquals("v3.21", detectAlpineBranch(rootfs))

            release.writeText("edge\n")
            assertEquals("latest-stable", detectAlpineBranch(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    // ==================== pip / npm 配置 ====================

    @Test
    fun `pip conf carries index url and trusted host`() {
        val conf = buildPipConf("https://pypi.tuna.tsinghua.edu.cn/simple")

        assertTrue(conf.contains("[global]"))
        assertTrue(conf.contains("index-url = https://pypi.tuna.tsinghua.edu.cn/simple"))
        assertTrue(conf.contains("trusted-host = pypi.tuna.tsinghua.edu.cn"))
        assertEquals("", buildPipConf("  "))
    }

    @Test
    fun `npmrc carries registry`() {
        assertEquals("registry=https://registry.npmmirror.com\n", buildNpmrc("https://registry.npmmirror.com"))
        assertEquals("", buildNpmrc(""))
    }

    // ==================== 落盘行为 ====================

    @Test
    fun `apply writes files backs up repositories and is idempotent`() {
        val sandbox = Files.createTempDirectory("nekobot-sandbox").toFile()
        try {
            val rootfs = File(sandbox, "alpine-rootfs")
            val repositories = File(rootfs, LocalSandboxMirrorFiles.APK_REPOSITORIES)
            repositories.parentFile?.mkdirs()
            val originalContent = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n"
            repositories.writeText(originalContent)

            val mirrors = LocalSandboxMirrors(
                apkBase = "https://mirrors.tuna.tsinghua.edu.cn/alpine",
                pipIndexUrl = "https://pypi.tuna.tsinghua.edu.cn/simple",
                npmRegistry = "https://registry.npmmirror.com",
            )
            val written = LocalSandboxMirrorFiles.apply(sandbox, rootfs, mirrors)

            assertTrue(written.contains(LocalSandboxMirrorFiles.APK_REPOSITORIES))
            assertTrue(written.contains(LocalSandboxMirrorFiles.PIP_CONF))
            assertTrue(written.contains(LocalSandboxMirrorFiles.NPMRC))
            assertTrue(
                repositories.readText().startsWith("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/main")
            )
            assertTrue(File(rootfs, LocalSandboxMirrorFiles.APK_REPOSITORIES_BACKUP).readText() == originalContent)
            assertTrue(File(rootfs, LocalSandboxMirrorFiles.PIP_CONF).readText().contains("index-url"))
            assertTrue(File(rootfs, LocalSandboxMirrorFiles.NPMRC).readText().contains("registry="))

            // 同一份配置重复应用不应再次写盘
            assertTrue(LocalSandboxMirrorFiles.apply(sandbox, rootfs, mirrors).isEmpty())
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun `clearing mirrors restores apk repositories and removes pip npm files`() {
        val sandbox = Files.createTempDirectory("nekobot-sandbox-clear").toFile()
        try {
            val rootfs = File(sandbox, "alpine-rootfs")
            val repositories = File(rootfs, LocalSandboxMirrorFiles.APK_REPOSITORIES)
            repositories.parentFile?.mkdirs()
            val originalContent = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n"
            repositories.writeText(originalContent)

            LocalSandboxMirrorFiles.apply(
                sandbox,
                rootfs,
                LocalSandboxMirrors(
                    apkBase = "https://mirrors.tuna.tsinghua.edu.cn/alpine",
                    pipIndexUrl = "https://pypi.tuna.tsinghua.edu.cn/simple",
                    npmRegistry = "https://registry.npmmirror.com",
                ),
            )
            val cleared = LocalSandboxMirrorFiles.apply(sandbox, rootfs, LocalSandboxMirrors())

            assertTrue(cleared.contains(LocalSandboxMirrorFiles.APK_REPOSITORIES))
            assertTrue(cleared.contains(LocalSandboxMirrorFiles.PIP_CONF))
            assertTrue(cleared.contains(LocalSandboxMirrorFiles.NPMRC))
            assertEquals(originalContent, repositories.readText())
            assertFalse(File(rootfs, LocalSandboxMirrorFiles.APK_REPOSITORIES_BACKUP).exists())
            assertFalse(File(rootfs, LocalSandboxMirrorFiles.PIP_CONF).exists())
            assertFalse(File(rootfs, LocalSandboxMirrorFiles.NPMRC).exists())
        } finally {
            sandbox.deleteRecursively()
        }
    }

    // ==================== 浏览器配置 ====================

    @Test
    fun `browser config resolves user agent per mode`() {
        assertEquals(
            LocalBrowserConfig.MOBILE_USER_AGENT,
            LocalBrowserConfig(userAgentMode = BrowserUserAgentMode.MOBILE).userAgent
        )
        assertEquals(
            LocalBrowserConfig.DESKTOP_USER_AGENT,
            LocalBrowserConfig(userAgentMode = BrowserUserAgentMode.DESKTOP).userAgent
        )
        // 自定义留空时回落移动端身份，避免发出空 User-Agent
        assertEquals(
            LocalBrowserConfig.MOBILE_USER_AGENT,
            LocalBrowserConfig(
                userAgentMode = BrowserUserAgentMode.CUSTOM,
                customUserAgent = "   "
            ).userAgent
        )
        assertEquals(
            "Custom/1.0",
            LocalBrowserConfig(
                userAgentMode = BrowserUserAgentMode.CUSTOM,
                customUserAgent = "  Custom/1.0  "
            ).userAgent
        )
    }

    @Test
    fun `browser user agent mode falls back to mobile`() {
        assertEquals(BrowserUserAgentMode.MOBILE, BrowserUserAgentMode.fromStorage(null))
        assertEquals(BrowserUserAgentMode.MOBILE, BrowserUserAgentMode.fromStorage("bogus"))
        assertEquals(BrowserUserAgentMode.DESKTOP, BrowserUserAgentMode.fromStorage("DESKTOP"))
    }
}
