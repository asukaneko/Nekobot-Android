package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.security.MessageDigest
import java.util.Base64

internal data class WebDavSyncIndexEntry(
    val hash: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
    val delta: String = ""
)

internal data class WebDavSyncManifest(
    var version: Int = 2,
    var profileId: String = "",
    var syncGroupId: String = "",
    var revision: Long = 0,
    var updatedAt: String = "",
    val coverage: MutableSet<String> = linkedSetOf(),
    val records: MutableMap<String, WebDavSyncIndexEntry> = linkedMapOf()
)

/** 远端修订已提交、但本地记录或基线尚未完成应用时的恢复信息。 */
internal data class WebDavSyncJournal(
    val profileName: String = "",
    val targetManifest: WebDavSyncManifest = WebDavSyncManifest(),
    val incomingKeys: List<String> = emptyList(),
    val conflictDetailsJson: String = "[]",
    val updatedAt: String = ""
)

internal data class WebDavSyncRecord(
    val key: String = "",
    val type: String = "",
    val id: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
    val hash: String = "",
    val value: JsonObject? = null,
    val attachments: Map<String, WebDavSyncFileRef> = emptyMap(),
    @Transient val localAttachments: Map<String, File> = emptyMap()
)

/** 加密 delta 只保存文件对象引用，媒体内容按固定大小分块传输。 */
internal data class WebDavSyncFileRef(
    val size: Long = 0L,
    val sha256: String = "",
    val chunks: List<String> = emptyList()
)

internal data class WebDavSyncDelta(
    val version: Int = 2,
    val revision: Long = 0,
    val deviceId: String = "",
    val createdAt: String = "",
    val records: List<WebDavSyncRecord> = emptyList()
)

internal object LocalWebDavIncrementalLogic {
    private val gson = Gson()

    fun record(
        type: String,
        id: String,
        updatedAt: String,
        value: JsonObject,
        localAttachments: Map<String, File> = emptyMap()
    ): WebDavSyncRecord {
        val attachments = localAttachments.mapValues { (_, file) ->
            require(file.isFile) { "同步附件不存在：${file.name}" }
            WebDavSyncFileRef(file.length(), sha256(file), emptyList())
        }
        val canonical = buildString {
            append(gson.toJson(value))
            attachments.toSortedMap().forEach { (name, ref) ->
                append('\n').append(name).append(':').append(ref.size).append(':').append(ref.sha256)
            }
        }
        return WebDavSyncRecord(
            key = "$type:$id",
            type = type,
            id = id,
            updatedAt = updatedAt,
            hash = sha256(canonical.toByteArray(Charsets.UTF_8)),
            value = value,
            attachments = attachments,
            localAttachments = localAttachments
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    const val DELETED_HASH = "__deleted__"
}

/** 逐条读取 delta；历史版本内联的 Base64 媒体直接解码到暂存文件。 */
internal object WebDavSyncDeltaStreamReader {
    private val gson = Gson()

    fun readSelected(file: File, keys: Set<String>, stagingDirectory: File): Map<String, WebDavSyncRecord> {
        if (keys.isEmpty()) return emptyMap()
        return PushbackReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8), 1)
            .use { JsonStream(it, keys, stagingDirectory).readDelta() }
    }

