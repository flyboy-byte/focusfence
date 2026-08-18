package app.focusfence.dns

/**
 * A fixed ring of per-minute counts backing the live meter on Home.
 *
 * In memory only, like [app.focusfence.vpn.DnsVpnService.blockedCount] — it is a
 * read-out of what the filter is doing right now, not history, so nothing here
 * touches storage. Writes ride the already-blocked query path, which is a small
 * fraction of traffic and was doing a locked stats merge anyway.
 *
 * Pure by the `dns/` convention: the caller supplies the clock, which is also
 * what lets the ring's rollover be tested without waiting minutes for it.
 */
class ActivityMeter(private val minutes: Int = DEFAULT_MINUTES) {

    companion object {
        const val DEFAULT_MINUTES = 20
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val UNSTARTED = Long.MIN_VALUE
    }

    private val buckets = IntArray(minutes)
    private var head = 0
    private var headMinute = UNSTARTED

    /** Adds [count] to the bucket for the minute containing [nowMillis]. */
    @Synchronized
    fun record(nowMillis: Long, count: Int = 1) {
        advanceTo(nowMillis / MILLIS_PER_MINUTE)
        buckets[head] += count
    }

    /**
     * Counts oldest first, the last entry being the minute containing
     * [nowMillis]. Advances the ring first, so a quiet stretch reads as zeros
     * rather than as stale counts frozen at the last block.
     */
    @Synchronized
    fun snapshot(nowMillis: Long): IntArray {
        advanceTo(nowMillis / MILLIS_PER_MINUTE)
        return IntArray(minutes) { buckets[(head + 1 + it) % minutes] }
    }

    @Synchronized
    fun reset() {
        buckets.fill(0)
        head = 0
        headMinute = UNSTARTED
    }

    private fun advanceTo(minute: Long) {
        if (headMinute == UNSTARTED) {
            headMinute = minute
            return
        }
        // A clock that went backwards keeps the current bucket rather than
        // rewinding the ring; elapsedRealtime shouldn't, but the guard is free.
        if (minute <= headMinute) return
        // More than a full ring of silence is indistinguishable from exactly one,
        // so cap the walk instead of spinning through a long idle gap.
        val steps = (minute - headMinute).coerceAtMost(minutes.toLong()).toInt()
        repeat(steps) {
            head = (head + 1) % minutes
            buckets[head] = 0
        }
        headMinute = minute
    }
}
