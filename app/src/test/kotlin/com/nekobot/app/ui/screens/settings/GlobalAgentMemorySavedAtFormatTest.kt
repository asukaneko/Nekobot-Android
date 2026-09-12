package com.nekobot.app.ui.screens.settings

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalAgentMemorySavedAtFormatTest {

    @Test
    fun convertsUtcTimestampToRequestedSystemZone() {
        assertEquals(
            "2026-08-04 11:00",
            formatGlobalMemorySavedAt("2026-08-04T03:00:00Z", ZoneId.of("Asia/Shanghai"))
        )
    }

    @Test
    fun convertsOffsetTimestampToRequestedSystemZone() {
        assertEquals(
            "2026-08-04 11:00",
            formatGlobalMemorySavedAt("2026-08-04T05:00:00+02:00", ZoneId.of("Asia/Shanghai"))
        )
    }

    @Test
    fun keepsValueWhenTimestampHasNoZone() {
        assertEquals(
            "2026-08-04 09:30",
            formatGlobalMemorySavedAt("2026-08-04T09:30:00", ZoneId.of("Asia/Shanghai"))
        )
    }

    @Test
    fun returnsNullForBlankTimestamp() {
        assertNull(formatGlobalMemorySavedAt(null))
        assertNull(formatGlobalMemorySavedAt("   "))
    }
}
