package app.focusfence.server

import app.focusfence.dns.Pairing
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.log
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * PROTOTYPE accountability backend. Two ways to be a partner, side by side:
 *
 *  1. **Web + email (+ optional SMS)** — the default. The phone enrolls a device
 *     key, uploads signed, sequence-checked batches of its activity log, and
 *     heartbeats. The owner creates an invite code and sends it to the partner,
 *     who joins on /join. The partner gets alerts and a weekly report by email
 *     (and alerts by SMS if they opt in) and a private report page.
 *  2. **App to app** — both people run the app and pair with a shared key the
 *     server never sees (see `dns/Pairing`). The server is only a *blind mailbox*:
 *     it stores ciphertext under an id it can't link to anyone.
 *
 * Env: FF_DATA_DIR (./data), FF_PORT (8080), FF_PUBLIC_URL (http://localhost:8080),
 * FF_STALE_MINUTES (240), RESEND_API_KEY, FF_MAIL_FROM, FF_SMS_PROVIDER, FF_SMS_FROM,
 * TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TELNYX_API_KEY.
 */
class Config(
    val publicUrl: String,
    /** Long enough to ride out Doze, which can hold background work for hours overnight. */
    val staleMs: Long = 240 * 60_000L,
    val reportEveryMs: Long = 7L * 24 * 3_600_000,
    val inviteTtlMs: Long = 24 * 3_600_000L,
    val smsMinIntervalMs: Long = 10 * 60_000L,
    /** Signed requests must be this fresh: stops replay of a captured request. */
    val clockSkewMs: Long = 10 * 60_000L,
    /** Hard bounds, because this box's disk is shared with other services and enroll/mailbox creation are open. */
    val maxDevices: Int = 100,
    val maxMailboxes: Int = 500,
    val maxDataBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxPartnersPerDevice: Int = 5,
    /** Whole-server email ceilings per day, whatever the number of addresses. */
    val emailGlobalDailyCap: Int = 200,
    val confirmGlobalDailyCap: Int = 30,
    /** If set, /v1/enroll needs this in the X-FF-Enroll header: open enrollment lets anyone take device slots. */
    val enrollToken: String? = null,
    /** Where a new person gets the app; the admin page's invite message links it. */
    val appUrl: String = "https://github.com/flyboy-byte/focusfence/releases/latest",
    /** Emails allowed to sign in to /admin; empty switches the admin page off. */
    val adminEmails: List<String> = emptyList(),
    /** False when no email provider is set: admin email codes would only reach the log (audit A6). */
    val emailConfigured: Boolean = true,
    /** Backstop on top of double opt-in: no address is emailed more often than this, or more than the daily cap. */
    val emailMinIntervalMs: Long = 60_000L,
    val emailDailyCap: Int = 60,
    /** A partner who never confirms their email is dropped after this. */
    val unconfirmedTtlMs: Long = 3L * 24 * 3_600_000,
    /** Which peers may set X-Forwarded-For (the reverse proxy in front of us). */
    val trustedProxy: (String) -> Boolean = TrustedProxies.LOOPBACK,
    val now: () -> Long = System::currentTimeMillis,
)

@Serializable data class EnrollRequest(val publicKeySpki: String)
@Serializable data class EnrollResponse(val deviceId: String)
@Serializable data class SignedRequest(val deviceId: String, val t: Long, val signature: String, val partnerId: String? = null)
@Serializable data class InviteResponse(val code: String, val expiresMs: Long, val joinUrl: String)
@Serializable data class PartnerInfo(val id: String, val name: String, val email: String, val sms: Boolean, val linkedMs: Long, val confirmed: Boolean = true)
@Serializable data class PartnersResponse(val partners: List<PartnerInfo>)
@Serializable data class BatchRequest(
    val deviceId: String,
    val firstSeq: Long,
    val lastSeq: Long,
    val prevHash: String,
    val recordsRaw: String,
    val signature: String,
)
@Serializable data class BatchResponse(val acceptedThrough: Long, val gap: Long, val nextHash: String)
/** Sent with a 409 so the phone can resume from where the server actually is. */
@Serializable data class CursorResponse(val error: String, val expectedNextSeq: Long, val lastHash: String)
@Serializable data class ReportRequest(val deviceId: String, val t: Long, val from: Long, val to: Long, val blob: String, val signature: String)
@Serializable data class AlertsRequest(val deviceId: String, val t: Long, val lines: List<String>, val signature: String)
@Serializable data class ReportData(val deviceId: String, val name: String, val online: Boolean, val lastSeenMs: Long, val reports: List<Store.SealedReport>)
@Serializable data class HeartbeatRequest(val deviceId: String, val t: Long, val lastSeq: Long, val signature: String)
@Serializable data class ShotRequest(val deviceId: String, val t: Long, val shotId: String, val ciphertextB64: String, val signature: String)
@Serializable data class ErrorResponse(val error: String)
@Serializable data class OkResponse(val ok: Boolean = true)
@Serializable data class CreateMailboxRequest(val tokenHash: String)
@Serializable data class PostMailRequest(val c: String)
@Serializable data class PostMailResponse(val id: Long)
@Serializable data class MailListResponse(val msgs: List<Store.MailMessage>, val lastWriteMs: Long)


fun main(args: Array<String>) {
    val env: (String) -> String? = { System.getenv(it) }
    val trusted = TrustedProxies.parse(env("FF_TRUSTED_PROXIES"))
    val cfg = Config(
        publicUrl = (env("FF_PUBLIC_URL") ?: "http://localhost:8080").trimEnd('/'),
        staleMs = (env("FF_STALE_MINUTES") ?: "240").toLong() * 60_000,
        trustedProxy = trusted,
        enrollToken = env("FF_ENROLL_TOKEN")?.takeIf { it.isNotBlank() },
        appUrl = env("FF_APP_URL")?.takeIf { it.startsWith("https://") } ?: "https://github.com/flyboy-byte/focusfence/releases/latest",
        adminEmails = env("FF_ADMIN_EMAILS").orEmpty().split(",").map { it.trim() }.filter { it.contains("@") },
        emailConfigured = !env("RESEND_API_KEY").isNullOrBlank(),
        maxDevices = (env("FF_MAX_DEVICES") ?: "100").toInt(),
        maxMailboxes = (env("FF_MAX_MAILBOXES") ?: "500").toInt(),
        maxDataBytes = (env("FF_MAX_DATA_MB") ?: "2048").toLong() * 1024 * 1024,
    )
    if (runAdmin(args, File(env("FF_DATA_DIR") ?: "data"), System.currentTimeMillis(), ::println)) return
    val store = Store(File(env("FF_DATA_DIR") ?: "data"))
    store.purgeLegacyRecords().takeIf { it > 0 }?.let { println("D-023: deleted $it readable activity record files from earlier versions") }
    val notifier = LiveNotifier(
        resendKey = env("RESEND_API_KEY"),
        mailFrom = env("FF_MAIL_FROM") ?: "accountability@localhost",
        smsProvider = env("FF_SMS_PROVIDER") ?: "none",
        smsFrom = env("FF_SMS_FROM"),
        env = env,
    )
    val alerts = Alerts(store, notifier, cfg)
    thread(isDaemon = true, name = "sweep") {
        while (true) {
            runCatching { runBlocking { alerts.sweep() } }
            Thread.sleep(60_000)
        }
    }
    embeddedServer(CIO, host = env("FF_HOST") ?: "0.0.0.0", port = (env("FF_PORT") ?: "8080").toInt()) { module(store, notifier, cfg) }.start(wait = true)
}

/** Fixed-window limiter for the few unauthenticated endpoints (invite codes are short). */
class RateLimiter(private val max: Int, private val windowMs: Long, private val now: () -> Long) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()
    private val calls = java.util.concurrent.atomic.AtomicLong()
    fun allow(key: String): Boolean {
        val t = now()
        // Audit A7: drop keys whose hits have all aged out, so per-address/IP maps can't grow forever. Both the sweep and
        // the check run inside the map's per-key compute, so a sweep can't drop a list a caller is about to add to (S3).
        if (calls.incrementAndGet() % 1024 == 0L) for (k in hits.keys) hits.computeIfPresent(k) { _, v -> if (v.all { t - it > windowMs }) null else v }
        var ok = false
        hits.compute(key) { _, v ->
            val list = v ?: ArrayList()
            list.removeAll { t - it > windowMs }
            if (list.size < max) { list += t; ok = true }
            list
        }
        return ok
    }
}

