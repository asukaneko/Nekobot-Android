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
        assertFalse(session.resume(home, true))
        assertTrue(session.authenticated(home))
        session.transferTo(CompanionPage::class.java)
        session.pause(home)
        assertTrue(session.resume(companion, true))
        session.stop(home)
        session.transferTo(Home::class.java)
        session.pause(companion)
        assertTrue(session.resume(home, true))
        session.stop(companion)
        session.pause(home)
        assertFalse(session.resume(companion, true))
    }

    @Test fun backgroundRecentsAndNotificationLaunchRequireFreshAuthentication() {
        val session = AppPrivacySession()
        val companion = CompanionPage()
        assertFalse(session.resume(companion, true))
        assertTrue(session.authenticated(companion))
        session.pause(companion)
        session.stop(companion)
        assertFalse(session.authenticated(companion))
        assertFalse(session.resume(companion, true))
        session.pause(companion)
        assertFalse(session.resume(CompanionPage(), true))
        assertTrue(session.resume(companion, false))
    }

    @Test fun lockedSourceAndInterruptedTransferCannotGrantEntry() {
        val session = AppPrivacySession()
        val home = Home()
        val companion = CompanionPage()
        assertFalse(session.resume(home, true))
        session.transferTo(CompanionPage::class.java)
        session.pause(home)
        assertFalse(session.resume(companion, true))
        assertTrue(session.authenticated(companion))
        session.transferTo(Home::class.java)
        session.pause(companion)
        session.stop(companion)
        assertFalse(session.resume(home, true))
    }
}
