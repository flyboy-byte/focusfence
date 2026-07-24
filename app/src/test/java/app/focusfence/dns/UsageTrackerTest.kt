package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTrackerTest {

    private val gap = 3 * 60_000L
    private val day1 = "2026-07-23"
    private val day2 = "2026-07-24"

    @Test
    fun accumulatesGapsWithinASession() {
        val t = UsageTracker(gap)
        assertEquals(0L, t.recordAndGetUsage("reddit.com", 1_000, day1, true))
        assertEquals(60_000L, t.recordAndGetUsage("reddit.com", 61_000, day1, true))
        assertEquals(120_000L, t.recordAndGetUsage("reddit.com", 121_000, day1, true))
    }

    @Test
    fun longGapStartsNewSessionWithoutCounting() {
        val t = UsageTracker(gap)
        t.recordAndGetUsage("reddit.com", 1_000, day1, true)
        t.recordAndGetUsage("reddit.com", 61_000, day1, true) // 1 min used
        // 30 minutes of silence — not usage.
        assertEquals(60_000L, t.recordAndGetUsage("reddit.com", 61_000 + 30 * 60_000, day1, true))
        // Next query a minute later counts again.
        assertEquals(120_000L, t.recordAndGetUsage("reddit.com", 121_000 + 30 * 60_000, day1, true))
    }

    @Test
    fun screenOffNeitherAccruesNorKeepsSessionAlive() {
        val t = UsageTracker(gap)
        t.recordAndGetUsage("reddit.com", 1_000, day1, true)
        // Background refresh with screen off, one minute later: no accrual...
        assertEquals(0L, t.recordAndGetUsage("reddit.com", 61_000, day1, false))
        // ...and it broke the session, so the next screen-on query starts fresh.
        assertEquals(0L, t.recordAndGetUsage("reddit.com", 121_000, day1, true))
        assertEquals(60_000L, t.recordAndGetUsage("reddit.com", 181_000, day1, true))
    }

    @Test
    fun usageResetsOnNewDay() {
        val t = UsageTracker(gap)
        t.recordAndGetUsage("reddit.com", 1_000, day1, true)
        t.recordAndGetUsage("reddit.com", 61_000, day1, true)
        assertEquals(0L, t.recordAndGetUsage("reddit.com", 121_000, day2, true))
    }

    @Test
    fun preloadSeedsPersistedUsage() {
        val t = UsageTracker(gap)
        t.preload("reddit.com", day1, 5 * 60_000L)
        assertEquals(5 * 60_000L, t.recordAndGetUsage("reddit.com", 1_000, day1, true))
        // Preloaded value from an older day is discarded.
        val t2 = UsageTracker(gap)
        t2.preload("reddit.com", day1, 5 * 60_000L)
        assertEquals(0L, t2.recordAndGetUsage("reddit.com", 1_000, day2, true))
    }

    @Test
    fun domainsAreTrackedIndependently() {
        val t = UsageTracker(gap)
        t.recordAndGetUsage("reddit.com", 1_000, day1, true)
        t.recordAndGetUsage("reddit.com", 61_000, day1, true)
        assertEquals(0L, t.recordAndGetUsage("x.com", 62_000, day1, true))
    }
}
