package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CompanionRelativeDateTest {
    private val today = LocalDate.of(2026, 10, 17)
    private fun format(date: String?, day: LocalDate = today) = CompanionPromptLanguage.memory("我记得这件事。", date, today = day)
    @Test fun today() { assertEquals("今天：我记得这件事。", format("2026-10-17")) }
    @Test fun yesterday() { assertEquals("昨天：我记得这件事。", format("2026-10-16")) }
    @Test fun dayBeforeYesterday() { assertEquals("前天：我记得这件事。", format("2026-10-15")) }
    @Test fun earlierThisYear() { assertEquals("3月8日：我记得这件事。", format("2026-03-08")) }
    @Test fun otherYear() { assertEquals("2025年10月17日：我记得这件事。", format("2025-10-17")) }
    @Test fun futureYear() { assertEquals("2027年1月3日：我记得这件事。", format("2027-01-03")) }
    @Test fun newYearYesterday() { assertEquals("昨天：我记得这件事。", format("2025-12-31", LocalDate.of(2026, 1, 1))) }
    @Test fun newYearDayBeforeYesterday() { assertEquals("前天：我记得这件事。", format("2025-12-31", LocalDate.of(2026, 1, 2))) }
    @Test fun newYearEarlierDateHasYear() { assertEquals("2025年12月29日：我记得这件事。", format("2025-12-29", LocalDate.of(2026, 1, 1))) }
    @Test fun leapYearBoundary() { assertEquals("昨天：我记得这件事。", format("2024-02-29", LocalDate.of(2024, 3, 1))) }
    @Test fun missingInvalidAndAnnualDates() {
        assertEquals("我记得这件事。", format(null))
        assertEquals("我记得这件事。", format("2026-02-30"))
        assertEquals("5月20日：生日。", CompanionPromptLanguage.memory("生日。", annualMonthDay = "05-20", today = today))
    }
    @Test fun renderingNeverMutatesStoredDateOrContent() {
        val row = CompanionMemoryEntity("m", "s", "k", 1, "fact", "人", "我记得这件事。", "active",
            eventDate = "2026-10-17", createdAt = "2020-01-01", updatedAt = "2020-01-01")
        assertEquals("今天：我记得这件事。", CompanionPromptLanguage.memory(row, today))
        assertEquals("昨天：我记得这件事。", CompanionPromptLanguage.memory(row, today.plusDays(1)))
        assertEquals("2026-10-17", row.eventDate)
        assertEquals("我记得这件事。", row.content)
    }
    @Test fun recurringAnniversaryDoesNotBecomeAOneOffRelativeDate() {
        assertEquals("10月17日：生日。", CompanionPromptLanguage.memory("生日。", "2026-10-17", "10-17", today))
    }
}
