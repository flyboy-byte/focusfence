package app.focusfence.dns

/**
 * The partner-facing summary of a stretch of the activity log, sent app-to-app
 * through the blind mailbox (see [Pairing]). It is built on the phone, from the
 * same records the phone keeps, and only the summary leaves — never raw lines.
 *
 * Protection comes first on purpose: a quiet report means nothing if filtering
 * was off, so off-time, integrity changes and record gaps lead.
 *
 * Messages are flat [LogJson] objects. List-valued fields are encoded as lines
 * ("key\tcount" per line) so the one tiny codec covers both ends. Pure JVM.
 */
class Digest(private val fromT: Long) {

    private var lookups = 0L
    private var blocked = 0L
    private val blockedBy = LinkedHashMap<String, Long>()
    private val categories = LinkedHashMap<String, Long>()
    private val categoryHosts = HashMap<String, Long>()
    private val blockedHosts = HashMap<String, Long>()
    private val sites = HashMap<String, Long>()
    private val appMs = HashMap<String, Long>()
    private val appSince = HashMap<String, Long>()
    private val events = ArrayList<String>()
    private var enforcements = 0L
    private var flaggedShots = 0L
    private var firstSeq = -1L
    private var lastSeq = -1L
    private var missing = 0L
    private var offSince = -1L
    private var offMs = 0L
    /** Times the network went bad (degraded / upstream down): one count, not a line each. */
    private var networkTrouble = 0L
    private var lastT = fromT

    fun add(f: Map<String, Any?>) {
        val t = f["t"] as? Long ?: return
        if (t < fromT) return
        if (t > lastT) lastT = t
        (f["seq"] as? Long)?.let { seq ->
            if (firstSeq < 0) firstSeq = seq
            if (lastSeq >= 0 && seq > lastSeq + 1) missing += seq - lastSeq - 1
            if (seq > lastSeq) lastSeq = seq
        }
        when (f["type"]) {
            LogEvents.TYPE_DNS -> {
                lookups++
                val host = f["host"] as? String ?: return
                val cats = (f["cat"] as? String)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                for (c in cats) categories.inc(c)
                if (cats.isNotEmpty()) categoryHosts.inc("$host [${cats.joinToString(",")}]")
                if (f["decision"] == "blocked") {
                    blocked++
                    blockedBy.inc(f["reason"] as? String ?: "?")
                    blockedHosts.inc(host)
                }
            }
            LogEvents.TYPE_URL -> (f["host"] as? String)?.let { host ->
                sites.inc(host)
                // Address-bar hosts matching a bundle count like DNS category hits.
                val cats = (f["cat"] as? String)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                for (c in cats) categories.inc(c)
                if (cats.isNotEmpty()) categoryHosts.inc("$host [${cats.joinToString(",")}] (browser)")
            }
            LogEvents.TYPE_TEXT -> {
                // Sites named on screen that match a bundle: shown, not necessarily visited.
                val hits = (f["hits"] as? String).orEmpty()
                for (h in hits.split(';').filter { it.isNotBlank() }) {
                    val host = h.substringBefore(':')
                    val cats = h.substringAfter(':', "").split(',').filter { it.isNotBlank() }
                    for (c in cats) categories.inc(c)
                    categoryHosts.inc("$host [${cats.joinToString(",")}] (on screen)")
                }
                // Risky words: counted per category, shown as the words themselves.
                for (part in (f["terms"] as? String).orEmpty().split(';').filter { it.isNotBlank() }) {
                    val cat = part.substringBefore(':')
                    categories.inc(cat)
                    categoryHosts.inc("\"${part.substringAfter(':')}\" [$cat] (words on screen)")
                }
            }
            LogEvents.TYPE_APP -> {
                val pkg = f["pkg"] as? String ?: return
                if (f["state"] == "fg") {
                    appSince[pkg] = t
                } else {
                    appSince.remove(pkg)?.let { start ->
                        // Cap one span at 4 h: a missed "bg" must not invent a day of use.
                        appMs[pkg] = (appMs[pkg] ?: 0L) + (t - start).coerceIn(0L, 4 * 3_600_000L)
                    }
                }
            }
            LogEvents.TYPE_ENFORCE -> enforcements++
            LogEvents.TYPE_SHOT -> if (f["kept"] == true) flaggedShots++
            LogEvents.TYPE_SERVICE -> {
                if (f["comp"] == "vpn") {
                    when (f["state"]) {
                        "start" -> if (offSince >= 0) {
                            offMs += t - offSince
                            offSince = -1
                        }
                        "stop" -> if (f["detail"] != "restart" && offSince < 0) offSince = t
                        "revoked", "destroyed", "establish_failed" -> if (offSince < 0) offSince = t
                    }
                }
                if (f["comp"] == "vpn" && f["state"] == "health" && healthNow(f) in NETWORK_HEALTH) {
                    networkTrouble++
                } else {
                    alertText(f)?.let { events += it }
                }
            }
            LogEvents.TYPE_INTEGRITY -> alertText(f)?.let { events += it }
        }
    }

