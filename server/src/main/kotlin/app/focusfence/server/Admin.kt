package app.focusfence.server

import io.ktor.http.ContentType
import kotlinx.coroutines.launch
import io.ktor.server.application.call
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), the default every authenticator app speaks. */
object Totp {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun newSecret(rnd: SecureRandom): String = ByteArray(20).also { rnd.nextBytes(it) }.let(::base32)
    fun base32(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) { buf = (buf shl 8) or (x.toInt() and 0xff); bits += 8; while (bits >= 5) { sb.append(B32[(buf shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }
    fun unbase32(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in s.uppercase().filter { it in B32 }) { buf = (buf shl 5) or B32.indexOf(c); bits += 5; if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 } }
        return out.toByteArray()
    }
    fun code(secretB32: String, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(unbase32(secretB32), "HmacSHA1")) }
        val h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0xf
        val n = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }
    /** Accepts the previous, current and next step (clock drift). Returns the matched step, or null. */
    fun verify(secretB32: String, given: String, nowMs: Long): Long? {
        val g = given.filter { it.isDigit() }
        if (g.length != 6) return null
        val step = nowMs / 30_000
        for (c in longArrayOf(step, step - 1, step + 1)) if (MessageDigest.isEqual(code(secretB32, c).toByteArray(), g.toByteArray())) return c
        return null
    }
}

/**
 * The operator page: per-person enrollment codes, devices, usage and an audit log. Sign-in is one factor
 * (D-035): a one-time code emailed to an allow-listed address, or an authenticator code once one is set up.
 * Off unless FF_ADMIN_EMAILS is set. No passwords exist anywhere. It can never show a report token, invite
 * code, sealed key, activity record or screenshot: none of those are read here.
 */
