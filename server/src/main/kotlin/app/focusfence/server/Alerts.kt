package app.focusfence.server

import app.focusfence.dns.Digest
import app.focusfence.dns.LogJson

/**
 * Turns uploaded records into partner notifications. Which records count as an
 * alert is decided by the phone's own [Digest.alertText], so the web/email
 * channel and the app-to-app channel alert on exactly the same things.
 *
 * Email gets every alert. SMS is opt-in, verified, alerts-only, and throttled
 * per partner so a flapping setting can't run up a provider bill.
 */
class Alerts(private val store: Store, private val notifier: Notifier, private val cfg: Config) {

    fun link(p: Store.Partner) = "${cfg.publicUrl}/p/${p.token}"

    /** Backstop against using us to flood an inbox: a minimum gap and a daily cap per address. */
    private val sent = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()
    /** And a ceiling on the whole server, so many addresses can't add up to a flood. */
    private val global = RateLimiter(cfg.emailGlobalDailyCap, 24 * 3_600_000L, cfg.now)
    private fun mayEmail(address: String): Boolean {
        val now = cfg.now()
        val list = sent.computeIfAbsent(address.lowercase()) { ArrayList() }
        synchronized(list) {
            list.removeAll { now - it > 24 * 3_600_000L }
            if (list.isNotEmpty() && now - list.last() < cfg.emailMinIntervalMs) return false
            if (list.size >= cfg.emailDailyCap) return false
            if (!global.allow("all")) return false
            list += now
            return true
        }
    }

    suspend fun toPartners(device: Store.Device, subject: String, lines: List<String>) {
        if (lines.isEmpty()) return
        val now = cfg.now()
        for (p in device.partners) {
            if (!p.confirmed) continue
            val html = "<p>${Web.esc(subject)}</p><ul>" +
                lines.joinToString("") { "<li>${Web.esc(it)}</li>" } +
                "</ul><p><a href=\"${link(p)}\">Open the report</a></p>"
            if (mayEmail(p.email)) notifier.email(p.email, "Focus Fence: $subject", html)
            val phone = p.phone
            if (p.phoneVerified && phone != null && now - p.lastSmsMs >= cfg.smsMinIntervalMs) {
                val more = if (lines.size > 1) " (+${lines.size - 1} more)" else ""
                if (notifier.sms(phone, "Focus Fence: ${lines.first()}$more. ${link(p)}")) {
                    store.updateDevice(device.id) { d ->
                        d.partners.firstOrNull { it.id == p.id }?.lastSmsMs = now
                    }
                }
            }
        }
    }

    /** Alert lines for a raw uploaded batch. */
    fun alertLines(recordsRaw: String): List<String> =
        recordsRaw.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { LogJson.decode(it)?.let(Digest::alertText) }
            .toList()

    fun digest(deviceId: String, sinceMs: Long, toMs: Long): Map<String, Any?> {
        val d = Digest(sinceMs)
        for (line in store.readRecords(deviceId)) LogJson.decode(line)?.let(d::add)
        return d.build(toMs)
    }

    /** One pass of the background checks: silent devices and weekly reports. */
    suspend fun sweep() {
        val now = cfg.now()
        for (d in store.allDevices()) {
            if (d.partners.any { !it.confirmed && now - it.linkedMs > cfg.unconfirmedTtlMs }) {
                store.updateDevice(d.id) { dev -> dev.partners = dev.partners.filter { it.confirmed || now - it.linkedMs <= cfg.unconfirmedTtlMs } }
            }
            if (d.partners.none { it.confirmed }) continue
            if (!d.staleAlerted && now - d.lastSeenMs > cfg.staleMs) {
                store.updateDevice(d.id) { it.staleAlerted = true }
                toPartners(
                    d, "phone went silent",
                    listOf("The phone hasn't checked in for ${(now - d.lastSeenMs) / 60_000} minutes. Protection may be off, the phone may be off, or the app may have been removed."),
                )
            }
            if (now - d.lastReportMs >= cfg.reportEveryMs) {
                store.updateDevice(d.id) { it.lastReportMs = now }
                val digest = digest(d.id, now - cfg.reportEveryMs, now)
                for (p in d.partners.filter { it.confirmed }) {
                    if (mayEmail(p.email)) notifier.email(
                        p.email, "Focus Fence: weekly report",
                        "${Web.digestHtml(digest)}<p><a href=\"${link(p)}\">Open the full report</a></p>",
                    )
                }
            }
        }
    }
}
