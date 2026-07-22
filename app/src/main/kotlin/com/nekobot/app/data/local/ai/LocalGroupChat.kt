package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import kotlin.random.Random

/** 本地群聊成员的调度/提示词所需摘要。 */
data class LocalGroupParticipant(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    /** 对齐原仓库 WorldEngine：好感 + 信任*0.8 + 熟悉度*0.5。 */
    val relationWeight: Double? = null
)

/** 一轮群聊中已完成的角色回复，用于解析角色间 @。 */
data class LocalGroupRoundResponse(
    val speakerId: String,
    val speakerName: String,
    val content: String
)

/**
 * 本地群聊配置，对齐原仓库 nbot/group/models.py:GroupConfig 的核心字段。
 *
 * Android 目前按顺序执行同一轮的多个角色，确保后发言角色能看到前面角色的回复；
 * 这对应原仓库 round_robin_mode=sequential 的行为。
 */
data class LocalGroupConfig(
    val speakerStrategy: String = "mention",
    val roundRobinMode: String = "sequential",
    val maxCharsPerTurn: Int = 800,
    val allowCharacterCrossTalk: Boolean = true,
    val crossTalkMaxMentions: Int = 5,
    val sharedMemory: Boolean = true
) {
    companion object {
        fun fromJson(json: String?): LocalGroupConfig {
            if (json.isNullOrBlank()) return LocalGroupConfig()
            return runCatching {
                val obj = JsonParser.parseString(json).asJsonObject
                LocalGroupConfig(
                    speakerStrategy = obj.get("speaker_strategy")?.asString
                        ?.takeIf { it.isNotBlank() } ?: "mention",
                    // 本地使用顺序执行；仍保留字段，便于导入/展示原仓库配置。
                    roundRobinMode = obj.get("round_robin_mode")?.asString
                        ?.takeIf { it.isNotBlank() } ?: "sequential",
                    maxCharsPerTurn = obj.get("max_chars_per_turn")?.asInt ?: 800,
                    allowCharacterCrossTalk = obj.get("allow_character_cross_talk")?.asBoolean ?: true,
                    crossTalkMaxMentions = (obj.get("cross_talk_max_mentions")?.asInt ?: 5)
                        .coerceIn(0, 20),
                    sharedMemory = obj.get("shared_memory")?.asBoolean ?: true
                )
            }.getOrDefault(LocalGroupConfig())
        }
    }
}

/** 本地群聊调度与提示词构建，对齐原仓库 SpeakerScheduler 的 Web 会话路径。 */
object LocalGroupChat {

    /**
     * 对齐原仓库 group/cross_talk.py：从本轮角色回复中收集被 @ 的角色。
     * 已在本轮发言的角色不重复触发，同一目标只触发一次，并限制最大数量。
     */
    fun collectCrossTalkSpeakers(
        responses: List<LocalGroupRoundResponse>,
        participants: List<LocalGroupParticipant>,
        maxMentions: Int = 5
    ): List<LocalGroupParticipant> {
        if (responses.isEmpty() || participants.isEmpty() || maxMentions <= 0) return emptyList()
        val alreadySpoken = responses.mapTo(linkedSetOf()) { it.speakerId }
        return responses
            .flatMap { response -> parseMentions(response.content, participants) }
            .distinctBy(LocalGroupParticipant::id)
            .filterNot { it.id in alreadySpoken }
            .take(maxMentions)
    }

    /** 按文本中的出现顺序解析 @角色ID / @角色名，支持中文名和带连字符的 ID。 */
    fun parseMentions(
        text: String,
        participants: List<LocalGroupParticipant>
    ): List<LocalGroupParticipant> {
        if (text.isBlank() || participants.isEmpty()) return emptyList()
        data class Match(val position: Int, val participant: LocalGroupParticipant)

        val matches = buildList {
            participants.forEach { participant ->
                listOf(participant.id, participant.name)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinctBy(String::lowercase)
                    .forEach { alias ->
                        val needle = "@$alias"
                        var searchStart = 0
                        while (searchStart < text.length) {
                            val position = text.indexOf(
                                string = needle,
                                startIndex = searchStart,
                                ignoreCase = true
                            )
                            if (position < 0) break
                            val next = text.getOrNull(position + needle.length)
                            val endsHere = next == null ||
                                !(next.isLetterOrDigit() || next == '_' || next == '-')
                            if (endsHere) add(Match(position, participant))
                            searchStart = position + 1
                        }
                    }
            }
        }
        return matches
            .sortedBy(Match::position)
            .distinctBy { it.participant.id }
            .map(Match::participant)
    }

    /**
     * 决定本轮发言角色。
     *
     * round_robin 与原仓库 Web 群聊一致：从上次发言者之后开始，每名角色各回复一次。
     * 其他策略每轮选择一个角色；world_engine 在本地没有独立世界引擎时使用相关度策略兜底。
     */
    fun selectSpeakers(
        config: LocalGroupConfig,
        message: String,
        participants: List<LocalGroupParticipant>,
        lastSpeakerId: String? = null,
        random: Random = Random.Default
    ): List<LocalGroupParticipant> {
        if (participants.isEmpty()) return emptyList()
        return when (config.speakerStrategy.lowercase()) {
            "round_robin" -> rotateAfter(participants, lastSpeakerId)
            "mention" -> listOf(findMention(message, participants) ?: participants.first())
            "random" -> listOf(participants[random.nextInt(participants.size)])
            "relevance" -> listOf(
                findMention(message, participants)
                    ?: findMostRelevant(message, participants)
                    ?: participants[random.nextInt(participants.size)]
            )
            "world_engine" -> listOf(selectByWorldEngine(message, participants, lastSpeakerId))
            "narrator_driven" -> listOf(participants.first())
            else -> listOf(findMention(message, participants) ?: participants.first())
        }
    }

