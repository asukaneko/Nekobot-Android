package com.nekobot.app.data.local.sessionimport

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipFile

/**
 * DeepSeek 官方数据导出 ZIP 中的一条会话概要，用于导入前的勾选列表。
 *
 * @property id 导出包内的会话 ID（只用于在本 ZIP 内定位文件）。
 */
data class ImportableConversation(
    val id: String,
    val title: String,
    val messageCount: Int,
    val pinned: Boolean,
    val updatedAt: String?
)

/**
 * 读取 DeepSeek 官方「导出数据」ZIP（`deepseek_data-<date>.zip`）。
 *
 * 归档结构（export_format_version = 3）：
 * ```
 * manifest.json               {"export_format_version":3}
 * user.json                   账号信息（不读取，避免把隐私带进应用）
 * chat_session_index.json     [{id,title,pinned,message_count,inserted_at,updated_at}]
 * chat_sessions/<id>.json     {id,title,pinned,messages:[...]}
 * chat_sessions/<id>_files/   附件原文件（本读取器不读取）
 * ```
 *
 * 消息正文由 `fragments` 拼装：`REQUEST` → 用户消息，`RESPONSE`/`TEMPLATE_RESPONSE` → 助手正文，
 * `THINK` → 推理内容，`FILE` → 附件文件名（折叠成正文字符串），
 * 联网搜索/工具/风险提示类片段不进入聊天记录。
 *
 * 读取结果转换为 nekobot 的 `nbot_session_export` 会话对象，交给现有导入流程落库，
 * 因此本地模式与服务器模式共用同一条导入通道。
 */
class DeepSeekArchiveReader private constructor(private val zip: ZipFile) : Closeable {

    /** 归档内文件的规范化名称（统一用 `/`）→ ZIP 中的真实 entry 名。 */
    private val entries: Map<String, String> = zip.entries().asSequence()
        .filter { !it.isDirectory }
        .associate { it.name.replace('\\', '/') to it.name }

    /**
     * 归档可能被用户重新打包成 `deepseek_data-xxx/` 之类的单层目录，
     * 这里识别共同前缀，避免整包导入时报“不是有效的导出包”。
     */
    private val rootPrefix: String = run {
        if (entries.containsKey(ENTRY_INDEX)) return@run ""
        entries.keys.firstOrNull { it.endsWith("/$ENTRY_INDEX") }?.removeSuffix(ENTRY_INDEX) ?: ""
    }

    private var consumedBytes = 0L

    /** 导出格式版本；没有 manifest 时为 null。 */
    val exportFormatVersion: Int? by lazy {
        val text = readOptionalText(ENTRY_MANIFEST, MAX_MANIFEST_BYTES) ?: return@lazy null
        runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.intOrNull("export_format_version")
    }

    /** 列出归档中的会话（只读索引，不解析消息正文）。 */
    fun listConversations(): List<ImportableConversation> {
        val indexed = readIndex()
        val conversations = if (indexed.isNotEmpty()) {
            indexed.filter { sessionEntryName(it.id) != null }
        } else {
            sessionIds().mapNotNull { readHeader(it) }
        }
        return conversations.sortedByDescending { it.updatedAt.orEmpty() }
    }

    /**
     * 把一个会话转换成 nekobot 导出格式的会话对象（含 messages）。
     *
     * 会话正文为空（例如只生成到一半的提问）时返回 null，调用方按失败计数。
     */
    fun buildSession(conversationId: String): JsonObject? {
        val entryName = sessionEntryName(conversationId) ?: return null
        val text = readEntryText(entryName, MAX_SESSION_BYTES) ?: return null
        val raw = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val drafts = mutableListOf<DraftMessage>()
        raw.getAsJsonArray("messages")?.forEach { element ->
            val message = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            toDraftMessage(message)?.let(drafts::add)
        }
        if (drafts.isEmpty()) return null

        // DeepSeek 的 inserted_at 存在「助手回复早于提问」的情况（同一轮最多相差几十毫秒），
        // 而应用按 created_at 升序展示消息，直接透传会把用户消息排到 AI 回复之后。
        // 这里按归档中的消息顺序强制时间戳严格递增，保留原时间只做必要的微调。
        val messages = JsonArray()
        var previousMillis: Long? = null
        val baseMillis = parseIsoMillis(raw.stringOrNull("inserted_at"))
            ?: parseIsoMillis(raw.stringOrNull("updated_at"))
            ?: 0L
        drafts.forEach { draft ->
            val original = parseIsoMillis(draft.insertedAt)
            val previous = previousMillis
            val millis = when {
                previous == null -> original ?: baseMillis
                original == null -> previous + MIN_MESSAGE_GAP_MILLIS
                else -> maxOf(original, previous + MIN_MESSAGE_GAP_MILLIS)
            }
            previousMillis = millis
            messages.add(toExportMessage(draft, formatIsoMillis(millis)))
        }

        val id = raw.stringOrNull("id") ?: conversationId
        val title = raw.stringOrNull("title") ?: defaultTitle(id)
        return JsonObject().apply {
            addProperty("name", title)
            // 普通 AI 对话：没有绑定角色卡，按助手会话导入才能正常续聊
            addProperty("session_mode", "agent")
            addProperty("pinned", raw.boolOrNull("pinned") ?: false)
            raw.stringOrNull("inserted_at")?.let { addProperty("created_at", it) }
            raw.stringOrNull("updated_at")?.let { addProperty("updated_at", it) }
            add("messages", messages)
        }
    }

