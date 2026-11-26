package app.focusfence.server

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
 * PROTOTYPE accountability backend. The phone enrolls a device key, uploads sealed report slices and alert lines, and
 * heartbeats. Partners (plan 13, one kind): the owner invites by email, by an 8-character code, or by a QR code of that
 * code's link. Whoever uses the invite first joins, from the Focus Fence app or a browser; that device makes its own key
 * pair, and the owner's phone seals the report key to it at its next check-in. The server relays public keys and
 * ciphertext only (D-023). Alerts reach a partner in their app, or by email when they have no app.
 *
 * Env: FF_DATA_DIR (./data), FF_PORT (8080), FF_PUBLIC_URL (http://localhost:8080),
 * FF_STALE_MINUTES (60), RESEND_API_KEY, FF_MAIL_FROM.
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
    /** Signed requests must be this fresh: stops replay of a captured request. */
    val clockSkewMs: Long = 10 * 60_000L,
    /** Hard bounds, because this box's disk is shared with other services. */
    val maxDevices: Int = 100,
    val maxDataBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxPartnersPerDevice: Int = 5,
    /** Whole-server email ceilings per day, whatever the number of addresses. */
    val emailGlobalDailyCap: Int = 200,
    /** Invite emails go to addresses nobody has confirmed yet, so they have their own, smaller ceiling. */
    val inviteEmailDailyCap: Int = 30,
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
    /** Which peers may set X-Forwarded-For (the reverse proxy in front of us). */
    val trustedProxy: (String) -> Boolean = TrustedProxies.LOOPBACK,
    val now: () -> Long = System::currentTimeMillis,
    /** The app the invite page's "Open in the app" button targets (FF_ANDROID_PACKAGE; only a fork changes it). */
    val androidPackage: String = Web.DEFAULT_PACKAGE,
)

@Serializable data class EnrollRequest(val publicKeySpki: String)
@Serializable data class EnrollResponse(val deviceId: String)
@Serializable data class SignedRequest(val deviceId: String, val t: Long, val signature: String, val partnerId: String? = null, val kind: String? = null, val inviteId: String? = null)
/** [email] empty: a code (and QR code) only. [from]: what the owner calls themselves in the email and on join pages. */
@Serializable data class InviteRequest(val deviceId: String, val t: Long, val signature: String, val email: String = "", val from: String = "")
@Serializable data class InviteResponse(val id: String, val code: String, val expiresMs: Long, val qrUrl: String, val emailed: Boolean = false)
/** Plan 13: the partner's side. Unsigned: the invite is the credential. [link] is an emailed link's secret, [code] a typed one. */
@Serializable data class JoinRequest(val code: String = "", val link: String = "", val name: String = "", val kind: String, val label: String = "", val publicKeySpki: String)
@Serializable data class JoinResponse(val partnerId: String, val deviceId: String, val token: String, val reportUrl: String, val ownerName: String)
@Serializable data class AddDeviceRequest(val kind: String, val label: String = "", val publicKeySpki: String)
@Serializable data class AddDeviceResponse(val deviceId: String)
@Serializable data class KeyResponse(val ready: Boolean, val sealed: String = "", val keyId: String = "")
@Serializable data class PartnerDeviceInfo(val id: String, val kind: String, val label: String, val addedMs: Long, val ready: Boolean)
/** [alertsBy]: "app", "email", or "none" (a browser-only partner who joined by code: the report, no alerts). */
@Serializable data class PartnerInfo(val id: String, val name: String, val email: String, val linkedMs: Long, val lastViewMs: Long, val alertsBy: String, val devices: List<PartnerDeviceInfo>)
@Serializable data class InviteInfo(val id: String, val expiresMs: Long, val email: String)
@Serializable data class PartnersResponse(val partners: List<PartnerInfo>, val invites: List<InviteInfo> = emptyList())
/** A partner device waiting for the report key. [firstDevice]: this partner had none yet, so they just joined. */
@Serializable data class SealTarget(val partnerId: String, val deviceId: String, val name: String, val kind: String, val label: String, val publicKeySpki: String, val firstDevice: Boolean, val groupName: String = "")
@Serializable data class SealRequest(val deviceId: String, val t: Long, val signature: String, val partnerDeviceId: String, val keyId: String, val sealed: String)
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
    val quietFrom: Int = app.focusfence.dns.AlertPrefs.QUIET_FROM_HOUR, val quietTo: Int = app.focusfence.dns.AlertPrefs.QUIET_TO_HOUR,
)
@Serializable data class ReportData(val deviceId: String, val name: String, val online: Boolean, val lastSeenMs: Long, val reports: List<Store.SealedReport>, val ownerName: String = "")
@Serializable data class HeartbeatRequest(
    val deviceId: String, val t: Long, val lastSeq: Long, val signature: String,
    /** After a long gap: the phone's account of it ("no internet; protection stayed on"), signed on its own. */
    val gap: String? = null, val gapSignature: String? = null,
    /** The newest owner notice the phone already has; only newer ones come back. Read cursor, not signed. */
    val noticesAfter: Long = 0,
    /**
     * Plan 13: a short id of the report key the phone uses now. Not signed: a wrong one only makes the phone seal the
     * key again to its own partners' devices, which the server can't open.
     */
    val keyId: String = "",
)
/** Plan 12: owner notices ride on the heartbeat. Older apps read only the status code, so this adds nothing for them. */
@Serializable data class HeartbeatResponse(
    val ok: Boolean = true, val notices: List<Store.AlertEntry> = emptyList(),
    /** Plan 12 §3: the owner's latest ask-a-partner request, while it can still matter to the phone. */
    val unlock: UnlockStatus? = null,
    /** Plan 13: partner devices that need the report key sealed to them (just joined, added, or the key changed). */
    val seal: List<SealTarget> = emptyList(),
    /** Live invites: while any is open the phone checks in every minute, so someone joining in person waits under one. */
    val invitesOpen: Int = 0,
    /** A partner left or removed a device: start a new report key and seal it to the rest. */
    val rotateKey: Boolean = false,
    /** Plan 14: reports a group lets this phone read, waiting for its device key. */
    val groupPairs: List<GroupPair> = emptyList(),
    /** Someone is joining one of this phone's groups: check in every minute. */
    val groupsBusy: Boolean = false,
)
@Serializable data class UnlockStatus(val id: String, val action: String, val state: String, val by: String, val askedMs: Long, val decidedMs: Long)

