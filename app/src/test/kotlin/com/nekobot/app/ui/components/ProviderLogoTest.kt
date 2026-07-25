package com.nekobot.app.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogoTest {
    @Test
    fun `opencode accounts use their official provider icons`() {
        assertTrue(
            providerLogoAsset("opencode-zen", null, null).endsWith("/opencode.svg")
        )
        assertTrue(
            providerLogoAsset("opencode-go", null, null).endsWith("/opencode-go.svg")
        )
    }
}
