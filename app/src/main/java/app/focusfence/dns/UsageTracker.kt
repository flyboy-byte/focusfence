package app.focusfence.dns

/**
 * Infers per-domain usage time from DNS query activity.
 *
 * While a site is actively used, the device keeps re-resolving its domains as
 * short DNS TTLs expire, so consecutive queries arrive at most a couple of
 * minutes apart. Each query within [sessionGapMillis] of the previous one
 * counts the gap as usage; a longer gap starts a new session and contributes
 * nothing. Usage accumulates per calendar day, keyed by [dayKey].
 *
 * Pure JVM state machine (no Android/clock dependencies) so it is unit-testable;
 * the caller supplies timestamps and day keys.
 */
class UsageTracker(private val sessionGapMillis: Long = DEFAULT_SESSION_GAP_MILLIS) {

    companion object {
        /** Max gap between lookups still considered continuous usage. */
        const val DEFAULT_SESSION_GAP_MILLIS: Long = 3 * 60_000
    }

    private class Entry(var dayKey: String, var usedMillis: Long, var lastSeenMillis: Long)

    private val entries = HashMap<String, Entry>()

    /** Seeds persisted usage (e.g. after a service restart). */
    @Synchronized
    fun preload(domain: String, dayKey: String, usedMillis: Long) {
        entries[domain] = Entry(dayKey, usedMillis, 0L)
    }

    /**
     * Registers a lookup for [domain] at [nowMillis] and returns total usage for
     * [dayKey]. With [accrue] false (e.g. screen off) nothing is added — the
     * current total is returned and the session is interrupted, so background
     * refreshes neither consume the budget nor keep a session alive.
     */
    @Synchronized
    fun recordAndGetUsage(domain: String, nowMillis: Long, dayKey: String, accrue: Boolean): Long {
        val entry = entries.getOrPut(domain) { Entry(dayKey, 0L, 0L) }
        if (entry.dayKey != dayKey) {
            entry.dayKey = dayKey
            entry.usedMillis = 0L
            entry.lastSeenMillis = 0L
        }
        if (!accrue) {
            entry.lastSeenMillis = 0L
            return entry.usedMillis
        }
        val last = entry.lastSeenMillis
        if (last in 1..nowMillis && nowMillis - last <= sessionGapMillis) {
            entry.usedMillis += nowMillis - last
        }
        entry.lastSeenMillis = nowMillis
        return entry.usedMillis
    }

    /** Current usage snapshot: domain -> (dayKey, usedMillis). */
    @Synchronized
    fun snapshot(): Map<String, Pair<String, Long>> =
        entries.mapValues { (_, e) -> e.dayKey to e.usedMillis }
}
