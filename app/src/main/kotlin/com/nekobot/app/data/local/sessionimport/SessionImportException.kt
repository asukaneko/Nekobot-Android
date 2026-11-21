package com.nekobot.app.data.local.sessionimport

/**
 * 会话导入过程中可预期的失败。
 *
 * 异常只携带诊断信息，界面按类型替换成本地化文案，避免把中文错误串写进数据层。
 */
sealed class SessionImportException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 链接不是可用的 HTTPS 公网地址。 */
    class InvalidUrl(detail: String) : SessionImportException(detail)

    /** 下载失败（HTTP 错误、连接中断等）。 */
    class DownloadFailed(val reason: String, cause: Throwable? = null) :
        SessionImportException("download failed: $reason", cause)

    /** 归档或单个会话超出可安全读取的体积上限。 */
    class TooLarge : SessionImportException("archive exceeds the size limit")

    /** 文件不是该格式的会话归档（或版本无法识别）。 */
    class UnsupportedArchive(val formatId: String, detail: String) :
        SessionImportException("$formatId archive is not supported: $detail")
}
