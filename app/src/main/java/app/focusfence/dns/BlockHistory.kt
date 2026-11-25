package app.focusfence.dns

/**
 * Block history (2026-11-25): the sites the filter stopped, for the owner's own Block history page. Built from the
 * activity log's blocked DNS records. Pure, so it is tested on the JVM; the page reads the log and feeds records in.
 *
 * Only what a person would recognise as "I tried to open that": a user rule's own site, or the front door of a
 * site on a content or threat list. Ads, trackers, the security and bypass lists, and deeper hosts (cdn., api.)
 * are left out, as in [BlockTriage]. Lookups of one site within [MERGE_MS] of each other are one entry, because a
 * single visit makes several (www and bare, A and AAAA, retries).
 */
object BlockHistory {
    const val MERGE_MS = 5 * 60_000L

    /** Lists whose hits are background noise, not someone opening a site. */
    private val QUIET_LISTS = setOf("security", "bypass")

    /** [why] is "rule" for the owner's own rule, otherwise the category id that blocked it. */
    data class Entry(val site: String, val first: Long, val last: Long, val count: Int, val why: String)

    private data class Hit(val t: Long, val site: String, val why: String)

    private fun hit(r: Map<String, Any?>): Hit? {
        if (r["type"] != LogEvents.TYPE_DNS || r["decision"] != LogEvents.DECISION_BLOCKED) return null
        val t = (r["t"] as? Number)?.toLong() ?: return null
        val host = (r["host"] as? String)?.trimEnd('.')?.lowercase() ?: return null
        return when (r["reason"]) {
            LogEvents.REASON_RULE -> {
                val rule = (r["rule"] as? String)?.lowercase() ?: return null
                if (Blocklist.isFrontDoor(host, rule)) Hit(t, rule, "rule") else null
            }
            LogEvents.REASON_CATEGORY -> {
                val cat = (r["cat"] as? String)?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && it !in QUIET_LISTS }
                    ?: return null
                BlockTriage.frontDoorSite(host)?.let { Hit(t, it, cat) }
            }
            else -> null
        }
    }

    /** Trackers and ads blocked in the background, by company domain: [blocked] lookups, the [last] one at. */
    data class Tracker(val domain: String, val blocked: Int, val last: Long)

    /**
     * The other side of the triage: everything [entries] leaves out because nobody opened it (ads, trackers, the
     * security and bypass lists, page parts), counted per registrable domain, most blocked first.
     */
    fun trackers(records: Sequence<Map<String, Any?>>): List<Tracker> {
        val count = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        for (r in records) {
            if (r["type"] != LogEvents.TYPE_DNS || r["decision"] != LogEvents.DECISION_BLOCKED) continue
            if (hit(r) != null) continue
            val t = (r["t"] as? Number)?.toLong() ?: continue
            val host = r["host"] as? String ?: continue
            val d = BlockTriage.registrable(host) ?: host.trimEnd('.').lowercase()
            count[d] = (count[d] ?: 0) + 1
            last[d] = maxOf(last[d] ?: 0L, t)
        }
        return count.map { (d, n) -> Tracker(d, n, last.getValue(d)) }.sortedWith(compareByDescending<Tracker> { it.blocked }.thenBy { it.domain })
    }

    /** Newest first. [records] are decoded log lines in any order. */
    fun entries(records: Sequence<Map<String, Any?>>): List<Entry> {
        val hits = records.mapNotNull(::hit).sortedBy { it.t }.toList()
        val open = HashMap<String, Entry>()
        val done = ArrayList<Entry>()
        for (h in hits) {
            val e = open[h.site]
            if (e != null && h.t - e.last <= MERGE_MS) {
                open[h.site] = e.copy(last = h.t, count = e.count + 1)
            } else {
                e?.let(done::add)
                open[h.site] = Entry(h.site, h.t, h.t, 1, h.why)
            }
        }
        done += open.values
        return done.sortedByDescending { it.last }
    }
}