/**
 * The address to rate-limit by. Behind a reverse proxy every connection arrives from the proxy, so the peer
 * address alone would put every partner in one bucket. The proxy header is believed ONLY when the peer is a
 * trusted proxy ([TrustedProxies]: loopback by default, `FF_TRUSTED_PROXIES` to add your proxy's network, e.g.
 * the Docker network); from anyone else it is forged input, so the peer address is used. The rightmost entry is
 * the one our proxy appended, so a client-supplied prefix cannot choose its own bucket.
 */
internal fun clientKey(peer: String, forwardedFor: String?, trusted: (String) -> Boolean = TrustedProxies.LOOPBACK): String {
    if (!trusted(peer)) return peer
    val last = forwardedFor?.split(',')?.lastOrNull()?.trim().orEmpty()
    return if (last.isNotEmpty() && last.length <= 45) last else peer
}

private val random = SecureRandom()
private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** 8 Crockford chars (40 bits), shown XXXX-XXXX. Short enough to text; single use, 24 h, rate-limited. */
fun newInviteCode(): String = buildString { repeat(8) { append(CODE_ALPHABET[random.nextInt(32)]) } }

fun normalizeCode(s: String): String =
    s.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')

fun normalizePhone(s: String): String? {
    val digits = s.filter { it.isDigit() }
    if (digits.length !in 8..15) return null
    return "+$digits"
}

