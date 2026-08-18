package app.focusfence.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityMeterTest {

    private val minute = 60_000L

    @Test
    fun `starts empty`() {
        val meter = ActivityMeter(5)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), meter.snapshot(0))
    }

    @Test
    fun `counts within one minute land in the newest bucket`() {
        val meter = ActivityMeter(5)
        meter.record(0)
        meter.record(30_000)
        meter.record(59_999)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 3), meter.snapshot(59_999))
    }

    @Test
    fun `each minute opens a new bucket`() {
        val meter = ActivityMeter(5)
        meter.record(0, 1)
        meter.record(minute, 2)
        meter.record(2 * minute, 3)
        assertArrayEquals(intArrayOf(0, 0, 1, 2, 3), meter.snapshot(2 * minute))
    }

    @Test
    fun `oldest counts fall off the ring`() {
        val meter = ActivityMeter(3)
        meter.record(0, 1)
        meter.record(minute, 2)
        meter.record(2 * minute, 3)
        meter.record(3 * minute, 4)
        assertArrayEquals(intArrayOf(2, 3, 4), meter.snapshot(3 * minute))
    }

    @Test
    fun `a silent gap reads as zeros, not as stale counts`() {
        val meter = ActivityMeter(4)
        meter.record(0, 7)
        assertArrayEquals(intArrayOf(0, 0, 0, 7), meter.snapshot(0))
        // Two idle minutes later the 7 has slid back, and the newest bucket is 0.
        assertArrayEquals(intArrayOf(0, 7, 0, 0), meter.snapshot(2 * minute))
    }

    @Test
    fun `an idle gap longer than the ring clears it entirely`() {
        val meter = ActivityMeter(4)
        meter.record(0, 7)
        meter.record(minute, 9)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), meter.snapshot(500 * minute))
    }

    @Test
    fun `snapshot does not advance past the requested minute`() {
        val meter = ActivityMeter(4)
        meter.record(3 * minute, 5)
        assertArrayEquals(intArrayOf(0, 0, 0, 5), meter.snapshot(3 * minute))
        // Recording again in the same minute still hits the same bucket.
        meter.record(3 * minute + 100, 2)
        assertArrayEquals(intArrayOf(0, 0, 0, 7), meter.snapshot(3 * minute + 100))
    }

    @Test
    fun `a backwards clock keeps the current bucket`() {
        val meter = ActivityMeter(4)
        meter.record(5 * minute, 3)
        meter.record(2 * minute, 4)
        assertArrayEquals(intArrayOf(0, 0, 0, 7), meter.snapshot(5 * minute))
    }

    @Test
    fun `reset empties the ring`() {
        val meter = ActivityMeter(3)
        meter.record(0, 4)
        meter.record(minute, 5)
        meter.reset()
        assertArrayEquals(intArrayOf(0, 0, 0), meter.snapshot(minute))
        // And the ring restarts from whatever minute comes next.
        meter.record(9 * minute, 2)
        assertArrayEquals(intArrayOf(0, 0, 2), meter.snapshot(9 * minute))
    }

    @Test
    fun `defaults to twenty minutes`() {
        assertEquals(20, ActivityMeter().snapshot(0).size)
        assertEquals(20, ActivityMeter.DEFAULT_MINUTES)
    }
}
