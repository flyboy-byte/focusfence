package app.focusfence.dns

import java.net.URLEncoder

/**
 * Settings → Report a bug (2026-11-25): a link to GitHub's issue form, filled in with versions and, if the owner ticks
 * it, a numbers-only summary. The repository is public, so nothing here may name a site, an app, a person, a server
 * address or a partner: only versions, on/off states, rates and counts of error codes. The owner sees the summary
 * before it goes, and the app sends nothing itself: the browser opens GitHub and the owner submits there.
 */
object BugReport {
    const val TEMPLATE = "bug_report.yml"

    data class Info(
        val appVersion: String,
        val protocol: Int,
        val serverProtocol: String,
        val android: String,
        val device: String,
    )

    data class Numbers(
        val filterOn: Boolean,
        val alwaysOn: String,
        val accessibility: Boolean,
        val strict: Boolean,
        val screenChecks: Boolean,
        val ai: Boolean,
        val onServer: Boolean,
        val screenOnPctPerHour: Double?,
        val screenOffPctPerHour: Double?,
        val cpuMsPerHourOn: Double?,
        val forwardFailurePct: Double?,
        val latencyP95Ms: Long?,
        /** "component:code" → count, from the diagnostics log's err records. */
        val errors: Map<String, Long>,
        val days: Int,
    )

    fun summary(n: Numbers): String = buildString {
        fun onOff(b: Boolean) = if (b) "on" else "off"
        appendLine("filter ${onOff(n.filterOn)}, always-on ${n.alwaysOn}, accessibility ${onOff(n.accessibility)}, strict ${onOff(n.strict)}")
        appendLine("screen checks ${onOff(n.screenChecks)}, AI ${onOff(n.ai)}, on a server: ${if (n.onServer) "yes" else "no"}")
        appendLine("last ${n.days} days:")
        n.screenOnPctPerHour?.let { appendLine("  battery screen on ${"%.1f".format(java.util.Locale.ROOT, it)} %/h, screen off ${"%.1f".format(java.util.Locale.ROOT, n.screenOffPctPerHour ?: 0.0)} %/h") }
        n.cpuMsPerHourOn?.let { appendLine("  app CPU screen on ${it.toLong()} ms/h") }
        n.forwardFailurePct?.let { appendLine("  DNS forward failures ${"%.1f".format(java.util.Locale.ROOT, it)} %") }
        n.latencyP95Ms?.let { appendLine("  DNS p95 latency $it ms") }
        if (n.errors.isEmpty()) appendLine("  no error records")
        else n.errors.entries.sortedByDescending { it.value }.take(10).forEach { (k, v) -> appendLine("  error $k ×$v") }
    }.trimEnd()

    /** The new-issue link: GitHub fills the form's fields from these query parameters (by field id). */
    fun url(repo: String, info: Info, summary: String?): String {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        val params = linkedMapOf(
            "template" to TEMPLATE,
            "labels" to "bug",
            "version" to "${info.appVersion} (protocol ${info.protocol}, server ${info.serverProtocol})",
            "device" to "${info.device}, Android ${info.android}",
        )
        if (!summary.isNullOrBlank()) params["summary"] = summary
        return "https://github.com/$repo/issues/new?" + params.entries.joinToString("&") { (k, v) -> "$k=${q(v)}" }
    }
}
