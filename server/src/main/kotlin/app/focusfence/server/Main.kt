package app.focusfence.server

import app.focusfence.dns.Pairing
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
    val now: () -> Long = System::currentTimeMillis,
)

@Serializable data class EnrollRequest(val publicKeySpki: String)
@Serializable data class EnrollResponse(val deviceId: String)
@Serializable data class SignedRequest(val deviceId: String, val t: Long, val signature: String, val partnerId: String? = null)
@Serializable data class InviteResponse(val code: String, val expiresMs: Long, val joinUrl: String)
@Serializable data class PartnerInfo(val id: String, val name: String, val email: String, val sms: Boolean, val linkedMs: Long)
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
@Serializable data class HeartbeatRequest(val deviceId: String, val t: Long, val lastSeq: Long, val signature: String)
@Serializable data class ShotRequest(val deviceId: String, val t: Long, val shotId: String, val ciphertextB64: String, val signature: String)
@Serializable data class ErrorResponse(val error: String)
@Serializable data class OkResponse(val ok: Boolean = true)
@Serializable data class CreateMailboxRequest(val tokenHash: String)
@Serializable data class PostMailRequest(val c: String)
@Serializable data class PostMailResponse(val id: Long)
@Serializable data class MailListResponse(val msgs: List<Store.MailMessage>, val lastWriteMs: Long)

fun main() {
    val env = System::getenv
    val cfg = Config(
        publicUrl = (env("FF_PUBLIC_URL") ?: "http://localhost:8080").trimEnd('/'),
        staleMs = (env("FF_STALE_MINUTES") ?: "240").toLong() * 60_000,
    )
    val store = Store(File(env("FF_DATA_DIR") ?: "data"))
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
    embeddedServer(CIO, port = (env("FF_PORT") ?: "8080").toInt()) { module(store, notifier, cfg) }.start(wait = true)
}

