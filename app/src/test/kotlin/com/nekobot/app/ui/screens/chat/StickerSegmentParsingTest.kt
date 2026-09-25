package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSegmentParsingTest {

    private val lookup: (String) -> String? = { name ->
        when (name) {
            "猫猫" -> "file:///stickers/cat.png"
            "生气" -> "file:///stickers/angry.png"
            else -> null
        }
    }

    @Test
    fun `未命中表情时保持原文`() {
        val segments = parseContentSegments("你好 [未知表情]", lookup)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments[0].type)
        assertEquals("你好 [未知表情]", segments[0].text)
    }

    @Test
    fun `命中表情时解析为表情段并保留前后文字`() {
        val segments = parseContentSegments("开心 [猫猫] 结束", lookup)

        assertEquals(3, segments.size)
        assertEquals(SegmentType.TEXT, segments[0].type)
        assertEquals("开心 ", segments[0].text)
        assertEquals(SegmentType.STICKER, segments[1].type)
        assertEquals("猫猫", segments[1].text)
        assertEquals("file:///stickers/cat.png", segments[1].url)
        assertEquals(" 结束", segments[2].text)
    }

    @Test
    fun `多个表情与文件引用可以共存`() {
        val segments = parseContentSegments("[猫猫][生气] [File: a.png]", lookup)

        assertEquals(3, segments.size)
        assertEquals(SegmentType.STICKER, segments[0].type)
        assertEquals(SegmentType.STICKER, segments[1].type)
        assertEquals(SegmentType.FILE, segments[2].type)
        assertEquals("a.png", segments[2].fileName)
    }

    @Test
    fun `没有查询表时行为与旧版一致`() {
        val segments = parseContentSegments("[猫猫]")

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments[0].type)
        assertTrue(segments[0].text.contains("[猫猫]"))
    }

    @Test
    fun `表情段计入图片内容分组`() {
        val sticker = parseContentSegments("[猫猫]", lookup).first()

        assertTrue(sticker.isImageContent())
    }
}
