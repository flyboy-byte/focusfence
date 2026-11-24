package app.focusfence.dns

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class FlagPolicyTest {
    private val min = 60_000L

    @Test
    fun loneModerateHitIsHeldBack() {
        // the 2026-11-20 false positive: porn 0.82, nothing else going on
        assertNull(FlagPolicy().onRawHit("photos", 0.82, 0))
    }

    @Test
    fun veryStrongScoreFlagsAlone() {
        assertEquals(FlagPolicy.Why.STRONG, FlagPolicy().onRawHit("browser", 0.93, 0))
    }

    @Test
    fun secondHitInSameAppWithinWindowIsPersistent() {
        val p = FlagPolicy()
        assertNull(p.onRawHit("browser", 0.80, 0))
        assertEquals(FlagPolicy.Why.PERSISTENT, p.onRawHit("browser", 0.72, 2 * min))
    }

    @Test
    fun persistenceNeedsTheSameAppAndTheWindow() {
        val p = FlagPolicy()
        assertNull(p.onRawHit("browser", 0.80, 0))
        assertNull(p.onRawHit("photos", 0.80, min))          // different app
        assertNull(p.onRawHit("browser", 0.80, 10 * min))    // first hit is too old
    }

    @Test
    fun adultWordsOnScreenCorroborateAMiddlingScore() {
        val p = FlagPolicy()
        p.noteSignal("browser", 0)
        assertEquals(FlagPolicy.Why.CORROBORATED, p.onRawHit("browser", 0.76, min))
    }

    @Test
    fun corroborationNeedsRecentSignalSameAppAndARealScore() {
        val p = FlagPolicy()
        p.noteSignal("browser", 0)
        assertNull(p.onRawHit("browser", 0.71, min))         // below the corroborated floor
        assertNull(FlagPolicy().apply { noteSignal("browser", 0) }.onRawHit("photos", 0.80, min)) // other app
        assertNull(FlagPolicy().apply { noteSignal("browser", 0) }.onRawHit("browser", 0.80, 10 * min)) // stale
    }

    @Test
    fun clockGoingBackwardsDoesNotCountAsPersistence() {
        val p = FlagPolicy()
        assertNull(p.onRawHit("browser", 0.80, 10 * min))
        assertNull(p.onRawHit("browser", 0.80, 9 * min))
    }
}
