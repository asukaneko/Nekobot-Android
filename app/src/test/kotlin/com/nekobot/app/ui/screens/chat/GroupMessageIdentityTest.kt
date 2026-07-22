package com.nekobot.app.ui.screens.chat

import com.google.gson.Gson
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupMessageIdentityTest {
    @Test
    fun `local group sender resolves character name and portrait`() {
        val identity = resolveGroupMessageIdentity(
            message = Message(role = "assistant", sender = "猫猫"),
            characters = listOf(CharacterPreset(id = "cat", name = "猫猫", portrait = "cat.png"))
        )

        assertEquals("猫猫", identity.name)
        assertEquals("cat.png", identity.portraitUrl)
    }

    @Test
    fun `remote sender aliases and portrait take precedence`() {
        val message = Gson().fromJson(
            """{"role":"assistant","sender_name":"兔兔","sender_portrait":"rabbit.png"}""",
            Message::class.java
        )
        val identity = resolveGroupMessageIdentity(message, emptyList())

        assertEquals("兔兔", identity.name)
        assertEquals("rabbit.png", identity.portraitUrl)
    }

    @Test
    fun `generic assistant label is not shown as character name`() {
        val identity = resolveGroupMessageIdentity(
            message = Message(role = "assistant", sender = "assistant"),
            characters = emptyList()
        )

        assertNull(identity.name)
    }
}
