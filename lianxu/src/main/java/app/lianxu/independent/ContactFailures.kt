package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase

internal enum class ContactFailureKind { MODEL_CONFIG, NETWORK, MODEL_RESPONSE, OTHER }
/** detail: the raw error for the user (HTTP status plus provider text, or exception class plus message), keys masked. */
internal data class ContactFailure(val kind:ContactFailureKind,val at:Long,val detail:String="")
/** 「上次没发出去」提示卡和弹窗里的字。只有真连不上服务商（NETWORK）才说「网络连不上」。 */
internal object ContactFailureText {
    private val status=Regex("^HTTP (\\d{3})")
    fun summary(f:ContactFailure):String = "她想联系你，但没发出去。"+when(f.kind){
        ContactFailureKind.NETWORK->"网络连不上。"
        ContactFailureKind.MODEL_CONFIG->"模型配置有问题，去大模型配置里看看。"
        ContactFailureKind.MODEL_RESPONSE->status.find(f.detail)?.let{"服务商返回 HTTP ${it.groupValues[1]}。"}?:"服务商返回了错误。"
        ContactFailureKind.OTHER->"出了点问题。"
    }
    /** 卡片上截一行原文预览。 */
    fun preview(detail:String)=detail.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.joinToString(" · ").take(160)
    /** 弹窗全文：先一句人话，再原样报错（存的时候密钥已遮掉）。 */
    fun full(f:ContactFailure)=buildString{
        append(summary(f))
        if(f.detail.isNotBlank())append(if(f.detail.startsWith("HTTP "))"\n\n服务返回的原文：\n" else "\n\n详细原因：\n").append(f.detail)
    }
    fun time(at:Long,now:java.time.Instant=java.time.Instant.now(),zone:java.time.ZoneId=java.time.ZoneId.systemDefault())=ChatTimeLabels.label(at,now,zone).removeSurrounding("[","]")
}
internal object ContactFailures {
    /** The raw error of a failed proactive contact. Local only, like chat errors: never backed up. */
    const val ERROR_PREFIX="contact-error:"
    fun errorKey(opportunity:Any)=ERROR_PREFIX+opportunity
    fun migrate(db:SQLiteDatabase){
        // Safe to run twice: a development database may already carry the columns under an older version number.
        val existing=db.rawQuery("PRAGMA table_info(companion_opportunities)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        if("fail_kind" !in existing)db.execSQL("ALTER TABLE companion_opportunities ADD COLUMN fail_kind TEXT")
        if("fail_at" !in existing)db.execSQL("ALTER TABLE companion_opportunities ADD COLUMN fail_at INTEGER")
    }
    fun classify(error:Throwable):ContactFailureKind {
        val seen=mutableSetOf<Throwable>()
        var e:Throwable?=error
        while(e!=null&&seen.add(e)){
            if(e is CompanionModelFailure)return when(e.failure){
                ChatFailure.MISSING_CONFIG,ChatFailure.INVALID_CONFIG,ChatFailure.MISSING_MEMORY_MODEL,ChatFailure.GO_ENDPOINT_UNVERIFIED->ContactFailureKind.MODEL_CONFIG
                // Same rule as chat: only a real "cannot reach the service" is a network problem.
                ChatFailure.NETWORK->ContactFailureKind.NETWORK
                ChatFailure.UNAUTHORIZED,ChatFailure.FORBIDDEN,ChatFailure.PAYMENT,ChatFailure.RATE_LIMIT,ChatFailure.REDIRECT,ChatFailure.SERVER,ChatFailure.REJECTED,ChatFailure.FORMAT,ChatFailure.STREAM_LIMIT,ChatFailure.STREAM_PROTOCOL,ChatFailure.STRUCTURED_FORMAT,ChatFailure.REPLY_TEXT_INVALID,ChatFailure.TRUNCATED,ChatFailure.UNSUPPORTED_TOOLS->ContactFailureKind.MODEL_RESPONSE
                else->ContactFailureKind.OTHER
            }
            e=e.cause
        }
        return if(unreachable(error))ContactFailureKind.NETWORK else ContactFailureKind.OTHER
    }
    /** What actually went wrong, as is: the provider's HTTP status and text when there is one, else the exception class and message. */
    fun detail(error:Throwable,secrets:List<String> = emptyList()):String {
        val seen=mutableSetOf<Throwable>();var e:Throwable?=error;var model:CompanionModelFailure?=null
        while(e!=null&&seen.add(e)){if(e is CompanionModelFailure){model=e;break};e=e.cause}
        val raw=model?.let{if(it.detail.isNotBlank())it.detail else "${it.failure.name}: ${it.failure.display}"}?:"${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        return Diagnostics.redact(raw,secrets).take(HttpsTextChatClient.DETAIL_LIMIT)
    }
}
