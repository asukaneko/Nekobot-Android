package app.lianxu.independent

/** Display only: the original message remains the source for every operation (memory, relation,
 *  retry, delete, copy all act on the one stored reply). Her reply is cut at blank lines into at most
 *  [MAX_BUBBLES] bubbles; anything beyond joins the last one. */
internal object ChatBubbleSegments {
    const val MAX_BUBBLES = 4
    fun forMessage(text: String, mine: Boolean): List<String> = if (mine) listOf(text) else capped(split(text))
    fun capped(parts: List<String>): List<String> =
        if (parts.size <= MAX_BUBBLES) parts else parts.take(MAX_BUBBLES - 1) + parts.drop(MAX_BUBBLES - 1).joinToString("\n\n")
    /** While the reply is still streaming the last bubble can keep growing: only bubbles already closed by a blank line count. */
    fun closedWhileStreaming(text: String): Int {
        val parts = split(text)
        // A sentinel after the text becomes its own part only if the text already ended with a separator (outside a code fence).
        val closed = if (split(text + "\uE000").size > parts.size) parts.size else (parts.size - 1).coerceAtLeast(0)
        return minOf(closed, MAX_BUBBLES - 1)
    }
    fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var fenced = false
        val tokens = Regex("```|\\n\\s*\\n")
        for (match in tokens.findAll(text)) {
            if (match.value == "```") fenced = !fenced
            else if (!fenced) {
                parts += text.substring(start, match.range.first)
                start = match.range.last + 1
            }
        }
        parts += text.substring(start)
        return parts.map { part -> part.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n") }.filter { it.isNotBlank() }
    }
}
