package com.nekobot.app.data.local.ai.time

import java.time.LocalDate

internal object JaRules {
    val rules = relativeRules(mapOf("今日" to 0, "明日" to 1, "明后日" to 2, "明々后日" to 3,
        "昨日" to -1, "一昨日" to -2, "一昨昨日" to -3, "きょう" to 0, "あした" to 1,
        "あさって" to 2, "しあさって" to 3, "きのう" to -1, "おととい" to -2, "さきおととい" to -3)) + listOf(
        DateRule("(去年|今年|来年)(?:の)?クリスマス") { m, d ->
            LocalDate.of(d.year + when (m.groupValues[1]) { "去年" -> -1; "来年" -> 1; else -> 0 }, 12, 25)
        },
        DateRule("(?:今から)?($CJK_NUMBER)(日|週間|周間|周|週)(前|后)") { m, d ->
            d.plusDays(m.n(1).toLong() * (if (m.groupValues[2] == "日") 1 else 7) * (if (m.groupValues[3] == "前") -1 else 1))
        },
        DateRule("(先周|今周|来周|再来周)(?:の)?([月火水木金土日])曜(?:日)?") { m, d ->
            TimeWordEngine.week(d, when (m.groupValues[1]) { "先周" -> -1; "来周" -> 1; "再来周" -> 2; else -> 0 }, "月火水木金土日".indexOf(m.groupValues[2]) + 1)
        },
        DateRule("来月(?:の)?($CJK_NUMBER)日") { m, d -> d.plusMonths(1).withDayOfMonth(m.n(1)) },
        DateRule("($CJK_NUMBER)年(?:の)?($CJK_NUMBER)月(?:の)?($CJK_NUMBER)日") { m, _ -> LocalDate.of(m.n(1), m.n(2), m.n(3)) },
        DateRule("($CJK_NUMBER)月(?:の)?($CJK_NUMBER)日") { m, d -> LocalDate.of(d.year, m.n(1), m.n(2)) }
    )
}
