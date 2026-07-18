package com.nekobot.app.data.local

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
