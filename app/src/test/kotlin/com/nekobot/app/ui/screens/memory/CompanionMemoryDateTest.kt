package com.nekobot.app.ui.screens.memory

import com.nekobot.app.R
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CompanionMemoryDateTest {
    private val today = LocalDate.of(2026, 10, 23)
    @Test fun todayAndYesterdayUseLocalizedLabels() {
        assertEquals(MemoryDateLabel(R.string.chat_today), memoryDateLabel("2026-10-23", today))
        assertEquals(MemoryDateLabel(R.string.chat_yesterday), memoryDateLabel("2026-10-22", today))
    }
    @Test fun sameYearOmitsYearAndCrossYearKeepsIt() {
        assertEquals(MemoryDateLabel(R.string.chat_month_day, listOf(10, 21)), memoryDateLabel("2026-10-21", today))
        assertEquals(MemoryDateLabel(R.string.chat_year_month_day, listOf(2025, 12, 31)), memoryDateLabel("2025-12-31", today))
    }
    @Test fun yesterdayRemainsRelativeAcrossNewYearAndLeapDay() {
        assertEquals(R.string.chat_yesterday, memoryDateLabel("2025-12-31", LocalDate.of(2026, 1, 1))!!.resource)
        assertEquals(R.string.chat_yesterday, memoryDateLabel("2024-02-29", LocalDate.of(2024, 3, 1))!!.resource)
    }
    @Test fun incompleteOrInvalidDatesStayUnchangedInsteadOfInventingADay() {
        for (value in listOf("", "02-29", "2026-02-30", "月底")) assertNull(memoryDateLabel(value, today))
    }
}
