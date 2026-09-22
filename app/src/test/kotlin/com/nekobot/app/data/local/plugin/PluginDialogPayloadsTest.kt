package com.nekobot.app.data.local.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 原生弹窗参数解析：options 形态、边界与默认选中项。 */
class PluginDialogPayloadsTest {

    private fun payload(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun stringOptionsBecomeLabelAndValue() {
        val options = PluginDialogPayloads.parseOptions(payload("""{"options":["甲","乙"]}"""))
        assertEquals(2, options.size)
        assertEquals("甲", options[0].label)
        assertEquals("甲", options[0].value)
        assertEquals("乙", options[1].value)
    }

    @Test
    fun objectOptionsKeepLabelAndValue() {
        val options = PluginDialogPayloads.parseOptions(
            payload("""{"options":[{"label":"简洁","value":"simple"},{"value":"rich"}]}""")
        )
        assertEquals("简洁", options[0].label)
        assertEquals("simple", options[0].value)
        assertEquals("rich", options[1].label)
        assertEquals("rich", options[1].value)
    }

    @Test
    fun invalidOptionsAreRejected() {
        assertThrows(PluginApiException::class.java) {
            PluginDialogPayloads.parseOptions(payload("""{"options":[]}"""))
        }
        assertThrows(PluginApiException::class.java) {
            PluginDialogPayloads.parseOptions(payload("""{"options":"abc"}"""))
        }
        assertThrows(PluginApiException::class.java) {
            PluginDialogPayloads.parseOptions(payload("""{"options":[{"label":"  "}]}"""))
        }
        val tooMany = (1..PluginDialogPayloads.MAX_OPTIONS + 1)
            .joinToString(",", prefix = "[", postfix = "]") { "\"o$it\"" }
        assertThrows(PluginApiException::class.java) {
            PluginDialogPayloads.parseOptions(payload("""{"options":$tooMany}"""))
        }
    }

    @Test
    fun selectedIndexAcceptsIndexOrValue() {
        val options = PluginDialogPayloads.parseOptions(
            payload("""{"options":[{"label":"甲","value":"a"},{"label":"乙","value":"b"}]}""")
        )
        assertEquals(1, PluginDialogPayloads.resolveSelectedIndex(payload("""{"selected":1}"""), options))
        assertEquals(1, PluginDialogPayloads.resolveSelectedIndex(payload("""{"selected":"b"}"""), options))
        assertEquals(0, PluginDialogPayloads.resolveSelectedIndex(payload("""{"selected":"甲"}"""), options))
        assertEquals(-1, PluginDialogPayloads.resolveSelectedIndex(payload("""{"selected":9}"""), options))
        assertEquals(-1, PluginDialogPayloads.resolveSelectedIndex(payload("""{"selected":"missing"}"""), options))
        assertEquals(-1, PluginDialogPayloads.resolveSelectedIndex(payload("""{}"""), options))
    }
}
