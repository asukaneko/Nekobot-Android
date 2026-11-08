package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页轻聊的绑定策略、草稿结算与剧情选项解析。 */
class HomeQuickChatPolicyTest {

    @Test
    fun companionSessionAlwaysWins() {
        assertEquals(
            HomeQuickChatPolicy.Binding.COMPANION,
            HomeQuickChatPolicy.binding(
                preferExisting = false,
                hasCompanionSession = true,
                hasExistingSession = true
            )
        )
        assertEquals(
            HomeQuickChatPolicy.Binding.COMPANION,
            HomeQuickChatPolicy.binding(
                preferExisting = true,
                hasCompanionSession = true,
                hasExistingSession = false
            )
        )
    }

    @Test
    fun preferExistingBindsLatestSession() {
        assertEquals(
            HomeQuickChatPolicy.Binding.EXISTING,
            HomeQuickChatPolicy.binding(
                preferExisting = true,
                hasCompanionSession = false,
                hasExistingSession = true
            )
        )
    }

    @Test
    fun newSceneCreatesSession() {
        assertEquals(
            HomeQuickChatPolicy.Binding.NEW,
            HomeQuickChatPolicy.binding(
                preferExisting = false,
                hasCompanionSession = false,
                hasExistingSession = true
            )
        )
        assertEquals(
            HomeQuickChatPolicy.Binding.NEW,
            HomeQuickChatPolicy.binding(
                preferExisting = true,
                hasCompanionSession = false,
                hasExistingSession = false
            )
        )
    }

    @Test
    fun draftIsDiscardedOnlyWhenNobodySpoke() {
        assertTrue(HomeQuickChatPolicy.shouldDiscardDraft(0))
        assertFalse(HomeQuickChatPolicy.shouldDiscardDraft(1))
        assertFalse(HomeQuickChatPolicy.shouldDiscardDraft(12))
    }

    @Test
    fun continueWindowIsTwentyFourHours() {
        val now = 1_000_000_000L
        val hour = 60L * 60L * 1000L
        assertTrue(HomeQuickChatPolicy.preferExistingByDefault(now - hour, now))
        assertTrue(HomeQuickChatPolicy.preferExistingByDefault(now - 23 * hour, now))
        assertFalse(HomeQuickChatPolicy.preferExistingByDefault(now - 25 * hour, now))
        assertFalse(HomeQuickChatPolicy.preferExistingByDefault(null, now))
        assertFalse(HomeQuickChatPolicy.preferExistingByDefault(0L, now))
    }

    @Test
    fun draftCodecRoundTrip() {
        val raw = HomeQuickChatDraftCodec.encode("character-demo", "session-demo", 1234L)
        assertEquals("character-demo", HomeQuickChatDraftCodec.characterId(raw))
        assertEquals("session-demo", HomeQuickChatDraftCodec.sessionId(raw))
        assertEquals(1234L, HomeQuickChatDraftCodec.createdAt(raw))
        assertTrue(HomeQuickChatDraftCodec.matches(raw, "session-demo"))
        assertFalse(HomeQuickChatDraftCodec.matches(raw, "session-other"))
        assertFalse(HomeQuickChatDraftCodec.matches("", "session-demo"))
    }

    @Test
    fun quickChatBubblesSplitOnSeparator() {
        assertEquals(
            listOf("第一段", "第二段", "第三段"),
            splitHomeQuickChatBubbles("第一段<||>第二段<||>第三段")
        )
        // Markdown 原样保留，交给渲染层解析
        assertEquals(
            listOf("- 列表项", "**加粗** 与 `code`"),
            splitHomeQuickChatBubbles("- 列表项 <||> **加粗** 与 `code`")
        )
        // 空段丢弃，首尾空白裁剪
        assertEquals(listOf("只有一段"), splitHomeQuickChatBubbles("  <||>只有一段<||>  "))
        assertTrue(splitHomeQuickChatBubbles(null).isEmpty())
        assertTrue(splitHomeQuickChatBubbles("   ").isEmpty())
        assertTrue(splitHomeQuickChatBubbles("<||>").isEmpty())
    }

    @Test
    fun plotChoicesParseObjectAndArray() {
        val objectJson = """{"choices":[{"id":"c1","text":"继续往前走"},{"text":"留在原地"}]}"""
        val fromObject = parseHomePlotChoices(objectJson)
        assertEquals(2, fromObject.size)
        assertEquals("c1", fromObject[0].id)
        assertEquals("继续往前走", fromObject[0].text)
        assertTrue(fromObject[1].id.isNotBlank())

        val arrayJson = """[{"title":"推开那扇门"}]"""
        val fromArray = parseHomePlotChoices(arrayJson)
        assertEquals(1, fromArray.size)
        assertEquals("推开那扇门", fromArray[0].text)

        assertTrue(parseHomePlotChoices(null).isEmpty())
        assertTrue(parseHomePlotChoices("not-json").isEmpty())
        assertTrue(parseHomePlotChoices("""{"choices":[]}""").isEmpty())
    }
}
