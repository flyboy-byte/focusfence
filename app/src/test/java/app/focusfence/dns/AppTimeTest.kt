package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AppTimeTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 11, 24)
    private val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3_600_000L
    private fun min(n: Int) = n * 60_000L
    private val RESUMED = AppTimeline.ACTIVITY_RESUMED
    private val OFF = AppTimeline.SCREEN_NON_INTERACTIVE

    @Test
    fun countsEachAppUntilTheNextOneOrScreenOff() {
        val a = AppTime(zone)
        a.feed(t0, RESUMED, "chat")
        a.feed(t0 + min(10), RESUMED, "browser")
        a.feed(t0 + min(15), RESUMED, "chat")
        a.feed(t0 + min(20), OFF, null)
        assertEquals(listOf("chat" to min(15), "browser" to min(5)), a.apps(day))
        assertEquals(min(20), a.total(day))
    }

    @Test
    fun theShadeDoesntStopTheClock() {
        val a = AppTime(zone, ignoredPackages = setOf("shade"))
        a.feed(t0, RESUMED, "chat")
        a.feed(t0 + min(5), RESUMED, "shade")
        a.feed(t0 + min(8), OFF, null)
        assertEquals(listOf("chat" to min(8)), a.apps(day))
    }

    @Test
    fun goingHomeStopsTheAppButHomeIsntListed() {
        val a = AppTime(zone, hiddenPackages = setOf("launcher"))
        a.feed(t0, RESUMED, "chat")
        a.feed(t0 + min(5), RESUMED, "launcher")
        a.feed(t0 + min(8), OFF, null)
        assertEquals(listOf("chat" to min(5)), a.apps(day))
        assertEquals(min(5), a.total(day))
    }

    @Test
    fun splitsAtMidnight() {
        val a = AppTime(zone)
        val beforeMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - min(10)
        a.feed(beforeMidnight, RESUMED, "video")
        a.feed(beforeMidnight + min(25), OFF, null)
        assertEquals(min(10), a.total(day))
        assertEquals(min(15), a.total(day.plusDays(1)))
        assertEquals(listOf("video" to min(25)), a.apps(listOf(day, day.plusDays(1))))
    }

    @Test
    fun aMissedScreenOffIsCapped() {
        val a = AppTime(zone, maxSpanMs = min(60))
        a.feed(t0, RESUMED, "game")
        a.finish(t0 + min(600))
        assertEquals(min(60), a.total(day))
    }

    @Test
    fun formatsForPeople() {
        assertEquals("<1 min", AppTime.format(30_000))
        assertEquals("45 min", AppTime.format(min(45)))
        assertEquals("2 h", AppTime.format(min(120)))
        assertEquals("2 h 5 min", AppTime.format(min(125)))
    }
}
