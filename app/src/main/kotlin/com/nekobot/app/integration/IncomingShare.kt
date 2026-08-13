package com.nekobot.app.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.nekobot.app.R
import java.io.File
import java.util.UUID

/** 系统分享进入应用后暂存的附件。文件已复制到应用缓存，不依赖外部 URI 授权。 */
data class IncomingShareAttachment(
    val name: String,
    val mimeType: String,
    val localPath: String
)

/** 等待用户选择目标会话的系统分享内容。 */
data class IncomingShare(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val attachments: List<IncomingShareAttachment> = emptyList()
)

object IncomingShareParser {
    private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L

    fun parseDeepLinkSessionId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = Regex("""^nekobot://(?:chat|session)/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?: return null
        return java.net.URLDecoder.decode(match.groupValues[1], Charsets.UTF_8.name())
            .takeIf { it.isNotBlank() }
    }

    fun parse(context: Context, intent: Intent): IncomingShare? {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null

        val text = buildString {
            intent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { append(it).append('\n') }
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::append)
        }.trim()

        val uris = buildList {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    .orEmpty()
                    .forEach(::add)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
            }
        }.distinct()
        val attachments = uris.mapNotNull { uri -> copyAttachment(context, uri, intent.type) }
        if (text.isBlank() && attachments.isEmpty()) return null
        return IncomingShare(text = text, attachments = attachments)
    }

    private fun copyAttachment(
        context: Context,
        uri: Uri,
        fallbackMime: String?
    ): IncomingShareAttachment? {
        val resolver = context.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            if (size > MAX_ATTACHMENT_BYTES) return null
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: uri.lastPathSegment ?: "shared_file"

        val safeName = displayName
            .substringAfterLast('/')
            .replace(Regex("""[^\p{L}\p{N}._ -]"""), "_")
            .take(120)
            .ifBlank { "shared_file" }
        val targetDir = File(context.cacheDir, "incoming_share").apply { mkdirs() }
        val target = File(targetDir, "${UUID.randomUUID()}_$safeName")
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ATTACHMENT_BYTES) {
                            context.getString(R.string.incoming_share_too_large)
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
            IncomingShareAttachment(
                name = safeName,
                mimeType = resolver.getType(uri)
                    ?: fallbackMime
                    ?: "application/octet-stream",
                localPath = target.absolutePath
            )
        }.getOrElse {
            target.delete()
            null
        }
    }
}
