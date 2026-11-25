package app.focusfence.dns

/**
 * Which blocks are worth telling anyone about (D-024). Pure, so the thresholds are JVM-tested.
 *
 *  - **Silent:** ads, trackers, DoH guards, other apps' background lookups, screen off. Logged, counted in the
 *    report, nothing else (about 90% of all blocks on the 2026-11 exports).
 *  - **Notice:** a user rule, or a high-risk category ([HIGH_RISK]), on a site's front door while the screen is on.
 *    The owner gets the chosen style (notification or full screen); with the accessibility service the browser's
 *    address bar must show that site first.
 *  - **Partner:** [Escalation] — repeated confirmed high-risk notices in a short window tell partners, by category,
 *    never by site name.
 */
object BlockTriage {

    /** In priority order: the first one a host is listed under names the notice. */
    val HIGH_RISK = listOf("adult", "gambling", "scam")

    /** The high-risk category a category block belongs to, or null if it's not one. */
    fun highRisk(categories: List<String>): String? = HIGH_RISK.firstOrNull { it in categories }

    private val FRONT_DOOR = listOf("www.", "m.", "mobile.")

    /** Second-level labels under a country code that make a three-label registrable name (example.co.uk). */
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "ac", "gov", "edu", "or", "ne", "go", "gob", "gv", "ltd", "plc")

    /**
     * The site a category-blocked lookup is the front door of, or null for anything deeper (`cdn.`, `api.`, tracking
     * pixels): those are page parts or background traffic, not a person opening the site. Category lists carry no
     * rule domain, so this is shape-based: a bare registrable name, optionally behind www./m./mobile.
     */
    fun frontDoorSite(qname: String): String? {
        val host = qname.trimEnd('.').lowercase()
        if (isRegistrable(host)) return host // mobile.de, m.tv: the prefix is the name itself
        val bare = FRONT_DOOR.firstOrNull { host.startsWith(it) }?.let { host.removePrefix(it) } ?: return null
        return if (isRegistrable(bare)) bare else null
    }

    /** The registrable name a host belongs to: sdk.iad-02.braze.com -> braze.com. Null for a bare label. */
    fun registrable(qname: String): String? {
        val labels = qname.trimEnd('.').lowercase().split('.')
        for (n in 3 downTo 2) { // example.co.uk before co.uk
            if (labels.size < n) continue
            val name = labels.takeLast(n).joinToString(".")
            if (isRegistrable(name)) return name
        }
        return null
    }

    private fun isRegistrable(name: String): Boolean {
        val labels = name.split('.')
        if (labels.any { it.isEmpty() }) return false
        return when (labels.size) {
            2 -> true
            // example.co.uk, example.com.au; not cdn.bet.io, where "bet" is the site and "cdn" a part of it.
            3 -> labels[2].length == 2 && labels[1] in SECOND_LEVEL
            else -> false
        }
    }

    /**
     * "Tried again and again": [count] confirmed high-risk notices of one category within [windowMs] tells partners
     * once, then stays quiet for [quietMs] for that category so one evening doesn't become a dozen emails.
     */
    class Escalation(
        private val count: Int = 3,
        private val windowMs: Long = 10 * 60_000L,
        private val quietMs: Long = 60 * 60_000L,
        /** One site counts once per this long: a tab left open re-resolves after the 60 s block TTL. */
        private val siteGapMs: Long = 5 * 60_000L,
    ) {
        private val hits = HashMap<String, ArrayDeque<Long>>()
        private val lastFired = HashMap<String, Long>()
        private val lastSite = HashMap<String, Long>()

        /**
         * Records one confirmed notice for [site]; returns how many tries are in the window when partners should be
         * told now, else null. The same site again within [siteGapMs] is the same try, not a new one.
         */
        @Synchronized
        fun hit(category: String, site: String, now: Long): Int? {
            val prevSite = lastSite[site]
            if (prevSite != null && now - prevSite in 0 until siteGapMs) return null
            lastSite[site] = now
            if (lastSite.size > 256) lastSite.entries.removeIf { now - it.value >= siteGapMs }
            val q = hits.getOrPut(category) { ArrayDeque() }
            q.addLast(now)
            while (q.isNotEmpty() && (now - q.first() >= windowMs || now < q.first())) q.removeFirst()
            if (q.size < count) return null
            val last = lastFired[category]
            if (last != null && now - last in 0 until quietMs) return null
            lastFired[category] = now
            return q.size
        }
    }
}