private fun Store.UnlockRequest.status(now: Long) =
    UnlockStatus(id, action, app.focusfence.dns.UnlockAsk.state(state, askedMs, now), by, askedMs, decidedMs)
@Serializable data class ErrorResponse(val error: String)
@Serializable data class OkResponse(val ok: Boolean = true)

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
        maxDataBytes = (env("FF_MAX_DATA_MB") ?: "2048").toLong() * 1024 * 1024,
    )
    if (runAdmin(args, File(env("FF_DATA_DIR") ?: "data"), System.currentTimeMillis(), ::println)) return
    val store = Store(File(env("FF_DATA_DIR") ?: "data"))
    store.purgeLegacyRecords().takeIf { it > 0 }?.let { println("D-023: deleted $it readable activity record files from earlier versions") }
    store.migrateToV2().takeIf { it > 0 }?.let { println("plan 13: cleared old partner data from $it devices (partners re-join with the new invites)") }
    val notifier = LiveNotifier(
        resendKey = env("RESEND_API_KEY"),
        mailFrom = env("FF_MAIL_FROM") ?: "accountability@localhost",
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

/** Partner actions in the service journal, by short ids only (see Alerts' journal). */
private val partnerJournal = org.slf4j.LoggerFactory.getLogger("ff.partners")
private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** 8 Crockford chars (40 bits), shown XXXX-XXXX. Short enough to read out; single use, 24 h, rate-limited. */
fun newInviteCode(): String = buildString { repeat(8) { append(CODE_ALPHABET[random.nextInt(32)]) } }

fun normalizeCode(s: String): String =
    s.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')

internal fun newToken(): String = ByteArray(24).also { random.nextBytes(it) }
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }



private const val TOO_OLD = "This version of Focus Fence is too old for this server. Update the app."

