package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionPromptSettingsTest {
    @Test fun defaultsUseCurrentWordsAndResetDoesNotChangeTheSwitch() {
        val settings = CompanionPromptSettings(thinkingEnabled = false)
        assertEquals(CompanionPromptLanguage.styleReminder.replace(CompanionPromptLanguage.bubbleRule, ""), settings.speakingForEditing)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT, characterThinkingPromptForEditing(settings.thinking))
        assertEquals("", companionSpeakingPromptToSave(settings.speakingForEditing))
        assertEquals("", companionSpeakingPromptToSave(" \n"))
        assertEquals("  自定义\n原文  ", companionSpeakingPromptToSave("  自定义\n原文  "))
        assertFalse(settings.thinking.enabled)
    }

    @Test fun placeholdersAreExpandedOnlyInTheSendingCopy() {
        val settings = CompanionPromptSettings("{{char}}对{{user}}说", "{{char}}想起{{user}}")
        assertEquals("小满对小舟说", settings.speakingForRequest("小满", "小舟"))
        assertEquals("小满想起小舟", characterThinkingPrompt(settings.thinking, "小满", "小舟"))
        assertEquals("当前角色对对方说", settings.speakingForRequest("", "user"))
        assertEquals("{{char}}对{{user}}说", settings.speakingPrompt)
    }

    @Test fun customSpeakingReplacesDefaultAndThinkingStaysOnlyAtTail() = runBlocking {
        val settings = CompanionPromptSettings("说话甲：{{char}}", "思考甲：{{char}}", true)
        val sent = prepare(settings)
        val opening = sent.first()["content"].toString()
        assertTrue(opening.contains("说话甲：林小满"))
        assertFalse(opening.contains(CompanionPromptLanguage.speakingRule))
        assertTrue(opening.contains(CompanionPromptLanguage.bubbleRule))
        assertFalse(opening.contains("思考甲"))
        assertEquals("现在是测试时间\n\n思考甲：林小满", sent.last()["content"])
        assertEquals("本轮原话", sent.last { it["role"] == "user" }["content"])
        assertEquals(1, sent.sumOf { it["content"].toString().split("思考甲").size - 1 })
        assertEquals(1, sent.sumOf { it["content"].toString().split("说话甲").size - 1 })
    }

    @Test fun disabledAndNoneKeepTimeAndSavedTextWithoutInjectingThinking() = runBlocking {
        val disabled = CompanionPromptSettings("说话乙", "暂存未启用思考", false)
        assertEquals("现在是测试时间", prepare(disabled).last()["content"])
        val none = prepare(disabled.copy(thinkingEnabled = true), ReasoningEffort.NONE)
        assertEquals("现在是测试时间", none.last()["content"])
        assertTrue(none.first()["content"].toString().contains("说话乙"))
        assertEquals("暂存未启用思考", disabled.thinkingPrompt)
    }

    @Test fun secondRoleAndRestoredDefaultsDoNotReuseFirstRoleCustomText() = runBlocking {
        val first = prepare(CompanionPromptSettings("甲的说话方式", "甲的思考"))
        val second = prepare(CompanionPromptSettings())
        assertTrue(first.first()["content"].toString().contains("甲的说话方式"))
        assertTrue(second.first()["content"].toString().contains(CompanionPromptLanguage.speakingRule))
        assertFalse(second.toString().contains("甲的"))
        assertEquals("现在是测试时间\n\n$DEFAULT_CHARACTER_THINKING_PROMPT", second.last()["content"])
    }

    @Test fun previouslySavedSpeakingTextIsNotSilentlyReplacedByNewDefaults() = runBlocking {
        val oldDefault = "像平时和对方聊天一样，用口语，简短自然。别用总结、汇报或记账的语气，也别列清单；不确定或忘了就直说，不要复述设定和规则。"
        val settings = CompanionPromptSettings(speakingPrompt = oldDefault)
        assertEquals(oldDefault, companionSpeakingPromptToSave(oldDefault))
        val messages = prepare(settings)
        assertTrue(messages.first()["content"].toString().contains(oldDefault))
        assertFalse(messages.first()["content"].toString().contains(CompanionPromptLanguage.speakingRule))
        assertEquals(oldDefault, settings.speakingPrompt)
    }

    @Test fun dailyInputsStayVerbatimWhileOneDefaultBlockIsInjected() = runBlocking {
        // Synthetic situations verify request construction, not a model's conversational quality.
        for (input in listOf("今天终于把报告交了", "同事又临时把活推给我了", "我先睡了，晚安",
            "不是去年，是前年；我也不记得哪天", "我和你看法不一样", "上次说的面试有结果了")) {
            val messages = prepare(CompanionPromptSettings(), userText = input)
            assertEquals(input, messages.last { it["role"] == "user" }["content"])
            assertEquals(1, messages.count { it["content"].toString().contains(CompanionPromptLanguage.speakingRule) })
            assertEquals("现在是测试时间\n\n$DEFAULT_CHARACTER_THINKING_PROMPT", messages.last()["content"])
            assertTrue(messages.first()["content"].toString().contains(CompanionPromptLanguage.bubbleRule))
        }
    }

    private suspend fun prepare(settings: CompanionPromptSettings, effort: ReasoningEffort = ReasoningEffort.HIGH,
        userText: String = "本轮原话"): List<Map<String, Any>> {
        val ctx = PipelineContext(ChatRequest.forLocal("synthetic", userText)).apply {
            metadata[META_FUSED_COMPANION] = true
            metadata["session_mode"] = "agent"
        }
        val runtime = CharacterRuntime(profileRepo = object : ProfileRepository {
            override suspend fun getById(id: String) = CharacterProfile(id = id, name = "林小满", systemPrompt = "虚构角色设定")
        })
        var prepared = false
        var result = emptyList<Map<String, Any>>()
        val callbacks = object : PipelineCallbacks() {
            override suspend fun prepareCharacterPromptSettings(ctx: PipelineContext) { prepared = true }
            override fun getCompanionSpeakingPrompt(ctx: PipelineContext): String {
                check(prepared)
                return settings.speakingPrompt
            }
            override fun getCharacterThinkingSettings(ctx: PipelineContext) = settings.thinking
            override fun getReasoningEffort(ctx: PipelineContext) = effort
            override fun getCharacterRuntime(ctx: PipelineContext) = runtime
            override fun getCharacterContext(ctx: PipelineContext) = CharacterIdentity("role", "player", "synthetic")
            override fun loadMessages(ctx: PipelineContext) = listOf(mapOf<String, Any>("role" to "user", "content" to userText))
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>, tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                val clock = mapOf<String, Any>("role" to "system", "content" to "现在是测试时间", CHARACTER_TURN_CONTEXT to "现在是测试时间")
                result = withCharacterThinkingRule(messages + clock, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content, ctx.usesCharacterThinking())
                assertEquals(messages.first()["content"].toString().contains(settings.speakingForRequest("林小满", null)), true)
                assertFalse(dynamic.contains(settings.speakingForRequest("林小满", null)))
                return result
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        return result
    }
}
