package com.nekobot.app.data.local.ai.terminal

/**
 * 终端事件接收方：解析器完全不分配事件对象，直接回调宿主。
 *
 * 参数数组 [params] 是解析器内部复用的缓冲区，只在回调期间有效，
 * 接收方不得保存引用。
 */
internal interface TerminalEventSink {
    /** 可打印字符（已按 UTF-8 解码成码点）。 */
    fun onPrint(codePoint: Int)

    /** C0 控制字符。 */
    fun onControl(code: Int)

    /** CSI 序列，例如 `ESC [ 1 ; 2 H`。 */
    fun onCsi(params: IntArray, paramCount: Int, privateMarker: Char, intermediate: Char, finalByte: Char)

    /** 两字符 ESC 序列（含中间字符的序列只保留最终字节）。 */
    fun onEsc(finalByte: Char)

    /** OSC 序列（标题、超链接等）。 */
    fun onOsc(command: Int, payload: String)
}

/**
 * VT100 / xterm 转义序列解析器。
 *
 * 采用标准的“字节驱动状态机”实现：GROUND 状态下处理 UTF-8 解码与 C0 控制字符，
 * ESC/CSI/OSC 各自有独立状态，遇到非法字节即回到 GROUND，避免畸形序列卡死终端。
 */
internal class TerminalAnsiParser(private val sink: TerminalEventSink) {

