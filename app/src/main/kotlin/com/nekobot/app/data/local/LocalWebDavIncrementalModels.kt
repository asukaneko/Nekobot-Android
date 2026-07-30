package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.MessageDigest

internal data class WebDavSyncIndexEntry(
    val hash: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
    val delta: String = ""
)

internal data class WebDavSyncManifest(
    val version: Int = 1,
    var revision: Long = 0,
    var updatedAt: String = "",
    val records: MutableMap<String, WebDavSyncIndexEntry> = linkedMapOf()
)

internal data class WebDavSyncRecord(
    val key: String = "",
    val type: String = "",
    val id: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
    val hash: String = "",
    val value: JsonObject? = null
)

internal data class WebDavSyncDelta(
    val version: Int = 1,
    val revision: Long = 0,
    val deviceId: String = "",
    val createdAt: String = "",
    val records: List<WebDavSyncRecord> = emptyList()
)

internal object LocalWebDavIncrementalLogic {
    private val gson = Gson()

    fun record(type: String, id: String, updatedAt: String, value: JsonObject): WebDavSyncRecord {
        val canonical = gson.toJson(value)
        return WebDavSyncRecord(
            key = "$type:$id",
            type = type,
            id = id,
            updatedAt = updatedAt,
            hash = sha256(canonical.toByteArray(Charsets.UTF_8)),
            value = value
        )
    }

    fun tombstone(key: String, updatedAt: String): WebDavSyncRecord {
        val separator = key.indexOf(':')
        require(separator > 0 && separator < key.lastIndex) { "无效的同步记录键：$key" }
        return WebDavSyncRecord(
            key = key,
            type = key.substring(0, separator),
            id = key.substring(separator + 1),
            updatedAt = updatedAt,
            deleted = true,
            hash = DELETED_HASH
        )
    }

    fun changed(
        current: WebDavSyncIndexEntry?,
        baseline: WebDavSyncIndexEntry?
    ): Boolean = when {
        current == null && baseline == null -> false
        current == null -> baseline?.deleted != true
        baseline == null -> true
        else -> current.hash != baseline.hash || current.deleted != baseline.deleted
    }

    fun localWins(localUpdatedAt: String, remoteUpdatedAt: String): Boolean =
        localUpdatedAt >= remoteUpdatedAt

    fun indexOf(record: WebDavSyncRecord, deltaName: String): WebDavSyncIndexEntry =
        WebDavSyncIndexEntry(
            hash = record.hash,
            updatedAt = record.updatedAt,
            deleted = record.deleted,
            delta = deltaName
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    const val DELETED_HASH = "__deleted__"
}
