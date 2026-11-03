package com.nekobot.app.data.local.ai

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/** Raw provider bodies, URLs, tokens and chat content never become the displayed message. */
internal class FusedSummaryFailure(val reason: Reason) : IllegalStateException(reason.text.value()) {
    enum class Reason(val text: FusedMemoryText, val retryable: Boolean = false) {
        NETWORK(FusedMemoryText.SUMMARY_NETWORK, true),
        TIMEOUT(FusedMemoryText.SUMMARY_TIMEOUT, true),
        AUTH(FusedMemoryText.SUMMARY_AUTH),
        QUOTA(FusedMemoryText.SUMMARY_QUOTA),
        RATE_LIMIT(FusedMemoryText.SUMMARY_RATE_LIMIT),
        CAPACITY(FusedMemoryText.MODEL_TOO_SMALL),
        CURRENT_LIMIT(FusedMemoryText.SUMMARY_CURRENT_LIMIT),
        CONTEXT_LIMIT(FusedMemoryText.CONTEXT_TOO_LARGE),
        FORMAT(FusedMemoryText.SUMMARY_FORMAT, true),
        INCOMPLETE(FusedMemoryText.INCOMPLETE, true),
        STORAGE(FusedMemoryText.SUMMARY_STORAGE),
        SOURCE_CHANGED(FusedMemoryText.RETRY_CHANGED),
        UNAVAILABLE(FusedMemoryText.SUMMARY_UNAVAILABLE),
        REJECTED(FusedMemoryText.SUMMARY_REJECTED),
        UNKNOWN(FusedMemoryText.SUMMARY_UNKNOWN)
    }

    companion object {
        fun from(error: Exception): FusedSummaryFailure {
            if (error is FusedSummaryFailure) return error
            val reason = when (error) {
                is TimeoutCancellationException, is SocketTimeoutException -> Reason.TIMEOUT
                is IncompleteMemoryStream, is IncompleteChatStream -> Reason.INCOMPLETE
                is FailoverRejectedContentException -> Reason.REJECTED
                is FailoverAllFailedException -> error.failures.lastOrNull()?.let {
                    classify(it.statusCode, it.message)
                } ?: Reason.UNAVAILABLE
                is FailoverHttpException -> classify(error.statusCode, error.message.orEmpty())
                is IOException -> Reason.NETWORK
                else -> when (error.message) {
                    FusedMemoryText.MODEL_TOO_SMALL.value() -> Reason.CAPACITY
                    FusedMemoryText.CONTEXT_TOO_LARGE.value() -> Reason.CONTEXT_LIMIT
                    "未配置可用的聊天模型，请在故障转移队列中启用 purpose=chat 的模型" -> Reason.UNAVAILABLE
                    "模型返回了空响应", "响应体为空", "响应 JSON 格式无效", "模型未返回经历摘要" -> Reason.FORMAT
                    else -> Reason.UNKNOWN
                }
            }
            return FusedSummaryFailure(reason)
        }

        private fun classify(status: Int, message: String): Reason {
            val value = message.lowercase()
            return when {
                message == IncompleteChatStream.messageText() -> Reason.INCOMPLETE
                status == -2 || status == 408 || status == 504 -> Reason.TIMEOUT
                status == -1 -> Reason.NETWORK
                status in setOf(401, 403) -> Reason.AUTH
                status == 402 || "insufficient_quota" in value || "quota_exceeded" in value -> Reason.QUOTA
                status == 429 -> Reason.RATE_LIMIT
                status == 413 || "context_length_exceeded" in value || "maximum context length" in value -> Reason.CAPACITY
                "content_filter" in value -> Reason.REJECTED
                status in 500..599 -> Reason.NETWORK
                message in setOf("模型返回了空响应", "响应体为空", "响应 JSON 格式无效", "模型未返回经历摘要") -> Reason.FORMAT
                else -> Reason.UNKNOWN
            }
        }
    }
}
