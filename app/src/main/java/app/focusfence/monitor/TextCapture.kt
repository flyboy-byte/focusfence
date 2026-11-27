package app.focusfence.monitor

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import app.focusfence.data.EventLog
import app.focusfence.dns.LogEvents
import app.focusfence.dns.UrlDeduper

/**
 * Extracts visible on-screen text from a watched app for the accountability
 * record — the "content inside apps" signal. A bounded walk of the node tree
 * (capped at [MAX_NODES]) collects text nodes; the result is length-capped and
 * de-duplicated so the same screen isn't logged repeatedly.
 *
 * Scope is the owner's chosen watched apps only, and only while the capture
 * indicator is showing. Password fields are skipped. Like screenshots, this is
 * self-accountability data, visible to the person via the indicator.
 */
object TextCapture {

    private const val MAX_NODES = 400
    private const val MAX_TEXT_CHARS = 2000

    /** Plan 17: at most one partner alert per app in this long, however many screens of the same page scroll past. */
    private const val ALERT_EVERY_MS = 10 * 60_000L

    private val deduper = UrlDeduper()
    private val lastAlert = HashMap<String, Long>()

    /** Reads [pkg]'s screen; true when adult words were on it (the caller then checks a screenshot, plan 17). */
    fun capture(context: Context, pkg: String, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        // CPU attribution: tree walks are the costly part of text capture.
        val cpu0 = android.os.Debug.threadCpuTimeNanos()
        return try {
            captureInner(context, pkg, root)
        } finally {
            Monitor.perf.count("text_cpu_us", (android.os.Debug.threadCpuTimeNanos() - cpu0) / 1000)
        }
    }

    private fun captureInner(context: Context, pkg: String, root: AccessibilityNodeInfo): Boolean {
        val sb = StringBuilder()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || visited >= MAX_NODES || sb.length >= MAX_TEXT_CHARS) return
            visited++
            if (!node.isPassword) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    if (sb.isNotEmpty()) sb.append(" · ")
                    sb.append(t)
                }
                // Plan 17: images, video tiles and buttons carry their words here (a thumbnail's title, a post's alt
                // text), often with no text node at all.
                val d = node.contentDescription?.toString()?.trim()
                if (!d.isNullOrEmpty() && d != t) {
                    if (sb.isNotEmpty()) sb.append(" · ")
                    sb.append(d)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        Monitor.perf.count("text_nodes", visited.toLong())
        if (visited >= MAX_NODES) Monitor.perf.count("text_truncated")

        if (sb.isEmpty()) {
            Monitor.perf.count("text_empty")
            return false
        }
        val text = sb.toString().take(MAX_TEXT_CHARS)
        // Dedup keyed by a short digest so near-identical screens collapse.
        if (!deduper.accept(pkg, "text", text.hashCode().toString())) return false
        Monitor.perf.count("text_captured")
        // Second detection path: sites named on screen, checked against the bundles.
        val hits = app.focusfence.dns.Signals.categorize(
            app.focusfence.dns.Signals.hostsIn(text), app.focusfence.vpn.DnsVpnService.detectionLists,
        )
        if (hits.isNotEmpty()) {
            Monitor.perf.count("text_cat_hits")
            app.focusfence.dns.Signals.categoriesOf(hits).forEach { Monitor.perf.count("text_cat_$it") }
        }
        // Third path: risky words, no network or model needed.
        val terms = app.focusfence.dns.RiskTerms.find(text)
        if (terms.isNotEmpty()) {
            Monitor.perf.count("text_term_hits")
            terms.keys.forEach { Monitor.perf.count("text_term_$it") }
            // Adult words on screen corroborate a middling image score in this app (dns/FlagPolicy).
            if ("adult" in terms) ScreenCapture.flagPolicy.noteSignal(pkg, System.currentTimeMillis())
        }
        // Plan 17: clearly explicit words alert partners (Digest.alertText), like a flagged screenshot.
        val now = System.currentTimeMillis()
        if (app.focusfence.dns.RiskTerms.strong(terms) && now - (lastAlert[pkg] ?: 0L) >= ALERT_EVERY_MS) {
            lastAlert[pkg] = now
            Monitor.perf.count("text_alerts")
            val detail = app.focusfence.dns.RiskTerms.alertDetail(terms, app.focusfence.UiUtil.appLabel(context, pkg))
            EventLog.activity(LogEvents.service(now, "monitor", "text", detail), urgent = true)
        }
        EventLog.activity(
            LogEvents.text(
                System.currentTimeMillis(), pkg, text,
                hits = hits.takeIf { it.isNotEmpty() }?.let(app.focusfence.dns.Signals::encodeHits),
                cat = (app.focusfence.dns.Signals.categoriesOf(hits) + terms.keys).distinct().sorted()
                    .takeIf { it.isNotEmpty() }?.joinToString(","),
                terms = terms.takeIf { it.isNotEmpty() }?.let(app.focusfence.dns.RiskTerms::encode),
            )
        )
        return "adult" in terms
    }
}
