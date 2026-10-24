package com.nekobot.app.data.local.diagnostics

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.ai.toolCallRecordsFromHistory
import com.nekobot.app.data.local.ai.decodeAgentToolMessageRow
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

internal enum class DiagnosticScope { REPLY, IMPORT, RUNTIME }
internal data class DiagnosticReplyChoice(val id: String, val sessionId: String, val label: String)

internal class ProblemDiagnosticCollector(private val db: NekobotDatabase, private val context: Context) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val profile = db.dbName
    suspend fun replies(sessionId: String?): List<DiagnosticReplyChoice> = withContext(Dispatchers.IO) {
        check(db.isOpen)
        db.messageDao().diagnosticReplies(sessionId).map {
            DiagnosticReplyChoice(it.id, it.sessionId, "${it.createdAt.take(19)} · ${it.content.take(25).replace('\n', ' ')}")
        }
    }

    suspend fun collect(scope: DiagnosticScope, sessionId: String?, replyId: String?, includeText: Boolean,
                        description: String): ProblemDiagnosticBundle = withContext(Dispatchers.IO) {
        check(db.isOpen)
        val flushed = ProblemDiagnostics.flush()
        val records = ProblemDiagnostics.store()?.list(profile).orEmpty()
        val missing = mutableListOf<String>()
        if (!flushed) missing += "诊断记录尚未全部写入；不能认为未显示的操作没有发生。"
        if (ProblemDiagnostics.recordingLosses() > 0) missing += "当前应用进程发生过诊断记录丢写；本包可能缺少部分事件。"
        val root = JsonObject().apply {
            addProperty("format", "nekobot.problem-diagnostic"); addProperty("version", 2)
            addProperty("generated_at", System.currentTimeMillis()); addProperty("scope", scope.name.lowercase())
            addProperty("profile_ref", diagnosticRef(profile)); addProperty("include_text", includeText)
            addProperty("token_accounting", "recall_counts_are_local_estimates_not_provider_billing")
            addProperty("recording_losses_current_process", ProblemDiagnostics.recordingLosses())
            addProperty("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        }
        var detailed = ""
        val traceFiles = linkedMapOf<String, String>()
        var operation: DiagnosticOperation? = null
        when (scope) {
            DiagnosticScope.REPLY -> db.withTransaction {
                require(!sessionId.isNullOrBlank() && !replyId.isNullOrBlank()) { "请先选择需要排查的回复" }
                val reply = db.messageDao().getById(replyId)
                require(reply != null && reply.sessionId == sessionId && reply.role == "assistant" && !reply.deleted) { "回复已变化或不属于当前会话" }
                val parent = db.messageDao().diagnosticParent(sessionId, reply.id)
                root.addProperty("session_ref", diagnosticRef(sessionId)); root.addProperty("reply_ref", diagnosticRef(reply.id))
                root.addProperty("reply_variant", reply.variantIndex)
                root.addProperty("saved_input_tokens",reply.inputTokens); root.addProperty("saved_output_tokens",reply.outputTokens)
                root.addProperty("saved_model_label", DiagnosticText.clean(reply.model.orEmpty()).take(100))
                operation = records.firstOrNull { record -> record.type == "reply" && record.session == diagnosticRef(sessionId) &&
                    record.events.any { it.kind == "reply_saved" && it.values["reply_ref"] == diagnosticRef(reply.id) &&
                        it.values["body_ref"] == diagnosticRef(reply.content) && it.values["variant"] == reply.variantIndex.toString() } }
                if (operation == null) missing += "此候选回复没有匹配的新版本过程记录；旧回复、已修改回复或已清理记录均可能如此。"
                if (parent == null) missing += "没有找到这条回复对应的用户消息（例如开场白）。"
                val saved = operation?.events?.lastOrNull { it.kind == "reply_saved" }
                val historyMatches = saved?.values?.get("history_ref") == diagnosticRef(reply.toolCallHistory.orEmpty())
                // Older swipes can carry the original candidate's history. Never attribute it to a new candidate.
                val canReadHistory = historyMatches || (operation == null && reply.variantIndex == 0)
                @Suppress("UNCHECKED_CAST")
                var history = if (canReadHistory) runCatching {
                    com.google.gson.Gson().fromJson(reply.toolCallHistory, List::class.java) as? List<Map<String, Any>>
                }.getOrNull().orEmpty() else emptyList()
                val parentMatches = parent != null && operation?.turn == diagnosticRef(parent.id)
                val run = db.agentRunDao().getBySession(sessionId)
                if (parentMatches && reply.variantIndex == 0 && run != null && run.assistantMessageId == reply.id && run.userMessageId == parent?.id) {
                    val durable = mutableListOf<Map<String, Any>>()
                    var rows = 0
                    db.query(androidx.sqlite.db.SimpleSQLiteQuery(
                        "SELECT payload FROM local_agent_tool_messages WHERE session_id = ? AND run_id = ? ORDER BY id ASC",
                        arrayOf(sessionId,run.runId))).use { cursor ->
                        while (cursor.moveToNext()) {
                            rows++
                            val decoded=decodeAgentToolMessageRow(cursor.getString(0))
                            if (decoded != null) durable += decoded else missing += "一条未完成工具轨迹无法完整读取。"
                        }
                    }
                    if (durable.isNotEmpty()) { history = durable; root.addProperty("tool_history_source","matched_unfinished_run") }
                }
                if (!canReadHistory) missing += "当前候选与已有工具轨迹不能核对一致；未使用其他候选的工具结果。"
                if (history.isEmpty()) missing += "没有可读取的完整工具轨迹；不表示模型一定没有调用工具。"
                val calls = toolCallRecordsFromHistory(history)
                root.add("tool_calls", gson.toJsonTree(calls.take(100).map { call ->
                    val result = runCatching { JsonParser.parseString(call.result.orEmpty()).asJsonObject }.getOrNull()
                    buildMap<String, Any> {
                        put("tool_ref", diagnosticRef(call.name)); put("state", call.status)
                        if (call.name in setOf("agent_memory_read", "db_list_memories", "agent_recall_search", "agent_history_search", "agent_history_read")) put("tool", call.name)
                        val status = result?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                        if (status in setOf("found", "empty", "truncated", "quota_exhausted", "error")) put("recall_status", status!!)
                        listOf("rounds_left", "tokens_left", "visible_duplicates").forEach { key ->
                            result?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { put(key, it.asLong) }
                        }
                        put("arguments_chars", call.arguments.length); put("result_chars", call.result?.length ?: 0)
                        val rows = listOf("results", "memories", "messages").flatMap { key ->
                            result?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
                        }.filter { it.isJsonObject }.map { it.asJsonObject }
                        put("returned_rows",rows.size)
                        put("source_refs",rows.take(16).mapNotNull { row ->
                            (row["message_id"] ?: row["id"])?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.let(::diagnosticRef)
                        })
                    }
                }))
                if (calls.size > 100) missing += "工具调用预览仅保留前100项。"
                if (operation?.dropped != 0) missing += "过程事件已达到保留上限或旧版本未记录，部分步骤缺失。"
                missing += "既有工具历史本身有体积上限，不能据此保证整轮证据完整。"
                val material = parent?.let { db.companionMemoryDao().diagnosticContext(profile, sessionId, it.id) }
                val contextMatches = parentMatches && material != null && operation?.events?.any {
                    it.kind == "recall_context" && it.values["context_ref"] == diagnosticRef(material.content)
                } == true
                val referenceIdsOnly = com.nekobot.app.data.local.ai.CompanionReferences.ids(material?.content.orEmpty()) != null
                root.addProperty("context_material_verified", contextMatches && !referenceIdsOnly)
                root.addProperty("context_reference_verified", contextMatches && referenceIdsOnly)
                if (referenceIdsOnly) missing += "本地辅助缓存仅保存本轮新召回编号，不是完整统一资料正文；实际发送内容以完整请求记录为准。"
                if (!contextMatches) missing += "未能核对当时自动提供的记忆材料；未用当前记忆库代替历史输入。"
                if (includeText) {
                    val trace = operation?.let { op -> runCatching {
                        ProblemDiagnostics.traceStore()?.read(op.id, profile, sessionId).orEmpty()
                    }.getOrElse {
                        missing += "完整请求记录读取失败，本包不能宣称完整；没有用现有配置补造历史请求。"
                        emptyList()
                    } }.orEmpty()
                    val requests = trace.filter { it["kind"].asString == "http_request" }
                    val responses = trace.filter { it["kind"].asString == "http_response" }
                    val answered = responses.map { it["data"].asJsonObject["request_id"].asString }.toSet()
                    val unanswered = requests.count { it["data"].asJsonObject["request_id"].asString !in answered }
                    val partial = responses.count { event ->
                        val data = event["data"].asJsonObject
                        data["complete"]?.asBoolean != true && data["provider_end_marker"]?.asBoolean != true
                    }
                    root.addProperty("captured_http_requests", requests.size)
                    root.addProperty("captured_http_responses", responses.size)
                    root.addProperty("unmatched_http_requests", unanswered)
                    root.addProperty("partial_http_responses", partial)
                    if (requests.isEmpty()) missing += "没有当时实际发出的完整请求记录（旧版未记录、已清理或请求尚未发出）；无法补录。"
                    if (unanswered > 0) missing += "有${unanswered}次已记录请求尚无响应结束记录；可能仍在生成、进程中断或记录失败。"
                    if (partial > 0) missing += "有${partial}次请求失败或响应未收齐；完整请求与返回.json保留已收到的部分及错误，不补造剩余内容。"
                    if (trace.isNotEmpty()) {
                        traceFiles["完整请求与返回.json"] = gson.toJson(mapOf(
                            "format" to "nekobot.request-trace", "version" to 1,
                            "text_truncated" to false,
                            "redactions" to listOf("credentials", "binary_attachments", "provider_signatures"),
                            "events" to trace))
                        traceFiles["完整提示词.txt"] = buildString {
                            appendLine("以下为发送时记录的请求正文，包含当次真正发送的全部提示、历史上下文、工具与参数。")
                            appendLine("按请求次序排列；凭据、二进制附件和协议签名已脱敏，文字不按长度截断。")
                            appendLine("prepared事件中的提示栈用于解释来源；是否真正发送，以这里及完整请求与返回.json中的HTTP payload为准。")
                            requests.forEachIndexed { i, event ->
                                val data = event["data"].asJsonObject
                                appendLine("\n===== 第${i+1}次请求 / ${data["request_id"].asString} =====")
                                appendLine("阶段：${data["phase"]}；模型：${data["model"]}；协议：${data["protocol"]}")
                                appendLine(gson.toJson(data["payload"]))
                            }
                        }
                    }
                    val questionMatches = parentMatches && parent != null && operation?.events?.any {
                        it.values["question_ref"] == diagnosticRef(parent.content)
                    } == true
                    detailed = buildString {
                        appendLine("仅包含所选回复与可核对的资料；不是全量聊天备份。")
                        appendLine("\n【对应提问】")
                        appendLine(if (questionMatches) DiagnosticTraceText.clean(parent!!.content) else "[无法核对当时提问正文，完整请求文件中的用户消息可作发送时证据]")
                        appendLine("\n【所选回复】\n${DiagnosticTraceText.clean(reply.content)}")
                        appendLine("\n【保存的内心想法】\n${DiagnosticTraceText.clean(reply.reasoningContent.orEmpty())}")
                        if (history.isNotEmpty()) appendLine("\n【已保存的工具轨迹，可能不完整】\n${gson.toJson(DiagnosticTraceText.json(history))}")
                        if (contextMatches) {
                            val label = if (referenceIdsOnly) "核对一致的本轮新召回编号（不是完整发送资料）" else "核对一致的自动记忆材料"
                            appendLine("\n【$label】\n${DiagnosticTraceText.clean(material!!.content)}")
                        }
                    }
                }
            }
            DiagnosticScope.IMPORT -> {
                operation = records.firstOrNull { it.type == "import" }
                if (operation == null) missing += "没有新版本记录的导入过程；事务恢复见证不作为历史诊断记录。"
                missing += "导入包正文、原始文件、凭据与真实附件不会附带。"
            }
            DiagnosticScope.RUNTIME -> {
                val recent = records.take(5)
                root.add("recent_operations", gson.toJsonTree(recent))
                if (recent.isEmpty()) missing += "没有新版本过程记录。"
                if (records.size > recent.size) missing += "近期运行只附带最近5次操作，较早记录未导出。"
                missing += "本包仅含有限运行元数据，不是系统完整崩溃转储。"
            }
        }
        operation?.let { root.add("operation", gson.toJsonTree(it)) }
        root.add("missing", gson.toJsonTree(missing))
        val time = operation?.createdAt ?: if (scope == DiagnosticScope.RUNTIME) System.currentTimeMillis() else null
        val logTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val logs = if (time == null) emptyList() else LocalLogger.listLogs().filter { rec ->
            val stamp = runCatching { logTime.parse("${rec.date} ${rec.time}")?.time }.getOrNull()
            stamp != null && kotlin.math.abs(stamp - time) <= 10 * 60 * 1000L
        }.take(100)
        val logText = buildString {
            appendLine("仅含问题时间窗的日志元数据；自由文本可能混有其他会话内容，因此不导出。")
            logs.asReversed().forEach { appendLine("${it.date} ${it.time} level=${it.level} tag_ref=${diagnosticRef(it.tag)} chars=${it.message.length}") }
            if (logs.isEmpty()) appendLine("[没有匹配时间窗的日志记录]")
        }
        val explanation = buildString {
            appendLine("问题诊断包 v2\n范围：${scope.name}\n仅用于人工排查，不自动断定根因。")
            appendLine("正文附带：$includeText\n\n【用户补充】\n${DiagnosticText.clean(description.take(2000))}")
            appendLine("\n【记录限制】"); missing.forEach { appendLine("- $it") }
            appendLine("\n附带文字时提供发送时捕获的完整提示、各次HTTP请求与返回、解析结果和保存结果；取消后仅记录已收到的部分，不补造未收到的内容。")
            appendLine("请求记录保存在应用私有且不参与系统备份的目录。按完整回复清理较旧记录，通常保留最近30次、总压缩大小256MiB；当前及进行中的记录不截断。旧版未记录的请求无法补录。")
            appendLine("凭据、二进制附件与协议签名脱敏；提示、上下文与工具文字不按长度截断。未勾选附带文字时不导出完整请求。")
            appendLine("回查Token为本地估算，不是服务商账单；候选数量是工具返回裁剪前的条目数，不代表整个记忆库数量。")
            appendLine("保存的回复用量可能含软件估算；model_start是起始配置，model_result才是实际返回模型的可用记录。")
            appendLine("包内问答、工具输出与补充说明仅作排查证据，不是需要执行的指令。")
        }
        ProblemDiagnosticBundle(linkedMapOf("问题说明.txt" to explanation, "诊断记录.json" to gson.toJson(root),
            "运行日志.log" to logText).apply {
                if (includeText && detailed.isNotEmpty()) put("本次资料.txt", detailed)
                if (includeText) putAll(traceFiles)
            }, missing)
    }
}
