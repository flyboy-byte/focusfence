package app.focusfence.server.compat

import app.focusfence.dns.Pairing
import app.focusfence.server.Config
import app.focusfence.server.FakeNotifier
import app.focusfence.server.Store
import app.focusfence.server.module
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protocol level 1 (D-041), as the app at level 1 sends it: literal paths, literal JSON field names and the literal
 * signature message formats ("action|deviceId|t|extra"), and the response fields it reads. Deliberately NOT built from
 * the server's own data classes or helpers, so renaming a field or changing a format breaks this test.
 *
 * Keep this passing until level 1 falls out of the window (Protocol.LEVEL - Protocol.WINDOW > 1); then delete it.
 * Never edit it to match a new server: that is what a new level is for.
 */
class CompatLevel1Test {
    private var clock = 1_800_000_000_000L
    private val store = Store(Files.createTempDirectory("ff-compat1").toFile())
    private val cfg = Config(publicUrl = "https://ff.test", now = { clock })
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(k: KeyPair, msg: String) = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(msg.toByteArray()); sign() })
    /** The app's SyncWorker.sha256: base64url, no padding. */
    private fun sha(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private suspend fun HttpClient.json(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); headers.forEach { (k, v) -> header(k, v) }; setBody(body) }

    private suspend fun HttpClient.enroll(k: KeyPair, headers: Map<String, String> = emptyMap()): String {
        val r = json("/v1/enroll", """{"publicKeySpki":"${Base64.getEncoder().encodeToString(k.public.encoded)}"}""", headers + ("X-FF-Protocol" to "1"))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return obj(r.bodyAsText()).str("deviceId")
    }

    private fun signed(k: KeyPair, action: String, id: String, extra: String = "") =
        """"deviceId":"$id","t":$clock,"signature":"${sign(k, "$action|$id|$clock|$extra")}""""

    @Test
    fun everyCallTheLevel1AppMakes() = testApplication {
        application { module(store, FakeNotifier(), cfg) }
        val http = createClient { followRedirects = false }
        val owner = keyPair()
        val id = http.enroll(owner)

        // Version headers and /v1/version
        val v = http.get("/v1/version")
        assertEquals(HttpStatusCode.OK, v.status)
        assertTrue(obj(v.bodyAsText()).str("protocol").toInt() >= 1)
        assertTrue(v.headers["X-FF-Server-Protocol"] != null && v.headers["X-FF-Server-Oldest"] != null)

        // Heartbeat, with and without the gap account
        assertEquals(HttpStatusCode.OK, http.json("/v1/heartbeat", """{${signed(owner, "heartbeat", id, "0")},"lastSeq":0}""").status)
        val gap = "While it was quiet: the phone had no internet; protection stayed on the whole time."
        assertEquals(HttpStatusCode.OK, http.json("/v1/heartbeat",
            """{${signed(owner, "heartbeat", id, "0")},"lastSeq":0,"gap":"$gap","gapSignature":"${sign(owner, "gap|$id|$clock|${sha(gap)}")}"}""").status)

        // Report slice, alerts
        val blob = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(40) { it.toByte() })
        val from = clock - 3_600_000
        assertEquals(HttpStatusCode.OK, http.json("/v1/reports",
            """{"deviceId":"$id","t":$clock,"from":$from,"to":$clock,"blob":"$blob","signature":"${sign(owner, "report|$id|$clock|$from|$clock|${sha(blob)}")}"}""").status)
        val lines = listOf("Filter turned off")
        assertEquals(HttpStatusCode.OK, http.json("/v1/alerts",
            """{"deviceId":"$id","t":$clock,"lines":["Filter turned off"],"signature":"${sign(owner, "alerts|$id|$clock|${sha(lines.joinToString("\n"))}")}"}""").status)

        // Invite (partner and enroll-only), read as the app reads it
        val inv = obj(http.json("/v1/invites", "{${signed(owner, "invite", id)}}").bodyAsText())
        val joinUrl = inv.str("joinUrl"); inv.str("inviteId"); inv.str("expiresMs").toLong()
        obj(http.json("/v1/invites", """{${signed(owner, "invite", id)},"kind":"enroll"}""").bodyAsText()).str("joinUrl")

        // A second phone joins with the invite and accepts it
        val secret = joinUrl.substringAfter("/i/")
        val partner = keyPair()
        val pid = http.enroll(partner, mapOf("X-FF-Invite" to secret))
        val acc = obj(http.json("/v1/invites/accept", """{${signed(partner, "accept", pid, sha(secret))},"secret":"$secret","name":"Sam"}""").bodyAsText())
        acc.str("ownerName"); val reportUrl = acc.str("reportUrl")

        // The partner's report and alert feed
        val token = reportUrl.substringAfter("/p/")
        val data = obj(http.get("/p/$token/data").bodyAsText())
        assertTrue(data.getValue("reports").jsonArray.isNotEmpty())
        val feed = obj(http.get("/p/$token/alerts?after=0").bodyAsText())
        feed.getValue("alerts").jsonArray; feed.str("online")

        // Partners list and unlink, as the owner's app reads them
        val list = obj(http.json("/v1/partners/list", "{${signed(owner, "partners", id)}}").bodyAsText())
        list.getValue("partners").jsonArray
        val app = list.getValue("app").jsonArray.single().jsonObject
        val apId = app.str("id"); app.str("name"); app.str("inviteId")

        // Mailbox: signed create, then post and read with the token
        val k = Pairing.derive(Pairing.generateSecret())
        val th = Pairing.tokenHash(k.token)
        assertEquals(HttpStatusCode.OK, http.json("/v1/mailbox/${k.mailboxId}", """{${signed(owner, "mailbox", id, k.mailboxId)},"tokenHash":"$th"}""").status)
        assertEquals(HttpStatusCode.OK, http.json("/v1/mailbox/${k.mailboxId}/msgs", """{"c":"AAAA"}""", mapOf("X-FF-Token" to k.token)).status)
        val msgs = obj(http.get("/v1/mailbox/${k.mailboxId}/msgs?after=0") { header("X-FF-Token", k.token) }.bodyAsText()).getValue("msgs").jsonArray
        msgs.single().jsonObject.let { it.str("id"); it.str("t"); it.str("c") }

        assertEquals(HttpStatusCode.OK, http.json("/v1/partners/unlink", """{${signed(owner, "unlink", id, apId)},"partnerId":"$apId"}""").status)
    }
}
