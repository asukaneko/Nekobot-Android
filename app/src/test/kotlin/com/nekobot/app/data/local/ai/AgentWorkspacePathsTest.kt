package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentWorkspacePathsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun paths() = AgentWorkspacePaths(temporary.newFolder(), temporary.newFolder())

    @Test fun blankPrimaryPathCannotHideSharedAliases() {
        val paths = paths()
        listOf(
            mapOf("path" to "", "filename" to "shared://demo.txt"),
            mapOf("path" to "  ", "filename" to "", "file_path" to "shared://demo.txt"),
            mapOf("filename" to " SHARED://notes/../demo.txt ")
        ).forEach { args ->
            assertEquals("shared://demo.txt", paths.sharedMutationPath("file_write", args))
            assertEquals(paths.resolve("shared://demo.txt"), paths.resolve(args.agentWorkspacePath()))
        }
    }

    @Test fun downloadAndExplicitOrDefaultEpubOutputsAreCovered() {
        val paths = paths()
        assertEquals("shared://demo.txt", paths.sharedMutationPath("download_file",
            mapOf("url" to "https://example.com/demo.txt", "save_path" to "shared://demo.txt")))
        assertEquals("shared://demo.txt", paths.sharedMutationPath("workspace_extract_epub",
            mapOf("path" to "demo.epub", "output_path" to "shared://demo.txt")))
        assertEquals("shared://demo.txt", paths.sharedMutationPath("workspace_extract_epub",
            mapOf("filename" to "shared://demo.epub")))
        assertNull(paths.sharedMutationPath("workspace_extract_epub",
            mapOf("path" to "shared://demo.epub", "output_path" to "demo.txt")))
    }

    @Test fun localTargetsAndReadOnlyToolsDoNotRequestSharedWritePermission() {
        val paths = paths()
        assertNull(paths.sharedMutationPath("file_write", mapOf("path" to "demo.txt", "filename" to "shared://demo.txt")))
        assertNull(paths.sharedMutationPath("file_read", mapOf("path" to "shared://demo.txt")))
        assertNull(paths.sharedMutationPath("download_file", mapOf("url" to "https://example.com/demo.txt")))
    }

    @Test fun canonicalTargetsCannotEscapeEitherWorkspace() {
        val paths = paths()
        assertNull(paths.resolve("../outside.txt"))
        assertNull(paths.resolve("shared://../outside.txt"))
        assertNull(paths.sharedMutationPath("workspace_delete_file", mapOf("path" to "shared://../outside.txt")))
        assertEquals("shared://notes/demo.txt", paths.sharedMutationPath("workspace_delete_file",
            mapOf("file_path" to "shared://notes/sub/../demo.txt")))
        assertNotNull(paths.resolve("/workspace/demo.txt"))
    }
}
