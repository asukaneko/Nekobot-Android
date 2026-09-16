package com.nekobot.app.data.local

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSkillStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `downloads multiple skills from repository zip`() {
        val zip = zipOf(
            "repo-main/skills/alpha/SKILL.md" to """
                ---
                name: alpha
                description: Alpha skill
                aliases: [a, first]
                ---
                # Alpha
            """.trimIndent().toByteArray(),
            "repo-main/skills/alpha/reference.md" to "Alpha reference".toByteArray(),
            "repo-main/skills/beta/SKILL.md" to "# beta\n\nBeta skill".toByteArray(),
            "repo-main/skills/beta/resources/example.txt" to "example".toByteArray()
        )
        val downloader = SkillPackageDownloader(clientReturning(zip, "application/zip"))

        val packages = downloader.download("https://example.com/skills.zip")

        assertEquals(listOf("alpha", "beta"), packages.map { it.name })
        assertEquals(listOf("a", "first"), packages.first().aliases)
        assertEquals("Alpha reference", packages.first().referenceMd)
        assertTrue(packages.last().files.containsKey("resources/example.txt"))
    }

    @Test
    fun `skills sh URL selects requested skill`() {
        val zip = zipOf(
            "repo-main/skills/alpha/SKILL.md" to "# alpha".toByteArray(),
            "repo-main/skills/beta/SKILL.md" to "# beta".toByteArray()
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "api.github.com") {
                """{"default_branch":"main"}""".toByteArray()
            } else {
                zip
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(
                    if (request.url.host == "api.github.com") {
                        "application/json".toMediaType()
                    } else {
                        "application/zip".toMediaType()
                    }
                ))
                .build()
        }.build()

        val packages = SkillPackageDownloader(client)
            .download("https://skills.sh/vercel-labs/agent-skills/beta")

        assertEquals(1, packages.size)
        assertEquals("beta", packages.single().name)
    }

    @Test
    fun `rejects zip path traversal`() {
        val zip = zipOf(
            "../SKILL.md" to "# unsafe".toByteArray()
        )
        val downloader = SkillPackageDownloader(clientReturning(zip, "application/zip"))

        val result = runCatching { downloader.download("https://example.com/unsafe.zip") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不安全路径"))
    }

    @Test
    fun `rejects unsafe skill name from markdown`() {
        val markdown = """
            ---
            name: ../unsafe
            description: unsafe
            ---
            # Unsafe
        """.trimIndent().toByteArray()
        val downloader = SkillPackageDownloader(clientReturning(markdown, "text/markdown"))

        val result = runCatching { downloader.download("https://example.com/SKILL.md") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("路径分隔符"))
    }

    @Test
    fun `storage preserves standard files and blocks escaped reads`() {
        val root = temporaryFolder.newFolder("skills")
        val storage = LocalSkillStorage(root)
        val pkg = DownloadedSkillPackage(
            name = "demo",
            description = "Demo",
            aliases = emptyList(),
            skillMd = "# demo",
            referenceMd = "reference",
            sourceUrl = "https://example.com/demo.zip",
            files = mapOf(
                "SKILL.md" to "# demo".toByteArray(),
                "reference.md" to "reference".toByteArray(),
                "scripts/main.py" to "print('demo')".toByteArray()
            )
        )

        storage.install(pkg, overwrite = false)

        assertEquals("# demo", storage.skillMd("demo"))
        assertEquals("reference", storage.referenceMd("demo"))
        assertEquals("https://example.com/demo.zip", storage.sourceUrl("demo"))
        assertTrue(storage.listFiles("demo").any { it.path == "scripts/main.py" && it.type == "script" })
        assertTrue(runCatching { storage.readText("demo", "../outside.txt") }.isFailure)

        storage.rename("demo", "renamed")
        assertFalse(storage.exists("demo"))
        assertTrue(storage.exists("renamed"))
    }

    @Test
    fun `install preserves arbitrary sibling files and folders next to SKILL md`() {
        val root = temporaryFolder.newFolder("skills-siblings")
        val storage = LocalSkillStorage(root)
        val pkg = DownloadedSkillPackage(
            name = "sibling-demo",
            description = "Demo",
            aliases = emptyList(),
            skillMd = "# sibling-demo",
            referenceMd = "reference",
            sourceUrl = "https://example.com/sibling.zip",
            files = mapOf(
                "SKILL.md" to "# sibling-demo".toByteArray(),
                // 非固定目录的中文说明 / 自定义子目录。
                "说明.md" to "同级的自定义 Markdown".toByteArray(),
                "configs/app.toml" to "enabled = true".toByteArray(),
                "assets/logo.txt" to "logo".toByteArray(),
                "scripts/tool.py" to "print('ok')".toByteArray()
            )
        )

        storage.install(pkg, overwrite = false)

        assertTrue(storage.readText("sibling-demo", "说明.md").contains("自定义 Markdown"))
        assertTrue(storage.readText("sibling-demo", "configs/app.toml").contains("enabled"))
        assertTrue(storage.readText("sibling-demo", "assets/logo.txt").contains("logo"))
        assertTrue(storage.readText("sibling-demo", "scripts/tool.py").contains("ok"))
        val paths = storage.listFiles("sibling-demo").map { it.path }.toSet()
        assertTrue(paths.containsAll(listOf("说明.md", "configs/app.toml", "assets/logo.txt", "scripts/tool.py")))
    }

    @Test
    fun `save keeps untouched sibling files`() {
        val root = temporaryFolder.newFolder("skills-preserve")
        val storage = LocalSkillStorage(root)
        val pkg = DownloadedSkillPackage(
            name = "preserve",
            description = "Demo",
            aliases = emptyList(),
            skillMd = "# preserve",
            referenceMd = null,
            sourceUrl = "https://example.com/preserve.zip",
            files = mapOf(
                "SKILL.md" to "# preserve".toByteArray(),
                "SIBLING.txt" to "keep me".toByteArray()
            )
        )
        storage.install(pkg, overwrite = false)

        // 更新 SKILL.md / reference.md 时，不应吞掉 SKILL.md 同级已有文件。
        storage.save("preserve", skillMd = "# preserve v2", referenceMd = "ref", sourceUrl = "https://example.com/preserve.zip")

        assertTrue(storage.readText("preserve", "SIBLING.txt").contains("keep me"))
        assertEquals("# preserve v2", storage.skillMd("preserve"))
    }

    // ==================== 导出 ZIP ====================

    @Test
    fun `export package keeps every file and can be reinstalled`() {
        val root = temporaryFolder.newFolder("skills-export")
        val storage = LocalSkillStorage(root)
        storage.install(
            DownloadedSkillPackage(
                name = "export-demo",
                description = "Demo",
                aliases = emptyList(),
                skillMd = "# export-demo",
                referenceMd = "reference",
                sourceUrl = "https://example.com/export.zip",
                files = mapOf(
                    "SKILL.md" to "# export-demo".toByteArray(),
                    "reference.md" to "reference".toByteArray(),
                    "说明.md" to "同级说明".toByteArray(),
                    "scripts/tool.py" to "print('ok')".toByteArray(),
                    "assets/logo.bin" to byteArrayOf(0, 1, 2, 3, 4)
                )
            ),
            overwrite = false
        )

        val exported = SkillZipExporter.build("export-demo", storage.readAllFiles("export-demo"))

        // 导出的包结构与 GitHub 下载的 Skill 包一致，可直接被解析器还原。
        val packages = SkillPackageDownloader(clientReturning(exported, "application/zip"))
            .download("https://example.com/export-demo.zip")
        val restored = packages.single()
        assertEquals("export-demo", restored.name)
        assertTrue(restored.files.containsKey("说明.md"))
        assertTrue(restored.files.containsKey("scripts/tool.py"))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), restored.files["assets/logo.bin"])
    }

    @Test
    fun `export excludes local source metadata and names file after skill`() {
        val root = temporaryFolder.newFolder("skills-export-meta")
        val storage = LocalSkillStorage(root)
        storage.save("meta-demo", skillMd = "# meta-demo", referenceMd = null, sourceUrl = "https://example.com/x.zip")

        val files = storage.readAllFiles("meta-demo")

        assertTrue(files.containsKey("SKILL.md"))
        assertFalse("config.json 只是本机安装来源元数据", files.containsKey("config.json"))
        assertEquals("meta-demo.zip", SkillZipExporter.zipFileName("meta-demo"))
    }

    @Test
    fun `export drops paths escaping the skill root`() {
        val zip = SkillZipExporter.build(
            "safe-demo",
            mapOf(
                "SKILL.md" to "# safe-demo".toByteArray(),
                "../outside.txt" to "nope".toByteArray(),
                "scripts/ok.py" to "print('ok')".toByteArray()
            )
        )

        val entries = mutableListOf<String>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries += entry.name
            }
        }
        assertEquals(listOf("safe-demo/SKILL.md", "safe-demo/scripts/ok.py"), entries)
    }

    @Test
    fun `export rejects empty skill directory`() {
        val result = runCatching { SkillZipExporter.build("empty-demo", emptyMap()) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("为空"))
    }

    private fun clientReturning(bytes: ByteArray, contentType: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody(contentType.toMediaType()))
                .build()
        }.build()

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
