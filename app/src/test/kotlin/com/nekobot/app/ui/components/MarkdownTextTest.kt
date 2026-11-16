package com.nekobot.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun `short reply and folded inner monologue use content width`() {
        val blocks = visibleMarkdownBlocks("又是\"1\"……（内心：他是不是在逗我）", true, true)
        assertTrue(blocks.any { it is MdBlock.InnerMonologue })
        assertTrue(chatMarkdownWrapsWidth(blocks))
    }

    @Test
    fun `hidden inner monologue keeps short visible reply content width`() {
        val blocks = visibleMarkdownBlocks("好呀（内心：很长的内心内容）", true, false)
        assertEquals(listOf(MdBlock.Paragraph("好呀")), blocks)
        assertTrue(chatMarkdownWrapsWidth(blocks))
    }

    @Test
    fun `rich blocks keep dedicated sizing instead of intrinsic text sizing`() {
        listOf("```kotlin\nprintln(1)\n```", "![图片](https://example.com/a.png)",
            "| A | B |\n| --- | --- |\n| 1 | 2 |").forEach { source ->
            assertFalse(source, chatMarkdownWrapsWidth(parseBlocks(source, chatMode = true)))
        }
    }

    @Test
    fun `chat preprocessing keeps original text and never injects private glyphs`() {
        val source = "她说：**（轻声回应）**，然后写下 `call(foo)` 与 ~~原文~~。"
        val preprocessed = preprocessMarkdown(source, chatMode = true)
        val rendered = parseInline(
            preprocessed,
            Color.Black,
            TextStyle.Default,
            styleParentheses = true
        )

        assertEquals(source.replace("**", "").replace("`", ""), rendered.text)
        assertFalse(rendered.text.contains("PAREN"))
        assertFalse(rendered.text.any { it.code in 0xE000..0xF8FF })
    }

    @Test
    fun `code block keeps parentheses unchanged`() {
        val blocks = parseBlocks("```kotlin\ncall(foo)\n```", chatMode = true)

        assertEquals(1, blocks.size)
        assertEquals("call(foo)", (blocks.single() as MdBlock.CodeBlock).code)
    }

    @Test
    fun `standalone markdown image is parsed as an image block`() {
        val block = parseBlocks("![生成图片](file:///data/user/0/com.nekobot.app/files/image.png)").single()

        assertTrue(block is MdBlock.Image)
        block as MdBlock.Image
        assertEquals("生成图片", block.alt)
        assertEquals("file:///data/user/0/com.nekobot.app/files/image.png", block.url)
    }

    @Test
    fun `inner monologue is parsed structurally without visible marker`() {
        val blocks = parseBlocks("前文（内心：有点紧张）后文", chatMode = true)

        assertEquals(3, blocks.size)
        assertEquals("前文", (blocks[0] as MdBlock.Paragraph).content)
        assertEquals("有点紧张", (blocks[1] as MdBlock.InnerMonologue).content)
        assertEquals("后文", (blocks[2] as MdBlock.Paragraph).content)
        assertTrue(blocks.none { it.toString().contains("INNER:") })
    }

    @Test
    fun `http markdown link keeps clickable URL annotation`() {
        val rendered = parseInline(
            "[Nekobot](https://example.com/docs)",
            Color.Black,
            TextStyle.Default
        )

        assertEquals("Nekobot", rendered.text)
        assertEquals(
            "https://example.com/docs",
            rendered.getStringAnnotations("URL", 0, rendered.length).single().item
        )
    }

    @Test
    fun `completed markdown received incrementally is rendered by the standard parser`() {
        val rendered = parseInline(
            "正在生成 **Markdown** 和 `code`",
            Color.Black,
            TextStyle.Default
        )

        assertEquals("正在生成 Markdown 和 code", rendered.text)
        assertEquals(2, rendered.spanStyles.size)
    }

    @Test
    fun `unsupported markdown link does not receive URL annotation`() {
        val rendered = parseInline(
            "[Local file](file:///sdcard/example.txt)",
            Color.Black,
            TextStyle.Default
        )

        assertTrue(rendered.getStringAnnotations("URL", 0, rendered.length).isEmpty())
    }

    @Test
    fun `table copy text is tab separated and strips inline markdown`() {
        val table = parseBlocks(
            "| Name | Value |\n| --- | --- |\n| **Neko** | `42` |"
        ).single() as MdBlock.Table

        assertEquals(
            "Name\tValue\nNeko\t42",
            tableToTsv(table, Color.Black, TextStyle.Default)
        )
    }

    /**
     * 长回复渲染曾经在逐行循环里现场编译正则：Android 上每条消息要编译上万次 ICU pattern，
     * 把主线程卡住数秒后触发「输入事件无响应」。这里用一份多行文档守住解析路径，
     * 既校验区块结构，也给整段解析留一个宽松的时间上限（预编译后仅数十毫秒）。
     */
    @Test
    fun `long reply parses blocks without per line pattern compilation stalls`() {
        val reply = buildString {
            repeat(400) { index ->
                appendLine("## 小节 $index")
                appendLine("第 $index 段正文，含 `code`、**粗体** 与（内心：独白 $index）。")
                appendLine("1. 有序项")
                appendLine("- 无序项")
                appendLine("| A | B |")
                appendLine("| --- | --- |")
                appendLine("| 1 | 2 |")
                appendLine()
            }
        }

        parseBlocks("预热", chatMode = true) // 排除类加载与 JIT 首次开销
        val startedAt = System.nanoTime()
        val blocks = parseBlocks(reply, chatMode = true)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(blocks.any { it is MdBlock.Header })
        assertTrue(blocks.any { it is MdBlock.Table })
        assertTrue(blocks.any { it is MdBlock.ListItem })
        assertTrue(blocks.any { it is MdBlock.InnerMonologue })
        assertTrue("解析 $elapsedMs ms 过慢", elapsedMs < 5_000)
    }
}
