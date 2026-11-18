package app.focusfence.server

import app.focusfence.dns.Pairing
import io.ktor.http.HttpClient
import io.ktor.http.request.forms.submitForm
import io.ktor.http.request.get
import io.ktor.http.request.header
import io.ktor.http.request.post
import io.ktor.http.request.setBody
import io.ktor.http.statement.HttpResponse
import io.ktor.http.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeNotifier : Notifier {
    val emails = ArrayList<Triple<String, String, String>>()
    val texts = ArrayList<Pair<String, String>>()
    override val smsAvailable = true
    override suspend fun email(to: String, subject: String, html: String): Boolean { emails += Triple(to, subject, html); return true }
    override suspend fun sms(to: String, text: String): Boolean { texts += to to text; return true }
}

class ServerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var clock = 1_800_000_000_000L
    private val notifier = FakeNotifier()
    private val store = Store(Files.createTempDirectory("ff-server").toFile())
    private val cfg = Config(publicUrl = "https://ff.test", now = { clock })
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(bytes); sign()
        })

    /** Redirects are not followed, so the join/verify flows can be asserted step by step. */
    private fun run(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store, notifier, cfg) }
        block(createClient { followRedirects = false })
    }

    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.enroll(): String {
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        val r = postJson("/v1/enroll", """{"publicKeySpki":"$spki"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString(EnrollResponse.serializer(), r.bodyAsText()).deviceId
    }

    private fun signed(action: String, id: String, extra: String = "", t: Long = clock, partnerId: String? = null): String {
        val sig = sign(Integrity.requestMessage(action, id, t, extra))
        val pid = if (partnerId != null) ""","partnerId":"$partnerId"""" else ""
        return """{"deviceId":"$id","t":$t,"signature":"$sig"$pid}"""
    }

    private suspend fun HttpClient.invite(id: String): InviteResponse =
        json.decodeFromString(InviteResponse.serializer(), postJson("/v1/invites", signed("invite", id)).bodyAsText())

    private suspend fun HttpClient.join(code: String, phone: String? = null): HttpResponse =
        submitForm("/join", Parameters.build {
            append("code", code); append("name", "Sam"); append("email", "sam@example.com")
            if (phone != null) { append("phone", phone); append("sms_consent", "yes") }
        })

    private suspend fun HttpClient.batch(id: String, first: Long, last: Long, prev: String, raw: String): HttpResponse {
        val sig = sign(Integrity.batchMessage(id, first, last, prev, raw))
        val body = json.encodeToString(BatchRequest.serializer(), BatchRequest(id, first, last, prev, raw, sig))
        return postJson("/v1/batches", body)
    }

    @Test
    fun rejectsNonP256Key() = run { http ->
        val r = http.postJson("/v1/enroll", """{"publicKeySpki":"AAAA"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun inviteJoinListAndUnlink() = run { http ->
        val id = http.enroll()
        val inv = http.invite(id)
        assertTrue(inv.joinUrl.startsWith("https://ff.test/join?code="))
        // Codes are forgiving to type: lowercase, no dash.
        val r = http.join(inv.code.lowercase().replace("-", ""))
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("sam@example.com", notifier.emails.last().first)

        val list = json.decodeFromString(PartnersResponse.serializer(), http.postJson("/v1/partners/list", signed("partners", id)).bodyAsText())
        assertEquals(1, list.partners.size)
        assertEquals("Sam", list.partners[0].name)
        assertTrue(list.partners[0].email.startsWith("sa…@"))

        // Single use.
        val again = http.join(inv.code)
        assertTrue(again.bodyAsText().contains("isn't valid"))

        // Unlink tells the partner.
        val pid = list.partners[0].id
        val before = notifier.emails.size
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/partners/unlink", signed("unlink", id, pid, partnerId = pid)).status)
        assertTrue(notifier.emails.drop(before).any { it.second.contains("removed") })
        assertEquals(0, store.device(id)!!.partners.size)
    }

    @Test
    fun inviteExpires() = run { http ->
        val id = http.enroll()
        val inv = http.invite(id)
        clock += cfg.inviteTtlMs + 1
        assertTrue(http.join(inv.code).bodyAsText().contains("isn't valid"))
    }

    @Test
    fun staleOrForgedSignedRequestsRejected() = run { http ->
        val id = http.enroll()
        val stale = http.postJson("/v1/invites", signed("invite", id, t = clock - cfg.clockSkewMs - 1))
        assertEquals(HttpStatusCode.Unauthorized, stale.status)
        // Signature for a different action can't be reused.
        val sig = sign(Integrity.requestMessage("partners", id, clock))
        val wrong = http.postJson("/v1/invites", """{"deviceId":"$id","t":$clock,"signature":"$sig"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun batchesChainAlertAndReportGaps() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code)
        notifier.emails.clear()

        val raw1 = """{"seq":1,"t":$clock,"type":"dns","host":"a.com","qtype":"A","decision":"allowed"}"""
        val r1 = http.batch(id, 1, 1, "GENESIS", raw1)
        assertEquals(HttpStatusCode.OK, r1.status)
        val next = json.decodeFromString(BatchResponse.serializer(), r1.bodyAsText()).nextHash
        assertTrue(notifier.emails.isEmpty(), "plain DNS records don't alert")

        // Seq 2..4 never arrive; an integrity change arrives.
        val raw2 = """{"seq":5,"t":$clock,"type":"integ","key":"a11y_enabled","value":"false","was":"true"}"""
        val r2 = http.batch(id, 5, 5, next, raw2)
        assertEquals(HttpStatusCode.OK, r2.status)
        val alert = notifier.emails.single()
        assertTrue(alert.third.contains("a11y_enabled changed"), alert.third)
        assertTrue(alert.third.contains("3 activity records never arrived"), alert.third)
        assertEquals(3, store.device(id)!!.missingRecords)

        // Replay is refused with the server's cursor so the phone can resync.
        val replay = http.batch(id, 5, 5, next, raw2)
        assertEquals(HttpStatusCode.Conflict, replay.status)
        val cursor = json.decodeFromString(CursorResponse.serializer(), replay.bodyAsText())
        assertEquals(6, cursor.expectedNextSeq)

        // Forged batch.
        val forged = http.postJson("/v1/batches", json.encodeToString(BatchRequest.serializer(),
            BatchRequest(id, 6, 6, cursor.lastHash, raw1, sign("other".toByteArray()))))
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun smsOptInVerifiedThenAlertsThrottled() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code, phone = "+1 (555) 123-4567")
        val (to, text) = notifier.texts.single()
        assertEquals("+15551234567", to)
        val code = Regex("\\d{6}").find(text)!!.value
        val token = store.device(id)!!.partners.single().token

        assertEquals(HttpStatusCode.Found, http.submitForm("/p/$token/sms/verify", Parameters.build { append("code", "000000") }).status)
        assertTrue(!store.device(id)!!.partners.single().phoneVerified)
        http.submitForm("/p/$token/sms/verify", Parameters.build { append("code", code) })
        assertTrue(store.device(id)!!.partners.single().phoneVerified)

        notifier.texts.clear()
        val raw = """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}"""
        val h = json.decodeFromString(BatchResponse.serializer(), http.batch(id, 1, 1, "GENESIS", raw).bodyAsText()).nextHash
        assertEquals(1, notifier.texts.size)
        assertTrue(notifier.texts[0].second.contains("revoked"))
        // A second alert inside the throttle window: email yes, text no.
        val raw2 = """{"seq":2,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}"""
        http.batch(id, 2, 2, h, raw2)
        assertEquals(1, notifier.texts.size)
    }

    @Test
    fun partnerPageNeedsToken() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code)
        val token = store.device(id)!!.partners.single().token
        val page = http.get("/p/$token").bodyAsText()
        assertTrue(page.contains("Focus Fence report"))
        assertTrue(page.contains("Protection was on the whole time"))
        assertEquals(HttpStatusCode.NotFound, http.get("/p/not-a-real-token-at-all-xx").status)
        // The old unauthenticated /partner/{deviceId} view is gone.
        assertEquals(HttpStatusCode.NotFound, http.get("/partner/$id").status)
    }

    @Test
    fun staleDeviceAlertsOnceAndWeeklyReportSends() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code)
        notifier.emails.clear()
        val alerts = Alerts(store, notifier, cfg)
        clock += cfg.staleMs + 60_000
        alerts.sweep()
        alerts.sweep()
        assertEquals(1, notifier.emails.count { it.second.contains("silent") })
        clock += cfg.reportEveryMs
        alerts.sweep()
        assertEquals(1, notifier.emails.count { it.second.contains("weekly report") })
    }

    @Test
    fun blindMailboxEndToEnd() = run { http ->
        // Owner's phone side.
        val secret = Pairing.generateSecret()
        val k = Pairing.derive(secret)
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"${Pairing.tokenHash(k.token)}"}""").status)
        val sealed = Pairing.seal(k.enc, """{"kind":"alert","text":"Filter turned off"}""".toByteArray(), k.mailboxId.toByteArray())
        val post = http.post("/v1/mailbox/${k.mailboxId}/msgs") {
            header("X-FF-Token", k.token)
            contentType(ContentType.Application.Json)
            setBody("""{"c":"${Base64.getEncoder().encodeToString(sealed)}"}""")
        }
        assertEquals(HttpStatusCode.OK, post.status)

        // What the server holds is opaque.
        val stored = store.readMail(k.mailboxId, 0).single()
        assertTrue(!String(Base64.getDecoder().decode(stored.c)).contains("Filter"))

        // Partner's phone, from the same pairing key typed in.
        val partner = Pairing.derive(Pairing.decode(Pairing.encode(secret))!!)
        val list = http.get("/v1/mailbox/${partner.mailboxId}/msgs?after=0") { header("X-FF-Token", partner.token) }
        val msgs = json.decodeFromString(MailListResponse.serializer(), list.bodyAsText()).msgs
        val plain = Pairing.open(partner.enc, Base64.getDecoder().decode(msgs.single().c), partner.mailboxId.toByteArray())
        assertNotNull(plain)
        assertTrue(String(plain).contains("Filter turned off"))

        // Wrong token: no read, no write. Someone else can't claim the id.
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/mailbox/${k.mailboxId}/msgs") { header("X-FF-Token", "nope") }.status)
        assertEquals(HttpStatusCode.Conflict, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"other"}""").status)
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/v1/mailbox/not-hex", """{"tokenHash":"x"}""").status)
        assertNull(Pairing.open(Pairing.derive(Pairing.generateSecret()).enc, Base64.getDecoder().decode(msgs.single().c), k.mailboxId.toByteArray()))
    }
}
