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

    @Test
    fun rendersCpuAiAndOnScreenSections() {
        val s = LogSummary()
        s.add(line(mapOf(
            "t" to 1L, "type" to "perf", "win" to 60_000L,
            "dns_cpu_us" to 2_000_000L, "fwd_cpu_us" to 1_000_000L, "a11y_cpu_us" to 6_000_000L, "text_cpu_us" to 4_000_000L,
            "shot_cpu_us" to 1_000_000L, "clf_cpu_us" to 800_000L,
            "shot_req" to 5L, "shot_timer" to 4L, "shot" to 3L, "shot_fail" to 2L, "shot_fail_c6" to 2L,
            "clf" to 3L, "clf_top_neutral" to 3L, "clf_risk_b0" to 2L, "clf_risk_b7" to 1L,
            "text_captured" to 10L, "text_nodes" to 500L, "text_cat_hits" to 1L, "text_cat_gambling" to 1L, "url_cat_hits" to 2L,
        )))
        val out = s.render()
        assertTrue(out, out.contains("== where the cpu goes"))
        assertTrue(out, out.contains("a11y events        6.0 s   60%"))
        assertTrue(out, out.contains("  of which ai"))
        assertTrue(out, out.contains("fail codes    6=2"))
        assertTrue(out, out.contains("top label     neutral=3"))
        assertTrue(out, out.contains("0-9%:2  70-79%:1"))
        assertTrue(out, out.contains("10 captures, 1 naming a bundle site, avg 50 nodes"))
        assertTrue(out, out.contains("text cats     gambling=1"))
        assertTrue(out, out.contains("address bar   2 visits"))
    }
}
