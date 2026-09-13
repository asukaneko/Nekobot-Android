package com.nekobot.app.data.local.ai.terminal

/**
 * 沙箱终端仿真核心：把 PTY 输出的字节流变成可绘制的字符网格。
 *
 * 结构上分成三层——[TerminalAnsiParser] 负责字节 → 事件，本类负责事件 → 缓冲区操作，
 * [TerminalBuffer] 负责网格状态。本类不依赖任何 Android / Compose 类型，
 * 因此可以直接用 JVM 单元测试覆盖。
 *
 * 线程约定：所有方法都在同一线程（UI 主线程）调用；PTY 读线程只通过
 * [TerminalSession] 的 Flow 把字节交给 UI 线程后再 [feed]。
 */
internal class TerminalEmulator(cols: Int = DEFAULT_COLS, rows: Int = DEFAULT_ROWS) :
    TerminalEventSink {

    private val primary = TerminalBuffer(cols, rows, maxScrollback = MAX_SCROLLBACK)
    private val alternate = TerminalBuffer(cols, rows, maxScrollback = 0).apply {
        scrollbackEnabled = false
    }

    private val parser = TerminalAnsiParser(this)
    private val pen = CursorStyle()

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    var isAlternateActive: Boolean = false
        private set

    val activeBuffer: TerminalBuffer
        get() = if (isAlternateActive) alternate else primary

    // ── DEC 私有模式开关（供输入层决定按键编码）──────────────────────────
    var applicationCursorKeys: Boolean = false
        private set
    var applicationKeypad: Boolean = false
        private set
    var autoWrap: Boolean = true
        private set
    var bracketedPaste: Boolean = false
        private set
    var originMode: Boolean = false
        private set
    var cursorVisible: Boolean = true
        private set
    var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    var title: String = ""
        private set

    /** 终端主动回写的数据（DSR / DA 等），由会话层写回 PTY。 */
    var onResponse: ((ByteArray) -> Unit)? = null

    /** 内容变化通知，UI 层用它触发重绘。 */
    var onChanged: (() -> Unit)? = null

    /** 回滚查看偏移：0 表示停在最新输出。 */
    var scrollOffset: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, activeBuffer.scrollback.size)
            if (field != clamped) {
                field = clamped
                notifyChanged()
            }
        }

    // ── 文本选择（视口坐标）──────────────────────────────────────────────
    private var selection: IntArray? = null

    val selectionRect: IntArray? get() = selection

    fun setSelection(col1: Int, row1: Int, col2: Int, row2: Int) {
        selection = intArrayOf(col1, row1, col2, row2)
        notifyChanged()
    }

    fun clearSelection() {
        if (selection != null) {
            selection = null
            notifyChanged()
        }
    }

    // ── 输入 ──────────────────────────────────────────────────────────────

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        parser.feed(bytes, length)
        notifyChanged()
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        primary.resize(newCols, newRows)
        alternate.resize(newCols, newRows)
        if (scrollOffset > activeBuffer.scrollback.size) {
            scrollOffset = activeBuffer.scrollback.size
        }
        notifyChanged()
    }

    fun cursorPosition(): Pair<Int, Int> = activeBuffer.cursorCol to activeBuffer.cursorRow

    /**
     * 返回恰好 [rows] 行、每行 [cols] 列的可见内容，已应用回滚偏移。
     *
     * 历史行会按当前列数补齐/裁剪，保证尺寸变化后旧行仍能对齐绘制。
     */
    fun visibleLines(): List<Array<TerminalCell>> {
        val buffer = activeBuffer
        val grid = buffer.grid
        if (scrollOffset == 0) {
            return if (grid.size == rows && grid.all { it.size == cols }) grid.toList()
            else List(rows) { row -> normalizeRow(grid.getOrNull(row)) }
        }

        val history = buffer.scrollback
        val totalRows = history.size + grid.size
        val start = (totalRows - rows - scrollOffset).coerceAtLeast(0)
        return List(rows) { offset ->
            val index = start + offset
            if (index < history.size) normalizeRow(history.elementAt(index))
            else normalizeRow(grid.getOrNull(index - history.size))
        }
    }

    /** 提取选区文本，按阅读顺序返回，行尾空格会被裁掉。 */
    fun selectedText(): String {
        val rect = selection ?: return ""
        val lines = visibleLines()
        if (lines.isEmpty()) return ""

        val forward = rect[1] < rect[3] || (rect[1] == rect[3] && rect[0] <= rect[2])
        val startCol = if (forward) rect[0] else rect[2]
        val startRow = (if (forward) rect[1] else rect[3]).coerceIn(0, lines.size - 1)
        val endCol = if (forward) rect[2] else rect[0]
        val endRow = (if (forward) rect[3] else rect[1]).coerceIn(0, lines.size - 1)

        val builder = StringBuilder()
        for (row in startRow..endRow) {
            val line = lines[row]
            val from = if (row == startRow) startCol.coerceIn(0, line.size) else 0
            val to = if (row == endRow) (endCol + 1).coerceIn(0, line.size) else line.size
            val rowText = StringBuilder()
            for (col in from until to) {
                val codePoint = line[col].codePoint
                if (codePoint != 0) rowText.appendCodePoint(codePoint)
            }
            var end = rowText.length
            while (end > 0 && rowText[end - 1] == ' ') end--
            builder.append(rowText, 0, end)
            if (row < endRow) builder.append('\n')
        }
        return builder.toString()
    }

    // ── TerminalEventSink ────────────────────────────────────────────────

    override fun onPrint(codePoint: Int) {
        activeBuffer.writeCodePoint(codePoint, pen, autoWrap)
    }

    override fun onControl(code: Int) {
        when (code) {
            0x07 -> Unit                                  // BEL：暂不发声
            0x08 -> activeBuffer.moveCursorBackward(1)    // BS
            0x09 -> activeBuffer.tabForward()             // HT
            0x0A, 0x0B, 0x0C -> activeBuffer.lineFeed()   // LF / VT / FF
            0x0D -> activeBuffer.carriageReturn()         // CR
            0x0E, 0x0F -> Unit                            // 字符集切换，忽略
        }
    }

    override fun onCsi(
        params: IntArray,
        paramCount: Int,
        privateMarker: Char,
        intermediate: Char,
        finalByte: Char,
    ) {
        if (privateMarker == '?') {
            applyDecPrivateMode(params, paramCount, set = finalByte == 'h')
            return
        }
        if (privateMarker == '>') {
            if (finalByte == 'c') respond("\u001B[>0;0;0c")   // 次要设备属性
            return
        }

        val buffer = activeBuffer
        val first = parameter(params, paramCount, 0, 1)
        when (finalByte) {
            'A' -> buffer.moveCursorUp(first)
            'B' -> buffer.moveCursorDown(first)
            'C' -> buffer.moveCursorForward(first)
            'D' -> buffer.moveCursorBackward(first)
            'E' -> {
                buffer.moveCursorDown(first)
                buffer.carriageReturn()
            }
            'F' -> {
                buffer.moveCursorUp(first)
                buffer.carriageReturn()
            }
            'G', '`' -> buffer.moveCursorToColumn(first - 1)
            'H', 'f' -> {
                val row = first
                val col = parameter(params, paramCount, 1, 1)
                if (originMode) buffer.moveCursorTo(col - 1, buffer.scrollTop + row - 1)
                else buffer.moveCursorTo(col - 1, row - 1)
            }
            'd' -> buffer.moveCursorTo(buffer.cursorCol, first - 1)
            'J' -> buffer.eraseInDisplay(parameter(params, paramCount, 0, 0))
            'K' -> buffer.eraseInLine(parameter(params, paramCount, 0, 0))
            'X' -> buffer.eraseCharacters(first)
            'L' -> buffer.insertLines(first)
            'M' -> buffer.deleteLines(first)
            '@' -> buffer.insertCharacters(first)
            'P' -> buffer.deleteCharacters(first)
            'S' -> buffer.scrollUp(first)
            'T' -> buffer.scrollDown(first)
            'r' -> {
                val top = first
                val bottom = parameter(params, paramCount, 1, rows)
                buffer.setScrollRegion(top, bottom)
                buffer.moveCursorTo(0, if (originMode) buffer.scrollTop else 0)
            }
            'm' -> applySgr(params, paramCount)
            'n' -> when (parameter(params, paramCount, 0, 0)) {
                6 -> respond("\u001B[${buffer.cursorRow + 1};${buffer.cursorCol + 1}R")
                5 -> respond("\u001B[0n")
            }
            's' -> buffer.saveCursor(pen)
            'u' -> buffer.restoreCursor(pen)
            'I' -> repeat(first) { buffer.tabForward() }
            'q' -> if (intermediate == ' ') {
                cursorShape = when (parameter(params, paramCount, 0, 1)) {
                    3, 4 -> CursorShape.UNDERLINE
                    5, 6 -> CursorShape.BAR
                    else -> CursorShape.BLOCK
                }
            }
            else -> Unit
        }
    }

    override fun onEsc(finalByte: Char) {
        when (finalByte) {
            '7' -> activeBuffer.saveCursor(pen)
            '8' -> activeBuffer.restoreCursor(pen)
            'D' -> activeBuffer.lineFeed()
            'M' -> activeBuffer.reverseIndex()
            'E' -> {
                activeBuffer.carriageReturn()
                activeBuffer.lineFeed()
            }
            'c' -> resetTerminal()
            '=' -> applicationKeypad = true
            '>' -> applicationKeypad = false
            else -> Unit
        }
    }

    override fun onOsc(command: Int, payload: String) {
        if (command == 0 || command == 2) title = payload
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    private fun applyDecPrivateMode(params: IntArray, count: Int, set: Boolean) {
        for (index in 0 until count) {
            when (params[index]) {
                1 -> applicationCursorKeys = set
                6 -> {
                    originMode = set
                    activeBuffer.moveCursorTo(0, if (set) activeBuffer.scrollTop else 0)
                }
                7 -> autoWrap = set
                25 -> cursorVisible = set
                47, 1047 -> switchAlternate(set)
                1048 -> if (set) activeBuffer.saveCursor(pen) else activeBuffer.restoreCursor(pen)
                1049 -> if (set) {
                    primary.saveCursor(pen)
                    switchAlternate(true)
                    activeBuffer.eraseInDisplay(2)
                } else {
                    switchAlternate(false)
                    primary.restoreCursor(pen)
                }
                2004 -> bracketedPaste = set
                else -> Unit
            }
        }
    }

    private fun applySgr(params: IntArray, count: Int) {
        if (count == 0) {
            pen.reset()
            return
        }
        var index = 0
        while (index < count) {
            when (val code = params[index]) {
                0 -> pen.reset()
                1 -> pen.attributes = pen.attributes.with(TextAttributes.BOLD)
                2 -> pen.attributes = pen.attributes.with(TextAttributes.DIM)
                3 -> pen.attributes = pen.attributes.with(TextAttributes.ITALIC)
                4, 21 -> pen.attributes = pen.attributes.with(TextAttributes.UNDERLINE)
                5 -> pen.attributes = pen.attributes.with(TextAttributes.BLINK)
                7 -> pen.attributes = pen.attributes.with(TextAttributes.INVERSE)
                8 -> pen.attributes = pen.attributes.with(TextAttributes.HIDDEN)
                9 -> pen.attributes = pen.attributes.with(TextAttributes.STRIKETHROUGH)
                22 -> pen.attributes = pen.attributes
                    .without(TextAttributes.BOLD)
                    .without(TextAttributes.DIM)
                23 -> pen.attributes = pen.attributes.without(TextAttributes.ITALIC)
                24 -> pen.attributes = pen.attributes.without(TextAttributes.UNDERLINE)
                25 -> pen.attributes = pen.attributes.without(TextAttributes.BLINK)
                27 -> pen.attributes = pen.attributes.without(TextAttributes.INVERSE)
                28 -> pen.attributes = pen.attributes.without(TextAttributes.HIDDEN)
                29 -> pen.attributes = pen.attributes.without(TextAttributes.STRIKETHROUGH)
                in 30..37 -> pen.foreground = TerminalColor.Indexed(code - 30)
                in 40..47 -> pen.background = TerminalColor.Indexed(code - 40)
                39 -> pen.foreground = TerminalColor.Default
                49 -> pen.background = TerminalColor.Default
                in 90..97 -> pen.foreground = TerminalColor.Indexed(code - 90 + 8)
                in 100..107 -> pen.background = TerminalColor.Indexed(code - 100 + 8)
                38 -> index = applyExtendedColor(params, count, index, isForeground = true)
                48 -> index = applyExtendedColor(params, count, index, isForeground = false)
            }
            index++
        }
    }

    /**
     * 处理 `38;5;n` / `38;2;r;g;b`（以及 48 对应背景）。
     *
     * @return 该 SGR 子序列占用的最后一个参数下标。
     */
    private fun applyExtendedColor(
        params: IntArray,
        count: Int,
        index: Int,
        isForeground: Boolean,
    ): Int {
        if (index + 1 >= count) return index
        return when (params[index + 1]) {
            5 -> {
                if (index + 2 >= count) return index + 1
                val color = TerminalColor.Indexed(params[index + 2].coerceIn(0, 255))
                if (isForeground) pen.foreground = color else pen.background = color
                index + 2
            }
            2 -> {
                if (index + 4 >= count) return index + 1
                val color = TerminalColor.Rgb(
                    params[index + 2].coerceIn(0, 255),
                    params[index + 3].coerceIn(0, 255),
                    params[index + 4].coerceIn(0, 255),
                )
                if (isForeground) pen.foreground = color else pen.background = color
                index + 4
            }
            else -> index
        }
    }

    private fun switchAlternate(enable: Boolean) {
        if (enable && !isAlternateActive) {
            isAlternateActive = true
            alternate.resize(cols, rows)
            alternate.eraseInDisplay(2)
            alternate.moveCursorTo(0, 0)
        } else if (!enable && isAlternateActive) {
            isAlternateActive = false
        }
    }

    private fun resetTerminal() {
        parser.reset()
        pen.reset()
        applicationCursorKeys = false
        applicationKeypad = false
        autoWrap = true
        bracketedPaste = false
        originMode = false
        cursorVisible = true
        cursorShape = CursorShape.BLOCK
        isAlternateActive = false
        primary.resize(cols, rows)
        primary.eraseInDisplay(3)
        primary.moveCursorTo(0, 0)
        alternate.resize(cols, rows)
        alternate.eraseInDisplay(3)
        alternate.moveCursorTo(0, 0)
        selection = null
        scrollOffset = 0
        title = ""
    }

    /** 清屏但保留回滚历史（终端界面的“清屏”按钮）。 */
    fun clearScreen() {
        activeBuffer.eraseInDisplay(2)
        activeBuffer.moveCursorTo(0, 0)
        selection = null
        scrollOffset = 0
        notifyChanged()
    }

    private fun respond(text: String) {
        onResponse?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    /** 取第 [index] 个参数，缺省或非正值时用 [fallback]。 */
    private fun parameter(params: IntArray, count: Int, index: Int, fallback: Int): Int {
        if (index >= count) return fallback
        val value = params[index]
        return if (value <= 0) fallback else value
    }

    private fun normalizeRow(row: Array<TerminalCell>?): Array<TerminalCell> {
        if (row == null) return Array(cols) { TerminalCell.BLANK }
        if (row.size == cols) return row
        return Array(cols) { index -> row.getOrNull(index) ?: TerminalCell.BLANK }
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        private const val MAX_SCROLLBACK = 2000
    }
}
