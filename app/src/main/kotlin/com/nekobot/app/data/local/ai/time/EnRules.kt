package com.nekobot.app.data.local.ai.time

import java.time.LocalDate

internal object EnRules {
    private val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private fun month(s: String) = months.indexOfFirst { it.startsWith(s.removeSuffix(".")) } + 1
    private const val NUMBER = "(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten)"
    private val weekday = weekdays.joinToString("|")
    private val monthName = months.flatMap { listOf(it, it.take(3) + "\\.?") }.joinToString("|")
    val numeric = listOf(
        DateRule("(\\d{1,2})/(\\d{1,2})/(\\d{4})") { m, _ -> LocalDate.of(m.n(3), m.n(1), m.n(2)) },
        DateRule("(\\d{4})-(\\d{1,2})-(\\d{1,2})") { m, _ -> LocalDate.of(m.n(1), m.n(2), m.n(3)) }
    )
    val rules = relativeRules(mapOf("today" to 0, "tomorrow" to 1, "the day after tomorrow" to 2,
        "day after tomorrow" to 2, "three days from today" to 3, "yesterday" to -1,
        "the day before yesterday" to -2, "day before yesterday" to -2, "three days before today" to -3)) + numeric + listOf(
        DateRule("christmas (last|this|next) year") { m, d ->
            LocalDate.of(d.year + when (m.groupValues[1]) { "last" -> -1; "next" -> 1; else -> 0 }, 12, 25)
        },
        DateRule("(last|this|next) year's christmas") { m, d ->
            LocalDate.of(d.year + when (m.groupValues[1]) { "last" -> -1; "next" -> 1; else -> 0 }, 12, 25)
        },
        DateRule("in ($NUMBER) (days?|weeks?)") { m, d -> d.plusDays(m.n(1).toLong() * if (m.groupValues[2].startsWith("week")) 7 else 1) },
        DateRule("($NUMBER) (days?|weeks?) (ago|later|from now)") { m, d -> d.plusDays(m.n(1).toLong() *
            (if (m.groupValues[2].startsWith("week")) 7 else 1) * (if (m.groupValues[3] == "ago") -1 else 1)) },
        DateRule("(last|this|next|next next) (?:week (?:on )?)?($weekday)") { m, d ->
            TimeWordEngine.week(d, when (m.groupValues[1]) { "last" -> -1; "next" -> 1; "next next" -> 2; else -> 0 }, weekdays.indexOf(m.groupValues[2]) + 1)
        },
        DateRule("($weekday) (last|this|next) week") { m, d ->
            TimeWordEngine.week(d, when (m.groupValues[2]) { "last" -> -1; "next" -> 1; else -> 0 }, weekdays.indexOf(m.groupValues[1]) + 1)
        },
        DateRule("(?:the )?(\\d{1,2})(?:st|nd|rd|th)? of next month") { m, d -> d.plusMonths(1).withDayOfMonth(m.n(1)) },
        DateRule("next month (\\d{1,2})(?:st|nd|rd|th)?") { m, d -> d.plusMonths(1).withDayOfMonth(m.n(1)) },
        DateRule("($monthName) (\\d{1,2})(?:st|nd|rd|th)?(?:,? (\\d{4}))?") { m, d ->
            LocalDate.of(m.groupValues[3].toIntOrNull() ?: d.year, month(m.groupValues[1]), m.n(2))
        },
        DateRule("(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($monthName)(?:,? (\\d{4}))?") { m, d ->
            LocalDate.of(m.groupValues[3].toIntOrNull() ?: d.year, month(m.groupValues[2]), m.n(1))
        }
    )
}
