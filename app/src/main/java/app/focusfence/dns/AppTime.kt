package app.focusfence.dns

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Foreground time per app per day, built from the [AppTimeline] stream (the same one the activity log records), for
 * the "This week" screen. Pure so it is tested on the JVM; the screen reads UsageStats events and feeds them in.
 *
 * [ignoredPackages] never take the foreground (the notification shade over an app); [hiddenPackages] do take it but
 * aren't listed or counted (the launcher: going home stops the app you left, yet home-screen time isn't app time).
 *
 * A stretch that crosses midnight is split between the two days. One stretch counts at most [maxSpanMs]: a missed
 * screen-off (phone died, events trimmed) must not turn into a 14-hour app.
 */
class AppTime(
    private val zone: ZoneId,
    ignoredPackages: Set<String> = emptySet(),
    private val hiddenPackages: Set<String> = emptySet(),
    private val maxSpanMs: Long = 4 * 3_600_000L,
) {
    private val timeline = AppTimeline(ignoredPackages)
    private val byDay = HashMap<LocalDate, HashMap<String, Long>>()
    private var current: String? = null
    private var since = 0L

    /** Feed raw UsageStats events in time order (type ints match UsageEvents.Event). */
    fun feed(t: Long, type: Int, pkg: String?) {
        for (out in timeline.feed(t, type, pkg)) {
            when (out) {
                is AppTimeline.Out.App -> if (out.state == "fg") {
                    close(out.t); current = out.pkg; since = out.t
                } else if (out.pkg == current) {
                    close(out.t)
                }
                is AppTimeline.Out.Screen -> if (out.state == "off") close(out.t)
            }
        }
    }

    /** Counts the app still in front up to [now]. Call once after the last event. */
    fun finish(now: Long) = close(now)

    /** Milliseconds per package on [day], biggest first. */
    fun apps(day: LocalDate): List<Pair<String, Long>> =
        byDay[day].orEmpty().entries.filter { it.key !in hiddenPackages }.sortedByDescending { it.value }.map { it.key to it.value }

    /** Milliseconds per package summed over [days], biggest first. */
    fun apps(days: Collection<LocalDate>): List<Pair<String, Long>> {
        val sum = HashMap<String, Long>()
        for (d in days) byDay[d]?.forEach { (p, ms) -> sum[p] = (sum[p] ?: 0L) + ms }
        return sum.entries.filter { it.key !in hiddenPackages }.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun total(day: LocalDate): Long = byDay[day]?.filterKeys { it !in hiddenPackages }?.values?.sum() ?: 0L

    private fun close(t: Long) {
        val pkg = current ?: return
        current = null
        var start = since
        val end = minOf(t, since + maxSpanMs)
        while (start < end) {
            val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stop = minOf(end, nextMidnight)
            val m = byDay.getOrPut(day) { HashMap() }
            m[pkg] = (m[pkg] ?: 0L) + (stop - start)
            start = stop
        }
    }

    companion object {
        /** "2 h 5 min", "45 min", "<1 min". */
        fun format(ms: Long): String {
            val min = ms / 60_000
            return when {
                min < 1 -> "<1 min"
                min < 60 -> "$min min"
                min % 60 == 0L -> "${min / 60} h"
                else -> "${min / 60} h ${min % 60} min"
            }
        }
    }
}