    private enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        OSC_COMMAND,
        OSC_STRING,
        STRING_IGNORE,
    }

    private var state = State.GROUND

    // ── CSI 累积状态 ──────────────────────────────────────────────────────
    private val parameters = IntArray(MAX_PARAMS)
    private var parameterCount = 0
    private var currentParameter = 0
    private var hasCurrentParameter = false
    private var privateMarker = NONE
    private var intermediate = NONE

    // ── OSC 累积状态 ──────────────────────────────────────────────────────
    private var oscCommand = 0
    private var hasOscCommand = false
    private val oscPayload = StringBuilder()

    // ── UTF-8 解码 ────────────────────────────────────────────────────────
    private val utf8 = ByteArray(4)
    private var utf8Length = 0
    private var utf8Needed = 0

    /** 喂入一段原始字节。 */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        var index = 0
        while (index < length) {
            consume(bytes[index].toInt() and 0xFF)
            index++
        }
    }

    /** 丢弃未完成的解析状态（清屏/重置时调用）。 */
    fun reset() {
        state = State.GROUND
        parameterCount = 0
        currentParameter = 0
        hasCurrentParameter = false
        privateMarker = NONE
        intermediate = NONE
        oscCommand = 0
        hasOscCommand = false
        oscPayload.setLength(0)
        utf8Length = 0
        utf8Needed = 0
    }

    private fun consume(byte: Int) {
        if (utf8Needed > 0 && state == State.GROUND) {
            if (byte and 0xC0 == 0x80) {
                utf8[utf8Length++] = byte.toByte()
                utf8Needed--
                if (utf8Needed == 0) {
                    val codePoint = decodeUtf8(utf8, utf8Length)
                    utf8Length = 0
                    if (codePoint >= 0) sink.onPrint(codePoint)
                }
                return
            }
            // 残缺序列：丢弃并重新按普通字节处理当前字节
            utf8Length = 0
            utf8Needed = 0
        }

        when (state) {
            State.GROUND -> consumeGround(byte)
            State.ESCAPE -> consumeEscape(byte)
            State.ESCAPE_INTERMEDIATE -> consumeEscapeIntermediate(byte)
            State.CSI_ENTRY -> consumeCsiEntry(byte)
            State.CSI_PARAM -> consumeCsiParam(byte)
            State.CSI_INTERMEDIATE -> consumeCsiIntermediate(byte)
            State.CSI_IGNORE -> consumeCsiIgnore(byte)
            State.OSC_COMMAND -> consumeOscCommand(byte)
            State.OSC_STRING -> consumeOscString(byte)
            State.STRING_IGNORE -> consumeStringIgnore(byte)
        }
    }

    private fun consumeGround(byte: Int) {
        when {
            byte == 0x1B -> state = State.ESCAPE
            byte == 0x9B -> beginCsi()                       // 8 位 CSI
            byte == 0x9D -> beginOsc()                       // 8 位 OSC
            byte == 0x90 || byte == 0x98 || byte == 0x9E || byte == 0x9F ->
                state = State.STRING_IGNORE                   // DCS/SOS/PM/APC
            byte < 0x20 || byte == 0x7F -> sink.onControl(byte)
            byte in 0x20..0x7E -> sink.onPrint(byte)
            byte in 0xC2..0xDF -> startUtf8(byte, 1)
            byte in 0xE0..0xEF -> startUtf8(byte, 2)
            byte in 0xF0..0xF4 -> startUtf8(byte, 3)
            else -> Unit                                       // 非法起始字节，忽略
        }
    }

    private fun startUtf8(byte: Int, continuationBytes: Int) {
        utf8[0] = byte.toByte()
        utf8Length = 1
        utf8Needed = continuationBytes
    }

    private fun consumeEscape(byte: Int) {
        when (byte) {
            0x5B -> beginCsi()                                 // [
            0x5D -> beginOsc()                                 // ]
            0x50, 0x58, 0x5E, 0x5F -> state = State.STRING_IGNORE
            in 0x20..0x2F -> state = State.ESCAPE_INTERMEDIATE
            0x1B -> Unit                                       // 连续 ESC，保持在 ESCAPE
            in 0x30..0x7E -> {
                sink.onEsc(byte.toChar())
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun consumeEscapeIntermediate(byte: Int) {
        when (byte) {
            in 0x20..0x2F -> Unit
            0x1B -> state = State.ESCAPE
            in 0x30..0x7E -> {
                sink.onEsc(byte.toChar())
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun beginCsi() {
        state = State.CSI_ENTRY
        parameterCount = 0
        currentParameter = 0
        hasCurrentParameter = false
        privateMarker = NONE
        intermediate = NONE
    }

    private fun consumeCsiEntry(byte: Int) {
        when (byte) {
            in 0x30..0x39 -> {
                currentParameter = byte - 0x30
                hasCurrentParameter = true
                state = State.CSI_PARAM
            }
            0x3B -> {
                pushParameter(0)
                state = State.CSI_PARAM
            }
            in 0x3C..0x3F -> {
                privateMarker = byte.toChar()
                state = State.CSI_PARAM
            }
            in 0x20..0x2F -> {
                intermediate = byte.toChar()
                state = State.CSI_INTERMEDIATE
            }
            0x1B -> state = State.ESCAPE
            in 0x40..0x7E -> dispatchCsi(byte.toChar())
            else -> state = State.CSI_IGNORE
        }
    }

    private fun consumeCsiParam(byte: Int) {
        when (byte) {
            in 0x30..0x39 -> {
                // 参数过长时饱和，避免整数溢出
                currentParameter = if (currentParameter > MAX_PARAM_VALUE / 10) MAX_PARAM_VALUE
                else currentParameter * 10 + (byte - 0x30)
                hasCurrentParameter = true
            }
            0x3B -> {
                pushParameter(if (hasCurrentParameter) currentParameter else 0)
                currentParameter = 0
                hasCurrentParameter = false
            }
            in 0x3C..0x3F -> if (privateMarker == NONE) privateMarker = byte.toChar()
            in 0x20..0x2F -> {
                if (hasCurrentParameter) {
                    pushParameter(currentParameter)
                    currentParameter = 0
                    hasCurrentParameter = false
                }
                if (intermediate == NONE) intermediate = byte.toChar()
                state = State.CSI_INTERMEDIATE
            }
            0x1B -> state = State.ESCAPE
            in 0x40..0x7E -> {
                if (hasCurrentParameter) pushParameter(currentParameter)
                dispatchCsi(byte.toChar())
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun consumeCsiIntermediate(byte: Int) {
        when (byte) {
            in 0x20..0x2F -> Unit
            0x1B -> state = State.ESCAPE
            in 0x40..0x7E -> {
                if (hasCurrentParameter) pushParameter(currentParameter)
                dispatchCsi(byte.toChar())
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun consumeCsiIgnore(byte: Int) {
        when (byte) {
            0x1B -> state = State.ESCAPE
            in 0x40..0x7E -> state = State.GROUND
        }
    }

    private fun dispatchCsi(finalByte: Char) {
        sink.onCsi(parameters, parameterCount, privateMarker, intermediate, finalByte)
        state = State.GROUND
    }

    private fun pushParameter(value: Int) {
        if (parameterCount < MAX_PARAMS) {
            parameters[parameterCount++] = value
        }
    }

    private fun beginOsc() {
        state = State.OSC_COMMAND
        oscCommand = 0
        hasOscCommand = false
        oscPayload.setLength(0)
    }

    private fun consumeOscCommand(byte: Int) {
        when (byte) {
            in 0x30..0x39 -> {
                oscCommand = oscCommand * 10 + (byte - 0x30)
                hasOscCommand = true
            }
            0x3B -> state = State.OSC_STRING
            0x07 -> finishOsc()
            0x1B -> {
                finishOsc()
                state = State.ESCAPE
            }
            else -> state = State.GROUND
        }
    }

    private fun consumeOscString(byte: Int) {
        when (byte) {
            0x07 -> finishOsc()
            0x1B -> {
                finishOsc()
                state = State.ESCAPE
            }
            in 0x20..0x7E, in 0x80..0xFF -> if (oscPayload.length < MAX_OSC_PAYLOAD) {
                oscPayload.append(byte.toChar())
            }
            else -> Unit
        }
    }

    private fun consumeStringIgnore(byte: Int) {
        when (byte) {
            0x07 -> state = State.GROUND
            0x1B -> state = State.ESCAPE
            0x9C -> state = State.GROUND
        }
    }

    private fun finishOsc() {
        sink.onOsc(if (hasOscCommand) oscCommand else 0, oscPayload.toString())
        state = State.GROUND
    }

    private fun decodeUtf8(bytes: ByteArray, length: Int): Int {
        val first = bytes[0].toInt() and 0xFF
        return when (length) {
            2 -> ((first and 0x1F) shl 6) or (bytes[1].toInt() and 0x3F)
            3 -> ((first and 0x0F) shl 12) or
                ((bytes[1].toInt() and 0x3F) shl 6) or
                (bytes[2].toInt() and 0x3F)
            4 -> ((first and 0x07) shl 18) or
                ((bytes[1].toInt() and 0x3F) shl 12) or
                ((bytes[2].toInt() and 0x3F) shl 6) or
                (bytes[3].toInt() and 0x3F)
            else -> -1
        }
    }

    private fun Int.inRange(range: IntRange): Boolean = this in range

    private companion object {
        const val NONE = '\u0000'
        const val MAX_PARAMS = 32
        const val MAX_PARAM_VALUE = 65535
        const val MAX_OSC_PAYLOAD = 4096
    }
}
