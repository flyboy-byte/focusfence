package app.focusfence.dns

/**
 * Streaming summary of the activity and diag logs: feed decoded lines with
 * [add], then read the fields or [render] a plain-text report. Shared by the
 * on-device diagnostics screen; `tools/analyze_logs.py` computes the same
 * figures off-device. Pure JVM so the numbers are unit-tested.
 */
class LogSummary(private val sinceT: Long = Long.MIN_VALUE) {

    val typeCounts = LinkedHashMap<String, Long>()
    val dnsDecisions = LinkedHashMap<String, Long>()
    val blockReasons = LinkedHashMap<String, Long>()
    private val allowedHosts = HashMap<String, Long>()
    private val blockedHosts = HashMap<String, Long>()
    private val urlHosts = HashMap<String, Long>()
    private val appForeground = HashMap<String, Long>()
    val perfTotals = LinkedHashMap<String, Long>()
    private val windowP95 = ArrayList<Long>()
    var perfWindowMs = 0L
        private set
    val integrity = ArrayList<String>()
    val services = ArrayList<String>()
    val errors = LinkedHashMap<String, Long>()
    private val battery = ArrayList<BatterySample>()
    var firstT = Long.MAX_VALUE
        private set
    var lastT = Long.MIN_VALUE
        private set

    fun add(f: Map<String, Any?>) {
        val t = f["t"] as? Long ?: return
        if (t < sinceT) return
        val type = f["type"] as? String ?: return
        if (t < firstT) firstT = t
        if (t > lastT) lastT = t
        typeCounts.inc(type)
        when (type) {
            LogEvents.TYPE_DNS -> {
                val decision = f["decision"] as? String ?: "?"
                val host = f["host"] as? String ?: "?"
                dnsDecisions.inc(decision)
                if (decision == LogEvents.DECISION_BLOCKED) {
                    blockReasons.inc(f["reason"] as? String ?: "?")
                    blockedHosts.inc(host)
                } else {
                    allowedHosts.inc(host)
                }
            }
            LogEvents.TYPE_URL -> urlHosts.inc(f["host"] as? String ?: "?")
            LogEvents.TYPE_APP -> if (f["state"] == "fg") appForeground.inc(f["pkg"] as? String ?: "?")
            LogEvents.TYPE_INTEGRITY ->
                integrity += "${f["key"]}=${f["value"]} (was ${f["was"]}) @$t"
            LogEvents.TYPE_SERVICE ->
                services += "${f["comp"]}:${f["state"]}${f["detail"]?.let { " [$it]" } ?: ""} @$t"
            LogEvents.TYPE_ERROR -> errors.inc("${f["comp"]}:${f["what"]}")
            LogEvents.TYPE_BATTERY -> BatterySample.fromLog(f)?.let { battery += it }
            LogEvents.TYPE_PERF -> {
                perfWindowMs += f["win"] as? Long ?: 0L
                (f["p95"] as? Long)?.let { windowP95 += it }
                for ((k, v) in f) {
                    if (k in PERF_NON_COUNTERS) continue
                    (v as? Long)?.let { perfTotals[k] = (perfTotals[k] ?: 0L) + it }
                }
            }
        }
    }

    fun battery(): BatterySummary.Result = BatterySummary.summarize(battery)

    fun topAllowed(n: Int) = top(allowedHosts, n)
    fun topBlocked(n: Int) = top(blockedHosts, n)
    fun topUrlHosts(n: Int) = top(urlHosts, n)
    fun topApps(n: Int) = top(appForeground, n)

    /** Median of per-window p95 latencies, and the worst window. */
    fun latencyP95(): Pair<Long, Long>? {
        if (windowP95.isEmpty()) return null
        val sorted = windowP95.sorted()
        return sorted[sorted.size / 2] to sorted.last()
    }

    /** Fraction of forwarded queries that got no answer from any resolver. */
    fun forwardFailureRate(): Double? {
        val ok = perfTotals["fwd"] ?: 0L
        val fail = perfTotals["fwd_fail"] ?: 0L
        return if (ok + fail == 0L) null else fail.toDouble() / (ok + fail)
    }

    /** Fraction of browser address-bar reads that found no URL. */
    fun urlMissRate(): Double? {
        val ok = perfTotals["url_read"] ?: 0L
        val miss = perfTotals["url_miss"] ?: 0L
        return if (ok + miss == 0L) null else miss.toDouble() / (ok + miss)
    }

