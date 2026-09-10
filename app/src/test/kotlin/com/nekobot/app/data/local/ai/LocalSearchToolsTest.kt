package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 检索工具（grep / glob）与网页正文抽取：让模型"先定位再精确读取"，避免整份读文件。
 */
class LocalSearchToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun workspace(): File {
        val root = tempFolder.newFolder("workspace")
        File(root, "src/main").mkdirs()
        File(root, "docs").mkdirs()
        File(root, "src/main/Main.kt").writeText(
            """
            fun main() {
                val greeting = "Hello Nekobot"
                println(greeting)
            }
            """.trimIndent()
        )
        File(root, "src/main/Util.kt").writeText("val VERSION = \"0.7.0\"\n")
        File(root, "docs/guide.md").writeText("# 指南\nNekobot 使用说明\n")
        File(root, "node_modules/dep").mkdirs()
        File(root, "node_modules/dep/index.js").writeText("console.log('Hello')\n")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("[core]\n\trepositoryformatversion = 0\n")
        return root
    }

    @Test
    fun `grep 按正则命中并带行号`() {
        val outcome = grepWorkspace(workspace(), pattern = "Hello", caseSensitive = true)

        assertEquals(1, outcome.matches.size)
        assertEquals("src/main/Main.kt", outcome.matches.first().relativePath)
        assertEquals(2, outcome.matches.first().lineNumber)
        assertTrue(outcome.matches.first().line.contains("Hello Nekobot"))
    }

    @Test
    fun `grep 默认忽略大小写`() {
        val outcome = grepWorkspace(workspace(), pattern = "hello")

        assertTrue("默认应忽略大小写命中", outcome.matches.isNotEmpty())
    }

    @Test
    fun `grep 跳过隐藏与依赖目录`() {
        val outcome = grepWorkspace(workspace(), pattern = "Hello")

        val paths = outcome.matches.map { it.relativePath }
        assertFalse("不应检索 node_modules: $paths", paths.any { it.startsWith("node_modules") })
        assertFalse("不应检索 .git: $paths", paths.any { it.startsWith(".git") })
    }

    @Test
    fun `grep 支持 glob 过滤文件名`() {
        val outcome = grepWorkspace(workspace(), pattern = "Nekobot", fileGlob = "*.md")

        assertEquals(1, outcome.matches.size)
        assertEquals("docs/guide.md", outcome.matches.first().relativePath)
    }

    @Test
    fun `grep 支持 path 限定子目录`() {
        val outcome = grepWorkspace(workspace(), pattern = "VERSION", pathPrefix = "src")

        assertEquals(1, outcome.matches.size)
        assertEquals("src/main/Util.kt", outcome.matches.first().relativePath)
    }

    @Test
    fun `grep 达到上限时标记截断`() {
        val outcome = grepWorkspace(workspace(), pattern = "e", limit = 1)

        assertTrue(outcome.truncated)
        assertEquals(1, outcome.matches.size)
    }

    @Test
    fun `grep 对非法正则返回错误而不是抛异常`() {
        val outcome = grepWorkspace(workspace(), pattern = "([")

        assertTrue(outcome.error != null)
        assertTrue(outcome.matches.isEmpty())
        assertTrue(formatGrepOutcome("([", outcome, 50).contains("正则表达式无效"))
    }

    @Test
    fun `grep 结果文本包含定位信息`() {
        val outcome = grepWorkspace(workspace(), pattern = "VERSION")

        val text = formatGrepOutcome("VERSION", outcome, 50)
        assertTrue(text.contains("src/main/Util.kt:1:"))
    }

    @Test
    fun `glob 支持跨目录通配`() {
        val outcome = globWorkspace(workspace(), pattern = "**/*.kt")

        assertEquals(listOf("src/main/Main.kt", "src/main/Util.kt"), outcome.files.map { it.first })
    }

    @Test
    fun `glob 单星号不跨目录而双星号跨目录`() {
        val root = workspace()
        // 不带目录分隔符的模式按文件名匹配（与 ripgrep -g 一致）
        val byName = globWorkspace(root, pattern = "*.md")
        assertEquals(listOf("docs/guide.md"), byName.files.map { it.first })

        // 带目录时 * 不跨目录：src/*.kt 不匹配 src/main/Main.kt
        val shallow = globWorkspace(root, pattern = "src/*.kt")
        assertTrue("src/*.kt 不应匹配子目录中的文件: ${shallow.files}", shallow.files.isEmpty())

        val deep = globWorkspace(root, pattern = "src/**/*.kt")
        assertEquals(listOf("src/main/Main.kt", "src/main/Util.kt"), deep.files.map { it.first })
    }

    @Test
    fun `glob 结果文本包含文件大小`() {
        val outcome = globWorkspace(workspace(), pattern = "**/*.md")

        val text = formatGlobOutcome("**/*.md", outcome, 50)
        assertTrue(text, text.contains("docs/guide.md"))
        assertTrue(text, text.contains("B)"))
    }

    @Test
    fun `glob 未命中时给出明确说明`() {
        val outcome = globWorkspace(workspace(), pattern = "**/*.rs")

        assertTrue(formatGlobOutcome("**/*.rs", outcome, 50).contains("未找到"))
    }
}

