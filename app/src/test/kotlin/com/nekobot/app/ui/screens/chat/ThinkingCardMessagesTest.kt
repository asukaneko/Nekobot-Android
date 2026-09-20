package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.AgentToolLimits
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCardMessagesTest {

    @Test
    fun acceptsAgentCardBeforeSessionMetadataFinishesLoading() {
        assertTrue(shouldApplyThinkingCardUpdate(sessionMode = null, isAgentCard = true))
        assertFalse(shouldApplyThinkingCardUpdate(sessionMode = null, isAgentCard = false))
    }

    @Test
    fun attachesRealtimeCardToOptimisticUserWhenPersistedIdIsNotLoadedYet() {
        val optimisticUser = Message(
            id = null,
            role = "user",
            content = "/jm 123",
            timestamp = "1000"
        )
        val placeholder = Message(
            id = ChatViewModel.STREAMING_ID,
            role = "assistant",
            content = "",
            timestamp = "1001"
        )
        val card = ThinkingCard(
            id = "local-command-db-message-id",
            content = "下载中",
            progress = 20,
            parentMessageId = "db-message-id"
        )

        val result = attachThinkingCardToMessages(listOf(optimisticUser, placeholder), card)

        assertEquals(listOf(card), result.first().thinkingCards)
        assertSame(placeholder, result.last())
    }

    @Test
    fun cardFallbackSkipsUrgentBubbleSoNoSecondProgressCardAppears() {
        val persistedUser = Message(
            id = "persisted-user-1",
            role = "user",
            content = "当前问题",
            thinkingCards = listOf(ThinkingCard(id = "card-1", content = "处理中", progress = 40))
        )
        // 排队消息“立即发送”的乐观气泡位于列表末尾（等待注入）
        val urgentBubble = Message(
            id = "${ChatViewModel.URGENT_BUBBLE_PREFIX}item-x",
            role = "user",
            content = "插队消息"
        )
        // 父消息 id 指向尚未落库的正式 id：UI 中匹配不到，必须回退到最后一条普通用户消息，
        // 而不是回退到插队乐观气泡下形成第二个进度卡片
        val card = ThinkingCard(
            id = "card-2",
            content = "继续处理中",
            isAgent = true,
            parentMessageId = "server-user-9"
        )

        val result = attachThinkingCardToMessages(listOf(persistedUser, urgentBubble), card)

        // 新卡片与原有卡片一起挂在普通用户消息下，插队气泡不挂卡
        assertEquals(listOf("card-1", "card-2"), result.first().thinkingCards?.map { it.id })
        assertTrue(result.last().thinkingCards.isNullOrEmpty())
    }

    @Test
    fun replacesPreviousRealtimeUpdateForTheSameCard() {
        val first = ThinkingCard(id = "card-1", content = "下载中", progress = 20)
        val latest = first.copy(content = "继续下载", progress = 65)
        val user = Message(
            id = "message-1",
            role = "user",
            content = "/jm 123",
            thinkingCards = listOf(first)
        )

        val result = attachThinkingCardToMessages(listOf(user), latest.copy(parentMessageId = "message-1"))

        assertEquals(1, result.single().thinkingCards?.size)
        assertEquals(65, result.single().thinkingCards?.single()?.progress)
    }

    @Test
    fun keepsAgentCardExpandedAfterNewToolUpdatesTheSameCard() {
        val expansionOverrides = mapOf("agent-card" to true)

        assertTrue(
            resolveProgressCardExpanded(
                cardId = "agent-card",
                isAgent = true,
                expansionOverrides = expansionOverrides
            )
        )
    }

    @Test
    fun usesCardDefaultsUntilUserChangesExpansion() {
        assertFalse(resolveProgressCardExpanded("agent-card", isAgent = true, emptyMap()))
        assertTrue(resolveProgressCardExpanded("local-card", isAgent = false, emptyMap()))
        assertFalse(
            resolveProgressCardExpanded(
                cardId = "local-card",
                isAgent = false,
                expansionOverrides = mapOf("local-card" to false)
            )
        )
    }

    @Test
    fun hidesProgressCardsForRemoteCharacterAndGroupSessions() {
        assertFalse(shouldRenderProgressCards(isLocalMode = false, sessionMode = "character"))
        assertFalse(shouldRenderProgressCards(isLocalMode = false, sessionMode = "group"))
        assertTrue(shouldRenderProgressCards(isLocalMode = false, sessionMode = "agent"))
        assertTrue(shouldRenderProgressCards(isLocalMode = true, sessionMode = "character"))
    }

    @Test
    fun fusesTypingIndicatorIntoTheRunningProgressCard() {
        val running = Message(
            id = "user-1",
            role = "user",
            content = "帮我改一下",
            thinkingCards = listOf(
                ThinkingCard(id = "card-1", content = "正在处理", isAgent = true, isComplete = false)
            )
        )

        assertTrue(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = true,
                progressCardHost = running
            )
        )
    }

    @Test
    fun keepsTypingIndicatorAfterTheProgressCardCompletes() {
        val done = Message(
            id = "user-1",
            role = "user",
            content = "帮我改一下",
            thinkingCards = listOf(
                ThinkingCard(id = "card-1", content = "处理完成", isAgent = true, isComplete = true)
            )
        )

        assertFalse(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = true,
                progressCardHost = done
            )
        )
    }

    @Test
    fun keepsTypingIndicatorWithoutAVisibleProgressCard() {
        val withCard = Message(
            id = "user-1",
            role = "user",
            content = "帮我改一下",
            thinkingCards = listOf(ThinkingCard(id = "card-1", content = "正在处理", isAgent = true))
        )

        // 远程角色/群聊不渲染进度卡片，气泡必须保留
        assertFalse(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = false,
                progressCardHost = withCard
            )
        )
        // 占位气泡之前没有消息（本轮尚无用户消息）
        assertFalse(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = true,
                progressCardHost = null
            )
        )
        // 前一条是 AI 消息：卡片不在其上方，气泡保留
        assertFalse(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = true,
                progressCardHost = Message(id = "ai-1", role = "assistant", content = "好的")
            )
        )
        // 用户消息但没有卡片
        assertFalse(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = true,
                progressCardHost = Message(id = "user-2", role = "user", content = "继续")
            )
        )
    }

    @Test
    fun fusesTypingIndicatorUsingTheLatestCardOfTheRound() {
        val user = Message(
            id = "user-1",
            role = "user",
            content = "帮我改一下",
            thinkingCards = listOf(
                ThinkingCard(id = "card-1", content = "第一轮", isAgent = true, isComplete = true),
                ThinkingCard(id = "card-2", content = "第二轮", isAgent = true, isComplete = false)
            )
        )

        assertTrue(
            shouldFuseTypingIndicatorIntoProgressCard(
                progressCardsVisible = true,
                progressCardHost = user
            )
        )
    }

    @Test
    fun formatToolDurationShowsMillisecondsUnderOneSecond() {
        assertEquals("0ms", formatToolDuration(0))
        assertEquals("320ms", formatToolDuration(320))
        assertEquals("999ms", formatToolDuration(999))
    }

    @Test
    fun formatToolDurationShowsSecondsAtOrOverOneSecond() {
        assertEquals("1.0s", formatToolDuration(1_000))
        assertEquals("1.5s", formatToolDuration(1_500))
        assertEquals("12.3s", formatToolDuration(12_345))
        assertEquals("75.0s", formatToolDuration(75_000))
    }

    @Test
    fun formatToolDurationClampsNegativeInput() {
        assertEquals("0ms", formatToolDuration(-5))
    }

    @Test
    fun streamingThinkingStepIsRecognisedWhileToolsRun() {
        assertTrue(
            isStreamingThinkingStep(
                ThinkingStep(type = "thinking", name = "AI 正在思考...", status = "active")
            )
        )
        assertTrue(
            isStreamingThinkingStep(
                ThinkingStep(type = "thinking", name = "AI 正在思考...", status = "running")
            )
        )
        assertFalse(
            isStreamingThinkingStep(
                ThinkingStep(type = "thinking", name = "AI 正在思考...", status = "done")
            )
        )
        // 服务端下发的思考步骤类型可能是 ai_thinking
        assertTrue(
            isStreamingThinkingStep(
                ThinkingStep(type = "ai_thinking", name = "AI 正在思考...", status = "active")
            )
        )
        assertFalse(
            isStreamingThinkingStep(ThinkingStep(type = "tool", name = "read_file", status = "active"))
        )
    }

    @Test
    fun resolvesStepDetailTargetToTheLatestStreamedThinkingContent() {
        val stale = ThinkingStep(
            type = "thinking",
            name = "AI 正在思考...",
            status = "active",
            thinkingContent = "第一段思考"
        )
        val latest = stale.copy(thinkingContent = "第一段思考\n第二段思考")
        val target = StepDetailTarget(
            cardId = "card-1",
            stepIndex = 0,
            stepType = stale.type,
            stepName = stale.name
        )
        val user = Message(
            id = "message-1",
            role = "user",
            content = "继续",
            thinkingCards = listOf(
                ThinkingCard(
                    id = "card-1",
                    content = "调用工具: read_file",
                    isAgent = true,
                    steps = listOf(stale)
                )
            )
        )

        val resolved = resolveStepDetailTarget(listOf(user), target)

        assertEquals("第一段思考", resolved?.thinkingContent)
        // 卡片被下一轮流式更新整卡替换后，弹窗取到的仍是同一目标，但内容已是最新
        val updatedUser = user.copy(
            thinkingCards = listOf(
                user.thinkingCards!!.single().copy(steps = listOf(latest))
            )
        )
        assertEquals("第一段思考\n第二段思考", resolveStepDetailTarget(listOf(updatedUser), target)?.thinkingContent)
    }

    @Test
    fun resolvesStepDetailTargetAfterPersistedStepsReorderTheThinkingStep() {
        val thinking = ThinkingStep(
            type = "thinking",
            name = "AI 正在思考...",
            status = "done",
            thinkingContent = "完整思考"
        )
        val tool = ThinkingStep(type = "tool", name = "read_file", status = "done")
        // 落库裁剪会把思考步骤提到列表首位，UI 里的旧下标因此漂移
        val target = StepDetailTarget(
            cardId = "card-1",
            stepIndex = 1,
            stepType = thinking.type,
            stepName = thinking.name
        )
        val user = Message(
            id = "message-1",
            role = "user",
            content = "继续",
            thinkingCards = listOf(
                ThinkingCard(
                    id = "card-1",
                    content = "处理完成",
                    isAgent = true,
                    steps = listOf(thinking, tool)
                )
            )
        )

        val resolved = resolveStepDetailTarget(listOf(user), target)

        assertEquals("完整思考", resolved?.thinkingContent)
    }

    @Test
    fun thinkingLinePreviewCollapsesToASingleLine() {
        assertEquals(
            "先看目录 再读文件",
            buildThinkingLinePreview("先看目录\n\n   再读文件   ")
        )
    }

    @Test
    fun thinkingLinePreviewKeepsTheNewestContentVisible() {
        val content = "开头" + "x".repeat(500) + "最新结论"

        val preview = buildThinkingLinePreview(content)

        assertEquals(AgentToolLimits.PROGRESS_REASONING_LINE_CHARS, preview.length)
        assertTrue("行内预览必须停在最新内容上", preview.endsWith("最新结论"))
    }

    @Test
    fun resolvesStepDetailTargetToTheNearestRoundWhenThinkingNamesRepeat() {
        // 每轮思考的 type+name 完全相同（AI 正在思考...），下标漂移时必须取最近的一轮，
        // 否则点开的会是别的一轮的思考内容。
        val round1 = ThinkingStep(
            type = "thinking",
            name = "AI 正在思考...",
            status = "done",
            thinkingContent = "第一轮思考"
        )
        val round2 = round1.copy(thinkingContent = "第二轮思考")
        val tool = ThinkingStep(type = "tool", name = "read_file", status = "done")
        val target = StepDetailTarget(
            cardId = "card-1",
            stepIndex = 2,
            stepType = "thinking",
            stepName = "AI 正在思考..."
        )
        val user = Message(
            id = "message-1",
            role = "user",
            content = "继续",
            thinkingCards = listOf(
                ThinkingCard(
                    id = "card-1",
                    content = "处理完成",
                    isAgent = true,
                    steps = listOf(round1, round2, tool)
                )
            )
        )

        assertEquals("第二轮思考", resolveStepDetailTarget(listOf(user), target)?.thinkingContent)
    }

    @Test
    fun resolvesStepDetailTargetToNullWhenCardIsGone() {
        val target = StepDetailTarget(
            cardId = "missing-card",
            stepIndex = 0,
            stepType = "thinking",
            stepName = "AI 正在思考..."
        )

        assertEquals(null, resolveStepDetailTarget(emptyList(), target))
    }
}
