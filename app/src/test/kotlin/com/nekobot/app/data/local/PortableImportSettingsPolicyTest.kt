package com.nekobot.app.data.local

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PortableImportSettingsPolicyTest {
    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject
    private val before = json("""{"language":"zh","rag_top_k":5,"custom_font_file":"local-font","custom_chat_background_file":"local-bg","_font_path":"content://synthetic/local-font","_background_path":"content://synthetic/local-bg"}""")

    @Test fun updatingOneSettingPreservesExactUnselectedDeviceUrisDespiteLegacyRestoreSideEffects() {
        var font: String? = "content://synthetic/local-font"
        var background: String? = "content://synthetic/local-bg"
        PortableImportSettingsPolicy.apply(before, json("""{"language":"en","rag_top_k":9,"custom_font_file":"archive-font"}"""), setOf("language"), {
            assertEquals("en", it["language"].asString)
            assertEquals(5, it["rag_top_k"].asInt)
            font = null; background = null // Existing restore drops references it cannot resolve locally.
        }, { font = it }, { background = it })
        assertEquals("content://synthetic/local-font", font)
        assertEquals("content://synthetic/local-bg", background)
    }
    @Test fun explicitlySelectingFontUpdateKeepsRestoredFontAndPreservesOnlyUnselectedBackground() {
        var font: String? = null; var background: String? = null
        PortableImportSettingsPolicy.apply(before, json("""{"custom_font_file":"selected-font"}"""), setOf("custom_font_file"), {
            font = "file:///synthetic/fonts/selected-font"
        }, { font = it }, { background = it })
        assertEquals("file:///synthetic/fonts/selected-font", font)
        assertEquals("content://synthetic/local-bg", background)
    }
    @Test fun explicitlyClearingFontDoesNotDeleteOrChangeUnselectedReferences() {
        var font: String? = "old"; var background: String? = null
        PortableImportSettingsPolicy.apply(before, json("""{"custom_font_file":null}"""), setOf("custom_font_file"), { font = null },
            { font = it }, { background = it })
        assertNull(font)
        assertEquals("content://synthetic/local-bg", background)
    }
    @Test(expected = IllegalArgumentException::class) fun incomingFilesCannotWriteInternalRollbackKeys() {
        PortableImportSettingsPolicy.validate(before, json("""{"_font_path":"foreign"}"""))
    }
    @Test fun selectedAssetNamesAreStrictLeavesAndNullMeansAnExplicitClear() {
        assertEquals("fixture.ttf", PortableImportSettingsPolicy.fileName(json("""{"custom_font_file":"fixture.ttf"}"""), "custom_font_file"))
        assertNull(PortableImportSettingsPolicy.fileName(json("""{"custom_font_file":null}"""), "custom_font_file"))
        listOf("../foreign", "a/b", "a\\b", "C:foreign", ".", "").forEach { value ->
            val input = com.google.gson.JsonObject().apply { addProperty("custom_font_file", value) }
            try { PortableImportSettingsPolicy.fileName(input, "custom_font_file"); fail(value) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test(expected = IllegalArgumentException::class) fun staleFieldChoicesAreRejectedBeforeLegacyRestoreRuns() {
        PortableImportSettingsPolicy.apply(before, json("""{"language":"en"}"""), setOf("rag_top_k"), { fail("Must not restore") }, {}, {})
    }
}
