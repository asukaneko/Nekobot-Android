package com.nekobot.app.ui.screens.plot

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryGraphTimeFormatterTest {

    @Test
    fun convertsUtcTimestampToRequestedSystemZone() {
        assertEquals(
            "2026-08-04 11:00:00",
            formatPlotNodeTimestamp("2026-08-04T03:00:00Z", ZoneId.of("Asia/Shanghai"))
        )
    }

    @Test
    fun convertsOffsetTimestampToRequestedSystemZone() {
        assertEquals(
            "2026-08-04 11:00:00",
            formatPlotNodeTimestamp("2026-08-04T05:00:00+02:00", ZoneId.of("Asia/Shanghai"))
        )
    }
}
