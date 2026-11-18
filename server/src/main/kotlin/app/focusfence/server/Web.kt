package app.focusfence.server

import app.focusfence.dns.Digest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The partner's web pages: joining with an invite code, and a private report
 * page per partner (the link token is the credential, like a magic link).
 * The report is the same [Digest] the partner app shows, so both partner
 * channels say the same thing. Plain HTML, no scripts.
 */
object Web {

    private val timeFmt = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm").withZone(ZoneId.systemDefault())

    fun time(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

    fun esc(x: String): String =
        x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun page(title: String, body: String): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style>
body{font-family:system-ui,sans-serif;max-width:640px;margin:0 auto;padding:16px;color:#1b1b1f;line-height:1.45}
h1{font-size:1.4em}h2{font-size:1.1em;margin-top:1.6em}
.card{border:1px solid #ddd;border-radius:12px;padding:12px 16px;margin:12px 0}
.ok{color:#1b7f3b}.bad{color:#b3261e;font-weight:600}.muted{color:#666;font-size:.9em}
input,button{font:inherit;padding:8px;margin:4px 0;border-radius:8px;border:1px solid #bbb}
button{background:#3f51b5;color:#fff;border:0;padding:8px 16px}
label{display:block;margin-top:8px}table{border-collapse:collapse}td{padding:2px 12px 2px 0}
</style></head><body>$body</body></html>"""

    fun joinForm(code: String, error: String?, smsAvailable: Boolean): String = page(
        "Become an accountability partner",
        buildString {
            append("<h1>Become an accountability partner</h1>")
            append("<p>Someone asked you to be their accountability partner on Focus Fence. Enter the invite code they sent you. You'll get a weekly report by email and an alert if their protection changes.</p>")
            if (error != null) append("<p class='bad'>${esc(error)}</p>")
            append("<form method='post' action='/join'>")
            append("<label>Invite code<br><input name='code' required value='${esc(code)}' autocapitalize='characters'></label>")
            append("<label>Your name<br><input name='name' required maxlength='60'></label>")
            append("<label>Your email<br><input name='email' type='email' required maxlength='120'></label>")
            if (smsAvailable) {
                append("<label>Mobile number for text alerts (optional)<br><input name='phone' type='tel' placeholder='+1 555 123 4567'></label>")
                append("<label><input type='checkbox' name='sms_consent' value='yes'> Text me alerts (not reports). Message rates may apply; reply STOP to opt out.</label>")
            }
            append("<p><button type='submit'>Join</button></p></form>")
        },
    )

    fun message(title: String, text: String): String =
        page(title, "<h1>${esc(title)}</h1><p>${esc(text)}</p>")

    fun partnerPage(
        partner: Store.Partner,
        deviceOnline: Boolean,
        lastSeenMs: Long,
        digest: Map<String, Any?>,
        smsAvailable: Boolean,
        notice: String?,
    ): String = page("Focus Fence report", buildString {
        val t = partner.token
        append("<h1>Focus Fence report</h1>")
        append("<p class='muted'>For ${esc(partner.name)} · last 7 days · keep this link private, it's your login.</p>")
        if (notice != null) append("<div class='card'>${esc(notice)}</div>")
        append(
            if (deviceOnline) "<p class='ok'>The phone is checking in normally (last ${time(lastSeenMs)}).</p>"
            else "<p class='bad'>The phone hasn't checked in since ${time(lastSeenMs)} — protection may be off, or the app may have been removed.</p>"
        )
        append(digestHtml(digest))
        append("<h2>Text alerts</h2>")
        when {
            !smsAvailable -> append("<p class='muted'>Text alerts aren't set up on this server.</p>")
            partner.phoneVerified -> append(
                "<p>Alerts are texted to ${esc(mask(partner.phone.orEmpty()))}.</p>" +
                    "<form method='post' action='/p/$t/sms/stop'><button>Stop texts</button></form>"
            )
            partner.phone != null -> append(
                "<p>We texted a code to ${esc(mask(partner.phone.orEmpty()))}.</p>" +
                    "<form method='post' action='/p/$t/sms/verify'><input name='code' inputmode='numeric' maxlength='6' required> <button>Confirm</button></form>"
            )
            else -> append(
                "<form method='post' action='/p/$t/sms'><input name='phone' type='tel' required placeholder='+1 555 123 4567'>" +
                    "<label><input type='checkbox' name='sms_consent' value='yes' required> Text me alerts. Reply STOP to opt out.</label>" +
                    "<button>Send code</button></form>"
            )
        }
        append("<h2>Leave</h2><form method='post' action='/p/$t/leave'><button style='background:#b3261e'>Stop being their partner</button></form>")
    })

    fun digestHtml(d: Map<String, Any?>): String = buildString {
        val off = d["off_min"] as? Long ?: 0L
        val missing = d["missing"] as? Long ?: 0L
        append("<div class='card'>")
        append(if (off == 0L) "<p class='ok'>Protection was on the whole time.</p>" else "<p class='bad'>Protection was OFF for $off minutes.</p>")
        if (missing > 0) append("<p class='bad'>$missing records are missing from the log (deleted or lost).</p>")
        val events = (d["events"] as? String).orEmpty().lines().filter { it.isNotBlank() }
        if (events.isNotEmpty()) {
            append("<p>Changes:</p><ul>")
            events.forEach { append("<li>${esc(it)}</li>") }
            append("</ul>")
        }
        append("<p class='muted'>${d["lookups"] ?: 0} lookups · ${d["blocked"] ?: 0} blocked")
        val flagged = d["flagged_shots"] as? Long ?: 0L
        if (flagged > 0) append(" · $flagged flagged screenshots (end-to-end encrypted)")
        append("</p></div>")
        table("Categories", d["cats"])
        table("Category sites", d["cat_hosts"])
        table("App time (minutes)", d["apps_min"])
        table("Browser sites", d["sites"])
        table("Top blocked", d["top_blocked"])
    }

    private fun StringBuilder.table(title: String, raw: Any?) {
        val items = Digest.parsePairs(raw as? String)
        if (items.isEmpty()) return
        append("<h2>${esc(title)}</h2><table>")
        items.forEach { (k, v) -> append("<tr><td>${esc(k)}</td><td>$v</td></tr>") }
        append("</table>")
    }

    fun mask(phoneOrEmail: String): String = when {
        '@' in phoneOrEmail -> phoneOrEmail.substringBefore('@').take(2) + "…@" + phoneOrEmail.substringAfter('@')
        phoneOrEmail.length > 4 -> "••" + phoneOrEmail.takeLast(4)
        else -> "••"
    }
}
