package dev.daily.todo

import org.junit.Assert.*
import org.junit.Test

class GuardSessionsTest {
    @Test fun keyboardClosingDuringSubmitIsNotADeparture() {
        val sessions = GuardSessions()
        val saving = sessions.enter("a", 10)
        sessions.obscure()
        assertFalse(sessions.hasForegroundEvidence)
        saving.released = true
        assertSame(saving, sessions.enter("a", 20))
        assertTrue(sessions.current!!.released)
        sessions.obscure()
        sessions.enter("home", 30)
        assertFalse(sessions.enter("a", 40).released)
    }

    @Test fun reconnectKeepsOnlyAShortMatchingRelease() {
        val sessions = GuardSessions()
        sessions.enter("a", 5).released = true
        sessions.disconnect(10)
        sessions.disconnect(15)
        assertNull(sessions.current)
        assertTrue(sessions.enter("a", 20).released)
        sessions.disconnect(25)
        assertFalse(sessions.enter("b", 30).released)
        assertFalse(sessions.enter("a", 35).released)
        sessions.current!!.released = true
        sessions.disconnect(40)
        assertFalse(sessions.enter("a", 40_000).released)
        sessions.current!!.released = true
        sessions.disconnect(40_001)
        sessions.leave()
        assertFalse(sessions.enter("a", 40_002).released)
    }
    @Test fun continuousTargetDoesNotRepromptAfterSubmit() {
        val sessions = GuardSessions()
        val first = sessions.enter("a", 0)
        first.released = true
        assertSame(first, sessions.enter("a", 0))
        assertTrue(sessions.enter("a", 0).released)
        sessions.enter("b", 0)
        assertFalse(sessions.enter("a", 0).released)
    }

    @Test fun interruptionInvalidatesOldEvidenceAndPreservesMatchingDraft() {
        val sessions = GuardSessions()
        val before = sessions.enter("a", 0)
        sessions.draft("a", "why").text = "reading"
        sessions.leave()
        assertNull(sessions.current)
        assertNotSame(before, sessions.enter("a", 0))
        assertEquals("", sessions.draft("b", "why").text)
        assertEquals("reading", sessions.draft("a", "why").text)
        assertEquals("", sessions.draft("a", "new question").text)
    }
}
