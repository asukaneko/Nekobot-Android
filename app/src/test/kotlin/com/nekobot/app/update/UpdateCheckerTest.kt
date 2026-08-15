package com.nekobot.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun githubAssetsUseGhproxyBeforeDirectDownload() {
        val source = "https://github.com/asukaneko/Nekobot-Android/releases/download/v0.5.2/Nekobot-v0.5.2.apk"

        assertEquals(
            listOf(
                "https://ghproxy.com/$source",
                source
            ),
            UpdateChecker.buildDownloadUrls(source)
        )
    }

    @Test
    fun githubAssetsCanUseAManuallySelectedSource() {
        val source = "https://github.com/asukaneko/Nekobot-Android/releases/download/v0.5.2/Nekobot-v0.5.2.apk"

        assertEquals(
            listOf("https://ghproxy.com/$source"),
            UpdateChecker.buildDownloadUrls(source, UpdateChecker.DownloadSource.GHPROXY)
        )
        assertEquals(
            listOf(source),
            UpdateChecker.buildDownloadUrls(source, UpdateChecker.DownloadSource.GITHUB_DIRECT)
        )
    }

    @Test
    fun nonGithubAssetsKeepTheirOriginalDownloadUrl() {
        val source = "https://downloads.example.com/Nekobot.apk"

        assertEquals(listOf(source), UpdateChecker.buildDownloadUrls(source))
    }
}
