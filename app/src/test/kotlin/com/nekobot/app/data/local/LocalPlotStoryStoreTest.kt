package com.nekobot.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPlotStoryStoreTest {

    @Test
    fun ownerLeaseRejectsOldRepositoryAfterSameProfileIsReactivated() {
        val oldOwner = Any()
        val newOwner = Any()
        var oldWriteRan = false
        var newWriteRan = false

        LocalPlotStoryStore.activateProfile("same-profile", oldOwner) {}
        LocalPlotStoryStore.activateProfile("same-profile.db", newOwner) {}

        assertFalse(
            LocalPlotStoryStore.runIfActiveProfile("same-profile", oldOwner) {
                oldWriteRan = true
            }
        )
        LocalPlotStoryStore.deactivateProfile(oldOwner)
        assertTrue(
            LocalPlotStoryStore.runIfActiveProfile("same-profile", newOwner) {
                newWriteRan = true
            }
        )
        assertFalse(oldWriteRan)
        assertTrue(newWriteRan)

        LocalPlotStoryStore.deactivateProfile(newOwner)
        assertFalse(LocalPlotStoryStore.runIfActiveProfile("same-profile", newOwner) {})
    }

    @Test
    fun failedInitializationDoesNotLeaveOwnerActive() {
        val failedOwner = Any()

        assertThrows(IllegalStateException::class.java) {
            LocalPlotStoryStore.activateProfile("failed-profile", failedOwner) {
                throw IllegalStateException("boom")
            }
        }

        assertFalse(
            LocalPlotStoryStore.runIfActiveProfile("failed-profile", failedOwner) {}
        )
    }
}
