package app.lianxu.independent

/** Split presentation at blank lines outside code fences. Each visible segment still belongs to one stored reply. */
internal object ChatBubbleSegments {
    fun forMessage(text: String, mine: Boolean): List<String> = if (mine) listOf(text) else split(text)
    /** While the reply is still streaming the last bubble can keep growing: only bubbles already closed by a blank line count. */
    fun closedWhileStreaming(text: String): Int {
        val parts = split(text)
        // A sentinel after the text becomes its own part only if the text already ended with a separator (outside a code fence).
        val closed = if (split(text + "\uE000").size > parts.size) parts.size else (parts.size - 1).coerceAtLeast(0)
        return closed
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
