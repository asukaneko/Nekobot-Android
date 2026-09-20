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
    fun groupSessionUsesPipelineWithoutSingleBoundCharacter() {
        assertTrue(shouldUseLocalPipeline(sessionMode = "group", hasCharacter = false))
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

    @Test
    fun agentSessionNeverInjectsWorldBooks() {
        assertFalse(shouldInjectWorldBooks("agent"))
        assertFalse(shouldInjectWorldBooks("AGENT"))
        assertTrue(shouldInjectWorldBooks("character"))
        assertTrue(shouldInjectWorldBooks("group"))
    }

    @Test
    fun agentSessionInheritingCharacterInjectsWorldBooks() {
        assertTrue(shouldInjectWorldBooks("agent", inheritCharacter = true))
        assertTrue(shouldInjectWorldBooks("AGENT", inheritCharacter = true))
    }

    @Test
    fun onlyAgentSessionsCanInheritCharacter() {
        assertTrue(inheritsCharacter("agent", true))
        assertTrue(inheritsCharacter("AGENT", true))
        assertFalse(inheritsCharacter("agent", false))
        assertFalse(inheritsCharacter("agent", null))
        assertFalse(inheritsCharacter("character", true))
        assertFalse(inheritsCharacter("group", true))
    }
}
