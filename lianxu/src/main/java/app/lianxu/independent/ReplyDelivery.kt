package app.lianxu.independent

import kotlinx.coroutines.CompletableDeferred

/** The transcript contains only bubbles acknowledged by the page, never a generated tail. */
internal class ReplyDelivery {
    private val earlier = mutableListOf<String>()
    private var current = emptyList<String>()
    private var shown = 0
    private var finished = false
    private var stopped = false
    private var restored = emptyList<String>()
    val completed = CompletableDeferred<Unit>()
    val bubbles get() = earlier + current
    val visible get() = earlier.size + shown
    val spoken get() = (earlier + current.take(shown)).joinToString("\n\n")

    fun restore(text:String) {
        check(earlier.isEmpty()&&current.isEmpty())
        restored = ChatBubbleSegments.split(text)
        earlier += restored
    }

    /** The ledger restored the already shown prefix before the durable tail becomes displayable. */
    fun restoreFinal(bubbles:List<String>){
        check(current.isEmpty()&&!finished&&!stopped)
        val transcript=earlier.joinToString("\n\n")
        val count=(0..bubbles.size).firstOrNull{bubbles.take(it).joinToString("\n\n")==transcript}
            ?:error("stored_reply_prefix_mismatch")
        earlier.clear()
        earlier+=bubbles.take(count)
        current=bubbles.drop(count)
        finished=true
        settle()
    }

    fun update(text: String, final: Boolean = false) {
        if (stopped) return
        val raw = ChatBubbleSegments.split(text)
        var repeated=0
        while(repeated<minOf(raw.size,restored.size)&&raw[repeated]==restored[repeated])repeated++
        val parts = raw.drop(repeated)
        // A tool can start another response. What was already shown still belongs to the chat.
        if (current.take(shown) != parts.take(shown)) nextResponse()
        current = if (final) parts else parts.take((ChatBubbleSegments.closedWhileStreaming(text)-repeated).coerceAtLeast(0))
        finished = final
        settle()
    }
    /** A late tool cannot rewrite a bubble the user has already seen. Ignore its parallel
     * assistant content when it was still a draft, and retain prefixes from previous rounds. */
    fun finishOrdered(parts: List<ReplyPart>): List<ReplyPart>? {
        if(stopped) return null
        var repeated=0
        while(repeated<minOf(restored.size,parts.size) && restored[repeated]==parts[repeated].text) repeated++
        val planned = parts.drop(repeated)
        val previousRoundSize = earlier.size - restored.size
        val overlap = (minOf(planned.size, previousRoundSize) downTo 1).firstOrNull { count ->
            earlier.takeLast(count) == planned.take(count).map { it.text }
        } ?: 0
        val tail = planned.drop(overlap)
        if(current.take(shown)!=tail.take(shown).map { it.text }) return null
        current=tail.map { it.text }
        finished=true
        settle()
        return earlier.dropLast(overlap).map { ReplyPart(it,false) } + planned
    }

    fun nextResponse() {
        if (stopped) return
        earlier += current.take(shown)
        current = emptyList(); shown = 0; finished = false
    }
    fun acknowledge(count: Int) {
        if (stopped) return
        shown = maxOf(shown, (count - earlier.size).coerceIn(0, current.size))
        settle()
    }
    fun stop(): String { stopped = true; return spoken }
    private fun settle() { if (finished && shown == current.size) completed.complete(Unit) }
}
