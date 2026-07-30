package com.nekobot.app.data.local.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalScheduleCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val base = ZonedDateTime.of(2026, 7, 30, 10, 0, 0, 0, zone)

    @Test
    fun intervalUsesConfiguredMinutes() {
        val next = LocalScheduleCalculator.nextRun(
            trigger = "interval",
            configJson = """{"interval_minutes":15}""",
            from = base
        )

        assertEquals(base.plusMinutes(15).toInstant(), next)
    }

    @Test
    fun cronFindsNextMatchingMinute() {
        val next = LocalScheduleCalculator.nextCron("*/10 9-11 * * *", base)

        assertEquals(base.plusMinutes(10), next)
    }

    @Test
    fun runAtAcceptsLocalDateTime() {
        val next = LocalScheduleCalculator.nextRun(
            trigger = "run_at",
            configJson = """{"run_at":"2026-07-30 18:30"}""",
            from = base
        )

        assertEquals(
            ZonedDateTime.of(2026, 7, 30, 18, 30, 0, 0, zone).toInstant(),
            next
        )
    }

    @Test
    fun invalidCronIsRejected() {
        assertNull(LocalScheduleCalculator.nextCron("not a cron", base))
    }
}
