package com.nekobot.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPipelineRoutingTest {

    @Test
    fun agentSessionUsesPipelineWithoutCharacter() {
        assertTrue(shouldUseLocalPipeline(sessionMode = "agent", hasCharacter = false))
    }

    @Test
    fun characterSessionWithoutCharacterKeepsLegacyFlow() {
        assertFalse(shouldUseLocalPipeline(sessionMode = "character", hasCharacter = false))
    }

    @Test
    fun boundCharacterAlwaysUsesPipeline() {
        assertTrue(shouldUseLocalPipeline(sessionMode = "character", hasCharacter = true))
    }

    @Test
    fun imageAttachmentForcesPipelineWithoutCharacter() {
        assertTrue(
            shouldUseLocalPipeline(
                sessionMode = "character",
                hasCharacter = false,
                hasAttachments = true
            )
        )
    }
}
