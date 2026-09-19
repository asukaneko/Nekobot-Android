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
    fun `grep 的 path 指向文件时只检索该文件`() {
        val outcome = grepWorkspace(workspace(), pattern = "Hello", pathPrefix = "src/main/Main.kt")

        assertEquals(1, outcome.matches.size)
        assertEquals("src/main/Main.kt", outcome.matches.first().relativePath)
    }

    @Test
    fun `检索根目录支持工作区相对路径与 workspace 路径`() {
        val root = workspace()

        assertEquals("", resolveSearchRoot("", root, null).pathPrefix)
        assertEquals("", resolveSearchRoot(".", root, null).pathPrefix)
        assertEquals("src/main", resolveSearchRoot("src/main", root, null).pathPrefix)
        assertEquals("src/main", resolveSearchRoot("/workspace/src/main", root, null).pathPrefix)
        assertEquals(
            "src/main",
            resolveSearchRoot(File(root, "src/main").canonicalPath, root, null).pathPrefix
        )
        assertFalse(resolveSearchRoot("src/main", root, null).shared)
    }

    @Test
    fun `检索根目录支持共享工作区`() {
        val root = workspace()
        val shared = tempFolder.newFolder("shared")

        val byScheme = resolveSearchRoot("shared://docs", root, shared)
        assertEquals(null, byScheme.error)
        assertTrue(byScheme.shared)
        assertEquals(shared.canonicalFile, byScheme.base)
        assertEquals("docs", byScheme.pathPrefix)

        val byAbsolute = resolveSearchRoot(File(shared, "docs/guide.md").canonicalPath, root, shared)
        assertEquals(null, byAbsolute.error)
        assertTrue(byAbsolute.shared)
        assertEquals("docs/guide.md", byAbsolute.pathPrefix)
    }

    @Test
    fun `未挂载共享工作区时 shared 根目录给出错误`() {
        val resolved = resolveSearchRoot("shared://", workspace(), null)

        assertTrue("应提示共享工作区不可用", resolved.error != null)
    }

    @Test
    fun `沙箱外的根目录被拒绝而不是退回工作区`() {
        val resolved = resolveSearchRoot("/sdcard/Documents", workspace(), null)

        assertTrue("越界根目录必须报错", resolved.error != null)
    }

    @Test
    fun `根目录与 path 合并后的命中路径可直接用于文件工具`() {
        val root = workspace()
        val shared = tempFolder.newFolder("shared")
        File(shared, "docs").mkdirs()
        File(shared, "docs/note.md").writeText("Hello shared\n")

        val searchRoot = resolveSearchRoot("shared://", root, shared)
        val prefix = combineSearchPrefix(searchRoot.pathPrefix, "docs")
        val outcome = grepWorkspace(searchRoot.base, pattern = "Hello", pathPrefix = prefix)

        assertEquals(1, outcome.matches.size)
        assertEquals(
            "shared://docs/note.md",
            searchResultPath(searchRoot, outcome.matches.first().relativePath)
        )
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
 * Agent 长期记忆抽取：只在值得时触发；先审查已有记忆，再按增/改/删操作改写。
 */
class AgentMemoryExtractorTest {

    private val preferenceHeading = AgentMemoryExtractor.Category.PREFERENCE.heading()
    private val environmentHeading = AgentMemoryExtractor.Category.ENVIRONMENT.heading()
    private val conventionHeading = AgentMemoryExtractor.Category.CONVENTION.heading()

    @Test
    fun `过短的回合不触发抽取`() {
        assertFalse(AgentMemoryExtractor.shouldExtract("你好", "你好！"))
        assertFalse(
            AgentMemoryExtractor.shouldExtract(
                "以后所有回复都用中文".repeat(10),
                "好的，我会一直用中文回复。"
            )
        )
        assertTrue(
            AgentMemoryExtractor.shouldExtract(
                "以后所有回复都用中文，并且先给结论。".repeat(40),
                "好的，我会一直用中文回复并先给结论。"
            )
        )
    }

    @Test
    fun `NONE 与空输出不产生任何操作`() {
        assertTrue(AgentMemoryExtractor.parseActions("NONE").isEmpty())
        assertTrue(AgentMemoryExtractor.parseActions("   ").isEmpty())
        assertTrue(AgentMemoryExtractor.parseActions("这是一段没有操作的说明").isEmpty())
    }

    @Test
    fun `代码块围栏被清理`() {
        val actions = AgentMemoryExtractor.parseActions(
            "```markdown\nadd | $preferenceHeading | 回复使用简体中文\n```"
        )

        assertEquals(1, actions.size)
        assertTrue(actions.first() is AgentMemoryExtractor.MemoryAction.Add)
        assertEquals("回复使用简体中文", actions.first().content)
    }

    @Test
    fun `非标准分类的操作被丢弃`() {
        val actions = AgentMemoryExtractor.parseActions(
            "add | 本次改动 | 修了按钮颜色\nadd | $preferenceHeading | 喜欢简洁回答"
        )

        assertEquals(1, actions.size)
        assertEquals("喜欢简洁回答", actions.first().content)
    }

    @Test
    fun `无法识别的动作被丢弃`() {
        val actions = AgentMemoryExtractor.parseActions(
            "记得 | $preferenceHeading | 喜欢简洁回答"
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `三种动作都能解析`() {
        val actions = AgentMemoryExtractor.parseActions(
            """
            add | $preferenceHeading | 喜欢简洁回答
            replace | $environmentHeading | 构建需要 JDK 21
            delete | $conventionHeading | 旧约定原文
            """.trimIndent()
        )

        assertEquals(3, actions.size)
        assertTrue(actions[0] is AgentMemoryExtractor.MemoryAction.Add)
        assertTrue(actions[1] is AgentMemoryExtractor.MemoryAction.Replace)
        assertTrue(actions[2] is AgentMemoryExtractor.MemoryAction.Delete)
    }

    @Test
    fun `内容里的竖线不会被当成分隔符丢掉`() {
        val actions = AgentMemoryExtractor.parseActions(
            "add | $preferenceHeading | 构建命令是 a | b"
        )

        // 中间的 `|` 属于内容本身，拼回后必须还在，只有动作/分类两处才当分隔符。
        assertEquals(1, actions.size)
        assertTrue(actions.first().content.startsWith("构建命令是 a"))
        assertTrue(actions.first().content.endsWith("b"))
        assertTrue(actions.first().content.contains("|"))
    }

    @Test
    fun `一次性任务细节不写入`() {
        val actions = AgentMemoryExtractor.parseActions(
            "add | $environmentHeading | 本轮临时把 JAVA_HOME 指向了 JDK 21\n" +
                "add | $environmentHeading | Android 项目使用 gradlew.bat 构建"
        )

        assertEquals(1, actions.size)
        assertTrue(actions.first().content.contains("gradlew.bat"))
    }

    @Test
    fun `新增条目时既有内容原样保留`() {
        val existing = "## $preferenceHeading\n- 喜欢简洁回复\n\n# 项目\nNekobot"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Add(
                    AgentMemoryExtractor.Category.ENVIRONMENT,
                    "构建需要 JDK 21"
                )
            )
        )

        assertEquals(1, result.added)
        assertTrue("既有偏好条目必须保留", result.content.contains("喜欢简洁回复"))
        assertTrue("用户手写的无关小节必须保留", result.content.contains("# 项目"))
        assertTrue(result.content.contains("构建需要 JDK 21"))
    }

    @Test
    fun `重复的新增不会写第二遍`() {
        val existing = "## $preferenceHeading\n- 喜欢简洁回复"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Add(
                    AgentMemoryExtractor.Category.PREFERENCE,
                    "喜欢简洁回复"
                )
            )
        )

        assertEquals(0, result.changedItems)
        assertEquals(1, Regex("喜欢简洁回复").findAll(result.content).count())
    }

    @Test
    fun `删除只作用于真实存在的条目`() {
        val existing = "## $preferenceHeading\n- 喜欢简洁回复\n- 使用中文"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Delete(
                    AgentMemoryExtractor.Category.PREFERENCE,
                    "使用中文"
                ),
                // 凭空删除一条不存在的条目：必须被忽略，不能破坏既有内容。
                AgentMemoryExtractor.MemoryAction.Delete(
                    AgentMemoryExtractor.Category.PREFERENCE,
                    "这条根本不存在"
                )
            )
        )

        assertEquals(1, result.deleted)
        assertFalse(result.content.contains("使用中文"))
        assertTrue("未被删除的条目必须保留", result.content.contains("喜欢简洁回复"))
    }

    @Test
    fun `改写本分类条目而非整段覆盖`() {
        val existing = "## $environmentHeading\n- 构建用 gradlew\n- 工作区在 /workspace"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Replace(
                    AgentMemoryExtractor.Category.ENVIRONMENT,
                    "构建用 gradlew.bat 且需要 JDK 21",
                    target = null
                )
            )
        )

        assertEquals(1, result.replaced)
        assertTrue(result.content.contains("JDK 21"))
        assertTrue("同分类的其它条目不能被一起抹掉", result.content.contains("/workspace"))
    }

    @Test
    fun `改写时按旧原文精确定位`() {
        val existing = "## $environmentHeading\n- 构建用 gradlew\n- 工作区在 /workspace"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Replace(
                    AgentMemoryExtractor.Category.ENVIRONMENT,
                    "工作区改到 /sdcard/nekobot",
                    target = "工作区在 /workspace"
                )
            )
        )

        assertTrue(result.content.contains("/sdcard/nekobot"))
        assertTrue("另一条不能被误改", result.content.contains("构建用 gradlew"))
    }

    @Test
    fun `别名标题在改动后归一到标准小节`() {
        val existing = "# 偏好\n- 喜欢简洁回复"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Add(
                    AgentMemoryExtractor.Category.PREFERENCE,
                    "使用中文"
                )
            )
        )

        // 不能同时留下 `# 偏好` 和 `## 用户偏好` 两个同类小节。
        assertEquals(1, Regex("(?m)^#{1,6}\\s").findAll(result.content).count())
        assertTrue(result.content.contains("喜欢简洁回复"))
        assertTrue(result.content.contains("使用中文"))
    }

    @Test
    fun `清空后的分类小节不再保留空标题`() {
        val existing = "## $preferenceHeading\n- 唯一一条"

        val result = AgentMemoryExtractor.applyActions(
            existing,
            listOf(
                AgentMemoryExtractor.MemoryAction.Delete(
                    AgentMemoryExtractor.Category.PREFERENCE,
                    "唯一一条"
                )
            )
        )

        assertEquals(1, result.deleted)
        assertFalse(result.content.contains(preferenceHeading))
    }

    @Test
    fun `抽取提示词注入当前记忆并要求先审查`() {
        val prompt = AgentMemoryExtractor.buildExtractionPrompt(
            userMessage = "用户说了 A",
            assistantMessage = "Agent 回答了 B",
            existingMemory = "## $preferenceHeading\n- 喜欢简洁回复"
        )

        assertTrue(prompt.contains("用户说了 A"))
        assertTrue(prompt.contains("Agent 回答了 B"))
        assertTrue("必须把现有记忆发给模型", prompt.contains("喜欢简洁回复"))
        assertTrue("必须要求先审查再决定", prompt.contains("先审查已有记忆"))
        assertTrue(prompt.contains("NONE"))
        assertTrue(prompt.contains(preferenceHeading))
        assertTrue(prompt.contains("绝对不要记录"))
    }

    @Test
    fun `没有记忆时提示词说明按新增处理`() {
        val prompt = AgentMemoryExtractor.buildExtractionPrompt("A", "B", existingMemory = "")

        assertTrue(prompt.contains("尚无任何记忆"))
    }

    @Test
    fun `记忆过长时截断并明确告知模型`() {
        val long = "## $preferenceHeading\n" + (1..5_000).joinToString("\n") { "- 条目 $it" }

        val prompt = AgentMemoryExtractor.buildExtractionPrompt("A", "B", existingMemory = long)

        assertTrue(prompt.contains("只显示了前面一部分"))
        assertTrue("提示词不能无界增长", prompt.length < long.length)
    }
}
