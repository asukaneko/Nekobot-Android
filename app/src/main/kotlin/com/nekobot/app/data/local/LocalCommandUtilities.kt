package com.nekobot.app.data.local

import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** 不依赖 Android 或服务器的本地命令工具，保持可单元测试。 */
internal object LocalCommandUtilities {
    private val dicePattern =
        Regex("""(?i)^(?:(\d{1,3})?d(\d{1,5}))([+-]\d{1,7})?$""")
    private val passwordLower = "abcdefghijkmnopqrstuvwxyz"
    private val passwordUpper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val passwordDigits = "23456789"
    private val passwordSymbols = "!@#$%^&*()-_=+"
    private val passwordAlphabet =
        passwordLower + passwordUpper + passwordDigits + passwordSymbols

    fun rollDiceText(
        rawNotation: String,
        nextInt: (Int) -> Int = { bound -> Random.nextInt(bound) }
    ): String {
        val notation = rawNotation.trim().ifBlank { "1d6" }
        val match = dicePattern.matchEntire(notation)
            ?: throw IllegalArgumentException("格式：`/roll [数量d面数±修正]`，例如 `/roll 2d6+1`")
        val count = match.groupValues[1].ifBlank { "1" }.toInt()
        val sides = match.groupValues[2].toInt()
        val modifier = match.groupValues[3].ifBlank { "0" }.toInt()
        require(count in 1..100) { "骰子数量必须在 1 到 100 之间。" }
        require(sides in 2..10_000) { "骰子面数必须在 2 到 10000 之间。" }
        require(modifier in -1_000_000..1_000_000) { "修正值绝对值不能超过 1000000。" }

        val rolls = List(count) { nextInt(sides).coerceIn(0, sides - 1) + 1 }
        val total = rolls.sum() + modifier
        val shownRolls = rolls.take(30).joinToString(", ")
        val hidden = if (rolls.size > 30) " …（另有 ${rolls.size - 30} 个）" else ""
        val normalized = "${count}d$sides" + when {
            modifier > 0 -> "+$modifier"
            modifier < 0 -> modifier.toString()
            else -> ""
        }
        return buildString {
            appendLine("🎲 **$normalized = $total**")
            append("点数：$shownRolls$hidden")
            if (modifier != 0) append("\n修正：${if (modifier > 0) "+" else ""}$modifier")
        }
    }

    fun pickText(
        rawOptions: String,
        nextInt: (Int) -> Int = { bound -> Random.nextInt(bound) }
    ): String {
        val options = splitOptions(rawOptions)
        require(options.size >= 2) { "格式：`/pick 选项A | 选项B | 选项C`" }
        require(options.size <= 100) { "一次最多提供 100 个选项。" }
        val selected = options[nextInt(options.size).coerceIn(0, options.lastIndex)]
        return "🎯 我选：**$selected**"
    }

    fun calculate(expression: String): String {
        val raw = expression.trim()
        require(raw.isNotEmpty()) { "格式：`/calc <表达式>`，例如 `/calc (12+3)*4`" }
        require(raw.length <= 256) { "表达式不能超过 256 个字符。" }
        val value = Calculator(raw).parse()
        require(value.isFinite()) { "计算结果不是有限数字。" }
        return formatNumber(value)
    }

    fun generatePassword(
        requestedLength: String,
        secureRandom: SecureRandom = SecureRandom()
    ): String {
        val length = requestedLength.trim().ifBlank { "20" }.toIntOrNull()
            ?: throw IllegalArgumentException("格式：`/password [长度]`")
        require(length in 8..128) { "密码长度必须在 8 到 128 之间。" }

        val chars = mutableListOf(
            passwordLower.random(secureRandom),
            passwordUpper.random(secureRandom),
            passwordDigits.random(secureRandom),
            passwordSymbols.random(secureRandom)
        )
        repeat(length - chars.size) {
            chars += passwordAlphabet.random(secureRandom)
        }
        for (index in chars.lastIndex downTo 1) {
            val swapIndex = secureRandom.nextInt(index + 1)
            val temp = chars[index]
            chars[index] = chars[swapIndex]
            chars[swapIndex] = temp
        }
        return chars.joinToString("")
    }

