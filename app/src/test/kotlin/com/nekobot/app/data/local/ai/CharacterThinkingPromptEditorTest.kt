package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingPromptEditorTest {
    @Test
    fun emptyAndWhitespacePreferencesOpenWithEditableApprovedDefault() {
        for (saved in listOf("", " \n\t ")) {
            assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT,
                characterThinkingPromptForEditing(CharacterThinkingSettings(customPrompt = saved)))
        }
    }

    @Test
    fun unchangedDefaultSavesAsFollowDefaultAndStillProducesTheSamePrompt() {
        val stored = characterThinkingPromptToSave(DEFAULT_CHARACTER_THINKING_PROMPT)
        assertEquals("", stored)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT.replace("{{char}}", "林小满").replace("{{user}}", "小星"),
            characterThinkingPrompt(CharacterThinkingSettings(customPrompt = stored), "林小满", "小星"))
    }

    @Test
    fun editingDefaultKeepsTheEditAndNewlinesInActualSystemText() {
        val edited = DEFAULT_CHARACTER_THINKING_PROMPT + "\n  我还惦记着{{user}}。  "
        val stored = characterThinkingPromptToSave(edited)
        assertEquals(edited, stored)
        assertEquals(edited, characterThinkingPromptForEditing(CharacterThinkingSettings(customPrompt = stored)))
        assertTrue(characterThinkingPrompt(CharacterThinkingSettings(customPrompt = stored), "林小满", "小星")
            .contains("\n  我还惦记着小星。  "))
    }

    @Test
    fun clearingAndRestoreDefaultBothUseApprovedTextWithoutChangingSwitch() {
        for (draft in listOf("", DEFAULT_CHARACTER_THINKING_PROMPT)) {
            val settings = CharacterThinkingSettings(false, characterThinkingPromptToSave(draft))
            assertFalse(settings.enabled)
            assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT, characterThinkingPromptForEditing(settings))
        }
    }
}
