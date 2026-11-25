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
import io.ktor.http.HttpHeaders
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeNotifier : Notifier {
    val emails = ArrayList<Triple<String, String, String>>()
    val texts = ArrayList<Pair<String, String>>()
    /** Simulates the provider refusing (an unverified sender, an outage). */
    var failEmails = false
    override val smsAvailable = true
    override suspend fun email(to: String, subject: String, html: String): Boolean {
        if (failEmails) return false
        emails += Triple(to, subject, html); return true
    }
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
        assertTrue(inv.joinUrl.startsWith("https://ff.test/i/"))
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
    fun theInviteLinkBecomesThePartnersReportLinkForGood() = run { http ->
        // D-026: the partner holds one link; after joining and confirming it opens the report, any browser, any time.
        val id = http.enroll()
        val secret = http.invite(id).joinUrl.substringAfter("/i/")
        val form = http.get("/i/$secret")
        assertEquals(HttpStatusCode.OK, form.status)
        assertTrue(form.bodyAsText().contains("name='link'"))
        assertFalse(form.bodyAsText().contains("name='code'"), "no code to type when the link is the invite")
        http.submitForm("/join", Parameters.build { append("link", secret); append("name", "Sam"); append("email", "sam@example.com") })
        // Joined but not confirmed: the link says so and hands out no report token.
        val waiting = http.get("/i/$secret")
        assertEquals(HttpStatusCode.OK, waiting.status)
        assertTrue(waiting.bodyAsText().contains("Confirm your email"))
        assertEquals(HttpStatusCode.Found, http.post("/confirm/${emailedConfirmToken()}").status)
        val token = store.device(id)!!.partners.single().token
        repeat(2) {
            val go = http.get("/i/$secret")
            assertEquals(HttpStatusCode.Found, go.status)
            assertEquals("/p/$token", go.headers[HttpHeaders.Location], "the same link keeps working")
        }
        // The link can't be used to join a second time, and a removed partner's link stops working.
        val again = http.submitForm("/join", Parameters.build { append("link", secret); append("name", "Eve"); append("email", "eve@example.com") })
        assertTrue(again.bodyAsText().contains("It may have expired or already been used"))
        assertEquals(1, store.device(id)!!.partners.size)
        store.updateDevice(id) { it.partners = emptyList() }
        assertEquals(HttpStatusCode.NotFound, http.get("/i/$secret").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/i/short").status)
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
        // D-023: the page is a shell; the report itself is opened in the browser from sealed slices.
        assertTrue(page.contains("data-page=\"report\"") && page.contains("/static/report.js"))
        assertTrue(page.contains("cannot read it"))
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
    fun silenceAndReturnReachEmailAndAppPartners() = run { http ->
        val id = http.enroll()
        http.joinConfirmed(id)
        val token = "t".repeat(32)
        store.updateDevice(id) { it.appPartners = listOf(Store.AppPartner("ap1", "other-phone", "Sam", clock, token = token)) }
        notifier.emails.clear()
        val sweeper = Alerts(store, notifier, cfg)
        suspend fun feed(after: Long = 0) = json.decodeFromString(AlertFeed.serializer(), http.get("/p/$token/alerts?after=$after").bodyAsText())
        assertTrue(feed().online)
        assertTrue(feed().alerts.isEmpty())

        clock += cfg.staleMs - 60_000
        sweeper.sweep()
        assertTrue(feed().alerts.isEmpty(), "not silent yet")
        clock += 2 * 60_000
        sweeper.sweep()
        val silent = feed()
        assertTrue(!silent.online)
        assertTrue(silent.alerts.single().text.contains("hasn't checked in for 61 minutes"), silent.alerts.toString())
        assertEquals(1, notifier.emails.count { it.second.contains("silent") })

        // A check-in tells everyone it's back, once.
        clock += 60_000
        val gap = "While it was quiet: the phone had no internet; protection stayed on the whole time."
        val forged = """{"deviceId":"$id","t":$clock,"lastSeq":0,"signature":"${sign(Integrity.requestMessage("heartbeat", id, clock, "0"))}","gap":"Nothing happened.","gapSignature":"${sign(Integrity.requestMessage("gap", id, clock, Integrity.sha256(gap)))}"}"""
        val hb = """{"deviceId":"$id","t":$clock,"lastSeq":0,"signature":"${sign(Integrity.requestMessage("heartbeat", id, clock, "0"))}","gap":"$gap","gapSignature":"${sign(Integrity.requestMessage("gap", id, clock, Integrity.sha256(gap)))}"}"""
        // A gap account whose signature doesn't match is dropped; the phone is still back.
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/heartbeat", forged).status)
        var back = feed(after = silent.alerts.single().t)
        assertEquals("The phone is checking in again after 62 minutes without a check-in.", back.alerts.single().text)
        // Silence again, and this time the phone explains it.
        clock += cfg.staleMs + 60_000
        sweeper.sweep()
        val again = """{"deviceId":"$id","t":$clock,"lastSeq":0,"signature":"${sign(Integrity.requestMessage("heartbeat", id, clock, "0"))}","gap":"$gap","gapSignature":"${sign(Integrity.requestMessage("gap", id, clock, Integrity.sha256(gap)))}"}"""
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/heartbeat", again).status)
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/heartbeat", again).status)
        back = feed(after = back.alerts.single().t)
        assertTrue(back.online)
        assertEquals(2, back.alerts.size, back.alerts.toString()) // silent, then back
        assertEquals("The phone is checking in again after 61 minutes without a check-in. $gap", back.alerts.last().text)
        assertEquals(2, notifier.emails.count { it.second.contains("phone is back") })
        assertEquals(HttpStatusCode.NotFound, http.get("/p/${"x".repeat(32)}/alerts").status)
    }

    @Test
    fun theOpenReportSliceIsReplacedNotPiledUp() {
        val s = Store(Files.createTempDirectory("ff-slices").toFile())
        val h = 3_600_000L
        s.saveReport("d1", Store.SealedReport(10 * h, 9 * h, 10 * h, "closed"), 10 * h)
        s.saveReport("d1", Store.SealedReport(10 * h + 1, 10 * h, 10 * h + 15 * 60_000, "open-1"), 10 * h)
        s.saveReport("d1", Store.SealedReport(10 * h + 2, 10 * h + 20 * 60_000, 10 * h + 20 * 60_000, "shot"), 10 * h)
        s.saveReport("d1", Store.SealedReport(10 * h + 3, 10 * h, 10 * h + 30 * 60_000, "open-2"), 10 * h)
        assertEquals(listOf("closed", "shot", "open-2"), s.readReports("d1").map { it.blob })
    }

    @Test
    fun blindMailboxEndToEnd() = run { http ->
        // Owner's phone side. D-035: only an enrolled phone opens a new mailbox.
        val secret = Pairing.generateSecret()
        val k = Pairing.derive(secret)
        val th = Pairing.tokenHash(k.token)
        assertEquals(HttpStatusCode.NotFound, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"$th"}""").status, "no device, no mailbox")
        val id = http.enroll()
        val sig = sign(Integrity.requestMessage("mailbox", id, clock, k.mailboxId))
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"$th","deviceId":"$id","t":$clock,"signature":"${sign(Integrity.requestMessage("mailbox", id, clock, "other"))}"}""").status)
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"$th","deviceId":"$id","t":$clock,"signature":"$sig"}""").status)
        // Once it exists, the other side re-checks it with the token hash alone.
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/mailbox/${k.mailboxId}", """{"tokenHash":"$th"}""").status)
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

    @Test
    fun anInviteOpenedInTheAppEnrollsAndPairsThatPhone() = run(Config(publicUrl = "https://ff.test", enrollToken = "s3cret", now = { clock })) { http ->
        // D-035. The owner enrolled with the server's code; the partner phone has only the invite.
        val ownerSpki = Base64.getEncoder().encodeToString(keys.public.encoded)
        val owner = json.decodeFromString(EnrollResponse.serializer(), http.post("/v1/enroll") { header("X-FF-Enroll", "s3cret"); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$ownerSpki"}""") }.bodyAsText()).deviceId
        store.updateDevice(owner) { it.label = "Logan" }
        val inv = http.invite(owner)
        val secret = inv.joinUrl.substringAfter("/i/")
        assertTrue(inv.inviteId.isNotEmpty())

        val partnerKeys = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pSpki = Base64.getEncoder().encodeToString(partnerKeys.public.encoded)
        suspend fun enrollWith(h: String, v: String) = http.post("/v1/enroll") { header(h, v); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$pSpki"}""") }
        assertEquals(HttpStatusCode.Unauthorized, enrollWith("X-FF-Invite", "x".repeat(32)).status, "a made-up invite is no credential")
        val r = enrollWith("X-FF-Invite", secret)
        assertEquals(HttpStatusCode.OK, r.status)
        val partner = json.decodeFromString(EnrollResponse.serializer(), r.bodyAsText()).deviceId

        fun psign(action: String, extra: String): String = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(partnerKeys.private); update(Integrity.requestMessage(action, partner, clock, extra)); Base64.getEncoder().encodeToString(sign())
        }
        val body = """{"deviceId":"$partner","t":$clock,"signature":"${psign("accept", Integrity.sha256(secret))}","secret":"$secret","name":"Sam"}"""
        val acc = http.postJson("/v1/invites/accept", body)
        assertEquals(HttpStatusCode.OK, acc.status)
        val accepted = json.decodeFromString(AcceptResponse.serializer(), acc.bodyAsText())
        assertEquals("Logan", accepted.ownerName)
        // Same report as an email partner: the same page and the same sealed data, under their own link.
        val path = accepted.reportUrl.removePrefix("https://ff.test")
        assertTrue(path.startsWith("/p/"))
        assertEquals(HttpStatusCode.OK, http.get(path).status)
        assertTrue(!http.get(path).bodyAsText().contains("Text alerts"), "no texts for an app partner")
        assertEquals(HttpStatusCode.OK, http.get("$path/data").status)
        assertEquals(HttpStatusCode.NotFound, http.postJson("/v1/invites/accept", body).status, "single use")

        // The owner's phone sees which invite became an app partner, and can remove it.
        val list = json.decodeFromString(PartnersResponse.serializer(), http.postJson("/v1/partners/list", signed("partners", owner)).bodyAsText())
        val ap = list.app.single()
        assertEquals("Sam" to inv.inviteId, ap.name to ap.inviteId)
        assertEquals(partner, store.device(owner)!!.appPartners.single().deviceId, "the server knows who watches whom")
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/partners/unlink", signed("unlink", owner, ap.id, partnerId = ap.id)).status)
        assertTrue(store.device(owner)!!.appPartners.isEmpty())

        // A group's enroll-only invite lets a phone on, but isn't a partner invite in the app or in a browser.
        val g = json.decodeFromString(InviteResponse.serializer(), http.postJson("/v1/invites", signed("invite", owner).dropLast(1) + ""","kind":"enroll"}""").bodyAsText())
        val gs = g.joinUrl.substringAfter("/i/")
        assertEquals(HttpStatusCode.NotFound, http.get("/i/$gs").status)
        val gBody = """{"deviceId":"$partner","t":$clock,"signature":"${psign("accept", Integrity.sha256(gs))}","secret":"$gs","name":"Sam"}"""
        assertEquals(HttpStatusCode.NotFound, http.postJson("/v1/invites/accept", gBody).status)
        store.deleteDevice(partner)
        assertEquals(HttpStatusCode.OK, enrollWith("X-FF-Invite", gs).status)
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

    @Test
    fun aFailedSendDoesNotReplaceTheTokenOrKillTheOldLink() = run { http ->
        val id = http.enroll()
        http.join(http.invite(id).code)
        val first = emailedConfirmToken()
        val pid = store.device(id)!!.partners.single().id
        val before = store.device(id)!!.partners.single().confirmHash
        notifier.failEmails = true
        clock += 61 * 60_000
        http.post("/join/resend/$pid")
        assertEquals(before, store.device(id)!!.partners.single().confirmHash, "nobody got a new link, so nothing changes")
        assertEquals(HttpStatusCode.Found, http.post("/confirm/$first").status, "the first email's link still works")
    }

    @Test
    fun aFirstSendThatFailsLeavesNoDanglingHash() = run { http ->
        val id = http.enroll()
        notifier.failEmails = true
        http.join(http.invite(id).code)
        assertEquals(null, store.device(id)!!.partners.single().confirmHash)
        notifier.failEmails = false
        clock += 61 * 60_000
        http.post("/join/resend/${store.device(id)!!.partners.single().id}")
        assertEquals(HttpStatusCode.Found, http.post("/confirm/${emailedConfirmToken()}").status)
    }

    @Test
    fun perPersonCodesAreSingleUseRevocableAndExpire() {
        val dir = Files.createTempDirectory("ff-codes").toFile()
        val codes = EnrollCodes(java.io.File(dir, "codes.json"))
        val (plain, rec) = codes.create("friend", 1000, expiresMs = 5000, maxUses = 1)
        assertEquals(false, codes.redeem("nope", 1001))
        assertEquals(true, codes.redeem(plain.lowercase().replace("-", " "), 1001), "format-insensitive")
        assertEquals(false, codes.redeem(plain, 1002), "single use")
        val (p2, r2) = codes.create("two", 1000, expiresMs = 5000)
        assertEquals(false, codes.redeem(p2, 6000), "expired")
        val (p3, r3) = codes.create("three", 1000)
        assertEquals(true, codes.revoke(r3.id)); assertEquals(false, codes.redeem(p3, 1001), "revoked")
        assertEquals(false, java.io.File(dir, "codes.json").readText().contains(plain.replace("-", "")), "only the hash is stored")
        val out = mutableListOf<String>()
        assertEquals(true, runAdmin(arrayOf("admin", "codes", "list"), dir, 1001, out::add)); assertEquals(3, out.size)
        assertEquals(false, runAdmin(arrayOf("serve"), dir, 0, out::add))
    }

    @Test
    fun enrollAcceptsAPersonCodeAndTheBootstrapTokenAndNeverBurnsACodeOnABadKey() = run(Config(publicUrl = "https://ff.test", enrollToken = "boot", now = { clock })) { http ->
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        suspend fun enroll(code: String, key: String = spki) = http.post("/v1/enroll") { header("X-FF-Enroll", code); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$key"}""") }.status
        val (plain, _) = EnrollCodes(java.io.File(store.dir, "codes.json")).create("friend", clock, maxUses = 1)
        assertEquals(HttpStatusCode.BadRequest, enroll(plain, key = "garbage"), "bad key is rejected")
        assertEquals(HttpStatusCode.OK, enroll(plain), "the code was not burned by the bad key")
        assertEquals(HttpStatusCode.Unauthorized, enroll(plain), "now used up")
        assertEquals(HttpStatusCode.OK, enroll("boot"), "bootstrap token still works")
        assertEquals(HttpStatusCode.Unauthorized, enroll("zzzz"))
    }

    @Test
    fun theServerNoLongerServesTheAboutPages() = run { http ->
        // They live on GitHub Pages (2026-11-24); the server has no /about at all.
        assertEquals(HttpStatusCode.NotFound, http.get("/about/privacy.html").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/about").status)
    }

    // ---- D-023: the operator can't read a user's activity ---------------------------------------------------

    private suspend fun HttpClient.postReport(id: String, blob: String, from: Long = clock - 3_600_000, to: Long = clock): HttpResponse {
        val sig = sign(Integrity.requestMessage("report", id, clock, "$from|$to|${Integrity.sha256(blob)}"))
        return postJson("/v1/reports", json.encodeToString(ReportRequest.serializer(), ReportRequest(id, clock, from, to, blob, sig)))
    }

    @Test
    fun sealedReportsAreStoredAsCiphertextAndOpenOnlyWithThePartnersKey() = run { http ->
        val id = http.enroll()
        val token = http.joinConfirmed(id)
        val key = app.focusfence.dns.ReportSeal.newKey()
        val plain = """{"kind":"digest","from":1,"to":2,"lookups":42,"sites":"secret-site.example\t3"}"""
        val blob = app.focusfence.dns.ReportSeal.seal(key, plain.toByteArray(), id)
        assertEquals(HttpStatusCode.OK, http.postReport(id, blob).status)
        // On disk: ciphertext only. The site name never appears anywhere in the data dir.
        val onDisk = store.dir.walkTopDown().filter { it.isFile }.joinToString("") { it.readText() }
        assertTrue(!onDisk.contains("secret-site") && onDisk.contains(blob))
        // The partner page's data endpoint hands back the blob, and only the key opens it.
        val data = json.decodeFromString(ReportData.serializer(), http.get("/p/$token/data").bodyAsText())
        assertEquals(id, data.deviceId)
        assertEquals(plain, String(app.focusfence.dns.ReportSeal.open(key, data.reports.single().blob, data.deviceId)!!))
        assertNull(app.focusfence.dns.ReportSeal.open(app.focusfence.dns.ReportSeal.newKey(), data.reports.single().blob, data.deviceId))
        assertEquals(HttpStatusCode.NotFound, http.get("/p/not-a-real-token-at-all-xx/data").status)
    }

    @Test
    fun reportUploadsAreSignedAndBounded() = run { http ->
        val id = http.enroll()
        val forged = json.encodeToString(ReportRequest.serializer(), ReportRequest(id, clock, 1, 2, "abc", sign("nope".toByteArray())))
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/reports", forged).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, http.postReport(id, "x".repeat(Store.REPORT_MAX_CHARS + 1)).status)
    }

    @Test
    fun alertLinesAreEmailedButBatchesAreNeverStored() = run { http ->
        val id = http.enroll()
        http.joinConfirmed(id)
        notifier.emails.clear()
        val lines = listOf("Filter turned off")
        val sig = sign(Integrity.requestMessage("alerts", id, clock, Integrity.sha256(lines.joinToString("\n"))))
        val ok = http.postJson("/v1/alerts", json.encodeToString(AlertsRequest.serializer(), AlertsRequest(id, clock, lines, sig)))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(notifier.emails.single().third.contains("Filter turned off"))
        val bad = json.encodeToString(AlertsRequest.serializer(), AlertsRequest(id, clock, listOf("forged"), sig))
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/alerts", bad).status)
        // An older app still uploading raw batches: alerts work, nothing is written.
        http.batch(id, 1, 1, "GENESIS", """{"seq":1,"t":$clock,"type":"dns","host":"secret-site.example","decision":"allowed"}""")
        assertTrue(store.dir.walkTopDown().none { it.name.contains(".activity.jsonl") })
        assertTrue(store.dir.walkTopDown().filter { it.isFile }.none { it.readText().contains("secret-site") })
    }

    @Test
    fun legacyReadableRecordsArePurged() {
        val f = java.io.File(store.dir, "devices/abc.activity.jsonl").apply { writeText("{}\n") }
        java.io.File(store.dir, "devices/abc.activity.jsonl.1").writeText("{}\n")
        assertEquals(2, store.purgeLegacyRecords())
        assertTrue(!f.exists())
    }

    @Test
    fun scriptsAreAllowedOnlyOnTheReportPagesAndTheWeeklyEmailCarriesNoReport() = run { http ->
        assertTrue(http.get("/p/x").headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertTrue(!http.get("/").headers["Content-Security-Policy"]!!.contains("script-src"))
        val js = http.get("/static/report.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue(!js.bodyAsText().contains("innerHTML ="), "report text is data: built with textContent only")
        val id = http.enroll()
        http.joinConfirmed(id)
        notifier.emails.clear()
        clock += cfg.reportEveryMs
        store.updateDevice(id) { it.lastSeenMs = clock }
        Alerts(store, notifier, cfg).sweep()
        val weekly = notifier.emails.single { it.second.contains("weekly report") }.third
        assertTrue(weekly.contains("end-to-end encrypted") && !weekly.contains("lookups"))
    }
}