    fun sha256(text: String): String {
        require(text.isNotEmpty()) { "格式：`/sha256 <文本>`" }
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun splitOptions(raw: String): List<String> {
        val delimiter = when {
            raw.contains('|') || raw.contains('｜') -> Regex("""[|｜]""")
            raw.contains('\n') -> Regex("""\r?\n""")
            else -> Regex("""[,，]""")
        }
        return raw.split(delimiter)
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun String.random(random: SecureRandom): Char = this[random.nextInt(length)]

    private fun formatNumber(value: Double): String {
        val rounded = value.toLong()
        if (abs(value - rounded.toDouble()) < 1e-12) return rounded.toString()
        return BigDecimal.valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
    }

    private class Calculator(private val input: String) {
        private var index = 0

        fun parse(): Double {
            val result = parseExpression()
            skipSpaces()
            if (index != input.length) error("无法识别 `${input.substring(index).take(12)}`")
            return result
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                value = when {
                    consume('+') -> value + parseTerm()
                    consume('-') -> value - parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                value = when {
                    consume('*') -> value * parsePower()
                    consume('/') -> {
                        val divisor = parsePower()
                        require(divisor != 0.0) { "不能除以零。" }
                        value / divisor
                    }
                    consume('%') -> {
                        val divisor = parsePower()
                        require(divisor != 0.0) { "不能对零取余。" }
                        value % divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            return if (consume('^')) base.pow(parsePower()) else base
        }

        private fun parseUnary(): Double = when {
            consume('+') -> parseUnary()
            consume('-') -> -parseUnary()
            else -> parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            if (consume('(')) {
                val value = parseExpression()
                require(consume(')')) { "缺少右括号 `)`。" }
                return value
            }
            if (index < input.length && (input[index].isDigit() || input[index] == '.')) {
                return parseNumber()
            }
            if (index < input.length && input[index].isLetter()) {
                val name = parseIdentifier().lowercase()
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> {
                        require(consume('(')) { "函数 `$name` 后需要括号。" }
                        val argument = parseExpression()
                        require(consume(')')) { "函数 `$name` 缺少右括号。" }
                        when (name) {
                            "sqrt" -> sqrt(argument)
                            "abs" -> abs(argument)
                            "sin" -> sin(argument)
                            "cos" -> cos(argument)
                            "tan" -> tan(argument)
                            "ln" -> ln(argument)
                            "log", "log10" -> log10(argument)
                            else -> error("不支持函数 `$name`")
                        }
                    }
                }
            }
            error("表达式在第 ${index + 1} 个字符处不完整。")
        }

        private fun parseNumber(): Double {
            skipSpaces()
            val start = index
            var hasExponent = false
            while (index < input.length) {
                val char = input[index]
                when {
                    char.isDigit() || char == '.' -> index++
                    (char == 'e' || char == 'E') && !hasExponent -> {
                        hasExponent = true
                        index++
                        if (index < input.length && (input[index] == '+' || input[index] == '-')) {
                            index++
                        }
                    }
                    else -> break
                }
            }
            return input.substring(start, index).toDoubleOrNull()
                ?: error("数字格式不正确。")
        }

        private fun parseIdentifier(): String {
            skipSpaces()
            val start = index
            while (index < input.length && input[index].isLetterOrDigit()) index++
            return input.substring(start, index)
        }

        private fun consume(expected: Char): Boolean {
            skipSpaces()
            if (index >= input.length || input[index] != expected) return false
            index++
            return true
        }

        private fun skipSpaces() {
            while (index < input.length && input[index].isWhitespace()) index++
        }

        private fun error(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
