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
    fun alreadyPreviewShapedArgumentsAreNotWrappedTwice() {
        // 进度报告器写入的已经是预览文本；落库再包一层会让详情弹窗只剩一行 "preview = {...}"。
        val card = ThinkingCard(
            id = "card",
            content = "done",
            steps = listOf(
                ThinkingStep(
                    type = "tool",
                    arguments = mapOf("preview" to "{path=/sdcard/a.txt, limit=10}")
                )
            ),
            isAgent = true
        )

        val persisted = card.toPersistedProgressCard()

        assertEquals(
            "{path=/sdcard/a.txt, limit=10}",
            persisted.steps.single().arguments?.get("preview")
        )
    }

    @Test
    fun persistedCardSharesIntermediateTextBudgetAcrossReplies() {
        // 中间回复每轮都可能留一段：总预算从最新一段往前分配，预算耗尽的更早回复不再保留。
        val replies = 8
        val card = ThinkingCard(
            id = "card",
            content = "done",
            steps = (0 until replies).map { index ->
                ThinkingStep(
                    type = "agent_text",
                    status = "done",
                    text = "$index".repeat(AgentToolLimits.PROGRESS_INTERMEDIATE_CHARS)
                )
            },
            isAgent = true
        )

        val persisted = card.toPersistedProgressCard()

        val total = persisted.steps.sumOf { it.text?.length ?: 0 }
        assertTrue(total <= AgentToolLimits.PROGRESS_INTERMEDIATE_TOTAL_CHARS)
        assertEquals(
            AgentToolLimits.PROGRESS_INTERMEDIATE_CHARS,
            persisted.steps.last().text?.length
        )
        assertEquals("最早几段应被预算裁掉正文", null, persisted.steps.first().text)
    }

    @Test
    fun intermediateReplyTextSurvivesPersistedJsonRoundTrip() {
        val card = ThinkingCard(
            id = "card",
            content = "done",
            steps = listOf(
                ThinkingStep(type = "agent_text", status = "done", text = "我先读取配置。")
            ),
            isAgent = true
        )

        val json = com.google.gson.Gson().toJson(listOf(card.toPersistedProgressCard()))
        val restored = decodeThinkingCardsForUi("user-1", json)

        assertEquals("我先读取配置。", restored?.single()?.steps?.single()?.text)
    }

    @Test
    fun imageUrlsAreSummarizedInsteadOfShowingBase64Data() {
        // read_image / android_screenshot 注入的 data URI 可能数 MB，进度卡片只能显示简短说明。
        val dataUri = "data:image/png;base64," + "A".repeat(400_000)
        val result = mapOf(
            "success" to true,
            "path" to "photo.png",
            "_image_urls" to listOf(dataUri)
        )

        val sanitized = sanitizeAgentToolResultForDisplay(result)

        val placeholder = sanitized["_image_urls"] as? String
        assertTrue(placeholder?.contains("image/png") == true)
        assertTrue(placeholder?.contains("数据已省略") == true)
        val preview = boundedAgentValuePreview(sanitized, AgentToolLimits.progressPreviewChars())
        assertFalse(preview.contains("AAAA"))
        assertTrue(preview.contains("photo.png"))
    }

    @Test
    fun sanitizeKeepsResultsWithoutImagesUntouched() {
        val noImageUrls = mapOf<String, Any>("success" to true)
        assertTrue(sanitizeAgentToolResultForDisplay(noImageUrls) === noImageUrls)

        val emptyImageUrls = mapOf<String, Any>("success" to true, "_image_urls" to emptyList<String>())
        assertTrue(sanitizeAgentToolResultForDisplay(emptyImageUrls) === emptyImageUrls)
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
