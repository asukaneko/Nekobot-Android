package com.nekobot.app.data.local.ai.time

import java.time.LocalDate

internal object KoRules {
    val rules = relativeRules(mapOf("오늘" to 0, "내일" to 1, "모레" to 2, "내일 모레" to 2, "글피" to 3,
        "어제" to -1, "그저께" to -2, "그제" to -2, "그끄저께" to -3)) + listOf(
        DateRule("(이틀|사흘|나흘)\\s*(전|후|뒤)") { m, d ->
            d.plusDays((when (m.groupValues[1]) { "이틀" -> 2L; "사흘" -> 3L; else -> 4L }) *
                if (m.groupValues[2] == "전") -1 else 1)
        },
        DateRule("(작년|올해|내년)\\s*크리스마스") { m, d ->
            LocalDate.of(d.year + when (m.groupValues[1]) { "작년" -> -1; "내년" -> 1; else -> 0 }, 12, 25)
        },
        DateRule("(?:지금으로부터 )?(\\d+|한|두|세)\\s*(일|주|주일)\\s*(전|후|뒤)") { m, d ->
            d.plusDays(m.n(1).toLong() * (if (m.groupValues[2] == "일") 1 else 7) * (if (m.groupValues[3] == "전") -1 else 1))
        },
        DateRule("(지난|이번|다음|다다음)\\s*주\\s*([월화수목금토일])요일") { m, d ->
            TimeWordEngine.week(d, when (m.groupValues[1]) { "지난" -> -1; "다음" -> 1; "다다음" -> 2; else -> 0 }, "월화수목금토일".indexOf(m.groupValues[2]) + 1)
        },
        DateRule("다음\\s*달\\s*(\\d{1,2})일") { m, d -> d.plusMonths(1).withDayOfMonth(m.n(1)) },
        DateRule("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일") { m, _ -> LocalDate.of(m.n(1), m.n(2), m.n(3)) },
        DateRule("(\\d{1,2})월\\s*(\\d{1,2})일") { m, d -> LocalDate.of(d.year, m.n(1), m.n(2)) }
    )
}
