package app.lianxu.independent

import com.google.gson.JsonObject

/** Basic export projects only known producers and primitive values with a defined domain. */
internal object BasicDiagnosticFields {
    private val network = setOf("network.request","network.response","network.cancelled","network.failed",
        "http.phase","http.request.body","http.request_sent","http.response","http.first_byte","http.last_byte",
        "http.transfer_end","http.write_progress","http.stream_progress","http.stream_rejected","http.error_body",
        "http.error_body.unavailable","http.format_failed","http.network_failed","http.tls_failed","http.timeout",
        "http.total_timeout","http.abort_requested","http.disconnect_failed","http.usage",
        "model.directory.start","model.directory.result","model.directory.cancelled","model.directory.failed",
        "model.http.request","model.http.status","model.http.response","model.http.failed","model.http.format_failed",
        "model.http.timeout","model.http.tls_failed","model.http.abort_requested","model.http.disconnect_failed",
        "embedding.http.request","embedding.http.status","embedding.http.response","embedding.http.result","embedding.http.failed",
        "embedding.http.disconnect_failed")
    private val transactions = setOf("model.batch","backup.export","backup.import","backup.legacy.import","db.transaction")
        .flatMap { base -> listOf("start","complete","failed","cancelled").map { "$base.$it" } }.toSet() +
        setOf("chat.persisted","chat.result","chat.transition","chat.failed","chat.cancelled","chat.storage.failed",
            "chat.display.save.failed","chat.settle.failed","chat.reasoning.shown","chat.reply.notification.failed","backup.page.failed","backup.vault.cleanup.failed",
            "db.transaction.begin","db.transaction.commit","db.transaction.rollback","db.transaction.end_failed")
    private val structured = setOf("companion.structured.attempt","companion.structured.accepted","companion.structured.rejected")
    private val service = setOf("service.request.begin","service.created","service.foreground.active","service.foreground.failed",
        "service.stop_requested","service.timeout","service.destroyed","service.stopped")
    private val stages = setOf("appraise","interpret","control","reconsider","reply_batch_review")
    private val failureStages = setOf("connect_or_write","await_response_headers","read_response_body")
    private val phases = setOf("queued","prepare_body","open_connection_object","acquire_output_stream","write_body",
        "close_output_stream","await_response_headers","read_response_body")
    private val failures = ChatFailure.entries.map { it.name }.toSet() + ConfigError.entries.map { it.name }
    private val errors = setOf("SocketException","SocketTimeoutException","ConnectException","UnknownHostException",
        "SSLException","SSLHandshakeException","SSLPeerUnverifiedException","IOException","EOFException",
        "ProtocolException","JSONException","IllegalArgumentException","IllegalStateException","SecurityException",
        "ForegroundServiceStartNotAllowedException","CancellationException","TimeoutCancellationException","SQLiteException","SQLiteFullException")
    fun project(event: String, data: JsonObject): JsonObject? {
        if (event in LocalStep.events) return LocalStep.project(event, data)
        if (event == ChatVoiceDiagnostics.EVENT) return ChatVoiceDiagnostics.basic(data)
        val feature = listOf("memory.", "review.", "light_review.", "learning.", "daily_contact.", "proactive.", "contact.", "video.").any(event::startsWith) || event == "daily.plan.failure"
        if (!feature && event !in network && event !in transactions && event !in structured && event !in service) return null
        val out = JsonObject()
        fun string(key: String) = data[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun number(key: String, range: LongRange) {
            data[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
                ?.takeIf { it in range }?.let { out.addProperty(key, it) }
        }
        fun flag(key: String) {
            data[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.let { out.addProperty(key, it.asBoolean) }
        }
        fun choice(key: String, choices: Set<String>) {
            string(key)?.takeIf { it in choices }?.let { out.addProperty(key, it) }
        }
        for (key in listOf("request_id","turn","span","parent_span")) {
            string(key)?.takeIf(ChatVoiceDiagnostics::validTurn)?.let { out.addProperty(key,it) }
        }
        for (key in listOf("attempt","request_number","step")) number(key, 0L..Int.MAX_VALUE.toLong())
        string("error_type")?.substringAfterLast('.')?.let { out.addProperty("error_type",if(it in errors) it else "OTHER") }
        choice("reason", failures)
        if (feature) {
            choice("kind", ContactFailureKind.entries.map { it.name }.toSet())
            choice("reason", failures + setOf("optional_timeout","too_long","non_final_segment","superseded"))
            choice("stage", stages)
            choice("status", setOf("success","failure","cancelled","skipped"))
            choice("outcome", setOf("success","failure","cancelled","skipped","error"))
            choice("commit_state", setOf("committed","discarded"))
            number("http_status",100L..599)
            for (key in listOf("count","added","frames","duration_ms","operation_count","operations","judge_calls","auxiliary_rounds"))
                number(key,0L..Int.MAX_VALUE.toLong())
            flag("saved")
            return out.takeIf { it.size() > 0 }
        }
        if (event in network || event in structured) {
            choice("stage", stages)
            number("model_attempt", 1L..StructuredModelCall.MAX_ATTEMPTS.toLong())
        }
        if (event in network) {
            number("status",100L..599); number("http_status",100L..599)
            choice("status",setOf("success","failure"))
            for(key in listOf("request_bytes","response_bytes","planned_body_bytes","write_attempted_bytes","client_write_completed_bytes",
                "timeout_ms","connect_timeout_ms","read_timeout_ms","first_byte_ms","last_byte_ms","chunks","stream_chars")) number(key,0L..Int.MAX_VALUE.toLong())
            flag("stream"); flag("after_terminal_decision"); flag("saw_background"); flag("network_blocked")
            choice("request_role",setOf("ordinary_chat","aux_vision"))
            if (event=="http.request_sent") flag("voice_tool_provided")
            choice("terminal_status",failures + setOf("SUCCESS","CANCELLED","EXCEPTION"))
            choice("transport_phase",phases)
            choice("failure_stage",failureStages)
            choice("cancel_source",setOf("none","total_timeout","socket_timeout","caller_cancellation","coroutine_timeout","cancellation_unknown"))
            choice("cancel_origin",setOf("caller","source_control","role_deleted","backup_replacement","user_stop","notification_stop","service_timeout","service_rejected","service_destroyed"))
            choice("cause_relation",setOf("primary_local_timeout","after_local_timeout","after_local_cancellation","cancellation_origin_unknown","unattributed"))
        } else if (event in service) {
            for(key in listOf("active_requests","promoted_aborted")) number(key,0L..Int.MAX_VALUE.toLong())
            for(key in listOf("ignoring_battery_optimizations","power_save_mode","background_restricted","notifications_enabled","chat_channel_blocked")) flag(key)
            choice("restrict_background_status",setOf("disabled","whitelisted","enabled","unknown"))
        } else {
            flag("saved"); flag("retry")
            if (event == "chat.reasoning.shown") {
                choice("failure", failures)
                choice("outcome", setOf("success","failure","cancelled","error","storage"))
            }
            choice("state",TurnState.entries.map { it.name }.toSet())
            choice("scope",setOf("all","role"))
        }
        return out.takeIf { it.size()>0 }
    }
}
