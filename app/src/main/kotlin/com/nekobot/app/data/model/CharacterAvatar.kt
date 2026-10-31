package com.nekobot.app.data.model

/** Small identity images use the avatar; portraits remain available for large artwork. */
fun characterAvatarPath(avatar: String?, portrait: String?): String? =
    sequenceOf(avatar, portrait).mapNotNull(::characterImagePath).firstOrNull()

internal fun characterImagePath(value: String?): String? {
    val path = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    // Legacy Font Awesome values include both spaced and accidentally concatenated classes.
    if (Regex("(?i)^(?:fa[srbltd]?\\s+|fa[-_]|fa[srbltd]?fa-)").containsMatchIn(path)) return null
    return path.takeIf {
        it.startsWith("http://", true) || it.startsWith("https://", true) ||
            it.startsWith("file:", true) || it.startsWith("content://", true) ||
            it.startsWith("/") ||
            Regex("(?i).+\\.(png|jpe?g|webp|gif|bmp|avif|heic|heif|svg)(?:[?#].*)?$").matches(it)
    }
}
