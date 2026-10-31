package com.nekobot.app.data.local

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.Strictness
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read-only historical records. They are never represented as chat messages or favorite IDs. */
internal data class ImportedFavoriteSnapshot(
    val personaName: String,
    val favoriteTime: String,
    val messageTime: String,
    val messageContent: String,
    val forwardedContent: String
)

internal data class ImportedFavoriteSnapshotResult(
    val items: List<ImportedFavoriteSnapshot> = emptyList(),
    val unreadable: Boolean = false
)

internal const val MAX_IMPORTED_FAVORITES_BYTES = 2 * 1024 * 1024
private const val MAX_IMPORTED_FAVORITES_COUNT = 1_000
private val snapshotGson = GsonBuilder().setStrictness(Strictness.STRICT).create()

/** Repository identity distinguishes a reopened database even when its profile name is unchanged. */
internal class ImportedFavoriteSnapshotOwner(
    private val profile: String,
    private val revision: Long,
    private val repository: Any
) {
    fun permits(isLocal: Boolean, profile: String, revision: Long, repository: Any): Boolean =
        isLocal && this.profile == profile && this.revision == revision && this.repository === repository
}

/** The caller must validate ownership in the active database both before and after this read. */
internal fun readImportedFavoriteSnapshots(
    filesDir: File,
    sessionId: String,
    canReadActiveSession: () -> Boolean
): ImportedFavoriteSnapshotResult {
    if (!canReadActiveSession() || !sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
        sessionId.equals("shared", ignoreCase = true)) return ImportedFavoriteSnapshotResult()
    val result = runCatching {
        val files = filesDir.canonicalFile
        val workspace = File(files, "workspace").canonicalFile
        val session = File(workspace, sessionId).canonicalFile
        val target = File(session, "未匹配收藏.json").canonicalFile
        // Direct parents also reject directory symlinks that escape or switch sessions.
        require(workspace.parentFile == files && workspace.name == "workspace" &&
            session.parentFile == workspace && session.name == sessionId &&
            target.parentFile == session && target.name == "未匹配收藏.json")
        if (!target.exists()) return@runCatching ImportedFavoriteSnapshotResult()
        require(target.isFile && target.length() <= MAX_IMPORTED_FAVORITES_BYTES)
        val text = target.inputStream().use { input ->
            val bytes = ByteArray(MAX_IMPORTED_FAVORITES_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            require(count <= MAX_IMPORTED_FAVORITES_BYTES)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        }
        parseImportedFavoriteSnapshots(text)
    }.getOrElse { ImportedFavoriteSnapshotResult(unreadable = true) }
    return if (canReadActiveSession()) result else ImportedFavoriteSnapshotResult()
}

internal fun parseImportedFavoriteSnapshots(text: String): ImportedFavoriteSnapshotResult = runCatching {
    require(text.length <= MAX_IMPORTED_FAVORITES_BYTES)
    val root = snapshotGson.fromJson(text.removePrefix("\uFEFF"), JsonElement::class.java)
    require(root != null && root.isJsonArray && root.asJsonArray.size() <= MAX_IMPORTED_FAVORITES_COUNT)
    val items = root.asJsonArray.map { element ->
        require(element.isJsonObject)
        val record = element.asJsonObject
        fun field(name: String, numeric: Boolean = false): String {
            val value = record.get(name) ?: return ""
            if (value.isJsonNull) return ""
            require(value.isJsonPrimitive)
            require(value.asJsonPrimitive.isString || (numeric && value.asJsonPrimitive.isNumber))
            return value.asString
        }
        ImportedFavoriteSnapshot(
            personaName = field("personaName"),
            favoriteTime = field("favoriteTime", numeric = true),
            messageTime = field("messageTime", numeric = true),
            messageContent = field("messageContent"),
            forwardedContent = field("forwardedContent")
        ).also { require(it.messageContent.isNotBlank() || it.forwardedContent.isNotBlank()) }
    }
    ImportedFavoriteSnapshotResult(items)
}.getOrElse { ImportedFavoriteSnapshotResult(unreadable = true) }

internal fun importedSnapshotTimestamp(raw: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val epoch = raw.toLongOrNull() ?: return raw
    val instant = runCatching {
        when (raw.length) {
            13 -> Instant.ofEpochMilli(epoch)
            10 -> Instant.ofEpochSecond(epoch)
            else -> return raw
        }
    }.getOrNull() ?: return raw
    return runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(instant) }
        .getOrDefault(raw)
}
