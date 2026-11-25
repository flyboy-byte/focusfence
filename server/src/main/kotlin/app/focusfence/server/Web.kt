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
        x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * What the person who just joined sees. It deliberately carries NO token that grants anything: the confirmation
     * link exists only in the email, so only the owner of the address can confirm it. The one control is "send it
     * again", keyed by the partner id (which can't confirm, read a report or do anything else).
     */
    fun joinPending(partnerId: String, notice: String? = null): String = page(
        "Check your email",
        "<h1>Check your email</h1><p>We sent a confirmation link to the address you entered. Open it and press the " +
            "button to start getting alerts. Until then nothing else is sent to that address, and the request expires in a few days.</p>" +
            (if (notice != null) "<div class='card'>${esc(notice)}</div>" else "") +
            "<form method=\"post\" action=\"/join/resend/${esc(partnerId)}\"><button type=\"submit\">Send the email again</button></form>",
    )

    /** The page behind the emailed confirm link: a button, because confirming must be a deliberate POST. */
    fun confirmForm(token: String): String = page(
        "Confirm your email",
        "<p>Confirm that you want to be this person's accountability partner and get their alerts and weekly report by email.</p>" +
            "<form method=\"post\" action=\"/confirm/${esc(token)}\"><button type=\"submit\">Yes, confirm</button></form>",
    )

    /** [script] names a page for `/static/report.js` (D-023); pages without it carry no script at all. */
    fun page(title: String, body: String, script: String? = null): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>${if (script != null) "<script src=\"/static/report.js\" defer></script>" else ""}
<style>
body{font-family:system-ui,sans-serif;max-width:640px;margin:0 auto;padding:16px;color:#1b1b1f;line-height:1.45}
h1{font-size:1.4em}h2{font-size:1.1em;margin-top:1.6em}
.card{border:1px solid #ddd;border-radius:12px;padding:12px 16px;margin:12px 0}
.ok{color:#1b7f3b}.bad{color:#b3261e;font-weight:600}.muted{color:#666;font-size:.9em}
input,button{font:inherit;padding:8px;margin:4px 0;border-radius:8px;border:1px solid #bbb}
button{background:#3f51b5;color:#fff;border:0;padding:8px 16px}
label{display:block;margin-top:8px}table{border-collapse:collapse}td{padding:2px 12px 2px 0}
.seg{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0}.seg button{background:transparent;color:inherit;border:1px solid #bbb;border-radius:999px;padding:6px 14px}
.seg button.on{background:#3f51b5;border-color:#3f51b5;color:#fff}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;margin:8px 0}
.stat{border:1px solid #ddd;border-radius:12px;padding:10px 12px}.stat b{display:block;font-size:1.4em}
table.sort{width:100%}table.sort th{text-align:left;cursor:pointer;padding:6px 12px 6px 0;border-bottom:1px solid #ddd;white-space:nowrap}
table.sort td{padding:6px 12px 6px 0;border-bottom:1px solid #eee;overflow-wrap:anywhere}table.sort td.n,table.sort th.n{text-align:right}
.filter{width:100%;box-sizing:border-box}.day{margin:14px 0 4px;font-weight:600}
@media (prefers-color-scheme:dark){body{background:#131316;color:#e4e1e6}.card,.stat{border-color:#3a3a40}table.sort th,table.sort td{border-color:#2e2e33}
.muted{color:#a0a0a8}input{background:#1e1e22;color:inherit;border-color:#444}.ok{color:#6fd08c}.bad{color:#ffb4ab}}
</style></head><body${if (script != null) " data-page=\"$script\"" else ""}>$body</body></html>"""

    fun joinForm(code: String, error: String?, smsAvailable: Boolean, link: String? = null): String = page(
        "Become an accountability partner",
        buildString {
            append("<h1>Become an accountability partner</h1>")
            append("<p>Someone asked you to be their accountability partner on Focus Fence. You'll get a weekly report by email and an alert if their protection changes.</p>")
            if (error != null) append("<p class='bad'>${esc(error)}</p>")
            // Filled by /static/report.js when the link carried a report key (D-023), so a partner who opened this inside a
            // chat app can carry the key to the browser they'll read reports in.
            append("<div id='keystatus' class='muted'></div>")
            append("<form method='post' action='/join'>")
            // Opened from the invite link: the link is the invite, so there's no code to type. Keep that link: it's yours.
            if (link != null) {
                append("<input type='hidden' name='link' value='${esc(link)}'>")
                append("<p class='muted'><b>Keep the link you were sent.</b> After you join and confirm your email, it opens the report in any browser.</p>")
            } else {
                append("<label>Invite code<br><input name='code' required value='${esc(code)}' autocapitalize='characters'></label>")
            }
            append("<label>Your name<br><input name='name' required maxlength='60'></label>")
            append("<label>Your email<br><input name='email' type='email' required maxlength='120'></label>")
            if (smsAvailable) {
                append("<label>Mobile number for text alerts (optional)<br><input name='phone' type='tel' placeholder='+1 555 123 4567'></label>")
                append("<label><input type='checkbox' name='sms_consent' value='yes'> Text me alerts (not reports). Message rates may apply; reply STOP to opt out.</label>")
            }
            append("<p><button type='submit'>Join</button></p></form>")
        },
        "join",
    )

    fun message(title: String, text: String): String =
        page(title, "<h1>${esc(title)}</h1><p>${esc(text)}</p>")

    fun partnerPage(
        partner: Store.Partner,
        deviceOnline: Boolean,
        lastSeenMs: Long,
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
        // D-023: the report is sealed on the phone; /static/report.js opens it here with the key from the partner's link.
        append("<div id='report' class='card'><p class='muted'>Opening the encrypted report…</p></div>")
        append("<noscript><p class='bad'>The report is end-to-end encrypted and opens with JavaScript in this browser. Alerts still arrive by email.</p></noscript>")
        append("<p class='muted'>End-to-end encrypted: this server stores the report but cannot read it. The key is in the link the phone's owner sent you; open that link and the report opens, in any browser.</p>")
        // An app partner (no email, D-035) gets alerts in their own app: no texts section.
        if (partner.email.isNotEmpty()) append("<h2>Text alerts</h2>")
        if (partner.email.isNotEmpty()) when {
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
    }, "report")

    /** Where a resent key link lands: the script keeps the key from after '#', which never reaches this server. */
    fun keyPage(): String = page(
        "Report key",
        "<h1>Report key</h1><p id='keystatus'>Saving the key in this browser…</p>" +
            "<p class='muted'>Now open your report from the link in your Focus Fence email. The key stays in this browser; this server never receives it.</p>",
        "key",
    )

    fun mask(phoneOrEmail: String): String = when {
        '@' in phoneOrEmail -> phoneOrEmail.substringBefore('@').take(2) + "…@" + phoneOrEmail.substringAfter('@')
        phoneOrEmail.length > 4 -> "••" + phoneOrEmail.takeLast(4)
        else -> "••"
    }
}
