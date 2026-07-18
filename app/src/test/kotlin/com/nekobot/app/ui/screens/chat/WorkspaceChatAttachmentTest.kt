package com.nekobot.app.ui.screens.chat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceChatAttachmentTest {

    @Test
    fun remoteUpload_usesBackendFilenameAndServerPath() {
        val result = JsonParser.parseString(
            """
            {
              "success": true,
              "filename": "image_1.png",
              "path": "D:/server/workspace/image_1.png",
              "size": 1024,
              "mime_type": "image/png"
            }
            """.trimIndent()
        )

        val attachment = buildWorkspaceChatAttachment(
            uploadResult = result,
            sessionId = "session-1",
            originalName = "image.png",
            fallbackMime = "application/octet-stream"
        )

        assertEquals("image_1.png", attachment["name"])
        assertEquals("image/png", attachment["type"])
        assertEquals("D:/server/workspace/image_1.png", attachment["path"])
        assertEquals("web", attachment["source"])
        assertEquals(1024L, attachment["size"])
    }

    @Test
    fun remoteUpload_withoutPath_usesWorkspaceRouteForBackendResolver() {
        val result = JsonParser.parseString(
            """{"success":true,"filename":"角色 图.png","mime_type":"image/png"}"""
        )

        val attachment = buildWorkspaceChatAttachment(
            uploadResult = result,
            sessionId = "session-2",
            originalName = "fallback.png",
            fallbackMime = "image/png"
        )

        assertEquals(
            "/api/sessions/session-2/workspace/files/角色 图.png",
            attachment["path"]
        )
    }

    @Test
    fun workspaceFileNameEncoding_usesPercent20ForPathSpaces() {
        assertEquals(
            "%E8%A7%92%E8%89%B2%20%E5%9B%BE.png",
            encodeWorkspaceFileName("角色 图.png")
        )
    }
}