/**
 * 网页正文抽取：去掉脚本/样式/标签，保留段落结构。
 */
class LocalWebFetchExtractionTest {

    @Test
    fun `抽取正文并去掉脚本样式`() {
        val html = """
            <html><head><title>T</title><style>body{color:red}</style>
            <script>var a = "<div>not text</div>";</script></head>
            <body><h1>标题</h1><p>第一段</p><p>第二段</p>
            <a href="https://x">链接</a></body></html>
        """.trimIndent()

        val text = LocalWebFetch.extractReadableText(html)

        assertTrue(text.contains("标题"))
        assertTrue(text.contains("第一段"))
        assertTrue(text.contains("第二段"))
        assertTrue(text.contains("链接"))
        assertFalse("脚本内容必须被移除", text.contains("not text"))
        assertFalse("样式必须被移除", text.contains("color:red"))
        assertFalse("标签必须被移除", text.contains("<p>"))
    }

    @Test
    fun `实体被解码`() {
        val text = LocalWebFetch.extractReadableText("<p>a &amp; b &lt;tag&gt; &nbsp;c</p>")

        assertTrue(text.contains("a & b <tag>"))
    }

    @Test
    fun `超过预算时截断并提示`() {
        val html = "<p>" + "字".repeat(5_000) + "</p>"

        val text = LocalWebFetch.extractReadableText(html, maxChars = 1_000)

        assertTrue(text.length < 1_200)
        assertTrue(text.contains("正文已截断"))
    }

    @Test
    fun `HTML 与 JSON 响应体可区分`() {
        assertTrue(LocalWebFetch.looksLikeHtml("text/html; charset=utf-8", "<html></html>"))
        assertFalse(LocalWebFetch.looksLikeHtml("application/json", "{\"a\":1}"))
        assertTrue(LocalWebFetch.looksLikeHtml(null, "<div>内容</div>"))
    }

    @Test
    fun `空响应返回空文本`() {
        assertEquals("", LocalWebFetch.extractReadableText(""))
    }
}

/**
 * Agent 长期记忆抽取：只在值得时触发，按小节去重合并。
 */
class AgentMemoryExtractorTest {

    @Test
    fun `过短的回合不触发抽取`() {
        assertFalse(AgentMemoryExtractor.shouldExtract("你好", "你好！"))
        assertTrue(
            AgentMemoryExtractor.shouldExtract(
                "以后所有回复都用中文".repeat(20),
                "好的，我会一直用中文回复。"
            )
        )
    }

    @Test
    fun `NONE 与空输出都不写入`() {
        assertEquals("", AgentMemoryExtractor.sanitizeExtraction("NONE"))
        assertEquals("", AgentMemoryExtractor.sanitizeExtraction("   "))
        assertEquals("", AgentMemoryExtractor.sanitizeExtraction("这是一段没有小节的说明"))
    }

    @Test
    fun `代码块围栏被清理`() {
        val cleaned = AgentMemoryExtractor.sanitizeExtraction("```markdown\n# 偏好\n中文回复\n```")

        assertTrue(cleaned.startsWith("# 偏好"))
        assertFalse(cleaned.contains("```"))
    }

    @Test
    fun `合并时同标题小节被更新而非重复追加`() {
        val existing = "# 偏好\n喜欢简洁回复\n\n# 项目\nNekobot"
        val addition = "# 偏好\n喜欢简洁的中文回复"

        val merged = AgentMemoryExtractor.mergeMemory(existing, addition)

        assertEquals(1, Regex("(?m)^# 偏好$").findAll(merged).count())
        assertTrue(merged.contains("喜欢简洁的中文回复"))
        assertTrue("未涉及的既有小节必须保留", merged.contains("# 项目"))
    }

    @Test
    fun `合并新标题时追加在末尾`() {
        val merged = AgentMemoryExtractor.mergeMemory("# 偏好\n中文", "# 环境\n工作区使用 /workspace")

        assertTrue(merged.indexOf("# 偏好") < merged.indexOf("# 环境"))
    }

    @Test
    fun `抽取提示词包含双方内容与格式要求`() {
        val prompt = AgentMemoryExtractor.buildExtractionPrompt("用户说了 A", "Agent 回答了 B")

        assertTrue(prompt.contains("用户说了 A"))
        assertTrue(prompt.contains("Agent 回答了 B"))
        assertTrue(prompt.contains("NONE"))
    }
}
