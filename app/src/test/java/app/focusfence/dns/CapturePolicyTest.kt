package app.focusfence.dns

import app.focusfence.dns.CapturePolicy.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {

    @Test fun intervalStaysWithinBoundsAndCentersOnTarget() {
        val lo = CapturePolicy.nextDueAt(0, Level.STANDARD, 0.0)
        val hi = CapturePolicy.nextDueAt(0, Level.STANDARD, 0.999999)
        val mid = CapturePolicy.nextDueAt(0, Level.STANDARD, 0.5)
        assertEquals(Level.STANDARD.minMs, lo)                 // floor at rnd=0
        assertTrue(hi <= 2 * Level.STANDARD.targetMs - Level.STANDARD.minMs)
        assertEquals(Level.STANDARD.targetMs, mid)             // mean at rnd=0.5
    }

    @Test fun clampsOutOfRangeRandom() {
        assertEquals(Level.LOW.minMs, CapturePolicy.nextDueAt(0, Level.LOW, -1.0))
    }

    @Test fun capturesOnlyWhenForegroundScreenOnAndDue() {
        val due = 1000L
        assertTrue(CapturePolicy.shouldCapture(true, true, 1000, due))
        assertFalse(CapturePolicy.shouldCapture(true, true, 999, due))   // not due
        assertFalse(CapturePolicy.shouldCapture(false, true, 2000, due)) // not watched
        assertFalse(CapturePolicy.shouldCapture(true, false, 2000, due)) // screen off
    }
}