    private class JsonStream(
        private val input: PushbackReader,
        private val wantedKeys: Set<String>,
        private val stagingDirectory: File
    ) {
        fun readDelta(): Map<String, WebDavSyncRecord> {
            expect('{')
            val records = linkedMapOf<String, WebDavSyncRecord>()
            var first = true
            while (true) {
                val next = nextNonWhitespace()
                if (next == '}'.code) break
                if (!first) require(next == ','.code) { "增量 JSON 格式无效" }
                else input.unread(next)
                val name = readString()
                expect(':')
                if (name == "records") {
                    expect('[')
                    var firstRecord = true
                    while (true) {
                        val recordStart = nextNonWhitespace()
                        if (recordStart == ']'.code) break
                        if (!firstRecord) require(recordStart == ','.code) { "增量记录列表格式无效" }
                        else input.unread(recordStart)
                        readRecord()?.let { record -> records[record.key] = record }
                        firstRecord = false
                    }
                } else {
                    skipValue(0)
                }
                first = false
            }
            require(nextNonWhitespace() == -1) { "增量 JSON 尾部包含无效数据" }
            return records
        }

        private fun readRecord(): WebDavSyncRecord? {
            expect('{')
            val firstName = readString()
            require(firstName == "key") { "增量记录缺少 key" }
            expect(':')
            val key = readString()
            if (key !in wantedKeys) {
                skipObjectFields(first = false, depth = 1)
                return null
            }

            var type = ""
            var id = ""
            var updatedAt = ""
            var deleted = false
            var hash = ""
            var value: JsonObject? = null
            val attachments = linkedMapOf<String, WebDavSyncFileRef>()
            val localAttachments = linkedMapOf<String, File>()
            var first = false
            while (true) {
                val next = nextNonWhitespace()
                if (next == '}'.code) break
                if (!first) require(next == ','.code) { "增量记录格式无效" }
                else input.unread(next)
                val name = readString()
                expect(':')
                when (name) {
                    "type" -> type = readString()
                    "id" -> id = readString()
                    "updatedAt" -> updatedAt = readString()
                    "deleted" -> deleted = readPrimitiveToken() == "true"
                    "hash" -> hash = readString()
                    "value" -> value = readRecordValue(type, attachments, localAttachments)
                    "attachments" -> readJsonElement(0).takeUnless { it.isJsonNull }
                        ?.asJsonObject?.entrySet()?.forEach { (attachmentName, rawRef) ->
                            attachments[attachmentName] = parseFileRef(rawRef.asJsonObject)
                        }
                    else -> skipValue(0)
                }
                first = false
            }
            return WebDavSyncRecord(
                key = key,
                type = type,
                id = id,
                updatedAt = updatedAt,
                deleted = deleted,
                hash = hash,
                value = value,
                attachments = attachments,
                localAttachments = localAttachments
            )
        }

        private fun readRecordValue(
            type: String,
            attachments: MutableMap<String, WebDavSyncFileRef>,
            localAttachments: MutableMap<String, File>
        ): JsonObject? {
            val first = nextNonWhitespace()
            if (first == 'n'.code) {
                input.unread(first)
                require(readPrimitiveToken() == "null") { "增量记录 value 格式无效" }
                return null
            }
            require(first == '{'.code) { "增量记录 value 格式无效" }
            val value = JsonObject()
            var firstProperty = true
            while (true) {
                val next = nextNonWhitespace()
                if (next == '}'.code) break
                if (!firstProperty) require(next == ','.code) { "增量记录 value 格式无效" }
                else input.unread(next)
                val name = readString()
                expect(':')
                val attachmentName = when {
                    type == TYPE_MESSAGE && name == "audio_file_base64" -> "audio"
                    type == TYPE_MESSAGE_IMAGE && name == "file_base64" -> "image"
                    type == TYPE_STICKER && name == "file_base64" -> "image"
                    else -> null
                }
                if (attachmentName != null) {
                    val target = File(stagingDirectory, "legacy-attachment-${java.util.UUID.randomUUID()}.bin")
                    try {
                        val reference = readBase64ToFile(target, maxAttachmentBytes(type))
                        attachments[attachmentName] = reference
                        localAttachments[attachmentName] = target
                    } catch (error: Throwable) {
                        target.delete()
                        throw error
                    }
                } else {
                    value.add(name, readJsonElement(0))
                }
                firstProperty = false
            }
            return value
        }

        private fun readBase64ToFile(target: File, maxBytes: Long): WebDavSyncFileRef {
            expect('"')
            val digest = MessageDigest.getInstance("SHA-256")
            val quartet = CharArray(4)
            var quartetSize = 0
            var size = 0L
            var padded = false
            FileOutputStream(target).buffered().use { output ->
                while (true) {
                    val char = input.read()
                    require(char >= 0) { "增量附件 Base64 不完整" }
                    if (char == '"'.code) break
                    require(!padded && char != '\\'.code) { "增量附件 Base64 格式无效" }
                    val value = char.toChar()
                    require(value.isLetterOrDigit() || value == '+' || value == '/' || value == '=') {
                        "增量附件 Base64 格式无效"
                    }
                    quartet[quartetSize++] = value
                    if (quartetSize == quartet.size) {
                        val decoded = runCatching { Base64.getDecoder().decode(String(quartet)) }
                            .getOrElse { throw IllegalArgumentException("增量附件 Base64 格式无效", it) }
                        size += decoded.size
                        require(size <= maxBytes) { "历史增量附件超过大小限制" }
                        digest.update(decoded)
                        output.write(decoded)
                        padded = quartet.any { it == '=' }
                        quartetSize = 0
                    }
                }
            }
            require(quartetSize == 0) { "增量附件 Base64 长度无效" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return WebDavSyncFileRef(size, hash, emptyList())
        }

        private fun readJsonElement(depth: Int): JsonElement {
            require(depth <= MAX_JSON_DEPTH) { "增量 JSON 嵌套过深" }
            return when (val first = nextNonWhitespace()) {
                '"'.code -> JsonPrimitive(readStringAfterQuote())
                '{'.code -> {
                    val obj = JsonObject()
                    var firstField = true
                    while (true) {
                        val next = nextNonWhitespace()
                        if (next == '}'.code) break
                        if (!firstField) require(next == ','.code) { "增量 JSON 对象格式无效" }
                        else input.unread(next)
                        val name = readString()
                        expect(':')
                        obj.add(name, readJsonElement(depth + 1))
                        firstField = false
                    }
                    obj
                }
                '['.code -> {
                    val array = JsonArray()
                    var firstElement = true
                    while (true) {
                        val next = nextNonWhitespace()
                        if (next == ']'.code) break
                        if (!firstElement) require(next == ','.code) { "增量 JSON 数组格式无效" }
                        else input.unread(next)
                        array.add(readJsonElement(depth + 1))
                        firstElement = false
                    }
                    array
                }
                else -> {
                    require(first >= 0) { "增量 JSON 值缺失" }
                    input.unread(first)
                    val token = readPrimitiveToken()
                    when (token) {
                        "null" -> JsonNull.INSTANCE
                        "true" -> JsonPrimitive(true)
                        "false" -> JsonPrimitive(false)
                        else -> runCatching { JsonPrimitive(token.toBigDecimal()) }
                            .getOrElse { throw IllegalArgumentException("增量 JSON 数字格式无效", it) }
                    }
                }
            }
        }

        private fun skipValue(depth: Int) {
            require(depth <= MAX_JSON_DEPTH) { "增量 JSON 嵌套过深" }
            when (val first = nextNonWhitespace()) {
                '"'.code -> skipStringAfterQuote()
                '{'.code -> skipObjectFields(first = true, depth = depth + 1)
                '['.code -> {
                    var firstElement = true
                    while (true) {
                        val next = nextNonWhitespace()
                        if (next == ']'.code) break
                        if (!firstElement) require(next == ','.code) { "增量 JSON 数组格式无效" }
                        else input.unread(next)
                        skipValue(depth + 1)
                        firstElement = false
                    }
                }
                else -> {
                    require(first >= 0) { "增量 JSON 值缺失" }
                    input.unread(first)
                    readPrimitiveToken()
                }
            }
        }

        private fun parseFileRef(raw: JsonObject): WebDavSyncFileRef {
            val chunks = raw.getAsJsonArray("chunks")?.map { it.asString }.orEmpty()
            return WebDavSyncFileRef(
                size = raw.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
                sha256 = raw.get("sha256")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                chunks = chunks
            )
        }

        private fun maxAttachmentBytes(type: String): Long = when (type) {
            TYPE_MESSAGE -> 64L * 1024L * 1024L
            TYPE_MESSAGE_IMAGE -> 64L * 1024L * 1024L
            TYPE_STICKER -> 16L * 1024L * 1024L
            "conflict_copy" -> 64L * 1024L * 1024L
            "file" -> 64L * 1024L * 1024L
            else -> error("不支持的历史增量附件类型：$type")
        }

        private fun readString(): String {
            expect('"')
            return readStringAfterQuote()
        }

        private fun readStringAfterQuote(): String {
            val result = StringBuilder()
            while (true) {
                val value = input.read()
                require(value >= 0) { "增量 JSON 字符串不完整" }
                when (value) {
                    '"'.code -> return result.toString()
                    '\\'.code -> {
                        when (val escaped = input.read()) {
                            '"'.code -> result.append('"')
                            '\\'.code -> result.append('\\')
                            '/'.code -> result.append('/')
                            'b'.code -> result.append('\b')
                            'f'.code -> result.append('\u000c')
                            'n'.code -> result.append('\n')
                            'r'.code -> result.append('\r')
                            't'.code -> result.append('\t')
                            'u'.code -> {
                                val hex = CharArray(4) { input.read().toChar() }
                                result.append(hex.concatToString().toInt(16).toChar())
                            }
                            else -> error("增量 JSON 转义格式无效")
                        }
                    }
                    else -> result.append(value.toChar())
                }
                require(result.length <= MAX_JSON_STRING_CHARS) { "增量 JSON 字符串过大" }
            }
        }

        private fun skipStringAfterQuote() {
            while (true) {
                when (val value = input.read()) {
                    '"'.code -> return
                    '\\'.code -> require(input.read() >= 0) { "增量 JSON 转义不完整" }
                    -1 -> error("增量 JSON 字符串不完整")
                }
            }
        }

        private fun readPrimitiveToken(): String {
            val result = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) break
                if (value.toChar() in JSON_DELIMITERS) {
                    input.unread(value)
                    break
                }
                result.append(value.toChar())
                require(result.length <= MAX_PRIMITIVE_CHARS) { "增量 JSON 值过大" }
            }
            return result.toString()
        }

        private fun nextNonWhitespace(): Int {
            while (true) {
                val value = input.read()
                if (value < 0 || !value.toChar().isWhitespace()) return value
            }
        }

        private fun expect(expected: Char) {
            require(nextNonWhitespace() == expected.code) { "增量 JSON 格式无效：预期 $expected" }
        }

        private fun skipObjectFields(first: Boolean, depth: Int) {
            var firstField = first
            while (true) {
                val next = nextNonWhitespace()
                if (next == '}'.code) return
                if (!firstField) require(next == ','.code) { "增量 JSON 对象格式无效" }
                else input.unread(next)
                readString()
                expect(':')
                skipValue(depth + 1)
                firstField = false
            }
        }
    }

    private const val MAX_JSON_DEPTH = 64
    private const val MAX_JSON_STRING_CHARS = 16 * 1024 * 1024
    private const val MAX_PRIMITIVE_CHARS = 256
    private const val TYPE_MESSAGE = "message"
    private const val TYPE_MESSAGE_IMAGE = "message_image"
    private const val TYPE_STICKER = "sticker"
    private val JSON_DELIMITERS = setOf(',', ']', '}', ' ', '\n', '\r', '\t')
}
