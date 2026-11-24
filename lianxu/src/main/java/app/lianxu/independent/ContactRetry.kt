package app.lianxu.independent

/** Transport/reentry safety only. These delays never constrain the model's chosen timestamp. */
internal object ContactRetry {
    const val FAILED_RETRY_DELAY_MS=15*60_000L
    const val REENTRY_DELAY_MS=30_000L
    fun delay(planned:Long,now:Long,reentry:Boolean)=(planned-now).coerceAtLeast(if(reentry)REENTRY_DELAY_MS else 0)
}
