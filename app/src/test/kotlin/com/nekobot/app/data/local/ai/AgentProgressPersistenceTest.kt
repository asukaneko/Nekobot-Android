package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressPersistenceTest {

    @Test
    fun persistedCardBoundsReasoningAndToolPayloads() {
        val card = ThinkingCard(
            id = "card",
            content = "done",
            steps = listOf(
                ThinkingStep(
                    type = "thinking",
                    thinkingContent = "r".repeat(80_000)
                ),
                ThinkingStep(
                    type = "tool",
                    arguments = mapOf("input" to "a".repeat(50_000)),
                    fullResult = mapOf("content" to "x".repeat(500_000))
                )
            ),
            isAgent = true
        )

        val persisted = card.toPersistedProgressCard()

        // 断言统一上限本身，而不是写死旧数字：改设置或调默认值时这里仍应成立。
        assertEquals(
            AgentToolLimits.PROGRESS_REASONING_CHARS,
            persisted.steps.first().thinkingContent?.length
        )
        assertTrue(
            persisted.steps[1].arguments?.get("preview").toString().length <=
                AgentToolLimits.progressPreviewChars()
        )
        assertTrue(
            persisted.steps[1].fullResult.toString().length <= AgentToolLimits.progressPreviewChars()
        )
    }

    @Test
    fun persistedCardSharesReasoningBudgetAcrossRounds() {
        // 每轮思考各自成步：逐条截断到单条上限会让总量随轮数线性膨胀，
        // 因此总预算从最新一轮往前分配，预算耗尽的更早思考只留步骤摘要。
        val rounds = 8
        val card = ThinkingCard(
            id = "card",
            content = "done",
            steps = (0 until rounds).map { index ->
                ThinkingStep(
                    type = "thinking",
                    name = "round-$index",
                    status = "done",
                    thinkingContent = "$index".repeat(AgentToolLimits.PROGRESS_REASONING_CHARS)
                )
            },
            isAgent = true
        )

        val persisted = card.toPersistedProgressCard()

        val total = persisted.steps.sumOf { it.thinkingContent?.length ?: 0 }
        assertTrue(total <= AgentToolLimits.PROGRESS_REASONING_TOTAL_CHARS)
        assertEquals(
            AgentToolLimits.PROGRESS_REASONING_CHARS,
            persisted.steps.last().thinkingContent?.length
        )
        assertEquals("最早几轮应被预算裁掉正文", null, persisted.steps.first().thinkingContent)
        assertEquals(rounds, persisted.steps.size)
    }

    @Test
    fun persistedCardKeepsHeadThinkingInsideTheTailWindowExactlyOnce() {
        val cap = AgentToolLimits.PROGRESS_PERSISTED_STEPS
        // 开头那条思考落在尾部窗口之内：不能再作为「头部思考」额外补一次
        val steps = buildList {
            repeat(300) { add(ThinkingStep(type = "tool", name = "tool-$it", status = "done")) }
            repeat(cap - 300 + 5) { add(ThinkingStep(type = "thinking", name = "think-$it", status = "done")) }
        }
        val card = ThinkingCard(id = "card", content = "done", steps = steps, isAgent = true)

        val persisted = card.toPersistedProgressCard()

        assertEquals(cap, persisted.steps.size)
        // 步骤名唯一 → 去重后数量不变即证明没有重复添加
        assertEquals(persisted.steps.size, persisted.steps.map { it.name }.toSet().size)
    }

    @Test
    fun persistedCardKeepsTheOpeningThinkingStepWhenTrimming() {
        val cap = AgentToolLimits.PROGRESS_PERSISTED_STEPS
        val steps = buildList {
            add(ThinkingStep(type = "thinking", name = "opening", status = "done"))
            repeat(cap + 20) { add(ThinkingStep(type = "tool", name = "tool-$it", status = "done")) }
        }
        val card = ThinkingCard(id = "card", content = "done", steps = steps, isAgent = true)

        val persisted = card.toPersistedProgressCard()

        assertEquals(cap, persisted.steps.size)
        assertEquals("opening", persisted.steps.first().name)
        assertEquals(1, persisted.steps.count { it.type == "thinking" })
    }

    @Test
    fun boundedPreviewStopsCyclesAndNeverExceedsBudget() {
        val cyclic = linkedMapOf<String, Any>()
        cyclic["self"] = cyclic

        val preview = boundedAgentValuePreview(cyclic, 128)

        assertTrue(preview.contains("<cycle>"))
        assertTrue(preview.length <= 128)
    }

    @Test
    fun toolOutputTruncationRecognizesCommonBooleanEncodings() {
        assertTrue(isAgentToolOutputTruncated(mapOf("truncated" to true)))
        assertTrue(isAgentToolOutputTruncated(mapOf("TRUNCATED" to "true")))
        assertTrue(isAgentToolOutputTruncated(mapOf("truncated" to 1)))
        assertFalse(isAgentToolOutputTruncated(mapOf("truncated" to false)))
        assertFalse(isAgentToolOutputTruncated(mapOf("content" to "complete")))
    }

    @Test
    fun oversizedLegacyCardSkipsJsonParsingAndReturnsSafePlaceholder() {
        // 故意使用无效 JSON：若先解析再检查大小，本测试会返回 null。
        val raw = "x".repeat(MAX_AGENT_PROGRESS_HISTORY_JSON_CHARS + 1)

        val cards = decodeThinkingCardsForUi("user-1", raw)

        assertEquals(1, cards?.size)
        assertTrue(cards?.single()?.isComplete == true)
        assertTrue(cards?.single()?.isAgent == true)
        assertEquals("user-1", cards?.single()?.parentMessageId)
    }
}
