package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputLayoutModeTest {

    @Test
    fun fromStorage_defaultsInvalidValuesToMerged() {
        assertEquals(ChatInputLayoutMode.MERGED, ChatInputLayoutMode.fromStorage(null))
        assertEquals(ChatInputLayoutMode.MERGED, ChatInputLayoutMode.fromStorage("unknown"))
    }

    @Test
    fun fromStorage_restoresSeparateLayout() {
        assertEquals(ChatInputLayoutMode.SEPARATE, ChatInputLayoutMode.fromStorage("SEPARATE"))
    }
}
