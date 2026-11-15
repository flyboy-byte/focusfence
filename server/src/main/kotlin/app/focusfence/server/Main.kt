package app.focusfence.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import kotlin.concurrent.thread

/**
 * PROTOTYPE accountability backend. Proves the device→server→partner loop:
 * enroll a device key, ingest signed, sequence-checked batches of the same
 * event-log records the phone writes, heartbeat for liveness, and email the
 * partner a weekly report plus alerts when integrity drops or the device goes
 * silent. Screenshots arrive as opaque ciphertext the server cannot read.
 *
 * NOT production-ready and NOT built by the app's CI. Env:
 *   FF_DATA_DIR (default ./data), FF_PORT (8080),
 *   RESEND_API_KEY, FF_MAIL_FROM (default accountability@localhost),
 *   FF_STALE_MINUTES (default 90).
 */
@Serializable data class EnrollRequest(val partnerEmail: String, val publicKeySpki: String)
@Serializable data class EnrollResponse(val deviceId: String)
@Serializable data class BatchRequest(
    val deviceId: String,
    val firstSeq: Long,
    val lastSeq: Long,
    val prevHash: String,
    val recordsRaw: String,
    val signature: String,
)
@Serializable data class BatchResponse(val acceptedThrough: Long, val gap: Long, val nextHash: String)
@Serializable data class HeartbeatRequest(val deviceId: String, val t: Long, val lastSeq: Long, val signature: String)
@Serializable data class ShotRequest(val deviceId: String, val shotId: String, val ciphertextB64: String, val signature: String)

fun main() {
    val dataDir = File(System.getenv("FF_DATA_DIR") ?: "data")
    val port = (System.getenv("FF_PORT") ?: "8080").toInt()
    val staleMs = (System.getenv("FF_STALE_MINUTES") ?: "90").toLong() * 60_000
    val store = Store(dataDir)
    val email = Email(System.getenv("RESEND_API_KEY"), System.getenv("FF_MAIL_FROM") ?: "accountability@localhost")

    startStalenessWatcher(store, email, staleMs)

    embeddedServer(CIO, port = port) { module(store, email, staleMs) }.start(wait = true)
}

fun Application.module(store: Store, email: Email, staleMs: Long) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "error")))
        }
    }

    routing {
        get("/") { call.respondText("Focus Fence accountability server (prototype)") }

        post("/v1/enroll") {
            val req = call.receive<EnrollRequest>()
            // Reject a malformed key early so a device can't enroll unverifiably.
            runCatching { Integrity.publicKeyFromBase64Spki(req.publicKeySpki) }
                .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad key")) }
            val device = store.createDevice(req.partnerEmail, req.publicKeySpki)
            call.respond(EnrollResponse(device.id))
        }

        post("/v1/batches") {
            val req = call.receive<BatchRequest>()
            val device = store.device(req.deviceId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown device"))
            val key = Integrity.publicKeyFromBase64Spki(device.publicKeySpki)
            val msg = Integrity.batchMessage(req.deviceId, req.firstSeq, req.lastSeq, req.prevHash, req.recordsRaw)
            val sigOk = Integrity.verifySignature(key, msg, req.signature)
            when (val r = Integrity.check(sigOk, req.firstSeq, req.lastSeq, req.prevHash,
                device.lastHash, device.expectedNextSeq, req.recordsRaw)) {
                is Integrity.Result.Reject ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to r.reason))
                is Integrity.Result.Ok -> {
                    store.appendRecords(device, req.recordsRaw, req.lastSeq, r.newHash)
                    if (containsUrgentIntegrity(req.recordsRaw)) alertIntegrity(email, device)
                    call.respond(BatchResponse(req.lastSeq, r.gap, r.newHash))
                }
            }
        }

        post("/v1/heartbeat") {
            val req = call.receive<HeartbeatRequest>()
            val device = store.device(req.deviceId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown device"))
            val key = Integrity.publicKeyFromBase64Spki(device.publicKeySpki)
            val ok = Integrity.verifySignature(key, "${req.deviceId}|${req.t}|${req.lastSeq}".toByteArray(), req.signature)
            if (!ok) return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad signature"))
            store.touch(device)
            call.respond(mapOf("ok" to true))
        }

        // Encrypted screenshot blob; server stores ciphertext only.
        post("/v1/shots") {
            val req = call.receive<ShotRequest>()
            val device = store.device(req.deviceId)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown device"))
            val key = Integrity.publicKeyFromBase64Spki(device.publicKeySpki)
            if (!Integrity.verifySignature(key, "${req.deviceId}|${req.shotId}".toByteArray(), req.signature)) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad signature"))
            }
            store.saveBlob(req.deviceId, req.shotId, Base64.getDecoder().decode(req.ciphertextB64))
            call.respond(mapOf("ok" to true))
        }

        // Minimal partner view. A real deployment puts auth in front of this.
        get("/partner/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val device = store.device(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val since = System.currentTimeMillis() - 7 * 24 * 60 * 60_000
            val summary = Report.build(store.readRecords(id), device, since, staleMs)
            call.respondText(Report.renderHtml(summary, device), io.ktor.http.ContentType.Text.Html)
        }
    }
}

private fun containsUrgentIntegrity(recordsRaw: String): Boolean =
    recordsRaw.lineSequence().any { it.contains("\"type\":\"integ\"") || it.contains("\"state\":\"revoked\"") }

private fun alertIntegrity(email: Email, device: Store.Device) = thread {
    kotlinx.coroutines.runBlocking {
        email.send(device.partnerEmail, "Focus Fence: protection changed",
            "<p>A protection change was reported for device ${device.id.take(8)}. " +
                "See the report: /partner/${device.id}</p>")
    }
}

/** Marks devices stale and alerts the partner once when a device goes silent. */
private fun startStalenessWatcher(store: Store, email: Email, staleMs: Long) = thread(isDaemon = true) {
    val alerted = HashSet<String>()
    while (true) {
        val now = System.currentTimeMillis()
        for (d in store.allDevices()) {
            val silent = now - d.lastSeenMs > staleMs
            if (silent && alerted.add(d.id)) {
                kotlinx.coroutines.runBlocking {
                    email.send(d.partnerEmail, "Focus Fence: device went silent",
                        "<p>Device ${d.id.take(8)} has not reported in over ${staleMs / 60_000} minutes. " +
                            "Monitoring may be off, or the phone is off/uninstalled.</p>")
                }
            } else if (!silent) {
                alerted.remove(d.id)
            }
        }
        Thread.sleep(60_000)
    }
}
