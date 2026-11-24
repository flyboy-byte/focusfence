package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnHealthTest {

    private val online = { true }
    private val offline = { false }

    private fun fresh(): VpnHealth = VpnHealth().also { it.reset(0, screenOn = true) }

    @Test
    fun startsOk() {
        val h = fresh()
        h.recordQuery(1_000)
        assertEquals(VpnHealth.State.OK, h.evaluate(2_000, false, false, online))
    }

    @Test
    fun lockdownBeatsEverything() {
        val h = fresh()
        repeat(30) { h.recordForward(true) }
        assertEquals(VpnHealth.State.LOCKDOWN, h.evaluate(1_000, true, true, online))
        assertTrue(VpnHealth.State.LOCKDOWN.problem)
    }

    @Test
    fun strictPrivateDnsIsBypassedEvenWhenRelaysWork() {
        val h = fresh()
        repeat(30) { h.recordForward(true) }
        assertEquals(VpnHealth.State.BYPASSED, h.evaluate(1_000, false, true, online))
        assertTrue(VpnHealth.State.BYPASSED.problem)
    }

    @Test
    fun failureStreakWhileOnlineIsUpstreamDown() {
        val h = fresh()
        repeat(6) { h.recordForward(false) }
        h.recordQuery(1_000)
        assertEquals(VpnHealth.State.UPSTREAM_DOWN, h.evaluate(2_000, false, false, online))
    }

    @Test
    fun failuresWhileOfflineAreNotAnAlarm() {
        val h = fresh()
        repeat(10) { h.recordForward(false) }
        h.recordQuery(1_000)
        val s = h.evaluate(2_000, false, false, offline)
        assertEquals(VpnHealth.State.OFFLINE, s)
        assertFalse(s.problem)
    }

    @Test
    fun oneSuccessBreaksTheStreak() {
        val h = fresh()
        repeat(5) { h.recordForward(false) }
        h.recordForward(true)
        repeat(5) { h.recordForward(false) }
        h.recordQuery(1_000)
        // 10 fails / 11 samples is under minSamples, and no 6-streak.
        assertEquals(VpnHealth.State.OK, h.evaluate(2_000, false, false, online))
    }

    @Test
    fun highFailureShareIsDegraded() {
        val h = fresh()
        // 30% failures, interleaved so there's never a long streak.
        repeat(10) {
            h.recordForward(false)
            h.recordForward(true)
            h.recordForward(true)
        }
        assertEquals(0.333, h.failureRate()!!, 0.01)
        h.recordQuery(1_000)
        assertEquals(VpnHealth.State.DEGRADED, h.evaluate(2_000, false, false, online))
    }

    @Test
    fun degradedStaysOnUntilFailuresDropWellBelowTheTrigger() {
        val h = fresh()
        h.recordQuery(1_000)
        // 30% failures: degraded.
        repeat(10) { h.recordForward(false); h.recordForward(true); h.recordForward(true) }
        assertEquals(VpnHealth.State.DEGRADED, h.evaluate(2_000, false, false, online))
        // Hovering at 20%: under the 25% trigger, but still bad, so no flip back to ok.
        repeat(8) { h.recordForward(false); repeat(4) { h.recordForward(true) } }
        assertEquals(0.20, h.failureRate()!!, 0.01)
        assertEquals(VpnHealth.State.DEGRADED, h.evaluate(3_000, false, false, online))
        // Clean again: ok.
        repeat(40) { h.recordForward(true) }
        assertEquals(VpnHealth.State.OK, h.evaluate(4_000, false, false, online))
        // And from ok, 20% is not enough to go degraded.
        repeat(8) { h.recordForward(false); repeat(4) { h.recordForward(true) } }
        assertEquals(VpnHealth.State.OK, h.evaluate(5_000, false, false, online))
    }

    @Test
    fun rateNeedsEnoughSamples() {
        val h = fresh()
        repeat(5) { h.recordForward(false); h.recordForward(true) }
        assertNull(h.failureRate())
    }

    @Test
    fun windowForgetsOldFailures() {
        val h = fresh()
        repeat(20) { h.recordForward(false); h.recordForward(true) } // 50%
        repeat(40) { h.recordForward(true) } // window now all successes
        assertEquals(0.0, h.failureRate()!!, 0.0001)
    }

    @Test
    fun screenOnWithNoQueriesIsSilent() {
        val h = fresh()
        val tenMin = 10 * 60_000L
        assertEquals(VpnHealth.State.OK, h.evaluate(tenMin - 1, false, false, online))
        assertEquals(VpnHealth.State.SILENT, h.evaluate(tenMin, false, false, online))
    }

    @Test
    fun silenceClockRestartsWhenTheScreenComesOn() {
        val h = fresh()
        h.recordScreen(false, 1_000)
        // An hour with the screen off: no queries is normal.
        h.recordScreen(true, 3_600_000)
        assertEquals(VpnHealth.State.OK, h.evaluate(3_600_000 + 60_000, false, false, online))
    }

    @Test
    fun screenOffNeverSilent() {
        val h = fresh()
        h.recordScreen(false, 1_000)
        assertEquals(VpnHealth.State.OK, h.evaluate(5 * 3_600_000L, false, false, online))
    }

    @Test
    fun aQueryClearsSilence() {
        val h = fresh()
        h.recordQuery(9 * 60_000L)
        assertEquals(VpnHealth.State.OK, h.evaluate(12 * 60_000L, false, false, online))
    }
}
