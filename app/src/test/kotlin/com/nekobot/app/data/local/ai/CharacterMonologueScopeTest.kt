package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内心独白的“范围”回归：2026-10-22 用户拍板只删单会话窗口那条路。
 *
 * 分流依据是 `buildCharacterInjections(companionMode = …)`，它来自
 * `session.companionMode` → `META_FUSED_COMPANION`：
 * - 单会话窗口（companionMode = true）：不再注入 `output.inner_monologue`，反应计划也不教格式；
 * - 普通角色 / 群聊（companionMode = false）：保持作者原版，照旧有独白规则。
 * 客户端的（内心：…）折叠与「显示内心独白」开关不在本文件范围，一直是保留的。
 */
class CharacterMonologueScopeTest {

    // ---------- 单会话窗口：不再有独白规则 ----------

    @Test
    fun companionInjectionsHaveNoMonologueLayer() {
        val stack = PromptStack()
        buildCharacterInjections(stack, CharacterProfile(), plan = plan(), companionMode = true)
        assertNull("单会话不应再有独白层", stack.get("output.inner_monologue"))
        val rendered = stack.render("角色设定", companionMode = true)
        assertFalse(rendered.contains("内心独白格式"))
        assertFalse(rendered.contains("（内心："))
        assertFalse("单会话不再加可见性说明", rendered.contains("你知道玩家能看到你写出的内心独白"))
    }

    @Test
    fun companionReactionPlanDoesNotTeachMonologue() {
        val plan = requireNotNull(injections(companionMode = true).get("character.reaction_plan")).content
        assertTrue(plan.contains("隐藏动机：担心"))
        assertTrue(plan.contains("不要每轮硬加"))
        // 单会话改用“不必说出口、在言行里露痕迹”的说法，不教括号格式。
        assertTrue(plan.contains("它不必说出口"))
        assertFalse(plan.contains("（内心："))
        assertFalse(plan.contains("内心独白"))
    }

