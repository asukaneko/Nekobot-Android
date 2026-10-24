package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.companionTransferName
import com.nekobot.app.data.local.db.CompanionContextMaterialEntity
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime

class CompanionNaturalPromptTest {
    private val now = ZonedDateTime.parse("2026-10-17T10:24:00+08:00[Asia/Shanghai]")
    private fun row(id: String = "old", text: String = "早安", time: String = "2026-10-17T07:24:00+08:00") =
        LocalMessageEntity(id = id, sessionId = "s", role = "user", content = text, timestamp = time, createdAt = time)

    @Test fun clockUsesChineseDateWeekdayAndElapsedHours() {
        assertEquals("现在是2026年10月17日 星期六 上午10:24，距离你们上一条消息过去了3小时。这只是让你把握语气的参考，不用主动提起。",
            CompanionPromptLanguage.clock(now, row()))
    }

    @Test fun elapsedTimeUsesMinutesHoursAndDays() {
        for ((minutes, expected) in listOf(0L to "不到1分钟", 1L to "1分钟", 59L to "59分钟", 60L to "1小时",
            61L to "1小时1分钟", 1439L to "23小时59分钟", 1440L to "1天", 3060L to "2天3小时")) {
            assertEquals(expected, CompanionPromptLanguage.interval(Duration.ofMinutes(minutes)))
        }
    }

    @Test fun unknownMissingAndFutureMessageTimesDoNotInventAnInterval() {
        for (previous in listOf(null, row(time = "未知"), row(time = now.plusHours(1).toString()))) {
            assertFalse(CompanionPromptLanguage.clock(now, previous).contains("上一条消息"))
        }
        assertTrue(CompanionPromptLanguage.clock(now, row(time = "2026-10-17T07:24:00")).contains("3小时"))
        assertTrue(CompanionPromptLanguage.clock(now.withHour(22), null).contains("晚上10:24"))
    }

    @Test fun memoryUsesEventDateAndOnlyDiaryBodyWithoutInternalFields() {
        val memory = CompanionMemoryEntity("secret-id", "s", "key", 2, "episode", "角色", "我陪他改好了简历。", "active",
            createdAt = "2026-10-17", updatedAt = "2026-10-17", eventDate = "2026-10-12", expiresAt = "2099-01-01")
        assertEquals("10月12日：我陪他改好了简历。", CompanionCoreMemory.render(listOf(memory), now.toLocalDate()))
        assertEquals("我陪他改好了简历。", CompanionPromptLanguage.memory(memory.copy(eventDate = null)))
        assertEquals(memory.content, "我陪他改好了简历。")
        for (field in listOf("id", "key", "type", "confidence", "source", "expires", "status")) {
            assertFalse(field, CompanionPromptLanguage.memory(memory).contains(field))
        }
    }

    @Test fun importedUnverifiedCoreAndRecallTextRetainEvidenceWithoutEditingStoredBody() {
        val memory = CompanionMemoryEntity("m", "s", "k", 1, "fact", "玩家", "我记得他喜欢茉莉茶。", "active",
            alwaysProvide = true, provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED,
            createdAt = "2026-10-17", updatedAt = "2026-10-17", eventDate = "2026-10-12")
        val expected = "（${CompanionImportPolicy.UNVERIFIED_EVIDENCE}）10月12日：我记得他喜欢茉莉茶。"
        assertEquals(expected, CompanionCoreMemory.render(listOf(memory), now.toLocalDate()))
        assertEquals(expected, CompanionPromptLanguage.memories(listOf(memory), now.toLocalDate()))
        assertEquals("我记得他喜欢茉莉茶。", memory.content)
        for (trusted in listOf(memory.copy(manual = true), memory.copy(provenance = CompanionImportPolicy.IMPORTED_VERIFIED))) {
            assertEquals("10月12日：我记得他喜欢茉莉茶。", CompanionPromptLanguage.memory(trusted, now.toLocalDate()))
        }
    }

    @Test fun savedMemoryToolEvidenceSurvivesPlainTextConversionExactlyOnce() {
        val notice = "（${CompanionImportPolicy.UNVERIFIED_EVIDENCE}）"
        for ((name, key) in listOf("agent_memory_read" to "memories", "agent_recall_search" to "results")) {
            for (content in listOf("旧记忆原文。", notice + "旧记忆原文。")) {
                val payload = Gson().toJson(mapOf(key to listOf(mapOf("content" to content,
                    "evidence" to CompanionImportPolicy.UNVERIFIED_EVIDENCE))))
                val source = mapOf<String, Any>("role" to "tool", "name" to name, "content" to payload)
                assertEquals(notice + "旧记忆原文。", CompanionPromptLanguage.message(source)["content"])
                assertEquals(payload, source["content"])
            }
        }
    }

