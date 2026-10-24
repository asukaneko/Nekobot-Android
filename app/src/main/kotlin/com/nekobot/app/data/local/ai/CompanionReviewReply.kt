package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionLockedReviewEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalMessageVariantEntity
import java.time.Instant

/** 只以数据库中已保存的回复作为弹窗边界；重抽沿用消息ID时使用最新候选的保存时间。 */
internal data class CompanionReviewReply(val boundary: String, val savedAt: Instant) {
    fun includes(review: CompanionLockedReviewEntity): Boolean = runCatching {
        !savedAt.isBefore(Instant.parse(review.createdAt))
    }.getOrDefault(false)

    companion object {
        fun latest(dialogue: LocalMessageEntity?, variantHost: LocalMessageEntity?, variant: LocalMessageVariantEntity?): CompanionReviewReply? {
            val direct = from(dialogue, emptyList())
            val regenerated = from(variantHost, listOfNotNull(variant))
                ?.takeIf { variantHost?.sessionId == dialogue?.sessionId && variant != null }
                ?.takeIf { candidate -> runCatching { !candidate.savedAt.isBefore(Instant.parse(requireNotNull(dialogue).createdAt)) }.getOrDefault(false) }
            return listOfNotNull(direct, regenerated).maxByOrNull { it.savedAt }
        }

        fun from(message: LocalMessageEntity?, variants: List<LocalMessageVariantEntity>): CompanionReviewReply? {
            if (message == null || message.deleted || message.role != "assistant" || message.content.isBlank()) return null
            val saved = (listOf(message.createdAt) + variants.filter { it.messageId == message.id && it.sessionId == message.sessionId }.map { it.createdAt })
                .mapNotNull { runCatching { Instant.parse(it) }.getOrNull() }.maxOrNull() ?: return null
            return CompanionReviewReply("${message.id}:$saved", saved)
        }
    }
}
