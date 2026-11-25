package com.nekobot.app

import org.junit.Assert.*
import org.junit.Test

class AppPrivacySessionTest {
    private class Home
    private class CompanionPage

    @Test fun unlockedInternalTransfersAreSingleUseInBothDirections() {
        val session = AppPrivacySession()
        val home = Home()
        val companion = CompanionPage()
        assertFalse(session.start(home, true))
        assertTrue(session.authenticated(home))
        session.transferTo(CompanionPage::class.java)
        session.pause(home)
        assertTrue(session.start(companion, true))
        session.stop(home)
        session.transferTo(Home::class.java)
        session.pause(companion)
        assertTrue(session.start(home, true))
        session.stop(companion)
        session.pause(home)
        assertFalse(session.start(companion, true))
    }

    @Test fun backgroundRecentsAndNotificationLaunchRequireFreshAuthentication() {
        val session = AppPrivacySession()
        val companion = CompanionPage()
        assertFalse(session.start(companion, true))
        assertTrue(session.authenticated(companion))
        session.pause(companion)
        session.stop(companion)
        assertFalse(session.authenticated(companion))
        assertFalse(session.start(companion, true))
        session.pause(companion)
        assertFalse(session.start(CompanionPage(), true))
        assertTrue(session.start(companion, false))
    }

    @Test fun lockedSourceAndInterruptedTransferCannotGrantEntry() {
        val session = AppPrivacySession()
        val home = Home()
        val companion = CompanionPage()
        assertFalse(session.start(home, true))
        session.transferTo(CompanionPage::class.java)
        session.pause(home)
        assertFalse(session.start(companion, true))
        assertTrue(session.authenticated(companion))
        session.transferTo(Home::class.java)
        session.pause(companion)
        session.stop(companion)
        assertFalse(session.start(home, true))
    }

    @Test fun configurationChangeGrantsOnlyTheRecreatedInstanceOneUnlockedStart() {
        val session = AppPrivacySession()
        val retained = PrivacyRecreationGrant()
        val original = CompanionPage()
        assertFalse(session.start(original, true, retained))
        assertTrue(session.authenticated(original))
        assertTrue(session.pause(original, recreating = retained))
        session.stop(original)
        assertFalse(session.authenticated(original))
        // A notification starting a separate instance of the same class has its own store.
        assertFalse(session.start(CompanionPage(), true, PrivacyRecreationGrant()))

        val recreated = CompanionPage()
        assertTrue(session.start(recreated, true, retained))
        assertFalse(session.pause(recreated))
        session.stop(recreated)
        assertFalse(session.start(recreated, true, retained))
    }

    @Test fun promptPauseAllowsSuccessUntilStopAndResumeNeedsNoSecondGrant() {
        val session = AppPrivacySession()
        val home = Home()
        val companion = CompanionPage()
        assertFalse(session.start(companion, true))
        assertFalse(session.pause(companion, promptPending = true))
        assertTrue(session.authenticated(companion))
        // ON_RESUME only updates FLAG_SECURE. The authenticated session can navigate immediately.
        session.transferTo(Home::class.java)
        session.pause(companion)
        assertTrue(session.start(home, true))
        session.stop(companion)
        session.pause(home, promptPending = true)
        session.stop(home)
        assertFalse(session.authenticated(home))
        assertFalse(session.start(home, true))
    }
}
