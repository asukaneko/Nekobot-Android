package app.lianxu.independent

import com.google.gson.JsonObject

internal enum class VoiceVendor { BREEZEBLUE, OTHER, UNKNOWN }
internal enum class VoiceStage { OFFER, MODEL_RESPONSE, TOOL, QUEUE, VALIDATE, SYNTHESIS, TEMP_WRITE, DECODE, CACHE, PRESENTATION }
internal enum class VoiceStatus {
    OFFERED, OMITTED, NOT_CALLED, CALLED, ACCEPTED, REJECTED, NOT_CONFIGURED, STARTED, SUCCEEDED, FAILED, CANCELLED, CACHE_HIT,
    AUDIO_READY, TEXT_FALLBACK, APP_BACKGROUND, OTHER_PAGE, CHAT_VISIBLE, VISIBILITY_UNKNOWN,
    VOICE_RENDERED, TEXT_RENDERED, UNAVAILABLE_RENDERED
}
internal data class VoiceTrace(val turn: String, val attempt: Int, val provider: VoiceVendor = VoiceVendor.UNKNOWN, val partIndex:Int?=null) {
    fun offer(provided: Boolean, unavailable: VoiceReason?, fallback: Boolean = false) {
        val reason = when {
            provided -> null
            fallback -> VoiceReason.UNSUPPORTED_TOOLS
            else -> unavailable ?: VoiceReason.FINAL_FILTER
        }
        record(VoiceStage.OFFER, if (provided) VoiceStatus.OFFERED else VoiceStatus.OMITTED, reason)
    }
    fun response(provided: Boolean, result: TextChatResult) {
        if (!provided || result !is TextChatResult.Success) return
        val called = result.toolCalls.any { it.name == "send_short_voice" }
        record(VoiceStage.MODEL_RESPONSE, if (called) VoiceStatus.CALLED else VoiceStatus.NOT_CALLED)
    }
    fun rejected(target: VoiceTarget?, reason: VoiceReason) =
        record(VoiceStage.TOOL, if (target == null) VoiceStatus.NOT_CONFIGURED else VoiceStatus.REJECTED, reason)
    fun record(stage: VoiceStage, status: VoiceStatus, reason: VoiceReason? = null, elapsedMs: Long? = null) {
        Diagnostics.voice(this, stage, status, reason, elapsedMs)
    }
}
internal enum class VoiceReason {
    ROLE_DISABLED, NO_VOICE, SERVICE_NOT_READY, ROLE_VOICE_BLOCKED, FINAL_FILTER, UNSUPPORTED_TOOLS, NOT_OFFERED, INCOMPLETE_RESPONSE, INVALID_CALL, TARGET_CHANGED, STALE_TARGET_OR_MESSAGE, CACHE_MISSING, DECODER_ERROR, INVALID_AUDIO, IO, UNKNOWN, INVALID_INPUT,
    MISSING_CREDENTIAL, HTTP, HTTP_401, HTTP_403, HTTP_404, HTTP_429, HTTP_5XX, TOO_LARGE,
    BUSY, TIMEOUT, NETWORK, DURATION_UNREADABLE, PLAYER_ERROR, RENAME_FAILED
}

internal object ChatVoiceDiagnostics {
    const val EVENT = "chat.voice"
    private val uuid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    fun validTurn(turn: String) = uuid.matches(turn)

    // Project the speech event from typed fields for the basic export.
    fun basic(data: JsonObject?): JsonObject = JsonObject().apply {
        if (data == null) return@apply
        fun value(key: String) = data[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun integer(key: String) = data[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString
        value("turn")?.takeIf(::validTurn)?.let { addProperty("turn", it) }
        integer("attempt")?.toIntOrNull()?.takeIf { it > 0 }?.let { addProperty("attempt", it) }
        integer("request_number")?.toLongOrNull()?.takeIf { it > 0 }?.let { addProperty("request_number", it) }
        fun allowed(key: String, values: List<Enum<*>>) {
            value(key)?.takeIf { v -> values.any { it.name == v } }?.let { addProperty(key, it) }
        }
        integer("part_index")?.toIntOrNull()?.takeIf { it>=0 }?.let { addProperty("part_index",it) }
        allowed("provider", VoiceVendor.entries)
        allowed("stage", VoiceStage.entries)
        allowed("status", VoiceStatus.entries)
        allowed("reason", VoiceReason.entries)
    }

    // The log is durable. This small process-local index only prevents repeated UI events and
    // distinguishes a voice fallback from ordinary text that has never requested audio.
    private data class Display(var trace: VoiceTrace, var audioReady: Boolean = false,
        val shown: MutableSet<Pair<VoiceStatus, VoiceReason?>> = mutableSetOf())
    private val displays = object : LinkedHashMap<Triple<String, Int, Int?>, Display>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, Int, Int?>, Display>?) = size > 512
    }
    @Synchronized fun outcome(trace: VoiceTrace, status: VoiceStatus, reason: VoiceReason? = null) {
        val display=displays.getOrPut(Triple(trace.turn,trace.attempt,trace.partIndex)) { Display(trace) }
        display.trace=trace
        display.audioReady=status==VoiceStatus.AUDIO_READY
        trace.record(VoiceStage.PRESENTATION, status, reason)
    }
    @Synchronized fun known(turn: ChatTurn,part:Int?=null) = displays.containsKey(Triple(turn.id,turn.attempt,part))
    @Synchronized fun shown(turn: ChatTurn, status: VoiceStatus, reason: VoiceReason? = null, part:Int?=null) {
        val display = displays.getOrPut(Triple(turn.id,turn.attempt,part)) { Display(VoiceTrace(turn.id, turn.attempt,partIndex=part)) }
        val cause = if (status==VoiceStatus.TEXT_RENDERED && display.audioReady) VoiceReason.CACHE_MISSING else reason
        if (display.shown.add(status to cause)) display.trace.record(VoiceStage.PRESENTATION, status, cause)
    }
}
