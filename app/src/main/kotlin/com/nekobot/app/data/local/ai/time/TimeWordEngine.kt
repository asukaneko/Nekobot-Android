package com.nekobot.app.data.local.ai.time

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** 只解释完整的单日时间词。范围、模糊词和复合日期交给调用方处理。 */
internal object TimeWordEngine {
    sealed interface Result {
        data class Day(val date: LocalDate) : Result
        data object Unresolved : Result
    }

    fun resolve(timeText: String, spokenDay: LocalDate): Result {
        val text = normalize(timeText)
        val rules = when {
            text.any { it in '\uac00'..'\ud7af' } -> KoRules.rules
            text.any { it in 'a'..'z' } -> EnRules.rules
            text.any { it in '\u3040'..'\u30ff' } -> JaRules.rules
            else -> ZhRules.rules + JaRules.rules + EnRules.numeric
        }
        val dates = rules.mapNotNull { rule ->
            val match = rule.pattern.matchEntire(text) ?: return@mapNotNull null
            runCatching { rule.date(match, spokenDay) }.getOrNull()
        }.distinct()
        return dates.singleOrNull()?.let(Result::Day) ?: Result.Unresolved
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        .replace("禮拜", "礼拜").replace('週', '周').replace('後', '后').replace('個', '个').replace('號', '号').replace('兩', '两')

    internal fun week(day: LocalDate, offset: Int, weekday: Int): LocalDate =
        day.minusDays((day.dayOfWeek.value - 1).toLong()).plusWeeks(offset.toLong()).plusDays((weekday - 1).toLong())

    internal fun number(value: String): Int {
        value.toIntOrNull()?.let { return it }
        val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "한" to 1, "두" to 2, "세" to 3)
        words[value]?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value.all { it in digits }) return value.fold(0) { n, c -> n * 10 + digits.getValue(c) }
        var total = 0
        var digit = 0
        for (c in value) {
            if (c in digits) digit = digits.getValue(c)
            else {
                val unit = when (c) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> error("不是整数") }
                total += (if (digit == 0) 1 else digit) * unit
                digit = 0
            }
        }
        return total + digit
    }
}

internal class DateRule(pattern: String, val date: (MatchResult, LocalDate) -> LocalDate) {
    val pattern = Regex(pattern)
}
internal const val CJK_NUMBER = "[0-9零〇一二两三四五六七八九十百千]+"
internal fun MatchResult.n(index: Int) = TimeWordEngine.number(groupValues[index])
internal fun relativeRules(values: Map<String, Int>) = values.map { (word, days) ->
    DateRule(Regex.escape(word)) { _, day -> day.plusDays(days.toLong()) }
}
