package com.nekobot.app.data.model

import org.junit.Assert.*
import org.junit.Test

class CharacterAvatarTest {
    @Test fun avatarWinsAndLargePortraitIsUnchanged() {
        val session = Session(characterAvatar = "file:///avatar.png", portrait = "file:///portrait.png")
        assertEquals("file:///avatar.png", session.avatarUrl)
        assertEquals("file:///portrait.png", session.portraitUrl)
    }
    @Test fun emptyOrIconAvatarFallsBackToPortrait() {
        listOf(null, "", " ", "fas fa-cat", "fasfa-cat", "fa-cat", "far fa-user", "fa-solid fa-user", "FAB fa-github", "not an image")
            .forEach { assertEquals("/portrait.jpg", characterAvatarPath(it, "/portrait.jpg")) }
    }
    @Test fun noValidImageReturnsNull() {
        assertNull(characterAvatarPath("fa-solid fa-user", "fasfa-cat"))
        assertNull(characterAvatarPath("", null))
    }
    @Test fun supportedPhotoPathsAndWhitespaceArePreserved() {
        listOf("file:/avatar.png", "file:///avatar.png", "content://photos/7", "/portraits/7.png", "portraits/7.webp", "https://example.invalid/image?id=7")
            .forEach { assertEquals(it, characterAvatarPath(" $it ", "portrait.png")) }
    }
}
