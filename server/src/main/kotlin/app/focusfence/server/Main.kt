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
 * FF_STALE_MINUTES (60), RESEND_API_KEY, FF_MAIL_FROM, FF_SMS_PROVIDER, FF_SMS_FROM,
 * TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TELNYX_API_KEY.
 */
class Config(
    val publicUrl: String,
    /**
     * Silence before partners hear "phone went silent". Phones check in about every 15 minutes, day and night
     * (2026-11-25 export: no gap over 21 minutes), so 60 minutes is four missed check-ins.
     */
    val staleMs: Long = 60 * 60_000L,
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
    /** The app the invite page's "Open in the app" button targets (FF_ANDROID_PACKAGE; only a fork changes it). */
    val androidPackage: String = Web.DEFAULT_PACKAGE,
)

@Serializable data class EnrollRequest(val publicKeySpki: String)
@Serializable data class EnrollResponse(val deviceId: String)
@Serializable data class SignedRequest(val deviceId: String, val t: Long, val signature: String, val partnerId: String? = null, val kind: String? = null)
@Serializable data class InviteResponse(val code: String, val expiresMs: Long, val joinUrl: String, val inviteId: String = "")
@Serializable data class AcceptRequest(val deviceId: String, val t: Long, val signature: String, val secret: String, val name: String = "")
@Serializable data class AcceptResponse(val ownerId: String, val ownerName: String, val reportUrl: String = "")
@Serializable data class AppPartnerInfo(val id: String, val name: String, val inviteId: String, val linkedMs: Long, val lastViewMs: Long = 0, val readsFeed: Boolean = false)
@Serializable data class PartnerInfo(val id: String, val name: String, val email: String, val sms: Boolean, val linkedMs: Long, val confirmed: Boolean = true, val inviteId: String = "", val lastViewMs: Long = 0)
@Serializable data class PartnersResponse(val partners: List<PartnerInfo>, val app: List<AppPartnerInfo> = emptyList())
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
/** [minor]: indices of housekeeping lines (plan 12 §5). Unsigned: getting it wrong only changes when a partner hears. */
@Serializable data class AlertsRequest(val deviceId: String, val t: Long, val lines: List<String>, val signature: String, val minor: List<Int> = emptyList())
@Serializable data class VersionResponse(val protocol: Int, val oldestApp: Int, val server: String)
/** The release this server was built from (the tarball's VERSION file), or "dev". */
val serverVersion: String by lazy {
    // The install is <root>/lib/*.jar with VERSION in <root> (install.sh and the Docker image both lay it out so).
    runCatching {
        val jar = java.io.File(VersionResponse::class.java.protectionDomain.codeSource.location.toURI())
        java.io.File(jar.parentFile.parentFile, "VERSION").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: "dev"
}
@Serializable data class AlertFeed(
    val online: Boolean, val lastSeenMs: Long, val silentAfterMs: Long, val alerts: List<Store.AlertEntry>,
    /** Plan 12 §5: this partner's own choices, which their app applies in their phone's time zone. */
    val level: String = "all", val quiet: Boolean = false,
)
@Serializable data class ReportData(val deviceId: String, val name: String, val online: Boolean, val lastSeenMs: Long, val reports: List<Store.SealedReport>)
@Serializable data class HeartbeatRequest(
    val deviceId: String, val t: Long, val lastSeq: Long, val signature: String,
    /** After a long gap: the phone's account of it ("no internet; protection stayed on"), signed on its own. */
    val gap: String? = null, val gapSignature: String? = null,
    /** The newest owner notice the phone already has; only newer ones come back. Read cursor, not signed. */
    val noticesAfter: Long = 0,
)
/** Plan 12: owner notices ride on the heartbeat. Older apps read only the status code, so this adds nothing for them. */
@Serializable data class HeartbeatResponse(
    val ok: Boolean = true, val notices: List<Store.AlertEntry> = emptyList(),
    /** Plan 12 §3: the owner's latest ask-a-partner request, while it can still matter to the phone. */
    val unlock: UnlockStatus? = null,
)
@Serializable data class UnlockStatus(val id: String, val action: String, val state: String, val by: String, val askedMs: Long, val decidedMs: Long)

private fun Store.UnlockRequest.status(now: Long) =
    UnlockStatus(id, action, app.focusfence.dns.UnlockAsk.state(state, askedMs, now), by, askedMs, decidedMs)
@Serializable data class ShotRequest(val deviceId: String, val t: Long, val shotId: String, val ciphertextB64: String, val signature: String)
@Serializable data class ErrorResponse(val error: String)
@Serializable data class OkResponse(val ok: Boolean = true)
@Serializable data class CreateMailboxRequest(val tokenHash: String, val deviceId: String = "", val t: Long = 0, val signature: String = "")
@Serializable data class PostMailRequest(val c: String)
@Serializable data class PostMailResponse(val id: Long)
@Serializable data class MailListResponse(val msgs: List<Store.MailMessage>, val lastWriteMs: Long)


fun main(args: Array<String>) {
    val env: (String) -> String? = { System.getenv(it) }
    val trusted = TrustedProxies.parse(env("FF_TRUSTED_PROXIES"))
    val cfg = Config(
        publicUrl = (env("FF_PUBLIC_URL") ?: "http://localhost:8080").trimEnd('/'),
        androidPackage = env("FF_ANDROID_PACKAGE")?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_.]{3,100}")) } ?: Web.DEFAULT_PACKAGE,
        staleMs = (env("FF_STALE_MINUTES") ?: "60").toLong() * 60_000,
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
    // D-041: every response says which protocol levels this server speaks, so an app can tell its owner which side
    // needs updating instead of failing in odd ways.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        call.response.headers.append(app.focusfence.dns.Protocol.HEADER_SERVER, app.focusfence.dns.Protocol.LEVEL.toString())
        call.response.headers.append(app.focusfence.dns.Protocol.HEADER_SERVER_OLDEST, app.focusfence.dns.Protocol.OLDEST.toString())
    }
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

    /** The enrolled phone whose live invite link has this secret, and that invite. */
    fun inviteOwner(secret: String): Pair<Store.Device, Store.Invite>? {
        val hash = Integrity.sha256(secret); val now = cfg.now()
        for (d in store.allDevices()) d.invites.firstOrNull { it.linkHash == hash && it.expiresMs > now }?.let { return d to it }
        return null
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
        get("/v1/version") {
            call.respond(VersionResponse(app.focusfence.dns.Protocol.LEVEL, app.focusfence.dns.Protocol.OLDEST, serverVersion))
        }

        // ---- device API -------------------------------------------------------

        post("/v1/enroll") {
            val req = call.receive<EnrollRequest>()
            if (!enrollLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("slow down"))
            val codesOn = cfg.enrollToken != null || enrollCodes.any()
            var needsCode = codesOn
            // D-035: a live invite from an enrolled phone lets the phone it was sent to enroll, with no separate code.
            val invited = call.request.headers["X-FF-Invite"]?.takeIf { it.length >= 20 }?.let { inviteOwner(it) } != null
            if (invited) needsCode = false
            else if (codesOn) {
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
            val inviteId = java.util.UUID.randomUUID().toString()
            store.updateDevice(device.id) { d ->
                // At most 5 live invites; expired ones are dropped.
                d.invites = (d.invites.filter { it.expiresMs > cfg.now() } + Store.Invite(Integrity.sha256(code), expires, Integrity.sha256(secret), inviteId, if (req.kind == "enroll") "enroll" else "")).takeLast(5)
            }
            val shown = code.chunked(4).joinToString("-")
            call.respond(InviteResponse(shown, expires, "${cfg.publicUrl}/i/$secret", inviteId))
        }

        // D-035: the invite opened in the app instead of a browser. The calling phone (enrolled, often just now with
        // this same invite) becomes an app partner of the phone that made it. Single use, like the web join.
        post("/v1/invites/accept") {
            val req = call.receive<AcceptRequest>()
            val me = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(me, "accept", req.t, Integrity.sha256(req.secret), req.signature)) return@post call.unauthorized()
            if (!joinLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("slow down"))
            val (owner, invite) = inviteOwner(req.secret)?.takeIf { !it.second.enrollOnly } ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("invite not valid"))
            if (owner.id == me.id) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("that's this phone's own invite"))
            // Five of each kind (Logan, 2026-11-25), like email partners at /join.
            if (owner.appPartners.size >= cfg.maxPartnersPerDevice) return@post call.respond(HttpStatusCode.Conflict,
                ErrorResponse("This phone already has ${cfg.maxPartnersPerDevice} app partners, the most it can have."))
            val name = req.name.filter { !it.isISOControl() }.trim().take(60).ifEmpty { me.label.ifEmpty { "Partner" } }
            val token = newToken()
            store.updateDevice(owner.id) { d ->
                d.invites = d.invites.filter { it.linkHash != invite.linkHash }
                d.appPartners = d.appPartners.filter { it.deviceId != me.id } + Store.AppPartner(java.util.UUID.randomUUID().toString(), me.id, name, cfg.now(), invite.id, token)
            }
            // The same report an email partner reads: same page, same sealed data, same screenshots. The key stays on the phone.
            call.respond(AcceptResponse(owner.id, owner.label, "${cfg.publicUrl}/p/$token"))
        }

        post("/v1/partners/list") {
            val req = call.receive<SignedRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "partners", req.t, "", req.signature)) return@post call.unauthorized()
            call.respond(PartnersResponse(device.partners.map {
                PartnerInfo(it.id, it.name, Web.mask(it.email), it.phoneVerified, it.linkedMs, it.confirmed, it.inviteId, it.lastViewMs)
            }, device.appPartners.map { AppPartnerInfo(it.id, it.name, it.inviteId, it.linkedMs, it.lastViewMs, it.lastFeedMs > 0) }))
        }

        // Unlinking is never silent: the removed partner is told.
        post("/v1/partners/unlink") {
            val req = call.receive<SignedRequest>()
            val pid = req.partnerId ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("partnerId"))
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "unlink", req.t, pid, req.signature)) return@post call.unauthorized()
            // An app partner is told through the pairing itself (the phone posts a last message); here it's only forgotten.
            if (device.appPartners.any { it.id == pid }) {
                store.updateDevice(device.id) { d -> d.appPartners = d.appPartners.filter { it.id != pid } }
                return@post call.respond(OkResponse())
            }
            val removed = device.partners.firstOrNull { it.id == pid }
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown partner"))
            alerts.toPartners(device.copy(partners = listOf(removed)), "you were removed as a partner",
                listOf("The phone's owner removed you as their accountability partner. You won't get further reports."))
            store.updateDevice(device.id) { d -> d.partners = d.partners.filter { it.id != pid } }
            call.respond(OkResponse())
        }

        // Plan 12 §3: the owner asks a partner instead of waiting out the strict lock. Partners are told at once.
        post("/v1/unlock/ask") {
            val req = call.receive<SignedRequest>()
            val action = req.kind?.let(app.focusfence.dns.GuardPolicy.Action::fromKey)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown action"))
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "unlock", req.t, action.key, req.signature)) return@post call.unauthorized()
            if (device.partners.none { it.confirmed } && device.appPartners.isEmpty())
                return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("no partner to ask yet"))
            val now = cfg.now()
            // Asking again for the same thing while it's open doesn't tell partners twice.
            val open = device.unlockRequest?.takeIf { it.action == action.key && app.focusfence.dns.UnlockAsk.open(it.state, it.askedMs, now) }
            if (open != null) return@post call.respond(open.status(now))
            val r = Store.UnlockRequest(newToken().take(16), action.key, now)
            store.updateDevice(device.id) { it.unlockRequest = r }
            store.device(device.id)?.let { alerts.toPartners(it, "your OK is asked", listOf(app.focusfence.dns.UnlockAsk.askLine(action))) }
            call.respond(r.status(now))
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
                    alerts.checkedIn(device.id)
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
            val gap = req.gap?.takeIf { g -> req.gapSignature != null && signedOk(device, "gap", req.t, Integrity.sha256(g), req.gapSignature) }
            alerts.checkedIn(device.id, gap?.filter { !it.isISOControl() }?.take(300))
            val now = store.device(device.id)
            val ask = now?.unlockRequest?.takeIf { cfg.now() - it.askedMs < app.focusfence.dns.UnlockAsk.LAPSE_MS + app.focusfence.dns.UnlockAsk.GRANT_MS }
            call.respond(HeartbeatResponse(
                notices = now?.ownerNotices.orEmpty().filter { it.t > req.noticesAfter },
                unlock = ask?.status(cfg.now()),
            ))
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
            alerts.checkedIn(device.id)
            call.respond(OkResponse())
        }

        // D-023: protection changes arrive as the phone's own alert lines (Digest.alertText), never as records.
        post("/v1/alerts") {
            val req = call.receive<AlertsRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            val kept = req.lines.take(20).mapIndexed { i, l -> l.filter { !it.isISOControl() || it == ' ' }.take(300) to (i in req.minor) }
                .filter { it.first.isNotBlank() }
            val lines = kept.map { it.first }
            if (!signedOk(device, "alerts", req.t, Integrity.sha256(req.lines.take(20).joinToString("\n")), req.signature)) return@post call.unauthorized()
            if (lines.isNotEmpty()) alerts.toPartners(device, "protection changed", lines, kept.indices.filter { kept[it].second }.toSet())
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
                // D-035: only an enrolled phone opens a new mailbox, so nobody can fill the server without an invite or code.
                val dev = store.device(req.deviceId) ?: return@post call.unknownDevice()
                if (!signedOk(dev, "mailbox", req.t, id, req.signature)) return@post call.unauthorized()
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
            if (store.allDevices().none { d -> d.invites.any { it.linkHash == hash && it.expiresMs > now && !it.enrollOnly } }) {
                return@get call.respondText(Web.message("Not found", "This invite isn't valid. It may have expired; ask for a new one."), ContentType.Text.Html, HttpStatusCode.NotFound)
            }
            if (!notifier.emailAvailable) return@get call.respondText(Web.joinNeedsApp(cfg.androidPackage), ContentType.Text.Html)
            call.respondText(Web.joinForm("", null, notifier.smsAvailable, link = secret, pkg = cfg.androidPackage), ContentType.Text.Html)
        }

        get("/join") {
            val code = call.request.queryParameters["code"].orEmpty()
            if (!notifier.emailAvailable) return@get call.respondText(Web.joinNeedsApp(cfg.androidPackage), ContentType.Text.Html)
            call.respondText(Web.joinForm(code, null, notifier.smsAvailable), ContentType.Text.Html)
        }

        post("/join") {
            val form = call.receiveParameters()
            val rawCode = form["code"].orEmpty()
            // Without email the confirmation can never arrive: refuse before the invite is spent.
            if (!notifier.emailAvailable) return@post call.respondText(Web.joinNeedsApp(cfg.androidPackage), ContentType.Text.Html)
            if (!joinLimiter.allow(call.client())) {
                return@post call.respondText(Web.joinForm(rawCode, "Too many attempts. Try again in an hour.", notifier.smsAvailable),
                    ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            }
            val rawLink = form["link"].orEmpty().take(64)
            val linkHash = if (rawLink.length >= 20) Integrity.sha256(rawLink) else ""
            val codeHash = Integrity.sha256(normalizeCode(rawCode))
            fun again(err: String) = Web.joinForm(rawCode, err, notifier.smsAvailable, link = rawLink.takeIf { linkHash.isNotEmpty() }, pkg = cfg.androidPackage)
            val name = form["name"].orEmpty().filter { !it.isISOControl() }.trim().take(60)
            val email = form["email"].orEmpty().trim().take(120)
            if (name.isEmpty() || !EMAIL.matches(email)) {
                return@post call.respondText(again("Enter your name and email."), ContentType.Text.Html)
            }
            val now = cfg.now()
            fun Store.Invite.matches() = expiresMs > now && !enrollOnly && (if (linkHash.isNotEmpty()) this.linkHash == linkHash else this.codeHash == codeHash)
            val device = store.allDevices().firstOrNull { d -> d.invites.any { it.matches() } }
                ?: return@post call.respondText(again("That invite isn't valid. It may have expired or already been used."), ContentType.Text.Html)
            val invite = device.invites.first { it.matches() }
            if (device.partners.size >= cfg.maxPartnersPerDevice) {
                return@post call.respondText(again("This phone already has the most partners it can have."), ContentType.Text.Html)
            }
            // Double opt-in: the address is NOT trusted yet. Until its owner clicks the link below, the only thing
            // ever sent to it is that one email, so a stranger's address can't be signed up to receive alerts.
            val partner = Store.Partner(java.util.UUID.randomUUID().toString(), name, email, newToken(), now, confirmed = false,
                linkHash = invite.linkHash.ifEmpty { null }, inviteId = invite.id)
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
                // Journey 4 (2026-11-25): say what's coming, so the first alert isn't a surprise and silence isn't a worry.
                notifier.email(
                    partner.email, "Focus Fence: you're now an accountability partner",
                    "<p>Hi ${Web.esc(partner.name)}, you're set up. Here's what you'll get:</p><ul>" +
                        "<li><b>Alerts by email, within minutes,</b> when their protection changes (the filter turned off, " +
                        "uninstall protection removed) or the phone stops checking in for about an hour. You'll also hear when it's back.</li>" +
                        "<li><b>Flagged screenshots,</b> heavily blurred, if they turned on screen checks and the phone finds nudity on screen. They may add a note.</li>" +
                        "<li><b>A weekly email</b> pointing to the report. The report itself updates about every 15 minutes.</li></ul>" +
                        "<p>The report is end-to-end encrypted. Open it from the Focus Fence link they sent you: that link carries the " +
                        "key and works in any browser. This one works too in a browser that has opened that link before: " +
                        "<a href=\"${alerts.link(partner)}\">${alerts.link(partner)}</a></p>" +
                        "<p>New to this? <a href=\"${cfg.publicUrl}/guide\">Being a good accountability partner</a> takes two minutes.</p>" +
                        "<p>You can stop being their partner any time from the bottom of the report page.</p>",
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
                call.request.queryParameters["unlock"] == "approved" -> "Approved. They've been told, and so have any other partners."
                call.request.queryParameters["unlock"] == "denied" -> "You said no. They've been told. They can still wait out the delay."
                call.request.queryParameters["unlock"] == "late" -> "That request was already answered or has lapsed."
                call.request.queryParameters["prefs"] == "saved" -> "Saved. Alerts now reach you the way you chose."
                call.request.queryParameters["reply"] == "sent" -> "Sent. It shows on their phone at its next check-in."
                call.request.queryParameters["reply"] == "wait" -> "You just sent one. Try again in a couple of minutes."
                else -> null
            }
            val ask = device.unlockRequest?.takeIf { app.focusfence.dns.UnlockAsk.open(it.state, it.askedMs, cfg.now()) && partner.confirmed }
            call.respondText(Web.partnerPage(partner, cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs, notifier.smsAvailable, notice, ask),
                ContentType.Text.Html)
        }

        // The sealed report slices for this partner's phone. Ciphertext only: the key is in the partner's browser.
        get("/p/{token}/data") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            call.response.headers.append("Cache-Control", "no-store")
            // Plan 12 §2: only the page's script fetches this, so a mail scanner opening the link doesn't count as a look.
            store.markViewed(device.id, partner.id, cfg.now())
            call.respond(ReportData(device.id, partner.name, cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs,
                store.readReports(device.id).filter { cfg.now() - it.to <= cfg.reportEveryMs + 3_600_000L }))
        }

        // What this partner has been told, for app partners to notify on (2026-11-25). The same lines alert emails carry.
        get("/p/{token}/alerts") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            call.response.headers.append("Cache-Control", "no-store")
            store.markFeedRead(device.id, partner.id, cfg.now())
            call.respond(AlertFeed(cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs, cfg.staleMs,
                if (partner.confirmed) device.alerts.filter { it.t > after && it.t >= partner.linkedMs } else emptyList(),
                partner.alertLevel, partner.quiet))
        }

        // Saves a report key sent as a link (new device or cleared browser). The key stays after '#', in the browser.
        get("/key") { call.respondText(Web.keyPage(), ContentType.Text.Html) }
        get("/guide") { call.respondText(Web.guide(), ContentType.Text.Html) }

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

        // A one-tap reply to the owner (PartnerReply): set words only, at most one per partner every two minutes.
        val lastReply = java.util.concurrent.ConcurrentHashMap<String, Long>()
        post("/p/{token}/reply") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val reply = app.focusfence.dns.PartnerReply.Reply.fromKey(call.receiveParameters()["r"])
            if (reply == null || !partner.confirmed) return@post call.respond(HttpStatusCode.BadRequest)
            val now = cfg.now()
            val last = lastReply[partner.id]
            if (last != null && now - last < app.focusfence.dns.PartnerReply.EVERY_MS) return@post call.respondRedirect("/p/$token?reply=wait")
            lastReply[partner.id] = now
            store.addOwnerNotice(device.id, app.focusfence.dns.PartnerReply.line(partner.name, reply), now, kind = "reply")
            call.respondRedirect("/p/$token?reply=sent")
        }

        // Plan 12 §5: how alerts reach this partner. Their own page sets it; nobody else can.
        post("/p/{token}/prefs") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val form = call.receiveParameters()
            val level = app.focusfence.dns.AlertPrefs.Level.fromKey(form["level"]).key
            val quiet = form["quiet"] == "on"
            val zone = app.focusfence.dns.AlertPrefs.cleanZone(form["tz"])
            store.updateDevice(device.id) { d ->
                d.partners.firstOrNull { it.id == partner.id }?.apply { alertLevel = level; this.quiet = quiet; if (zone != null) this.zone = zone }
                d.appPartners = d.appPartners.map { if (it.id == partner.id) it.copy(alertLevel = level, quiet = quiet) else it }
            }
            call.respondRedirect("/p/$token?prefs=saved")
        }

        // Plan 12 §3: any one partner answers; the first answer counts.
        post("/p/{token}/unlock/{id}") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val approve = when (call.receiveParameters()["d"]) { "approve" -> true; "deny" -> false; else -> return@post call.respond(HttpStatusCode.BadRequest) }
            val now = cfg.now()
            var answered: Store.UnlockRequest? = null
            store.updateDevice(device.id) { d ->
                val r = d.unlockRequest
                if (partner.confirmed && r != null && r.id == call.parameters["id"] && app.focusfence.dns.UnlockAsk.open(r.state, r.askedMs, now)) {
                    answered = r.copy(state = if (approve) app.focusfence.dns.UnlockAsk.APPROVED else app.focusfence.dns.UnlockAsk.DENIED, by = partner.name, decidedMs = now)
                    d.unlockRequest = answered
                }
            }
            val r = answered ?: return@post call.respondRedirect("/p/$token?unlock=late")
            val action = app.focusfence.dns.GuardPolicy.Action.fromKey(r.action)
            if (action != null) store.device(device.id)?.let {
                alerts.toPartners(it, if (approve) "a partner approved" else "a partner said no", listOf(app.focusfence.dns.UnlockAsk.answerLine(partner.name, approve, action)), minor = setOf(0))
            }
            call.respondRedirect("/p/$token?unlock=${if (approve) "approved" else "denied"}")
        }

        post("/p/{token}/leave") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            store.updateDevice(device.id) { d -> d.partners = d.partners.filter { it.id != partner.id }; d.appPartners = d.appPartners.filter { it.id != partner.id } }
            // Leaving is never silent (plan 12): the owner's phone hears it at its next check-in, and everyone still
            // watching is told, so a partner quietly dropping off can't leave the owner believing they're watched.
            val who = partner.name.ifBlank { "A partner" }
            store.addOwnerNotice(device.id, "$who stopped being your accountability partner.", cfg.now())
            store.device(device.id)?.let { d ->
                if (d.partners.any { it.confirmed } || d.appPartners.isNotEmpty())
                    alerts.toPartners(d, "a partner left", listOf("$who stopped being an accountability partner for this phone."), minor = setOf(0))
            }
            call.respondText(Web.message("Done", "You're no longer their accountability partner. They've been told."), ContentType.Text.Html)
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