    fun render(): String = buildString {
        val hours = if (lastT > firstT) (lastT - firstT) / 3_600_000.0 else 0.0
        appendLine("span          ${fmt(hours)} h")
        appendLine("records       ${typeCounts.entries.joinToString { "${it.key}=${it.value}" }}")
        appendLine()
        appendLine("== battery (Focus Fence process, while a service ran) ==")
        val b = battery()
        appendLine("screen on     ${fmt(b.screenOn.hours)} h  ${fmt(b.screenOn.pctPerHour)} %/h  cpu ${fmt(b.screenOn.cpuMsPerHour)} ms/h")
        appendLine("screen off    ${fmt(b.screenOff.hours)} h  ${fmt(b.screenOff.pctPerHour)} %/h  cpu ${fmt(b.screenOff.cpuMsPerHour)} ms/h")
        appendLine("excluded      ${fmt(b.excludedHours)} h (charging, reboots, gaps)   samples ${b.samples}")
        appendLine("note          %/h is whole-device drain, not this app's share; cpu is this app only")
        appendLine()
        appendLine("== how well it works (diag perf) ==")
        appendLine("queries       ${perfTotals["q"] ?: 0}  forwarded ${perfTotals["fwd"] ?: 0}")
        appendLine("fwd failure   ${pct(forwardFailureRate())}  failovers ${perfTotals["fwd_failover"] ?: 0}")
        latencyP95()?.let { appendLine("latency p95   median ${it.first} ms, worst window ${it.second} ms") }
        appendLine("a11y events   ${perfTotals["a11y_ev"] ?: 0}  url miss ${pct(urlMissRate())}")
        appendLine("log dropped   ${perfTotals["log_dropped"] ?: 0}")
        val clf = perfTotals["clf"] ?: 0L
        if (clf > 0 || (perfTotals["shot"] ?: 0L) > 0) {
            // The AI is the costly part of capture; show its share so the owner can judge it.
            val ms = perfTotals["clf_ms"] ?: 0L
            appendLine(
                "ai flagging   ${clf} screenshots scored, avg ${if (clf > 0) ms / clf else 0} ms, " +
                    "total ${ms / 1000} s cpu, model load ${(perfTotals["clf_load_ms"] ?: 0L)} ms" +
                    if ((perfTotals["clf_load_fail"] ?: 0L) > 0) ", LOAD FAILED" else ""
            )
            appendLine("screenshots   ${perfTotals["shot"] ?: 0} taken, ${perfTotals["shot_flagged"] ?: 0} flagged, ${perfTotals["shot_fail"] ?: 0} failed")
        }
        if (errors.isNotEmpty()) appendLine("errors        ${errors.entries.joinToString { "${it.key}=${it.value}" }}")
        appendLine()
        appendLine("== detection (activity) ==")
        appendLine("dns           ${dnsDecisions.entries.joinToString { "${it.key}=${it.value}" }}")
        appendLine("blocked by    ${blockReasons.entries.joinToString { "${it.key}=${it.value}" }}")
        appendTop("top blocked", topBlocked(10))
        appendTop("top allowed", topAllowed(10))
        appendTop("top url host", topUrlHosts(10))
        appendTop("top apps", topApps(10))
        appendLine()
        appendLine("== integrity / services (latest last) ==")
        for (line in (integrity.takeLast(15))) appendLine("integ  $line")
        for (line in (services.takeLast(15))) appendLine("svc    $line")
    }

    private fun StringBuilder.appendTop(label: String, items: List<Pair<String, Long>>) {
        if (items.isEmpty()) return
        appendLine(label)
        for ((k, v) in items) appendLine("  %6d  %s".format(v, k))
    }

    private fun <K> MutableMap<K, Long>.inc(k: K) {
        this[k] = (this[k] ?: 0L) + 1
    }

    private fun top(m: Map<String, Long>, n: Int): List<Pair<String, Long>> =
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    private fun fmt(d: Double) = "%.2f".format(d)
    private fun pct(d: Double?) = if (d == null) "n/a" else "%.1f%%".format(d * 100)

    private companion object {
        val PERF_NON_COUNTERS = setOf("seq", "t", "type", "win", "p50", "p95", "p99", "max")
    }
}
