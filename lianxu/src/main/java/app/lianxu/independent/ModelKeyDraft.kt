package app.lianxu.independent

/** In-memory editor only; never saved in a Bundle or diagnostics. */
internal class ModelKeyDraft {
    private var revision=0L
    fun invalidate(){revision++}
    fun ticket():Long=revision
    fun accepts(ticket:Long)=ticket==revision
    companion object {
        fun resolve(old:ModelConfig?,draft:ModelConfig,entered:String,saved:()->String):String =
            if(entered.isNotEmpty())entered else if(old?.baseUrl==draft.baseUrl&&old.providerId==draft.providerId)saved() else ""
        fun needsWrite(old:ModelConfig?,draft:ModelConfig,previous:String,current:String)=old!=draft||previous!=current
    }
}
