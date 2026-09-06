package com.nekobot.app.data.local.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AndroidUsageGuide（android_help 指南）的纯 JVM 行为测试。 */
class AndroidUsageGuideTest {

    @Test
    fun zhFullGuideCoversAllChapters() {
        val guide = AndroidUsageGuide.build("zh", null)
        assertTrue(guide.contains("权限"))
        assertTrue(guide.contains("标准操作流程"))
        assertTrue(guide.contains("编号定位"))
        assertTrue(guide.contains("坐标手势"))
        assertTrue(guide.contains("文本输入"))
        assertTrue(guide.contains("滚动与加载"))
        assertTrue(guide.contains("观察与诊断"))
        assertTrue(guide.contains("常见陷阱"))
        assertTrue(guide.contains("安全规则"))
        assertTrue(guide.contains("android_ui_tap"))
    }

    @Test
    fun zhTopicFilterReturnsSingleChapter() {
        val guide = AndroidUsageGuide.build("zh", "gestures")
        assertTrue(guide.contains("坐标手势"))
        assertFalse(guide.contains("安全规则"))
        assertFalse(guide.contains("常见陷阱"))
    }

    @Test
    fun zhAllTopicReturnsFullGuide() {
        val guide = AndroidUsageGuide.build("zh", "all")
        assertTrue(guide.contains("标准操作流程"))
        assertTrue(guide.contains("安全规则"))
    }

    @Test
    fun unknownTopicListsAvailableTopics() {
        val guide = AndroidUsageGuide.build("zh", "not_a_topic")
        assertTrue(guide.contains("未知主题"))
        assertTrue(guide.contains("permissions"))
        assertTrue(guide.contains("safety"))
    }

    @Test
    fun enGuideIsEnglish() {
        val guide = AndroidUsageGuide.build("en", null)
        assertTrue(guide.contains("Standard flow"))
        assertTrue(guide.contains("Coordinate gestures"))
        assertTrue(guide.contains("Safety rules"))
        assertTrue(guide.contains("android_wait_for_idle"))
    }

    @Test
    fun jaGuideIsJapanese() {
        val guide = AndroidUsageGuide.build("ja", null)
        assertTrue(guide.contains("標準フロー"))
        assertTrue(guide.contains("座標ジェスチャー"))
        assertTrue(guide.contains("安全ルール"))
    }

    @Test
    fun koGuideIsKorean() {
        val guide = AndroidUsageGuide.build("ko", null)
        assertTrue(guide.contains("표준 흐름"))
        assertTrue(guide.contains("좌표 제스처"))
        assertTrue(guide.contains("안전 규칙"))
    }

    @Test
    fun languageNormalizationStripsRegionAndCase() {
        val zhTw = AndroidUsageGuide.build("zh-TW", null)
        val zhCn = AndroidUsageGuide.build("ZH_CN", null)
        assertTrue(zhTw.contains("标准操作流程"))
        assertTrue(zhCn.contains("标准操作流程"))
    }
}
