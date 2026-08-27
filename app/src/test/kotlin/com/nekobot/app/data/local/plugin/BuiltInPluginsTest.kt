package com.nekobot.app.data.local.plugin

import com.nekobot.app.data.local.LocalCommandAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInPluginsTest {

    @Test
    fun builtInPluginsRouteExistingCommandsToNativeHandlers() {
        val jm = BuiltInPlugins.findDefaultCommand("/jm")
        val novel = BuiltInPlugins.findDefaultCommand("/fb")
        val login = BuiltInPlugins.findDefaultCommand("/wenku_login")

        assertEquals(LocalCommandAction.JM_DOWNLOAD, jm?.builtInAction)
        assertEquals(LocalCommandAction.NOVEL_SEARCH, novel?.builtInAction)
        assertEquals(LocalCommandAction.WENKU8_LOGIN, login?.builtInAction)
    }

    @Test
    fun disabledBuiltInPluginDoesNotRegisterCommandsButStillReservesNames() {
        val plugins = BuiltInPlugins.installed { id -> id != BuiltInPlugins.JM_ID }
        val activeCommands = pluginCommandBindings(plugins)
        val allCommands = pluginCommandBindings(plugins, includeDisabled = true)

        assertFalse(activeCommands.any { it.pluginId == BuiltInPlugins.JM_ID })
        assertTrue(allCommands.any { it.trigger == "/jm" })
        assertTrue("/jm" in BuiltInPlugins.reservedCommandAliases())
        assertNotNull(BuiltInPlugins.findDefaultCommand("/findbook"))
    }
}
