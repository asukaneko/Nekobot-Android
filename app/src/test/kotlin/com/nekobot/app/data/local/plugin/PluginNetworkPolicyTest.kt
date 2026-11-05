package com.nekobot.app.data.local.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginNetworkPolicyTest {
    @Test fun terminalAndOtherNetworkApisAreRejectedWhileOffline() {
        listOf("terminal.exec", "ai.complete", "http.get", "http.post").forEach { api ->
            val error = assertThrows(PluginApiException::class.java) {
                checkPluginNetworkAccess(api) { false }
            }
            assertEquals("network_disabled", error.code)
        }
    }

    @Test fun offlineStorageDoesNotRequireNetworkAccess() {
        checkPluginNetworkAccess("storage.get") { error("offline API must not query network permission") }
    }

    @Test fun changesToTheNetworkSwitchApplyToEachCall() {
        var enabled = true
        checkPluginNetworkAccess("terminal.exec") { enabled }
        enabled = false
        assertThrows(PluginApiException::class.java) { checkPluginNetworkAccess("terminal.exec") { enabled } }
    }
}
