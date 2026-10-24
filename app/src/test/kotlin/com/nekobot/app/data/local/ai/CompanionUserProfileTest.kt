package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CompanionUserProfileTest {
    @Test fun conversionPreservesUserPersonaVerbatimInsideFirstPersonDiary() {
        val original = "我叫小舟。\n喜欢画画，不吃香菜；职业暂不透露。"
        val row = requireNotNull(CompanionUserProfile.converted(original, "scope", "2026-10-17"))
        assertEquals("我记得对方这样介绍自己：“$original”", row.content)
        assertEquals("user_profile", row.category)
        assertEquals("scope", row.scopeId)
        assertTrue(row.manual)
        assertNull(row.eventDate)
        assertFalse(row.content.contains("设计师"))
    }
    @Test fun missingPersonaProducesNoMemory() {
        assertNull(CompanionUserProfile.converted(null, "scope", "now"))
        assertNull(CompanionUserProfile.converted("  \n", "scope", "now"))
    }
    @Test fun repeatedConversionHasStableIdentityAndIsolatesScopes() {
        val a = CompanionUserProfile.converted("原文", "a", "now")!!
        assertEquals(a, CompanionUserProfile.converted("原文", "a", "now"))
        assertNotEquals(a.id, CompanionUserProfile.converted("原文", "b", "now")!!.id)
    }
    @Test fun ordinarySessionStillReceivesItsConfiguredPersona() = kotlinx.coroutines.runBlocking {
        val ctx = PipelineContext(ChatRequest.forLocal("s", "早上好")).apply {
            metadata["session_mode"] = "character"
            metadata["user_persona"] = "叫我小舟，我是设计师。"
            characterTurn = CharacterTurnContext(profile = CharacterProfile(name = "小满"), promptText = "我是小满。")
        }
        AIPipeline().prepareContext(ctx, object : PipelineCallbacks() {})
        assertTrue(ctx.messages.first()["content"].toString().contains("叫我小舟，我是设计师。"))
    }
}
