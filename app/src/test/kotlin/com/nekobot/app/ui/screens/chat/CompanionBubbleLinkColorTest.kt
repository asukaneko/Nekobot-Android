package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.nekobot.app.ui.components.markdownLinkColor
import com.nekobot.app.ui.components.parseInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBubbleLinkColorTest {
    @Test fun lightDefaultLinksRemainReadable() {
        val palette = companionChatPalette(false)
        assertEquals(0xFF000000.toInt(), companionBubbleLinkColor(palette).toArgb())
        assertReadableLinks(palette)
    }

    @Test fun darkDefaultLinksRemainReadable() {
        val palette = companionChatPalette(true)
        // 深色模式 AI 气泡改为主题表面色，默认字色/链接色相应提亮
        assertEquals(0xFFFFFFFF.toInt(), companionBubbleLinkColor(palette).toArgb())
        assertReadableLinks(palette)
    }

    @Test fun customLinksRemainReadableInLightMode() = assertCustomLinks(false)
    @Test fun customLinksRemainReadableInDarkMode() = assertCustomLinks(true)

    @Test fun grayscaleCustomColorsPassContrastAcrossTheBrightnessBoundary() {
        listOf(false, true).forEach { dark ->
            (0..255).forEach { value ->
                val color = Color(0xFF000000.toInt() or (value shl 16) or (value shl 8) or value)
                assertReadableLinks(companionChatPalette(dark, color, color))
            }
        }
    }

    @Test fun markdownLinksUseEachBubbleInkAndKeepUrlAndUnderline() {
        listOf(false, true).forEach { dark ->
            (listOf<Color?>(null) + customColors).forEach { custom ->
                val palette = companionChatPalette(dark, custom)
                val assistant = companionAssistantBubbleStyle(palette)
                for (isUser in listOf(false, true)) {
                    val ink = companionBubbleLinkColor(palette, isUser)
                    val colors = if (isUser) null else assistant.markdown
                    val rendered = parseInline("[点这里](https://example.com)", ink,
                        TextStyle.Default, linkColor = markdownLinkColor(colors, ink))
                    assertEquals("点这里", rendered.text)
                    assertEquals(ink, rendered.spanStyles.single().item.color)
                    assertEquals(TextDecoration.Underline, rendered.spanStyles.single().item.textDecoration)
                    assertEquals("https://example.com",
                        rendered.getStringAnnotations("URL", 0, rendered.length).single().item)
                }
            }
        }
    }

    @Test fun assistantBubbleLinkOverridePreservesDetailSurfaceLinks() {
        listOf(false, true).forEach { dark ->
            val palette = companionChatPalette(dark)
            val colors = companionAssistantBubbleStyle(palette).markdown
            val shared = companionBubbleLinkColor(palette)
            assertEquals(colors.surfaceLink, markdownLinkColor(colors, shared, onSurface = true))
            assertEquals(Color(0xFF58A6FF), markdownLinkColor(null, shared, onSurface = true))
        }
    }

    @Test fun markdownOutsideBubbleKeepsExistingLinkColors() {
        val colors = companionAssistantBubbleStyle(companionChatPalette(false)).markdown
        assertEquals(colors.link, markdownLinkColor(colors, null))
        assertEquals(Color(0xFF58A6FF), markdownLinkColor(null, null))
    }

    private fun assertCustomLinks(dark: Boolean) = customColors.forEach { color ->
        assertReadableLinks(companionChatPalette(dark, color, color))
    }

    private fun assertReadableLinks(palette: CompanionChatPalette) {
        val userInk = companionBubbleLinkColor(palette, isUser = true)
        val assistantInk = companionBubbleLinkColor(palette)
        val assistant = companionAssistantBubbleStyle(palette)
        val userMarkdown = markdownLinkColor(null, userInk)
        val assistantMarkdown = markdownLinkColor(assistant.markdown, assistantInk)
        assertEquals(palette.outgoingInk, userMarkdown)
        assertEquals(palette.incomingInk, assistantMarkdown)
        assertEquals(assistantInk, assistant.markdown.link)
        assertReadable(userMarkdown, palette.outgoing)
        assertReadable(assistantMarkdown, assistant.background)
    }

    private fun assertReadable(link: Color, background: Color) {
        assertEquals(1f, link.alpha, 0f)
        val displayed = link.compositeOver(background)
        val ratio = (maxOf(displayed.luminance(), background.luminance()) + 0.05f) /
            (minOf(displayed.luminance(), background.luminance()) + 0.05f)
        assertTrue("链接 $link 在背景 $background 的对比度 $ratio 应不低于 4.5:1", ratio >= 4.5f)
    }

    private val customColors = listOf(
        Color.White, Color.Black, Color(0xFFEAF7D8), Color(0xFF102030),
        Color(0xFF757575), Color(0xFF767676), Color(0xFF777777),
        Color(0xFF8B6CFF), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF)
    )
}
