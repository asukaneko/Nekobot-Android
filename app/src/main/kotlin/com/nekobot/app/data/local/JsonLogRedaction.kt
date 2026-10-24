package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonParser

private val logJsonString = Regex(""""(?:\\.|[^"\\])*"""")
private val logJsonKey = Regex("""("(?:\\.|[^"\\])*")\s*:\s*""")
private val credentialNames = setOf("authorization", "xauthtoken", "apikey", "password", "token",
    "secret", "accesstoken", "refreshtoken", "idtoken", "clientsecret")

/** Handles JSON embedded in an error prefix, escaped field names and encoded JSON strings. */
internal fun redactJsonLogCredentials(message: String, depth: Int = 0): String {
    if (depth > 3) return "<redacted>"
    val decodedStrings = logJsonString.replace(message) { match ->
        val decoded = runCatching { JsonParser.parseString(match.value).asString }.getOrNull()
        if (decoded != null && logJsonKey.containsMatchIn(decoded)) {
            val safe = redactJsonLogCredentials(decoded, depth + 1)
            if (safe != decoded) Gson().toJson(safe) else match.value
        } else match.value
    }
    val output = StringBuilder()
    var copied = 0
    for (match in logJsonKey.findAll(decodedStrings)) {
        if (match.range.first < copied) continue
        val key = runCatching { JsonParser.parseString(match.groupValues[1]).asString }.getOrNull()
            ?.lowercase(java.util.Locale.ROOT)?.replace(Regex("[_ -]"), "") ?: continue
        if (key !in credentialNames) continue
        val start = match.range.last + 1
        val end = jsonLogValueEnd(decodedStrings, start)
        output.append(decodedStrings, copied, start).append("\"<redacted>\"")
        copied = end
    }
    return output.append(decodedStrings, copied, decodedStrings.length).toString()
}

/** A truncated quoted value is redacted through EOF; braces and escaped quotes are not value boundaries. */
private fun jsonLogValueEnd(text: String, start: Int): Int {
    if (start >= text.length) return start
    var quoted = false
    var escaped = false
    var nesting = 0
    val stringValue = text[start] == '"'
    val container = text[start] == '{' || text[start] == '['
    for (i in start until text.length) {
        val c = text[i]
        if (quoted) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') {
                quoted = false
                if (stringValue) return i + 1
            }
        } else when {
            c == '"' -> quoted = true
            container && (c == '{' || c == '[') -> nesting++
            container && (c == '}' || c == ']') -> { if (--nesting == 0) return i + 1 }
            !container && (c.isWhitespace() || c in ",;}]" ) -> return i
        }
    }
    return text.length
}
