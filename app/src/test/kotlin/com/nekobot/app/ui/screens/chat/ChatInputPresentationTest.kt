package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ChatInputLayoutMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputPresentationTest {

    @Test
    fun mergedLayout_hidesEmptyInputWhenPlotSurfaceIsVisible() {
        assertFalse(
            shouldShowChatInput(
                layoutMode = ChatInputLayoutMode.MERGED,
                inputExpanded = false,
                hasPlotSurface = true,
                hasDraft = false
            )
        )
    }

    @Test
    fun mergedLayout_showsInputForExplicitExpansionDraftOrMissingPlotSurface() {
        assertTrue(shouldShowChatInput(ChatInputLayoutMode.MERGED, true, true, false))
        assertTrue(shouldShowChatInput(ChatInputLayoutMode.MERGED, false, true, true))
        assertTrue(shouldShowChatInput(ChatInputLayoutMode.MERGED, false, false, false))
    }

    @Test
    fun separateLayout_alwaysShowsInput() {
        assertTrue(shouldShowChatInput(ChatInputLayoutMode.SEPARATE, false, true, false))
    }
}
