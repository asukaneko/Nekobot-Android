package com.nekobot.app.data.local.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKnowledgeSearchTest {

    @Test
    fun chineseLexicalSearchRanksRelatedTextHigher() {
        val related = LocalKnowledgeSearch.lexicalScore(
            "量子计算原理",
            "量子计算利用量子比特和叠加态完成计算。"
        )
        val unrelated = LocalKnowledgeSearch.lexicalScore(
            "量子计算原理",
            "今天适合去公园散步和拍照。"
        )

        assertTrue(related > unrelated)
        assertTrue(related > 0f)
    }

    @Test
    fun chunkingKeepsLongDocumentSearchable() {
        val paragraph = "这是一个用于测试知识库切片的段落。".repeat(80)
        val chunks = LocalKnowledgeSearch.chunk(paragraph, chunkSize = 300, overlap = 50)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 300 })
    }

    @Test
    fun cosineReturnsOneForSameVector() {
        assertEquals(
            1f,
            LocalKnowledgeSearch.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)),
            0.0001f
        )
    }

    @Test
    fun markupImporterRemovesTagsAndScripts() {
        val text = LocalKnowledgeDocumentImporter.stripMarkup(
            "<h1>标题</h1><script>ignore()</script><p>正文 &amp; 资料</p>"
        )

        assertTrue("标题" in text)
        assertTrue("正文 & 资料" in text)
        assertTrue("ignore" !in text)
    }
}
