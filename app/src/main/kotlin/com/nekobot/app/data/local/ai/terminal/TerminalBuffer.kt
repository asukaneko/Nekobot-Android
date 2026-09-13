package com.nekobot.app.data.local.ai.terminal

/**
 * 终端屏幕缓冲区：rows × cols 字符网格 + 回滚历史 + 光标 + 滚动区域。
 *
 * 只保存状态与操作，不处理转义序列解析（见 [TerminalAnsiParser]）也不涉及绘制。
 */
internal class TerminalBuffer(
    var cols: Int,
    var rows: Int,
    private val maxScrollback: Int = 2000,
) {
    var grid: Array<Array<TerminalCell>> = emptyGrid(cols, rows)
        private set

    /** 已经滚出屏幕顶部的历史行（仅主缓冲区保留）。 */
    val scrollback: ArrayDeque<Array<TerminalCell>> = ArrayDeque()

    /** 备用屏幕不应污染主缓冲区的回滚历史。 */
    var scrollbackEnabled: Boolean = true

    var cursorCol: Int = 0
        private set
    var cursorRow: Int = 0
        private set

    /** 上一字符正好停在行尾：下一个字符需要先自动换行。 */
    var wrapPending: Boolean = false
        private set

    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    private var savedCursorCol = 0
    private var savedCursorRow = 0
    private var savedStyle: CursorStyle = CursorStyle()

    // ── 光标移动 ──────────────────────────────────────────────────────────

    fun moveCursorTo(col: Int, row: Int) {
        cursorCol = col.coerceIn(0, cols - 1)
        cursorRow = row.coerceIn(0, rows - 1)
        wrapPending = false
    }

    fun moveCursorToColumn(col: Int) {
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    fun moveCursorUp(count: Int) {
        cursorRow = (cursorRow - count).coerceAtLeast(scrollTop)
        wrapPending = false
    }

    fun moveCursorDown(count: Int) {
        cursorRow = (cursorRow + count).coerceAtMost(scrollBottom)
        wrapPending = false
    }

    fun moveCursorForward(count: Int) {
        cursorCol = (cursorCol + count).coerceAtMost(cols - 1)
        wrapPending = false
    }

    fun moveCursorBackward(count: Int) {
        cursorCol = (cursorCol - count).coerceAtLeast(0)
        wrapPending = false
    }

    // ── 光标保存 / 恢复（DECSC / DECRC）───────────────────────────────────

    fun saveCursor(style: CursorStyle) {
        savedCursorCol = cursorCol
        savedCursorRow = cursorRow
        savedStyle = style.snapshot()
    }

    /** 恢复光标位置，并把保存时的 SGR 状态写回 [style]。 */
    fun restoreCursor(style: CursorStyle) {
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        wrapPending = false
        style.applyFrom(savedStyle)
    }

    // ── 写入 ──────────────────────────────────────────────────────────────

    fun writeCodePoint(codePoint: Int, style: CursorStyle, autoWrap: Boolean) {
        if (wrapPending) {
            wrapPending = false
            if (autoWrap) {
                cursorCol = 0
                lineFeed()
            }
        }

        val width = characterWidth(codePoint)
        if (width == 2 && cursorCol >= cols - 1) {
            if (autoWrap) {
                cursorCol = 0
                lineFeed()
            } else {
                cursorCol = (cols - 2).coerceAtLeast(0)
            }
        }

        putCell(cursorRow, cursorCol, style.makeCell(codePoint, width))
        if (width == 2 && cursorCol + 1 < cols) {
            putCell(
                cursorRow,
                cursorCol + 1,
                TerminalCell(
                    codePoint = TerminalCell.SPACE,
                    foreground = style.foreground,
                    background = style.background,
                    attributes = style.attributes,
                    width = 1,
                    isWideTrailer = true,
                ),
            )
        }

        cursorCol += width
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        }
    }

    // ── 行操作 ────────────────────────────────────────────────────────────

    fun carriageReturn() {
        cursorCol = 0
        wrapPending = false
    }

    fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
        wrapPending = false
    }

    /** RI：光标上移一行，已在区域顶部时向下滚屏。 */
    fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
        wrapPending = false
    }

    // ── 滚屏 ──────────────────────────────────────────────────────────────

    fun scrollUp(count: Int) {
        repeat(count.coerceAtLeast(0)) {
            if (scrollbackEnabled && scrollTop == 0) {
                scrollback.addLast(grid[scrollTop])
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (row in scrollTop until scrollBottom) {
                grid[row] = grid[row + 1]
            }
            grid[scrollBottom] = blankRow()
        }
    }

    fun scrollDown(count: Int) {
        repeat(count.coerceAtLeast(0)) {
            for (row in scrollBottom downTo scrollTop + 1) {
                grid[row] = grid[row - 1]
            }
            grid[scrollTop] = blankRow()
        }
    }

    // ── 擦除 ──────────────────────────────────────────────────────────────

    /** ED：0=光标到末尾，1=开头到光标，2=整屏，3=整屏并清空回滚。 */
    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cursorRow + 1 until rows) grid[row] = blankRow()
            }
            1 -> {
                eraseInLine(1)
                for (row in 0 until cursorRow) grid[row] = blankRow()
            }
            2 -> for (row in 0 until rows) grid[row] = blankRow()
            3 -> {
                for (row in 0 until rows) grid[row] = blankRow()
                scrollback.clear()
            }
        }
    }

    /** EL：0=光标到行尾，1=行首到光标，2=整行。 */
    fun eraseInLine(mode: Int) {
        if (cursorRow !in 0 until rows) return
        val row = grid[cursorRow]
        when (mode) {
            0 -> for (col in cursorCol until cols) row[col] = TerminalCell.BLANK
            1 -> for (col in 0..cursorCol.coerceAtMost(cols - 1)) row[col] = TerminalCell.BLANK
            2 -> grid[cursorRow] = blankRow()
        }
    }

    /** ECH：从光标处擦除 [count] 个字符（不移动光标）。 */
    fun eraseCharacters(count: Int) {
        if (cursorRow !in 0 until rows) return
        val row = grid[cursorRow]
        val end = (cursorCol + count).coerceAtMost(cols)
        for (col in cursorCol until end) row[col] = TerminalCell.BLANK
    }

    // ── 插入 / 删除行与字符 ───────────────────────────────────────────────

    /** IL：在光标行插入空行，区域内的行下移。 */
    fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val lines = count.coerceIn(1, scrollBottom - cursorRow + 1)
        repeat(lines) {
            for (row in scrollBottom downTo cursorRow + 1) grid[row] = grid[row - 1]
            grid[cursorRow] = blankRow()
        }
    }

    /** DL：删除光标行，区域内的行上移。 */
    fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val lines = count.coerceIn(1, scrollBottom - cursorRow + 1)
        repeat(lines) {
            for (row in cursorRow until scrollBottom) grid[row] = grid[row + 1]
            grid[scrollBottom] = blankRow()
        }
    }

    /** ICH：在光标处插入空字符，右侧字符右移。 */
    fun insertCharacters(count: Int) {
        if (cursorRow !in 0 until rows) return
        val row = grid[cursorRow]
        val chars = count.coerceIn(1, cols - cursorCol)
        repeat(chars) {
            for (col in cols - 1 downTo cursorCol + 1) row[col] = row[col - 1]
            row[cursorCol] = TerminalCell.BLANK
        }
    }

    /** DCH：删除光标处字符，右侧字符左移。 */
    fun deleteCharacters(count: Int) {
        if (cursorRow !in 0 until rows) return
        val row = grid[cursorRow]
        val chars = count.coerceIn(1, cols - cursorCol)
        repeat(chars) {
            for (col in cursorCol until cols - 1) row[col] = row[col + 1]
            row[cols - 1] = TerminalCell.BLANK
        }
    }

    // ── 滚动区域（DECSTBM）───────────────────────────────────────────────

    /** 传入的是 1-based 行号，与 CSI r 一致。 */
    fun setScrollRegion(top: Int, bottom: Int) {
        val regionTop = (top - 1).coerceIn(0, rows - 1)
        val regionBottom = (bottom - 1).coerceIn(0, rows - 1)
        if (regionTop >= regionBottom) return
        scrollTop = regionTop
        scrollBottom = regionBottom
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    // ── 制表位（简化为每 8 列）────────────────────────────────────────────

    fun tabForward() {
        cursorCol = (((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH).coerceAtMost(cols - 1)
        wrapPending = false
    }

    // ── 尺寸变化 ──────────────────────────────────────────────────────────

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return
        grid = Array(newRows) { row ->
            Array(newCols) { col ->
                if (row < rows && col < cols) grid[row][col] else TerminalCell.BLANK
            }
        }
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        wrapPending = false
    }

    /** 取出某一行；越界返回空行，便于绘制端直接使用。 */
    fun rowOrBlank(row: Int): Array<TerminalCell> =
        if (row in 0 until rows) grid[row] else blankRow()

    private fun putCell(row: Int, col: Int, cell: TerminalCell) {
        if (row in 0 until rows && col in 0 until cols) {
            grid[row][col] = cell
        }
    }

    private fun blankRow(): Array<TerminalCell> = Array(cols) { TerminalCell.BLANK }

    private fun emptyGrid(columns: Int, lines: Int): Array<Array<TerminalCell>> =
        Array(lines) { Array(columns) { TerminalCell.BLANK } }

    companion object {
        const val TAB_WIDTH = 8

        /**
         * 判断码点的显示宽度：CJK、全角标点与 emoji 占 2 列，其余占 1 列。
         *
         * 依赖终端使用等宽字体，宽字符恰好占两格。
         */
        fun characterWidth(codePoint: Int): Int = when {
            codePoint < 0x1100 -> 1
            codePoint in 0x1100..0x115F -> 2                       // 谚文字母
            codePoint == 0x2329 || codePoint == 0x232A -> 2
            codePoint in 0x2E80..0x303E -> 2                       // 中日韩部首、假名标点
            codePoint in 0x3041..0x33FF -> 2                       // 假名、注音、韩文兼容
            codePoint in 0x3400..0x4DBF -> 2                       // 扩展 A
            codePoint in 0x4E00..0x9FFF -> 2                       // 基本汉字
            codePoint in 0xA000..0xA4CF -> 2                       // 彝文
            codePoint in 0xAC00..0xD7A3 -> 2                       // 韩文音节
            codePoint in 0xF900..0xFAFF -> 2                       // 兼容汉字
            codePoint in 0xFE10..0xFE19 -> 2
            codePoint in 0xFE30..0xFE6F -> 2
            codePoint in 0xFF00..0xFF60 -> 2                       // 全角形式
            codePoint in 0xFFE0..0xFFE6 -> 2
            codePoint in 0x1F300..0x1F64F -> 2                     // emoji
            codePoint in 0x1F900..0x1F9FF -> 2
            codePoint in 0x20000..0x3FFFD -> 2                     // 扩展 B 及以后
            else -> 1
        }
    }
}
