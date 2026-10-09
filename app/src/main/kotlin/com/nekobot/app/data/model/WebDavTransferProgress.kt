package com.nekobot.app.data.model

enum class WebDavTransferStage { Preparing, Uploading, Downloading, ServerProcessing }

/** 一次文件传输的实际字节进度；服务端未提供长度时保持不定进度。 */
data class WebDavTransferProgress(
    val stage: WebDavTransferStage = WebDavTransferStage.Preparing,
    val fileName: String = "",
    val transferredBytes: Long = 0L,
    val totalBytes: Long? = null,
    val completed: Boolean = false
) {
    val percent: Int?
        get() = if (completed) 100 else totalBytes?.takeIf { it > 0L }?.let {
            (transferredBytes.toDouble() / it * 100).toInt().coerceIn(0, 99)
        }
}
