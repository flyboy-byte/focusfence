package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSummaryTest {

    private fun line(f: Map<String, Any?>) = LogJson.decode(LogJson.encode(f))!!

    @Test
    fun countsDetectionAndBlocks() {
        val s = LogSummary()
        val rule = BlockRule("reddit.com", budgetMinutes = 10)
        s.add(line(LogEvents.dns(1, "www.reddit.com", 1, LogEvents.DECISION_ALLOWED, LogEvents.REASON_RULE, rule)))
        s.add(line(LogEvents.dns(2, "www.reddit.com", 1, LogEvents.DECISION_ALLOWED, LogEvents.REASON_RULE, rule)))
        s.add(line(LogEvents.dns(3, "ads.x.com", 1, LogEvents.DECISION_BLOCKED, LogEvents.REASON_ADS)))
        s.add(line(LogEvents.app(4, "com.reddit", "fg")))
        s.add(line(LogEvents.app(5, "com.reddit", "bg")))
        assertEquals(2L, s.dnsDecisions["allowed"])
        assertEquals(1L, s.blockReasons["ads"])
        assertEquals(listOf("www.reddit.com" to 2L), s.topAllowed(5))
        assertEquals(listOf("com.reddit" to 1L), s.topApps(5)) // only fg counted
        assertEquals(1L, s.firstT)
        assertEquals(5L, s.lastT)
    }

    @Test
    fun aggregatesPerfWindows() {
        val s = LogSummary()
        s.add(line(LogEvents.perf(10, 1000, mapOf("q" to 100L, "fwd" to 90L, "fwd_fail" to 10L, "p95" to 40L))))
        s.add(line(LogEvents.perf(20, 1000, mapOf("q" to 50L, "fwd" to 50L, "p95" to 80L, "url_read" to 3L, "url_miss" to 1L))))
        s.add(line(LogEvents.perf(30, 1000, mapOf("p95" to 60L))))
        assertEquals(150L, s.perfTotals["q"])
        assertNull(s.perfTotals["p95"]) // percentiles are not summed
        assertEquals(10.0 / 150.0, s.forwardFailureRate()!!, 1e-9)
        assertEquals(0.25, s.urlMissRate()!!, 1e-9)
        assertEquals(60L to 80L, s.latencyP95())
        assertEquals(3000L, s.perfWindowMs)
    }

    @Test
    fun respectsSinceAndSkipsJunk() {
        val s = LogSummary(sinceT = 100)
        s.add(line(LogEvents.dns(50, "old.com", 1, LogEvents.DECISION_ALLOWED)))
        s.add(mapOf("type" to "dns")) // no t
        s.add(line(LogEvents.integrity(150, "vpn", "stopped", "running")))
        assertEquals(mapOf("integ" to 1L), s.typeCounts)
        assertTrue(s.render().contains("vpn=stopped (was running)"))
        assertNull(s.forwardFailureRate())
    }
}
