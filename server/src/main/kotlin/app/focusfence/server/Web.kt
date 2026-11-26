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
body{font-family:system-ui,sans-serif;max-width:680px;margin:0 auto;padding:16px;color:#1b1b1f;background:#fbfbfe;line-height:1.45}
h1{font-size:1.4em;margin:.4em 0 .2em}h2{font-size:1.05em;margin:1.4em 0 .5em}.card h2{margin-top:.2em}
.card{border:1px solid #ddd;border-radius:12px;padding:12px 16px;margin:12px 0}
.ok{color:#1b7f3b}.bad{color:#b3261e;font-weight:600}.muted{color:#666;font-size:.9em}
input,button{font:inherit;padding:8px;margin:4px 0;border-radius:8px;border:1px solid #bbb}
button{background:#3f51b5;color:#fff;border:0;padding:8px 16px}
label{display:block;margin-top:8px}table{border-collapse:collapse}td{padding:2px 12px 2px 0}
.seg{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0}.seg button{background:transparent;color:inherit;border:1px solid #bbb;border-radius:999px;padding:6px 14px}
.seg button.on{background:#3f51b5;border-color:#3f51b5;color:#fff}
.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:8px 0}
.stat{border:1px solid #ddd;border-radius:12px;padding:10px 12px}.stat b{display:block;font-size:1.4em}
table.sort{width:100%}table.sort th{text-align:left;cursor:pointer;padding:6px 12px 6px 0;border-bottom:1px solid #ddd;white-space:nowrap}
table.sort td{padding:6px 12px 6px 0;border-bottom:1px solid #eee;overflow-wrap:anywhere}table.sort td.n,table.sort th.n{text-align:right}
.filter{width:100%;box-sizing:border-box}.day{margin:16px 0 6px;font-weight:600;font-size:.95em}
.card,.stat{background:#fff}
.hero{display:flex;gap:12px;align-items:flex-start;border-radius:14px;padding:14px 16px;margin:12px 0}
.ok-bg{background:#e6f4ea}.bad-bg{background:#fce8e6}.hero b{font-size:1.1em}
.dot{flex:none;width:12px;height:12px;border-radius:50%;margin-top:6px}.ok-dot{background:#1b7f3b}.bad-dot{background:#b3261e}
.attn p{margin:6px 0}.tag{display:inline-block;font-size:.8em;padding:2px 10px;border-radius:999px;background:#eceef6;margin:6px 0 0}
.bar{display:block;height:4px;border-radius:2px;background:#3f51b5;opacity:.35;margin-top:4px}
.days{display:grid;gap:6px}.dayrow{display:grid;grid-template-columns:64px 1fr;gap:2px 10px;align-items:center}
.dl{font-size:.9em}.track{height:10px;border-radius:5px;background:#eceef6;overflow:hidden}.fill{display:block;height:100%;background:#3f51b5;border-radius:5px}
.dn{grid-column:2;font-size:.8em}
.ev{display:grid;grid-template-columns:72px 1fr;gap:10px;padding:6px 0 6px 10px;border-left:3px solid #ccd}
.ev.alert{border-left-color:#b3261e}.ev.good{border-left-color:#1b7f3b}.evt{color:#666;font-size:.85em;white-space:nowrap}
@media (prefers-color-scheme:dark){body{background:#131316;color:#e4e1e6}.card,.stat{border-color:#3a3a40;background:#1b1b1f}
.ok-bg{background:#1d3324}.bad-bg{background:#3b1d1b}.track{background:#2a2a31}.fill,.bar{background:#b9c3ff}.ev{border-left-color:#444}.evt{color:#a0a0a8}
.ev.alert{border-left-color:#ffb4ab}.ev.good{border-left-color:#6fd08c}.tag{background:#2a2a31}table.sort th,table.sort td{border-color:#2e2e33}
.muted{color:#a0a0a8}input{background:#1e1e22;color:inherit;border-color:#444}.ok{color:#6fd08c}.bad{color:#ffb4ab}}
</style></head><body${if (script != null) " data-page=\"$script\"" else ""}>$body</body></html>"""

    /** The invite opened in a browser on a server that can't send email (journey 4): point to the app instead. */
    const val DEFAULT_PACKAGE = "app.focusfence.accountable"

    /**
     * Filled by report.js on Android: "Open in the Focus Fence app", an intent link to focusfence://invite carrying the
     * whole invite (its key stays on the phone; an intent isn't a web request). Without the app, it falls back to the
     * download page.
     */
    private fun openInApp(pkg: String) = "<p id='openapp' data-package='${esc(pkg)}'></p>"

    fun joinNeedsApp(pkg: String = DEFAULT_PACKAGE): String = page(
        "Join in the Focus Fence app",
        "<h1>Join in the Focus Fence app</h1>" + openInApp(pkg) + "<p>This server can't send email, so you can't join as an email partner. " +
            "Install Focus Fence AC on your phone, open <b>Partners → Join with an invite</b>, and paste the whole message " +
            "you were sent. You'll see the same report there, and alerts arrive as notifications.</p>" +
            "<p><a href=\"https://github.com/flyboy-byte/focusfence/releases/latest\">Get Focus Fence AC</a></p>" +
            "<p class='muted'>The invite still works: nothing was used up by opening this page.</p>",
        "join",
    )

    fun joinForm(code: String, error: String?, smsAvailable: Boolean, link: String? = null, pkg: String = DEFAULT_PACKAGE): String = page(
        "Become an accountability partner",
        buildString {
            append("<h1>Become an accountability partner</h1>")
            if (link != null) append(openInApp(pkg))
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

    /**
     * "Being a good accountability partner" (plan 12, later list): what each alert means and how to bring it up. Linked
     * from the welcome email and every report page. Plain words; no names, no data, the same on every server.
     */
    fun guide(): String = page("Being a good accountability partner", buildString {
        append("<h1>Being a good accountability partner</h1>")
        append("<p>Someone trusted you with this. You don't need to be an expert, and you don't need to watch all the time. ")
        append("Most of the job is being someone they can be honest with.</p>")
        append("<h2>What the alerts mean</h2><ul>")
        append("<li><b>The phone went silent.</b> It hasn't checked in for about an hour. Often that's a dead battery, no signal or the phone switched off. You'll hear when it's back, usually with what happened meanwhile.</li>")
        append("<li><b>Protection was turned off or loosened.</b> Something that blocks or reports was switched off or set weaker. Sometimes there's a good reason; ask what it was.</li>")
        append("<li><b>A screenshot was flagged.</b> The phone's own check thought it saw nudity. You get a heavily blurred thumbnail, never a clear image, and the check is sometimes wrong. They may have added a note.</li>")
        append("<li><b>Repeated blocked tries.</b> Several attempts at a blocked kind of site in a few minutes. It says which kind, never which site.</li>")
        append("<li><b>They ask for your OK.</b> They want to change a protected setting without waiting. Approve it if it makes sense to you; say no if you'd rather they wait. They can always wait it out, so you can't lock them out.</li>")
        append("</ul>")
        append("<h2>Bringing it up</h2><ul>")
        append("<li>Ask before you assume. \"I saw an alert last night, what happened?\" goes further than an accusation.</li>")
        append("<li>Talk soon, and in private. A short call the same day beats a long talk a week later.</li>")
        append("<li>Notice the good weeks too. The report shows quiet stretches; say so.</li>")
        append("<li>A slip isn't the end of anything. What helps is what they do next, and that you're still there.</li>")
        append("<li>If it gets heavier than you can carry, it's fine to say so and to suggest someone else they could talk to as well.</li>")
        append("</ul>")
        append("<h2>Your part</h2><ul>")
        append("<li>Open the report now and then. They can see when you last looked, and knowing someone does matters.</li>")
        append("<li>Choose how alerts reach you on your report page: every alert, a daily message, or quiet hours at night.</li>")
        append("<li>You can stop being their partner at any time from the bottom of the report page. They'll be told.</li>")
        append("</ul>")
    })

    fun message(title: String, text: String): String =
        page(title, "<h1>${esc(title)}</h1><p>${esc(text)}</p>")

    fun partnerPage(
        partner: Store.Partner,
        deviceOnline: Boolean,
        lastSeenMs: Long,
        smsAvailable: Boolean,
        notice: String?,
        ask: Store.UnlockRequest? = null,
    ): String = page("Focus Fence report", buildString {
        val t = partner.token
        append("<h1>Focus Fence report</h1>")
        append("<p class='muted'>For ${esc(partner.name)} · last 7 days · keep this link private, it's your login.</p>")
        if (notice != null) append("<div class='card'>${esc(notice)}</div>")
        // Plan 12 §3: an open "ask a partner" request, answerable without the key or JavaScript.
        val askAction = ask?.let { app.focusfence.dns.GuardPolicy.Action.fromKey(it.action) }
        if (ask != null && askAction != null) append(
            "<div class='card'><b>They ask for your OK to ${esc(app.focusfence.dns.UnlockAsk.what(askAction))}.</b>" +
                "<div class='muted'>Asked ${time(ask.askedMs)}; it lapses ${time(ask.askedMs + app.focusfence.dns.UnlockAsk.LAPSE_MS)}. " +
                "Approving lets them do only this, for ${app.focusfence.dns.UnlockAsk.GRANT_MS / 60_000} minutes. Without an answer they can still wait it out.</div>" +
                "<form method='post' action='/p/$t/unlock/${esc(ask.id)}' style='display:inline'><input type='hidden' name='d' value='approve'><button>Approve</button></form> " +
                "<form method='post' action='/p/$t/unlock/${esc(ask.id)}' style='display:inline'><input type='hidden' name='d' value='deny'><button>Say no</button></form></div>"
        )
        // The first thing a partner wants to know, readable without the key or JavaScript.
        append(
            if (deviceOnline) "<div class='hero ok-bg'><span class='dot ok-dot'></span><div><b>Checking in normally</b>" +
                "<div class='muted'><span id='seen' data-online='1' data-t='$lastSeenMs'>Last check-in ${time(lastSeenMs)}</span>. The phone checks in about every 15 minutes.</div></div></div>"
            else "<div class='hero bad-bg'><span class='dot bad-dot'></span><div><b id='seen' data-online='0' data-t='$lastSeenMs'>Silent since ${time(lastSeenMs)}</b>" +
                "<div class='muted'>Protection may be off, the phone may be off or out of signal, or the app may have been removed.</div></div></div>"
        )
        // D-023: the report is sealed on the phone; /static/report.js opens it here with the key from the partner's link.
        append("<div id='report'><p class='muted'>Opening the encrypted report…</p></div>")
        append("<noscript><p class='bad'>The report is end-to-end encrypted and opens with JavaScript in this browser. Alerts still arrive by email.</p></noscript>")
        append("<p class='muted'>The phone's owner can see when you last opened this report, not what you looked at. <a href='/guide'>Being a good partner</a></p>")
        append("<p class='muted'>End-to-end encrypted: this server stores the report but cannot read it. The key is in the link the phone's owner sent you; open that link and the report opens, in any browser.</p>")
        // A one-tap reply: set words only, so nothing personal is stored (PartnerReply).
        if (partner.confirmed) {
            append("<h2>Send them a word</h2><form method='post' action='/p/$t/reply'>")
            for (r in app.focusfence.dns.PartnerReply.Reply.values()) append("<button name='r' value='${r.key}'>${esc(r.text)}</button> ")
            append("<div class='muted'>It shows on their phone as a notification. Nothing else is sent.</div></form>")
        }
        // Plan 12 §5: how alerts reach them. The time zone comes from their browser (report.js fills #tz).
        val lvl = app.focusfence.dns.AlertPrefs.Level.fromKey(partner.alertLevel)
        fun radio(l: app.focusfence.dns.AlertPrefs.Level, label: String) =
            "<label style='display:block'><input type='radio' name='level' value='${l.key}'${if (l == lvl) " checked" else ""}> $label</label>"
        append("<h2>How alerts reach you</h2><form method='post' action='/p/$t/prefs'>")
        append(radio(app.focusfence.dns.AlertPrefs.Level.ALL, "Every alert, as it happens"))
        append(radio(app.focusfence.dns.AlertPrefs.Level.DAILY, "Important ones as they happen; the rest in one message a day, at 7 am"))
        append(radio(app.focusfence.dns.AlertPrefs.Level.IMPORTANT, "Important ones only; the rest stay in this report"))
        append("<label style='display:block'><input type='checkbox' name='quiet'${if (partner.quiet) " checked" else ""}> Quiet hours: nothing from 10 pm to 7 am; what came in overnight arrives together at 7 am</label>")
        append("<input type='hidden' name='tz' id='tz' value=''><div class='muted'>Your time zone: <span id='tzname'>${esc(partner.zone)}</span>. Important: the phone going silent, protection turned off, flagged screenshots, requests for your OK, and anything loosened.</div>")
        append("<button>Save</button></form>")
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
