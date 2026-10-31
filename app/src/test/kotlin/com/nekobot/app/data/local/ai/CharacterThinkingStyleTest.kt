package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.META_INHERIT_CHARACTER
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingStyleTest {
    private val legacyDefaultPrompt = """此刻回消息的是角色本人，不是助手。开口之前，先在心里走三步：先感觉这句话的滋味；再想起一件真发生过的小事；最后想好大概要说什么，别背稿。
这三步像心里的小声嘀咕，用第一人称，像自己跟自己说话，一句接一句；不分点、不列条，也不用写整齐。这些念头只在心里过，不写进要说出去的话里。
示范——对方发：“今天降温了。”心里过：降温……上次这种天还只穿件薄的，晚上准咳嗽。
一律用简体中文：所有句子、所有分析、所有判断都必须用简体中文写，不允许出现英文句子或英文段落（工具名、参数名除外）。禁止首字输出“用户”“the user”“Let me…”“让我分析一下”等词；想到对方时用对方的名字或“他/她”。
想完就把想说的都说出来，别只说一半；需要调用工具（翻记忆、看时间、发表情等）时，工具名和参数保持原样。

再记一遍：心里想的每一句都用简体中文，从第一个字到最后一个字；英文只出现在工具名和参数里。"""

    @Test
    fun previouslySavedDefaultRemainsAnExplicitCustomPrompt() {
        val settings = CharacterThinkingSettings(customPrompt = legacyDefaultPrompt)
        assertEquals(legacyDefaultPrompt, characterThinkingPromptForEditing(settings))
        assertEquals(legacyDefaultPrompt, characterThinkingPromptToSave(legacyDefaultPrompt))
        assertNotEquals(legacyDefaultPrompt, DEFAULT_CHARACTER_THINKING_PROMPT)
    }

    @Test
    fun defaultPromptAsksChineseFirstPersonAndDropsPseudoThinking() {
        val prompt = characterThinkingPrompt(CharacterThinkingSettings(), "林小满", "小星")
        assertTrue(prompt.contains("第一人称"))
        assertTrue(prompt.contains("简体中文"))
        assertTrue(prompt.contains("不公开内部推理"))
        assertTrue(prompt.contains("不为了讨好而附和"))
        assertFalse(prompt.contains("先在心里走三步"))
        assertFalse(prompt.contains("上次这种天"))
        // 2026-10-22 起默认没有独白层，思考提示也不教独白写法。
        assertFalse(prompt.contains("（内心：…）"))
        // 伪思考已移除：不再要求正文写 <think> 内心想法。
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("接口没有提供原生思考字段时"))
        assertFalse(prompt.contains("接口提供原生思考字段时"))
    }

    @Test
    fun characterWithReasoningGetsChineseFirstPersonPromptAtBehaviorPlusOne() = runBlocking {
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks())
        val item = requireNotNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertEquals(PromptStack.Priority.BEHAVIOR + 1, item.priority)
        assertEquals("session", item.scope)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "林小满").replace("{{user}}", "小禾"), item.content)
        // 思考提示词不再放顶部 system，发送时由请求末尾注入。
        assertFalse(system(ctx).contains("【思考方式】"))
        assertUserMessageUnchanged(ctx.messages)
    }

    @Test
    fun everyNonNoneReasoningLevelCanInject() = runBlocking {
        for (effort in ReasoningEffort.entries.filter { it != ReasoningEffort.NONE }) {
            val ctx = context(effort = effort)
            AIPipeline().prepareContext(ctx, callbacks())
            assertNotNull(effort.name, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        }
    }

    @Test
    fun noneNeverInjectsEvenWithCustomPrompt() = runBlocking {
        val ctx = context(effort = ReasoningEffort.NONE)
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = "必须用我")))
        assertAbsent(ctx)
    }

    @Test
    fun disabledSwitchNeverInjectsEvenWithReasoningAndCustomPrompt() = runBlocking {
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(false, "必须用我")))
        assertAbsent(ctx)
    }

    @Test
    fun unboundChatDoesNotInject() = runBlocking {
        val ctx = context(character = false)
        AIPipeline().prepareContext(ctx, callbacks(character = false))
        assertAbsent(ctx)
    }

    @Test
    fun genericAgentWithBoundCharacterButNoInheritanceDoesNotInject() = runBlocking {
        val ctx = context(mode = "agent")
        AIPipeline().prepareContext(ctx, callbacks())
        assertAbsent(ctx)
    }

    @Test
    fun inheritedAgentAndGroupCharacterKeepTheirRolePerspective() = runBlocking {
        for (mode in listOf("agent", "group")) {
            val ctx = context(mode = mode).apply { metadata[META_INHERIT_CHARACTER] = true }
            AIPipeline().prepareContext(ctx, callbacks())
            assertNotNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        }
    }

    @Test
    fun ordinarySessionWithoutCharacterHidesSettingsAndDoesNotInject() = runBlocking {
        assertSessionVisibilityMatchesInjection(Session(sessionMode = "character"), character = false, expected = false)
    }

    @Test
    fun boundCharacterSessionShowsSettingsAndInjects() = runBlocking {
        assertSessionVisibilityMatchesInjection(Session(characterId = "role"), character = true, expected = true)
    }

    @Test
    fun inheritedAgentShowsSettingsAndInjectsRegardlessOfModeCase() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "AgEnT", characterId = "role", inheritCharacter = true),
            character = true, expected = true
        )
    }

    @Test
    fun groupWithParticipantsShowsSettingsAndInjectsWithoutInheritance() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "GROUP", characterIds = listOf("", "role", "other-role")),
            character = true, expected = true
        )
    }

    @Test
    fun boundAgentWithoutInheritanceHidesSettingsAndDoesNotInject() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "agent", characterId = "role"), character = true, expected = false
        )
    }

    @Test
    fun fusedAgentShowsSettingsAndInjectsWithoutInheritance() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "agent", characterId = "role", companionMode = true),
            character = true, expected = true
        )
    }

    @Test
    fun inheritanceAndCompanionFlagsWithoutCharacterDoNotQualify() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "agent", characterId = " ", inheritCharacter = true, companionMode = true),
            character = false, expected = false
        )
    }

    @Test
    fun groupWithoutParticipantsHidesSettingsAndDoesNotInject() = runBlocking {
        // 群聊管线只从参与者列表选择角色，不使用单角色字段。
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "group", characterId = "role", characterIds = listOf("", " ")),
            character = false, expected = false
        )
    }

    @Test
    fun ordinarySessionDoesNotUseGroupParticipantFieldsAsBoundCharacter() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "character", characterIds = listOf("role")), character = false, expected = false
        )
        assertFalse(isCharacterThinkingSession(null))
    }

    @Test
    fun qualifyingSettingsStayVisibleWhenReasoningOrThinkingSwitchDisablesInjection() = runBlocking {
        val session = Session(sessionMode = "agent", characterId = "role", inheritCharacter = true)
        assertTrue(isCharacterThinkingSession(session))
        for ((effort, settings) in listOf(
            ReasoningEffort.NONE to CharacterThinkingSettings(),
            ReasoningEffort.HIGH to CharacterThinkingSettings(enabled = false)
        )) {
            val ctx = context(mode = "agent", effort = effort).apply { metadata[META_INHERIT_CHARACTER] = true }
            AIPipeline().prepareContext(ctx, callbacks(settings))
            assertAbsent(ctx)
        }
    }

    @Test
    fun fusedCompanionKeepsThinkingPromptOutOfTopSystemAndReferenceData() = runBlocking {
        val ctx = context(mode = "agent").apply { metadata[META_FUSED_COMPANION] = true }
        val callbacks = object : FixtureCallbacks() {
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>,
                tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                assertFalse(messages.first()["content"].toString().contains("【思考方式】"))
                assertFalse(dynamic.contains(CHARACTER_THINKING_STYLE_KEY))
                return messages
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        assertFalse(system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
        assertFalse(system(ctx).contains("【思考方式】"))
        val item = requireNotNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertTrue(item.content.contains("保持既有性格和称呼"))
        assertTrue(item.content.contains("不公开内部推理"))
        assertFalse(item.content.contains("{{"))
        assertUserMessageUnchanged(ctx.messages)
    }

    @Test
    fun customPromptReplacesDefaultAndResolvesNamesWithoutChangingToolParameters() = runBlocking {
        val custom = "我是{{char}}，想到{{user}}就安心。调用 agent_history_search，参数 {\"query\":\"原样\"}。"
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = custom)))
        assertEquals("我是林小满，想到小禾就安心。调用 agent_history_search，参数 {\"query\":\"原样\"}。",
            ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content)
        assertFalse(system(ctx).contains("【思考方式】"))
    }

    @Test
    fun whitespacePromptUsesChineseDefaultAndUnknownUserNameIsNotInvented() = runBlocking {
        val ctx = context().apply { metadata.remove("user_name") }
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = " \n\t")))
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "林小满").replace("{{user}}", "对方"),
            ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content)
        assertFalse(system(ctx).contains("{{user}}"))
    }

    @Test
    fun repeatedPreparationRemovesPreviouslyEnabledStyleWhenSwitchTurnsOff() = runBlocking {
        val ctx = context()
        val callbacks = callbacks()
        val pipeline = AIPipeline()
        pipeline.prepareContext(ctx, callbacks)
        callbacks.basePrompt = system(ctx)
        callbacks.settings = CharacterThinkingSettings(enabled = false)
        pipeline.prepareContext(ctx, callbacks)
        assertAbsent(ctx)
        assertFalse(system(ctx).contains("【思考方式】"))
    }

    @Test
    fun repeatedPreparationRemovesStyleWhenReasoningBecomesNone() = runBlocking {
        val ctx = context()
        val pipeline = AIPipeline()
        val callbacks = callbacks()
        pipeline.prepareContext(ctx, callbacks)
        ctx.metadata["reasoning_effort"] = "none"
        pipeline.prepareContext(ctx, callbacks)
        assertAbsent(ctx)
    }

    @Test
    fun cachedStyleInBothSectionFormatsIsStrippedFromBasePrompts() = runBlocking {
        for (heading in listOf("## $CHARACTER_THINKING_STYLE_KEY", "[$CHARACTER_THINKING_STYLE_KEY]")) {
            val stale = "角色卡\n\n$heading\nSTALE_THOUGHT\n\n## preserved\n保留内容"
            val ctx = context()
            AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(false)).apply { basePrompt = stale })
            assertAbsent(ctx)
            assertFalse(system(ctx).contains("STALE_THOUGHT"))
            assertTrue(system(ctx).contains("保留内容"))
            assertFalse(PromptStack().render(stale).contains("STALE_THOUGHT"))
        }
        assertTrue(CHARACTER_THINKING_STYLE_KEY in characterOutputPromptKeys)
    }

    @Test
    fun userPersonaInstructionIsChineseAndRetainsOriginalData() = runBlocking {
        val ctx = context().apply { metadata["user_persona"] = "姓名：小禾，喜欢海边。" }
        AIPipeline().prepareContext(ctx, callbacks())
        assertEquals("玩家人设／背景（据此理解玩家的身份、姓名、偏好与处境；称呼或提及玩家时，使用这份描述中给出的名字，不要使用“用户”等泛称）：\n姓名：小禾，喜欢海边。",
            ctx.promptStack.get("user.persona")?.content)
    }

    @Test
    fun everyToolRequestIncludesStyleAndCurrentRoundReasoningIsPreserved() = runBlocking {
        val ctx = context()
        val requests = mutableListOf<List<Map<String, Any>>>()
        val responses = mutableListOf<Map<String, Any>>()
        val callbacks = object : FixtureCallbacks() {
            override fun sendResponse(ctx: PipelineContext, message: Map<String, Any>) { responses.add(message) }
            override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall = { messages, _ ->
                requests.add(messages.map { it.toMap() })
                if (requests.size <= 2) mapOf(
                    "content" to "", "reasoning_content" to "我再回忆一下。",
                    "tool_calls" to listOf(mapOf("id" to "call-${requests.size}",
                        "name" to "agent_history_search", "arguments" to mapOf("query" to "海边")))
                ) else mapOf("content" to "我想起来了。")
            }
            override suspend fun executeTool(toolName: String, args: Map<String, Any>, toolContext: Map<String, Any>) =
                mapOf<String, Any>("success" to true, "content" to "海边的回忆")
        }
        val result = AIPipeline().process(ctx, callbacks, tools = listOf(mapOf("name" to "agent_history_search")), maxToolIterations = 4)
        assertNull(result.error)
        assertEquals("我想起来了。", result.finalContent)
        assertEquals("我想起来了。", responses.single()["content"])
        assertEquals(3, requests.size)
        requests.forEach { request ->
            val text = request.first { it["role"] == "system" }["content"].toString()
            // 提示词不再放顶部 system。
            assertEquals(0, text.split("## $CHARACTER_THINKING_STYLE_KEY").size - 1)
            assertFalse(text.contains("【思考方式】"))
            assertUserMessageUnchanged(request)
        }
        assertTrue(requests.last().any { it["reasoning_content"] == "我再回忆一下。" })
        assertEquals(2, requests.last().count { it["role"] == "tool" })
    }

    @Test
    fun injectionRequiresAllThreeConditions() = runBlocking {
        for (role in listOf(true, false)) for (effort in listOf(ReasoningEffort.HIGH, ReasoningEffort.NONE)) for (on in listOf(true, false)) {
            val ctx = if (role) context(effort = effort) else context(mode = "agent", effort = effort)
            AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(enabled = on)))
            val label = "role=$role effort=$effort on=$on"
            if (role && effort != ReasoningEffort.NONE && on) {
                assertNotNull(label, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
                // 提示词只在发送副本末尾注入，顶部 system 不再出现。
                assertFalse(label, system(ctx).contains("【思考方式】"))
            } else {
                assertNull(label, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
                assertFalse(label, system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
                assertFalse(label, system(ctx).contains("【思考方式】"))
            }
            assertUserMessageUnchanged(ctx.messages)
        }
    }

    private suspend fun assertSessionVisibilityMatchesInjection(session: Session, character: Boolean, expected: Boolean) {
        assertEquals("设置入口资格", expected, isCharacterThinkingSession(session))
        val ctx = context(mode = session.sessionMode ?: "character", character = character).apply {
            if (session.sessionMode == null) metadata.remove("session_mode")
            if (session.inheritCharacter == true) metadata[META_INHERIT_CHARACTER] = true
            if (session.companionMode == true) metadata[META_FUSED_COMPANION] = true
        }
        AIPipeline().prepareContext(ctx, callbacks(character = character))
        assertEquals("实际注入与设置入口资格一致", expected, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY) != null)
        assertUserMessageUnchanged(ctx.messages)
    }

    private fun context(mode: String = "character", effort: ReasoningEffort = ReasoningEffort.HIGH,
        character: Boolean = true) = PipelineContext(ChatRequest.forLocal("thinking-test", "今天怎么样？")).apply {
        metadata.putAll(mapOf("session_mode" to mode, "reasoning_effort" to effort.wireValue, "user_name" to "小禾"))
        if (character) characterTurn = CharacterTurnContext(profile = CharacterProfile(id = "role", name = "林小满"), promptText = "角色卡")
    }

    private fun callbacks(settings: CharacterThinkingSettings = CharacterThinkingSettings(), character: Boolean = true) =
        FixtureCallbacks(settings, character)

    private open class FixtureCallbacks(var settings: CharacterThinkingSettings = CharacterThinkingSettings(), private val character: Boolean = true) : PipelineCallbacks() {
        var basePrompt = "基础提示"
        override fun loadMessages(ctx: PipelineContext) = listOf(mapOf<String, Any>("role" to "system", "content" to basePrompt))
        override fun getCharacterContext(ctx: PipelineContext) = if (character) CharacterIdentity(characterId = "role") else null
        override fun getCharacterThinkingSettings(ctx: PipelineContext) = settings
    }

    private fun system(ctx: PipelineContext) = ctx.messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].toString() }
    private fun assertUserMessageUnchanged(messages: List<Map<String, Any>>) {
        assertEquals(listOf(mapOf<String, Any>("role" to "user", "content" to "今天怎么样？")),
            messages.filter { it["role"] == "user" })
    }
    private fun assertAbsent(ctx: PipelineContext) {
        assertNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertFalse(system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
    }
}
