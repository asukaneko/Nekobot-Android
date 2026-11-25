package app.lianxu.independent

/** Short local mutations/commit checks share this gate; never held while calling a provider. */
object AppMutationGate {
    private val lock=Any()
    private var revision=0L
    fun epoch():Long=synchronized(lock){revision}
    fun <T> read(action:()->T):T=synchronized(lock){action()}
    fun <T> write(action:()->T):T=synchronized(lock){action().also{revision++}}
}