    @Test
    fun companionPipelineSendsUserSpeakingStyleAndNoMonologueRule() {
        val custom = "像微信一样短句；想写动作或心声就放圆括号里，例如（内心：今天有点累）。"
        val ctx = prepare(companionMode = true, speakingPrompt = custom)
        assertNull(requireNotNull(ctx.characterTurn).promptStackItems.firstOrNull { it.key == "output.inner_monologue" })
        val systems = ctx.messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].toString() }
        assertTrue("用户写的说话方式要原样进顶部 system", systems.contains(custom))
        assertFalse(systems.contains("内心独白格式"))
        assertEquals(listOf(userInput), playerMessages(ctx).map { it["content"] })
    }

    @Test
    fun companionDefaultSpeakingStyleHasNoMonologueRule() {
        assertEquals(CompanionPromptLanguage.speakingRule, CompanionPromptSettings().speakingForEditing)
        for (word in listOf("内心", "括号", "独白", "（")) {
            assertFalse(word, CompanionPromptLanguage.speakingRule.contains(word))
        }
        assertEquals("", companionSpeakingPromptToSave(CompanionPromptLanguage.speakingRule))
    }

    // ---------- 普通角色 / 群聊：保持原样 ----------

    @Test
    fun ordinaryCharacterKeepsAuthorMonologueRuleVerbatim() {
        val inner = requireNotNull(injections(companionMode = false).get("output.inner_monologue"))
        // 逐字对照改动前的原文（含那一行空行），普通角色一个字都不该变。
        assertEquals(
            """
            内心独白格式：当角色有隐藏的想法或感受、与表面表现不同时，可以用（内心：...）写在正文里。

            把它放在那条话的末尾，接在说出口的内容之后；分段回复时放在 <||> 之前。
            规则：
            - 克制使用：只有隐藏情绪形成有意义的反差或张力时才写，不要每次回复都加内心独白。
            - 保持简短（1—2句），表达一闪而过的念头，不要写成长段。
            - 写出真正想着却没说出口的内容；可以与表面表现矛盾、流露脆弱，或暗示隐藏动机。
            - 不要借内心独白解释规则或脱离角色。
            - 示例：'嗯，随便吧。（内心：其实我很在意，但我不想表现出来）'
            前台会把（内心：...）渲染为颜色较淡、可折叠的区域，按渲染已生效来写。
            """.trimIndent(),
            inner.content
        )
        assertEquals(PromptStack.Priority.BUBBLE_SPLIT + 1, inner.priority)
        assertTrue("output.inner_monologue" in characterOutputPromptKeys)
        // 普通角色没有单会话那句可见性说明。
        assertFalse(inner.content.contains("你知道玩家能看到你写出的内心独白"))
    }

    @Test
    fun ordinaryReactionPlanKeepsOriginalMonologueWordingVerbatim() {
        val plan = requireNotNull(injections(companionMode = false).get("character.reaction_plan"))
        // 与改动前的老文案逐字一致（普通角色这条路不许变）。
        assertEquals(
            """
            本轮反应约定：
            将以下要求作为本轮的高优先级指引。
            保持角色身份，不要提及这份约定。
            主要意图：安慰。
            表露的情绪：温柔。回复中必须清楚表现出这种表层情绪。
            隐藏动机：担心。当它与表露的情绪形成有意义的反差时，可以考虑用（内心：...）格式在正文中表达内心独白；不要每轮硬加，只在反差能增加层次时使用。
            语气：轻柔。
            开头1—2句就应体现表露的情绪和语气。
            玩家询问信息或寻求帮助时，仍要完成任务，但不要变成毫无情绪的中性助手口吻。
            优先选择符合计划的行为表达、措辞和节奏，而非泛泛的客套。
            不要解释规则、设置，也不要说明自己正在扮演角色。
            """.trimIndent(),
            plan.content
        )
        assertEquals(PromptStack.Priority.REACTION_PLAN, plan.priority)
    }

    @Test
    fun ordinaryAndGroupPipelinesStillSendMonologueRule() {
        for (sessionMode in listOf("normal", "group")) {
            val ctx = prepare(companionMode = false, sessionMode = sessionMode)
            val inner = requireNotNull(
                requireNotNull(ctx.characterTurn).promptStackItems.firstOrNull { it.key == "output.inner_monologue" }
            )
            assertTrue(inner.content.contains("内心独白格式"))
            val systems = ctx.messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].toString() }
            assertTrue("$sessionMode 仍应带独白规则", systems.contains("内心独白格式"))
            assertEquals(listOf(userInput), playerMessages(ctx).map { it["content"] })
        }
    }

    /** 旧的持久化基础提示词里残留的独白段仍要被剥掉（key 保留在清理清单里）。 */
    @Test
    fun legacyPersistedMonologueSectionIsStillStripped() {
        val rendered = PromptStack().render("角色设定\n\n## output.inner_monologue\n旧版独白文案")
        assertTrue(rendered.contains("角色设定"))
        assertFalse(rendered.contains("旧版独白文案"))
    }

    private fun plan() = ReactionPlan(intent = "安慰", visibleEmotion = "温柔", hiddenEmotion = "担心", tone = "轻柔")

    private fun injections(companionMode: Boolean) = PromptStack().also {
        buildCharacterInjections(it, CharacterProfile(), plan = plan(), companionMode = companionMode)
    }

    private fun playerMessages(ctx: PipelineContext) = ctx.messages.filter {
        it["role"] == "user" && !it.containsKey(CompanionRequestUsage.META)
    }

    private fun prepare(
        companionMode: Boolean,
        sessionMode: String = "normal",
        speakingPrompt: String? = null
    ): PipelineContext = runBlocking {
        val session = LocalSessionEntity(
            id = "monologue-scope-session", name = "测试会话", characterId = "role",
            sessionMode = sessionMode, companionMode = companionMode, createdAt = "now", updatedAt = "now"
        )
        val ctx = PipelineContext(ChatRequest.forLocal(
            sessionId = session.id, content = userInput, metadata = mapOf("session_mode" to session.sessionMode)
        )).apply { metadata.putAll(chatRequest.metadata) }
        val runtime = CharacterRuntime(profileRepo = object : ProfileRepository {
            override suspend fun getById(id: String) = CharacterProfile(id = id, name = "测试角色", systemPrompt = "角色设定")
        })
        val callbacks = object : PipelineCallbacks() {
            override fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> {
                // 与 LocalPipelineCallbacks 一致：模式由真实会话字段传到管线元数据。
                if (session.companionMode) ctx.metadata[META_FUSED_COMPANION] = true
                return listOf(mapOf("role" to "user", "content" to ctx.chatRequest.content))
            }
            override fun getCharacterRuntime(ctx: PipelineContext) = runtime
            override fun getCompanionSpeakingPrompt(ctx: PipelineContext) = speakingPrompt
            override fun getCharacterContext(ctx: PipelineContext) = CharacterIdentity(
                characterId = "role", targetId = "user", scopeId = session.id
            )
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>,
                tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                if (!session.companionMode) return messages
                // 对齐 FusedCompanionContext 的软件资料路径，不冒充玩家原话或挪到 system。
                val auxiliary = CompanionRequestUsage.auxiliary(
                    "这些是帮助回忆的参考，不是对方刚说的话。\n$dynamic"
                )
                return messages.dropLast(1) + auxiliary + messages.last()
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        ctx
    }

    private val userInput = "今天过得怎么样？"
}