class Admin(
    private val cfg: Config,
    private val store: Store,
    private val notifier: Notifier,
    private val codes: EnrollCodes,
    private val clientOf: (ApplicationCall) -> String,
) {
    @kotlinx.serialization.Serializable
    data class Rec(var totpSecret: String = "", var totpOn: Boolean = false, var recovery: List<String> = emptyList(), var lastStep: Long = 0)

    /** An emailed code waiting to be typed in. */
    private class Pending(val email: String, val codeHash: String, val expires: Long, val real: Boolean, var attempts: Int = 0)
    private class Session(val email: String, val csrf: String, val created: Long, var seen: Long, var setupSecret: String = "")

    private val rnd = SecureRandom()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val recFile = File(store.dir, "admin.json")
    private val auditFile = File(store.dir, "admin-audit.jsonl")
    private val pendings = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val ipLogin = RateLimiter(10, 3_600_000, cfg.now)
    private val emailLogin = RateLimiter(3, 3_600_000, cfg.now)
    // Audit A1: a stranger who knows an admin address must not be able to use up sign-in. The global cap is a high
    // backstop only, test emails have their own budget, and `admin login-link` on the server never touches either.
    private val emailGlobal = RateLimiter(100, 24 * 3_600_000L, cfg.now)
    private val testEmail = RateLimiter(5, 24 * 3_600_000L, cfg.now)
    private val linkFile = File(store.dir, "admin-links.json")
    private val ipFail = RateLimiter(10, 3_600_000, cfg.now)
    private val allowed = cfg.adminEmails.map { it.lowercase() }.toSet()

    private fun token(n: Int = 24) = Web.run { ByteArray(n).also(rnd::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) } }
    private fun eq(a: String, b: String) = MessageDigest.isEqual(Integrity.sha256(a).toByteArray(), Integrity.sha256(b).toByteArray())
    /**
     * Audit A2: fail closed. If admin.json exists but can't be read, refuse admin sign-in (the exception becomes a
     * plain error page) and never save over it; treating it as empty would drop every admin to email-only and a
     * fresh authenticator.
     */
    private fun recs(): MutableMap<String, Rec> = synchronized(recFile) {
        if (!recFile.exists()) return mutableMapOf()
        runCatching { json.decodeFromString<MutableMap<String, Rec>>(recFile.readText()) }.getOrElse {
            System.err.println("ADMIN: ${recFile.path} exists but cannot be read; admin sign-in is refused until it is repaired. It is never overwritten.")
            throw IllegalStateException("admin store unreadable")
        }
    }
    /** Audit A3: every read-modify-write of admin.json happens under one lock. */
    private fun <T> updateRecs(block: (MutableMap<String, Rec>) -> T): T = synchronized(recFile) {
        val m = recs(); val r = block(m); saveRecs(m); r
    }
    private fun saveRecs(m: Map<String, Rec>) = synchronized(recFile) {
        val tmp = File(recFile.parentFile, "admin.json.tmp"); tmp.writeText(json.encodeToString(m))
        java.nio.file.Files.move(tmp.toPath(), recFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun consumeLink(plain: String): String? = synchronized(AdminLinks) {
        val all = AdminLinks.read(linkFile)
        val h = Integrity.sha256(plain)
        val hit = all.firstOrNull { MessageDigest.isEqual(it.hash.toByteArray(), h.toByteArray()) && it.expiresMs > cfg.now() } ?: return null
        AdminLinks.write(linkFile, all.filter { it.hash != hit.hash && it.expiresMs > cfg.now() })
        hit.email
    }

    private fun audit(who: String, ip: String, action: String, detail: String = "") = synchronized(auditFile) {
        auditFile.appendText(json.encodeToString(mapOf("t" to cfg.now().toString(), "who" to who, "ip" to ip, "action" to action, "detail" to detail.take(120))) + "\n")
    }
    private fun auditTail(n: Int): List<Map<String, String>> =
        if (!auditFile.exists()) emptyList() else auditFile.readLines().takeLast(n).reversed().mapNotNull { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    private val COOKIE = "__Host-ffa"
    private val PCOOKIE = "__Host-ffap"
    private fun setCookie(c: ApplicationCall, name: String, v: String, maxAge: Int) =
        c.response.headers.append("Set-Cookie", "$name=$v; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=$maxAge")

    private fun session(c: ApplicationCall): Session? {
        val id = c.request.cookies[COOKIE] ?: return null
        val s = sessions[id] ?: return null
        val now = cfg.now()
        if (now - s.seen > 30 * 60_000L || now - s.created > 8 * 3_600_000L) { sessions.remove(id); return null }
        s.seen = now
        return s
    }

    private suspend fun html(c: ApplicationCall, title: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        c.response.headers.append("Cache-Control", "no-store")
        c.respondText(if (body.startsWith("<!doctype")) body else Web.page(title, body), ContentType.Text.Html, status)
    }

    /** Sign-in and setup pages: the dashboard's look, one centred card, with the admin script for copy buttons. */
    private fun authPage(title: String, step: String?, body: String): String =
        "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>${Web.esc(title)} · Focus Fence admin</title><style>$CSS</style><script src='/static/admin.js' defer></script></head><body>" +
            "<main class='auth'><p class='brand'>Focus Fence server</p><div class='card'>" +
            (step?.let { "<p class='step'>${Web.esc(it)}</p>" } ?: "") + body + "</div></main></body></html>"

    private fun bad(note: String?) = note?.let { "<p class='bad'>${Web.esc(it)}</p>" } ?: ""

    private fun loginForm(note: String? = null) = authPage("Sign in", null,
        "<h1>Sign in</h1>" + bad(note) +
            "<form method='post' action='/admin/login'><label for='email'>Admin email</label>" +
            "<input id='email' name='email' type='email' required maxlength='120' autocomplete='username' autofocus>" +
            "<button class='wide'>Email me a code</button></form>" +
            "<h2 class='or'>Or use your authenticator</h2>" +
            "<form method='post' action='/admin/totp' autocomplete='off'><label for='temail'>Admin email</label>" +
            "<input id='temail' name='email' type='email' required maxlength='120' autocomplete='username'>" +
            "<label for='tcode'>Authenticator code</label><input id='tcode' name='code' required maxlength='24' inputmode='numeric' autocomplete='one-time-code'>" +
            "<p class='mut'>Only if you set one up under Activity. Lost that phone? A recovery code works here too.</p>" +
            "<button class='wide ghost'>Sign in</button></form>" +
            "<p class='mut'>No email on this server? On the server run <code>focusfence-server admin login-link you@example.com</code> for a one-time link.</p>")

    private fun verifyForm(note: String? = null): String = authPage("Sign in", null,
        "<h1>Check your email</h1><p class='mut'>If that address is an admin, a 6-digit code is on its way. It works for 10 minutes.</p>" +
            bad(note) +
            "<form method='post' action='/admin/verify' autocomplete='off'>" +
            "<label for='ec'>Code from the email</label><input id='ec' name='email_code' required maxlength='12' inputmode='numeric' autocomplete='one-time-code' autofocus>" +
            "<button class='wide'>Sign in</button></form>")

    /** Recovery codes, shown once, with copy and save buttons. */
    private fun recoveryPage(title: String, intro: String, plain: List<String>, next: String, nextLabel: String) = authPage(title, null,
        "<h1>${Web.esc(title)}</h1><p>${Web.esc(intro)}</p>" +
            "<pre class='codes' id='recovery'>${plain.joinToString("\n") { Web.esc(it.chunked(4).joinToString("-")) }}</pre>" +
            "<p class='actions'><button type='button' class='ghost' data-copy='recovery'>Copy</button>" +
            "<button type='button' class='ghost' data-download='recovery' data-name='focusfence-recovery-codes.txt'>Save as file</button></p>" +
            "<p class='mut'>Each code works once, in place of the authenticator. They are not shown again.</p>" +
            "<p><a class='button wide' href='$next'>${Web.esc(nextLabel)}</a></p>")

    private fun hidden(s: Session) = "<input type='hidden' name='csrf' value='${Web.esc(s.csrf)}'>"

    private val startedMs = cfg.now()

    private val CSS = """
:root{color-scheme:light dark;--bg:#f6f6f9;--card:#fff;--fg:#1b1b1f;--mut:#666;--line:#e2e2e8;--acc:#3f51b5;--ok:#1b7f3b;--bad:#b3261e;--warn:#a15c00}
@media(prefers-color-scheme:dark){:root{--bg:#121216;--card:#1c1c22;--fg:#e8e8ee;--mut:#9a9aa6;--line:#33333c;--acc:#9fb0ff;--ok:#6fd08c;--bad:#ff8a80;--warn:#ffc266}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}
header{background:var(--card);border-bottom:1px solid var(--line);padding:10px 16px;display:flex;flex-wrap:wrap;gap:8px 16px;align-items:center}
header b{font-size:1.05em}header .sp{flex:1}
nav{display:flex;gap:4px;flex-wrap:wrap}nav a{padding:6px 12px;border-radius:999px;text-decoration:none;color:var(--fg)}
nav a.on{background:var(--acc);color:#fff}@media(prefers-color-scheme:dark){nav a.on{color:#111}}
main{max-width:900px;margin:0 auto;padding:16px}h1{font-size:1.3em;margin:.2em 0 .6em}h2{font-size:1.05em;margin:0 0 .5em}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin:0 0 14px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-bottom:14px}
.big{font-size:1.6em;font-weight:600}.mut{color:var(--mut);font-size:.9em}.ok{color:var(--ok)}.bad{color:var(--bad)}.warn{color:var(--warn)}
.bar{height:8px;border-radius:4px;background:var(--line);overflow:hidden;margin-top:6px}.bar i{display:block;height:100%;background:var(--acc)}
.tw{overflow-x:auto}table{border-collapse:collapse;width:100%}th{text-align:left;color:var(--mut);font-weight:500;font-size:.85em}
td,th{padding:8px 10px 8px 0;border-bottom:1px solid var(--line);vertical-align:middle}tr:last-child td{border-bottom:0}
.badge{display:inline-block;padding:1px 9px;border-radius:999px;font-size:.8em;border:1px solid var(--line)}
form.inline{display:inline}label{display:block;margin:8px 0 2px;font-size:.9em;color:var(--mut)}
input{font:inherit;padding:8px 10px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--fg);width:100%;max-width:340px}
input[type=checkbox]{width:auto}button{font:inherit;padding:7px 14px;border-radius:8px;border:0;background:var(--acc);color:#fff;cursor:pointer}
@media(prefers-color-scheme:dark){button{color:#111}}button.ghost{background:transparent;color:var(--acc);border:1px solid var(--line)}button.danger{background:var(--bad);color:#fff}
.note{border-left:4px solid var(--acc)}.code{font:600 1.25em ui-monospace,monospace;letter-spacing:.04em;word-break:break-all}
.row{display:flex;flex-wrap:wrap;gap:12px}.row>div{flex:1;min-width:140px}
.auth{max-width:440px;padding-top:8vh}.brand{font-weight:600;margin:0 0 12px 4px}.auth .card{padding:22px 22px 18px}
.auth input{max-width:none}.auth h2.or{margin:22px 0 4px;padding-top:16px;border-top:1px solid var(--line)}.step{margin:0 0 4px;color:var(--mut);font-size:.85em}.wide{width:100%;margin-top:14px;padding:10px 14px}
a.button{display:inline-block;text-align:center;text-decoration:none;padding:10px 14px;border-radius:8px;background:var(--acc);color:#fff;box-sizing:border-box}
@media(prefers-color-scheme:dark){a.button{color:#111}}
pre.msg{font:inherit;line-height:1.55;background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:12px 14px;margin:12px 0;white-space:pre-wrap;overflow-wrap:anywhere}
pre.codes{font:600 1.05em/1.7 ui-monospace,monospace;background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:12px 14px;margin:12px 0;white-space:pre-wrap;word-break:break-all}
.actions{display:flex;gap:8px;flex-wrap:wrap}.qr{width:200px;height:200px;display:block;margin:12px auto;border-radius:8px}
.key{font:600 1em ui-monospace,monospace;letter-spacing:.06em;word-break:break-all}
.invite{border-left:4px solid var(--ok)}
.item{display:flex;gap:12px;align-items:center;padding:12px 0;border-top:1px solid var(--line)}.item:first-of-type{border-top:0}.grow{flex:1;min-width:0}
"""

    private fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val d = (cfg.now() - ms).coerceAtLeast(0) / 1000
        return when { d < 90 -> "just now"; d < 5400 -> "${d / 60} min ago"; d < 129600 -> "${d / 3600} h ago"; else -> "${d / 86400} days ago" }
    }
    private fun mb(b: Long) = if (b < 1_048_576) "${b / 1024} KB" else "${b / 1_048_576} MB"
    private fun bar(frac: Double) = "<div class='bar'><i style='width:${(frac.coerceIn(0.0, 1.0) * 100).toInt()}%'></i></div>"

    private fun shell(s: Session, tab: String, body: String): String {
        fun tabLink(id: String, label: String) = "<a href='/admin${if (id == "overview") "" else "/$id"}'${if (tab == id) " class='on'" else ""}>$label</a>"
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Focus Fence admin</title><style>$CSS</style><script src='/static/admin.js' defer></script></head><body>" +
            "<header><b>Focus Fence server</b><nav>${tabLink("overview", "Overview")}${tabLink("codes", "People")}${tabLink("devices", "Devices")}${tabLink("activity", "Activity")}</nav><span class='sp'></span>" +
            "<span class='mut'>${Web.esc(s.email)}</span><form class='inline' method='post' action='/admin/logout'>${hidden(s)}<button class='ghost'>Sign out</button></form></header><main>$body</main></body></html>"
    }

    private fun flash(newCode: String?, note: String?, invite: String? = null) =
        (note?.let { "<div class='card note'>${Web.esc(it)}</div>" } ?: "") +
            (newCode?.let {
                "<div class='card invite'><h2>Send this to them</h2>" +
                    "<pre class='msg' id='invite'>${Web.esc(invite ?: it)}</pre>" +
                    "<p class='actions'><button type='button' data-copy='invite'>Copy message</button><button type='button' class='ghost' data-copy='newcode'>Copy code only</button></p>" +
                    "<p class='mut'>Code <code class='key' id='newcode'>${Web.esc(it)}</code> is shown once and stored only as a hash. If it's lost, revoke it and make another.</p></div>"
            } ?: "")

    /** The message an admin sends a new person: where to get the app, this server's address, and their code. */
    private fun inviteText(name: String, code: String, uses: Int, expiresMs: Long): String {
        val limits = listOfNotNull(
            if (uses == 1) "works once" else if (uses > 1) "works $uses times" else null,
            if (expiresMs != 0L) "until " + java.time.format.DateTimeFormatter.ofPattern("MMM d").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(expiresMs)) else null,
        ).joinToString(", ")
        return "Hi ${name.ifBlank { "there" }}, here's how to join my Focus Fence server:\n" +
            "1. Install Focus Fence AC: ${cfg.appUrl}\n" +
            "2. In the app, open Partners, tap Join with a code and paste this whole message.\n" +
            "Server: ${cfg.publicUrl}\n" +
            "Enrollment code: $code" + (if (limits.isNotEmpty()) " ($limits)" else "")
    }

    private fun dashboard(s: Session, tab: String = "overview", newCode: String? = null, note: String? = null): String {
        val now = cfg.now()
        val body = when (tab) {
            "codes" -> {
                val list = codes.list().sortedByDescending { it.createdMs }
                val made = newCode?.let { nc -> list.firstOrNull()?.let { inviteText(it.label, nc, it.maxUses, it.expiresMs) } }
                val addForm = "<div class='card'><h2>Add a person</h2><p class='mut'>Makes an enrollment code for one person's phone and a message to send them.</p>" +
                    "<form method='post' action='/admin/codes/add'>${hidden(s)}<div class='row'><div><label for='pl'>Name</label><input id='pl' name='label' required maxlength='60' placeholder='Sam'></div>" +
                    "<div><label for='pd'>Code expires in (days)</label><input id='pd' name='days' inputmode='numeric' maxlength='4' placeholder='never' value='14'></div><div><label for='pu'>Phones it can add</label><input id='pu' name='uses' inputmode='numeric' maxlength='4' placeholder='unlimited' value='1'></div></div><p><button>Make code</button></p></form></div>"
                flash(newCode, note, made) + "<h1>People</h1>" + addForm + "<div class='card'><h2>Enrollment codes</h2>" +
                    (if (list.isEmpty()) "<p class='mut'>No codes yet. The server's own bootstrap code still works" + (if (cfg.enrollToken != null) "." else ", and enrollment is open (no code needed) until you make one.") + "</p>" else "") +
                    list.joinToString("") { c ->
                        val state = if (c.revoked) "<span class='badge bad'>revoked</span>" else if (c.expiresMs != 0L && now > c.expiresMs) "<span class='badge warn'>expired</span>"
                        else if (c.maxUses != 0 && c.uses >= c.maxUses) "<span class='badge warn'>used up</span>" else "<span class='badge ok'>live</span>"
                        val detail = "${c.uses} of ${if (c.maxUses == 0) "unlimited" else c.maxUses.toString()} used · last used ${ago(c.lastUsedMs)} · " +
                            (if (c.expiresMs == 0L) "never expires" else "expires ${Web.time(c.expiresMs)}")
                        "<div class='item'><div class='grow'><div><b>${Web.esc(c.label)}</b> $state</div><div class='mut'>$detail</div></div>" +
                            (if (!c.revoked) "<form class='inline' method='post' action='/admin/codes/revoke'>${hidden(s)}<input type='hidden' name='id' value='${Web.esc(c.id)}'><button class='ghost'>Revoke</button></form>" else "") + "</div>"
                    } + "</div>"
            }
            "devices" -> {
                val devs = store.allDevices().sortedByDescending { it.lastSeenMs }
                val byCode = codes.list().associateBy { it.id }
                flash(newCode, note) + "<h1>Devices</h1><p class='mut'>Names, dates, sizes and partner counts only. Reports, activity, tokens and screenshots are never shown here.</p>" +
                    (if (devs.isEmpty()) "<div class='card mut'>No phones enrolled yet.</div>" else "") +
                    devs.joinToString("") { d ->
                        val silent = d.staleAlerted
                        "<div class='card'><div class='row'><div><div class='mut'>Device <code>${Web.esc(d.id.take(8))}</code></div><div class='big'>${Web.esc(d.label.ifBlank { "unnamed" })}</div></div>" +
                            "<div><div class='mut'>Last seen</div>${ago(d.lastSeenMs)}${if (silent) " <span class='badge bad'>silent</span>" else ""}</div>" +
                            "<div><div class='mut'>Enrolled</div>${Web.time(d.enrolledMs)}<div class='mut'>with ${Web.esc(byCode[d.codeId]?.label ?: if (d.codeId.isEmpty()) "bootstrap code / open" else "a removed code")}</div></div>" +
                            "<div><div class='mut'>Partners</div>${d.partners.size}, on ${d.partners.sumOf { it.devices.size }} device(s)<div class='mut'>${mb(store.deviceBytes(d.id))} stored</div></div></div>" +
                            "<form method='post' action='/admin/devices/label'>${hidden(s)}<input type='hidden' name='id' value='${Web.esc(d.id)}'><div class='row'><div><input name='label' maxlength='40' placeholder='Name this device' value='${Web.esc(d.label)}'></div><div><button class='ghost'>Save name</button></div></div></form>" +
                            "<form method='post' action='/admin/devices/remove' style='margin-top:8px'>${hidden(s)}<input type='hidden' name='id' value='${Web.esc(d.id)}'><label><input type='checkbox' name='sure' value='1' required> Delete this device and everything stored for it</label><button class='danger'>Remove device</button></form></div>"
                    }
            }
            "activity" -> {
                val mine = sessions.values.sortedByDescending { it.seen }
                flash(newCode, note) + "<h1>Activity</h1><div class='card'><h2>Admin log</h2><div class='tw'><table><tr><th>When</th><th>Who</th><th>What</th><th>From</th></tr>" +
                    auditTail(100).joinToString("") { a -> "<tr><td>${Web.time(a["t"]?.toLongOrNull() ?: 0)}</td><td>${Web.esc(a["who"].orEmpty())}</td><td>${Web.esc(a["action"].orEmpty())} <span class='mut'>${Web.esc(a["detail"].orEmpty())}</span></td><td class='mut'>${Web.esc(a["ip"].orEmpty())}</td></tr>" } +
                    "</table></div></div><div class='card'><h2>Signed-in sessions</h2><p class='mut'>${mine.size} active. Sessions end after 30 minutes idle or 8 hours, and when the server restarts.</p>" +
                    "<form class='inline' method='post' action='/admin/sessions/others'>${hidden(s)}<button class='ghost'>Sign out all other sessions</button></form></div>" +
                    (if (recs()[s.email]?.totpOn == true)
                        "<div class='card'><h2>Your sign-in</h2><p class='mut'>Emailed code or authenticator, either one. Recovery codes left: ${recs()[s.email]?.recovery?.size ?: 0}</p>" +
                            "<form class='inline' method='post' action='/admin/recovery/new'>${hidden(s)}<button class='ghost'>Make new recovery codes</button></form> " +
                            "<form class='inline' method='post' action='/admin/setup/start'>${hidden(s)}<button class='ghost'>Replace authenticator</button></form> "
                    else "<div class='card'><h2>Your sign-in</h2><p class='mut'>Emailed code. Add an authenticator to sign in with it instead when email is slow.</p>" +
                            "<form class='inline' method='post' action='/admin/setup/start'>${hidden(s)}<button class='ghost'>Set up authenticator</button></form> ") +
                    "<form class='inline' method='post' action='/admin/test-email'>${hidden(s)}<button class='ghost'>Send me a test email</button></form></div>"
            }
            else -> {
                val used = store.usedBytes(now); val devs = store.deviceCount()
                val all = store.allDevices(); val silent = all.count { it.staleAlerted }
                val live = codes.list().count { !it.revoked && (it.expiresMs == 0L || now < it.expiresMs) && (it.maxUses == 0 || it.uses < it.maxUses) }
                flash(newCode, note) + "<h1>Overview</h1><div class='grid'>" +
                    "<div class='card'><div class='mut'>Data</div><div class='big'>${mb(used)}</div>${bar(used.toDouble() / cfg.maxDataBytes)}<div class='mut'>of ${mb(cfg.maxDataBytes)}</div></div>" +
                    "<div class='card'><div class='mut'>Phones</div><div class='big'>$devs</div>${bar(devs.toDouble() / cfg.maxDevices)}<div class='mut'>of ${cfg.maxDevices}</div></div>" +
                    "<div class='card'><div class='mut'>Silent phones</div><div class='big ${if (silent > 0) "bad" else "ok"}'>$silent</div><div class='mut'>no check-in for ${Alerts.minutes(cfg.staleMs)}</div></div></div>" +
                    "<div class='card'><h2>Enrollment</h2><p>${if (cfg.enrollToken == null && live == 0 && codes.list().isEmpty()) "<span class='bad'>Open: anyone who finds this server can enroll a phone.</span>" else "Closed: a code is needed."} " +
                    "$live live code(s)${if (cfg.enrollToken != null) " plus the bootstrap code" else ""}. <a href='/admin/codes'>People and codes</a></p></div>" +
                    "<div class='card'><h2>Server</h2><p class='mut'>Up for ${ago(startedMs).removeSuffix(" ago")} · public URL ${Web.esc(cfg.publicUrl)} · alerts after ${Alerts.minutes(cfg.staleMs)} of silence · email: ${if (cfg.adminEmails.isNotEmpty()) "${cfg.adminEmails.size} admin(s)" else ""} · invite emails capped at ${cfg.inviteEmailDailyCap}/day</p></div>"
            }
        }
        return shell(s, tab, body)
    }

    private fun newSession(c: ApplicationCall, email: String): Session {
        val id = token(); val s = Session(email, token(16), cfg.now(), cfg.now())
        sessions[id] = s; setCookie(c, COOKIE, id, 8 * 3600); return s
    }

    private suspend fun fail(c: ApplicationCall, ip: String, why: String) {
        audit("-", ip, "login_failed", why)
        html(c, "Sign in", verifyForm("That code did not work."), HttpStatusCode.Unauthorized)
    }

    fun routes(r: Route) {
        if (allowed.isEmpty()) return
        r.get("/admin") {
            val s = session(call)
            if (s == null) html(call, "Admin", loginForm()) else html(call, "Admin", dashboard(s))
        }
        for (tab in listOf("codes", "devices", "activity")) r.get("/admin/$tab") {
            val s = session(call)
            if (s == null) html(call, "Admin", loginForm()) else html(call, "Admin", dashboard(s, tab))
        }
        r.post("/admin/login") {
            val ip = clientOf(call)
            val p = call.receiveParameters()
            val email = p["email"].orEmpty().trim().lowercase().take(120)
            if (!ipLogin.allow(ip)) return@post html(call, "Admin", loginForm("Too many attempts. Try later."), HttpStatusCode.TooManyRequests)
            val nowMs = cfg.now()
            pendings.entries.removeIf { nowMs > it.value.expires } // nothing else ever removes an unused login attempt
            sessions.entries.removeIf { nowMs - it.value.seen > 30 * 60_000L || nowMs - it.value.created > 8 * 3_600_000L }
            val real = email in allowed
            // Audit A6: with no email provider the code would only reach the server log. Use the CLI link instead.
            if (!cfg.emailConfigured) return@post html(call, "Admin", loginForm("Email sign-in isn't set up on this server. On the server, run: focusfence-server admin login-link <email>"), HttpStatusCode.ServiceUnavailable)
            val code = (100000 + rnd.nextInt(900000)).toString()
            // Same page and same cookie whether or not the address is an admin, so this reveals nothing.
            pendings[token().also { setCookie(call, PCOOKIE, it, 600) }] = Pending(email, Integrity.sha256(code), cfg.now() + 600_000, real)
            if (real && emailLogin.allow(email) && emailGlobal.allow("all")) {
                // Audit A4: sent in the background so the response time doesn't reveal which addresses are admins.
                call.application.launch { notifier.email(email, "Focus Fence admin sign-in code", "<p>Your sign-in code is <b>$code</b>. It expires in 10 minutes. If you did not ask for it, ignore this email.</p>") }
                audit(email, ip, "login_code_sent")
            }
            html(call, "Sign in", verifyForm())
        }
        // Audit A1: the operator's own way in, which no one on the internet can use up. `admin login-link <email>` on the
        // server writes a one-time, 15-minute link that signs in on its own, like the emailed code (2026-11-25: one way in, not two).
        // Audit S2: GET only shows a button, so a chat app's link preview can't use the link up; the POST spends it.
        r.get("/admin/link/{token}") {
            val tok = call.parameters["token"].orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            html(call, "Sign in", authPage("Sign in", null, "<h1>Sign in</h1><p>Continue with this one-time link.</p>" +
                "<form method='post' action='/admin/link/${Web.esc(tok)}'><button class='wide'>Continue</button></form>"))
        }
        r.post("/admin/link/{token}") {
            val email = consumeLink(call.parameters["token"].orEmpty())?.takeIf { it in allowed }
                ?: return@post html(call, "Admin", loginForm("That sign-in link is invalid, used or expired."), HttpStatusCode.Unauthorized)
            audit(email, clientOf(call), "login_link_used")
            html(call, "Admin", dashboard(newSession(call, email)))
        }
        r.post("/admin/verify") {
            val ip = clientOf(call)
            val p = call.receiveParameters()
            if (!ipFail.allow(ip)) return@post html(call, "Admin", loginForm("Too many attempts. Try later."), HttpStatusCode.TooManyRequests)
            val pid = call.request.cookies[PCOOKIE] ?: return@post html(call, "Admin", loginForm("Start again."), HttpStatusCode.Unauthorized)
            val pend = pendings[pid] ?: return@post html(call, "Admin", loginForm("Start again."), HttpStatusCode.Unauthorized)
            pend.attempts++
            if (cfg.now() > pend.expires || pend.attempts > 5) { pendings.remove(pid); return@post html(call, "Admin", loginForm("Code expired. Start again."), HttpStatusCode.Unauthorized) }
            val codeOk = pend.real && eq(Integrity.sha256(p["email_code"].orEmpty().filter { it.isDigit() }), pend.codeHash)
            if (!codeOk) return@post fail(call, ip, "email_code")
            recs() // audit A2: an unreadable admin.json refuses every sign-in, not only the authenticator
            pendings.remove(pid)
            audit(pend.email, ip, "login_ok_email")
            html(call, "Admin", dashboard(newSession(call, pend.email)))
        }
        // The other way in (2026-11-25): an authenticator code (or a recovery code) alone, for an admin who set one up.
        r.post("/admin/totp") {
            val ip = clientOf(call)
            val p = call.receiveParameters()
            if (!ipFail.allow(ip)) return@post html(call, "Admin", loginForm("Too many attempts. Try later."), HttpStatusCode.TooManyRequests)
            val email = p["email"].orEmpty().trim().lowercase().take(120)
            val given = p["code"].orEmpty().trim()
            val recoveryHash = Integrity.sha256(normalizeCode(given))
            val outcome = if (email !in allowed) null else updateRecs { all ->
                val rec = all[email]?.takeIf { it.totpOn } ?: return@updateRecs null
                val step = Totp.verify(rec.totpSecret, given, cfg.now())
                val viaRecovery = step == null && rec.recovery.any { eq(it, recoveryHash) }
                if ((step == null || step <= rec.lastStep) && !viaRecovery) return@updateRecs null
                if (viaRecovery) rec.recovery = rec.recovery.filter { !eq(it, recoveryHash) } else rec.lastStep = step!!
                viaRecovery to rec.recovery.size
            }
            if (outcome == null) {
                audit("-", ip, "login_failed", "authenticator")
                return@post html(call, "Admin", loginForm("That didn't work. Check the email and code."), HttpStatusCode.Unauthorized)
            }
            val (viaRecovery, left) = outcome
            audit(email, ip, if (viaRecovery) "login_ok_recovery_code" else "login_ok_totp")
            html(call, "Admin", dashboard(newSession(call, email), note = if (viaRecovery) "You used a recovery code ($left left)." else null))
        }
        r.post("/admin/setup") {
            val s = session(call)?.takeIf { it.setupSecret.isNotEmpty() } ?: return@post html(call, "Admin", loginForm("Start again."), HttpStatusCode.Unauthorized)
            val p = call.receiveParameters()
            if (!eq(p["csrf"].orEmpty(), s.csrf)) return@post html(call, "Admin", loginForm("Start again."), HttpStatusCode.Forbidden)
            val step = Totp.verify(s.setupSecret, p["totp"].orEmpty(), cfg.now())
                ?: return@post html(call, "Set up", setupPage(s, "That code did not match. Check the secret and your phone's clock."), HttpStatusCode.Unauthorized)
            val plain = List(8) { newInviteCode() + newInviteCode() }
            updateRecs { all -> all[s.email] = Rec(s.setupSecret, true, plain.map { Integrity.sha256(normalizeCode(it)) }, step) }
            s.setupSecret = ""
            audit(s.email, clientOf(call), "totp_enrolled")
            html(call, "Recovery codes", recoveryPage("Save your recovery codes", "Your authenticator is set up: from now on you can sign in with it instead of an emailed code. Keep these somewhere safe in case you lose that phone.", plain, "/admin", "I saved them, continue"))
        }
        suspend fun ApplicationCall.authed(block: suspend (Session, io.ktor.http.Parameters) -> Unit) {
            val s = session(this) ?: return html(this, "Admin", loginForm("Sign in again."), HttpStatusCode.Unauthorized)
            val p = receiveParameters()
            if (!eq(p["csrf"].orEmpty(), s.csrf)) return html(this, "Admin", loginForm("Sign in again."), HttpStatusCode.Forbidden)
            block(s, p)
        }
        // Optional, from Activity → Your sign-in: an authenticator becomes a second way to sign in, never a second step.
        r.post("/admin/setup/start") { call.authed { s, _ ->
            s.setupSecret = Totp.newSecret(rnd)
            html(call, "Set up", setupPage(s))
        } }
        r.post("/admin/codes/add") { call.authed { s, p ->
            val days = p["days"]?.trim()?.toLongOrNull()?.coerceIn(1, 3650)
            val uses = p["uses"]?.trim()?.toIntOrNull()?.coerceIn(1, 1000) ?: 0
            val label = p["label"].orEmpty().trim().ifEmpty { "unnamed" }
            val (plain, c) = codes.create(label, cfg.now(), days?.let { cfg.now() + it * 86_400_000 } ?: 0, uses)
            audit(s.email, clientOf(call), "code_created", "${c.id} ${c.label}")
            html(call, "Admin", dashboard(s, "codes", newCode = plain))
        } }
        r.post("/admin/codes/revoke") { call.authed { s, p ->
            val id = p["id"].orEmpty(); val ok = codes.revoke(id)
            audit(s.email, clientOf(call), "code_revoked", id)
            html(call, "Admin", dashboard(s, "codes", note = if (ok) "Code revoked." else "No such code."))
        } }
        r.post("/admin/devices/remove") { call.authed { s, p ->
            val id = p["id"].orEmpty()
            val ok = p["sure"] == "1" && store.deleteDevice(id)
            audit(s.email, clientOf(call), "device_removed", id.take(8))
            html(call, "Admin", dashboard(s, "devices", note = if (ok) "Device removed." else "Nothing removed."))
        } }
        r.post("/admin/devices/label") { call.authed { s, p ->
            val id = p["id"].orEmpty(); val label = p["label"].orEmpty().trim().take(40)
            val ok = store.updateDevice(id) { it.label = label } != null
            audit(s.email, clientOf(call), "device_named", "${id.take(8)} $label")
            html(call, "Admin", dashboard(s, "devices", note = if (ok) "Saved." else "No such device."))
        } }
        r.post("/admin/sessions/others") { call.authed { s, _ ->
            val mine = call.request.cookies[COOKIE]
            val n = sessions.count { it.key != mine }
            sessions.keys.filter { it != mine }.forEach { sessions.remove(it) }
            audit(s.email, clientOf(call), "other_sessions_ended", n.toString())
            html(call, "Admin", dashboard(s, "activity", note = "$n other session(s) signed out."))
        } }
        r.post("/admin/recovery/new") { call.authed { s, _ ->
            val plain = List(8) { newInviteCode() + newInviteCode() }
            updateRecs { all -> all[s.email]?.let { it.recovery = plain.map { c -> Integrity.sha256(normalizeCode(c)) } } }
            audit(s.email, clientOf(call), "recovery_codes_renewed")
            html(call, "Recovery codes", recoveryPage("New recovery codes", "The old ones no longer work.", plain, "/admin/activity", "I saved them, go back"))
        } }
        r.post("/admin/test-email") { call.authed { s, _ ->
            val ok = cfg.emailConfigured && testEmail.allow("all") && notifier.email(s.email, "Focus Fence admin test", "<p>This is a test email from your Focus Fence server. Sending works.</p>")
            audit(s.email, clientOf(call), "test_email", if (ok) "sent" else "failed")
            html(call, "Admin", dashboard(s, "activity", note = if (ok) "Test email sent to ${s.email}." else "The email could not be sent. Check the server log."))
        } }
        r.post("/admin/logout") { call.authed { s, _ ->
            call.request.cookies[COOKIE]?.let { sessions.remove(it) }
            audit(s.email, clientOf(call), "logout")
            html(call, "Admin", loginForm("Signed out."))
        } }
    }

    private fun setupPage(s: Session, note: String? = null): String {
        val uri = "otpauth://totp/Focus%20Fence:${java.net.URLEncoder.encode(s.email, "UTF-8")}?secret=${s.setupSecret}&issuer=Focus%20Fence"
        return authPage("Set up", null,
            "<h1>Add an authenticator</h1>" + bad(note) +
                "<p>Scan this with an authenticator app (Aegis, Google Authenticator, 1Password, any TOTP app).</p>" +
                Qr.svg(uri, "QR code for your authenticator app") +
                "<p class='mut'>Can't scan? Enter this key instead (time-based, 6 digits), or <a href='${Web.esc(uri)}'>open it on the phone that has the app</a>.</p>" +
                "<p class='actions'><code class='key' id='totpKey'>${Web.esc(s.setupSecret)}</code> <button type='button' class='ghost' data-copy='totpKey'>Copy key</button></p>" +
                "<form method='post' action='/admin/setup'>${hidden(s)}<label for='totp'>Code the app shows now</label>" +
                "<input id='totp' name='totp' required maxlength='8' inputmode='numeric' autocomplete='one-time-code' autofocus><button class='wide'>Confirm</button></form>")
    }
}

/** One-time admin sign-in links written by the server CLI (audit A1). Only hashes are stored. */
object AdminLinks {
    @kotlinx.serialization.Serializable
    data class Link(val hash: String, val email: String, val expiresMs: Long)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val ser = kotlinx.serialization.builtins.ListSerializer(Link.serializer())

    fun read(f: File): List<Link> = if (!f.exists()) emptyList() else runCatching { json.decodeFromString(ser, f.readText()) }
        .getOrElse { System.err.println("admin-links: cannot read ${f.absolutePath}: $it (made as another user?)"); emptyList() } // audit S1: say why a link shows as invalid

    fun write(f: File, links: List<Link>) {
        val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(json.encodeToString(ser, links))
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** Returns the plain link token; stores only its hash, valid 15 minutes. */
    fun create(dataDir: File, email: String, now: Long): String {
        val f = File(dataDir, "admin-links.json")
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        write(f, read(f).filter { it.expiresMs > now } + Link(Integrity.sha256(token), email.lowercase(), now + 15 * 60_000L))
        return token
    }
}
