package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

internal data class ReplyPart(val text: String, val voice: Boolean)

internal object OrderedChatReply {
    const val PREFIX = "chat-reply-layout:"
    val definition = mapOf("type" to "function", "function" to mapOf(
        "name" to "send_short_voice",
        "description" to "根据角色、上下文和对方当下的偏好决定怎么说。全部用文字时直接自然回复；不方便听时用文字。想发语音或对方希望听你说时，用这个工具一次提交整轮回复：segments 按实际发送顺序列出每条文字或语音，条数和顺序随对话需要。语音段要像真人开口说话一样自然口语化，贴合角色口吻，别念书面语、括弧动作或标点；文字段按平时聊天习惯写。只写要说的原话，不另发正文，不写类型标签。",
        "parameters" to mapOf("type" to "object", "additionalProperties" to false, "required" to listOf("segments"),
            "properties" to mapOf("segments" to mapOf("type" to "array", "items" to mapOf(
                "type" to "object", "additionalProperties" to false, "required" to listOf("type","text"),
                "properties" to mapOf("type" to mapOf("type" to "string","enum" to listOf("text","voice")), "text" to mapOf("type" to "string"))
            ))))
    ))

    fun parse(raw: String): List<ReplyPart>? = runCatching {
        require(raw.length <= 32000)
        val obj = JSONObject(raw)
        val parts = if (obj.has("segments")) {
            require(!obj.has("text"))
            val array = obj.getJSONArray("segments")
            require(array.length() in 1..128)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val type = item.get("type")
                require(type == "text" || type == "voice")
                ReplyPart(item.get("text") as String, type == "voice")
            }
        } else listOf(ReplyPart(obj.get("text") as String, true))
        require(parts.sumOf { it.text.length } + 2*(parts.size-1) <= 16000)
        require(parts.filter { it.voice }.sumOf { it.text.codePointCount(0,it.text.length) } <= 2400)
        require(parts.all { part ->
            val text = part.text
            text.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(text) &&
                text.none { it.isISOControl() && (part.voice || it != '\n' && it != '\t') } &&
                (!part.voice || text.codePointCount(0,text.length) <= 300)
        })
        parts
    }.getOrNull()

    fun body(parts: List<ReplyPart>) = parts.joinToString("\n\n") { it.text }

    // Keep source text in the message only. Signed lengths recover exact boundaries and media types.
    fun encode(parts: List<ReplyPart>): String = JSONObject()
        .put("body", stableDigest(body(parts)))
        .put("sizes", JSONArray(parts.map { if(it.voice) it.text.length else -it.text.length })).toString()

    fun decode(body: String, layout: String?): List<ReplyPart>? {
        if(layout == null) return null
        return runCatching {
            val obj = JSONObject(layout)
            require(obj.getString("body") == stableDigest(body))
            val sizes = obj.getJSONArray("sizes")
            require(sizes.length() in 1..body.length)
            var offset=0
            List(sizes.length()) { i ->
                val signed=sizes.getInt(i)
                require(signed != 0 && signed != Int.MIN_VALUE)
                if(i>0){require(body.startsWith("\n\n",offset));offset+=2}
                val length=kotlin.math.abs(signed)
                require(length <= body.length-offset)
                ReplyPart(body.substring(offset,offset+length),signed>0).also { offset+=length }
            }.also { require(offset==body.length) }
        }.getOrNull()
    }

    fun save(db: SQLiteDatabase, turn: String, layout: String?) {
        if(layout!=null) db.insertOrThrow("preferences",null,ContentValues().apply {
            put("key",PREFIX+turn);put("value",layout)
        })
    }

    fun audioKey(turn: ChatTurn, body: String, index: Int) =
        ChatVoiceDelivery.key(turn.friendId,turn.id,turn.attempt,"part:$index:"+stableDigest(body))
}