private fun newToken(): String = ByteArray(24).also { random.nextBytes(it) }
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun smsCode(): String = (100000 + random.nextInt(900000)).toString()

fun Application.module(store: Store, notifier: Notifier, cfg: Config) {
    val enrollCodes = EnrollCodes(File(store.dir, "codes.json"))
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    // The partner pages are plain server-rendered HTML with inline CSS and no scripts, so a strict CSP costs nothing
    // and turns any future escaping slip into a non-event.
    install(createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            // Scripts only where D-023 needs them (the partner's browser opens the sealed report), and only our own file.
            val path = call.request.local.uri.substringBefore('?')
            // The admin page has one too (/static/admin.js): copy and download buttons, nothing that talks to the server.
            val scripts = path.startsWith("/p/") || path.startsWith("/i/") || path == "/join" || path == "/key" || path.startsWith("/admin")
            call.response.headers.append("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'" +
                    if (scripts) "; script-src 'self'; connect-src 'self'" else "")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
        }
    })
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Never echo the exception text: it can carry class names or file paths. Keep it in our log only.
            call.application.log.warn("request failed: ${cause::class.simpleName}: ${(cause.message ?: "").filter { !it.isISOControl() }.take(120)}")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad request"))
        }
    }
    val alerts = Alerts(store, notifier, cfg)
    val joinLimiter = RateLimiter(10, 3_600_000, cfg.now)
    val smsLimiter = RateLimiter(3, 3_600_000, cfg.now)
    val enrollLimiter = RateLimiter(5, 3_600_000, cfg.now)
    val mailboxLimiter = RateLimiter(20, 3_600_000, cfg.now)
    val confirmLimiter = RateLimiter(1, 3_600_000, cfg.now) // confirmation emails per address
    val confirmGlobal = RateLimiter(cfg.confirmGlobalDailyCap, 24 * 3_600_000L, cfg.now)

    fun ApplicationCall.client() = clientKey(request.origin.remoteHost, request.headers["X-Forwarded-For"], cfg.trustedProxy)
    fun full(): Boolean = store.usedBytes(cfg.now()) >= cfg.maxDataBytes
    /** One confirmation email per address per hour; that cap holds for resends too. */
    suspend fun sendConfirmation(deviceId: String, partner: Store.Partner) {
        if (!confirmLimiter.allow(partner.email.lowercase()) || !confirmGlobal.allow("all")) return
        // A new single-use token every time (only its hash is stored), so an older email's link stops working...
        val token = newToken()
        val hash = Integrity.sha256(token)
        var previous: String? = null
        store.updateDevice(deviceId) { d -> d.partners.firstOrNull { it.id == partner.id }?.let { previous = it.confirmHash; it.confirmHash = hash } }
        val sent = notifier.email(
            partner.email, "Focus Fence: confirm your email",
            "<p>Someone used an invite to add this address as an accountability partner for a Focus Fence phone.</p>" +
                "<p>If that was you, <a href=\"${cfg.publicUrl}/confirm/$token\">confirm here</a>. " +
                "If it wasn't, ignore this email: nothing else will be sent, and the request expires in a few days.</p>",
        )
        // ...but only if the email really went out: otherwise nobody holds the new link, and the old one must keep working.
        if (!sent) store.updateDevice(deviceId) { d -> d.partners.firstOrNull { it.id == partner.id && it.confirmHash == hash }?.confirmHash = previous }
    }
    suspend fun ApplicationCall.serverFull(): Unit {
        // Visible to the operator: a full server silently refuses every phone.
        application.log.warn("server full: refusing {} (data {} MB of {} MB, {} devices of {})",
            request.local.uri.substringBefore('?'), store.usedBytes(cfg.now()) / 1_048_576, cfg.maxDataBytes / 1_048_576, store.deviceCount(), cfg.maxDevices)
        respond(HttpStatusCode.InsufficientStorage, ErrorResponse("server full"))
    }

    fun signedOk(device: Store.Device, action: String, t: Long, extra: String, sig: String): Boolean {
        if (abs(cfg.now() - t) > cfg.clockSkewMs) return false
        val key = runCatching { Integrity.publicKeyFromBase64Spki(device.publicKeySpki) }.getOrNull() ?: return false
        return Integrity.verifySignature(key, Integrity.requestMessage(action, device.id, t, extra), sig)
    }

    suspend fun ApplicationCall.unauthorized() = respond(HttpStatusCode.Unauthorized, ErrorResponse("bad signature or stale request"))
    suspend fun ApplicationCall.unknownDevice() = respond(HttpStatusCode.NotFound, ErrorResponse("unknown device"))

    routing {
        Admin(cfg, store, notifier, enrollCodes) { it.client() }.routes(this)
        get("/") { call.respondText("Focus Fence accountability server (prototype)") }
        // For container health checks and uptime monitors: no auth, no data, no store access.
        get("/healthz") { call.respondText("ok") }

        // ---- device API -------------------------------------------------------

        post("/v1/enroll") {
            val req = call.receive<EnrollRequest>()
            if (!enrollLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("slow down"))
            val codesOn = cfg.enrollToken != null || enrollCodes.any()
            var needsCode = codesOn
            if (codesOn) {
                val got = call.request.headers["X-FF-Enroll"].orEmpty()
                val boot = cfg.enrollToken?.let { java.security.MessageDigest.isEqual(Integrity.sha256(got).toByteArray(), Integrity.sha256(it).toByteArray()) } ?: false
                if (boot) needsCode = false
                else if (got.isEmpty()) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("enrollment code needed"))
            }
            if (full() || store.deviceCount() >= cfg.maxDevices) return@post call.serverFull()
            runCatching { Integrity.publicKeyFromBase64Spki(req.publicKeySpki) }
                .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad key")) }
            val usedCode = if (needsCode) enrollCodes.redeemId(call.request.headers["X-FF-Enroll"].orEmpty(), cfg.now())
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("enrollment code needed")) else ""
            val created = store.createDevice(req.publicKeySpki, cfg.now())
            if (usedCode.isNotEmpty()) store.updateDevice(created.id) { it.codeId = usedCode }
            call.respond(EnrollResponse(created.id))
        }

        post("/v1/invites") {
            val req = call.receive<SignedRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "invite", req.t, "", req.signature)) return@post call.unauthorized()
            val code = newInviteCode()
            // The link's own long secret: unlike the short code it stays valid once used, as that partner's report link.
            val secret = newToken()
            val expires = cfg.now() + cfg.inviteTtlMs
            store.updateDevice(device.id) { d ->
                // At most 5 live invites; expired ones are dropped.
                d.invites = (d.invites.filter { it.expiresMs > cfg.now() } + Store.Invite(Integrity.sha256(code), expires, Integrity.sha256(secret))).takeLast(5)
            }
            val shown = code.chunked(4).joinToString("-")
            call.respond(InviteResponse(shown, expires, "${cfg.publicUrl}/i/$secret"))
        }

        post("/v1/partners/list") {
            val req = call.receive<SignedRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "partners", req.t, "", req.signature)) return@post call.unauthorized()
            call.respond(PartnersResponse(device.partners.map {
                PartnerInfo(it.id, it.name, Web.mask(it.email), it.phoneVerified, it.linkedMs, it.confirmed)
            }))
        }

        // Unlinking is never silent: the removed partner is told.
        post("/v1/partners/unlink") {
            val req = call.receive<SignedRequest>()
            val pid = req.partnerId ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("partnerId"))
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "unlink", req.t, pid, req.signature)) return@post call.unauthorized()
            val removed = device.partners.firstOrNull { it.id == pid }
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown partner"))
            alerts.toPartners(device.copy(partners = listOf(removed)), "you were removed as a partner",
                listOf("The phone's owner removed you as their accountability partner. You won't get further reports."))
            store.updateDevice(device.id) { d -> d.partners = d.partners.filter { it.id != pid } }
            call.respond(OkResponse())
        }

        post("/v1/batches") {
            val req = call.receive<BatchRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (full()) return@post call.serverFull()
            val key = Integrity.publicKeyFromBase64Spki(device.publicKeySpki)
            val msg = Integrity.batchMessage(req.deviceId, req.firstSeq, req.lastSeq, req.prevHash, req.recordsRaw)
            val sigOk = Integrity.verifySignature(key, msg, req.signature)
            if (req.recordsRaw.length > 512 * 1024) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("batch too large"))
            }
            when (val r = Integrity.check(sigOk, req.firstSeq, req.lastSeq, req.prevHash,
                device.lastHash, device.expectedNextSeq, req.recordsRaw)) {
                is Integrity.Result.Reject -> if (r.reason == "bad signature") {
                    call.unauthorized()
                } else {
                    call.respond(HttpStatusCode.Conflict, CursorResponse(r.reason, device.expectedNextSeq, device.lastHash))
                }
                is Integrity.Result.Ok -> {
                    // A device's first batch starts wherever its local log was at
                    // enrollment; that jump isn't missing data.
                    val gap = if (device.expectedNextSeq > 1) r.gap else 0
                    // D-023: an older app's records are read for alerts here, in memory, and never stored.
                    store.advanceCursor(device.id, req.lastSeq, r.newHash, gap, cfg.now())
                    val lines = alerts.alertLines(req.recordsRaw).toMutableList()
                    if (gap > 0) lines += "$gap activity records never arrived (deleted, or lost on the phone)"
                    if (lines.isNotEmpty()) alerts.toPartners(device, "protection changed", lines)
                    call.respond(BatchResponse(req.lastSeq, gap, r.newHash))
                }
            }
        }

        post("/v1/heartbeat") {
            val req = call.receive<HeartbeatRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "heartbeat", req.t, req.lastSeq.toString(), req.signature)) return@post call.unauthorized()
            store.updateDevice(device.id) { d ->
                d.lastSeenMs = cfg.now()
                d.staleAlerted = false
            }
            call.respond(OkResponse())
        }

        // D-023: the partner report, sealed on the phone; stored as ciphertext and opened only in the partner's browser.
        post("/v1/reports") {
            val req = call.receive<ReportRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (req.blob.length > Store.REPORT_MAX_CHARS) return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
            if (!signedOk(device, "report", req.t, "${req.from}|${req.to}|${Integrity.sha256(req.blob)}", req.signature)) return@post call.unauthorized()
            if (req.to < req.from || req.to > cfg.now() + cfg.clockSkewMs) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad range"))
            if (full()) return@post call.serverFull()
            store.saveReport(device.id, Store.SealedReport(req.t, req.from, req.to, req.blob), cfg.now())
            store.updateDevice(device.id) { d -> d.lastSeenMs = cfg.now(); d.staleAlerted = false }
            call.respond(OkResponse())
        }

        // D-023: protection changes arrive as the phone's own alert lines (Digest.alertText), never as records.
        post("/v1/alerts") {
            val req = call.receive<AlertsRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            val lines = req.lines.take(20).map { l -> l.filter { !it.isISOControl() || it == ' ' }.take(300) }.filter { it.isNotBlank() }
            if (!signedOk(device, "alerts", req.t, Integrity.sha256(req.lines.take(20).joinToString("\n")), req.signature)) return@post call.unauthorized()
            if (lines.isNotEmpty()) alerts.toPartners(device, "protection changed", lines)
            call.respond(OkResponse())
        }

        // Encrypted screenshot blob; the server stores ciphertext only.
        post("/v1/shots") {
            val req = call.receive<ShotRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "shot", req.t, req.shotId, req.signature)) return@post call.unauthorized()
            if (full()) return@post call.serverFull()
            if (req.ciphertextB64.length > Store.SHOT_MAX_BYTES * 4 / 3 + 16) return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
            val bytes = Base64.getDecoder().decode(req.ciphertextB64)
            if (bytes.size > Store.SHOT_MAX_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
            store.saveBlob(req.deviceId, req.shotId, bytes)
            call.respond(OkResponse())
        }

        // ---- blind mailbox (app to app) -------------------------------------
        // The id and token are derived from a pairing key the server never sees.
        // The server keeps only sha256(token), so it can't post as either side.

        post("/v1/mailbox/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!MAILBOX_ID.matches(id)) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad id"))
            val req = call.receive<CreateMailboxRequest>()
            if (store.mailbox(id) == null) { // an existing mailbox is just re-checked; only NEW ones are limited
                if (!mailboxLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("slow down"))
                if (full() || store.mailboxCount() >= cfg.maxMailboxes) return@post call.serverFull()
            }
            if (req.tokenHash.length != 43) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad token hash"))
            val mb = store.createMailbox(id, req.tokenHash, cfg.now())
            if (mb.tokenHash != req.tokenHash) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("taken"))
            call.respond(OkResponse())
        }

        post("/v1/mailbox/{id}/msgs") {
            val id = call.parameters["id"].orEmpty()
            if (!MAILBOX_ID.matches(id)) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad id"))
            if (full()) return@post call.serverFull()
            val mb = store.mailbox(id) ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no mailbox"))
            if (!tokenOk(call, mb)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bad token"))
            val req = call.receive<PostMailRequest>()
            if (req.c.length > 96 * 1024) return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
            call.respond(PostMailResponse(store.postMail(id, req.c, cfg.now())))
        }

        get("/v1/mailbox/{id}/msgs") {
            val id = call.parameters["id"].orEmpty()
            if (!MAILBOX_ID.matches(id)) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad id"))
            val mb = store.mailbox(id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no mailbox"))
            if (!tokenOk(call, mb)) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bad token"))
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            call.respond(MailListResponse(store.readMail(id, after), mb.lastWriteMs))
        }

        // ---- partner web pages ---------------------------------------------

        // The partner's one link (D-026). Before joining: the join form. After joining and confirming the address: on to
        // the report, and the browser carries the '#k=' key across the redirect, so it works in any browser, any time.
        get("/i/{secret}") {
            val secret = call.parameters["secret"].orEmpty()
            if (secret.length < 20) return@get call.respondText(Web.message("Not found", "This link isn't valid."), ContentType.Text.Html, HttpStatusCode.NotFound)
            val hash = Integrity.sha256(secret)
            store.partnerByLinkHash(hash)?.let { (_, partner) ->
                return@get if (partner.confirmed) call.respondRedirect("/p/${partner.token}")
                else call.respondText(Web.message("Confirm your email first",
                    "We emailed ${Web.mask(partner.email)} a confirmation link. Click it, then open this same link again: it's your report link from then on."),
                    ContentType.Text.Html)
            }
            val now = cfg.now()
            if (store.allDevices().none { d -> d.invites.any { it.linkHash == hash && it.expiresMs > now } }) {
                return@get call.respondText(Web.message("Not found", "This invite isn't valid. It may have expired; ask for a new one."), ContentType.Text.Html, HttpStatusCode.NotFound)
            }
            call.respondText(Web.joinForm("", null, notifier.smsAvailable, link = secret), ContentType.Text.Html)
        }

        get("/join") {
            val code = call.request.queryParameters["code"].orEmpty()
            call.respondText(Web.joinForm(code, null, notifier.smsAvailable), ContentType.Text.Html)
        }

        post("/join") {
            val form = call.receiveParameters()
            val rawCode = form["code"].orEmpty()
            if (!joinLimiter.allow(call.client())) {
                return@post call.respondText(Web.joinForm(rawCode, "Too many attempts. Try again in an hour.", notifier.smsAvailable),
                    ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            }
            val rawLink = form["link"].orEmpty().take(64)
            val linkHash = if (rawLink.length >= 20) Integrity.sha256(rawLink) else ""
            val codeHash = Integrity.sha256(normalizeCode(rawCode))
            fun again(err: String) = Web.joinForm(rawCode, err, notifier.smsAvailable, link = rawLink.takeIf { linkHash.isNotEmpty() })
            val name = form["name"].orEmpty().filter { !it.isISOControl() }.trim().take(60)
            val email = form["email"].orEmpty().trim().take(120)
            if (name.isEmpty() || !EMAIL.matches(email)) {
                return@post call.respondText(again("Enter your name and email."), ContentType.Text.Html)
            }
            val now = cfg.now()
            fun Store.Invite.matches() = expiresMs > now && (if (linkHash.isNotEmpty()) this.linkHash == linkHash else this.codeHash == codeHash)
            val device = store.allDevices().firstOrNull { d -> d.invites.any { it.matches() } }
                ?: return@post call.respondText(again("That invite isn't valid. It may have expired or already been used."), ContentType.Text.Html)
            val invite = device.invites.first { it.matches() }
            if (device.partners.size >= cfg.maxPartnersPerDevice) {
                return@post call.respondText(again("This phone already has the most partners it can have."), ContentType.Text.Html)
            }
            // Double opt-in: the address is NOT trusted yet. Until its owner clicks the link below, the only thing
            // ever sent to it is that one email, so a stranger's address can't be signed up to receive alerts.
            val partner = Store.Partner(java.util.UUID.randomUUID().toString(), name, email, newToken(), now, confirmed = false,
                linkHash = invite.linkHash.ifEmpty { null })
            store.updateDevice(device.id) { d ->
                d.invites = d.invites.filter { it.codeHash != invite.codeHash } // single use: the code is spent, the link now belongs to this partner
                d.partners = d.partners + partner
            }
            sendConfirmation(device.id, partner)
            // No redirect to the report: the joiner must not receive any token for an address they haven't proven they own.
            call.respondText(Web.joinPending(partner.id), ContentType.Text.Html)

        }

        // The first email can fail (provider down, sender not verified) or land in spam: let the joiner ask again.
        // Keyed by partner id, which grants nothing; the per-address hourly cap and the per-client join cap still apply.
        post("/join/resend/{pid}") {
            if (!joinLimiter.allow(call.client())) return@post call.respondText(Web.message("Slow down", "Too many attempts. Try again in an hour."), ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            val found = store.partnerById(call.parameters["pid"].orEmpty())
            if (found != null && !found.second.confirmed) sendConfirmation(found.first.id, found.second)
            call.respondText(Web.joinPending(call.parameters["pid"].orEmpty().take(64), "If that request is still open, we sent the email again."), ContentType.Text.Html)
        }

        // The email link only SHOWS a button; confirming is a POST, so a mail scanner that opens links can't confirm for you.
        get("/confirm/{token}") {
            val token = call.parameters["token"].orEmpty()
            store.partnerByConfirmToken(token)
                ?: return@get call.respondText(Web.message("Not found", "This link isn't valid or has already been used."), ContentType.Text.Html, HttpStatusCode.NotFound)
            call.respondText(Web.confirmForm(token), ContentType.Text.Html)
        }

        post("/confirm/{token}") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByConfirmToken(token)
                ?: return@post call.respondText(Web.message("Not found", "This link isn't valid or has already been used."), ContentType.Text.Html, HttpStatusCode.NotFound)
            run {
                store.updateDevice(device.id) { d -> d.partners.firstOrNull { it.id == partner.id }?.apply { confirmed = true; confirmHash = null } }
                notifier.email(
                    partner.email, "Focus Fence: you're now an accountability partner",
                    "<p>Hi ${Web.esc(partner.name)}, you'll get a weekly report and alerts by email.</p>" +
                        "<p>Your private report page (keep this link to yourself): <a href=\"${alerts.link(partner)}\">${alerts.link(partner)}</a></p>",
                )
            }
            // Only now does the confirmed owner of the address get their report link (here, and in the email).
            call.respondRedirect("/p/${partner.token}?welcome=1")
        }

        get("/p/{token}") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respondText(Web.message("Not found", "This link isn't valid any more."), ContentType.Text.Html, HttpStatusCode.NotFound)
            val notice = when {
                call.request.queryParameters["welcome"] != null && !partner.confirmed ->
                    "Check your email and confirm your address to start getting alerts. Until then nothing is sent to you."
                call.request.queryParameters["welcome"] != null -> "You're linked. Bookmark this page — it's your private report."
                call.request.queryParameters["sms"] == "on" -> "Text alerts are on."
                call.request.queryParameters["sms"] == "bad" -> "That code didn't match. Try again."
                else -> null
            }
            call.respondText(Web.partnerPage(partner, cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs, notifier.smsAvailable, notice),
                ContentType.Text.Html)
        }

        // The sealed report slices for this partner's phone. Ciphertext only: the key is in the partner's browser.
        get("/p/{token}/data") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(ReportData(device.id, partner.name, cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs,
                store.readReports(device.id).filter { cfg.now() - it.to <= cfg.reportEveryMs + 3_600_000L }))
        }

        // Saves a report key sent as a link (new device or cleared browser). The key stays after '#', in the browser.
        get("/key") { call.respondText(Web.keyPage(), ContentType.Text.Html) }

        for (name in listOf("report.js", "admin.js")) get("/static/$name") {
            val js = object {}.javaClass.getResource("/static/$name")?.readText()
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(js, ContentType.parse("text/javascript"))
        }

        post("/p/{token}/sms") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val form = call.receiveParameters()
            val phone = form["phone"]?.let(::normalizePhone)
            if (partner.confirmed && phone != null && form["sms_consent"] == "yes" && notifier.smsAvailable) {
                startSmsVerification(store, notifier, smsLimiter, cfg, device.id, partner.id, phone)
            }
            call.respondRedirect("/p/$token")
        }

        post("/p/{token}/sms/verify") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val code = call.receiveParameters()["code"].orEmpty().filter { it.isDigit() }
            val ok = partner.smsCodeHash != null && cfg.now() < partner.smsCodeExpiresMs &&
                Integrity.sha256(code) == partner.smsCodeHash
            if (ok) {
                store.updateDevice(device.id) { d ->
                    d.partners.firstOrNull { it.id == partner.id }?.apply {
                        phoneVerified = true
                        smsCodeHash = null
                    }
                }
            }
            call.respondRedirect("/p/$token?sms=${if (ok) "on" else "bad"}")
        }

        post("/p/{token}/sms/stop") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            store.updateDevice(device.id) { d ->
                d.partners.firstOrNull { it.id == partner.id }?.apply {
                    phone = null
                    phoneVerified = false
                }
            }
            call.respondRedirect("/p/$token")
        }

        post("/p/{token}/leave") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            store.updateDevice(device.id) { d -> d.partners = d.partners.filter { it.id != partner.id } }
            call.respondText(Web.message("Done", "You're no longer their accountability partner."), ContentType.Text.Html)
        }
    }
}

private val MAILBOX_ID = Regex("^[0-9a-f]{64}$")
private val EMAIL = Regex("^[^\\s@<>\"',;]{1,64}@[A-Za-z0-9.-]{1,80}\\.[A-Za-z]{2,24}$")

private fun tokenOk(call: ApplicationCall, mb: Store.Mailbox): Boolean {
    val token = call.request.headers["X-FF-Token"] ?: return false
    return java.security.MessageDigest.isEqual(Pairing.tokenHash(token).toByteArray(), mb.tokenHash.toByteArray())
}

private suspend fun startSmsVerification(
    store: Store, notifier: Notifier, limiter: RateLimiter, cfg: Config,
    deviceId: String, partnerId: String, phone: String,
) {
    if (!limiter.allow(partnerId)) return
    val code = smsCode()
    store.updateDevice(deviceId) { d ->
        d.partners.firstOrNull { it.id == partnerId }?.apply {
            this.phone = phone
            phoneVerified = false
            smsCodeHash = Integrity.sha256(code)
            smsCodeExpiresMs = cfg.now() + 15 * 60_000
        }
    }
    notifier.sms(phone, "Focus Fence code: $code. Confirms text alerts for an accountability partner. Reply STOP to opt out.")
}
