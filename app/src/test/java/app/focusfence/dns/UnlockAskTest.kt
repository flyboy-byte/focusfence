package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockAskTest {
    private val min = 60_000L

    @Test
    fun aRequestNobodyAnswersLapses() {
        assertEquals(UnlockAsk.PENDING, UnlockAsk.state(UnlockAsk.PENDING, 0, 15 * min))
        assertEquals(UnlockAsk.LAPSED, UnlockAsk.state(UnlockAsk.PENDING, 0, 15 * min + 1))
        assertTrue(UnlockAsk.open(UnlockAsk.PENDING, 0, 14 * min))
        assertFalse(UnlockAsk.open(UnlockAsk.PENDING, 0, 16 * min))
        // An answer doesn't lapse; it's simply what happened.
        assertEquals(UnlockAsk.DENIED, UnlockAsk.state(UnlockAsk.DENIED, 0, 60 * min))
        assertFalse(UnlockAsk.open(UnlockAsk.APPROVED, 0, min))
    }

    @Test
    fun anApprovalCoversOnlyWhatWasAskedForFifteenMinutes() {
        val a = GuardPolicy.Action.LOOSEN_BUNDLE
        val decided = 100 * min
        assertTrue(UnlockAsk.allows(a.key, decided, a, decided))
        assertTrue(UnlockAsk.allows(a.key, decided, a, decided + 15 * min - 1))
        assertFalse(UnlockAsk.allows(a.key, decided, a, decided + 15 * min))
        assertFalse(UnlockAsk.allows(a.key, decided, GuardPolicy.Action.TURN_OFF_VPN, decided + min))
        assertFalse(UnlockAsk.allows(null, decided, a, decided + min))
        assertFalse(UnlockAsk.allows(a.key, 0, a, min))
        assertFalse("a clock set back doesn't stretch it", UnlockAsk.allows(a.key, decided, a, decided - min))
    }

    @Test
    fun partnersReadPlainWords() {
        val a = GuardPolicy.Action.TURN_OFF_VPN
        assertEquals(
            "The phone's owner asks for your OK to turn off the VPN. Approve or deny it on the report page; the request " +
                "lapses in 15 minutes. If nobody answers, they can still wait 10 minutes and do it.",
            UnlockAsk.askLine(a),
        )
        assertEquals("Sam approved: turn off the VPN, for the next 15 minutes.", UnlockAsk.answerLine("Sam", true, a))
        assertEquals("A partner said no to: turn off the VPN.", UnlockAsk.answerLine("", false, a))
    }
}