    /** The digest message covering [fromT]..[toT]. */
    fun build(toT: Long): Map<String, Any?> {
        val stillOff = if (offSince >= 0) toT - offSince else 0L
        return linkedMapOf(
            "kind" to KIND_DIGEST,
            "v" to 1,
            "from" to fromT,
            "to" to toT,
            "off_min" to (offMs + stillOff) / 60_000,
            "missing" to missing,
            "net_trouble" to networkTrouble,
            "seq_first" to firstSeq,
            "seq_last" to lastSeq,
            "events" to events.takeLast(MAX_EVENTS).joinToString("\n"),
            "lookups" to lookups,
            "blocked" to blocked,
            "blocked_by" to pairs(blockedBy.entries.map { it.key to it.value }),
            "cats" to pairs(categories.entries.map { it.key to it.value }),
            "cat_hosts" to pairs(top(categoryHosts, TOP)),
            "top_blocked" to pairs(top(blockedHosts, TOP)),
            "sites" to pairs(top(sites, TOP)),
            "apps_min" to pairs(top(appMs, TOP).map { it.first to it.second / 60_000 }.filter { it.second > 0 }),
            "enforce" to enforcements,
            "flagged_shots" to flaggedShots,
        )
    }

    private fun MutableMap<String, Long>.inc(k: String) {
        this[k] = (this[k] ?: 0L) + 1
    }