    override fun close() {
        zip.close()
    }

    // ---------------------------------------------------------------- 索引

    private fun readIndex(): List<ImportableConversation> {
        val text = readOptionalText(ENTRY_INDEX, MAX_INDEX_BYTES) ?: return emptyList()
        val array = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return emptyList()
        val seen = HashSet<String>()
        return array.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = item.stringOrNull("id")?.takeIf { seen.add(it) } ?: return@mapNotNull null
            ImportableConversation(
                id = id,
                title = item.stringOrNull("title") ?: defaultTitle(id),
                messageCount = item.intOrNull("message_count") ?: 0,
                pinned = item.boolOrNull("pinned") ?: false,
                updatedAt = item.stringOrNull("updated_at") ?: item.stringOrNull("inserted_at")
            )
        }
    }

    /** 索引缺失时的兜底：直接读每个会话文件的头部与消息数。 */
    private fun readHeader(conversationId: String): ImportableConversation? {
        val entryName = sessionEntryName(conversationId) ?: return null
        val text = readEntryText(entryName, MAX_SESSION_BYTES) ?: return null
        val raw = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        return ImportableConversation(
            id = conversationId,
            title = raw.stringOrNull("title") ?: defaultTitle(conversationId),
            messageCount = raw.getAsJsonArray("messages")?.size() ?: 0,
            pinned = raw.boolOrNull("pinned") ?: false,
            updatedAt = raw.stringOrNull("updated_at") ?: raw.stringOrNull("inserted_at")
        )
    }

    // ---------------------------------------------------------------- 消息

    /** 待写入的一条消息：正文/推理已拼好，时间戳在 [buildSession] 里统一规整。 */
    private data class DraftMessage(
        val role: String,
        val content: String,
        val reasoning: String,
        val insertedAt: String?
    )

    /**
     * 把 DeepSeek 消息（fragments 结构）解析为草稿。
     *
     * @return 正文为空的消息返回 null（思考中断、纯工具调用等没有可读内容）。
     */
    private fun toDraftMessage(message: JsonObject): DraftMessage? {
        val role = when (message.stringOrNull("role")?.uppercase(Locale.ROOT)) {
            "USER" -> "user"
            "ASSISTANT" -> "assistant"
            else -> return null
        }
        val contentTypes = if (role == "user") setOf("REQUEST") else setOf("RESPONSE", "TEMPLATE_RESPONSE")
        val contentParts = mutableListOf<String>()
        val reasoningParts = mutableListOf<String>()
        val attachments = mutableListOf<String>()

        message.getAsJsonArray("fragments")?.forEach { element ->
            val fragment = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val type = fragment.stringOrNull("type") ?: return@forEach
            val text = fragment.stringOrNull("content")
            when {
                type in contentTypes && text != null -> contentParts.add(text)
                type == "THINK" && text != null -> reasoningParts.add(text)
                type == "FILE" -> attachments.addAll(attachmentNames(fragment))
            }
        }

        val body = contentParts.joinToString("\n\n").trim()
        val attachmentText = attachments.distinct().joinToString("\n") { "📎 $it" }
        val content = listOf(body, attachmentText).filter { it.isNotBlank() }.joinToString("\n\n")
        if (content.isBlank()) return null

        return DraftMessage(
            role = role,
            content = content,
            reasoning = reasoningParts.joinToString("\n\n").trim(),
            insertedAt = message.stringOrNull("inserted_at")
        )
    }

    private fun toExportMessage(draft: DraftMessage, createdAt: String): JsonObject = JsonObject().apply {
        addProperty("role", draft.role)
        addProperty("content", draft.content)
        if (draft.reasoning.isNotBlank()) addProperty("reasoning_content", draft.reasoning)
        addProperty("created_at", createdAt)
        addProperty("timestamp", createdAt)
    }

    /** `FILE` 片段里的附件名（文件本身留在 ZIP 内，不导入）。 */
    private fun attachmentNames(fragment: JsonObject): List<String> {
        val files = fragment.getAsJsonArray("files") ?: return emptyList()
        return files.mapNotNull { element ->
            val file = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            file.stringOrNull("file_name")
                ?: file.stringOrNull("export_path")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        }
    }

    // ---------------------------------------------------------------- ZIP 读取

    private fun hasSessionData(): Boolean =
        sessionIds().isNotEmpty() || resolveEntry(ENTRY_INDEX) != null || resolveEntry(ENTRY_MANIFEST) != null

    private fun resolveEntry(relativePath: String): String? = entries[rootPrefix + relativePath]

    private fun sessionEntryName(conversationId: String): String? {
        if (conversationId.isBlank() || '/' in conversationId || '\\' in conversationId) return null
        return resolveEntry("$SESSION_DIR$conversationId.json")
    }

    private fun sessionIds(): List<String> {
        val prefix = rootPrefix + SESSION_DIR
        return entries.keys.asSequence()
            .filter { it.startsWith(prefix) && it.endsWith(".json") }
            .map { it.removePrefix(prefix).removeSuffix(".json") }
            .filter { it.isNotBlank() && '/' !in it && '\\' !in it }
            .toList()
    }

    private fun readOptionalText(relativePath: String, maxBytes: Long): String? {
        val entryName = resolveEntry(relativePath) ?: return null
        return readEntryText(entryName, maxBytes)
    }

    private fun readEntryText(entryName: String, maxBytes: Long): String? {
        val entry = zip.getEntry(entryName) ?: return null
        if (entry.size > maxBytes) throw SessionImportException.TooLarge()
        val output = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes || consumedBytes + total > MAX_TOTAL_READ_BYTES) {
                    throw SessionImportException.TooLarge()
                }
                output.write(buffer, 0, read)
            }
            consumedBytes += total
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun defaultTitle(conversationId: String): String = "DeepSeek ${conversationId.take(8)}"

    /** 解析 ISO-8601 时间戳为毫秒；无法解析时返回 null。 */
    private fun parseIsoMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /** 固定毫秒精度 + UTC：保证按字符串排序与按时间排序结果一致。 */
    private fun formatIsoMillis(millis: Long): String = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(millis))

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return element.asString.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.boolOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asBoolean }.getOrNull()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asInt }.getOrNull()
    }

    companion object {
        const val ENTRY_INDEX = "chat_session_index.json"
        const val ENTRY_MANIFEST = "manifest.json"
        const val SESSION_DIR = "chat_sessions/"

        /** 当前已验证的导出格式版本；更高版本结构未知，明确拒绝而不是导入错误数据。 */
        const val SUPPORTED_FORMAT_VERSION = 3

        private const val MAX_MANIFEST_BYTES = 256L * 1024
        private const val MAX_INDEX_BYTES = 32L * 1024 * 1024
        private const val MAX_SESSION_BYTES = 64L * 1024 * 1024
        private const val MAX_TOTAL_READ_BYTES = 1024L * 1024 * 1024

        /** 相邻消息的最小时间间隔（毫秒）：只用于纠正导出中「回复早于提问」的时间戳。 */
        private const val MIN_MESSAGE_GAP_MILLIS = 1L

        private val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC)

        /**
         * 打开归档。
         *
         * @throws SessionImportException.UnsupportedArchive 不是 DeepSeek 导出包或版本不支持。
         * @throws SessionImportException.TooLarge 单文件/累计读取超出上限。
         */
        fun open(file: File): DeepSeekArchiveReader {
            val zip = try {
                ZipFile(file)
            } catch (error: Exception) {
                throw SessionImportException.UnsupportedArchive("deepseek", "not a zip archive")
            }
            val reader = try {
                DeepSeekArchiveReader(zip)
            } catch (error: Exception) {
                zip.close()
                throw error
            }
            try {
                if (!reader.hasSessionData()) {
                    throw SessionImportException.UnsupportedArchive("deepseek", "no chat session data")
                }
                val version = reader.exportFormatVersion
                if (version != null && version > SUPPORTED_FORMAT_VERSION) {
                    throw SessionImportException.UnsupportedArchive("deepseek", "format version $version")
                }
                return reader
            } catch (error: Exception) {
                reader.close()
                throw error
            }
        }
    }
}
