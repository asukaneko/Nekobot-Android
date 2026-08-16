package com.nekobot.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun githubAssetsUseTheOfficialDownloadUrlOnly() {
        val source = "https://github.com/asukaneko/Nekobot-Android/releases/download/v0.5.2/Nekobot-v0.5.2.apk"

        assertEquals(listOf(source), UpdateChecker.buildDownloadUrls(source))
    }
}
