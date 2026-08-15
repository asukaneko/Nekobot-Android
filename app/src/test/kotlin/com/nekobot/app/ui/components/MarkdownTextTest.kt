package com.nekobot.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

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
    fun `unsupported markdown link does not receive URL annotation`() {
        val rendered = parseInline(
            "[Local file](file:///sdcard/example.txt)",
            Color.Black,
            TextStyle.Default
        )

        assertTrue(rendered.getStringAnnotations("URL", 0, rendered.length).isEmpty())
    }
}