    /** 构建当前发言角色的群聊约束；完整角色卡由 CharacterRuntime 单独注入。 */
    fun buildSystemPrompt(
        groupName: String,
        participants: List<LocalGroupParticipant>,
        speaker: LocalGroupParticipant
    ): String = buildString {
        appendLine("# 群聊场景")
        appendLine("群聊名称: $groupName")
        appendLine("当前发言角色: ${speaker.name} (${speaker.id})")
        appendLine()
        appendLine("## 参与角色")
        participants.forEach { participant ->
            val description = participant.description.take(80)
            append("- **${participant.name}** (${participant.id})")
            if (description.isNotBlank()) append(": $description")
            appendLine()
            if (participant.personality.isNotBlank()) {
                appendLine("  性格: ${participant.personality.take(60)}")
            }
        }
        appendLine()
        appendLine("## 群聊规则")
        appendLine("- 你现在只扮演 ${speaker.name}，以该角色的身份和口吻回复")
        appendLine("- 回复要简洁自然，符合角色性格")
        appendLine("- 【严禁】代替其他角色发言、回答或行动。你只能控制自己扮演的角色，绝不能写出其他角色的台词、动作或心理活动")
        appendLine("- 如果需要其他角色回应，请使用 @角色名 让该角色自己回答")
        appendLine("- 保持角色一致性，不要跳出设定")
        appendLine("- 只在有需要时 @群聊中已列出的角色，不要滥用 @")
    }.trim()

    /** 让模型能在共享历史中区分每名群聊角色。 */
    fun annotateHistoryContent(role: String, content: String, sender: String?): String {
        val cleanSender = sender?.trim().orEmpty()
        if (cleanSender.isBlank()) return content
        if (role == "assistant" && cleanSender.equals("AI", ignoreCase = true)) return content
        return "【$cleanSender】$content"
    }

    private fun rotateAfter(
        participants: List<LocalGroupParticipant>,
        lastSpeakerId: String?
    ): List<LocalGroupParticipant> {
        val lastIndex = participants.indexOfFirst { it.id == lastSpeakerId }
        val startIndex = if (lastIndex < 0) 0 else (lastIndex + 1) % participants.size
        return participants.indices.map { offset ->
            participants[(startIndex + offset) % participants.size]
        }
    }

    private fun findMention(
        message: String,
        participants: List<LocalGroupParticipant>
    ): LocalGroupParticipant? {
        val lower = message.lowercase()
        return participants.firstOrNull { participant ->
            val id = participant.id.lowercase()
            val name = participant.name.lowercase()
            "@$id" in lower || "@$name" in lower ||
                (name.isNotBlank() && name in lower) ||
                (id.isNotBlank() && id in lower)
        }
    }

    private fun findMostRelevant(
        message: String,
        participants: List<LocalGroupParticipant>
    ): LocalGroupParticipant? {
        val lower = message.lowercase()
        if (lower.isBlank()) return null
        val scored = participants.map { participant ->
            var score = 0
            if (participant.name.isNotBlank() && participant.name.lowercase() in lower) score += 20
            if (participant.id.isNotBlank() && participant.id.lowercase() in lower) score += 10
            val keywords = (participant.description + " " + participant.personality)
                .lowercase()
                .split(Regex("[\\s,，。.!！?？;；:：、]+"))
                .filter { it.length >= 2 }
                .distinct()
            score += keywords.count { it in lower }
            score += ((participant.relationWeight ?: 0.0) / 20.0).toInt()
            participant to score
        }
        val maxScore = scored.maxOfOrNull { it.second } ?: 0
        return scored.firstOrNull { maxScore > 0 && it.second == maxScore }?.first
    }

    /** 对齐原仓库规则版 WorldEngine：@ → 关系权重 → 名称关键词 → 排除上次发言者轮换。 */
    private fun selectByWorldEngine(
        message: String,
        participants: List<LocalGroupParticipant>,
        lastSpeakerId: String?
    ): LocalGroupParticipant {
        val lower = message.lowercase()
        participants.firstOrNull { participant ->
            "@${participant.id.lowercase()}" in lower ||
                (participant.name.isNotBlank() && "@${participant.name.lowercase()}" in lower)
        }?.let { return it }

        val candidates = participants.filterNot { it.id == lastSpeakerId }.ifEmpty { participants }
        val withRelationship = candidates.filter { it.relationWeight != null }
        if (withRelationship.isNotEmpty()) {
            return withRelationship.maxBy { it.relationWeight ?: 0.0 }
        }

        participants.firstOrNull { participant ->
            participant.name.isNotBlank() && participant.name.lowercase() in lower
        }?.let { return it }

        return candidates.first()
    }
}