fun Application.module(store: Store, notifier: Notifier, cfg: Config) {
    val enrollCodes = EnrollCodes(File(store.dir, "codes.json"))
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    // The partner pages are plain server-rendered HTML with inline CSS and one script of ours, so a strict CSP costs
    // nothing and turns any future escaping slip into a non-event.
    install(createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            // Scripts only where D-023 needs them (the partner's browser makes its key and opens the sealed report).
            val path = call.request.local.uri.substringBefore('?')
            // The admin page has one too (/static/admin.js): copy and download buttons, nothing that talks to the server.
            val scripts = path.startsWith("/p/") || path.startsWith("/j/") || path.startsWith("/c/") || path.startsWith("/admin")
            call.response.headers.append("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'" +
                    if (scripts) "; script-src 'self'; connect-src 'self'" else "")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
        }
    })
    // D-041: every response says which protocol levels this server speaks, so an app can tell its owner which side
    // needs updating instead of failing in odd ways. Plan 13: below Protocol.OLDEST the API refuses outright.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        call.response.headers.append(app.focusfence.dns.Protocol.HEADER_SERVER, app.focusfence.dns.Protocol.LEVEL.toString())
        call.response.headers.append(app.focusfence.dns.Protocol.HEADER_SERVER_OLDEST, app.focusfence.dns.Protocol.OLDEST.toString())
        val path = call.request.local.uri.substringBefore('?')
        if (path.startsWith("/v1/") && path != "/v1/version") {
            val level = call.request.headers[app.focusfence.dns.Protocol.HEADER_APP]?.toIntOrNull() ?: 0
            if (level < app.focusfence.dns.Protocol.OLDEST) {
                call.respond(HttpStatusCode.UpgradeRequired, ErrorResponse(TOO_OLD))
                finish()
            }
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Never echo the exception text: it can carry class names or file paths. Keep it in our log only.
            call.application.log.warn("request failed: ${cause::class.simpleName}: ${(cause.message ?: "").filter { !it.isISOControl() }.take(120)}")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad request"))
        }
    }
    val alerts = Alerts(store, notifier, cfg)
    val groups = Groups(store, cfg)
    val joinLimiter = RateLimiter(10, 3_600_000, cfg.now)
    val enrollLimiter = RateLimiter(5, 3_600_000, cfg.now)
    val credEnrollLimiter = RateLimiter(30, 3_600_000, cfg.now)
    val inviteLimiter = RateLimiter(10, 3_600_000, cfg.now) // per owner phone
    val inviteEmailLimiter = RateLimiter(1, 3_600_000, cfg.now) // per address: an invite email is unasked-for mail
    val inviteEmailGlobal = RateLimiter(cfg.inviteEmailDailyCap, 24 * 3_600_000L, cfg.now)

    fun ApplicationCall.client() = clientKey(request.origin.remoteHost, request.headers["X-Forwarded-For"], cfg.trustedProxy)
    fun full(): Boolean = store.usedBytes(cfg.now()) >= cfg.maxDataBytes
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
    fun ownerName(d: Store.Device) = d.ownerName.ifEmpty { d.label }.ifEmpty { "Your partner" }
    fun clean(s: String, max: Int) = s.filter { !it.isISOControl() }.trim().take(max)
    fun keyOk(spki: String) = spki.length <= 200 && runCatching { Integrity.publicKeyFromBase64Spki(spki) }.isSuccess

    /** Unlinking or leaving: the rest of the partners can still read on, but the phone starts a new key for them. */
    fun forget(deviceId: String, partnerId: String) = store.updateDevice(deviceId) { d ->
        d.partners = d.partners.filter { it.id != partnerId }
        d.rotateKey = true
    }

    routing {
        Admin(cfg, store, notifier, enrollCodes) { it.client() }.routes(this)
        get("/") { call.respondText("Focus Fence accountability server (prototype)") }
        // For container health checks and uptime monitors: no auth, no data, no store access.
        get("/healthz") { call.respondText("ok") }
        get("/v1/version") {
            call.respond(VersionResponse(app.focusfence.dns.Protocol.LEVEL, app.focusfence.dns.Protocol.OLDEST, serverVersion))
        }

        // ---- the owner's phone -------------------------------------------------

        post("/v1/enroll") {
            val req = call.receive<EnrollRequest>()
            // Every app user on this server is enrolled, partners too: being someone's partner is a credential (one phone
            // per partner token), so a partner's phone can be watched later without an enrollment code. Browsers don't enroll.
            var needsCode = cfg.enrollToken != null || enrollCodes.any()
            val asPartner = call.request.headers["X-FF-Partner"].orEmpty().take(64).takeIf { it.length >= 20 }
                ?.let { store.partnerByToken(it) }
                ?.takeIf { (_, p) -> p.enrolledDeviceId.isEmpty() || store.device(p.enrolledDeviceId) == null }
            // Plan 14: a group code too, since every member must be a phone on this server.
            val withGroup = call.request.headers["X-FF-Group"]?.let { groups.lookup(it) }?.takeIf { it.members.size < Groups.MAX_MEMBERS }
            if (asPartner != null || withGroup != null) needsCode = false
            // A group meeting puts six phones behind one Wi-Fi address: enrolling with a partner or group credential has its
            // own, looser limit; the strict one guards enrollment codes and open servers.
            val limiter = if (asPartner != null || withGroup != null) credEnrollLimiter else enrollLimiter
            if (!limiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("slow down"))
            if (needsCode) {
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
            asPartner?.let { (owner, p) ->
                store.updateDevice(owner.id) { d -> d.partners.firstOrNull { it.id == p.id }?.enrolledDeviceId = created.id }
            }
            call.respond(EnrollResponse(created.id))
        }

        // Plan 13: one invite, three ways in. The code is typed in the app or scanned (the QR code is its /c/ link); with
        // an email, the address gets a link of its own. Whichever is used first spends the invite.
        post("/v1/invites") {
            val req = call.receive<InviteRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "invite", req.t, req.email, req.signature)) return@post call.unauthorized()
            val email = req.email.trim().lowercase().take(120)
            if (email.isNotEmpty() && !EMAIL.matches(email)) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("That email address doesn't look right."))
            if (email.isNotEmpty() && !notifier.emailAvailable) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("This server can't send email. Use the code instead."))
            if (device.partners.count { it.chosen } >= cfg.maxPartnersPerDevice || device.partners.size >= Groups.MAX_WATCHERS) return@post call.respond(HttpStatusCode.Conflict,
                ErrorResponse("You already have ${cfg.maxPartnersPerDevice} partners, the most a phone can have."))
            if (!inviteLimiter.allow(device.id)) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many invites. Try again in an hour."))
            val now = cfg.now()
            val code = newInviteCode()
            val secret = if (email.isNotEmpty()) newToken() else ""
            val inv = Store.Invite(java.util.UUID.randomUUID().toString(), Integrity.sha256(code), now + cfg.inviteTtlMs,
                linkHash = if (secret.isEmpty()) "" else Integrity.sha256(secret), email = email, createdMs = now)
            val from = clean(req.from, 40)
            store.updateDevice(device.id) { d ->
                d.invites = (d.invites.filter { it.expiresMs > now } + inv).takeLast(5)
                if (from.isNotEmpty()) d.ownerName = from
            }
            var emailed = false
            if (email.isNotEmpty() && inviteEmailLimiter.allow(email) && inviteEmailGlobal.allow("all")) {
                val who = Web.esc(from.ifEmpty { ownerName(device) })
                val link = "${cfg.publicUrl}/j/$secret"
                emailed = notifier.email(email, "Focus Fence: $who wants you as an accountability partner",
                    "<p>$who uses Focus Fence to block sites and asked you to be their accountability partner: you'd see their " +
                        "report and hear if their protection is turned off.</p>" +
                        "<p><a href=\"$link\"><b>Open the invite</b></a></p>" +
                        "<p>It works once and ends in 24 hours. If you have the Focus Fence app, the page offers to open it there.</p>" +
                        "<p>Not expecting this? Ignore it: nothing else will be sent to you.</p>")
            }
            partnerJournal.info("invite dev={} email={} emailed={}", device.id.take(8), email.isNotEmpty(), emailed)
            call.respond(InviteResponse(inv.id, code.chunked(4).joinToString("-"), inv.expiresMs, "${cfg.publicUrl}/c/$code", emailed))
        }

        post("/v1/invites/cancel") {
            val req = call.receive<SignedRequest>()
            val id = req.inviteId ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("inviteId"))
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "invite_cancel", req.t, id, req.signature)) return@post call.unauthorized()
            store.updateDevice(device.id) { d -> d.invites = d.invites.filter { it.id != id } }
            call.respond(OkResponse())
        }

        post("/v1/partners/list") {
            val req = call.receive<SignedRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "partners", req.t, "", req.signature)) return@post call.unauthorized()
            val now = cfg.now()
            call.respond(PartnersResponse(
                device.partners.map { p ->
                    val by = when { p.devices.any { it.kind == Store.KIND_APP && now - it.lastFeedMs < Store.APP_QUIET_MS } -> "app"; p.email.isNotEmpty() -> "email"; p.devices.any { it.kind == Store.KIND_APP } -> "app"; else -> "none" }
                    PartnerInfo(p.id, p.name, if (p.email.isEmpty()) "" else Web.mask(p.email), p.linkedMs, p.lastViewMs, by,
                        p.devices.map { PartnerDeviceInfo(it.id, it.kind, it.label, it.addedMs, it.sealed != null && it.keyId == device.reportKeyId) })
                },
                device.invites.filter { it.expiresMs > now }.map { InviteInfo(it.id, it.expiresMs, if (it.email.isEmpty()) "" else Web.mask(it.email)) },
            ))
        }

        // Removing is never silent: a partner with email is told; an app partner's feed stops and their app says so.
        post("/v1/partners/unlink") {
            val req = call.receive<SignedRequest>()
            val pid = req.partnerId ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("partnerId"))
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "unlink", req.t, pid, req.signature)) return@post call.unauthorized()
            val removed = device.partners.firstOrNull { it.id == pid }
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown partner"))
            // Plan 14: a group made this pair, so it goes when one of you leaves that group, not here.
            if (removed.groupIds.isNotEmpty()) return@post call.respond(HttpStatusCode.Conflict,
                ErrorResponse("${removed.name} is in a group with you. To stop sharing, leave ${groups.groupName(removed).ifEmpty { "the group" }}."))
            if (removed.email.isNotEmpty()) alerts.emailOne(removed, "you were removed as a partner",
                listOf("${ownerName(device)} removed you as their accountability partner. You won't get further reports."))
            forget(device.id, pid)
            partnerJournal.info("unlink dev={} partner={}", device.id.take(8), pid.take(8))
            call.respond(OkResponse())
        }

        // ---- groups (plan 14) ------------------------------------------------
        suspend fun ApplicationCall.group(action: String, extra: (GroupRequest) -> String, block: suspend (GroupRequest, Store.Device) -> Unit) {
            val req = receive<GroupRequest>()
            val device = store.device(req.deviceId) ?: return unknownDevice()
            if (!signedOk(device, action, req.t, extra(req), req.signature)) return unauthorized()
            try { block(req, device) } catch (e: GroupError) { respond(HttpStatusCode.fromValue(e.status), ErrorResponse(e.message.orEmpty())) }
        }
        post("/v1/groups/create") {
            call.group("group_create", { it.name }) { req, d ->
                if (full()) return@group call.serverFull()
                val r = groups.create(d, clean(req.name, 40), clean(req.label, 40))
                partnerJournal.info("group create dev={} group={}", d.id.take(8), r.groupId.take(8))
                call.respond(r)
            }
        }
        post("/v1/groups/code") { call.group("group_code", { it.groupId }) { req, d -> call.respond(groups.newCode(d, req.groupId)) } }
        post("/v1/groups/join") {
            if (!joinLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many tries. Try again in an hour."))
            call.group("group_join", { normalizeCode(it.code) }) { req, d ->
                val g = groups.join(d, req.code, clean(req.label, 40))
                partnerJournal.info("group join dev={} group={} size={}", d.id.take(8), g.id.take(8), g.members.size)
                call.respond(groups.list(d).groups.first { it.id == g.id })
            }
        }
        post("/v1/groups/leave") {
            call.group("group_leave", { it.groupId }) { req, d ->
                groups.leave(d, req.groupId)
                partnerJournal.info("group leave dev={} group={}", d.id.take(8), req.groupId.take(8))
                call.respond(OkResponse())
            }
        }
        post("/v1/groups/list") { call.group("groups", { "" }) { _, d -> call.respond(groups.list(d)) } }
        post("/v1/groups/level") { call.group("group_level", { "${it.groupId}|${it.level}" }) { req, d -> groups.setLevel(d, req.groupId, req.level); call.respond(OkResponse()) } }
        post("/v1/groups/key") {
            call.group("group_key", { "${it.ownerDeviceId}|${it.partnerId}|${Integrity.sha256(it.publicKeySpki)}" }) { req, d ->
                if (!keyOk(req.publicKeySpki)) return@group call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad key"))
                call.respond(GroupKeyResponse(groups.addKey(d, req.ownerDeviceId, req.partnerId, req.publicKeySpki, clean(req.label, 40))))
            }
        }
        // The app's one code box: says whether a code is a group's or a partner invite, and whose, before joining.
        post("/v1/codes/lookup") {
            if (!joinLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many tries. Try again in an hour."))
            val code = normalizeCode(call.receive<LookupRequest>().code)
            groups.lookup(code)?.let { g -> return@post call.respond(LookupResponse("group", g.name, g.members.map { it.name }, g.members.size >= Groups.MAX_MEMBERS)) }
            store.inviteByHash(Integrity.sha256(code), cfg.now())?.let { (owner, _) -> return@post call.respond(LookupResponse("invite", ownerName(owner))) }
            call.respond(HttpStatusCode.NotFound, ErrorResponse("That code isn't valid. It may have expired or been used already; ask for a new one."))
        }

        // Plan 13: the report key, sealed by the phone to one partner device's public key. Ciphertext only.
        post("/v1/seal") {
            val req = call.receive<SealRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (req.sealed.length > 2048 || req.keyId.length > 64) return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
            if (!signedOk(device, "seal", req.t, "${req.partnerDeviceId}|${req.keyId}|${Integrity.sha256(req.sealed)}", req.signature)) return@post call.unauthorized()
            // Sealed with a key the phone has since replaced: refuse, and the next heartbeat asks for the new one.
            if (req.keyId != device.reportKeyId) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("stale key"))
            var found = false
            store.updateDevice(device.id) { d ->
                d.partners.forEach { p -> p.devices.firstOrNull { it.id == req.partnerDeviceId }?.let { pd ->
                    pd.sealed = req.sealed; pd.keyId = req.keyId; pd.sealedMs = cfg.now(); found = true
                } }
            }
            if (!found) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown partner device"))
            partnerJournal.info("sealed dev={} pdev={}", device.id.take(8), req.partnerDeviceId.take(8))
            call.respond(OkResponse())
        }

        // Plan 12 §3: the owner asks a partner instead of waiting out the strict lock. Partners are told at once.
        post("/v1/unlock/ask") {
            val req = call.receive<SignedRequest>()
            val action = req.kind?.let(app.focusfence.dns.GuardPolicy.Action::fromKey)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown action"))
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "unlock", req.t, action.key, req.signature)) return@post call.unauthorized()
            if (device.partners.none { it.ready }) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("no partner to ask yet"))
            val now = cfg.now()
            // Asking again for the same thing while it's open doesn't tell partners twice.
            val open = device.unlockRequest?.takeIf { it.action == action.key && app.focusfence.dns.UnlockAsk.open(it.state, it.askedMs, now) }
            if (open != null) return@post call.respond(open.status(now))
            val r = Store.UnlockRequest(newToken().take(16), action.key, now)
            store.updateDevice(device.id) { it.unlockRequest = r }
            partnerJournal.info("ask dev={} action={}", device.id.take(8), action.key)
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
            val keyId = req.keyId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
            // A new key id means the phone has started a new key: whatever asked for one is answered.
            if (keyId.isNotEmpty() && keyId != device.reportKeyId) store.updateDevice(device.id) { it.reportKeyId = keyId; it.rotateKey = false }
            val d = store.device(device.id) ?: return@post call.unknownDevice()
            val now = cfg.now()
            val ask = d.unlockRequest?.takeIf { now - it.askedMs < app.focusfence.dns.UnlockAsk.LAPSE_MS + app.focusfence.dns.UnlockAsk.GRANT_MS }
            val seal = if (keyId.isEmpty()) emptyList() else d.partners.flatMap { p ->
                p.devices.filter { it.sealed == null || it.keyId != keyId }.map { pd ->
                    SealTarget(p.id, pd.id, p.name, pd.kind, pd.label, pd.publicKeySpki, firstDevice = !p.ready, groupName = groups.groupName(p))
                }
            }
            call.respond(HeartbeatResponse(
                notices = d.ownerNotices.filter { it.t > req.noticesAfter },
                unlock = ask?.status(now),
                seal = seal,
                invitesOpen = d.invites.count { it.expiresMs > now },
                rotateKey = d.rotateKey,
                groupPairs = groups.pending(d.id),
                groupsBusy = groups.busy(d.id),
            ))
        }

        // D-023: the partner report, sealed on the phone; stored as ciphertext and opened only on partner devices.
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

        // ---- the partner's side -----------------------------------------------

        // Plan 13: whoever uses an invite first joins (Logan: "trust who uses the invite"). Their device made a key pair
        // and sends only the public half; the owner's phone seals the report key to it at its next check-in.
        post("/v1/join") {
            val req = call.receive<JoinRequest>()
            if (!joinLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many tries. Try again in an hour."))
            if (req.kind != Store.KIND_APP && req.kind != Store.KIND_WEB) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("kind"))
            if (!keyOk(req.publicKeySpki)) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad key"))
            val hash = when {
                req.link.length >= 20 -> Integrity.sha256(req.link.take(64))
                normalizeCode(req.code).length == 8 -> Integrity.sha256(normalizeCode(req.code))
                else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Type the 8-character code."))
            }
            val now = cfg.now()
            val (owner, inv) = store.inviteByHash(hash, now)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("That invite isn't valid. It may have expired or been used already; ask for a new one."))
            if (full()) return@post call.serverFull()
            val viaEmail = inv.linkHash.isNotEmpty() && inv.linkHash == hash
            val label = clean(req.label, 40).ifEmpty { if (req.kind == Store.KIND_APP) "Phone" else "Browser" }
            val pd = Store.PartnerDevice(java.util.UUID.randomUUID().toString(), req.kind, label, req.publicKeySpki, now)
            var partner: Store.Partner? = null
            var refusal: String? = null
            store.updateDevice(owner.id) { d ->
                when {
                    d.invites.none { it.id == inv.id } -> refusal = "That invite was just used."
                    d.partners.count { it.chosen } >= cfg.maxPartnersPerDevice || d.partners.size >= Groups.MAX_WATCHERS -> refusal = "They already have the most partners a phone can have."
                    else -> {
                        val name = clean(req.name, 60).ifEmpty { "Partner" }
                        val p = Store.Partner(java.util.UUID.randomUUID().toString(), name, newToken(), now,
                            email = if (viaEmail) inv.email else "", devices = listOf(pd), inviteId = inv.id)
                        d.partners = d.partners + p
                        d.invites = d.invites.filter { it.id != inv.id }
                        partner = p
                    }
                }
            }
            refusal?.let { return@post call.respond(HttpStatusCode.Conflict, ErrorResponse(it)) }
            val p = partner ?: return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("That invite isn't valid any more."))
            partnerJournal.info("join dev={} partner={} kind={} email={}", owner.id.take(8), p.id.take(8), req.kind, viaEmail)
            call.respond(JoinResponse(p.id, pd.id, p.token, "${cfg.publicUrl}/p/${p.token}", ownerName(owner)))
        }

        // The pages an invite opens: the emailed link (/j/) and the QR code's link (/c/). report.js joins from here.
        get("/j/{secret}") {
            val secret = call.parameters["secret"].orEmpty().take(64)
            val found = secret.takeIf { it.length >= 20 }?.let { store.inviteByHash(Integrity.sha256(it), cfg.now()) }
            call.respondText(Web.joinPage(found?.let { ownerName(it.first) }, link = secret, code = "", pkg = cfg.androidPackage),
                ContentType.Text.Html, if (found == null) HttpStatusCode.NotFound else HttpStatusCode.OK)
        }
        get("/c/{code}") {
            val code = normalizeCode(call.parameters["code"].orEmpty().take(20))
            groups.lookup(code)?.let { g -> return@get call.respondText(Web.groupJoinPage(g.name, g.members.map { it.name }, cfg.publicUrl, code, cfg.androidPackage), ContentType.Text.Html) }
            val found = code.takeIf { it.length == 8 }?.let { store.inviteByHash(Integrity.sha256(it), cfg.now()) }
            call.respondText(Web.joinPage(found?.let { ownerName(it.first) }, link = "", code = code, pkg = cfg.androidPackage),
                ContentType.Text.Html, if (found == null) HttpStatusCode.NotFound else HttpStatusCode.OK)
        }

        get("/p/{token}") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respondText(Web.message("Not found", "This link isn't valid any more. If you were removed or left, you're no longer their partner."), ContentType.Text.Html, HttpStatusCode.NotFound)
            val q = call.request.queryParameters
            val notice = when {
                q["unlock"] == "approved" -> "Approved. They've been told, and so have any other partners."
                q["unlock"] == "denied" -> "You said no. They've been told. They can still wait out the delay."
                q["unlock"] == "late" -> "That request was already answered or has lapsed."
                q["prefs"] == "saved" -> "Saved. Alerts now reach you the way you chose."
                q["device"] == "removed" -> "Removed. That device can't open the report any more."
                q["device"] == "last" -> "That's your only device. To stop seeing the report, use Stop being their partner."
                else -> null
            }
            val ask = device.unlockRequest?.takeIf { app.focusfence.dns.UnlockAsk.open(it.state, it.askedMs, cfg.now()) && partner.ready }
            call.respondText(Web.partnerPage(partner, ownerName(device), cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs, notice, ask, partner.emailsAlerts(cfg.now())),
                ContentType.Text.Html)
        }

        // The sealed report slices for this partner's phone. Ciphertext only: the key is on the partner's devices.
        get("/p/{token}/data") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            call.response.headers.append("Cache-Control", "no-store")
            // Plan 12 §2: only the page's script fetches this, so a mail scanner opening the link doesn't count as a look.
            store.markViewed(device.id, partner.id, cfg.now())
            call.respond(ReportData(device.id, partner.name, cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs,
                store.readReports(device.id).filter { cfg.now() - it.to <= cfg.reportEveryMs + 3_600_000L }, ownerName(device)))
        }

        // Plan 13: this device's copy of the report key, sealed to its own public key, once the owner's phone has sent it.
        get("/p/{token}/key") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            val pd = partner.devices.firstOrNull { it.id == call.request.queryParameters["d"] }
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown device"))
            call.response.headers.append("Cache-Control", "no-store")
            val s = pd.sealed
            call.respond(if (s == null) KeyResponse(false) else KeyResponse(true, s, pd.keyId).also { if (pd.keyId != device.reportKeyId) partnerJournal.info("key old dev={} pdev={}", device.id.take(8), pd.id.take(8)) })
        }

        // What partners have been told, for a partner's app to notify on. The same lines alert emails carry.
        get("/p/{token}/alerts") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            call.response.headers.append("Cache-Control", "no-store")
            call.request.queryParameters["d"]?.let { store.markFeedRead(device.id, partner.id, it, cfg.now()) }
            call.respond(AlertFeed(cfg.now() - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs, cfg.staleMs,
                if (partner.ready) device.alerts.filter { it.t > after && it.t >= partner.linkedMs } else emptyList(),
                partner.alertLevel, partner.quiet, partner.quietFrom, partner.quietTo))
        }

        // Plan 13: another device of the same partner (a laptop browser, a new phone). The report link is the credential,
        // so this can't be silent: the owner's phone says "Sam added a browser" when it seals, and Sam is emailed.
        post("/p/{token}/devices") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
            val req = call.receive<AddDeviceRequest>()
            if (!joinLimiter.allow(call.client())) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many tries. Try again in an hour."))
            if (req.kind != Store.KIND_APP && req.kind != Store.KIND_WEB) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("kind"))
            if (!keyOk(req.publicKeySpki)) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad key"))
            if (partner.devices.size >= Store.DEVICES_PER_PARTNER) return@post call.respond(HttpStatusCode.Conflict,
                ErrorResponse("You already have ${Store.DEVICES_PER_PARTNER} devices, the most there can be. Remove one first."))
            val label = clean(req.label, 40).ifEmpty { if (req.kind == Store.KIND_APP) "Phone" else "Browser" }
            val pd = Store.PartnerDevice(java.util.UUID.randomUUID().toString(), req.kind, label, req.publicKeySpki, cfg.now())
            store.updateDevice(device.id) { d -> d.partners.firstOrNull { it.id == partner.id }?.let { it.devices = it.devices + pd } }
            partnerJournal.info("device added dev={} partner={} kind={}", device.id.take(8), partner.id.take(8), req.kind)
            if (partner.email.isNotEmpty()) alerts.emailOne(partner, "a device was added",
                listOf("A ${if (req.kind == Store.KIND_APP) "phone" else "browser"} ($label) was added to the devices that open ${ownerName(device)}'s report. If that wasn't you, remove it from the bottom of the report page."))
            call.respond(AddDeviceResponse(pd.id))
        }

        post("/p/{token}/devices/{id}/remove") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val pd = partner.devices.firstOrNull { it.id == call.parameters["id"] } ?: return@post call.respondRedirect("/p/$token")
            if (partner.devices.size == 1) return@post call.respondRedirect("/p/$token?device=last")
            store.updateDevice(device.id) { d ->
                d.partners.firstOrNull { it.id == partner.id }?.let { it.devices = it.devices.filter { x -> x.id != pd.id } }
                d.rotateKey = true
            }
            store.addOwnerNotice(device.id, "${partner.name} removed a device (${pd.label}).", cfg.now(), "device")
            partnerJournal.info("device removed dev={} partner={}", device.id.take(8), partner.id.take(8))
            call.respondRedirect("/p/$token?device=removed")
        }

        for (name in listOf("report.js", "admin.js")) get("/static/$name") {
            val js = object {}.javaClass.getResource("/static/$name")?.readText()
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(js, ContentType.parse("text/javascript"))
        }

        // Plan 12 §5: how alerts reach this partner. Their own page sets it; nobody else can.
        post("/p/{token}/prefs") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val form = call.receiveParameters()
            val level = app.focusfence.dns.AlertPrefs.Level.fromKey(form["level"]).key
            val quiet = form["quiet"] == "on"
            val zone = app.focusfence.dns.AlertPrefs.cleanZone(form["tz"])
            val from = app.focusfence.dns.AlertPrefs.hourOr(form["from"]?.toIntOrNull(), partner.quietFrom)
            val to = app.focusfence.dns.AlertPrefs.hourOr(form["to"]?.toIntOrNull(), partner.quietTo)
            store.updateDevice(device.id) { d ->
                d.partners.firstOrNull { it.id == partner.id }?.apply { alertLevel = level; this.quiet = quiet; quietFrom = from; quietTo = to; if (zone != null) this.zone = zone }
            }
            partnerJournal.info("prefs dev={} partner={} level={} quiet={}", device.id.take(8), partner.id.take(8), level, if (quiet) "$from-$to" else "off")
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
                if (partner.ready && r != null && r.id == call.parameters["id"] && app.focusfence.dns.UnlockAsk.open(r.state, r.askedMs, now)) {
                    answered = r.copy(state = if (approve) app.focusfence.dns.UnlockAsk.APPROVED else app.focusfence.dns.UnlockAsk.DENIED, by = partner.name, decidedMs = now)
                    d.unlockRequest = answered
                }
            }
            val r = answered ?: return@post call.respondRedirect("/p/$token?unlock=late").also { partnerJournal.info("answer late dev={}", device.id.take(8)) }
            partnerJournal.info("answer dev={} partner={} {}", device.id.take(8), partner.id.take(8), r.state)
            val action = app.focusfence.dns.GuardPolicy.Action.fromKey(r.action)
            if (action != null) store.device(device.id)?.let {
                alerts.toPartners(it, if (approve) "a partner approved" else "a partner said no", listOf(app.focusfence.dns.UnlockAsk.answerLine(partner.name, approve, action)), minor = setOf(0))
            }
            call.respondRedirect("/p/$token?unlock=${if (approve) "approved" else "denied"}")
        }

        post("/p/{token}/leave") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            if (partner.groupIds.isNotEmpty()) return@post call.respondText(Web.message("In a group",
                "You see this report through a group. To stop, leave the group in the Focus Fence app."), ContentType.Text.Html, HttpStatusCode.Conflict)
            forget(device.id, partner.id)
            // Leaving is never silent (plan 12): the owner's phone hears it at its next check-in, and everyone still
            // watching is told, so a partner quietly dropping off can't leave the owner believing they're watched.
            partnerJournal.info("partner left dev={} partner={}", device.id.take(8), partner.id.take(8))
            val who = partner.name.ifBlank { "A partner" }
            store.addOwnerNotice(device.id, "$who stopped being your accountability partner.", cfg.now(), "left")
            store.device(device.id)?.let { d ->
                if (d.partners.any { it.ready })
                    alerts.toPartners(d, "a partner left", listOf("$who stopped being an accountability partner for this phone."), minor = setOf(0))
            }
            call.respondText(Web.message("Done", "You're no longer their accountability partner. They've been told."), ContentType.Text.Html)
        }
    }
}

private val EMAIL = Regex("^[^\\s@<>\"',;]{1,64}@[A-Za-z0-9.-]{1,80}\\.[A-Za-z]{2,24}$")
