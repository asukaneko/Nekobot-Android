package app.lianxu.independent

import org.json.JSONObject

data class ChatReplyQuote(val messageId: Long, val mine: Boolean, val snippet: String, val mediaKind: String = "text") {
    fun encode() = JSONObject().put("messageId",messageId).put("mine",mine)
        .put("snippet",shorten(snippet)).put("mediaKind",mediaKind).toString()
    fun label() = listOfNotNull(when(mediaKind) {
        "image" -> "[图片]"
        "video" -> "[视频]"
        "voice" -> "[语音]"
        else -> null
    }, snippet.takeIf { it.isNotBlank() }).joinToString(" ")
    fun prefix(persona: UserPersona) = "[引用${if(mine) persona.addressee() else "你"}之前说的话：「${label()}」]\n"
    companion object {
        const val PREFIX = "chat-reply-quote:"
        fun shorten(text: String): String {
            val line = text.trim().replace(Regex("""\s+"""), " ")
            return line.substring(0,line.offsetByCodePoints(0,minOf(200,line.codePointCount(0,line.length))))
        }
        fun decode(raw: String?): ChatReplyQuote? = raw?.let { runCatching {
            val j=JSONObject(it)
            val kind=j.getString("mediaKind")
            val id=j.getLong("messageId")
            require(id>0 && kind in setOf("text","image","video","voice"))
            ChatReplyQuote(id,j.getBoolean("mine"),shorten(j.getString("snippet")),kind)
        }.getOrNull() }
    }
}
