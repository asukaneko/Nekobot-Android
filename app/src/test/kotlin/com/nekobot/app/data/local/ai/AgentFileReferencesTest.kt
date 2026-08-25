package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentFileReferencesTest {

    @Test
    fun appendsFileMarkerWhenToolOnlyReturnsPath() {
        assertEquals(
            "文件已准备好。\n\n[File: exports/report.pdf]",
            appendAgentFileReferences(
                content = "文件已准备好。",
                references = listOf("exports/report.pdf")
            )
        )
    }

    @Test
    fun createsFileMarkerForBlankFinalContentAndDeduplicates() {
        assertEquals(
            "[File: shared://report.pdf]",
            appendAgentFileReferences(
                content = "",
                references = listOf("shared://report.pdf", "shared:\\report.pdf")
            )
        )
    }

    @Test
    fun keepsExistingFileMarker() {
        val content = "已发送\n[文件: report.pdf]"
        assertEquals(content, appendAgentFileReferences(content, listOf("report.pdf")))
    }
}
