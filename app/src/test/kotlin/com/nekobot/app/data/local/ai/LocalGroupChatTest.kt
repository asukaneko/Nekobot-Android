package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupChatTest {

    private val participants = listOf(
        LocalGroupParticipant("alice", "爱丽丝", "擅长魔法", "冷静"),
        LocalGroupParticipant("bob", "小明", "擅长机械", "活泼"),
        LocalGroupParticipant("carol", "卡萝尔", "擅长绘画", "温柔")
    )

    @Test
    fun roundRobinRunsEveryCharacterOnceAfterLastSpeaker() {
        val speakers = LocalGroupChat.selectSpeakers(
            config = LocalGroupConfig(speakerStrategy = "round_robin"),
            message = "继续",
            participants = participants,
            lastSpeakerId = "alice"
        )

        assertEquals(listOf("bob", "carol", "alice"), speakers.map { it.id })
    }

    @Test
    fun mentionMatchesCharacterDisplayName() {
        val speakers = LocalGroupChat.selectSpeakers(
            config = LocalGroupConfig(speakerStrategy = "mention"),
            message = "@小明 你怎么看？",
            participants = participants
        )

        assertEquals(listOf("bob"), speakers.map { it.id })
    }

    @Test
    fun characterMentionTriggersUnspokenTargetInOrder() {
        val responses = listOf(
            LocalGroupRoundResponse(
                speakerId = "alice",
                speakerName = "爱丽丝",
                content = "@卡萝尔 你来画一下，@小明 帮忙准备工具。"
            )
        )

        val speakers = LocalGroupChat.collectCrossTalkSpeakers(
            responses = responses,
            participants = participants,
            maxMentions = 5
        )

        assertEquals(listOf("carol", "bob"), speakers.map { it.id })
    }

    @Test
    fun characterCrossTalkDoesNotRepeatCharactersThatAlreadySpoke() {
        val responses = listOf(
            LocalGroupRoundResponse("alice", "爱丽丝", "@小明 你觉得呢？"),
            LocalGroupRoundResponse("bob", "小明", "我同意。")
        )

        val speakers = LocalGroupChat.collectCrossTalkSpeakers(responses, participants)

        assertTrue(speakers.isEmpty())
    }

    @Test
    fun characterCrossTalkSupportsCharacterIdAndMentionLimit() {
        val responses = listOf(
            LocalGroupRoundResponse("alice", "爱丽丝", "请 @bob 和 @carol 继续。")
        )

        val speakers = LocalGroupChat.collectCrossTalkSpeakers(
            responses = responses,
            participants = participants,
            maxMentions = 1
        )

        assertEquals(listOf("bob"), speakers.map { it.id })
    }

    @Test
    fun groupPromptForbidsWritingOtherCharacters() {
        val prompt = LocalGroupChat.buildSystemPrompt("测试群聊", participants, participants.first())

        assertTrue(prompt.contains("当前发言角色: 爱丽丝"))
        assertTrue(prompt.contains("严禁"))
        assertTrue(prompt.contains("绝不能写出其他角色的台词"))
    }

    @Test
    fun historyIncludesAssistantSenderName() {
        assertEquals(
            "【爱丽丝】你好",
            LocalGroupChat.annotateHistoryContent("assistant", "你好", "爱丽丝")
        )
        assertEquals(
            "你好",
            LocalGroupChat.annotateHistoryContent("assistant", "你好", "AI")
        )
    }

    @Test
    fun worldEngineUsesRelationshipWeightAndAvoidsLastSpeaker() {
        val related = participants.mapIndexed { index, participant ->
            participant.copy(relationWeight = when (index) {
                0 -> 90.0
                1 -> 120.0
                else -> 80.0
            })
        }

        val speakers = LocalGroupChat.selectSpeakers(
            config = LocalGroupConfig(speakerStrategy = "world_engine"),
            message = "大家觉得呢？",
            participants = related,
            lastSpeakerId = "alice"
        )

        assertEquals(listOf("bob"), speakers.map { it.id })
    }
}
