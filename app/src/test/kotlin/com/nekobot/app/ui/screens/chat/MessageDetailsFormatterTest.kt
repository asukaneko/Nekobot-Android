package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessageModelFailoverEntry
import com.nekobot.app.data.model.MessageModelUsage
import com.nekobot.app.data.model.MessageModelUsageEntry
import com.nekobot.app.testString
import com.nekobot.app.R
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MessageDetailsFormatterTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun format(message: Message) = formatMessageDetails(message, shanghai, ::testString)

    private fun formatMessageTimestamp(raw: String?, zone: ZoneId) =
        com.nekobot.app.ui.screens.chat.formatMessageTimestamp(raw, zone, testString(R.string.single_role_not_recorded))

    @Test fun isoTimeConvertsToPhoneZoneAndDropsSeconds() {
        assertEquals("2026-10-13 15:30", formatMessageTimestamp("2026-10-13T07:30:59Z", shanghai))
        assertEquals("2026-10-13 15:30", formatMessageTimestamp("2026-10-13T15:30:59+08:00", shanghai))
    }
    @Test fun epochMillisecondsAndSecondsRepresentTheSameMinute() {
        val instant = Instant.parse("2026-10-13T07:30:00Z")
        assertEquals("2026-10-13 15:30", formatMessageTimestamp(instant.toEpochMilli().toString(), shanghai))
        assertEquals("2026-10-13 15:30", formatMessageTimestamp(instant.epochSecond.toString(), shanghai))
    }
    @Test fun localDateTimeRetainsItsRecordedCalendarTime() {
        assertEquals("2026-10-13 15:30", formatMessageTimestamp("2026-10-13 15:30:59", shanghai))
        assertEquals("2026-10-13 15:30", formatMessageTimestamp("2026-10-13T15:30", shanghai))
    }
    @Test fun offsetTimeCanCrossCalendarDayInPhoneZone() {
        assertEquals("2026-10-14 07:30", formatMessageTimestamp("2026-10-13T23:30:00Z", shanghai))
        assertEquals("2026-10-13 16:30", formatMessageTimestamp("2026-10-13T23:30:00Z", ZoneId.of("America/Los_Angeles")))
    }
    @Test fun missingOrInvalidTimeNeverInventsADate() {
        for (raw in listOf(null, "", " ", "15:30", "2026-10-13", "not a time", "2026-02-30T12:00"))
            assertEquals("未记录", formatMessageTimestamp(raw, shanghai))
    }
    @Test fun approvedExampleUsesFullCountsAndOutputDividedByGenerationTime() {
        val result = format(Message(model = "MiMo-V2-Flash", inputTokens = 16_068, outputTokens = 664, durationMs = 15_000.0))
        assertEquals("MiMo-V2-Flash", result.model)
        assertEquals("16,068", result.inputTokens)
        assertEquals("664", result.outputTokens)
        assertEquals("16,732", result.totalTokens)
        assertEquals("15.0 秒", result.duration)
        assertEquals("44.3 Token/秒", result.speed)
    }
    @Test fun missingMetadataDisplaysUnrecorded() {
        val result = format(Message())
        assertEquals(MessageDetails("未记录", "未记录", "未记录", "未记录", "未记录", "未记录", "未记录"), result)
    }
    @Test fun recordedTotalDoesNotPretendMissingInputOrOutputWasZero() {
        val result = format(Message(inputTokens = 20, tokens = 30, durationMs = 1_000.0))
        assertEquals("20", result.inputTokens)
        assertEquals("未记录", result.outputTokens)
        assertEquals("30", result.totalTokens)
        assertEquals("未记录", result.speed)
        assertEquals("未记录", format(Message(outputTokens = 10)).totalTokens)
    }
    @Test fun recordedZeroIsKeptAndInvalidTokenCountsAreUnrecorded() {
        val result = format(Message(inputTokens = 0, outputTokens = 0, durationMs = 1_000.0))
        assertEquals("0", result.totalTokens)
        assertEquals("0.0 Token/秒", result.speed)
        val bad = format(Message(inputTokens = -1, outputTokens = -1, tokens = -1))
        assertEquals("未记录", bad.inputTokens)
        assertEquals("未记录", bad.outputTokens)
        assertEquals("未记录", bad.totalTokens)
    }
    @Test fun sumDoesNotOverflowAndUsesRecordedSplitCounts() {
        assertEquals("4,294,967,294", format(Message(inputTokens = Int.MAX_VALUE, outputTokens = Int.MAX_VALUE)).totalTokens)
        assertEquals("30", format(Message(inputTokens = 20, outputTokens = 10, tokens = 99)).totalTokens)
    }
    @Test fun zeroNegativeAndNonfiniteDurationsDoNotShowInvalidSpeed() {
        for (duration in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val result = format(Message(outputTokens = 100, durationMs = duration))
            assertEquals("未记录", result.duration)
            assertEquals("未记录", result.speed)
        }
    }
    @Test fun modelUsageListsEveryModelAndThenTheFellBackOnes() {
        val result = format(Message(modelUsage = MessageModelUsage(
            models = listOf(
                MessageModelUsageEntry(model = "主模型", actualModel = "model-a",
                    inputTokens = 16_068, outputTokens = 664, calls = 3),
                MessageModelUsageEntry(model = "备用模型", actualModel = "model-b",
                    inputTokens = 900, outputTokens = 40, calls = 1, fallback = true)
            ),
            failovers = listOf(MessageModelFailoverEntry(model = "主模型", actualModel = "model-a", statusCode = 429))
        )))
        assertEquals(3, result.modelUsage.size)
        assertEquals("主模型", result.modelUsage[0].label)
        assertEquals("输入 16,068 · 输出 664 · 3 次调用", result.modelUsage[0].usage)
        assertEquals("备用模型", result.modelUsage[1].label)
        assertEquals("输入 900 · 输出 40 · 回退", result.modelUsage[1].usage)
        assertEquals("主模型", result.modelUsage[2].label)
        assertEquals("HTTP 429，已回退", result.modelUsage[2].usage)
    }

    @Test fun modelUsageWithoutReportedTokensStillShowsTheModel() {
        val result = format(Message(modelUsage = MessageModelUsage(
            models = listOf(MessageModelUsageEntry(actualModel = "model-a", calls = 1))
        )))
        assertEquals("model-a", result.modelUsage.single().label)
        assertEquals("未记录", result.modelUsage.single().usage)
    }

    @Test fun oldMessagesHaveNoModelUsageSection() {
        assertTrue(format(Message()).modelUsage.isEmpty())
    }

    @Test fun smallPositiveDurationUsesActualDivision() {
        assertEquals("400.0 Token/秒", format(Message(outputTokens = 20, durationMs = 50.0)).speed)
        assertEquals("未记录", format(Message(outputTokens = 1, durationMs = Double.MIN_VALUE)).speed)
    }
}
