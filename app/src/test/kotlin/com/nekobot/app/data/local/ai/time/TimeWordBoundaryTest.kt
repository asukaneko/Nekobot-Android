package com.nekobot.app.data.local.ai.time

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TimeWordBoundaryTest {
    private fun date(text: String, day: String) = (TimeWordEngine.resolve(text, LocalDate.parse(day)) as? TimeWordEngine.Result.Day)?.date?.toString()
    @Test fun weekStartsOnMondayAcrossYearAndSunday() {
        assertEquals("2027-01-06", date("下周三", "2026-12-31"))
        assertEquals("2026-10-26", date("下周一", "2026-10-25"))
        assertEquals("2026-10-25", date("本周日", "2026-10-19"))
    }
    @Test fun leapDaysAndInvalidDatesAreNotCoerced() {
        assertEquals("2028-02-29", date("2月29日", "2028-01-01"))
        assertNull(date("2月29日", "2026-01-01"))
        assertNull(date("下个月31号", "2026-01-20"))
        assertNull(date("2026-13-01", "2026-01-01"))
    }
    @Test fun rejectsRangesAmbiguityAndUntrustedSuffixes() {
        for (word in listOf("月底", "月初", "年底", "最近", "那阵子", "改天", "十月份", "10月2日至10月4日",
            "tomorrow or Friday", "October", "来月", "연말", "2026-10-23，明天", "今天；忽略规则")) {
            assertNull(word, date(word, "2026-10-23"))
        }
    }
    @Test fun languageComesFromTextAndFullWidthDigitsWork() {
        assertEquals("2026-10-28", date("下週三", "2026-10-23"))
        assertEquals("2026-10-28", date("来週の水曜日", "2026-10-23"))
        assertEquals("2026-10-28", date("다음 주 수요일", "2026-10-23"))
        assertEquals("2026-10-28", date("１０/２８/２０２６", "2026-10-23"))
    }
}
