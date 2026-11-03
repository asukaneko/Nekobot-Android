package com.nekobot.app.data.local.ai.time

import java.time.LocalDate

internal object ZhRules {
    val rules = relativeRules(mapOf("今天" to 0, "今日" to 0, "明天" to 1, "明日" to 1,
        "后天" to 2, "大后天" to 3, "昨天" to -1, "昨日" to -1, "前天" to -2, "大前天" to -3)) + listOf(
        DateRule("(去年|今年|明年)(?:的)?(?:圣诞|聖誕)(?:节|節)?") { m, d ->
            LocalDate.of(d.year + when (m.groupValues[1]) { "去年" -> -1; "明年" -> 1; else -> 0 }, 12, 25)
        },
        DateRule("($CJK_NUMBER)\\s*(天|日|周|星期|个星期)\\s*(前|后)") { m, d ->
            d.plusDays(m.n(1).toLong() * (if (m.groupValues[2] in listOf("天", "日")) 1 else 7) * (if (m.groupValues[3] == "前") -1 else 1))
        },
        DateRule("(上上|上|这|这个|本|下下|下)(?:个)?(?:周|星期|礼拜)([一二三四五六日天1-7])") { m, d ->
            val offset = when (m.groupValues[1]) { "上上" -> -2; "上" -> -1; "下" -> 1; "下下" -> 2; else -> 0 }
            val weekday = when (m.groupValues[2]) { "日", "天" -> 7; else -> TimeWordEngine.number(m.groupValues[2]) }
            TimeWordEngine.week(d, offset, weekday)
        },
        DateRule("下个?月\\s*($CJK_NUMBER)[日号]") { m, d -> d.plusMonths(1).withDayOfMonth(m.n(1)) },
        DateRule("($CJK_NUMBER)年\\s*($CJK_NUMBER)月\\s*($CJK_NUMBER)[日号]?") { m, _ -> LocalDate.of(m.n(1), m.n(2), m.n(3)) },
        DateRule("($CJK_NUMBER)月\\s*($CJK_NUMBER)[日号]?") { m, d -> LocalDate.of(d.year, m.n(1), m.n(2)) },
        DateRule("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})") { m, _ -> LocalDate.of(m.n(1), m.n(2), m.n(3)) }
    )
}
