package com.nekobot.app.data.local.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端仿真器的纯 JVM 单测：覆盖沙箱终端依赖的 VT 行为
 * （光标、擦除、滚屏与回滚、备用屏幕、SGR 颜色、宽字符、选区文本）。
 */
class TerminalEmulatorTest {

    private fun TerminalEmulator.feedText(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun TerminalEmulator.rowText(row: Int): String = buildString {
        visibleLines()[row].forEach { cell ->
            if (!cell.isWideTrailer) appendCodePoint(cell.codePoint)
        }
    }.trimEnd()

    @Test
    fun `plain text lands on the grid and advances the cursor`() {
        val emulator = TerminalEmulator(cols = 20, rows = 4)
        emulator.feedText("hello")

        assertEquals("hello", emulator.rowText(0))
        assertEquals(5 to 0, emulator.cursorPosition())
    }

    @Test
    fun `carriage return and line feed move to the next line`() {
        val emulator = TerminalEmulator(cols = 20, rows = 4)
        emulator.feedText("first\r\nsecond")

        assertEquals("first", emulator.rowText(0))
        assertEquals("second", emulator.rowText(1))
    }

    @Test
    fun `sgr colors and bold are recorded on cells`() {
        val emulator = TerminalEmulator(cols = 20, rows = 4)
        emulator.feedText("\u001B[31mred\u001B[0m plain")

        val redCell = emulator.visibleLines()[0][0]
        assertEquals(TerminalColor.Indexed(1), redCell.foreground)

        val plainCell = emulator.visibleLines()[0][4]
        assertEquals(TerminalColor.Default, plainCell.foreground)
    }

    @Test
    fun `truecolor sgr is parsed`() {
        val emulator = TerminalEmulator(cols = 20, rows = 4)
        emulator.feedText("\u001B[38;2;10;20;30mX")

        assertEquals(
            TerminalColor.Rgb(10, 20, 30),
            emulator.visibleLines()[0][0].foreground,
        )
    }

    @Test
    fun `erase in line clears only the requested range`() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.feedText("abcdefg")
        emulator.feedText("\u001B[2D")   // 光标回到第 6 列
        emulator.feedText("\u001B[K")    // 擦到行尾

        assertEquals("abcde", emulator.rowText(0))
    }

    @Test
    fun `cursor positioning follows csi H`() {
        val emulator = TerminalEmulator(cols = 10, rows = 5)
        emulator.feedText("\u001B[3;4HX")

        assertEquals("   X", emulator.rowText(2))
        assertEquals(4 to 2, emulator.cursorPosition())
    }

    @Test
    fun `line feed at the bottom scrolls and fills scrollback`() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        emulator.feedText("one\r\ntwo\r\nthree")

        assertEquals("two", emulator.rowText(0))
        assertEquals("three", emulator.rowText(1))
        assertEquals(1, emulator.activeBuffer.scrollback.size)

        emulator.scrollOffset = 1
        assertEquals("one", emulator.visibleLines()[0].let { row ->
            buildString { row.forEach { appendCodePoint(it.codePoint) } }.trimEnd()
        })
    }

    @Test
    fun `alternate screen keeps the primary buffer intact`() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.feedText("primary")
        emulator.feedText("\u001B[?1049h")
        assertTrue(emulator.isAlternateActive)

        emulator.feedText("\u001B[2Jsecondary")
        assertFalse(emulator.rowText(0).startsWith("primary"))

        emulator.feedText("\u001B[?1049l")
        assertFalse(emulator.isAlternateActive)
        assertEquals("primary", emulator.rowText(0))
    }

    @Test
    fun `wide characters claim two cells with a trailer`() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        emulator.feedText("中")

        val line = emulator.visibleLines()[0]
        assertEquals(2, line[0].width)
        assertTrue(line[1].isWideTrailer)
        assertEquals(2 to 0, emulator.cursorPosition())
    }

    @Test
    fun `resize keeps existing content and clamps the cursor`() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.feedText("keep")
        emulator.resize(20, 6)

        assertEquals("keep", emulator.rowText(0))
        assertEquals(20, emulator.visibleLines()[0].size)
        assertEquals(6, emulator.visibleLines().size)
    }

    @Test
    fun `selected text is extracted in reading order and trimmed`() {
        val emulator = TerminalEmulator(cols = 12, rows = 3)
        emulator.feedText("hello world\r\nsecond")

        emulator.setSelection(0, 0, 4, 0)
        assertEquals("hello", emulator.selectedText())

        // 反向选择（从第二行往前拖）按阅读顺序归一化，跨行时首行整行都算被选中
        emulator.setSelection(4, 1, 0, 0)
        assertEquals("hello world\nsecon", emulator.selectedText())
    }

    @Test
    fun `osc sets the window title`() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        emulator.feedText("\u001B]0;nekobot\u0007")

        assertEquals("nekobot", emulator.title)
    }

    @Test
    fun `device status report answers with the cursor position`() {
        val emulator = TerminalEmulator(cols = 10, rows = 4)
        var response: String? = null
        emulator.onResponse = { response = String(it, Charsets.UTF_8) }

        emulator.feedText("ab\u001B[6n")
        assertEquals("\u001B[1;3R", response)
    }

    @Test
    fun `dec private modes toggle wrap and cursor visibility`() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        assertTrue(emulator.autoWrap)
        assertTrue(emulator.cursorVisible)

        emulator.feedText("\u001B[?7l\u001B[?25l")
        assertFalse(emulator.autoWrap)
        assertFalse(emulator.cursorVisible)

        emulator.feedText("\u001B[?7h\u001B[?25h")
        assertTrue(emulator.autoWrap)
        assertTrue(emulator.cursorVisible)
    }

    @Test
    fun `application cursor keys mode follows decckm`() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        assertFalse(emulator.applicationCursorKeys)

        emulator.feedText("\u001B[?1h")
        assertTrue(emulator.applicationCursorKeys)

        emulator.feedText("\u001B[?1l")
        assertFalse(emulator.applicationCursorKeys)
    }

    @Test
    fun `utf8 multi byte input is decoded into single code points`() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        emulator.feedText("→é")

        val line = emulator.visibleLines()[0]
        assertEquals('→'.code, line[0].codePoint)
        assertEquals('é'.code, line[1].codePoint)
    }

    @Test
    fun `malformed escape sequences do not break the parser`() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        // 非法 CSI 参数 + 未闭合的 OSC（由 BEL 结束）之后，普通文本仍要正常写入
        emulator.feedText("\u001B[\u0000\u001B]oops\u0007ok")

        assertTrue(emulator.rowText(0).contains("ok"))
    }

    @Test
    fun `clear screen keeps scrollback history`() {
        val emulator = TerminalEmulator(cols = 10, rows = 2)
        emulator.feedText("one\r\ntwo\r\nthree")
        emulator.clearScreen()

        assertEquals("", emulator.rowText(0))
        assertTrue(emulator.activeBuffer.scrollback.size >= 1)
        assertEquals(0, emulator.scrollOffset)
    }

    @Test
    fun `terminal reset clears everything`() {
        val emulator = TerminalEmulator(cols = 10, rows = 3)
        emulator.feedText("payload")
        emulator.setSelection(0, 0, 2, 0)
        emulator.feedText("\u001Bc")

        assertEquals("", emulator.rowText(0))
        assertNull(emulator.selectionRect)
        assertEquals(0 to 0, emulator.cursorPosition())
        assertEquals(0, emulator.activeBuffer.scrollback.size)
    }
}
