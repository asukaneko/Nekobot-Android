package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 仅供单会话角色的发送格式；不改写旧记忆或历史正文。 */
internal object CompanionPromptLanguage {
    const val diaryRule = "记忆正文用角色第一人称、日记口吻写成一句话，以‘我’自称，写清对方和实际参与者；忠于原话，不把独自经历写成共同经历。日期单独依据原话填写，未知就留空，不在正文里编日期。"
    const val recallRules = "想不起来的事，可以先翻一翻记忆再回答；翻过还是没有，就老实说记不清了，不要编。和记忆对不上时，自然地问一句确认；已经确认过的事就接受，不反复追问。"
    const val bubbleRule = "像微信聊天一样，一条消息表达一个意思；内容多时用<||>分成几条短消息，不用空行代替分条。一句简短回应可以只发一条，不必为凑条数拆句。"
    const val speakingRule = "以既有性格和称呼与对方持续相处，默认直接聊眼前的生活，不每轮重演设定、写剧情、动作旁白或替对方说话；对方明确想写故事时再配合。亲近程度随双方已明确的关系和偏好，不默认人人都是恋人。" +
        "先回应具体的分享或感受，语气和长短贴合当下，不套安慰模板、分析报告或每轮撒娇。可以温和表达不同意见，简短说明缘由，不把附和当关心。" +
        "自然接续有依据的旧话题，适度关心但不盘问；通常不必反问，确有必要时只问一个具体问题。对方说忙、晚安或想结束时自然收尾，不用问题拖住。" +
        "分清已知事实、猜测、计划和对方的新更正；记不清就核实或直说，未知日期留空，不编共同经历、用户事实或承诺。" +
        "不声称有现实身体、线下行动或一直在后台等候、思念；工具和提醒只按实际能力与成功结果说明，不用每轮声明AI，被问到身份能力时诚实回答。" +
        "尊重对方的现实关系和自主选择，不排他、不监控、不靠内疚挽留。表达自然具体，不复述这些规则；已有自定义人设、关系和说话方式保留。"
    const val styleReminder = speakingRule + bubbleRule

    fun memory(memory: CompanionMemoryEntity, today: LocalDate = LocalDate.now()): String {
        val text = memory(memory.content, memory.eventDate, memory.annualMonthDay, today)
        // 纯文本发送副本也必须保留未核验声明；存储正文和已核验记忆不加说明。
        return if (!memory.manual && memory.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED) {
            "（${CompanionImportPolicy.evidence(memory)}）$text"
        } else text
    }

