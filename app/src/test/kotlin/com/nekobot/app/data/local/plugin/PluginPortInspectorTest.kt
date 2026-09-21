package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 生态识别、安全解压、静态自检与 API 名称扫描。 */
class PluginPortInspectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun detectSillyTavernExtension() {
        val manifest = """{"display_name":"My Extension","js":"index.js","css":"style.css"}"""
        assertEquals(
            PluginPortInspector.Ecosystem.SILLY_TAVERN,
            PluginPortInspector.detectEcosystem(manifest, null)
        )
    }

    @Test
    fun detectOperitToolPkg() {
        val manifest = """{"schema_version":1,"toolpkg_id":"demo","main":"main.js"}"""
        assertEquals(
            PluginPortInspector.Ecosystem.OPERIT_TOOLPKG,
            PluginPortInspector.detectEcosystem(manifest, null)
        )
    }

    @Test
    fun detectNekobotNative() {
        val manifest = """{"api_version":2,"id":"demo","commands":[]}"""
        assertEquals(
            PluginPortInspector.Ecosystem.NEKOBOT,
            PluginPortInspector.detectEcosystem(manifest, null)
        )
    }

    @Test
    fun detectDeepSeekHarness() {
        val packageJson = """{"name":"x","dependencies":{"cordis":"^1.0.0"}}"""
        assertEquals(
            PluginPortInspector.Ecosystem.DEEPSEEK_HARNESS,
            PluginPortInspector.detectEcosystem(packageJson, packageJson)
        )
    }

    @Test
    fun detectUnknown() {
        assertEquals(
            PluginPortInspector.Ecosystem.UNKNOWN,
            PluginPortInspector.detectEcosystem("""{"foo":1}""", null)
        )
    }

    @Test
    fun inspectZipExtractsAndReportsFiles() {
        val zip = temp.newFile("st-extension.zip")
        ZipOutputStream(zip.outputStream()).use { stream ->
            stream.putNextEntry(ZipEntry("manifest.json"))
            stream.write("""{"display_name":"Demo","js":"index.js"}""".toByteArray())
            stream.closeEntry()
            stream.putNextEntry(ZipEntry("index.js"))
            stream.write(
                """
                const ctx = getContext();
                toastr.success("hi");
                const data = await fetch("https://example.com");
                """.trimIndent().toByteArray()
            )
            stream.closeEntry()
        }
        val output = File(temp.root, "out")
        val inspection = PluginPortInspector.inspect(zip, output)

        assertEquals(PluginPortInspector.Ecosystem.SILLY_TAVERN, inspection.ecosystem)
        assertEquals("manifest.json", inspection.manifestFile)
        assertEquals(2, inspection.files.size)
        assertTrue(inspection.suggestedPermissions.contains("chat.read"))
        assertTrue(inspection.suggestedPermissions.contains("notify"))
        assertTrue(inspection.suggestedPermissions.contains("network"))
        assertTrue(File(output, "index.js").isFile)
    }

    @Test
    fun inspectRejectsUnsafeZipPaths() {
        val zip = temp.newFile("evil.zip")
        ZipOutputStream(zip.outputStream()).use { stream ->
            stream.putNextEntry(ZipEntry("../escape.js"))
            stream.write("bad".toByteArray())
            stream.closeEntry()
        }
        val error = runCatching {
            PluginPortInspector.inspect(zip, File(temp.root, "evil-out"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(File(temp.root.parentFile, "escape.js").exists())
    }

    @Test
    fun checkReportsMissingPageEntry() {
        val dir = temp.newFolder("plugin")
        File(dir, "main.js").writeText("NekoPlugin.registerCommand('ping', async () => 'pong');")
        val manifest = """
            {
              "api_version": 2,
              "id": "demo.check",
              "name": "自检",
              "version": "1.0.0",
              "entry": "main.js",
              "commands": [{"name": "ping"}],
              "pages": [{"id": "notes", "title": "笔记", "entry": "pages/notes.html"}]
            }
        """.trimIndent()
        val result = PluginPortInspector.check(dir, manifest)
        assertFalse(result.ok)
        assertTrue(result.pageErrors.any { it.contains("notes.html") })
    }

    @Test
    fun checkReportsUnsupportedApiCalls() {
        val dir = temp.newFolder("plugin2")
        File(dir, "main.js").writeText(
            """
            const a = await ctx.api.getSession();
            const b = await ctx.api.magicFeature();
            const c = await host.files.read("notes.md");
            """.trimIndent()
        )
        val manifest = """
            {
              "api_version": 2,
              "id": "demo.check2",
              "name": "自检2",
              "version": "1.0.0",
              "entry": "main.js",
              "permissions": ["chat.read"],
              "commands": [{"name": "ping"}]
            }
        """.trimIndent()
        val result = PluginPortInspector.check(dir, manifest)
        assertFalse(result.ok)
        assertTrue(result.unsupportedApis.any { it.contains("magicFeature") })
        assertTrue(result.unsupportedApis.any { it.contains("host.files.read") })
        assertTrue(result.unsupportedApis.none { it.contains("getSession") })
    }

    @Test
    fun writeApisAreRecognized() {
        val supported = PluginPortInspector.unsupportedApiCalls(
            """
            await ctx.api.appendMessage({content: "hi"});
            await ctx.api.sendMessage({content: "hi"});
            await ctx.api.createSession({name: "n"});
            await ctx.api.switchSession("id");
            await ctx.api.render("{{a}}", {});
            await ctx.api.createCharacter({name: "n"});
            await ctx.api.updateCharacter({id: "i", name: "n"});
            await ctx.api.memoryWrite("x");
            await ctx.api.memoryAppend("y");
            await ctx.api.memoryEdit("a", "b");
            await ctx.api.memoryRead();
            await host.chat.messages.append({content: "hi"});
            await host.chat.send({content: "hi"});
            await host.chat.sessions.create({name: "n"});
            await host.chat.sessions.switch("id");
            await host.memory.write("x");
            await host.memory.append("y");
            await host.memory.edit("a", "b");
            await host.ui.render("{{a}}", {});
            await host.characters.create({name: "n"});
            await host.characters.update({id: "i", name: "n"});
            """.trimIndent()
        )
        assertTrue(supported.isEmpty())
    }

    @Test
    fun checkPassesForHealthyPlugin() {
        val dir = temp.newFolder("plugin3")
        File(dir, "main.js").writeText("NekoPlugin.registerCommand('ping', async () => 'pong');")
        File(dir, "pages").mkdirs()
        File(dir, "pages/notes.html").writeText("<!DOCTYPE html><html><head></head><body>ok</body></html>")
        val manifest = """
            {
              "api_version": 2,
              "id": "demo.check3",
              "name": "自检3",
              "version": "1.0.0",
              "entry": "main.js",
              "commands": [{"name": "ping"}],
              "pages": [{"id": "notes", "title": "笔记", "entry": "pages/notes.html"}]
            }
        """.trimIndent()
        val result = PluginPortInspector.check(dir, manifest)
        assertTrue(result.ok)
    }
}
