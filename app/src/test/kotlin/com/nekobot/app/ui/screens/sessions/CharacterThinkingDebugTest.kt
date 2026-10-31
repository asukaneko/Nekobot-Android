package com.nekobot.app.ui.screens.sessions

import android.content.SharedPreferences
import com.nekobot.app.data.local.CharacterThinkingPreferences
import com.nekobot.app.data.local.ReasoningPreferences
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.Session
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CharacterThinkingDebugTest {
    private val session = Session(id = "s", characterId = "role", characterName = "林小满",
        sessionMode = "agent", companionMode = true)
    private val other = PromptStackItem("character.relationship", "实际关系记录", priority = 50, enabled = false)
    private val old = PromptStackItem(CHARACTER_THINKING_STYLE_KEY, "旧思考文案", priority = 21, scope = "session")

    @Test
    fun flippingSwitchUpdatesAlreadyObservingStackWithoutSendingAnyMessage() = runBlocking {
        val saved = mutableMapOf<String, Any>("reasoning_effort_agent" to "high", "show_reasoning" to false)
        val prefs = preferences(saved)
        val thinking = CharacterThinkingPreferences(prefs)
        val reasoning = ReasoningPreferences(prefs)
        val recorded = listOf(other, old)
        val observed = mutableListOf<List<PromptStackItem>>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(thinking.settings, reasoning.changes) { current, _ ->
                liveCharacterThinkingDebugItems(recorded, session, current,
                    reasoning.effort("p", "s", true, "agent"), "林小满")
            }.collect { observed += it }
        }
        repeat(3) { yield() }
        thinking.enabled = false
        repeat(3) { yield() }
        thinking.enabled = true
        repeat(3) { yield() }
        thinking.customPrompt = "我想起{{char}}的约定。\n\n对{{user}}说什么呢？"
        repeat(3) { yield() }
        reasoning.setEffort("p", "s", true, "agent", ReasoningEffort.NONE)
        repeat(3) { yield() }
        reasoning.setEffort("p", "s", true, "agent", ReasoningEffort.HIGH)
        repeat(3) { yield() }
        job.cancel()

        assertEquals(listOf(true, false, true, true, false, true), observed.map { rows -> rows.any { it.key == CHARACTER_THINKING_STYLE_KEY } })
        assertEquals("我想起林小满的约定。\n\n对对方说什么呢？", observed.last().first().content)
        observed.forEach { assertSame(other, it.last()) }
        assertEquals(listOf(other, old), recorded)
        assertEquals(false, saved["show_reasoning"])
    }

    @Test
    fun noPriorChatStillShowsEnabledCurrentDefaultAndHidesItImmediatelyWhenOff() {
        val active = liveCharacterThinkingDebugItems(emptyList(), session, CharacterThinkingSettings(), ReasoningEffort.HIGH, "林小满")
        assertEquals(1, active.size)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "林小满").replace("{{user}}", "对方"), active.single().content)
        assertEquals("system", active.single().role)
        assertEquals("session", active.single().scope)
        assertEquals(PromptStack.Priority.BEHAVIOR + 1, active.single().priority)
        assertTrue(liveCharacterThinkingDebugItems(active, session, CharacterThinkingSettings(false), ReasoningEffort.HIGH, "林小满").isEmpty())
    }

    @Test
    fun ordinaryDebugMatchesInjectionWhileCompanionChangesOnlyModelText() = runBlocking {
        val sessions = listOf(session,
            Session(characterId = "role", sessionMode = "character"),
            Session(characterId = "role", sessionMode = "agent", inheritCharacter = true),
            Session(characterId = "role", sessionMode = "agent"), Session(sessionMode = "character"))
        for (s in sessions) for (enabled in listOf(true, false)) for (effort in listOf(ReasoningEffort.HIGH, ReasoningEffort.NONE, ReasoningEffort.CUSTOM)) {
            val settings = CharacterThinkingSettings(enabled, "我是{{char}}，用中文想{{user}}。")
            val ctx = PipelineContext(ChatRequest.forLocal("test", "哈哈")).apply {
                metadata["session_mode"] = s.sessionMode.orEmpty()
                metadata["reasoning_effort"] = effort.wireValue
                if (s.inheritCharacter == true) metadata[com.nekobot.app.data.local.META_INHERIT_CHARACTER] = true
                if (s.companionMode == true) metadata[META_FUSED_COMPANION] = true
                if (!s.characterId.isNullOrBlank()) characterTurn = CharacterTurnContext(
                    profile = CharacterProfile(id = "role", name = "林小满"), promptText = "角色卡")
            }
            val callbacks = object : PipelineCallbacks() {
                override fun getCharacterThinkingSettings(ctx: PipelineContext) = settings
                override fun getCharacterContext(ctx: PipelineContext) = s.characterId?.let { CharacterIdentity(characterId = it) }
                override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall = error("设置调试不得调用模型")
            }
            AIPipeline().prepareContext(ctx, callbacks)
            val debug = liveCharacterThinkingDebugItems(listOf(old, other), s, settings, effort, "林小满")
                .firstOrNull { it.key == CHARACTER_THINKING_STYLE_KEY }
            val actual = ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)
            if (s.companionMode == true && actual != null) {
                // 本次范围只改模型收到的文本，调试页显示文案按要求保留。
                assertEquals(characterThinkingPrompt(settings, "林小满", null, companionMode = true), actual.content)
                assertEquals(characterThinkingPrompt(settings, "林小满", null), debug?.content)
            } else assertEquals(actual?.content, debug?.content)
            assertEquals(actual?.priority, debug?.priority)
            assertEquals(actual?.role, debug?.role)
            assertEquals(actual?.scope, debug?.scope)
        }
    }

    @Test
    fun repeatedProjectionDoesNotDuplicateStyleOrChangeAnyRecordedRuntimeFields() {
        val settings = CharacterThinkingSettings(customPrompt = "我慢慢想。")
        var projected = listOf(old, old, other)
        repeat(4) { projected = liveCharacterThinkingDebugItems(projected, session, settings, ReasoningEffort.HIGH, "林小满") }
        assertEquals(1, projected.count { it.key == CHARACTER_THINKING_STYLE_KEY })
        assertSame(other, projected.last())
        assertFalse(projected.last().enabled)
        assertEquals(listOf(other), liveCharacterThinkingDebugItems(projected, session, settings.copy(enabled = false), ReasoningEffort.HIGH, "林小满"))
    }

    private fun preferences(saved: MutableMap<String, Any>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getBoolean", "getString" -> saved[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    when (editMethod.name) {
                        "putBoolean", "putString" -> { pending[editArgs!![0] as String] = requireNotNull(editArgs[1]); editor }
                        "apply", "commit" -> { saved.putAll(pending); true }
                        else -> error(editMethod.name)
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error(method.name)
        }
    } as SharedPreferences
}
