package com.nekobot.app.data.local.ai.time

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TimeWordReportedRegressionTest {
    @Test fun relativeChristmasUsesOriginalMessageYearAcrossYearBoundary() {
        for (spoken in listOf("2026-10-23", "2026-01-01", "2020-12-31")) {
            val day = LocalDate.parse(spoken)
            for (word in listOf("去年圣诞节", "去年的聖誕節", "去年のクリスマス", "작년 크리스마스", "christmas last year")) {
                assertEquals(word, TimeWordEngine.Result.Day(LocalDate.of(day.year - 1, 12, 25)), TimeWordEngine.resolve(word, day))
            }
        }
    }
    @Test fun koreanNativeDayCountsHandleLeapDayAndYearBoundary() {
        assertEquals(TimeWordEngine.Result.Day(LocalDate.parse("2024-02-29")), TimeWordEngine.resolve("이틀 전", LocalDate.parse("2024-03-02")))
        assertEquals(TimeWordEngine.Result.Day(LocalDate.parse("2025-12-30")), TimeWordEngine.resolve("이틀 전", LocalDate.parse("2026-01-01")))
        assertEquals(TimeWordEngine.Result.Day(LocalDate.parse("2026-01-04")), TimeWordEngine.resolve("사흘 후", LocalDate.parse("2026-01-01")))
        for (word in listOf("那年", "上次", "그때", "last time")) assertEquals(TimeWordEngine.Result.Unresolved, TimeWordEngine.resolve(word, LocalDate.parse("2026-10-23")))
    }
}