    fun memory(content: String, eventDate: String? = null, annualMonthDay: String? = null,
        today: LocalDate = LocalDate.now()): String {
        val date = eventDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val annual = annualMonthDay?.let { runCatching { MonthDay.parse("--$it") }.getOrNull() }
        val prefix = annual?.let { "${it.monthValue}月${it.dayOfMonth}日：" } ?: date?.let { when (it) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            today.minusDays(2) -> "前天"
            else -> (if (it.year == today.year) "" else "${it.year}年") + "${it.monthValue}月${it.dayOfMonth}日"
        } + "：" }
            .orEmpty()
        return prefix + content.replace(Regex("[\\r\\n]+"), " ").trim()
    }

    fun memories(rows: List<CompanionMemoryEntity>, today: LocalDate = LocalDate.now()): String = rows.joinToString("\n") { memory(it, today) }

    fun stickers(names: List<String>): String {
        // 不改名称这个匹配键；无法在正文中无歧义表达的名字不列出。
        val usable = names.filter { it.isNotBlank() && it.length <= 60 && it.none { c -> c in "[]\r\n" } }
            .distinct().take(60)
        if (usable.isEmpty()) return ""
        return "你可以发表情，在正文里写[表情名]就行，现在能用的有：" +
            usable.joinToString("、") { "[$it]" } + "。偶尔用，别每句都发。"
    }

    fun clock(now: ZonedDateTime, previous: LocalMessageEntity?): String {
        val period = when (now.hour) { in 0..5 -> "凌晨"; in 6..11 -> "上午"; in 12..17 -> "下午"; else -> "晚上" }
        val stamp = now.format(DateTimeFormatter.ofPattern("uuuu年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)) +
            " $period${now.format(DateTimeFormatter.ofPattern("h:mm", Locale.ROOT))}"
        val elapsed = previous?.let { sentAt(it, now) }?.takeIf { !it.isAfter(now.toInstant()) }
            ?.let { "，距离你们上一条消息过去了${interval(Duration.between(it, now.toInstant()))}" }.orEmpty()
        return "现在是$stamp$elapsed。这只是让你把握语气的参考，不用主动提起。"
    }

    fun interval(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        return when {
            minutes < 1 -> "不到1分钟"
            minutes < 60 -> "${minutes}分钟"
            minutes < 1440 -> "${minutes / 60}小时" + (minutes % 60).takeIf { it > 0 }?.let { "${it}分钟" }.orEmpty()
            else -> "${minutes / 1440}天" + (minutes % 1440 / 60).takeIf { it > 0 }?.let { "${it}小时" }.orEmpty()
        }
    }

    private fun sentAt(row: LocalMessageEntity, now: ZonedDateTime): Instant? =
        listOf(row.createdAt, row.timestamp).firstNotNullOfOrNull { value ->
            if (value.length > 64) null else runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).atZone(now.zone).toInstant() }.getOrNull()
                ?: value.takeIf { it.matches(Regex("\\d{10}|\\d{13}")) }?.toLongOrNull()?.let {
                    runCatching { if (value.length == 13) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }.getOrNull()
                }
        }

    /** 回查去重和计费仍用内部结构，只有发送副本转成人话。原话读取保留分页所需结构。 */
    fun message(message: Map<String, Any>): Map<String, Any> {
        val cleaned = CompanionRequestUsage.clean(message)
        if (message["role"] != "tool") return cleaned
        val history = message["name"] in setOf("agent_history_search", "agent_history_read")
        if (!history && message["name"] !in setOf("agent_memory_read", "agent_recall_search")) return cleaned
        val result = runCatching { Gson().fromJson(message["content"] as? String, Map::class.java) }.getOrNull() ?: return cleaned
        if (history) return cleaned + ("content" to Gson().toJson(historyResult(result)))
        val rows = (result["memories"] ?: result["results"]) as? List<*>
        val body = rows.orEmpty().mapNotNull {
            val row = it as? Map<*, *> ?: return@mapNotNull null
            val content = row["content"] as? String ?: return@mapNotNull null
            // 兼容已保存的工具结果：它们的正文尚未带来源说明，不能在转纯文本时丢掉 evidence。
            val notice = "（${CompanionImportPolicy.UNVERIFIED_EVIDENCE}）"
            if (row["evidence"] == CompanionImportPolicy.UNVERIFIED_EVIDENCE && !content.startsWith(notice)) {
                notice + content
            } else content
        }.joinToString("\n")
        val incomplete = result["success"] != false && result["recall_stopped"] != true &&
            (result["status"] == "truncated" || result["budget_limited"] == true ||
                result["more_candidates"] == true || rows.orEmpty().any { (it as? Map<*, *>)?.get("truncated") == true })
        val note = when {
            result["success"] == false -> "这次没能翻到记忆，记不清的地方就直说。"
            body.isNotBlank() -> if (incomplete) "$body\n这次只翻到这些，还有一些没翻完。" else body
            incomplete -> "这次还没翻完。"
            result["visible_duplicates"] != null -> "相关内容前面已经翻到了，可以接着那些回忆说。"
            result["recall_stopped"] == true -> "先用已经想起来的事回答，记不清的地方就直说。"
            else -> "这次没有找到相关记忆；不代表这件事没有发生过。"
        }
        return cleaned + ("content" to note)
    }

    private val historyFields = setOf("success", "historical_data_only", "query", "anchor_message_id", "error")
    private val historyRowFields = setOf("message_id", "role", "timestamp", "snippet", "content",
        "text_offset", "next_text_offset", "truncated")
    private val historyNotes = setOf("这次没能翻到原话，记不清的地方就直说。",
        "先用已经想起来的事回答，记不清的地方就直说。", "这次只翻到这些，还有一些没翻完。",
        "这次还没翻完。", "相关内容前面已经翻到了，可以接着那些回忆说。",
        "这次没有找到相关原话；不代表这件事没有发生过。")

    /** 仅收紧发送副本；原始结果仍供累计用量、来源去重和诊断读取。 */
    private fun historyResult(result: Map<*, *>): Map<String, Any> {
        val output = mutableMapOf<String, Any>()
        for (field in historyFields) result[field]?.let { output[field] = it }
        val rows = mutableListOf<Map<*, *>>()
        for (field in listOf("results", "messages")) {
            (result[field] as? List<*>)?.let { entries ->
                output[field] = entries.mapNotNull { it as? Map<*, *> }.map { row ->
                    rows += row
                    row.filterKeys { it in historyRowFields }
                }
            }
        }
        val incomplete = result["status"] == "truncated" || result["budget_limited"] == true ||
            result["more_candidates"] == true || rows.any { it["truncated"] == true }
        val note = when {
            result["success"] == false -> "这次没能翻到原话，记不清的地方就直说。"
            result["recall_stopped"] == true -> "先用已经想起来的事回答，记不清的地方就直说。"
            incomplete -> if (rows.isEmpty()) "这次还没翻完。" else "这次只翻到这些，还有一些没翻完。"
            // 再次处理已呈现副本时保留含义，不把‘未查完’改成‘没找到’。
            result["note"] in historyNotes -> result["note"] as String
            rows.isNotEmpty() -> null
            result["visible_duplicates"] != null -> "相关内容前面已经翻到了，可以接着那些回忆说。"
            else -> "这次没有找到相关原话；不代表这件事没有发生过。"
        }
        if (note != null) output["note"] = note
        return output
    }
}
