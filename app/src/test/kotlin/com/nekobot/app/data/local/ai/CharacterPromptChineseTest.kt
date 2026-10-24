package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CharacterPromptChineseTest {
    @Test
    fun chineseOutputRulesPreserveDelimitersFormatAndPriorities() {
        val stack = PromptStack()
        buildCharacterInjections(stack, CharacterProfile())
        val bubble = requireNotNull(stack.get("output.bubble_split"))
        val inner = requireNotNull(stack.get("output.inner_monologue"))
        assertTrue(bubble.content.contains("第一条气泡内容<||>第二条气泡内容<||>第三条气泡内容"))
        assertTrue(bubble.content.contains("不必拆分每次回复"))
        assertTrue(inner.content.contains("（内心：...）"))
        assertTrue(inner.content.contains("保持简短（1—2句）"))
        assertTrue(inner.content.contains("不要每次回复都加"))
        assertEquals(PromptStack.Priority.BUBBLE_SPLIT, bubble.priority)
        assertEquals(PromptStack.Priority.BUBBLE_SPLIT + 1, inner.priority)
        assertFalse(bubble.content.contains("When you want"))
        assertFalse(inner.content.contains("Inner monologue format"))
        // 2026-10-22：独白规则只在普通角色保留，单会话窗口不再注入。
        assertTrue("output.inner_monologue" in characterOutputPromptKeys)
        val companion = PromptStack()
        buildCharacterInjections(companion, CharacterProfile(), companionMode = true)
        assertNull(companion.get("output.inner_monologue"))
    }

    @Test
    fun stateRelationshipAndEvolutionLabelsAreChineseWhileValuesStayIntact() {
        val stack = PromptStack()
        buildCharacterInjections(stack, CharacterProfile(),
            state = CharacterState(mood = "开心", moodIntensity = 0.7f, energy = 85, scene = mapOf("current_activity" to "散步"),
                personalityEvolution = listOf(mapOf("trait" to "openness", "delta" to 5, "reason" to "受到鼓励"))),
            relationship = RelationshipState(affection = 61, trust = 72, familiarity = 43, dependency = 24, security = 65, jealousy = 6))
        val state = requireNotNull(stack.get("character.runtime_state")).content
        assertTrue(state.contains("当前心情：开心"))
        assertTrue(state.contains("精力：85"))
        assertTrue(state.contains("current_activity: 散步"))
        val relationship = requireNotNull(stack.get("character.relationship")).content
        for (line in listOf("好感：61/100", "信任：72/100", "熟悉度：43/100", "依赖：24/100", "安全感：65/100", "嫉妒：6/100")) {
            assertTrue(relationship.contains(line))
        }
        val evolution = requireNotNull(stack.get("character.personality_evolution")).content
        assertTrue(evolution.contains("性格演化"))
        assertTrue(evolution.contains("openness：适度增强（变化量 +5）"))
        assertTrue(evolution.contains("受到鼓励"))
    }

    @Test
    fun reactionPlanRetainsConditionalMonologueTaskCompletionAndStyleTargets() {
        val stack = PromptStack()
        buildCharacterInjections(stack, CharacterProfile(), plan = ReactionPlan(intent = "安慰", visibleEmotion = "温柔",
            hiddenEmotion = "担心", tone = "轻柔", styleControls = mapOf("length" to "短", "action_detail" to "少", "initiative" to "适中")))
        val plan = requireNotNull(stack.get("character.reaction_plan")).content
        for (text in listOf("本轮反应约定", "主要意图：安慰", "表露的情绪：温柔", "隐藏动机：担心", "语气：轻柔",
            "回复长度目标：短", "动作细节目标：少", "主动性目标：适中", "不要每轮硬加", "仍要完成任务", "不要说明自己正在扮演角色")) {
            assertTrue(text, plan.contains(text))
        }
        // 普通角色保持作者原版：反应计划仍可以提（内心：…）格式；单会话那边不看这段（计划不注入）。
        assertTrue(plan.contains("（内心：...）"))
        assertEquals(PromptStack.Priority.REACTION_PLAN, stack.get("character.reaction_plan")?.priority)
    }
}
