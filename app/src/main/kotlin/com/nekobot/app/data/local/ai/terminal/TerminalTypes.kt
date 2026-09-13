package com.nekobot.app.data.local.ai.terminal

/** 终端颜色：默认色、256 色索引或 24 位真彩。 */
sealed class TerminalColor {
    object Default : TerminalColor()
    data class Indexed(val index: Int) : TerminalColor()
    data class Rgb(val red: Int, val green: Int, val blue: Int) : TerminalColor()
}

/**
 * xterm-256 调色板与默认前/背景色（ARGB Int，便于 Canvas 直接绘制）。
 *
 * 默认色沿用聊天页沙箱终端的深蓝黑底 + 浅灰前景，避免纯黑底在 OLED 上与界面脱节。
 */
object TerminalPalette {
    /** 默认前景色。 */
    const val DEFAULT_FOREGROUND: Int = 0xFFD8DEE9.toInt()

    /** 默认背景色。 */
    const val DEFAULT_BACKGROUND: Int = 0xFF0B0F14.toInt()

    private val table: IntArray = IntArray(256).also { table ->
        // 0-15：标准色与高亮色（暗色背景下对比度均衡的一组取值）
        val base = intArrayOf(
            0x000000, 0xCD3131, 0x0DBC79, 0xE5E510,
            0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
            0x666666, 0xF14C4C, 0x23D18B, 0xF5F543,
            0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF,
        )
        for (index in base.indices) {
            table[index] = 0xFF000000.toInt() or base[index]
        }
        // 16-231：6×6×6 色彩立方
        var cursor = 16
        for (red in 0 until 6) {
            for (green in 0 until 6) {
                for (blue in 0 until 6) {
                    val r = if (red == 0) 0 else 55 + 40 * red
                    val g = if (green == 0) 0 else 55 + 40 * green
                    val b = if (blue == 0) 0 else 55 + 40 * blue
                    table[cursor++] = argb(0xFF, r, g, b)
                }
            }
        }
        // 232-255：24 级灰阶
        for (step in 0 until 24) {
            val value = 8 + 10 * step
            table[cursor++] = argb(0xFF, value, value, value)
        }
    }

    /** 取得 256 色索引对应的 ARGB 值。 */
    fun indexed(index: Int): Int = table[index.coerceIn(0, table.size - 1)]

    /**
     * 把终端颜色解析成可绘制的 ARGB。
     *
     * [bold] 为真且是前景色时，0-7 的索引色按高亮色处理（传统终端的加亮行为）。
     */
    fun resolve(color: TerminalColor, isForeground: Boolean, bold: Boolean = false): Int =
        when (color) {
            is TerminalColor.Default -> if (isForeground) DEFAULT_FOREGROUND else DEFAULT_BACKGROUND
            is TerminalColor.Indexed ->
                if (bold && isForeground && color.index < 8) indexed(color.index + 8)
                else indexed(color.index)
            is TerminalColor.Rgb -> argb(0xFF, color.red, color.green, color.blue)
        }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)
}

/** 文本属性位域（粗体/斜体/下划线等）。 */
@JvmInline
value class TextAttributes(val bits: Int = 0) {
    fun has(flag: Int): Boolean = bits and flag != 0
    fun with(flag: Int): TextAttributes = TextAttributes(bits or flag)
    fun without(flag: Int): TextAttributes = TextAttributes(bits and flag.inv())

    companion object {
        const val BOLD = 1 shl 0
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val BLINK = 1 shl 4
        const val INVERSE = 1 shl 5
        const val HIDDEN = 1 shl 6
        const val STRIKETHROUGH = 1 shl 7
    }
}

/**
 * 网格中的单个字符单元。
 *
 * [isWideTrailer] 标记宽字符（CJK/emoji）占用的第二个格子，绘制时跳过。
 */
data class TerminalCell(
    val codePoint: Int = SPACE,
    val foreground: TerminalColor = TerminalColor.Default,
    val background: TerminalColor = TerminalColor.Default,
    val attributes: TextAttributes = TextAttributes(),
    val width: Int = 1,
    val isWideTrailer: Boolean = false,
) {
    companion object {
        const val SPACE = ' '.code
        val BLANK = TerminalCell()
    }
}

/** 当前 SGR 状态，决定新写入字符的样式。 */
class CursorStyle(
    var foreground: TerminalColor = TerminalColor.Default,
    var background: TerminalColor = TerminalColor.Default,
    var attributes: TextAttributes = TextAttributes(),
) {
    fun makeCell(codePoint: Int, width: Int = 1): TerminalCell =
        TerminalCell(codePoint, foreground, background, attributes, width)

    fun reset() {
        foreground = TerminalColor.Default
        background = TerminalColor.Default
        attributes = TextAttributes()
    }

    /** 用 [other] 覆盖当前状态（用于保存/恢复光标时同步 SGR）。 */
    fun applyFrom(other: CursorStyle) {
        foreground = other.foreground
        background = other.background
        attributes = other.attributes
    }

    /** 生成快照，避免恢复后与原对象共享可变状态。 */
    fun snapshot(): CursorStyle = CursorStyle(foreground, background, attributes)
}

/** 光标形状（DECSCUSR）。 */
enum class CursorShape { BLOCK, UNDERLINE, BAR }
