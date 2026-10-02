package com.nekobot.app.data.local

import android.content.Context
import com.nekobot.app.data.local.db.MessageDao
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * local_messages 单行体积自愈。
 *
 * Android CursorWindow 单行上限约 2MB：任何一条消息行的 TEXT 字段合计超过该上限后，
 * 该会话所有整行读取（SELECT *）都会抛 SQLiteBlobTooBigException——会话打不开、
 * 上下文读不了、上下文用量算不出。历史版本曾把整轮工具调用记录（含 base64 截图）
 * 原样写进 tool_call_history，存量库里可能仍残留这类超限行。
 *
 * 修复策略：检测超限行 → 超限字段完整内容分块读出（substr 分段，单次查询结果远小于
 * CursorWindow）转存到 files/oversized_message_fields/ → 字段内只保留开头片段并标注。
 * 会话恢复可读，完整内容留在文件里供用户找回。
 */
object MessageOversizeRepair {

    /**
     * 单行五大 TEXT 字段合计的 UTF-8 字节预算。
     *
     * CursorWindow 约 2MB（各厂商可能有差异），留出行内其余小字段与缓冲的余量。
     */
    internal const val ROW_BYTE_BUDGET = 1_500_000L

    /** 分块读取超限字段时的每块字符数；单次查询结果远小于 CursorWindow，读取本身不会崩。 */
    private const val CHUNK_CHARS = 100_000

    /** 截断后在字段内保留的开头字符数，足以辨认这条消息是什么。 */
    private const val KEPT_HEAD_CHARS = 2_000

    /** 转存目录（相对 app filesDir）。 */
    private const val DUMP_DIR = "oversized_message_fields"

    /** 小于该字节数的字段不动：截断后头部片段+标注反而会比原字段更大。 */
    private const val MIN_FIELD_BYTES_TO_SHRINK = 10_000L

    private const val TAG = "MessageOversizeRepair"

    /** 每会话每进程只主动扫一次；会话内新写入的超限行交给读取侧异常兜底。 */
    private val scannedSessions: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * 读取会话消息前的主动检查：扫一遍该会话的超限行并就地修复。
     * 修复过的会话本进程内不再重复扫描。
     */
    suspend fun ensureSessionReadable(messageDao: MessageDao, context: Context?, sessionId: String) {
        if (sessionId.isBlank()) return
        if (!scannedSessions.add(sessionId)) return
        repairOversizedRows(messageDao, context, sessionId)
    }

    /**
     * 扫描并修复超限消息行；[sessionId] 为 null 时扫描全部会话。
     *
     * @return 修复（截断转存）的消息行数。
     */
    suspend fun repairOversizedRows(
        messageDao: MessageDao,
        context: Context?,
        sessionId: String?
    ): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var repaired = 0
        val ids = messageDao.listOversizedMessageIds(sessionId, ROW_BYTE_BUDGET)
        for (id in ids) {
            try {
                val sizes = messageDao.getFieldSizes(id) ?: continue
                val total = sizes.content + sizes.reasoningContent + sizes.thinkingCards +
                    sizes.toolCallHistory + sizes.knowledgeCitations
                if (total <= ROW_BYTE_BUDGET) continue
                if (repairRow(messageDao, context, id, sizes, total)) repaired++
            } catch (e: Exception) {
                LocalLogger.w(TAG, "修复超大消息行失败 id=$id: ${e.message}")
            }
        }
        if (ids.isNotEmpty()) {
            LocalLogger.w(
                TAG,
                "检测到 ${ids.size} 条超大消息行，已修复 $repaired 条（会话=${sessionId ?: "全部"}）"
            )
        }
        repaired
    }

    /** 逐字段修复一条超限行：从最大的字段开始转存截断，直到合计回到预算内。 */
    private suspend fun repairRow(
        messageDao: MessageDao,
        context: Context?,
        id: String,
        sizes: com.nekobot.app.data.local.db.MessageFieldSizes,
        totalBytes: Long
    ): Boolean {
        var remaining = totalBytes
        val fields = listOf(
            OversizedField("tool_call_history", sizes.toolCallHistory,
                read = { o, l -> messageDao.readToolCallHistoryChunk(id, o, l) },
                write = { v -> messageDao.updateToolCallHistory(id, v) }),
            OversizedField("content", sizes.content,
                read = { o, l -> messageDao.readContentChunk(id, o, l) },
                write = { v -> messageDao.updateContent(id, v.orEmpty()) }),
            OversizedField("thinking_cards", sizes.thinkingCards,
                read = { o, l -> messageDao.readThinkingCardsChunk(id, o, l) },
                write = { v -> messageDao.updateThinkingCards(id, v) }),
            OversizedField("reasoning_content", sizes.reasoningContent,
                read = { o, l -> messageDao.readReasoningContentChunk(id, o, l) },
                write = { v -> messageDao.updateReasoningContent(id, v) }),
            OversizedField("knowledge_citations", sizes.knowledgeCitations,
                read = { o, l -> messageDao.readKnowledgeCitationsChunk(id, o, l) },
                write = { v -> messageDao.updateKnowledgeCitations(id, v) })
        )
        var changed = false
        // 从最大的字段开始处理；预算内的小字段保持原样。
        for (field in fields.sortedByDescending { it.sizeBytes }) {
            if (remaining <= ROW_BYTE_BUDGET) break
            if (field.sizeBytes < MIN_FIELD_BYTES_TO_SHRINK) continue
            val full = readFieldInChunks(field.read)
            if (full.isNullOrEmpty()) continue
            val dumpFile = context?.let { dumpToFile(it, id, field.name, full) }
            val head = full.take(KEPT_HEAD_CHARS)
            val originalDesc = formatByteSize(field.sizeBytes)
            val marker = buildString {
                append("\n\n[该字段原始内容约 ")
                append(originalDesc)
                append("，超过单条消息体积上限，已截断以恢复会话可读")
                if (dumpFile != null) {
                    append("；完整内容转存至应用私有目录 oversized_message_fields/")
                    append(dumpFile.name)
                }
                append("。]")
            }
            field.write(head + marker)
            remaining -= field.sizeBytes - (head + marker).toByteArray(Charsets.UTF_8).size
            changed = true
            LocalLogger.w(
                TAG,
                "消息 $id 的 ${field.name}（$originalDesc）已截断转存"
            )
        }
        return changed
    }

    /** 用 substr 分块把整个字段读出来；空字段返回空串。 */
    private suspend fun readFieldInChunks(
        read: suspend (offset: Int, length: Int) -> String?
    ): String {
        val sb = StringBuilder()
        var offset = 1
        while (true) {
            val chunk = read(offset, CHUNK_CHARS) ?: break
            if (chunk.isEmpty()) break
            sb.append(chunk)
            if (chunk.length < CHUNK_CHARS) break
            offset += CHUNK_CHARS
        }
        return sb.toString()
    }

    private fun dumpToFile(context: Context, messageId: String, field: String, content: String): File? =
        try {
            val dir = File(context.filesDir, DUMP_DIR).apply { mkdirs() }
            val file = File(dir, "${messageId}_$field.txt")
            file.writeText(content)
            file
        } catch (e: Exception) {
            LocalLogger.w(TAG, "超大字段转存文件失败: ${e.message}")
            null
        }

    private fun formatByteSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    private data class OversizedField(
        val name: String,
        val sizeBytes: Long,
        val read: suspend (offset: Int, length: Int) -> String?,
        val write: suspend (value: String?) -> Unit
    )
}
