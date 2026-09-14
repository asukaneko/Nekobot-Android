package com.nekobot.app.ui.screens.extensions

import com.nekobot.app.data.model.KnowledgeDocument
import com.nekobot.app.data.model.KnowledgeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：知识库列表的 LazyColumn key 必须全局唯一。
 *
 * 背景（issue #17）：搜索结果与文档列表在同一个 LazyColumn 中渲染，两者都以文档 id
 * 作为 key；命中检索的文档会同时出现在两个区块，LazyColumn 测量阶段直接抛
 * `IllegalArgumentException: Key "..." was already used` 导致闪退。
 */
class KnowledgeListKeyTest {

    private fun doc(id: String) = KnowledgeDocument(id = id, title = "文档-$id", content = "内容")

    private fun result(
        docId: String,
        chunkIndex: Int,
        charOffset: Int,
        content: String
    ) = KnowledgeSearchResult(
        id = docId,
        title = "文档-$docId",
        content = content,
        chunkIndex = chunkIndex,
        charOffset = charOffset
    )

    /** 命中检索的文档同时出现在搜索结果与文档列表时，两组 key 不能相同。 */
    @Test
    fun searchResultAndDocumentKeysNeverCollide() {
        val hit = result("doc-1", chunkIndex = 0, charOffset = 0, content = "片段")
        val document = doc("doc-1")

        assertTrue(searchResultKey(hit) != knowledgeDocumentKey(document))
    }

    /** 同一文档的多个切片命中时，搜索结果内部 key 也必须互不相同。 */
    @Test
    fun multipleChunksOfSameDocumentHaveDistinctKeys() {
        val keys = listOf(
            result("doc-1", chunkIndex = 0, charOffset = 0, content = "第一段"),
            result("doc-1", chunkIndex = 1, charOffset = 120, content = "第二段"),
            result("doc-1", chunkIndex = 2, charOffset = 240, content = "第三段")
        ).map(::searchResultKey)

        assertEquals(keys.size, keys.toSet().size)
    }

    /** chunk 信息缺失时（远端接口未回传）仍要保证 key 唯一。 */
    @Test
    fun resultsWithoutChunkMetadataStillUnique() {
        val keys = listOf(
            KnowledgeSearchResult(id = "doc-1", title = "A", content = "甲"),
            KnowledgeSearchResult(id = "doc-1", title = "A", content = "乙"),
            KnowledgeSearchResult(id = null, title = "A", content = "丙")
        ).map(::searchResultKey)

        assertEquals(keys.size, keys.toSet().size)
    }

    /** 文档 id 缺失时用对象哈希兜底，不应产生 "doc_null"。 */
    @Test
    fun documentKeyFallsBackWhenIdMissing() {
        val key = knowledgeDocumentKey(KnowledgeDocument(title = "无 id", content = "内容"))

        assertTrue(key.startsWith("doc_"))
        assertTrue("null" !in key)
    }

    /** 整个列表（搜索结果 + 文档列表）拼接后 key 集合不应缩水。 */
    @Test
    fun combinedListKeysAreUnique() {
        val searchResults = listOf(
            result("doc-1", 0, 0, "甲"),
            result("doc-2", 0, 0, "乙")
        )
        val documents = listOf(doc("doc-1"), doc("doc-2"), doc("doc-3"))

        val keys = searchResults.map(::searchResultKey) + documents.map(::knowledgeDocumentKey)
        assertEquals(keys.size, keys.toSet().size)
    }
}
