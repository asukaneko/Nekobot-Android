package com.nekobot.app.ui.screens.settings

import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavTimeFormatterTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun isoAndHttpDatesDisplayTheSameLocalTime() {
        listOf(
            "2026-10-09T03:34:05.123456789Z",
            "2026-10-09T05:34:05.123456+02:00",
            "2026-10-09T11:34:05+08:00",
            "2026-10-09 03:34:05Z",
            "Fri, 09 Oct 2026 03:34:05 GMT"
        ).forEach { raw ->
            assertEquals(raw, "2026-10-09 11:34:05", formatWebDavTimestamp(raw, shanghai))
        }
    }

    @Test
    fun usesDestinationZoneAndCrossesDateBoundaries() {
        assertEquals(
            "2026-10-10 01:34:05",
            formatWebDavTimestamp("2026-10-09T17:34:05Z", shanghai)
        )
        assertEquals(
            "2026-10-08 20:34:05",
            formatWebDavTimestamp("2026-10-09T03:34:05Z", ZoneId.of("America/Los_Angeles"))
        )
    }

    @Test
    fun appliesDaylightSavingRulesAtTheRecordedInstant() {
        val newYork = ZoneId.of("America/New_York")
        assertEquals("2026-01-09 07:00:00", formatWebDavTimestamp("2026-01-09T12:00:00Z", newYork))
        assertEquals("2026-07-09 08:00:00", formatWebDavTimestamp("2026-07-09T12:00:00Z", newYork))
    }

    @Test
    fun readsTheCurrentPhoneZoneOnEachCall() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("2026-10-09 12:34:05", formatWebDavTimestamp("2026-10-09T03:34:05Z"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("2026-10-08 20:34:05", formatWebDavTimestamp("2026-10-09T03:34:05Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun formatsLegacyDatesWithoutGuessingTheirOriginalZone() {
        assertEquals("2026-10-09 11:34:05", formatWebDavTimestamp("2026-10-09T11:34:05.123456", shanghai))
        assertEquals("2026-10-09 11:34:05", formatWebDavTimestamp("2026-10-09 11:34:05", ZoneId.of("America/New_York")))
    }

    @Test
    fun handlesMissingAndUnexpectedValuesWithoutCrashing() {
        assertNull(formatWebDavTimestamp(null, shanghai))
        assertNull(formatWebDavTimestamp("  ", shanghai))
        assertEquals("2026-10-09 11:34:05", formatWebDavTimestamp(" 2026-10-09T03:34:05Z ", shanghai))
        assertEquals("unavailable", formatWebDavTimestamp("unavailable", shanghai))
    }
}
