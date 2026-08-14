package com.nekobot.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun githubAssetsPreferMirrorsBeforeDirectDownload() {
        val source = "https://github.com/asukaneko/Nekobot-Android/releases/download/v0.5.2/Nekobot-v0.5.2.apk"

        assertEquals(
            listOf(
                "https://gh-proxy.com/$source",
                "https://ghproxy.com/$source",
                source
            ),
            UpdateChecker.buildDownloadUrls(source)
        )
    }

    @Test
    fun nonGithubAssetsKeepTheirOriginalDownloadUrl() {
        val source = "https://downloads.example.com/Nekobot.apk"

        assertEquals(listOf(source), UpdateChecker.buildDownloadUrls(source))
    }
}