    @Test fun invalidDatesStayUnknownAndAnnualDatesRemainAvailable() {
        assertEquals("我记得他的生日。", CompanionPromptLanguage.memory("我记得他的生日。", "2026-02-30"))
        assertEquals("5月20日：我记得他的生日。", CompanionPromptLanguage.memory("我记得他的生日。", annualMonthDay = "05-20"))
    }

    @Test fun companionStackHidesKeysAndOrdinaryStackIsVerbatim() {
        val stack = PromptStack().apply { add("character.runtime_state", "有点开心"); add("output.extra_notes", "心里话", 15) }
        assertEquals("设定\n\n## output.extra_notes\n心里话\n\n## character.runtime_state\n有点开心", stack.render("设定"))
        assertEquals("设定\n\n心里话\n\n有点开心", stack.render("设定", companionMode = true))
        assertEquals(listOf("character.runtime_state", "output.extra_notes"), stack.keys)
    }

    @Test fun companionThinkingExtractsChineseBodyThoughtAndKeepsMonologueSeparate() {
        val prompt = characterThinkingPrompt(CharacterThinkingSettings(), "小满", "小舟", companionMode = true)
        for (word in listOf("token", "上下文", "模型", "系统", "output.")) assertFalse(word, prompt.contains(word, true))
        // 默认文案不点名、不带占位符，任何角色通用；姓名替换由自定义文案覆盖。
        assertFalse(prompt.contains("{{")); assertTrue(prompt.contains("保持既有性格和称呼"))
        // 默认只指导内部判断，不要求把推理或伪思考写入正文；既有流式解析能力保持兼容。
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("（内心：…）"))
        assertTrue(prompt.contains("不公开内部推理"))
        assertFalse(prompt.contains("先在开头用一对中文圆括号"))
        val thinking = StringBuilder(); val spoken = StringBuilder()
        val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(onContentChunk = spoken::append,
            onThinkingChunk = thinking::append), bodyOnly = true)
        stream.callbacks.onThinkingChunk("Let me consider the response.")
        listOf("<think>我", "有点想他", "。</think>", "早上好。", "（内心：见到他真开心。）", "<||>吃饭了吗？").forEach(stream.callbacks.onContentChunk)
        stream.finish()
        assertEquals("我有点想他。", thinking.toString())
        assertEquals("早上好。（内心：见到他真开心。）<||>吃饭了吗？", spoken.toString())
    }

    @Test fun companionCustomThoughtGoesToRequestTailOnEveryToolAndClosingRequest() {
        val prompt = characterThinkingPrompt(CharacterThinkingSettings(customPrompt = "我是{{char}}，想到{{user}}就开心。"),
            "小满", "小舟", companionMode = true)
        val user = mapOf<String,Any>("role" to "user", "content" to "我现在喜欢什么茶？")
        var messages = listOf(mapOf<String,Any>("role" to "system", "content" to "角色设定"), user)
        repeat(3) { turn ->
            messages = messages + mapOf("role" to "assistant", "content" to "", "tool_calls" to emptyList<Any>()) +
                mapOf("role" to "tool", "name" to "agent_history_search", "tool_call_id" to "call-$turn", "content" to "原话")
            val request = withCharacterThinkingRule(messages, prompt, enabled = true)
            assertFalse(request.first()["content"].toString().contains("我是小满，想到小舟就开心。"))
            val lastUser = request.last { it["role"] == "user" }
            assertEquals(user, lastUser)
            assertEquals("system", request.last()["role"])
            assertEquals(prompt, request.last()["content"])
            assertEquals(1, request.count { it["role"] == "system" && it["content"] == prompt })
        }
        val final = CompanionRecallFinal.finalMessages(messages)
        assertEquals(CompanionRecallFinal.FINAL_NOTE, final.last()["content"])
    }

    @Test fun companionUsesOneBubbleRuleAndNoDefaultMonologueRule() {
        val stack = PromptStack().apply { buildCharacterInjections(this, CharacterProfile(), companionMode = true) }
        val split = requireNotNull(stack.get("output.bubble_split")).content
        assertEquals(CompanionPromptLanguage.bubbleRule, split)
        assertEquals(CompanionPromptLanguage.speakingRule, stack.get(COMPANION_SPEAKING_STYLE_KEY)?.content)
        assertTrue(split.contains("内容多时用<||>分成几条短消息"))
        assertTrue(split.contains("一句简短回应可以只发一条"))
        assertFalse(split.contains("想分成"))
        // 默认不再教独白：这一层不在栈里，说话方式里也没有独白要求，用户想写自己在说话方式里加。
        assertNull(stack.get("output.inner_monologue"))
        assertFalse(CompanionPromptLanguage.speakingRule.contains("内心"))
        assertFalse(stack.render("设定", companionMode = true).contains("内心独白格式"))
    }

    @Test fun companionThinkingRequestsAreIdempotentAndKeepOrdinaryFormat() {
        val original = listOf(mapOf<String, Any>("role" to "system", "content" to "设定"))
        var companion = original
        repeat(3) { companion = withCharacterThinkingRequest(companion, "我的心里话", companionMode = true) }
        assertEquals("我的心里话\n\n设定", companion.single()["content"])
        assertEquals("## output.thinking_style\n我的心里话\n\n设定",
            withCharacterThinkingRequest(original, "我的心里话").single()["content"])
    }

    @Test fun fileAndInternetDefinitionsAreExcludedOnlyFromCompanionCopy() {
        val names = listOf("agent_memory_read", "agent_memory_update", "agent_recall_search", "agent_history_search", "agent_history_read",
            "list_stickers", "send_sticker", "get_date_time", "search_web", "web_fetch", "get_weather", "read_image", "generate_image",
            "workspace_list_files", "workspace_read_file", "workspace_write_file", "workspace_parse_file", "workspace_file_info", "workspace_extract_epub")
        val original = names.map { mapOf<String, Any>("type" to "function", "function" to mapOf("name" to it)) }
        val snapshot = Gson().toJson(original)
        assertEquals((names.take(8) + "generate_image").toSet(), FusedCompanionTools.definitions(original).map(::toolNameOf).toSet())
        assertEquals(snapshot, Gson().toJson(original))
    }

    @Test fun stickerNamesAreExactUniqueBoundedAndOptional() {
        assertEquals("", CompanionPromptLanguage.stickers(emptyList()))
        val prompt = CompanionPromptLanguage.stickers(listOf("开心", "抱抱", "开心", "[错名]", "换\n行") + (1..100).map { "表情$it" })
        assertTrue(prompt.contains("[开心]、[抱抱]")); assertTrue(prompt.contains("偶尔用"))
        assertEquals(60, Regex("\\[[^]]+]").findAll(prompt.substringAfter("现在能用的有：")).count())
        assertFalse(prompt.contains("[表情59]")); assertFalse(prompt.contains("[错名]"))
    }

    @Test fun recallAndFinalRulesAreShortNaturalAndDoNotExposeCounters() {
        for (rules in listOf(CompanionRecallBudget.QUERY_RULES, CompanionRecallFinal.FINAL_NOTE)) {
            for (word in listOf("token", "额度", "rounds_left", "tokens_left", "status", "上下文", "工具")) assertFalse(word, rules.contains(word, true))
            assertTrue(rules.contains("记不清"))
        }
    }

    @Test fun memoryToolPresentationKeepsInternalResultForDeduplicationButSendsDiaryLines() {
        val data = mapOf("memories" to listOf(mapOf("id" to "private", "key" to "resume", "expires_at" to "2099", "content" to "10月12日：我陪他改好了简历。")),
            "status" to "found", "rounds_left" to 2, "tokens_left" to 400)
        val source = mapOf<String, Any>("role" to "tool", "name" to "agent_memory_read", "tool_call_id" to "call", "content" to Gson().toJson(data))
        val sent = CompanionPromptLanguage.message(source)
        assertEquals("10月12日：我陪他改好了简历。", sent["content"])
        assertEquals("call", sent["tool_call_id"])
        assertEquals(Gson().toJson(data), source["content"])
        assertEquals(source, CompanionRequestUsage.clean(source))
    }

    @Test fun unifiedReferencesFollowHistoryAndClockFollowsUnchangedPlayerMessage() {
        val previous = row()
        val current = row("current", "回来啦", now.toOffsetDateTime().toString())
        val cached = CompanionContextMaterialEntity("s", previous.id, 1, 1, "stable", previous.id,
            "旧轮参考", "old-time-key", 0, "关键词", Gson().toJson(mapOf("role" to "user", "content" to previous.content)))
        fun build(time: ZonedDateTime) = FusedCompanionContext.assembleMessages("角色设定\n" + CompanionPromptLanguage.styleReminder, listOf(previous), listOf(cached), "本轮参考",
            CompanionPromptLanguage.clock(time, previous),
            mapOf("role" to "user", "content" to current.content), current)
        val first = build(now); val second = build(now.plusMinutes(1))
        assertEquals(first.dropLast(2), second.dropLast(2))
        assertEquals("早安", first[1]["content"])
        assertEquals("本轮参考", first[2]["content"])
        assertEquals("system", first[2]["role"])
        assertTrue(first.first()["content"].toString().contains(CompanionPromptLanguage.styleReminder))
        assertEquals("回来啦", first[first.lastIndex - 1]["content"])
        assertFalse(first.any { it["content"] == "旧轮参考" })
        val text = first.joinToString("\n") { it["content"].toString() }
        assertEquals(1, Regex("现在是").findAll(text).count())
        assertFalse(text.contains("software_context")); assertFalse(text.contains("message_metadata")); assertFalse(text.contains("历史消息日期"))
        assertNotEquals("fused-visible-core-v4", FusedCompanionPolicy.contextFormat)
    }

    @Test fun pipelineHidesGeneratedKeysAndIgnoresCompanionSessionPersona() = runBlocking {
        val ctx = PipelineContext(ChatRequest.forLocal("s", "哈哈")).apply {
            metadata[META_FUSED_COMPANION] = true
            metadata["session_mode"] = "agent"
            metadata["reasoning_effort"] = "high"
            metadata["user_persona"] = "叫我小舟，我是设计师。"
            characterTurn = CharacterTurnContext(profile = CharacterProfile(name = "小满"), promptText = "我是小满。")
        }
        val callbacks = object : PipelineCallbacks() {
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>, tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                assertFalse(dynamic.contains("output."))
                return messages
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        val system = ctx.messages.first()["content"].toString()
        assertFalse(system.contains("叫我小舟，我是设计师。"))
        assertFalse(system.contains("## ")); assertFalse(system.contains("user.persona")); assertFalse(system.contains("output.thinking_style"))
        assertFalse(system.contains("小满本人的心里话"))
        assertEquals(0, promptSectionCounts(ctx.messages)[PromptSection.PLAYER] ?: 0)
        assertFalse(CompanionRequestUsage.clean(ctx.messages.first()).containsKey(CompanionRequestUsage.SYSTEM_SECTIONS))
    }

    @Test fun conversionNameAndDiaryRulesMatchEverydayConsolidation() {
        assertEquals("林小满", companionTransferName("林小满"))
        assertEquals("原名（Agent）", companionTransferName("原名（Agent）"))
        assertEquals("2026-10-23.memory-v2", CompanionConversionRules.version)
        assertTrue(CompanionConversionRules.prompt.contains(CompanionPromptLanguage.diaryRule))
        assertTrue(FusedCompanionPolicy.extractionPrompt.contains(CompanionPromptLanguage.diaryRule))
        assertTrue(CompanionConversionRules.prompt.contains(CompanionPromptLanguage.recallRules))
    }

    @Test fun naturalMemorySectionsStillHaveAccurateAccountingAndNoEmptyMemoryCount() {
        val text = "每次提供的核心记忆：\n我记得他叫小舟。\n5月20日：我记得他的生日。\n本轮相关记忆：\n10月12日：我陪他改了简历。\n\n其他参考"
        val snapshot = CompanionRequestUsage.snapshot(listOf(CompanionRequestUsage.auxiliary(text)), emptyList(), 32000, emptyMap(), 1)
        assertEquals(2, snapshot.breakdown.parts.single { it.part == ContextUsagePart.CORE_MEMORY }.itemCount)
        assertEquals(1, snapshot.breakdown.parts.single { it.part == ContextUsagePart.RECALLED_MEMORY }.itemCount)
        val empty = CompanionRequestUsage.auxiliary("每次提供的核心记忆：\n\n本轮相关记忆：\n\n\n其他参考")
        val parts = CompanionRequestUsage.snapshot(listOf(empty), emptyList(), 32000, emptyMap(), 1).breakdown.parts
        assertFalse(parts.any { it.part == ContextUsagePart.CORE_MEMORY || it.part == ContextUsagePart.RECALLED_MEMORY })
    }
}
