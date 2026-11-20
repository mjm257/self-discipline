package dev.daily.todo

import org.junit.Assert.*
import org.junit.Test

class StorePolicyTest {
    @Test fun acceptsAnswersFromFifteenCharactersWithoutACap() {
        assertFalse(canSubmitAnswer("字".repeat(14) + "　"))
        assertTrue(canSubmitAnswer("字".repeat(15)))
        assertTrue(canSubmitAnswer("字".repeat(100_000)))
    }
    @Test fun countsCodePointsWithoutFormattingOrSpaces() {
        assertEquals(0, answerLength("\u0000\t\n\u00a0\u0085\u3000\u200d"))
        assertEquals(14, answerLength("一".repeat(13) + "😀"))
        assertEquals(15, answerLength("一".repeat(14) + "😀"))
    }

    @Test fun zeroCooldownAllowsImmediateReentry() {
        assertTrue(isRuleReady(true, 0, 1000, 1000))
        assertFalse(isRuleReady(false, 0, 1000, 1000))
        assertFalse(isRuleReady(true, 1, 1000, 1999))
        assertTrue(isRuleReady(true, 1, 1000, 2000))
    }

    @Test fun pinHashesAreSaltedAndChecked() {
        val first = PinHash.newSalt()
        val second = PinHash.newSalt()
        val digest = PinHash.encode("123456", first)
        assertNotEquals(first, second)
        assertNotEquals(digest, PinHash.encode("123456", second))
        assertTrue(PinHash.matches("123456", first, PinHash.iterations, digest))
        assertFalse(PinHash.matches("123455", first, PinHash.iterations, digest))
    }

    @Test fun firstRunAsksOnEveryEntryAfterPinSetup() {
        assertEquals(EntryPolicy.EVERY_ENTRY, AppSettings(hasPin = true).entryPolicy)
    }

    @Test fun pausesAreIndependentAndExpireAtDeadline() {
        val settings = AppSettings(hasPin = true, guardPausedUntil = 500)
        assertFalse(settings.guardingAt(499))
        assertTrue(settings.askingAt(499))
        assertTrue(settings.guardingAt(500))
        assertFalse(settings.copy(allPausedUntil = 700).askingAt(600))
        assertFalse(settings.copy(hasPin = false).guardingAt(800))
    }
}
