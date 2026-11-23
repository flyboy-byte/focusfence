package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {

    private val hour = 3_600_000L

    private fun s(
        t: Long, level: Int, screen: Boolean = false, plugged: Boolean = false,
        cpu: Long = 0, up: Long = t, pid: Int = 1, uah: Long? = null,
    ) = BatterySample(t, level, plugged, screen, cpu, up, pid, uah)

    @Test
    fun policySamplesOnChangeOrAfterGap() {
        val prev = s(0, 80)
        assertTrue(BatteryPolicy.shouldSample(null, 80, false, false, 0))
        assertFalse(BatteryPolicy.shouldSample(prev, 80, false, false, 60_000))
        assertTrue(BatteryPolicy.shouldSample(prev, 79, false, false, 60_000))
        assertTrue(BatteryPolicy.shouldSample(prev, 80, true, false, 60_000))
        assertTrue(BatteryPolicy.shouldSample(prev, 80, false, true, 60_000))
        assertTrue(BatteryPolicy.shouldSample(prev, 80, false, false, BatteryPolicy.DEFAULT_MAX_GAP_MS))
    }

    @Test
    fun attributesDrainToScreenState() {
        val r = BatterySummary.summarize(
            listOf(
                s(0, 100, screen = true, cpu = 0),
                s(hour, 90, screen = false, cpu = 600),  // 1h screen-on: -10%, 600ms cpu
                s(2 * hour, 89, screen = false, cpu = 660), // screen-off: -1%/h
                s(3 * hour, 88, screen = false, cpu = 700),
            )
        )
        assertEquals(1.0, r.screenOn.hours, 1e-9)
        assertEquals(10, r.screenOn.pctDrop)
        assertEquals(600L, r.screenOn.cpuMs)
        assertEquals(2.0, r.screenOff.hours, 1e-9)
        assertEquals(1.0, r.screenOff.pctPerHour, 1e-9)
        assertEquals(50.0, r.screenOff.cpuMsPerHour, 1e-9)
        assertEquals(0.0, r.excludedHours, 1e-9)
    }

    @Test
    fun excludesChargingRebootsAndGaps() {
        val r = BatterySummary.summarize(
            listOf(
                s(0, 50),
                s(hour, 60, plugged = true),              // charging pair: excluded
                s(2 * hour, 70),                          // still excluded (start plugged)
                s(3 * hour, 69, up = 1000),               // reboot (uptime went back): excluded
                s(3 * hour + 10 * hour, 60, up = 1000 + 10 * hour), // 10h gap: excluded
            )
        )
        assertEquals(0.0, r.screenOff.hours, 1e-9)
        assertEquals(13.0, r.excludedHours, 1e-9)
    }

    @Test
    fun cpuOnlyCountedWithinOneProcess() {
        val r = BatterySummary.summarize(listOf(s(0, 90, cpu = 5000, pid = 1), s(hour, 89, cpu = 100, pid = 2)))
        assertEquals(0L, r.screenOff.cpuMs)
        assertEquals(1, r.screenOff.pctDrop)
    }

    @Test
    fun chargeCounterOnlyWhenEverySampleHasIt() {
        val full = BatterySummary.summarize(listOf(s(0, 90, uah = 3_000_000), s(hour, 89, uah = 2_970_000)))
        assertEquals(30_000L, full.screenOff.uahDrop)
        val partial = BatterySummary.summarize(
            listOf(s(0, 90, uah = 3_000_000), s(hour, 89, uah = 2_970_000), s(2 * hour, 88))
        )
        assertNull(partial.screenOff.uahDrop)
    }

    @Test
    fun sampleRoundTripsThroughLog() {
        val sample = BatterySample(5L, 77, false, true, 1234, 999, 42, 2_000_000, -150_000, 301)
        val line = LogJson.encode(LogEvents.battery(5L, sample))
        assertEquals(sample, BatterySample.fromLog(LogJson.decode(line)!!))
    }

    @Test
    fun perfWindowPercentilesAndReset() {
        val w = PerfWindow(startedAt = 0)
        for (ms in 1L..100L) w.latency(ms)
        w.count("q", 100)
        w.count("fail")
        val (win, f) = w.snapshot(60_000)
        assertEquals(60_000L, win)
        assertEquals(50L, f["p50"])
        assertEquals(95L, f["p95"])
        assertEquals(99L, f["p99"])
        assertEquals(100L, f["max"])
        assertEquals(100L, f["q"])
        assertEquals(1L, f["fail"])
        assertTrue(w.isEmpty())
        val (win2, f2) = w.snapshot(90_000)
        assertEquals(30_000L, win2)
        assertFalse(f2.containsKey("p50"))
    }

    @Test
    fun perfWindowBoundsMemory() {
        val w = PerfWindow(maxSamples = 10, startedAt = 0)
        for (ms in 1L..50L) w.latency(ms)
        val (_, f) = w.snapshot(1)
        assertEquals(50L, f["lat_n"]) // all counted
        assertEquals(10L, f["max"])   // percentiles from the bounded buffer
    }
}
