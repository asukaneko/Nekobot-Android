package com.nekobot.app.ui.screens.chat

import com.nekobot.app.ui.components.MdBlock
import com.nekobot.app.ui.components.parseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块不能被消息分段劈开。
 *
 * 回归场景（占位数据合成，不含真实内容）：技能目录树里有一行
 * `config.json ← 记录来源，如 {"source_url":"https://…"}`，
 * 早期按 URL 切段会把围栏切成两半，气泡里就出现「代码块只剩前几行 + 裸露的 `}` 与目录树残片」。
 */
class CodeBlockSegmentParsingTest {

    private val original = """
        技能都放在**应用私有目录的 skills 文件夹**里：

        ```
        /app/files/skills/            ← 技能根目录
        ├── demo-skill/
        │   ├── SKILL.md              ← 必需：技能本体
        │   ├── reference.md          ← 可选：细节说明
        │   ├── config.json           ← 记录来源，如 {"source_url":"https://example.com/owner/demo-skill"}
        │   ├── scripts/  resources/  references/  tests/   ← 可选：同级所有文件都会被一起保存
        ├── another-skill/
        └── …（共 3 个目录）
        ```

        要点：

        - **一个技能对应一个目录**，目录名就是技能名。
    """.trimIndent()

    @Test
    fun `code block with url stays one text segment`() {
        val segments = parseContentSegments(original)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments.single().type)
    }

    @Test
    fun `code block keeps every tree line and the url inside it`() {
        val blocks = parseBlocks(original, chatMode = true)
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single().code

        assertTrue(code.startsWith("/app/files/skills/"))
        assertTrue(code.contains("example.com/owner/demo-skill"))
        assertTrue(code.contains("└── …（共 3 个目录）"))
        // 渲染器不再拿到两段残缺 markdown：代码块之外没有残留的目录树碎片
        val outside = blocks.filterNot { it is MdBlock.CodeBlock }
            .joinToString("\n") { (it as? MdBlock.Paragraph)?.content.orEmpty() }
        assertFalse(outside.contains("│"))
        assertFalse(outside.contains("\"}\n"))
    }

    @Test
    fun `url inside inline code is not split out`() {
        val segments = parseContentSegments("接口地址是 `https://example.com/api` 记得替换。")

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments.single().type)
        assertTrue(segments.none { it.type == SegmentType.LINK })
    }

    @Test
    fun `plain url outside code still becomes a link segment`() {
        val segments = parseContentSegments("看图 https://example.com/a.png 就懂")

        assertTrue(segments.any { it.type == SegmentType.IMAGE })
    }

    @Test
    fun `file reference and sticker inside code stay literal`() {
        val content = "```\n[File: a.png]\n[猫猫]\n```"
        val segments = parseContentSegments(content) { "file:///stickers/cat.png" }

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments.single().type)
    }

    @Test
    fun `unclosed code block during streaming still protects its url`() {
        val content = "记一下：\n```\ncurl https://example.com/hook"
        val segments = parseContentSegments(content)

        assertEquals(1, segments.size)
        assertTrue(segments.single().text.endsWith("https://example.com/hook"))
    }

    @Test
    fun `unstyled backtick does not swallow the following url`() {
        // 落单反引号不是行内代码，后面的网址仍要成为链接段
        val segments = parseContentSegments("反引号 ` 后面 https://example.com/x")

        assertTrue(segments.any { it.type == SegmentType.LINK })
    }

    @Test
    fun `code block and table use the wide bubble width bucket`() {
        assertTrue(markdownUsesWideBlocks(original))
        assertTrue(markdownUsesWideBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |"))
        assertTrue(markdownUsesWideBlocks("```\ncode\n```"))
        assertFalse(markdownUsesWideBlocks("普通正文，只有 `inline` 代码。"))
        assertFalse(markdownUsesWideBlocks("普通正文"))
    }

    @Test
    fun `table split by url keeps its separator row`() {
        val content = "| 名称 | 地址 |\n| --- | --- |\n| 文档 | https://example.com/doc |"
        val segments = parseContentSegments(content)

        // 表格里的网址拆段会破坏表格结构，分隔行必须仍在同一个文本段里
        val first = segments.first()
        assertEquals(SegmentType.TEXT, first.type)
        assertTrue(first.text.contains("| --- | --- |"))
    }
}
