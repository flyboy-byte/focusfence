package app.focusfence.dns

import java.time.Instant
import java.time.ZoneId

/**
 * Plan 12 §5: a partner chooses how alerts reach them. Compiled by app and server, so an email partner (the server
 * sends) and an app partner (their own phone notifies) get the same rules.
 *
 * - [Level.ALL]: every alert at once. The default (Logan, 2026-11-25), and today's behaviour.
 * - [Level.DAILY]: important alerts at once, the rest in one message a day.
 * - [Level.IMPORTANT]: important alerts only; the rest stay in the report.
 * - Quiet hours (10 pm to 7 am unless the partner picks other hours, in their own time zone): **nothing** goes out
 *   then. Everything held is sent together when they end (Logan: "Nothing; all waits till morning"). The daily message
 *   goes out at the same hour (7 am without quiet hours).
 *
 * "Important" (Logan picked all four groups): phone silent or protection off, flagged screenshots, unlock asks, and
 * loosening or repeated blocked tries. That is nearly everything; what's left is housekeeping such as "the phone is
 * back", partners joining or leaving, group changes, and the answer to an ask.
 */
object AlertPrefs {
    enum class Level(val key: String) {
        ALL("all"), DAILY("daily"), IMPORTANT("important");

        companion object {
            fun fromKey(k: String?): Level = values().firstOrNull { it.key == k } ?: ALL
        }
    }

    /** [quietFrom]/[quietTo]: whole hours, 0–23, in the partner's zone; a window may wrap midnight (22 → 7). */
    data class Prefs(
        val level: Level = Level.ALL, val quiet: Boolean = false, val zone: String = "UTC",
        val quietFrom: Int = QUIET_FROM_HOUR, val quietTo: Int = QUIET_TO_HOUR,
    )

    const val QUIET_FROM_HOUR = 22
    const val QUIET_TO_HOUR = 7

    enum class Route { NOW, HOLD, SKIP }

    /** A clean hour, or the default: anything else a form sends falls back rather than breaking the window. */
    fun hourOr(v: Int?, default: Int): Int = v?.takeIf { it in 0..23 } ?: default

    fun inQuiet(p: Prefs, hour: Int): Boolean {
        val from = hourOr(p.quietFrom, QUIET_FROM_HOUR); val to = hourOr(p.quietTo, QUIET_TO_HOUR)
        return when {
            from == to -> false // an empty window, not a 24-hour one: nobody means "never tell me"
            from < to -> hour in from until to
            else -> hour >= from || hour < to
        }
    }

    /** When held alerts go out: the end of quiet hours, or 7 am without them. */
    fun digestHour(p: Prefs): Int = if (p.quiet) hourOr(p.quietTo, QUIET_TO_HOUR) else QUIET_TO_HOUR

    fun route(p: Prefs, important: Boolean, hour: Int): Route = when {
        !important && p.level == Level.IMPORTANT -> Route.SKIP
        !important && p.level == Level.DAILY -> Route.HOLD
        p.quiet && inQuiet(p, hour) -> Route.HOLD
        else -> Route.NOW
    }

    /**
     * Whether held alerts go out now: past [digestHour] on a day they haven't gone out yet, and not inside quiet hours.
     * One rule covers both the night's held alerts and the daily message, since both are sent at the same hour.
     */
    fun flushDue(p: Prefs, hour: Int, day: Long, lastFlushDay: Long, anyHeld: Boolean): Boolean =
        anyHeld && day > lastFlushDay && hour >= digestHour(p) && !(p.quiet && inQuiet(p, hour))

    /** "10 pm", "7 am", "12 pm". */
    fun hourLabel(h: Int): String = when (h) { 0 -> "12 am"; 12 -> "12 pm"; in 1..11 -> "$h am"; else -> "${h - 12} pm" }

    fun zoneOf(p: Prefs): ZoneId = runCatching { ZoneId.of(p.zone) }.getOrDefault(ZoneId.of("UTC"))
    fun hour(p: Prefs, nowMs: Long): Int = Instant.ofEpochMilli(nowMs).atZone(zoneOf(p)).hour
    fun day(p: Prefs, nowMs: Long): Long = Instant.ofEpochMilli(nowMs).atZone(zoneOf(p)).toLocalDate().toEpochDay()

    /** A time zone name from the partner's browser, kept only if Java knows it. */
    fun cleanZone(z: String?): String? = z?.trim()?.take(64)?.takeIf { s -> runCatching { ZoneId.of(s) }.isSuccess }

    /** Housekeeping lines: everything else is important (see the class comment). */
    fun isImportant(f: Map<String, Any?>): Boolean {
        val comp = f["comp"]; val state = f["state"] as? String ?: return true
        if (f["type"] != LogEvents.TYPE_SERVICE) return true
        if (comp == "partner" && (state == "partner_removed" || state == "pairing_removed" || state.startsWith("group_"))) return false
        if (comp == "strict" && state == "partner_approval_used") return false
        return true
    }
}
