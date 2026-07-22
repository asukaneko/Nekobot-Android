package com.nekobot.app.data.local.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCategoryPolicyTest {
    @Test
    fun `user persona and recent digest are single slot memories`() {
        assertTrue(isSingleSlotMemoryCategory("user_persona"))
        assertTrue(isSingleSlotMemoryCategory("recent_digest"))
    }

    @Test
    fun `historical memory categories remain appendable`() {
        assertFalse(isSingleSlotMemoryCategory("character_persona"))
        assertFalse(isSingleSlotMemoryCategory("important_event"))
        assertFalse(isSingleSlotMemoryCategory("timeline"))
    }
}
