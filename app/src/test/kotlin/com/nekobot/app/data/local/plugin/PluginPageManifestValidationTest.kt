package com.nekobot.app.data.local.plugin

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面清单校验：id 唯一、entry 必须 .html、路径安全、上限与 i18n 约束。 */
class PluginPageManifestValidationTest {

    private val gson = Gson()

    private fun page(
        id: String = "notes",
        title: String = "随手记",
        entry: String = "pages/notes.html",
        icon: String = "",
        styles: List<String> = emptyList(),
        scripts: List<String> = emptyList(),
        i18n: Map<String, String> = emptyMap()
    ) = PluginPageManifest(
        id = id,
        title = title,
        entry = entry,
        icon = icon,
        styles = styles,
        scripts = scripts,
        titleI18n = i18n
    )

    @Test
    fun validPagePasses() {
        assertEquals(emptyList<String>(), PluginManifestValidator.validatePages(listOf(page())))
    }

    @Test
    fun entryMustBeSafeHtmlPath() {
        assertTrue(
            PluginManifestValidator.validatePages(listOf(page(entry = "pages/notes.js")))
                .any { it.contains(".html") }
        )
        assertTrue(
            PluginManifestValidator.validatePages(listOf(page(entry = "../outside.html")))
                .any { it.contains("entry") }
        )
        assertTrue(
            PluginManifestValidator.validatePages(listOf(page(entry = "/abs/notes.html")))
                .any { it.contains("entry") }
        )
    }

    @Test
    fun duplicatePageIdsAreRejected() {
        val errors = PluginManifestValidator.validatePages(listOf(page(id = "notes"), page(id = "notes")))
        assertTrue(errors.any { it.contains("重复的页面 id") })
    }

    @Test
    fun uppercasePageIdIsRejected() {
        val errors = PluginManifestValidator.validatePages(listOf(page(id = "Notes")))
        assertTrue(errors.any { it.contains("id") })
    }

    @Test
    fun tooManyPagesAreRejected() {
        val pages = (1..(PluginManifestValidator.MAX_PAGES + 1)).map {
            page(id = "p$it", entry = "pages/p$it.html")
        }
        val errors = PluginManifestValidator.validatePages(pages)
        assertTrue(errors.any { it.contains("最多") })
    }

    @Test
    fun unsafeAssetPathsAreRejected() {
        val errors = PluginManifestValidator.validatePages(
            listOf(page(styles = listOf("a/../../b.css"), scripts = listOf("C:\\evil.js")))
        )
        assertTrue(errors.any { it.contains("路径不安全") })
    }

    @Test
    fun iconMustBeSafeRelativePath() {
        val errors = PluginManifestValidator.validatePages(listOf(page(icon = "../icon.png")))
        assertTrue(errors.any { it.contains("icon") })
    }

    @Test
    fun unsupportedI18nLanguageIsRejected() {
        val errors = PluginManifestValidator.validatePages(
            listOf(page(i18n = mapOf("fr" to "Notes")))
        )
        assertTrue(errors.any { it.contains("title_i18n") })
    }

    @Test
    fun sanitizeFillsNestedPageDefaults() {
        val raw = """
            {
              "api_version": 2,
              "id": "demo.pages",
              "name": "页面插件",
              "version": "1.0.0",
              "entry": "main.js",
              "commands": [{ "name": "ping" }],
              "pages": [{ "id": "notes", "title": "笔记", "entry": "pages/notes.html" }]
            }
        """.trimIndent()
        val manifest = gson.fromJson(
            PluginManifestValidator.sanitizeManifestJson(raw),
            PluginManifest::class.java
        )
        assertEquals(1, manifest.pages.size)
        val page = manifest.pages[0]
        assertEquals("", page.icon)
        assertEquals(100, page.order)
        assertEquals(emptyList<String>(), page.styles)
        assertEquals(emptyList<String>(), page.scripts)
        assertEquals(emptyMap<String, String>(), page.titleI18n)
        assertNotNull(PluginManifestValidator.validatePages(manifest.pages))
    }

    @Test
    fun commandOpenPageMustReferenceDeclaredPage() {
        val manifest = PluginManifest(
            apiVersion = 2,
            id = "demo.open",
            name = "打开页面",
            version = "1.0.0",
            entry = "main.js",
            commands = listOf(PluginCommandManifest(name = "note", openPage = "notes")),
            pages = listOf(page(id = "notes"))
        )
        assertTrue(PluginManifestValidator.validate(manifest).isEmpty())

        val missingPage = manifest.copy(
            commands = listOf(PluginCommandManifest(name = "note", openPage = "missing"))
        )
        assertTrue(
            PluginManifestValidator.validate(missingPage).any { it.contains("未声明的页面") }
        )

        val badId = manifest.copy(
            commands = listOf(PluginCommandManifest(name = "note", openPage = "Notes"))
        )
        assertTrue(PluginManifestValidator.validate(badId).any { it.contains("open_page") })
    }

    @Test
    fun bindingPropagatesOpenPage() {
        val plugin = InstalledPlugin(
            id = "demo.open",
            name = "打开页面",
            version = "1.0.0",
            author = "",
            description = "",
            entry = "main.js",
            permissions = emptyList(),
            commands = listOf(PluginCommandManifest(name = "note", openPage = "notes")),
            enabled = true,
            installedAt = 0L,
            pages = listOf(page(id = "notes"))
        )
        val binding = pluginCommandBindings(listOf(plugin)).single()
        assertEquals("notes", binding.openPage)
        assertEquals("/note", binding.trigger)
    }

    @Test
    fun sanitizeFillsOpenPageDefault() {
        val raw = """
            {
              "api_version": 2,
              "id": "demo.open2",
              "name": "打开页面2",
              "version": "1.0.0",
              "entry": "main.js",
              "commands": [{ "name": "note" }],
              "pages": [{ "id": "notes", "title": "笔记", "entry": "pages/notes.html" }]
            }
        """.trimIndent()
        val manifest = gson.fromJson(
            PluginManifestValidator.sanitizeManifestJson(raw),
            PluginManifest::class.java
        )
        assertEquals("", manifest.commands[0].openPage)
    }

    @Test
    fun localizedTitleFallsBackToDefault() {
        val page = page(i18n = mapOf("en" to "Notes", "ja" to "メモ"))
        assertEquals("Notes", page.localizedTitle("en-US"))
        assertEquals("メモ", page.localizedTitle("ja"))
        assertEquals("随手记", page.localizedTitle("ko"))
        assertEquals("随手记", page.localizedTitle(null))
    }
}
