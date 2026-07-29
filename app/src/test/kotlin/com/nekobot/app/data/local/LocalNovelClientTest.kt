package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNovelClientTest {

    @Test
    fun `wenku8 headers let OkHttp negotiate compression automatically`() {
        val headers = buildWenku8Headers("jieqiUserInfo=test")

        // 不手动设置 Accept-Encoding，让 OkHttp 自动添加 gzip 并透明解压
        assertNull(headers["Accept-Encoding"])
        assertEquals("jieqiUserInfo=test", headers["Cookie"])
    }

    @Test
    fun `wenku8 headers use custom User-Agent when provided`() {
        val customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36"
        val headers = buildWenku8Headers("jieqiUserInfo=test", customUa)

        assertEquals(customUa, headers["User-Agent"])
    }

    @Test
    fun `search URL keeps the GBK keyword encoded exactly once`() {
        val url = buildWenku8SearchUrl("魔法", "articlename", 2)
        val query = requireNotNull(url.encodedQuery)

        assertTrue(query.contains("searchkey=%C4%A7%B7%A8"))
        assertFalse(query.contains("%25C4"))
        assertEquals("2", url.queryParameter("page"))
    }

    @Test
    fun `current wenku8 ranking card parses all core fields`() {
        val html = """
            <div style="width:95px;float:left;">
            <a href="/book/3057.htm" title="败北女角太多了！(败犬女主太多了！)">
            <img src="http://img.wenku8.com/image/3/3057/3057s.jpg" height="130" width="90"/>
            </a></div>
            <div style="margin-top:2px;">
              <b><a style="font-size:13px;" href="/book/3057.htm" title="败北女角太多了！(败犬女主太多了！)" target="_blank">败北女角太多了！(败犬女主太多了！)</a></b>
              <p>作者:雨森焚火/分类:小学馆</p>
              <p>更新:2026-07-19/字数:1270K/连载中/<span class="hottext">已动画化</span></p>
              <p>Tags:<span style="font-weight:bold;color: #1b74bc;">校园 欢乐向 青春 恋爱</span></p>
              <p>简介:平常担任班上背景人物的我──温水和彦。</p>
            </div>
        """.trimIndent()

        val book = requireNotNull(parseNovelCardBlock(html))

        assertEquals("3057", book.id)
        assertEquals("败北女角太多了！(败犬女主太多了！)", book.title)
        assertEquals("雨森焚火", book.author)
        assertEquals("小学馆", book.category)
        assertEquals("1270K", book.wordCount)
        assertEquals("连载中", book.isSerialize)
        assertEquals("2026-07-19", book.lastDate)
        assertEquals("http://img.wenku8.com/image/3/3057/3057s.jpg", book.coverUrl)
    }

    @Test
    fun `hotnovel arguments retain original aliases and limits`() {
        assertEquals(LocalNovelRankingPeriod.DAY, parseLocalNovelRankingPeriod(""))
        assertEquals(LocalNovelRankingPeriod.MONTH, parseLocalNovelRankingPeriod("mouth"))
        assertEquals(10, parseLocalNovelHotLimit("day"))
        assertEquals(100, parseLocalNovelHotLimit("month 500"))
    }
}
