package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorkspaceAndPdfPreviewTest {
    @Test
    fun identifiesPdfFilesForExternalViewer() {
        assertTrue(isPdfWorkspaceFile("downloads/JM123.PDF"))
        assertTrue(isPdfWorkspaceFile("no-extension", "application/pdf"))
        assertTrue(!isPdfWorkspaceFile("notes.txt", "text/plain"))
    }

    @Test
    fun normalizesWorkspacePathsAndFindsParent() {
        assertEquals(
            "downloads/books",
            normalizeWorkspaceBrowserPath("/downloads/./temp/../books/")
        )
        assertEquals(
            "downloads",
            parentWorkspaceBrowserPath("downloads/books")
        )
        assertEquals("", parentWorkspaceBrowserPath("downloads"))
    }

    @Test
    fun pdfRenderSizeFitsScreenAndKeepsAspectRatio() {
        val size = calculatePdfRenderSize(
            pageWidth = 595,
            pageHeight = 842,
            targetWidth = 800,
            maxPixels = 2_000_000
        )

        assertEquals(800, size.width)
        assertTrue(size.width * size.height <= 2_000_000)
        assertTrue(abs(size.width.toDouble() / size.height - 595.0 / 842.0) < 0.002)
    }

    @Test
    fun pdfRenderSizeCapsVeryTallPagesByPixelBudget() {
        val size = calculatePdfRenderSize(
            pageWidth = 1_000,
            pageHeight = 100_000,
            targetWidth = 800,
            maxPixels = 2_000_000
        )

        assertTrue(size.width < 800)
        assertTrue(size.width * size.height <= 2_000_000)
        assertTrue(abs(size.width.toDouble() / size.height - 0.01) < 0.001)
    }
}
