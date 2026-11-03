package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.local.LocalLogger
import java.io.IOException

internal class IncompleteChatStream : IOException(messageText()) {
    companion object {
        fun messageText(): String = LocalLogger.str(R.string.ai_stream_incomplete)
    }
}

/** EOF alone is not a successful response; a content-part end is not a Responses turn end. */
internal class LocalStreamCompletion(private val protocol: LocalProtocol) {
    private var completed = false
    fun observe(data: String) {
        if (data == "[DONE]") { completed = true; return }
        val terminal = if (protocol.name == OpenAIResponsesProtocol.name) {
            val type = runCatching { JsonParser.parseString(data).asJsonObject["type"]?.asString }.getOrNull()
            type in setOf("response.completed", "response.done", "response.incomplete")
        } else !protocol.parseStreamFinishReason(data).isNullOrBlank() ||
            (protocol.name == AnthropicMessagesProtocol.name && runCatching {
                JsonParser.parseString(data).asJsonObject["type"]?.asString == "message_stop"
            }.getOrDefault(false))
        if (terminal) completed = true
    }
    fun requireComplete() { if (!completed) throw IncompleteChatStream() }
}
