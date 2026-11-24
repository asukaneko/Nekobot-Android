package app.lianxu.independent

/** Published bubbles are append-only, including delayed saves and retries. */
internal object SpokenReply {
    fun merge(saved:String,shown:String):String=when {
        saved.isEmpty()->shown
        shown.isEmpty()||shown==saved||saved.startsWith(shown+"\n\n")->saved
        shown.startsWith(saved+"\n\n")->shown
        else->error("A displayed reply cannot be rewritten")
    }
}
