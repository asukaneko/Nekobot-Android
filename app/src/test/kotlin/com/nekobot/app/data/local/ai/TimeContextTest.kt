package com.nekobot.app.data.local.ai

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeContextTest {

    @Test
    fun utcPreviousTimeIsConvertedToDeviceZoneBeforeDisplayAndElapsedCalculation() {
        val context = TimeContext.buildRealTimeContext(
            previousTurnTime = "2026-07-22T04:00:00Z",
            currentTime = LocalDateTime.of(2026, 7, 22, 12, 5, 0),
            zoneId = ZoneId.of("Asia/Shanghai")
        )

        assertEquals("2026-07-22T12:00:00+08:00", context["previous_turn_time"])
        assertEquals(300L, context["elapsed_seconds"])
        assertEquals("5分钟", context["elapsed_label"])
    }

    @Test
    fun legacyNaiveTimeIsTreatedAsLocalTime() {
        val context = TimeContext.buildRealTimeContext(
            previousTurnTime = "2026-07-22T11:00:00",
            currentTime = LocalDateTime.of(2026, 7, 22, 12, 0, 0),
            zoneId = ZoneId.of("Asia/Shanghai")
        )

        assertEquals("2026-07-22T11:00:00+08:00", context["previous_turn_time"])
        assertEquals(3600L, context["elapsed_seconds"])
    }
}
