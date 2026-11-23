package app.focusfence.server

import app.focusfence.dns.Pairing
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
    private fun run(config: Config = cfg, block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store, notifier, config) }
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

    private suspend fun HttpClient.join(code: String, phone: String? = null, email: String = "sam@example.com"): HttpResponse =
        submitForm("/join", Parameters.build {
            append("code", code); append("name", "Sam"); append("email", email)
            if (phone != null) { append("phone", phone); append("sms_consent", "yes") }
        })

    /** The confirmation token exactly as the address owner would get it: from the email, nowhere else. */
    private fun emailedConfirmToken(): String =
        Regex("/confirm/([A-Za-z0-9_-]+)").find(notifier.emails.last { it.second.contains("confirm") }.third)!!.groupValues[1]

    /** Joins, then clicks the emailed confirm button, so alerts flow. Returns the partner's report token. */
    private suspend fun HttpClient.joinConfirmed(id: String, phone: String? = null): String {
        join(invite(id).code, phone)
        assertEquals(HttpStatusCode.Found, post("/confirm/${emailedConfirmToken()}").status)
        return store.device(id)!!.partners.last().token
    }

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
        assertEquals(HttpStatusCode.OK, r.status) // a "check your email" page, not a redirect to a report
        // Only a confirmation email goes out at join; it carries the confirm link, not the report link.
        assertEquals("sam@example.com", notifier.emails.last().first)
        assertTrue(notifier.emails.last().second.contains("confirm"))

        val list = json.decodeFromString(PartnersResponse.serializer(), http.postJson("/v1/partners/list", signed("partners", id)).bodyAsText())
        assertEquals(1, list.partners.size)
        assertEquals("Sam", list.partners[0].name)
        assertTrue(list.partners[0].email.startsWith("sa…@"))
        assertTrue(!list.partners[0].confirmed)
        http.post("/confirm/${emailedConfirmToken()}")

        // Single use.
        val again = http.join(inv.code)
        assertTrue(again.bodyAsText().contains("isn&#39;t valid"))

        // Unlink tells the partner.
        val pid = list.partners[0].id
        val before = notifier.emails.size
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/partners/unlink", signed("unlink", id, pid, partnerId = pid)).status)
        assertTrue(notifier.emails.drop(before).any { it.second.contains("removed") }) // confirmed, so they are told
        assertEquals(0, store.device(id)!!.partners.size)
    }

    @Test
    fun inviteExpires() = run { http ->
        val id = http.enroll()
        val inv = http.invite(id)
        clock += cfg.inviteTtlMs + 1
        assertTrue(http.join(inv.code).bodyAsText().contains("isn&#39;t valid"))
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
        http.joinConfirmed(id)
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
        val token = http.joinConfirmed(id)
        assertTrue(notifier.texts.isEmpty(), "no text at join")
        http.submitForm("/p/$token/sms", Parameters.build { append("phone", "+1 (555) 123-4567"); append("sms_consent", "yes") })
        val (to, text) = notifier.texts.single()
        assertEquals("+15551234567", to)
        val code = Regex("\\d{6}").find(text)!!.value

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
        clock += 2 * 60_000 // past the per-address email gap, still inside the 10 min text throttle
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
        http.joinConfirmed(id)
        notifier.emails.clear()
        val alerts = Alerts(store, notifier, cfg)
        clock += cfg.staleMs + 60_000
        alerts.sweep()
        alerts.sweep()
        assertEquals(1, notifier.emails.count { it.second.contains("silent") })
        clock += cfg.reportEveryMs
        store.updateDevice(id) { it.lastSeenMs = clock } // the phone is back, so only the report is due
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
        assertEquals(HttpStatusCode.Conflict, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"${Pairing.tokenHash("someone else")}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/v1/mailbox/not-hex", """{"tokenHash":"x"}""").status)
        assertNull(Pairing.open(Pairing.derive(Pairing.generateSecret()).enc, Base64.getDecoder().decode(msgs.single().c), k.mailboxId.toByteArray()))
    }

    // ---- audit fixes (extraction session, 2026-11-23) ------------------------------------------------

    @Test
    fun joinFormEscapesQuotesAndSendsACsp() = run { http ->
        val r = http.get("/join?code=%27%20autofocus%20onfocus%3D%27x")
        val body = r.bodyAsText()
        // The quote stays inside value='...' as &#39;, so it can't close the attribute and inject autofocus/onfocus.
        assertTrue(!body.contains("value='' autofocus"), body)
        assertTrue(body.contains("value='&#39; autofocus"), body)
        val csp = r.headers["Content-Security-Policy"].orEmpty()
        assertTrue(csp.contains("default-src 'none'") && csp.contains("frame-ancestors 'none'"), csp)
    }

    @Test
    fun unconfirmedAddressGetsOnlyTheConfirmationEmail() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code)
        val token = emailedConfirmToken()
        assertEquals(1, notifier.emails.size)

        // An alert-worthy batch reaches nobody who hasn't confirmed.
        http.batch(id, 1, 1, "GENESIS", """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}""")
        assertEquals(1, notifier.emails.size)

        // A link prefetcher (GET) must not confirm; only the button (POST) does.
        assertEquals(HttpStatusCode.OK, http.get("/confirm/$token").status)
        assertTrue(!store.device(id)!!.partners.single().confirmed)
        http.post("/confirm/$token")
        assertTrue(store.device(id)!!.partners.single().confirmed)
        assertEquals(null, store.device(id)!!.partners.single().confirmHash, "the token is single use")
        assertTrue(notifier.emails.last().second.contains("now an accountability partner"))
    }

    @Test
    fun unconfirmedPartnersExpireAndThePartnerCountIsCapped() = run { http ->
        val id = http.enroll()
        repeat(cfg.maxPartnersPerDevice) { http.join(http.invite(id).code) }
        assertTrue(http.join(http.invite(id).code).bodyAsText().contains("most partners"))
        clock += cfg.unconfirmedTtlMs + 1
        store.updateDevice(id) { it.lastSeenMs = clock }
        Alerts(store, notifier, cfg).sweep()
        assertEquals(0, store.device(id)!!.partners.size)
    }

    @Test
    fun anAddressIsNotEmailedFasterThanTheGap() = run { http ->
        val id = http.enroll()
        http.joinConfirmed(id)
        notifier.emails.clear()
        val a = """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}"""
        val h = json.decodeFromString(BatchResponse.serializer(), http.batch(id, 1, 1, "GENESIS", a).bodyAsText()).nextHash
        http.batch(id, 2, 2, h, a.replace("\"seq\":1", "\"seq\":2"))
        assertEquals(1, notifier.emails.size, "second alert inside the gap is held back")
    }

    @Test
    fun enrollIsCappedAndRateLimited() = run(Config(publicUrl = "https://ff.test", maxDevices = 1, now = { clock })) { http ->
        http.enroll()
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertEquals(HttpStatusCode.InsufficientStorage, http.postJson("/v1/enroll", """{"publicKeySpki":"$spki"}""").status)
    }

    @Test
    fun enrollIsRateLimitedPerClient() = run(Config(publicUrl = "https://ff.test", maxDevices = 1000, now = { clock })) { http ->
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        repeat(5) { assertEquals(HttpStatusCode.OK, http.postJson("/v1/enroll", """{"publicKeySpki":"$spki"}""").status) }
        assertEquals(HttpStatusCode.TooManyRequests, http.postJson("/v1/enroll", """{"publicKeySpki":"$spki"}""").status)
    }

    @Test
    fun diskBudgetStopsWritesButNotReads() = run(Config(publicUrl = "https://ff.test", maxDataBytes = 1, now = { clock })) { http ->
        val raw = """{"seq":1,"t":$clock,"type":"dns","host":"a.com","qtype":"A","decision":"allowed"}"""
        val d = store.createDevice(Base64.getEncoder().encodeToString(keys.public.encoded), clock)
        assertEquals(HttpStatusCode.InsufficientStorage, http.batch(d.id, 1, 1, "GENESIS", raw).status)
    }

    @Test
    fun shotsAreSizeCappedAndPrunedPerDevice() = run { http ->
        val id = http.enroll()
        fun shotBody(shotId: String, bytes: Int): String {
            val sig = sign(Integrity.requestMessage("shot", id, clock, shotId))
            val b64 = Base64.getEncoder().encodeToString(ByteArray(bytes))
            return """{"deviceId":"$id","t":$clock,"shotId":"$shotId","ciphertextB64":"$b64","signature":"$sig"}"""
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, http.postJson("/v1/shots", shotBody("big", Store.SHOT_MAX_BYTES + 1)).status)
        repeat(Store.SHOTS_PER_DEVICE + 5) { assertEquals(HttpStatusCode.OK, http.postJson("/v1/shots", shotBody("s$it", 16)).status) }
        val kept = java.io.File(store.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(store) as java.io.File, "blobs")
            .listFiles { f -> f.name.startsWith(id) }!!.size
        assertEquals(Store.SHOTS_PER_DEVICE, kept)
    }

    @Test
    fun recordsMustSpanTheClaimedSeqRange() = run { http ->
        val id = http.enroll()
        fun rec(n: Int) = """{"seq":$n,"t":$clock,"type":"dns","host":"a.com","qtype":"A","decision":"allowed"}"""
        // claims 1..3, carries only seq 1
        assertEquals(HttpStatusCode.Conflict, http.batch(id, 1, 3, "GENESIS", rec(1)).status)
        // out of order
        assertEquals(HttpStatusCode.Conflict, http.batch(id, 1, 2, "GENESIS", rec(2) + "\n" + rec(1)).status)
        // a record with no seq at all
        assertEquals(HttpStatusCode.Conflict, http.batch(id, 1, 1, "GENESIS", """{"t":1,"type":"dns"}""").status)
        // the honest batch still goes through, with a gap inside the range allowed
        assertEquals(HttpStatusCode.OK, http.batch(id, 1, 3, "GENESIS", rec(1) + "\n" + rec(3)).status)
    }

    @Test
    fun errorsDontLeakExceptionText() = run { http ->
        val r = http.postJson("/v1/enroll", """{"publicKeySpki":123}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(!r.bodyAsText().contains("Exception") && !r.bodyAsText().contains("kotlinx"), r.bodyAsText())
    }


    // ---- re-audit (extraction session, 2026-11-23) --------------------------------------------------

    @Test
    fun theJoinerIsNeverGivenAnyTokenSoCantConfirmSomeoneElsesAddress() = run { http ->
        val id = http.enroll()
        val r = http.join(http.invite(id).code)
        assertEquals(HttpStatusCode.OK, r.status, "no redirect to a report")
        val body = r.bodyAsText()
        val partner = store.device(id)!!.partners.single()
        val emailed = emailedConfirmToken()
        assertTrue(!body.contains(partner.token) && !body.contains(emailed) && !body.contains("/p/"), body)
        assertTrue(r.headers["Location"] == null)
        // the report token is not the confirmation token, and confirming with it does nothing
        assertEquals(HttpStatusCode.NotFound, http.post("/confirm/${partner.token}").status)
        assertTrue(!store.device(id)!!.partners.single().confirmed)
        // only the emailed token confirms, once, and then yields the report link
        val done = http.post("/confirm/$emailed")
        assertEquals(HttpStatusCode.Found, done.status)
        assertEquals("/p/${partner.token}?welcome=1", done.headers["Location"])
        assertEquals(HttpStatusCode.NotFound, http.post("/confirm/$emailed").status)
    }

    @Test
    fun resendMintsANewTokenAndTheOldLinkDies() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code)
        val first = emailedConfirmToken()
        val pid = store.device(id)!!.partners.single().id
        http.post("/join/resend/$pid")
        assertEquals(1, notifier.emails.size, "inside the hour: nothing more is sent")
        clock += 61 * 60_000
        val page = http.post("/join/resend/$pid")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(!page.bodyAsText().contains(store.device(id)!!.partners.single().token))
        assertEquals(2, notifier.emails.size)
        val second = emailedConfirmToken()
        assertTrue(first != second)
        assertEquals(HttpStatusCode.NotFound, http.post("/confirm/$first").status)
        assertEquals(HttpStatusCode.Found, http.post("/confirm/$second").status)
        assertEquals(HttpStatusCode.OK, http.post("/join/resend/not-a-partner").status) // doesn't reveal what exists
    }

    @Test
    fun confirmationEmailsHaveAWholeServerDailyCap() = run(Config(publicUrl = "https://ff.test", confirmGlobalDailyCap = 2, maxPartnersPerDevice = 10, now = { clock })) { http ->
        val id = http.enroll()
        repeat(4) { http.join(http.invite(id).code, email = "p$it@example.com") }
        assertEquals(2, notifier.emails.size)
    }

    @Test
    fun alertEmailsHaveAWholeServerDailyCap() = run(Config(publicUrl = "https://ff.test", emailGlobalDailyCap = 1, maxPartnersPerDevice = 10, now = { clock })) { http ->
        val id = http.enroll()
        repeat(2) { n ->
            http.join(http.invite(id).code, email = "q$n@example.com")
            http.post("/confirm/${emailedConfirmToken()}")
        }
        notifier.emails.clear()
        http.batch(id, 1, 1, "GENESIS", """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}""")
        assertEquals(1, notifier.emails.size, "two confirmed partners, but the server-wide cap lets one email out")
    }

    @Test
    fun enrollNeedsTheEnrollmentCodeWhenOneIsSet() = run(Config(publicUrl = "https://ff.test", enrollToken = "s3cret", now = { clock })) { http ->
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/enroll", """{"publicKeySpki":"$spki"}""").status)
        val wrong = http.post("/v1/enroll") { header("X-FF-Enroll", "nope"); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$spki"}""") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val right = http.post("/v1/enroll") { header("X-FF-Enroll", "s3cret"); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$spki"}""") }
        assertEquals(HttpStatusCode.OK, right.status)
    }

    @Test
    fun aDevicesShotsAreCappedInBytesTooOldestFirst() {
        val s = Store(Files.createTempDirectory("ff-shots").toFile(), shotBytesPerDevice = 100)
        repeat(3) { i -> s.saveBlob("dev", "s$i", ByteArray(60)); Thread.sleep(15) }
        val kept = java.io.File(s.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(s) as java.io.File, "blobs").listFiles()!!.map { it.name }
        assertEquals(listOf("dev_s2.bin"), kept, "180 bytes over a 100 byte budget: only the newest fits")
    }
}
