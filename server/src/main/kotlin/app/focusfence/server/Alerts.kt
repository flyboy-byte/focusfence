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

    /**
     * [minor] holds the indices of housekeeping lines ([app.focusfence.dns.AlertPrefs.isImportant] false): a partner who
     * chose fewer alerts gets those in the daily message, or not at all (plan 12 §5).
     */
    suspend fun toPartners(device: Store.Device, subject: String, lines: List<String>, minor: Set<Int> = emptySet()) {
        if (lines.isEmpty()) return
        val now = cfg.now()
        // App partners poll this (/p/{token}/alerts) and apply their own choices; email and text go out below.
        store.addAlerts(device.id, lines, now, minor)
        for (partner in device.partners) {
            if (!partner.confirmed) continue
            val prefs = partner.prefs()
            val hour = app.focusfence.dns.AlertPrefs.hour(prefs, now)
            val routes = lines.indices.map { app.focusfence.dns.AlertPrefs.route(prefs, it !in minor, hour) }
            val hold = lines.filterIndexed { i, _ -> routes[i] == app.focusfence.dns.AlertPrefs.Route.HOLD }
            if (hold.isNotEmpty()) store.updateDevice(device.id) { d ->
                d.partners.firstOrNull { it.id == partner.id }?.let { it.held = (it.held + hold.map { l -> Store.AlertEntry(now, l) }).takeLast(HELD_KEEP) }
            }
            val sendNow = lines.filterIndexed { i, _ -> routes[i] == app.focusfence.dns.AlertPrefs.Route.NOW }
            if (sendNow.isNotEmpty()) sendLines(device.id, partner, subject, sendNow, now)
        }
    }

    private suspend fun sendLines(deviceId: String, p: Store.Partner, subject: String, lines: List<String>, now: Long) {
        val html = "<p>${Web.esc(subject)}</p><ul>" +
            lines.joinToString("") { "<li>${Web.esc(it)}</li>" } +
            "</ul><p><a href=\"${link(p)}\">Open the report</a>. If it says the key is missing, open the Focus Fence link the phone's owner sent you instead: that one always works.</p>"
        if (mayEmail(p.email)) notifier.email(p.email, "Focus Fence: $subject", html)
        val phone = p.phone
        if (p.phoneVerified && phone != null && now - p.lastSmsMs >= cfg.smsMinIntervalMs) {
            val more = if (lines.size > 1) " (+${lines.size - 1} more)" else ""
            if (notifier.sms(phone, "Focus Fence: ${lines.first()}$more. ${link(p)}")) {
                store.updateDevice(deviceId) { d -> d.partners.firstOrNull { it.id == p.id }?.lastSmsMs = now }
            }
        }
    }

    /** Plan 12 §5: what each partner was holding goes out together, once a day at 7 their time, never inside quiet hours. */
    private suspend fun flushHeld(d: Store.Device, now: Long) {
        for (p in d.partners) {
            if (!p.confirmed || p.held.isEmpty()) continue
            val prefs = p.prefs()
            val day = app.focusfence.dns.AlertPrefs.day(prefs, now)
            if (!app.focusfence.dns.AlertPrefs.flushDue(prefs, app.focusfence.dns.AlertPrefs.hour(prefs, now), day, p.lastFlushDay, true)) continue
            val lines = p.held.map { "${Web.time(it.t)}: ${it.text}" }
            store.updateDevice(d.id) { dev -> dev.partners.firstOrNull { it.id == p.id }?.apply { held = emptyList(); lastFlushDay = day } }
            sendLines(d.id, p, "${lines.size} alert${if (lines.size == 1) "" else "s"} held for you", lines, now)
        }
    }

    /** Alert lines for a raw uploaded batch. */
    fun alertLines(recordsRaw: String): List<String> =
        recordsRaw.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { LogJson.decode(it)?.let(Digest::alertText) }
            .toList()

    /** The phone checked in after partners were told it was silent: say so, so the silence isn't left hanging. */
    suspend fun checkedIn(deviceId: String, account: String? = null) {
        val silentSince = store.markSeen(deviceId, cfg.now()) ?: return
        val device = store.device(deviceId) ?: return
        val back = "The phone is checking in again after ${minutes(cfg.now() - silentSince)} without a check-in."
        toPartners(device, "phone is back", listOf(if (account != null) "$back $account" else back), minor = setOf(0))
    }

    /** One pass of the background checks: silent devices and weekly reports. */
    suspend fun sweep() {
        val now = cfg.now()
        for (d in store.allDevices()) {
            flushHeld(d, now)
            if (d.partners.any { !it.confirmed && now - it.linkedMs > cfg.unconfirmedTtlMs }) {
                store.updateDevice(d.id) { dev -> dev.partners = dev.partners.filter { it.confirmed || now - it.linkedMs <= cfg.unconfirmedTtlMs } }
            }
            if (d.partners.none { it.confirmed } && d.appPartners.isEmpty()) continue
            // The dead man's switch: the phone checks in about every 15 minutes (measured 2026-11-25), so silence past
            // cfg.staleMs means several missed check-ins, not one slow one.
            if (!d.staleAlerted && now - d.lastSeenMs > cfg.staleMs) {
                store.updateDevice(d.id) { it.staleAlerted = true }
                toPartners(
                    d, "phone went silent",
                    listOf("The phone hasn't checked in for ${minutes(now - d.lastSeenMs)}. Protection may be off, the phone may be off or out of signal, or the app may have been removed. If it reconnects, you'll be told what happened meanwhile."),
                )
            }
            if (now - d.lastReportMs >= cfg.reportEveryMs) {
                store.updateDevice(d.id) { it.lastReportMs = now }
                // D-023: the server can't read the report, so the email is only a pointer to it.
                for (p in d.partners.filter { it.confirmed }) {
                    if (mayEmail(p.email)) notifier.email(
                        p.email, "Focus Fence: weekly report",
                        "<p>This week's Focus Fence report is ready. It is end-to-end encrypted, so it opens in your browser, " +
                            "not in this email.</p><p><a href=\"${link(p)}\">Open the report</a>. If it says the key is missing, open the Focus Fence link the phone's owner sent you instead: that one always works.</p>",
                    )
                }
            }
        }
    }

    companion object {
        /** Held alerts per partner: a week of a busy phone still fits; the report has the rest. */
        const val HELD_KEEP = 50

        /** "50 minutes", "3 h 5 min". */
        fun minutes(ms: Long): String {
            val m = ms / 60_000
            return if (m < 90) "$m minutes" else "${m / 60} h ${m % 60} min"
        }
    }
}
