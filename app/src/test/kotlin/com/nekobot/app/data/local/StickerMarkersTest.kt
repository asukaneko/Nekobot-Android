package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerMarkersTest {

    @Test
    fun `sanitizeName 去掉方括号与换行并限制长度`() {
        assertEquals("猫猫", StickerMarkers.sanitizeName("[猫猫]"))
        assertEquals("a b", StickerMarkers.sanitizeName("a\nb"))
        assertEquals(StickerMarkers.MAX_NAME_LENGTH, StickerMarkers.sanitizeName("x".repeat(200)).length)
    }

    @Test
    fun `candidateNames 排除文件引用并保留顺序去重`() {
        val names = StickerMarkers.candidateNames("你好 [猫猫] 再来 [生气] [猫猫] [File: a.png]")
        assertEquals(listOf("猫猫", "生气"), names)
    }

    @Test
    fun `containsReference 精确匹配方括号标记`() {
        assertTrue(StickerMarkers.containsReference("回复 [猫猫]", "猫猫"))
        assertFalse(StickerMarkers.containsReference("回复 猫猫", "猫猫"))
        assertFalse(StickerMarkers.containsReference("回复 [猫猫猫]", "猫猫"))
    }
}