/** Fixed-window limiter for the few unauthenticated endpoints (invite codes are short). */
class RateLimiter(private val max: Int, private val windowMs: Long, private val now: () -> Long) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()
    fun allow(key: String): Boolean {
        val t = now()
        val list = hits.computeIfAbsent(key) { ArrayList() }
        synchronized(list) {
            list.removeAll { t - it > windowMs }
            if (list.size >= max) return false
            list += t
            return true
        }
    }
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
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "error"))
        }
    }
    val alerts = Alerts(store, notifier, cfg)
    val joinLimiter = RateLimiter(10, 3_600_000, cfg.now)
    val smsLimiter = RateLimiter(3, 3_600_000, cfg.now)

    fun signedOk(device: Store.Device, action: String, t: Long, extra: String, sig: String): Boolean {
        if (abs(cfg.now() - t) > cfg.clockSkewMs) return false
        val key = runCatching { Integrity.publicKeyFromBase64Spki(device.publicKeySpki) }.getOrNull() ?: return false
        return Integrity.verifySignature(key, Integrity.requestMessage(action, device.id, t, extra), sig)
    }

    suspend fun ApplicationCall.unauthorized() = respond(HttpStatusCode.Unauthorized, ErrorResponse("bad signature or stale request"))
    suspend fun ApplicationCall.unknownDevice() = respond(HttpStatusCode.NotFound, ErrorResponse("unknown device"))

    routing {
        get("/") { call.respondText("Focus Fence accountability server (prototype)") }

        // ---- device API -------------------------------------------------------

        post("/v1/enroll") {
            val req = call.receive<EnrollRequest>()
            runCatching { Integrity.publicKeyFromBase64Spki(req.publicKeySpki) }
                .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad key")) }
            call.respond(EnrollResponse(store.createDevice(req.publicKeySpki, cfg.now()).id))
        }

        post("/v1/invites") {
            val req = call.receive<SignedRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "invite", req.t, "", req.signature)) return@post call.unauthorized()
            val code = newInviteCode()
            val expires = cfg.now() + cfg.inviteTtlMs
            store.updateDevice(device.id) { d ->
                // At most 5 live invites; expired ones are dropped.
                d.invites = (d.invites.filter { it.expiresMs > cfg.now() } + Store.Invite(Integrity.sha256(code), expires)).takeLast(5)
            }
            val shown = code.chunked(4).joinToString("-")
            call.respond(InviteResponse(shown, expires, "${cfg.publicUrl}/join?code=$shown"))
        }

        post("/v1/partners/list") {
            val req = call.receive<SignedRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "partners", req.t, "", req.signature)) return@post call.unauthorized()
            call.respond(PartnersResponse(device.partners.map {
                PartnerInfo(it.id, it.name, Web.mask(it.email), it.phoneVerified, it.linkedMs)
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
                    store.appendRecords(device.id, req.recordsRaw, req.lastSeq, r.newHash, r.gap, cfg.now())
                    val lines = alerts.alertLines(req.recordsRaw).toMutableList()
                    // Skip the gap alert for a device's very first batch (seq starts wherever the log was).
                    if (r.gap > 0 && device.expectedNextSeq > 1) {
                        lines += "${r.gap} activity records never arrived (deleted, or lost on the phone)"
                    }
                    if (lines.isNotEmpty()) alerts.toPartners(device, "protection changed", lines)
                    call.respond(BatchResponse(req.lastSeq, r.gap, r.newHash))
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

        // Encrypted screenshot blob; the server stores ciphertext only.
        post("/v1/shots") {
            val req = call.receive<ShotRequest>()
            val device = store.device(req.deviceId) ?: return@post call.unknownDevice()
            if (!signedOk(device, "shot", req.t, req.shotId, req.signature)) return@post call.unauthorized()
            store.saveBlob(req.deviceId, req.shotId, Base64.getDecoder().decode(req.ciphertextB64))
            call.respond(OkResponse())
        }

        // ---- blind mailbox (app to app) -------------------------------------
        // The id and token are derived from a pairing key the server never sees.
        // The server keeps only sha256(token), so it can't post as either side.

        post("/v1/mailbox/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (!MAILBOX_ID.matches(id)) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad id"))
            val req = call.receive<CreateMailboxRequest>()
            val mb = store.createMailbox(id, req.tokenHash, cfg.now())
            if (mb.tokenHash != req.tokenHash) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("taken"))
            call.respond(OkResponse())
        }

        post("/v1/mailbox/{id}/msgs") {
            val id = call.parameters["id"].orEmpty()
            val mb = store.mailbox(id) ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no mailbox"))
            if (!tokenOk(call, mb)) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bad token"))
            val req = call.receive<PostMailRequest>()
            if (req.c.length > 96 * 1024) return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
            call.respond(PostMailResponse(store.postMail(id, req.c, cfg.now())))
        }

        get("/v1/mailbox/{id}/msgs") {
            val id = call.parameters["id"].orEmpty()
            val mb = store.mailbox(id) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no mailbox"))
            if (!tokenOk(call, mb)) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bad token"))
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            call.respond(MailListResponse(store.readMail(id, after), mb.lastWriteMs))
        }

        // ---- partner web pages ---------------------------------------------

        get("/join") {
            val code = call.request.queryParameters["code"].orEmpty()
            call.respondText(Web.joinForm(code, null, notifier.smsAvailable), ContentType.Text.Html)
        }

        post("/join") {
            val form = call.receiveParameters()
            val rawCode = form["code"].orEmpty()
            if (!joinLimiter.allow(call.request.origin.remoteHost)) {
                return@post call.respondText(Web.joinForm(rawCode, "Too many attempts. Try again in an hour.", notifier.smsAvailable),
                    ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            }
            val codeHash = Integrity.sha256(normalizeCode(rawCode))
            val name = form["name"].orEmpty().trim().take(60)
            val email = form["email"].orEmpty().trim().take(120)
            if (name.isEmpty() || !email.contains('@')) {
                return@post call.respondText(Web.joinForm(rawCode, "Enter your name and email.", notifier.smsAvailable), ContentType.Text.Html)
            }
            val now = cfg.now()
            val device = store.allDevices().firstOrNull { d -> d.invites.any { it.codeHash == codeHash && it.expiresMs > now } }
                ?: return@post call.respondText(
                    Web.joinForm(rawCode, "That code isn't valid. It may have expired or already been used.", notifier.smsAvailable),
                    ContentType.Text.Html,
                )
            val partner = Store.Partner(java.util.UUID.randomUUID().toString(), name, email, newToken(), now)
            store.updateDevice(device.id) { d ->
                d.invites = d.invites.filter { it.codeHash != codeHash } // single use
                d.partners = d.partners + partner
            }
            notifier.email(
                email, "Focus Fence: you're now an accountability partner",
                "<p>Hi ${Web.esc(name)}, you'll get a weekly report and alerts by email.</p>" +
                    "<p>Your private report page (keep this link to yourself): <a href=\"${alerts.link(partner)}\">${alerts.link(partner)}</a></p>",
            )
            val phone = form["phone"]?.let(::normalizePhone)
            if (phone != null && form["sms_consent"] == "yes" && notifier.smsAvailable) {
                startSmsVerification(store, notifier, smsLimiter, cfg, device.id, partner.id, phone)
            }
            call.respondRedirect("/p/${partner.token}?welcome=1")
        }

        get("/p/{token}") {
            val (device, partner) = store.partnerByToken(call.parameters["token"].orEmpty())
                ?: return@get call.respondText(Web.message("Not found", "This link isn't valid any more."), ContentType.Text.Html, HttpStatusCode.NotFound)
            val now = cfg.now()
            val notice = when {
                call.request.queryParameters["welcome"] != null -> "You're linked. Bookmark this page — it's your private report."
                call.request.queryParameters["sms"] == "on" -> "Text alerts are on."
                call.request.queryParameters["sms"] == "bad" -> "That code didn't match. Try again."
                else -> null
            }
            call.respondText(
                Web.partnerPage(partner, now - device.lastSeenMs <= cfg.staleMs, device.lastSeenMs,
                    alerts.digest(device.id, now - cfg.reportEveryMs, now), notifier.smsAvailable, notice),
                ContentType.Text.Html,
            )
        }

        post("/p/{token}/sms") {
            val token = call.parameters["token"].orEmpty()
            val (device, partner) = store.partnerByToken(token) ?: return@post call.respond(HttpStatusCode.NotFound)
            val form = call.receiveParameters()
            val phone = form["phone"]?.let(::normalizePhone)
            if (phone != null && form["sms_consent"] == "yes" && notifier.smsAvailable) {
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
