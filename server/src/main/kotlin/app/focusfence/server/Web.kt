package app.focusfence.server

import app.focusfence.dns.Digest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The partner's web pages: an invite's join page, and a private report page per partner (the link token is the
 * credential, like a magic link). /static/report.js does what needs the browser's own key: joining, opening the report.
 */
object Web {

    private val timeFmt = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm").withZone(ZoneId.systemDefault())

    fun time(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

    fun esc(x: String): String =
        x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

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

    const val DEFAULT_PACKAGE = "app.focusfence.accountable"

    /**
     * Plan 13: what an invite opens, from the email (/j/, [link]) or the QR code (/c/, [code]). report.js makes this
     * browser's key pair and joins, or (on Android) offers the app with the invite in an intent. [owner] null: the invite
     * isn't valid (expired, used, or never was).
     */
    fun joinPage(owner: String?, link: String, code: String, pkg: String = DEFAULT_PACKAGE): String {
        if (owner == null) return page("Invite not valid", "<h1>This invite isn't valid</h1><p>It may have expired (invites last " +
            "24 hours) or been used already. If you joined with it, open the report from any alert email or from the app. " +
            "Otherwise ask for a new invite.</p>")
        return page("See ${owner}'s report", buildString {
            append("<h1>See ${esc(owner)}'s report</h1>")
            append("<div id='join' data-link='${esc(link)}' data-code='${esc(code)}' data-package='${esc(pkg)}'>")
            append("<p class='muted'>This browser becomes one of your devices. It makes its own key, so this server can't read the report.</p>")
            append("<label>Your name (${esc(owner)} sees this)<br><input id='jname' maxlength='60' autocomplete='name'></label>")
            append("<p><button type='button' id='jgo'>Join in this browser</button></p>")
            append("<p id='openapp'></p><p id='jmsg' class='muted'></p></div>")
            append("<noscript><p class='bad'>Joining needs JavaScript: this browser makes the key that opens the report.</p></noscript>")
            if (link.isEmpty()) append("<p class='muted'>Joined in a browser by code, you see the report but get no alerts. For alerts, join in the Focus Fence app.</p>")
        }, "join")
    }

    /** Plan 14: a group code's QR opens this. Groups are phones only (every member is watched), so no browser join. */
    fun groupJoinPage(name: String, members: List<String>, server: String, code: String, pkg: String = DEFAULT_PACKAGE): String =
        page("Join ${name}", buildString {
            append("<h1>Join ${esc(name)}</h1>")
            append("<p>${esc(members.joinToString(", "))}. Everyone in the group sees each other's report.</p>")
            val q = "server=" + java.net.URLEncoder.encode(server, "UTF-8") + "&code=" + java.net.URLEncoder.encode(code, "UTF-8")
            val fallback = java.net.URLEncoder.encode("https://github.com/flyboy-byte/focusfence/releases/latest", "UTF-8")
            append("<p><b><a href='${esc("intent://join?$q#Intent;scheme=focusfence;package=${pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }};S.browser_fallback_url=$fallback;end")}'>Open in the Focus Fence app</a></b></p>")
            append("<p class='muted'>A group is joined from the app on your phone. No app yet? The link above gets it. Or type ")
            append("<b>${esc(code.chunked(4).joinToString("-"))}</b> under Partners → Join with a code.</p>")
        })

    fun message(title: String, text: String): String =
        page(title, "<h1>${esc(title)}</h1><p>${esc(text)}</p>")

    fun partnerPage(
        partner: Store.Partner,
        owner: String,
        deviceOnline: Boolean,
        lastSeenMs: Long,
        notice: String?,
        ask: Store.UnlockRequest? = null,
        emailsAlerts: Boolean = false,
    ): String = page("${owner}'s report", buildString {
        val t = partner.token
        append("<h1>${esc(owner)}'s report</h1>")
        append("<p class='muted'>For ${esc(partner.name)} · last 7 days · keep this link private, it's your login.</p>")
        if (notice != null) append("<div class='card'>${esc(notice)}</div>")
        // Plan 12 §3: an open "ask a partner" request, answerable without the key or JavaScript.
        val askAction = ask?.let { app.focusfence.dns.GuardPolicy.Action.fromKey(it.action) }
        if (ask != null && askAction != null) append(
            "<div class='card'><b>${esc(owner)} asks for your OK to ${esc(app.focusfence.dns.UnlockAsk.what(askAction))}.</b>" +
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
        // D-023: the report is sealed on the phone; /static/report.js opens it with this browser's own key.
        append("<div id='report' data-owner='${esc(owner)}'><p class='muted'>Opening the encrypted report…</p></div>")
        append("<noscript><p class='bad'>The report is end-to-end encrypted and opens with JavaScript in this browser.</p></noscript>")
        append("<p class='muted'>${esc(owner)} can see when you last opened this report, not what you looked at. " +
            "End-to-end encrypted: this server stores the report but cannot read it.</p>")
        // Plan 12 §5: how alerts reach them. The time zone comes from their browser (report.js fills #tz).
        append("<h2>How alerts reach you</h2>")
        append("<p>" + when {
            partner.devices.any { it.kind == Store.KIND_APP } && !emailsAlerts -> "As notifications in the Focus Fence app."
            emailsAlerts -> "By email to ${esc(mask(partner.email))}."
            else -> "They don't: you joined in a browser by code. Join in the Focus Fence app to get alerts."
        } + "</p>")
        val lvl = app.focusfence.dns.AlertPrefs.Level.fromKey(partner.alertLevel)
        fun radio(l: app.focusfence.dns.AlertPrefs.Level, label: String) =
            "<label style='display:block'><input type='radio' name='level' value='${l.key}'${if (l == lvl) " checked" else ""}> $label</label>"
        append("<form method='post' action='/p/$t/prefs'>")
        append(radio(app.focusfence.dns.AlertPrefs.Level.ALL, "Every alert, as it happens"))
        append(radio(app.focusfence.dns.AlertPrefs.Level.DAILY, "Important ones as they happen; the rest in one message a day, when quiet hours end (7 am without them)"))
        append(radio(app.focusfence.dns.AlertPrefs.Level.IMPORTANT, "Important ones only; the rest stay in this report"))
        fun hours(name: String, sel: Int) = "<select name='$name'>" + (0..23).joinToString("") { h ->
            "<option value='$h'${if (h == sel) " selected" else ""}>${app.focusfence.dns.AlertPrefs.hourLabel(h)}</option>"
        } + "</select>"
        append("<label style='display:block'><input type='checkbox' name='quiet'${if (partner.quiet) " checked" else ""}> Quiet hours: nothing from " +
            hours("from", partner.quietFrom) + " to " + hours("to", partner.quietTo) + "; what came in meanwhile arrives together when they end</label>")
        append("<input type='hidden' name='tz' id='tz' value=''><div class='muted'>Your time zone: <span id='tzname'>${esc(partner.zone)}</span>. Important: the phone going silent, protection turned off, flagged screenshots, requests for your OK, and anything loosened.</div>")
        append("<button>Save</button></form>")
        // Plan 13: every device that opens this report. The owner sees the same list.
        append("<h2>Your devices</h2><table>")
        for (d in partner.devices) append("<tr><td>${esc(d.label)}</td><td class='muted'>${if (d.kind == Store.KIND_APP) "app" else "browser"} · added ${time(d.addedMs)}" +
            (if (d.sealed == null) " · waiting for the key" else "") + "</td><td>" +
            (if (partner.devices.size > 1) "<form method='post' action='/p/$t/devices/${esc(d.id)}/remove' style='display:inline'><button style='background:transparent;color:inherit;border:1px solid #bbb'>Remove</button></form>" else "") +
            "</td></tr>")
        append("</table><p class='muted'>To read on another device, open this page there: it offers to add that browser.</p>")
        append("<h2>Leave</h2><form method='post' action='/p/$t/leave'><button style='background:#b3261e'>Stop being their partner</button></form>")
    }, "report")

    fun mask(phoneOrEmail: String): String = when {
        '@' in phoneOrEmail -> phoneOrEmail.substringBefore('@').take(2) + "…@" + phoneOrEmail.substringAfter('@')
        phoneOrEmail.length > 4 -> "••" + phoneOrEmail.takeLast(4)
        else -> "••"
    }
}
