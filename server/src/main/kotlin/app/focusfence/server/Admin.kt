package app.focusfence.server

import io.ktor.http.ContentType
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
 * The operator page: per-person enrollment codes, devices, usage and an audit log, behind two factors
 * (a one-time code emailed to an allow-listed address, then an authenticator code or a recovery code).
 * Off unless FF_ADMIN_EMAILS is set. No passwords exist anywhere. It can never show a report token, invite
 * code, mailbox id, activity record or screenshot: none of those are read here.
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

    private class Pending(val email: String, val codeHash: String, val expires: Long, val real: Boolean, var attempts: Int = 0)
    private class Session(val email: String, val csrf: String, val created: Long, var seen: Long, var full: Boolean, var setupSecret: String = "")

    private val rnd = SecureRandom()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val recFile = File(store.dir, "admin.json")
    private val auditFile = File(store.dir, "admin-audit.jsonl")
    private val pendings = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val ipLogin = RateLimiter(10, 3_600_000, cfg.now)
    private val emailLogin = RateLimiter(3, 3_600_000, cfg.now)
    private val emailGlobal = RateLimiter(20, 24 * 3_600_000L, cfg.now)
    private val ipFail = RateLimiter(10, 3_600_000, cfg.now)
    private val allowed = cfg.adminEmails.map { it.lowercase() }.toSet()

    private fun token(n: Int = 24) = Web.run { ByteArray(n).also(rnd::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) } }
    private fun eq(a: String, b: String) = MessageDigest.isEqual(Integrity.sha256(a).toByteArray(), Integrity.sha256(b).toByteArray())
    private fun recs(): MutableMap<String, Rec> = synchronized(recFile) {
        if (!recFile.exists()) mutableMapOf() else runCatching { json.decodeFromString<MutableMap<String, Rec>>(recFile.readText()) }.getOrDefault(mutableMapOf())
    }
    private fun saveRecs(m: Map<String, Rec>) = synchronized(recFile) {
        val tmp = File(recFile.parentFile, "admin.json.tmp"); tmp.writeText(json.encodeToString(m))
        java.nio.file.Files.move(tmp.toPath(), recFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
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
        c.respondText(Web.page(title, body), ContentType.Text.Html, status)
    }

    private fun loginForm(note: String? = null) =
        "<h1>Focus Fence server admin</h1>" + (note?.let { "<p class='bad'>${Web.esc(it)}</p>" } ?: "") +
            "<form method='post' action='/admin/login'><label>Admin email<br><input name='email' type='email' required maxlength='120'></label><button>Email me a code</button></form>"

    private fun verifyForm(note: String? = null) =
        "<h1>Enter your codes</h1><p class='muted'>If that address is an admin, a one-time code was emailed (valid 10 minutes).</p>" +
            (note?.let { "<p class='bad'>${Web.esc(it)}</p>" } ?: "") +
            "<form method='post' action='/admin/verify' autocomplete='off'><label>Emailed code<br><input name='email_code' required maxlength='12' inputmode='numeric'></label>" +
            "<label>Authenticator code or a recovery code<br><input name='second' maxlength='24'></label>" +
            "<p class='muted'>First login on this server: leave the second box empty; you will set up the authenticator next.</p><button>Sign in</button></form>"

    private fun hidden(s: Session) = "<input type='hidden' name='csrf' value='${Web.esc(s.csrf)}'>"

    private fun dashboard(s: Session, newCode: String? = null, note: String? = null): String = buildString {
        append("<h1>Server admin</h1><p class='muted'>${Web.esc(s.email)} · <form style='display:inline' method='post' action='/admin/logout'>${hidden(s)}<button>Sign out</button></form></p>")
        if (note != null) append("<div class='card'>${Web.esc(note)}</div>")
        if (newCode != null) append("<div class='card'><b>New enrollment code (shown once):</b><br><code style='font-size:1.3em'>${Web.esc(newCode)}</code></div>")
        val used = store.usedBytes(cfg.now())
        append("<h2>Usage</h2><p>Data ${used / 1_048_576} of ${cfg.maxDataBytes / 1_048_576} MB · devices ${store.deviceCount()} of ${cfg.maxDevices} · mailboxes ${store.mailboxCount()} of ${cfg.maxMailboxes}</p>")
        append("<h2>Enrollment codes</h2><table>")
        for (c in codes.list()) {
            val state = if (c.revoked) "revoked" else if (c.expiresMs != 0L && cfg.now() > c.expiresMs) "expired" else "live"
            append("<tr><td>${Web.esc(c.label)}</td><td>${c.uses}/${if (c.maxUses == 0) "∞" else c.maxUses.toString()}</td><td>$state</td><td>")
            if (!c.revoked) append("<form method='post' action='/admin/codes/revoke'>${hidden(s)}<input type='hidden' name='id' value='${Web.esc(c.id)}'><button>Revoke</button></form>")
            append("</td></tr>")
        }
        append("</table><form method='post' action='/admin/codes/add'>${hidden(s)}<label>Who is it for<br><input name='label' required maxlength='60'></label>" +
            "<label>Expires in days (blank = never)<br><input name='days' inputmode='numeric' maxlength='4'></label>" +
            "<label>Max uses (blank = unlimited)<br><input name='uses' inputmode='numeric' maxlength='4'></label><button>Create a code</button></form>")
        append("<h2>Devices</h2><p class='muted'>Id, dates and partner count only. Reports, tokens and screenshots are never shown here.</p><table>")
        for (d in store.allDevices().sortedByDescending { it.lastSeenMs }) {
            append("<tr><td><code>${Web.esc(d.id.take(8))}</code></td><td>seen ${Web.time(d.lastSeenMs)}</td><td>${d.partners.size} partner(s)</td><td>" +
                "<form method='post' action='/admin/devices/remove'>${hidden(s)}<input type='hidden' name='id' value='${Web.esc(d.id)}'>" +
                "<label><input type='checkbox' name='sure' value='1' required> delete</label><button>Remove</button></form></td></tr>")
        }
        append("</table><h2>Recent admin activity</h2><table>")
        for (a in auditTail(30)) append("<tr><td>${Web.time(a["t"]?.toLongOrNull() ?: 0)}</td><td>${Web.esc(a["who"].orEmpty())}</td><td>${Web.esc(a["action"].orEmpty())} ${Web.esc(a["detail"].orEmpty())}</td><td class='muted'>${Web.esc(a["ip"].orEmpty())}</td></tr>")
        append("</table>")
    }

    private fun newSession(c: ApplicationCall, email: String, full: Boolean): Session {
        val id = token(); val s = Session(email, token(16), cfg.now(), cfg.now(), full)
        sessions[id] = s; setCookie(c, COOKIE, id, 8 * 3600); return s
    }

    private suspend fun fail(c: ApplicationCall, ip: String, why: String) {
        audit("-", ip, "login_failed", why)
        html(c, "Sign in", verifyForm("Those codes did not work."), HttpStatusCode.Unauthorized)
    }

    fun routes(r: Route) {
        if (allowed.isEmpty()) return
        r.get("/admin") {
            val s = session(call)
            when {
                s == null -> html(call, "Admin", loginForm())
                !s.full -> html(call, "Set up", setupPage(s))
                else -> html(call, "Admin", dashboard(s))
            }
        }
        r.post("/admin/login") {
            val ip = clientOf(call)
            val p = call.receiveParameters()
            val email = p["email"].orEmpty().trim().lowercase().take(120)
            if (!ipLogin.allow(ip)) return@post html(call, "Admin", loginForm("Too many attempts. Try later."), HttpStatusCode.TooManyRequests)
            val real = email in allowed
            val code = (100000 + rnd.nextInt(900000)).toString()
            // Same page and same cookie whether or not the address is an admin, so this reveals nothing.
            pendings[token().also { setCookie(call, PCOOKIE, it, 600) }] = Pending(email, Integrity.sha256(code), cfg.now() + 600_000, real)
            if (real && emailLogin.allow(email) && emailGlobal.allow("all")) {
                notifier.email(email, "Focus Fence admin sign-in code", "<p>Your sign-in code is <b>$code</b>. It expires in 10 minutes. If you did not ask for it, ignore this email.</p>")
                audit(email, ip, "login_code_sent")
            }
            html(call, "Sign in", verifyForm())
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
            val all = recs(); val rec = all[pend.email]
            if (rec == null || !rec.totpOn) {
                pendings.remove(pid)
                val s = newSession(call, pend.email, false); s.setupSecret = Totp.newSecret(rnd)
                audit(pend.email, ip, "login_email_ok_setup_needed")
                return@post html(call, "Set up", setupPage(s))
            }
            val second = p["second"].orEmpty().trim()
            val step = Totp.verify(rec.totpSecret, second, cfg.now())
            val recoveryHash = Integrity.sha256(normalizeCode(second))
            val viaRecovery = step == null && rec.recovery.any { eq(it, recoveryHash) }
            if ((step == null || step <= rec.lastStep) && !viaRecovery) return@post fail(call, ip, "second_factor")
            if (viaRecovery) rec.recovery = rec.recovery.filter { !eq(it, recoveryHash) } else rec.lastStep = step!!
            saveRecs(all)
            pendings.remove(pid)
            val s = newSession(call, pend.email, true)
            audit(pend.email, ip, if (viaRecovery) "login_ok_recovery_code" else "login_ok")
            html(call, "Admin", dashboard(s, note = if (viaRecovery) "You used a recovery code (${rec.recovery.size} left)." else null))
        }
        r.post("/admin/setup") {
            val s = session(call)?.takeIf { !it.full } ?: return@post html(call, "Admin", loginForm("Start again."), HttpStatusCode.Unauthorized)
            val p = call.receiveParameters()
            if (!eq(p["csrf"].orEmpty(), s.csrf)) return@post html(call, "Admin", loginForm("Start again."), HttpStatusCode.Forbidden)
            val step = Totp.verify(s.setupSecret, p["totp"].orEmpty(), cfg.now())
                ?: return@post html(call, "Set up", setupPage(s, "That code did not match. Check the secret and your phone's clock."), HttpStatusCode.Unauthorized)
            val plain = List(8) { newInviteCode() + newInviteCode() }
            val all = recs()
            all[s.email] = Rec(s.setupSecret, true, plain.map { Integrity.sha256(normalizeCode(it)) }, step)
            saveRecs(all)
            val full = Session(s.email, s.csrf, s.created, cfg.now(), true)
            sessions[call.request.cookies[COOKIE]!!] = full
            audit(s.email, clientOf(call), "totp_enrolled")
            html(call, "Recovery codes", "<h1>Authenticator set up</h1><p>Save these recovery codes now. Each works once if you lose your phone. They are not shown again.</p><div class='card'><code>" +
                plain.joinToString("<br>") { it.chunked(4).joinToString("-") } + "</code></div><p><a href='/admin'>Continue to the dashboard</a></p>")
        }
        suspend fun ApplicationCall.authed(block: suspend (Session, io.ktor.http.Parameters) -> Unit) {
            val s = session(this)?.takeIf { it.full } ?: return html(this, "Admin", loginForm("Sign in again."), HttpStatusCode.Unauthorized)
            val p = receiveParameters()
            if (!eq(p["csrf"].orEmpty(), s.csrf)) return html(this, "Admin", loginForm("Sign in again."), HttpStatusCode.Forbidden)
            block(s, p)
        }
        r.post("/admin/codes/add") { call.authed { s, p ->
            val days = p["days"]?.trim()?.toLongOrNull()?.coerceIn(1, 3650)
            val uses = p["uses"]?.trim()?.toIntOrNull()?.coerceIn(1, 1000) ?: 0
            val label = p["label"].orEmpty().trim().ifEmpty { "unnamed" }
            val (plain, c) = codes.create(label, cfg.now(), days?.let { cfg.now() + it * 86_400_000 } ?: 0, uses)
            audit(s.email, clientOf(call), "code_created", "${c.id} ${c.label}")
            html(call, "Admin", dashboard(s, newCode = plain))
        } }
        r.post("/admin/codes/revoke") { call.authed { s, p ->
            val id = p["id"].orEmpty(); val ok = codes.revoke(id)
            audit(s.email, clientOf(call), "code_revoked", id)
            html(call, "Admin", dashboard(s, note = if (ok) "Code revoked." else "No such code."))
        } }
        r.post("/admin/devices/remove") { call.authed { s, p ->
            val id = p["id"].orEmpty()
            val ok = p["sure"] == "1" && store.deleteDevice(id)
            audit(s.email, clientOf(call), "device_removed", id.take(8))
            html(call, "Admin", dashboard(s, note = if (ok) "Device removed." else "Nothing removed."))
        } }
        r.post("/admin/logout") { call.authed { s, _ ->
            call.request.cookies[COOKIE]?.let { sessions.remove(it) }
            audit(s.email, clientOf(call), "logout")
            html(call, "Admin", loginForm("Signed out."))
        } }
    }

    private fun setupPage(s: Session, note: String? = null): String {
        val uri = "otpauth://totp/Focus%20Fence:${java.net.URLEncoder.encode(s.email, "UTF-8")}?secret=${s.setupSecret}&issuer=Focus%20Fence"
        return "<h1>Set up your authenticator</h1>" + (note?.let { "<p class='bad'>${Web.esc(it)}</p>" } ?: "") +
            "<p>In an authenticator app (any TOTP app), add an account by entering this key (time-based, 6 digits):</p><div class='card'><code>${Web.esc(s.setupSecret)}</code></div>" +
            "<p class='muted'>Or open this link on the phone that has the app: <a href='${Web.esc(uri)}'>${Web.esc("otpauth link")}</a></p>" +
            "<form method='post' action='/admin/setup'>${hidden(s)}<label>Code the app shows now<br><input name='totp' required maxlength='8' inputmode='numeric'></label><button>Confirm</button></form>"
    }
}
