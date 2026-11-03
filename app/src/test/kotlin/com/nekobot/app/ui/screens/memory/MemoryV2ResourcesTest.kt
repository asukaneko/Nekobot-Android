package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.graphics.luminance
import com.nekobot.app.ui.theme.DarkColors
import com.nekobot.app.ui.theme.LightColors
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class MemoryV2ResourcesTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val locales = listOf("values", "values-zh-rTW", "values-en", "values-ja", "values-ko")
    private fun strings(locale: String, onlyMemory: Boolean = true): Map<String, String> = buildMap {
        File(root, locale).listFiles()!!.filter { it.extension == "xml" && (!onlyMemory || it.name == "memory_v2.xml") }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val key = node.attributes.getNamedItem("name").nodeValue
                check(!containsKey(key)) { "重复资源: $locale/$key" }
                put(key, node.textContent)
            }
        }
    }
    @Test fun allFiveLanguagesContainTheCompleteNonEmptyMemoryUiResources() {
        val baseline = strings("values")
        assertEquals(39, baseline.size)
        for (locale in locales) {
            val entries = strings(locale)
            assertEquals(locale, baseline.keys, entries.keys)
            assertTrue(locale, entries.values.all { it.isNotBlank() })
        }
    }
    @Test fun formatArgumentsMatchAcrossEveryLanguage() {
        val arguments = Regex("%[0-9]+\\\$[dsf]")
        val baseline = strings("values")
        for (locale in locales) for ((key, text) in strings(locale)) {
            assertEquals("$locale/$key", arguments.findAll(baseline.getValue(key)).map { it.value }.toList(),
                arguments.findAll(text).map { it.value }.toList())
        }
    }
    @Test fun dateLabelsExistInEveryLanguageWithoutFallbackToChinese() {
        for (locale in locales) for (key in listOf("chat_today", "chat_yesterday", "chat_month_day", "chat_year_month_day")) {
            assertFalse("$locale/$key", strings(locale, false)[key].isNullOrBlank())
        }
    }
    @Test fun bugFixMessagesAndCropActionsHaveFiveTranslationsWithMatchingArguments() {
        val keys = strings("values", false).keys.filter {
            it.startsWith("avatar_crop_") || it.startsWith("webdav_test_") || it.startsWith("webdav_folder_") || it == "ai_stream_incomplete"
        }
        assertEquals(19, keys.size) // Includes the existing webdav_test_connection label.
        val args = Regex("%[0-9]+\\\$[dsf]")
        val baseline = strings("values", false)
        for (locale in locales) {
            val translated = strings(locale, false)
            for (key in keys) {
                assertFalse("$locale/$key", translated[key].isNullOrBlank())
                assertEquals("$locale/$key", args.findAll(baseline.getValue(key)).map { it.value }.toList(),
                    args.findAll(translated.getValue(key)).map { it.value }.toList())
            }
        }
    }
    @Test fun lightAndDarkMemorySurfacesPreserveAppPaletteAndReadableBodyText() {
        for (base in listOf(LightColors, DarkColors)) {
            val colors = companionMemoryColorScheme(base)
            assertEquals(base.primary, colors.primary)
            assertEquals(base.surface, colors.surface)
            val ratio = (maxOf(colors.surface.luminance(), colors.onSurface.luminance()) + 0.05f) /
                (minOf(colors.surface.luminance(), colors.onSurface.luminance()) + 0.05f)
            assertTrue(ratio > 4.5f)
        }
        val dark = companionMemoryColorScheme(DarkColors)
        assertTrue(dark.onPrimary.luminance() < dark.primary.luminance())
    }
}
