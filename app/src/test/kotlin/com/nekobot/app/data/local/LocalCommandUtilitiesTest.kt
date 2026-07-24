package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalCommandUtilitiesTest {
    @Test
    fun rollsStandardDiceNotationWithModifier() {
        val values = ArrayDeque(listOf(0, 5))
        val result = LocalCommandUtilities.rollDiceText("2d6+1") {
            values.removeFirst()
        }

        assertTrue(result.contains("2d6+1 = 8"))
        assertTrue(result.contains("点数：1, 6"))
        assertTrue(result.contains("修正：+1"))
    }

    @Test
    fun rejectsUnsafeDiceRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalCommandUtilities.rollDiceText("101d6")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCommandUtilities.rollDiceText("1d1")
        }
    }

    @Test
    fun picksFromPipeAndChineseCommaOptions() {
        assertEquals(
            "🎯 我选：**B**",
            LocalCommandUtilities.pickText("A | B | C") { 1 }
        )
        assertEquals(
            "🎯 我选：**乙**",
            LocalCommandUtilities.pickText("甲，乙，丙") { 1 }
        )
    }

    @Test
    fun calculatesExpressionsWithoutExecutingCode() {
        assertEquals("60", LocalCommandUtilities.calculate("(12 + 3) * 4"))
        assertEquals("17", LocalCommandUtilities.calculate("sqrt(81) + 2^3"))
        assertEquals("1", LocalCommandUtilities.calculate("sin(pi / 2)"))

        assertThrows(IllegalArgumentException::class.java) {
            LocalCommandUtilities.calculate("1 / 0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCommandUtilities.calculate("Runtime.exec(1)")
        }
    }

    @Test
    fun generatesStrongPasswordWithinRequestedLength() {
        val password = LocalCommandUtilities.generatePassword("32")

        assertEquals(32, password.length)
        assertTrue(password.any(Char::isLowerCase))
        assertTrue(password.any(Char::isUpperCase))
        assertTrue(password.any(Char::isDigit))
        assertTrue(password.any { !it.isLetterOrDigit() })
    }

    @Test
    fun hashesUtf8TextWithSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LocalCommandUtilities.sha256("abc")
        )
    }
}
