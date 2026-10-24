package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.nekobot.app.data.model.Message
import com.nekobot.app.ui.components.parseInline
import org.junit.Assert.*
import org.junit.Test

class CompanionMessageStyleTest {
    private val reply = Message(role = "assistant", content = "我陪你。")

    @Test fun firstReplyShowsAvatar() = assertTrue(companionShowsAvatar(reply, null, false))
    @Test fun consecutiveRepliesShareAvatar() = assertFalse(companionShowsAvatar(reply, reply, false))
    @Test fun userInterruptStartsNewGroup() = assertTrue(companionShowsAvatar(reply, Message(role = "human"), false))
    @Test fun outgoingHasNoAvatar() = assertFalse(companionShowsAvatar(Message(role = "user"), reply, true))
    @Test fun timeSeparatorStartsNewGroup() = assertTrue(companionShowsAvatar(reply, reply, true))
    @Test fun differentSenderStartsNewGroup() = assertTrue(companionShowsAvatar(reply.copy(sender = "小满"), reply.copy(sender = "晚星"), false))
    @Test fun senderWhitespaceDoesNotBreakGroup() = assertFalse(companionShowsAvatar(reply.copy(sender = " 小满 "), reply.copy(sender = "小满"), false))
    @Test fun senderNameFallbackIsStable() = assertFalse(companionShowsAvatar(reply.copy(sender = " ", name = "小满"), reply.copy(name = "小满"), false))
    @Test fun hiddenSystemMessageBreaksGroup() = assertTrue(companionShowsAvatar(reply, Message(role = "system"), false))
    @Test fun streamingReplyKeepsConsecutiveGroup() = assertFalse(companionShowsAvatar(reply.copy(id = ChatViewModel.STREAMING_ID), reply, false))
    @Test fun shortIntervalHasNoSeparator() = assertFalse(section("21:06", "21:02"))
    @Test fun fiveMinuteIntervalHasSeparator() = assertTrue(section("21:06", "21:01"))
    @Test fun crossedDayHasSeparator() = assertTrue(companionStartsTimeSection(false, false, "2026-10-13", "2026-10-12", "00:01", "23:59"))
    @Test fun unknownTimeHasSeparator() = assertTrue(section(null, "21:01"))
    @Test fun invalidTimeHasSeparator() = assertTrue(section("25:10", "21:01"))
    @Test fun backwardsTimeHasSeparator() = assertTrue(section("21:01", "21:06"))
    @Test fun streamingHasNoNewSeparator() = assertFalse(companionStartsTimeSection(true, true, null, null, null, null))
    @Test fun firstVisibleMessageHasSeparator() = assertTrue(companionStartsTimeSection(true, false, "2026-10-13", "2026-10-13", "21:06", "21:06"))
    @Test fun phoneWidthLeavesAvatarAndMargins() {
        assertEquals(264, companionBubbleMaxWidthDp(360, false))
        assertEquals(296, companionBubbleMaxWidthDp(360, true))
    }
    @Test fun tabletWidthIsCapped() = assertEquals(360, companionBubbleMaxWidthDp(900, false))
    @Test fun narrowWidthDoesNotForceMinimum() {
        assertEquals(84, companionBubbleMaxWidthDp(180, false))
        assertEquals(1, companionBubbleMaxWidthDp(40, false))
    }
    @Test fun uploadedAvatarHasPriority() = assertEquals("file:///avatar.jpg", companionPhotoPath("file:///portrait.jpg", "file:///avatar.jpg"))
    @Test fun legacyIconFallsBackToPhoto() = assertEquals("file:///avatar.jpg", companionPhotoPath("fas fa-cat", "file:///avatar.jpg"))
    @Test fun missingPhotoFallsBackToInitial() = assertNull(companionPhotoPath(" ", "fasfa-cat"))
    @Test fun avatarPhotoWorksWithoutPortrait() = assertEquals("content://photo/1", companionPhotoPath(null, "content://photo/1"))

    @Test fun lightAssistantDefaultsToWhite() {
        val palette = companionChatPalette(false)
        val style = companionAssistantBubbleStyle(palette)
        assertEquals(Color(0xFFD5EBD2), palette.outgoing)
        assertEquals(Color(0xFF172A20), palette.outgoingInk)
        assertEquals(Color.White, style.background)
        assertEquals(Color.Black, style.content)
        assertEquals(1f, style.background.alpha, 0f)
    }

