package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNamingStyleTest {

    @Test
    fun agentSessionsUseAgentStyle() {
        listOf("agent", "Agent", "AGENT").forEach { mode ->
            assertEquals(
                "$mode should map to agent style",
                SessionNamingStyle.AGENT,
                SessionNamingStyle.of(mode)
            )
        }
    }

    @Test
    fun roleplayAndUnknownModesUseRoleplayStyle() {
        listOf("character", "group", "", null).forEach { mode ->
            assertEquals(
                "$mode should map to roleplay style",
                SessionNamingStyle.ROLEPLAY,
                SessionNamingStyle.of(mode)
            )
        }
    }

    // ==================== 题材判定 ====================

    @Test
    fun classicalTagsAreDetected() {
        assertEquals(
            RoleplayTheme.CLASSICAL,
            RoleplayTheme.classify(tags = "[\"古风\",\"仙侠\"]")
        )
        assertEquals(
            RoleplayTheme.CLASSICAL,
            RoleplayTheme.classify(tags = "武侠,江湖")
        )
    }

    @Test
    fun modernTagsAreDetected() {
        assertEquals(
            RoleplayTheme.MODERN,
            RoleplayTheme.classify(tags = "[\"二次元\",\"校园\"]")
        )
        assertEquals(
            RoleplayTheme.MODERN,
            RoleplayTheme.classify(tags = "都市,恋爱,日常")
        )
    }

    @Test
    fun descriptionIsUsedWhenTagsAreMissingOrNeutral() {
        assertEquals(
            RoleplayTheme.CLASSICAL,
            RoleplayTheme.classify(
                tags = "[\"女性向\"]",
                "她是青云宗最小的弟子，一心问道修仙。"
            )
        )
        assertEquals(
            RoleplayTheme.MODERN,
            RoleplayTheme.classify(
                tags = null,
                "便利店夜班店员，喜欢在深夜听城市的声音。"
            )
        )
    }

    @Test
    fun tagsOutweighLongDescriptions() {
        // 标签是门类的最可靠信号：描述里出现再多"都市"也不该盖过"玄幻"标签。
        assertEquals(
            RoleplayTheme.CLASSICAL,
            RoleplayTheme.classify(
                tags = "[\"玄幻\"]",
                "故事发生在现代都市，有学校、公司、偶像与咖啡店。"
            )
        )
    }

    @Test
    fun conflictingOrAbsentSignalsFallBackToUnknown() {
        assertEquals(RoleplayTheme.UNKNOWN, RoleplayTheme.classify(tags = "[\"治愈\"]"))
        assertEquals(RoleplayTheme.UNKNOWN, RoleplayTheme.classify(tags = null, "无名氏"))
        assertEquals(
            RoleplayTheme.UNKNOWN,
            RoleplayTheme.classify(tags = "[\"古风\",\"二次元\"]")
        )
    }

    // ==================== 提示词 ====================

    @Test
    fun classicalRoleplayPromptAsksForPoeticTitle() {
        val prompt = buildSessionNamingPrompt(
            style = SessionNamingStyle.ROLEPLAY,
            theme = RoleplayTheme.CLASSICAL,
            roleContext = "当前角色是'林晚'。",
            isUpdate = false,
            languageDirective = "【输出语言】简体中文。"
        )

        assertTrue(prompt.contains("当前角色是'林晚'。"))
        assertTrue(prompt.contains(SessionNamingStyle.ROLEPLAY.preferredLength))
        assertTrue(prompt.contains("诗意"))
        assertTrue(prompt.contains("意象"))
        assertTrue(prompt.endsWith("【输出语言】简体中文。"))
    }

    @Test
    fun modernRoleplayPromptAsksForFreshLifestyleTitle() {
        val prompt = buildSessionNamingPrompt(
            style = SessionNamingStyle.ROLEPLAY,
            theme = RoleplayTheme.MODERN,
            roleContext = "当前角色是'小满'。",
            isUpdate = false,
            languageDirective = "【输出语言】简体中文。"
        )

        assertTrue(prompt.contains("清新灵动"))
        assertTrue(prompt.contains("生活气息"))
        assertTrue(prompt.contains("不要堆砌古风辞藻"))
        // 现代题材不应再要求写成诗句。
        assertFalse(prompt.contains("像诗集篇目或章回小说的回目"))
    }

    @Test
    fun unknownThemeLetsTheModelPickTheRegister() {
        val prompt = buildSessionNamingPrompt(
            style = SessionNamingStyle.ROLEPLAY,
            theme = RoleplayTheme.UNKNOWN,
            roleContext = "",
            isUpdate = false,
            languageDirective = "lang"
        )

        assertTrue(prompt.contains("古风玄幻可典雅如诗"))
        assertTrue(prompt.contains("二次元都市则清新灵动"))
    }

    @Test
    fun agentPromptAsksForHighLevelSummaryWithoutDetails() {
        val prompt = buildSessionNamingPrompt(
            style = SessionNamingStyle.AGENT,
            theme = RoleplayTheme.UNKNOWN,
            roleContext = "",
            isUpdate = false,
            languageDirective = "【输出语言】简体中文。"
        )

        assertTrue(prompt.contains(SessionNamingStyle.AGENT.preferredLength))
        assertTrue(prompt.contains("整体概括"))
        assertTrue(prompt.contains("不要描述细节"))
        // Agent 标题是任务索引，不应被要求写成诗句。
        assertFalse(prompt.contains("诗意"))
        assertTrue(prompt.endsWith("【输出语言】简体中文。"))
    }

    @Test
    fun updateHintsDifferPerStyle() {
        val roleplay = buildSessionNamingPrompt(
            style = SessionNamingStyle.ROLEPLAY,
            theme = RoleplayTheme.CLASSICAL,
            roleContext = "",
            isUpdate = true,
            languageDirective = "lang"
        )
        val agent = buildSessionNamingPrompt(
            style = SessionNamingStyle.AGENT,
            theme = RoleplayTheme.UNKNOWN,
            roleContext = "",
            isUpdate = true,
            languageDirective = "lang"
        )

        assertTrue(roleplay.contains("场景与情绪"))
        assertTrue(agent.contains("任务目标"))
    }
}
