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
    private const val MAX_TOTAL_ATTACHMENT_BYTES = 50L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 100L * 1024L * 1024L
    private const val MAX_ATTACHMENTS = 8
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private data class CopiedAttachment(val attachment: IncomingShareAttachment, val bytes: Long)

    fun parseDeepLinkSessionId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = Regex("""^nekobot://(?:chat|session)/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?: return null
        return runCatching {
            java.net.URLDecoder.decode(match.groupValues[1], Charsets.UTF_8.name())
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    fun parse(context: Context, intent: Intent): IncomingShare? = runCatching {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        cleanupStaleCache(context)

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

        val uris = mutableListOf<Uri>()
        fun addUri(uri: Uri?) {
            if (uri != null && uris.size < MAX_ATTACHMENTS && uri !in uris) uris += uri
        }
        @Suppress("DEPRECATION")
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                .orEmpty()
                .take(MAX_ATTACHMENTS)
                .forEach(::addUri)
        } else {
            @Suppress("DEPRECATION")
            addUri(intent.getParcelableExtra(Intent.EXTRA_STREAM))
        }
        intent.clipData?.let { clip ->
            var index = 0
            while (index < clip.itemCount && uris.size < MAX_ATTACHMENTS) {
                addUri(clip.getItemAt(index).uri)
                index++
            }
        }

        val cacheBytesBeforeShare = incomingCacheDirectory(context).listFiles()
            ?.filter(File::isFile)
            ?.sumOf(File::length)
            ?: 0L
        var remainingBytes = minOf(
            MAX_TOTAL_ATTACHMENT_BYTES,
            (MAX_CACHE_BYTES - cacheBytesBeforeShare).coerceAtLeast(0L)
        )
        val attachments = mutableListOf<IncomingShareAttachment>()
        for (uri in uris) {
            if (remainingBytes <= 0L) break
            val copied = copyAttachment(context, uri, intent.type, remainingBytes) ?: continue
            attachments += copied.attachment
            remainingBytes -= copied.bytes
        }

        if (text.isBlank() && attachments.isEmpty()) null
        else IncomingShare(text = text, attachments = attachments)
    }.getOrNull()

    private fun copyAttachment(
        context: Context,
        uri: Uri,
        fallbackMime: String?,
        remainingBytes: Long
    ): CopiedAttachment? {
        if (!uri.scheme.equals("content", ignoreCase = true) || context.isOwnedContentProvider(uri)) return null
        val resolver = context.contentResolver
        var target: File? = null
        return runCatching {
            val metadata = resolver.query(
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
                if (size > MAX_ATTACHMENT_BYTES || size > remainingBytes) return null
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to size
            }
            val displayName = metadata?.first ?: uri.lastPathSegment ?: "shared_file"
            val safeName = displayName
                .substringAfterLast('/')
                .replace(Regex("""[^\p{L}\p{N}._ -]"""), "_")
                .take(120)
                .ifBlank { "shared_file" }
            val targetDir = incomingCacheDirectory(context).apply { mkdirs() }
            target = File(targetDir, "${UUID.randomUUID()}_$safeName")

            var copiedBytes = 0L
            resolver.openInputStream(uri)?.use { input ->
                target!!.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > MAX_ATTACHMENT_BYTES || copiedBytes > remainingBytes) {
                            throw IllegalArgumentException(context.getString(R.string.incoming_share_too_large))
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null

            CopiedAttachment(
                attachment = IncomingShareAttachment(
                    name = safeName,
                    mimeType = resolver.getType(uri) ?: fallbackMime ?: "application/octet-stream",
                    localPath = target!!.absolutePath
                ),
                bytes = copiedBytes
            )
        }.getOrElse {
            target?.delete()
            null
        }
    }

    private fun incomingCacheDirectory(context: Context): File = File(context.cacheDir, "incoming_share")

    private fun cleanupStaleCache(context: Context) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        incomingCacheDirectory(context).listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
