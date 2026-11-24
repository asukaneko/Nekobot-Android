package app.lianxu.independent

import kotlinx.coroutines.*

/** Short, ordered durable submissions; cancellation of a network reply cannot eat an accepted input. */
internal class ChatInputQueue<T>(private val scope: CoroutineScope) {
    private val tails = mutableMapOf<String, Deferred<T>>()
    fun submit(role: String, action: suspend (T?) -> T): Deferred<T> {
        val previous = tails[role]
        return scope.async(NonCancellable) {
            val value = previous?.let { runCatching { it.await() }.getOrNull() }
            action(value)
        }.also { tails[role] = it }
    }
}
