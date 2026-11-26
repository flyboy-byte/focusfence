package app.focusfence.server.compat

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
 * Protocol level 2 (plan 13), as the app at level 2 sends it: literal paths, literal JSON field names, the literal
 * signature messages ("action|deviceId|t|extra") and the response fields the app reads. Deliberately NOT built from
 * the server's own data classes, so renaming a field or changing a format breaks this test.
 *
 * Keep this passing until level 2 falls out of the window; then delete it. Never edit it to match a new server: that
 * is what a new level is for.
 */
class CompatLevel2Test {
    private var clock = 1_800_000_000_000L
    private val store = Store(Files.createTempDirectory("ff-compat2").toFile())
    private val cfg = Config(publicUrl = "https://ff.test", now = { clock })
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun sign(k: KeyPair, msg: String) = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(msg.toByteArray()); sign() })
    /** The app's sha256 helper: base64url, no padding. */
    private fun sha(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private suspend fun HttpClient.json(path: String, body: String): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); header("X-FF-Protocol", "2"); setBody(body) }

    @Test
    fun theLevelTwoAppStillWorks() = testApplication {
        application { module(store, FakeNotifier(), cfg) }
        val http = createClient { followRedirects = false }
        val owner = keyPair()
        val r = http.json("/v1/enroll", """{"publicKeySpki":"${Base64.getEncoder().encodeToString(owner.public.encoded)}"}""")
        assertEquals("2", r.headers["X-FF-Server-Protocol"])
        val id = obj(r.bodyAsText()).str("deviceId")
        fun signed(action: String, extra: String) = """"deviceId":"$id","t":$clock,"signature":"${sign(owner, "$action|$id|$clock|$extra")}""""

        // The owner invites by code.
        val inv = obj(http.json("/v1/invites", """{${signed("invite", "")},"email":"","from":"Logan"}""").bodyAsText())
        val code = inv.str("code")
        assertTrue(inv.str("qrUrl").endsWith("/c/" + code.replace("-", "")))
        assertTrue(inv.getValue("expiresMs").jsonPrimitive.content.toLong() > clock)
        inv.str("id"); inv.str("emailed")

        // A partner's app joins with it.
        val partner = keyPair()
        val j = obj(http.json("/v1/join", """{"code":"$code","name":"Sam","kind":"app","label":"Pixel 8","publicKeySpki":"${Base64.getEncoder().encodeToString(partner.public.encoded)}"}""").bodyAsText())
        val token = j.str("token"); val pdev = j.str("deviceId")
        assertEquals("Logan", j.str("ownerName"))
        assertTrue(j.str("reportUrl").endsWith("/p/$token")); j.str("partnerId")

        // The owner's heartbeat names the device to seal to; the phone seals and posts it.
        clock += 1_000
        val hb = obj(http.json("/v1/heartbeat", """{${signed("heartbeat", "0")},"lastSeq":0,"noticesAfter":0,"keyId":"k1"}""").bodyAsText())
        val target = hb.getValue("seal").jsonArray.single().jsonObject
        assertEquals(pdev, target.str("deviceId")); target.str("publicKeySpki"); target.str("partnerId"); target.str("name"); target.str("firstDevice")
        hb.getValue("notices").jsonArray; hb.str("invitesOpen"); hb.str("rotateKey")
        val sealed = "FFS1-opaque-to-the-server"
        clock += 1_000
        assertEquals(HttpStatusCode.OK, http.json("/v1/seal", """{${signed("seal", "$pdev|k1|${sha(sealed)}")},"partnerDeviceId":"$pdev","keyId":"k1","sealed":"$sealed"}""").status)

        // The partner's app reads its sealed key and the feed (no protocol header: /p/ paths are the report link's).
        val key = obj(http.get("/p/$token/key?d=$pdev").bodyAsText())
        assertEquals("true" to sealed, key.str("ready") to key.str("sealed"))
        val feed = obj(http.get("/p/$token/alerts?after=0&d=$pdev").bodyAsText())
        feed.getValue("alerts").jsonArray; feed.str("online"); feed.str("lastSeenMs"); feed.str("level"); feed.str("quiet"); feed.str("quietFrom"); feed.str("quietTo")

        // The owner's list.
        clock += 1_000
        val list = obj(http.json("/v1/partners/list", """{${signed("partners", "")}}""").bodyAsText())
        val p = list.getValue("partners").jsonArray.single().jsonObject
        assertEquals("Sam" to "app", p.str("name") to p.str("alertsBy"))
        val d = p.getValue("devices").jsonArray.single().jsonObject
        assertEquals("Pixel 8" to "true", d.str("label") to d.str("ready")); d.str("kind"); d.str("id"); d.str("addedMs")
        p.str("id"); p.str("email"); p.str("lastViewMs"); p.str("linkedMs")
        list.getValue("invites").jsonArray

        // Alerts, an ask, and removing the partner.
        val lines = "Filter turned off"
        assertEquals(HttpStatusCode.OK, http.json("/v1/alerts", """{${signed("alerts", sha(lines))},"lines":["$lines"],"minor":[]}""").status)
        clock += 1_000
        val ask = obj(http.json("/v1/unlock/ask", """{${signed("unlock", "guard_vpn_off")},"kind":"guard_vpn_off"}""").bodyAsText())
        assertEquals("pending", ask.str("state")); ask.str("id")
        clock += 1_000
        val pid = p.str("id")
        assertEquals(HttpStatusCode.OK, http.json("/v1/partners/unlink", """{${signed("unlink", pid)},"partnerId":"$pid"}""").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/p/$token/alerts").status)
    }
}