    @Test fun darkAssistantDefaultsToWhite() {
        val palette = companionChatPalette(true)
        val style = companionAssistantBubbleStyle(palette)
        assertEquals(Color(0xFF355B45), palette.outgoing)
        assertEquals(Color(0xFFF0F6F2), palette.outgoingInk)
        assertEquals(Color.White, style.background)
        assertEquals(Color.Black, style.content)
        assertEquals(1f, style.background.alpha, 0f)
    }

    @Test fun lightCustomBubbleColorsRemainIndependentAndReadable() = assertCustomColors(false)
    @Test fun darkCustomBubbleColorsRemainIndependentAndReadable() = assertCustomColors(true)

    @Test fun changingUserColorKeepsAssistantDefault() {
        val dark = companionChatPalette(false, Color(0xFF102030))
        val light = companionChatPalette(false, Color(0xFFEAF7D8))
        assertNotEquals(dark.outgoing, light.outgoing)
        assertNotEquals(dark.outgoingInk, light.outgoingInk)
        assertEquals(Color.White, companionAssistantBubbleStyle(dark).background)
        assertEquals(Color.White, companionAssistantBubbleStyle(light).background)
    }

    @Test fun changingAssistantColorKeepsUserColor() {
        val user = Color(0xFFEAF7D8)
        val first = companionChatPalette(false, user, Color(0xFF102030))
        val second = companionChatPalette(false, user, Color(0xFF8B6CFF))
        assertEquals(user, first.outgoing)
        assertEquals(user, second.outgoing)
        assertEquals(Color(0xFF102030), companionAssistantBubbleStyle(first).background)
        assertEquals(Color(0xFF8B6CFF), companionAssistantBubbleStyle(second).background)
    }

    @Test fun clearingCustomColorRestoresEachModesOriginalUserColor() {
        listOf(false, true).forEach { dark ->
            assertEquals(companionChatPalette(dark), companionChatPalette(dark, null))
        }
    }

    @Test fun customColorDoesNotChangeSurroundingPaletteOrDetailSurfaces() {
        listOf(false, true).forEach { dark ->
            val original = companionChatPalette(dark)
            val custom = companionChatPalette(dark, Color(0xFF8B6CFF))
            assertEquals(original, custom.copy(outgoing = original.outgoing, outgoingInk = original.outgoingInk))
            val originalMarkdown = companionAssistantBubbleStyle(original).markdown
            val customMarkdown = companionAssistantBubbleStyle(custom).markdown
            assertEquals(originalMarkdown.surface, customMarkdown.surface)
            assertEquals(originalMarkdown.onSurface, customMarkdown.onSurface)
            assertEquals(originalMarkdown.surfaceLink, customMarkdown.surfaceLink)
        }
    }

    @Test fun lightAssistantBodyAndAsidePassContrastOnUserBackground() = assertBubbleContrast(false)
    @Test fun darkAssistantBodyAndAsidePassContrastOnUserBackground() = assertBubbleContrast(true)
    @Test fun lightAssistantLinksPassContrastOnUserBackground() = assertLinkContrast(false)
    @Test fun darkAssistantLinksPassContrastOnUserBackground() = assertLinkContrast(true)
    @Test fun lightMonologueAndCodeTextAndControlsPassContrast() = assertSurfaceContrast(false)
    @Test fun darkMonologueAndCodeTextAndControlsPassContrast() = assertSurfaceContrast(true)

    @Test fun monologueLinksRemainReadableOnBothDetailSurfaces() {
        listOf(false, true).forEach { dark ->
            val colors = companionAssistantBubbleStyle(companionChatPalette(dark)).markdown
            assertReadable(colors.surfaceLink, colors.surface)
        }
    }

    @Test fun assistantMarkdownLinkUsesBubbleInkAndKeepsClickTargetAndUnderline() {
        val colors = companionAssistantBubbleStyle(companionChatPalette(false)).markdown
        val rendered = parseInline("[点这里](https://example.com)", Color.Black, TextStyle.Default,
            linkColor = colors.link)
        assertEquals("点这里", rendered.text)
        assertEquals(colors.link, rendered.spanStyles.single().item.color)
        assertEquals(TextDecoration.Underline, rendered.spanStyles.single().item.textDecoration)
        assertEquals("https://example.com", rendered.getStringAnnotations("URL", 0, rendered.length).single().item)
    }

