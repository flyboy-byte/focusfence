package app.focusfence.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the partner-facing report from a device's uploaded activity records.
 * Mirrors the on-device report (dns/LogSummary): protection/integrity first,
 * then flagged content, then time and sites — a quiet report is meaningless if
 * protection was off, so that is stated at the top.
 */
object Report {

    data class Summary(
        val fromMs: Long,
        val toMs: Long,
        val online: Boolean,
        val lastSeenMs: Long,
        val integrityEvents: List<String>,
        val blockedByReason: Map<String, Int>,
        val flaggedShots: Int,
        val topBlockedHosts: List<Pair<String, Int>>,
        val topCategories: Map<String, Int>,
        val dnsTotals: Map<String, Int>,
    )

    private fun Map<String, JsonElement>.str(k: String): String? =
        this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun Map<String, JsonElement>.long(k: String): Long? =
        this[k]?.let { runCatching { it.jsonPrimitive.content.toLong() }.getOrNull() }

    fun build(records: List<Map<String, JsonElement>>, device: Store.Device, sinceMs: Long, staleAfterMs: Long): Summary {
        val now = System.currentTimeMillis()
        val window = records.filter { (it.long("t") ?: 0) >= sinceMs }
        val integrity = ArrayList<String>()
        val blockedBy = LinkedHashMap<String, Int>()
        val blockedHosts = HashMap<String, Int>()
        val categories = LinkedHashMap<String, Int>()
        val dnsTotals = LinkedHashMap<String, Int>()
        var flagged = 0

        for (r in window) {
            when (r.str("type")) {
                "integ" -> integrity += "${r.str("key")}=${r.str("value")} (was ${r.str("was")})"
                "svc" -> if (r.str("comp") == "vpn" && r.str("state") in setOf("stop", "revoked", "destroyed")) {
                    integrity += "vpn ${r.str("state")}"
                }
                "dns" -> {
                    val decision = r.str("decision") ?: "?"
                    dnsTotals[decision] = (dnsTotals[decision] ?: 0) + 1
                    if (decision == "blocked") {
                        val reason = r.str("reason") ?: "?"
                        blockedBy[reason] = (blockedBy[reason] ?: 0) + 1
                        r.str("host")?.let { blockedHosts[it] = (blockedHosts[it] ?: 0) + 1 }
                    }
                    r.str("cat")?.split(",")?.forEach { if (it.isNotBlank()) categories[it] = (categories[it] ?: 0) + 1 }
                }
                "shot" -> if (r.str("kept") == "true") flagged++
            }
        }
        return Summary(
            fromMs = sinceMs, toMs = now,
            online = now - device.lastSeenMs <= staleAfterMs,
            lastSeenMs = device.lastSeenMs,
            integrityEvents = integrity.takeLast(50),
            blockedByReason = blockedBy,
            flaggedShots = flagged,
            topBlockedHosts = blockedHosts.entries.sortedByDescending { it.value }.take(15).map { it.key to it.value },
            topCategories = categories,
            dnsTotals = dnsTotals,
        )
    }

    fun renderHtml(s: Summary, device: Store.Device): String {
        fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;")
        val sb = StringBuilder()
        sb.append("<h1>Focus Fence report</h1>")
        sb.append("<p>Device <code>${esc(device.id.take(8))}</code> · partner ${esc(device.partnerEmail)}</p>")
        sb.append(if (s.online) "<p style='color:green'>Protection reporting normally.</p>"
            else "<p style='color:#b00'><b>Device has not reported recently — monitoring may be off.</b></p>")
        sb.append("<h2>Integrity</h2>")
        if (s.integrityEvents.isEmpty()) sb.append("<p>No protection changes.</p>")
        else sb.append("<ul>" + s.integrityEvents.joinToString("") { "<li>${esc(it)}</li>" } + "</ul>")
        sb.append("<h2>Flagged screenshots: ${s.flaggedShots}</h2>")
        sb.append("<p>Images are end-to-end encrypted; decrypt them in your partner key, the server cannot read them.</p>")
        sb.append("<h2>Blocked</h2><ul>")
        s.blockedByReason.forEach { (k, v) -> sb.append("<li>${esc(k)}: $v</li>") }
        sb.append("</ul>")
        if (s.topCategories.isNotEmpty()) {
            sb.append("<h2>Categories seen</h2><ul>")
            s.topCategories.forEach { (k, v) -> sb.append("<li>${esc(k)}: $v</li>") }
            sb.append("</ul>")
        }
        sb.append("<h2>Top blocked hosts</h2><ul>")
        s.topBlockedHosts.forEach { (h, c) -> sb.append("<li>${esc(h)}: $c</li>") }
        sb.append("</ul>")
        return sb.toString()
    }
}
