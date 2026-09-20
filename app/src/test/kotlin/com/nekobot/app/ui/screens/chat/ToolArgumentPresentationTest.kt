package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentPresentationTest {

    @Test
    fun flattenedPreviewBecomesReadableRows() {
        val rows = toolArgumentRows(mapOf("preview" to "{path=/sdcard/a.txt, limit=10}"))

        assertEquals(2, rows.size)
        assertEquals(ToolArgumentRow("path", "/sdcard/a.txt"), rows[0])
        assertEquals(ToolArgumentRow("limit", "10"), rows[1])
    }

    @Test
    fun commaInsideValueIsKeptWhenItIsNotANewParameter() {
        val rows = toolArgumentRows(
            mapOf("preview" to "{content=hello, world, path=/a.txt}")
        )

        assertEquals(2, rows.size)
        assertEquals("hello, world", rows[0].value)
        assertEquals(ToolArgumentRow("path", "/a.txt"), rows[1])
    }

    @Test
    fun nestedValuesStayIntact() {
        val rows = toolArgumentRows(
            mapOf("preview" to "{filter={status=done, size=3}, tags=[a, b], dry_run=true}")
        )

        assertEquals(3, rows.size)
        assertEquals("{status=done, size=3}", rows[0].value)
        assertEquals("[a, b]", rows[1].value)
        assertEquals("true", rows[2].value)
    }

    @Test
    fun jsonPreviewIsParsedFieldByField() {
        val rows = toolArgumentRows(
            mapOf("preview" to """{"path": "a.txt", "start_line": 1, "ok": true}""")
        )

        assertEquals(3, rows.size)
        assertEquals(ToolArgumentRow("path", "a.txt"), rows[0])
        assertEquals(ToolArgumentRow("start_line", "1"), rows[1])
        assertEquals(ToolArgumentRow("ok", "true"), rows[2])
    }

    @Test
    fun realArgumentsFromServerAreListed() {
        val rows = toolArgumentRows(
            mapOf("command" to "ls -la", "timeout" to 30, "nested" to mapOf("a" to 1))
        )

        assertEquals(3, rows.size)
        assertEquals(ToolArgumentRow("command", "ls -la"), rows[0])
        assertEquals(ToolArgumentRow("timeout", "30"), rows[1])
        assertEquals("nested", rows[2].name)
        assertTrue(rows[2].value.contains("\"a\""))
    }

    @Test
    fun plainTextPreviewFallsBackToSingleValueRow() {
        val rows = toolArgumentRows(mapOf("preview" to "adb shell input keyevent 3"))

        assertEquals(1, rows.size)
        assertEquals("", rows[0].name)
        assertEquals("adb shell input keyevent 3", rows[0].value)
    }

    @Test
    fun emptyPayloadYieldsNoRowsSoCallerCanFallBackToJson() {
        assertTrue(toolArgumentRows(null).isEmpty())
        assertTrue(toolArgumentRows(emptyMap()).isEmpty())
        assertTrue(toolArgumentRows(mapOf("preview" to "")).isEmpty())
        assertTrue(toolArgumentRows(mapOf("preview" to "{}")).isEmpty())
    }

    @Test
    fun shortValuesFitInlineWhileLongOrMultilineValuesStack() {
        assertTrue(ToolArgumentRow("path", "/a.txt").fitsInline())
        assertTrue(!ToolArgumentRow("content", "x".repeat(200)).fitsInline())
        assertTrue(!ToolArgumentRow("content", "line1\nline2").fitsInline())
        // 没有参数名的裸文本只能堆叠展示
        assertTrue(!ToolArgumentRow("", "adb shell").fitsInline())
    }
}