    @Test fun assistantAsideKeepsOriginalTextWithOpaqueReadableInk() {
        val style = companionAssistantBubbleStyle(companionChatPalette(true))
        val rendered = parseInline("（轻声说）", style.content, TextStyle.Default, styleParentheses = true,
            parenthesesColor = style.markdown.parentheses)
        assertEquals("（轻声说）", rendered.text)
        val ink = rendered.spanStyles.single().item.color
        assertEquals(1f, ink.alpha, 0f)
        assertReadable(ink, style.background)
    }

    @Test fun monologueInlineLinksUseSurfaceColorAndKeepOriginalContent() {
        listOf(false, true).forEach { dark ->
            val colors = companionAssistantBubbleStyle(companionChatPalette(dark)).markdown
            val rendered = parseInline("心想：[去看看](https://example.com)", colors.onSurface,
                TextStyle.Default, linkColor = colors.surfaceLink)
            assertEquals("心想：去看看", rendered.text)
            assertReadable(rendered.spanStyles.single().item.color, colors.surface)
        }
    }

    @Test fun markdownWithoutAssistantOverrideKeepsExistingColors() {
        val base = Color(0xFF202927)
        val rendered = parseInline("（轻声说）[链接](https://example.com)", base, TextStyle.Default,
            styleParentheses = true)
        assertEquals(base.copy(alpha = 0.62f), rendered.spanStyles.first().item.color)
        assertEquals(Color(0xFF58A6FF), rendered.spanStyles.last().item.color)
    }

    private fun assertBubbleContrast(dark: Boolean) {
        val style = companionAssistantBubbleStyle(companionChatPalette(dark))
        assertReadable(style.content, style.background)
        assertReadable(style.markdown.parentheses, style.background)
        // 行内代码仍沿用原来的灰色半透明底色。
        assertReadable(style.content, Color.Gray.copy(alpha = 0.15f).compositeOver(style.background))
    }

    private fun assertLinkContrast(dark: Boolean) {
        val style = companionAssistantBubbleStyle(companionChatPalette(dark))
        assertReadable(style.markdown.link, style.background)
    }

    private fun assertSurfaceContrast(dark: Boolean) {
        val colors = companionAssistantBubbleStyle(companionChatPalette(dark)).markdown
        assertEquals(1f, colors.surface.alpha, 0f)
        assertEquals(1f, colors.onSurface.alpha, 0f)
        assertReadable(colors.onSurface, colors.surface)
    }

    private fun assertReadable(ink: Color, background: Color) {
        val displayed = ink.compositeOver(background)
        val ratio = (maxOf(displayed.luminance(), background.luminance()) + 0.05f) /
            (minOf(displayed.luminance(), background.luminance()) + 0.05f)
        assertTrue("文字对比度 $ratio 应不低于 4.5:1", ratio >= 4.5f)
    }

    private fun assertCustomColors(dark: Boolean) {
        listOf(Color(0xFFEAF7D8), Color(0xFF102030), Color(0xFF777777), Color(0xFF8B6CFF)).forEach { color ->
            val palette = companionChatPalette(dark, color)
            val style = companionAssistantBubbleStyle(palette)
            assertEquals(color, palette.outgoing)
            assertEquals(Color.White, style.background)
            assertEquals(Color.Black, style.content)
            assertReadable(style.content, style.background)
            assertReadable(style.markdown.link, style.background)
            assertReadable(style.markdown.parentheses, style.background)
            assertReadable(style.content, Color.Gray.copy(alpha = 0.15f).compositeOver(style.background))
            assertReadable(style.markdown.onSurface, style.markdown.surface)
            assertReadable(style.markdown.surfaceLink, style.markdown.surface)
            val assistant = companionAssistantBubbleStyle(companionChatPalette(dark, null, color))
            assertEquals(color, assistant.background)
            assertReadable(assistant.content, assistant.background)
            assertReadable(assistant.markdown.link, assistant.background)
        }
    }

    private fun section(time: String?, previous: String?) =
        companionStartsTimeSection(false, false, "2026-10-13", "2026-10-13", time, previous)
}
