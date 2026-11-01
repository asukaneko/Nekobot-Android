package com.nekobot.app.ui.screens.chat

import org.junit.Assert.*
import org.junit.Test

class ChatSnapshotKeyTest {
    @Test fun snapshotKeysNeverTraverseMessagePayloads() {
        val payload = object {
            override fun equals(other: Any?): Boolean = error("payload comparison must not run")
            override fun hashCode(): Int = error("payload hashing must not run")
        }
        val snapshot = listOf(payload)
        assertEquals(ChatSnapshotKey(snapshot), ChatSnapshotKey(snapshot))
        assertEquals(System.identityHashCode(snapshot), ChatSnapshotKey(snapshot).hashCode())
        assertNotEquals(ChatSnapshotKey(snapshot), ChatSnapshotKey(listOf(payload)))
    }
}