    companion object {
        const val KIND_DIGEST = "digest"
        const val KIND_ALERT = "alert"
        private const val TOP = 15
        private const val MAX_EVENTS = 50

        /**
         * Whether [f] (already given [alertText]) is worth an instant email/notification, not just a line in the report.
         * Routine things stay report-only (2026-11-24, Logan: partners were mailed on every app update and whenever
         * mobile DNS got flaky): an update to a newer version, and network-quality health states. A downgrade, and
         * the health states that mean the filter is bypassed or not seeing traffic, still go out at once.
         */
        fun isUrgent(f: Map<String, Any?>): Boolean {
            if (f["type"] != LogEvents.TYPE_SERVICE) return true
            val comp = f["comp"]; val state = f["state"]
            if (comp == "app" && state == "updated") {
                val d = f["detail"] as? String ?: return true
                val from = d.substringAfter("from=", "").substringBefore(',')
                val to = d.substringAfter("to=", "").substringBefore(',')
                return compareVersions(to, from) < 0 // only a downgrade is urgent
            }
            if (comp == "vpn" && state == "health") {
                val now = (f["detail"] as? String)?.substringBefore(',')
                return now !in NETWORK_HEALTH
            }
            return true
        }

        /** The alert text if [f] should be sent right away, else null. */
        fun urgentText(f: Map<String, Any?>): String? = alertText(f)?.takeIf { isUrgent(f) }

        private val NETWORK_HEALTH = setOf("degraded", "upstream_down")

        private fun healthNow(f: Map<String, Any?>): String = (f["detail"] as? String).orEmpty().substringBefore(',')

        /** Compares "2.11.0-accountable"-style versions by their numeric parts; unparseable counts as equal. */
        fun compareVersions(a: String, b: String): Int {
            val x = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: return 0 }
            val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: return 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        /**
         * Human text for a record a partner should know about, or null. It decides the report's "events"; instant
         * alerts are the subset [isUrgent] passes ([urgentText]).
         */
        fun alertText(f: Map<String, Any?>): String? {
            val type = f["type"]
            if (type == LogEvents.TYPE_INTEGRITY) {
                val key = f["key"] as? String ?: return null
                val was = f["was"] as? String ?: return null // first observation, not a change
                // Billing hint, not protection: it flips true -> no_vpn -> true for a second on every app
                // update (the tunnel is down while the package is replaced) and mailed partners "Filter
                // turned off" for a routine update. Real outages are still the vpn svc records above.
                if (key == "vpn_metered") return null
                return "$key changed: $was → ${f["value"]}"
            }
            if (type != LogEvents.TYPE_SERVICE) return null
            val comp = f["comp"]
            val state = f["state"] as? String ?: return null
            return when {
                comp == "vpn" && state == "stop" -> if (f["detail"] == "restart") null else "Filter turned off"
                comp == "vpn" && state == "revoked" -> "Filter VPN revoked or replaced by another VPN"
                comp == "vpn" && state == "establish_failed" -> "Filter failed to start"
                comp == "vpn" && state == "health" -> {
                    val d = f["detail"] as? String ?: return null
                    val now = d.substringBefore(',')
                    if (now in PROBLEM_HEALTH) "Filter problem: $now" else null
                }
                comp == "admin" && state.startsWith("admin_disable") -> "Uninstall protection being removed"
                comp == "admin" && state == "admin_enabled" -> "Uninstall protection turned on"
                comp == "admin" -> "Managed mode: $state" + detail(f)
                comp == "strict" -> when (state) {
                    "unlock_requested" -> "Strict mode: unlock requested" + detail(f)
                    "unlock_open" -> "Strict mode: unlock window opened after the wait"
                    "unlocked" -> "Strict mode: unlocked with a code" + detail(f)
                    "partner_override", "own_pin_override" -> "Strict mode: a protected setting was changed with a code" + detail(f)
                    "disabled" -> "Strict mode turned off"
                    "enabled" -> "Strict mode turned on"
                    else -> null // "relocked": the owner cancelled their own request
                }
                comp == "app" && state == "updated" -> "App updated" + detail(f)
                comp == "log" && state == "cleared" -> "Activity log cleared"
                comp == "log" && state == "setting" -> "Recording setting changed" + detail(f)
                comp == "partner" && (state == "partner_removed" || state == "pairing_removed") ->
                    "A partner was removed" + detail(f)
                // the group's own change log: joins, leaves, removals, role and visibility changes
                comp == "partner" && state == "shot_shared" ->
                    "Shared a blurred screenshot with you" + detail(f) + ". Open the report to see it."
                comp == "partner" && state == "group_notice" -> "Group: " + ((f["detail"] as? String) ?: "membership changed")
                comp == "partner" && (state == "group_left" || state == "group_viewers" || state == "group_role" || state == "group_remove") ->
                    "Group change made on this phone" + detail(f)
                else -> null
            }
        }

        private val PROBLEM_HEALTH = setOf("degraded", "upstream_down", "silent", "bypassed", "lockdown")

        private fun detail(f: Map<String, Any?>): String =
            (f["detail"] as? String)?.takeIf { it.isNotBlank() && it != "user" }?.let { " ($it)" } ?: ""

        fun alert(t: Long, text: String): Map<String, Any?> =
            linkedMapOf("kind" to KIND_ALERT, "v" to 1, "t" to t, "text" to text)

        fun pairs(items: List<Pair<String, Long>>): String =
            items.joinToString("\n") { "${it.first.replace('\t', ' ').replace('\n', ' ')}\t${it.second}" }

        fun parsePairs(s: String?): List<Pair<String, Long>> =
            s.orEmpty().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val i = line.lastIndexOf('\t')
                if (i < 0) null else line.substring(0, i) to (line.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
            }.toList()

        private fun top(m: Map<String, Long>, n: Int): List<Pair<String, Long>> =
            m.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

        /** Plain-text rendering for the partner's report screen. */
        fun render(d: Map<String, Any?>, formatTime: (Long) -> String): String = buildString {
            val off = d["off_min"] as? Long ?: 0L
            val missing = d["missing"] as? Long ?: 0L
            append("${formatTime(d["from"] as? Long ?: 0)} – ${formatTime(d["to"] as? Long ?: 0)}\n\n")
            append(if (off == 0L) "Protection: on the whole time\n" else "Protection: OFF for $off min\n")
            if (missing > 0) append("⚠ $missing records missing from the log (deleted or lost)\n")
            val net = d["net_trouble"] as? Long ?: 0L
            if (net > 0) append("Bad network $net times (the connection, not the filter)\n")
            val events = (d["events"] as? String).orEmpty().lines().filter { it.isNotBlank() }
            if (events.isNotEmpty()) {
                append("\nChanges:\n")
                events.forEach { append("• ").append(it).append('\n') }
            }
            append("\nLookups ${d["lookups"] ?: 0}, blocked ${d["blocked"] ?: 0}")
            val flagged = d["flagged_shots"] as? Long ?: 0L
            if (flagged > 0) append(", flagged screenshots $flagged")
            append('\n')
            section("Categories", d["cats"])
            section("Category sites", d["cat_hosts"])
            section("App time (min)", d["apps_min"])
            section("Browser sites", d["sites"])
            section("Top blocked", d["top_blocked"])
        }

        private fun StringBuilder.section(title: String, raw: Any?) {
            val items = parsePairs(raw as? String)
            if (items.isEmpty()) return
            append("\n$title:\n")
            items.forEach { (k, v) -> append("  $k  $v\n") }
        }
    }
}
