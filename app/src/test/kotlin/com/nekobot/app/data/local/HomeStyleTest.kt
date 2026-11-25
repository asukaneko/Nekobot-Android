package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStyleTest {

    @Test
    fun fromStorage_defaultsToModeCards() {
        assertEquals(HomeStyle.MODE_CARDS, HomeStyle.fromStorage(null))
        assertEquals(HomeStyle.MODE_CARDS, HomeStyle.fromStorage(""))
        assertEquals(HomeStyle.MODE_CARDS, HomeStyle.fromStorage("unknown"))
    }

    @Test
    fun fromStorage_restoresSessionsList() {
        assertEquals(HomeStyle.SESSIONS, HomeStyle.fromStorage("SESSIONS"))
    }

    @Test
    fun fromStorage_restoresModeCards() {
        assertEquals(HomeStyle.MODE_CARDS, HomeStyle.fromStorage("MODE_CARDS"))
    }
}
