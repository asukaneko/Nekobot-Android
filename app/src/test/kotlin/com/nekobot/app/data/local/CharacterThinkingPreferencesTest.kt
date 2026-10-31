package com.nekobot.app.data.local

import android.content.SharedPreferences
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.local.ai.characterThinkingPrompt
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingPreferencesTest {
    @Test
    fun defaultsAreEnabledAndEmptyWithoutWritingExistingPreferences() {
        val saved = mutableMapOf<String, Any>()
        assertEquals(CharacterThinkingSettings(), CharacterThinkingPreferences(preferences(saved)).read())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun disabledSwitchAndCustomPromptSurviveReaderRecreation() {
        val saved = mutableMapOf<String, Any>()
        CharacterThinkingPreferences(preferences(saved)).apply {
            enabled = false
            customPrompt = "以我的身份想：今天很开心。"
        }
        val restored = CharacterThinkingPreferences(preferences(saved)).read()
        assertFalse(restored.enabled)
        assertEquals("以我的身份想：今天很开心。", restored.customPrompt)
    }

    @Test
    fun clearingPromptAndReenablingDoNotChangeUnrelatedPreferences() {
        val saved = mutableMapOf<String, Any>("reasoning_effort_character" to "high", "show_reasoning" to false)
        val store = CharacterThinkingPreferences(preferences(saved))
        store.enabled = false
        store.customPrompt = "旧文案"
        store.customPrompt = ""
        store.enabled = true
        assertEquals(CharacterThinkingSettings(), CharacterThinkingPreferences(preferences(saved)).read())
        assertEquals("high", saved["reasoning_effort_character"])
        assertEquals(false, saved["show_reasoning"])
    }

    @Test
    fun multilinePromptIsSavedVerbatimAndUsedAfterReopeningPreferences() {
        val saved = mutableMapOf<String, Any>()
        val prompt = "  我是{{char}}。\n想起{{user}}时，先想自己的心情。\n\t保留工具名 reasoning_content。  "
        CharacterThinkingPreferences(preferences(saved)).customPrompt = prompt

        val restored = CharacterThinkingPreferences(preferences(saved)).read()
        assertTrue(restored.enabled)
        assertEquals(prompt, restored.customPrompt)
        assertEquals(prompt.replace("{{char}}", "晚星").replace("{{user}}", "小星"),
            characterThinkingPrompt(restored, "晚星", "小星"))
    }

    @Test
    fun savingEmptyPromptRestoresExistingDefaultWithoutChangingDisabledSwitch() {
        val saved = mutableMapOf<String, Any>()
        val store = CharacterThinkingPreferences(preferences(saved))
        store.enabled = false
        store.customPrompt = "旧自定义提示词"
        store.customPrompt = ""

        val restored = CharacterThinkingPreferences(preferences(saved)).read()
        assertFalse(restored.enabled)
        assertEquals("", restored.customPrompt)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "晚星").replace("{{user}}", "小星"),
            characterThinkingPrompt(restored, "晚星", "小星"))
    }

    @Test
    fun whitespaceOnlyPromptUsesDefaultWithoutRewritingSavedTextOrOtherSettings() {
        val saved = mutableMapOf<String, Any>("reasoning_effort_character" to "none", "show_reasoning" to true)
        val blankPrompt = " \n\t "
        CharacterThinkingPreferences(preferences(saved)).customPrompt = blankPrompt

        val restored = CharacterThinkingPreferences(preferences(saved)).read()
        assertEquals(blankPrompt, restored.customPrompt)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "晚星").replace("{{user}}", "小星"),
            characterThinkingPrompt(restored, "晚星", "小星"))
        assertTrue(restored.enabled)
        assertEquals("none", saved["reasoning_effort_character"])
        assertEquals(true, saved["show_reasoning"])
    }

    @Test
    fun roleGroupAndCompanionReadersShareEverySwitchAndPromptUpdate() {
        val saved = mutableMapOf<String, Any>()
        // 三个入口分别持有偏好读取器，但使用同一份全局存储。
        val entries = List(3) { CharacterThinkingPreferences(preferences(saved)) }
        entries.forEachIndexed { index, writer ->
            val enabled = index % 2 == 0
            val prompt = "  入口$index：我是{{char}}。\n想起{{user}}时，用中文思考。  "
            writer.enabled = enabled
            writer.customPrompt = prompt

            entries.forEach { reader ->
                assertEquals(CharacterThinkingSettings(enabled, prompt), reader.read())
            }
            // 其他入口重进也应读取刚写入的值。
            assertEquals(CharacterThinkingSettings(enabled, prompt),
                CharacterThinkingPreferences(preferences(saved)).read())
        }
        assertEquals(2, saved.size)
    }

    @Test
    fun savingOrRestoringPromptFromAnotherEntryKeepsLatestSwitchAndReasoningSettings() {
        val saved = mutableMapOf<String, Any>(
            "reasoning_effort_character" to "none",
            "reasoning_effort_agent" to "high",
            "companion:session-a:reasoning" to "medium"
        )
        val roleEntry = CharacterThinkingPreferences(preferences(saved))
        val groupEntry = CharacterThinkingPreferences(preferences(saved))
        val companionEntry = CharacterThinkingPreferences(preferences(saved))
        assertTrue(groupEntry.read().enabled)
        assertTrue(companionEntry.read().enabled)

        roleEntry.enabled = false
        groupEntry.customPrompt = "我在群聊里的心里话。\n保留换行。"
        assertEquals(CharacterThinkingSettings(false, groupEntry.customPrompt), roleEntry.read())
        companionEntry.customPrompt = ""

        listOf(roleEntry, groupEntry, companionEntry).forEach { reader ->
            assertEquals(CharacterThinkingSettings(false, ""), reader.read())
            assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "晚星").replace("{{user}}", "小星"),
                characterThinkingPrompt(reader.read(), "晚星", "小星"))
        }
        assertEquals("none", saved["reasoning_effort_character"])
        assertEquals("high", saved["reasoning_effort_agent"])
        assertEquals("medium", saved["companion:session-a:reasoning"])
        assertEquals(5, saved.size)
    }

    /** JVM 测试只模拟所需 SharedPreferences API，写入必须经过 apply/commit 才能读回。 */
    private fun preferences(saved: MutableMap<String, Any>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getBoolean", "getString" -> saved[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    when (editMethod.name) {
                        "putBoolean", "putString" -> {
                            pending[editArgs!![0] as String] = requireNotNull(editArgs[1])
                            editor
                        }
                        "apply", "commit" -> { saved.putAll(pending); true }
                        else -> error("未模拟的编辑方法：${editMethod.name}")
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error("未模拟的偏好方法：${method.name}")
        }
    } as SharedPreferences
}
