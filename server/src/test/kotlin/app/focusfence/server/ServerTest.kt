package app.focusfence.server

import app.focusfence.dns.Ecies
import app.focusfence.dns.ReportSeal
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeNotifier : Notifier {
    val emails = ArrayList<Triple<String, String, String>>()
    /** Simulates the provider refusing (an unverified sender, an outage). */
    var failEmails = false
    var noEmail = false
    override val emailAvailable get() = !noEmail
    override suspend fun email(to: String, subject: String, html: String): Boolean {
        if (failEmails) return false
        emails += Triple(to, subject, html); return true
    }
}

private const val P = app.focusfence.dns.Protocol.HEADER_APP

class ServerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var clock = 1_800_000_000_000L
    private val notifier = FakeNotifier()
    private val store = Store(Files.createTempDirectory("ff-server").toFile())
    private val cfg = Config(publicUrl = "https://ff.test", now = { clock })
    private val keys: KeyPair = newKeys()
    private val reportKey = ReportSeal.newKey()
    private val keyId = "k1"

    private fun newKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun spki(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)

    private fun sign(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(bytes); sign()
        })

    /** Redirects are not followed, so flows can be asserted step by step. */
    private fun run(config: Config = cfg, block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store, notifier, config) }
        block(createClient { followRedirects = false })
    }

    /** Every app request carries its protocol level (D-041); level 2 is plan 13's. */
    private suspend fun HttpClient.postJson(path: String, body: String, level: String? = "2"): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (level != null) header(P, level)
        setBody(body)
    }

    private suspend fun HttpClient.enroll(): String {
        val r = postJson("/v1/enroll", """{"publicKeySpki":"${spki(keys)}"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString(EnrollResponse.serializer(), r.bodyAsText()).deviceId
    }

    private fun signed(action: String, id: String, extra: String = "", t: Long = clock, more: String = ""): String {
        val sig = sign(Integrity.requestMessage(action, id, t, extra))
        return """{"deviceId":"$id","t":$t,"signature":"$sig"$more}"""
    }

    private suspend fun HttpClient.invite(id: String, email: String = "", from: String = "Logan"): InviteResponse {
        val r = postJson("/v1/invites", signed("invite", id, email, more = ""","email":"$email","from":"$from""""))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(InviteResponse.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.join(code: String = "", link: String = "", name: String = "Sam", kind: String = "app", k: KeyPair = newKeys()): HttpResponse =
        postJson("/v1/join", """{"code":"$code","link":"$link","name":"$name","kind":"$kind","label":"Pixel 8","publicKeySpki":"${spki(k)}"}""")

    private suspend fun HttpClient.joined(code: String = "", link: String = "", name: String = "Sam", kind: String = "app", k: KeyPair = newKeys()): JoinResponse {
        val r = join(code, link, name, kind, k)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(JoinResponse.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.heartbeat(id: String, key: String = keyId, after: Long = 0): HeartbeatResponse {
        clock += 1_000
        val body = """{"deviceId":"$id","t":$clock,"lastSeq":0,"signature":"${sign(Integrity.requestMessage("heartbeat", id, clock, "0"))}","noticesAfter":$after,"keyId":"$key"}"""
        val r = postJson("/v1/heartbeat", body)
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString(HeartbeatResponse.serializer(), r.bodyAsText())
    }

    /** What the owner's phone does with each heartbeat's seal list: the report key, sealed to that device's public key. */
    private suspend fun HttpClient.seal(id: String, t: SealTarget, key: String = keyId, rk: String = reportKey): HttpResponse {
        val pub = Ecies.publicKeyFromSpki(Base64.getDecoder().decode(t.publicKeySpki))
        val sealed = Base64.getUrlEncoder().withoutPadding().encodeToString(Ecies.encrypt(rk.toByteArray(), pub, Ecies.INFO_KEY))
        val extra = "${t.deviceId}|$key|${Integrity.sha256(sealed)}"
        return postJson("/v1/seal", signed("seal", id, extra, more = ""","partnerDeviceId":"${t.deviceId}","keyId":"$key","sealed":"$sealed""""))
    }

    private suspend fun HttpClient.sealAll(id: String, key: String = keyId) {
        for (t in heartbeat(id, key).seal) assertEquals(HttpStatusCode.OK, seal(id, t, key).status)
    }

    private fun emailedSecret(): String = Regex("/j/([A-Za-z0-9_-]+)").find(notifier.emails.last { it.third.contains("/j/") }.third)!!.groupValues[1]

    /** An app partner by code, with the key sealed to their phone. Returns the join. */
    private suspend fun HttpClient.appPartner(id: String, name: String = "Sam"): JoinResponse {
        val j = joined(code = invite(id).code, name = name)
        sealAll(id)
        return j
    }

    /** An email partner: the emailed link, opened in a browser. */
    private suspend fun HttpClient.emailPartner(id: String, email: String = "jo@example.com", name: String = "Jo"): JoinResponse {
        invite(id, email)
        val j = joined(link = emailedSecret(), name = name, kind = "web")
        sealAll(id)
        return j
    }

    private suspend fun HttpClient.list(id: String): PartnersResponse {
        clock += 1_000
        return json.decodeFromString(PartnersResponse.serializer(), postJson("/v1/partners/list", signed("partners", id)).bodyAsText())
    }

    private suspend fun HttpClient.batch(id: String, first: Long, last: Long, prev: String, raw: String): HttpResponse {
        val sig = sign(Integrity.batchMessage(id, first, last, prev, raw))
        return postJson("/v1/batches", json.encodeToString(BatchRequest.serializer(), BatchRequest(id, first, last, prev, raw, sig)))
    }

    private suspend fun HttpClient.postReport(id: String, blob: String, from: Long = clock - 3_600_000, to: Long = clock): HttpResponse {
        val sig = sign(Integrity.requestMessage("report", id, clock, "$from|$to|${Integrity.sha256(blob)}"))
        return postJson("/v1/reports", json.encodeToString(ReportRequest.serializer(), ReportRequest(id, clock, from, to, blob, sig)))
    }

    // ---- plan 13: the protocol cut ------------------------------------------------------------------

    @Test
    fun appsBelowLevelTwoAreRefusedWithAClearMessage() = run { http ->
        val body = """{"publicKeySpki":"${spki(keys)}"}"""
        for (level in listOf(null, "1", "x")) {
            val r = http.postJson("/v1/enroll", body, level)
            assertEquals(HttpStatusCode.UpgradeRequired, r.status)
            assertTrue(r.bodyAsText().contains("Update the app"))
            assertEquals("2", r.headers[app.focusfence.dns.Protocol.HEADER_SERVER_OLDEST])
        }
        assertEquals(HttpStatusCode.OK, http.get("/v1/version").status, "anyone can ask which levels are served")
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/enroll", body).status)
    }

    @Test
    fun theOldPartnerPathsAreGone() = run { http ->
        val id = http.enroll()
        for (path in listOf("/i/" + "x".repeat(32), "/join", "/confirm/" + "x".repeat(32), "/key")) assertEquals(HttpStatusCode.NotFound, http.get(path).status, path)
        for (path in listOf("/v1/invites/accept", "/v1/mailbox/" + "a".repeat(64), "/v1/shots", "/join")) assertEquals(HttpStatusCode.NotFound, http.postJson(path, "{}").status, path)
        assertEquals(HttpStatusCode.NotFound, http.get("/.well-known/assetlinks.json").status, "no server is special")
        assertTrue(store.device(id)!!.schema == 2)
    }

    @Test
    fun oldPartnerDataIsDeletedNotConverted() {
        val d = store.createDevice(spki(keys), clock)
        store.updateDevice(d.id) { it.schema = 1; it.partners = listOf(Store.Partner("p", "Old", "t".repeat(32), clock)); it.invites = listOf(Store.Invite("i", "h", clock + 1)) }
        java.io.File(store.dir, "mailbox").apply { mkdirs(); java.io.File(this, "x.json").writeText("{}") }
        java.io.File(store.dir, "blobs").apply { mkdirs(); java.io.File(this, "x_1.bin").writeText("x") }
        assertEquals(1, store.migrateToV2())
        val after = store.device(d.id)!!
        assertTrue(after.partners.isEmpty() && after.invites.isEmpty() && after.schema == 2)
        assertFalse(java.io.File(store.dir, "mailbox").exists() || java.io.File(store.dir, "blobs").exists())
        assertEquals(0, store.migrateToV2(), "runs at every start, does nothing twice")
    }

    // ---- plan 13: inviting, joining, the key ----------------------------------------------------------

    @Test
    fun aCodeJoinsInTheAppAndTheOwnersPhoneSealsTheKeyToIt() = run { http ->
        val id = http.enroll()
        val inv = http.invite(id)
        assertTrue(Regex("^[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(inv.code), inv.code)
        assertEquals("https://ff.test/c/${inv.code.replace("-", "")}", inv.qrUrl)
        assertFalse(inv.emailed)
        assertEquals(1, http.heartbeat(id).invitesOpen, "while an invite is open the phone checks in every minute")
        assertTrue(http.get("/c/${inv.code.replace("-", "")}").bodyAsText().contains("See Logan&#39;s report"))

        // Typed sloppily: lower case, no dash, an O for a 0.
        val k = newKeys()
        val j = http.joined(code = inv.code.lowercase().replace("-", " ").replace('0', 'o'), k = k)
        assertEquals("Logan" to "https://ff.test/p/${j.token}", j.ownerName to j.reportUrl)
        assertEquals(HttpStatusCode.NotFound, http.join(code = inv.code).status, "single use")
        assertEquals(0, http.heartbeat(id).invitesOpen)

        // Joined, not yet sealed: listed, not ready, and the device's key isn't there yet.
        val listed = http.list(id).partners.single()
        assertEquals("Sam" to "app", listed.name to listed.alertsBy)
        assertEquals(listOf("Pixel 8" to false), listed.devices.map { it.label to it.ready })
        assertEquals(false, json.decodeFromString(KeyResponse.serializer(), http.get("/p/${j.token}/key?d=${j.deviceId}").bodyAsText()).ready)

        // No key id: the phone hasn't said which key it uses, so it isn't asked to seal.
        assertTrue(http.heartbeat(id, key = "").seal.isEmpty())
        val target = http.heartbeat(id).seal.single()
        assertEquals(Triple(j.deviceId, "Sam", true), Triple(target.deviceId, target.name, target.firstDevice))
        assertEquals(HttpStatusCode.Conflict, http.seal(id, target, key = "k0").status, "sealed with a key the phone no longer uses")
        val forged = http.postJson("/v1/seal", signed("seal", id, "other", more = ""","partnerDeviceId":"${target.deviceId}","keyId":"$keyId","sealed":"x""""))
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertEquals(HttpStatusCode.OK, http.seal(id, target).status)
        assertTrue(http.heartbeat(id).seal.isEmpty(), "sealed once")
        assertTrue(http.list(id).partners.single().devices.single().ready)

        // Only that device's private key opens what the server hands out.
        val got = json.decodeFromString(KeyResponse.serializer(), http.get("/p/${j.token}/key?d=${j.deviceId}").bodyAsText())
        val blob = Base64.getUrlDecoder().decode(got.sealed)
        assertEquals(reportKey, String(Ecies.decrypt(blob, k.private, Ecies.INFO_KEY)))
        assertTrue(runCatching { Ecies.decrypt(blob, newKeys().private, Ecies.INFO_KEY) }.isFailure)
        assertTrue(runCatching { Ecies.decrypt(blob, k.private) }.isFailure, "a sealed key can't pass as a screenshot")
        assertEquals(HttpStatusCode.NotFound, http.get("/p/${j.token}/key?d=nope").status)
        // The server never held the report key.
        assertTrue(store.dir.walkTopDown().filter { it.isFile }.none { it.readText().contains(reportKey) })
    }

    @Test
    fun aPartnersPhoneEnrollsWithItsPartnerTokenOnceWithoutACode() =
        run(Config(publicUrl = "https://ff.test", enrollToken = "boot-code", now = { clock })) { http ->
            suspend fun enrollWith(header: String, value: String, k: KeyPair = newKeys()) = http.post("/v1/enroll") {
                contentType(ContentType.Application.Json); header(P, "2"); header(header, value)
                setBody("""{"publicKeySpki":"${spki(k)}"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/enroll", """{"publicKeySpki":"${spki(newKeys())}"}""").status)
            val r = enrollWith("X-FF-Enroll", "boot-code", keys)
            val owner = json.decodeFromString(EnrollResponse.serializer(), r.bodyAsText()).deviceId
            val sam = http.joined(code = http.invite(owner).code)
            assertEquals(HttpStatusCode.Unauthorized, enrollWith("X-FF-Partner", "x".repeat(30)).status)
            val ok = enrollWith("X-FF-Partner", sam.token)
            assertEquals(HttpStatusCode.OK, ok.status)
            val samPhone = json.decodeFromString(EnrollResponse.serializer(), ok.bodyAsText()).deviceId
            assertEquals(samPhone, store.device(owner)!!.partners.single().enrolledDeviceId)
            // One phone per partner: the token can't mint more.
            assertEquals(HttpStatusCode.Unauthorized, enrollWith("X-FF-Partner", sam.token).status)
        }

    @Test
    fun anEmailInviteProvesTheAddressAndSendsNothingElseUntilUsed() = run { http ->
        val id = http.enroll()
        val inv = http.invite(id, "Jo@Example.com")
        assertTrue(inv.emailed)
        val mail = notifier.emails.single()
        assertEquals("jo@example.com", mail.first)
        assertTrue(mail.second.contains("Logan wants you as an accountability partner"))
        assertTrue(!mail.third.contains(reportKey) && !mail.third.contains(inv.code.replace("-", "")), "a link, nothing else in it")
        val secret = emailedSecret()
        assertTrue(http.get("/j/$secret").bodyAsText().contains("data-link='$secret'"))
        assertEquals(HttpStatusCode.NotFound, http.get("/j/" + "x".repeat(32)).status)

        // Nothing is sent to an address that hasn't used its link, whatever happens on the phone.
        http.batch(id, 1, 1, "GENESIS", """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}""")
        assertEquals(1, notifier.emails.size)

        val j = http.joined(link = secret, name = "Jo", kind = "web")
        http.sealAll(id)
        val p = store.device(id)!!.partners.single()
        assertEquals("jo@example.com", p.email)
        assertEquals("email", http.list(id).partners.single().alertsBy)
        assertTrue(http.get("/p/${j.token}").bodyAsText().contains("By email to jo…@example.com"))

        // The same invite's code, used instead of the link, proves nothing: no address, so alerts go to the app.
        val inv2 = http.invite(id, "kim@example.com")
        http.joined(code = inv2.code, name = "Kim")
        assertEquals("", store.device(id)!!.partners.last().email)
        // One invite email per address per hour: a second invite is made, but not emailed.
        assertFalse(http.invite(id, "kim@example.com").emailed)
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/v1/invites", signed("invite", id, "nope", more = ""","email":"nope"""")).status)
    }

    @Test
    fun withoutEmailOnlyCodesAreOffered() = run { http ->
        notifier.noEmail = true
        val id = http.enroll()
        assertEquals(HttpStatusCode.Conflict, http.postJson("/v1/invites", signed("invite", id, "jo@example.com", more = ""","email":"jo@example.com"""")).status)
        assertTrue(http.invite(id).code.isNotEmpty())
    }

    @Test
    fun invitesExpireCanBeCancelledAndPartnersAreCapped() = run(Config(publicUrl = "https://ff.test", maxPartnersPerDevice = 2, now = { clock })) { http ->
        val id = http.enroll()
        val old = http.invite(id)
        clock += cfg.inviteTtlMs + 1
        assertEquals(HttpStatusCode.NotFound, http.join(code = old.code).status)
        assertEquals(HttpStatusCode.NotFound, http.get("/c/${old.code.replace("-", "")}").status)
        val c = http.invite(id)
        assertEquals(listOf(c.id), http.list(id).invites.map { it.id })
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/invites/cancel", signed("invite_cancel", id, c.id, more = ""","inviteId":"${c.id}"""")).status)
        assertEquals(HttpStatusCode.NotFound, http.join(code = c.code).status)
        http.joined(code = http.invite(id).code)
        val last = http.invite(id)
        http.joined(code = last.code, name = "Kim")
        assertEquals(HttpStatusCode.Conflict, http.postJson("/v1/invites", signed("invite", id, more = ""","email":""""")).status)
        assertEquals(HttpStatusCode.BadRequest, http.join(code = "ABC").status)
    }

    @Test
    fun aPartnerAddsADeviceAndRemovingOneAsksForANewKey() = run { http ->
        val id = http.enroll()
        val jo = http.emailPartner(id)
        notifier.emails.clear()
        val laptop = newKeys()
        val add = http.postJson("/p/${jo.token}/devices", """{"kind":"web","label":"Firefox on Linux","publicKeySpki":"${spki(laptop)}"}""", level = null)
        assertEquals(HttpStatusCode.OK, add.status)
        val added = json.decodeFromString(AddDeviceResponse.serializer(), add.bodyAsText()).deviceId
        // Never silent: the owner's phone is asked to seal it (and says who added what), and Jo is emailed.
        val t = http.heartbeat(id).seal.single()
        assertEquals(Triple(added, false, "Firefox on Linux"), Triple(t.deviceId, t.firstDevice, t.label))
        assertTrue(notifier.emails.single().third.contains("Firefox on Linux"))
        http.seal(id, t)
        assertEquals(2, http.list(id).partners.single().devices.count { it.ready })
        repeat(2) { http.postJson("/p/${jo.token}/devices", """{"kind":"app","publicKeySpki":"${spki(newKeys())}"}""", level = null) }
        assertEquals(HttpStatusCode.Conflict, http.postJson("/p/${jo.token}/devices", """{"kind":"app","publicKeySpki":"${spki(newKeys())}"}""", level = null).status, "four at most")
        assertEquals(HttpStatusCode.BadRequest, http.postJson("/p/${jo.token}/devices", """{"kind":"web","publicKeySpki":"AAAA"}""", level = null).status)
        http.sealAll(id)

        // Jo removes the laptop: the owner hears it, and the phone is asked for a new key.
        assertEquals("/p/${jo.token}?device=removed", http.post("/p/${jo.token}/devices/$added/remove").headers["Location"])
        val hb = http.heartbeat(id)
        assertTrue(hb.rotateKey)
        assertEquals("device" to "Jo removed a device (Firefox on Linux).", hb.notices.single().let { it.kind to it.text })
        // The new key: every remaining device needs it, and the request to rotate is answered.
        val next = http.heartbeat(id, key = "k2")
        assertFalse(next.rotateKey)
        assertEquals(3, next.seal.size)
        assertTrue(next.seal.none { it.firstDevice }, "Jo still reads with the old key until then")
        next.seal.forEach { assertEquals(HttpStatusCode.OK, http.seal(id, it, key = "k2").status) }
        assertTrue(http.heartbeat(id, key = "k2").seal.isEmpty())

        // The last device can't be removed: that's leaving.
        store.updateDevice(id) { d -> d.partners.single().let { it.devices = it.devices.take(1) } }
        assertEquals("/p/${jo.token}?device=last", http.post("/p/${jo.token}/devices/${store.device(id)!!.partners.single().devices.single().id}/remove").headers["Location"])
    }

    @Test
    fun theOwnerRemovingAPartnerTellsThemAndTheirLinkStops() = run { http ->
        val id = http.enroll()
        val jo = http.emailPartner(id)
        notifier.emails.clear()
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/partners/unlink", signed("unlink", id, jo.partnerId, more = ""","partnerId":"${jo.partnerId}"""")).status)
        assertTrue(notifier.emails.single().second.contains("you were removed"))
        assertEquals(HttpStatusCode.NotFound, http.get("/p/${jo.token}").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/p/${jo.token}/alerts").status, "their app sees the link has gone")
        assertTrue(http.heartbeat(id).rotateKey)
    }

    @Test
    fun aPartnerLeavingTellsTheOwnerAndTheOthers() = run { http ->
        val id = http.enroll()
        val jo = http.emailPartner(id)
        val sam = http.appPartner(id)
        notifier.emails.clear()
        assertTrue(http.heartbeat(id).notices.isEmpty())

        clock += 60_000
        assertEquals(HttpStatusCode.OK, http.post("/p/${sam.token}/leave").status)
        assertEquals(listOf("Jo"), store.device(id)!!.partners.map { it.name })
        val hb = http.heartbeat(id)
        val n = hb.notices.single()
        assertEquals("left" to "Sam stopped being your accountability partner.", n.kind to n.text)
        assertTrue(hb.rotateKey, "so Sam can't read on")
        assertTrue(http.heartbeat(id, after = n.t).notices.isEmpty(), "a notice comes back once the phone has it")
        assertEquals(1, notifier.emails.count { it.first == "jo@example.com" && it.second.contains("a partner left") })

        // The last partner leaving: the owner is still told; there is nobody else to tell.
        notifier.emails.clear()
        clock += 60_000
        assertEquals(HttpStatusCode.OK, http.post("/p/${jo.token}/leave").status)
        assertEquals(2, http.heartbeat(id).notices.size)
        assertTrue(notifier.emails.isEmpty())
    }

    @Test
    fun alertsGoByEmailOnlyWhileNoAppOfTheirsReadsTheFeed() = run { http ->
        val id = http.enroll()
        // Jo opened the emailed link on a phone with the app: an app device, and a proven address.
        http.invite(id, "jo@example.com")
        val jo = http.joined(link = emailedSecret(), name = "Jo", kind = "app")
        http.sealAll(id)
        notifier.emails.clear()
        suspend fun alert(line: String) {
            clock += 2 * 60_000 // past the per-address gap
            val lines = listOf(line)
            http.postJson("/v1/alerts", json.encodeToString(AlertsRequest.serializer(), AlertsRequest(id, clock, lines, sign(Integrity.requestMessage("alerts", id, clock, Integrity.sha256(line))))))
        }
        alert("Filter turned off")
        assertEquals(1, notifier.emails.size, "their app hasn't read the feed yet: email")
        assertEquals("email", http.list(id).partners.single().alertsBy)
        http.get("/p/${jo.token}/alerts?d=${jo.deviceId}")
        assertEquals("app", http.list(id).partners.single().alertsBy)
        alert("Filter turned off again")
        assertEquals(1, notifier.emails.size, "one path: the app has it")
        assertEquals(2, json.decodeFromString(AlertFeed.serializer(), http.get("/p/${jo.token}/alerts").bodyAsText()).alerts.size)
        clock += Store.APP_QUIET_MS
        store.updateDevice(id) { it.lastSeenMs = clock }
        alert("Filter turned off a third time")
        assertEquals(2, notifier.emails.size, "an app that went quiet hands alerts back to email")
    }

    @Test
    fun joinPagesOfferTheAppAndEscapeWhatTheyShow() = run { http ->
        val id = http.enroll()
        val inv = http.invite(id, from = "Lo'gan <b>")
        val page = http.get("/c/${inv.code.replace("-", "")}")
        val body = page.bodyAsText()
        assertTrue(body.contains("data-package='app.focusfence.accountable'"))
        assertTrue(body.contains("Lo&#39;gan &lt;b&gt;") && !body.contains("<b>'s"), body)
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertTrue(body.contains("you see the report but get no alerts"), "a browser by code gets no alerts, and is told")
        assertTrue(http.get("/c/ZZZZZZZZ").bodyAsText().contains("This invite isn't valid"))
    }

    // ---- partners' pages and alerts ---------------------------------------------------------------------

    @Test
    fun partnerPageNeedsToken() = run { http ->
        val id = http.enroll()
        val sam = http.appPartner(id)
        val page = http.get("/p/${sam.token}").bodyAsText()
        assertTrue(page.contains("Logan&#39;s report"))
        assertTrue(page.contains("data-page=\"report\"") && page.contains("/static/report.js"))
        assertTrue(page.contains("cannot read it"))
        assertTrue(page.contains("Pixel 8") && page.contains("As notifications in the Focus Fence app."))
        assertEquals(HttpStatusCode.NotFound, http.get("/p/not-a-real-token-at-all-xx").status)
    }

    @Test
    fun anyOnePartnerAnswersAnAskAndTheFirstAnswerCounts() = run { http ->
        val id = http.enroll()
        suspend fun ask(key: String): HttpResponse {
            clock += 1_000
            return http.postJson("/v1/unlock/ask", signed("unlock", id, key, more = ""","kind":"$key""""))
        }
        suspend fun status(): UnlockStatus? = http.heartbeat(id).unlock
        val bundle = app.focusfence.dns.GuardPolicy.Action.LOOSEN_BUNDLE.key
        assertEquals(HttpStatusCode.Conflict, ask(bundle).status, "nobody to ask yet")
        http.joined(code = http.invite(id).code, name = "Kim")
        assertEquals(HttpStatusCode.Conflict, ask(bundle).status, "joined but without the key yet: can't answer")
        val jo = http.emailPartner(id)
        val sam = http.appPartner(id)
        notifier.emails.clear()
        assertEquals(HttpStatusCode.BadRequest, ask("guard_nonsense").status)
        clock += 1_000
        val forged = http.postJson("/v1/unlock/ask", signed("unlock", id, "guard_vpn_off", more = ""","kind":"$bundle""""))
        assertEquals(HttpStatusCode.Unauthorized, forged.status, "signed for another action")

        val first = json.decodeFromString(UnlockStatus.serializer(), ask(bundle).bodyAsText())
        assertEquals("pending", first.state)
        assertEquals(1, notifier.emails.count { it.second.contains("your OK is asked") && it.third.contains("loosen a filter bundle") })
        assertEquals(first.id, json.decodeFromString(UnlockStatus.serializer(), ask(bundle).bodyAsText()).id)
        assertEquals(1, notifier.emails.count { it.second.contains("your OK is asked") }, "asking again tells nobody twice")
        assertTrue(http.get("/p/${jo.token}").bodyAsText().contains("Logan asks for your OK to loosen a filter bundle."))
        assertEquals("pending", status()!!.state)

        val ok = http.submitForm("/p/${sam.token}/unlock/${first.id}", Parameters.build { append("d", "approve") })
        assertEquals("/p/${sam.token}?unlock=approved", ok.headers["Location"])
        val late = http.submitForm("/p/${jo.token}/unlock/${first.id}", Parameters.build { append("d", "deny") })
        assertEquals("/p/${jo.token}?unlock=late", late.headers["Location"])
        val approved = status()!!
        assertEquals("approved" to "Sam", approved.state to approved.by)
        assertEquals("Sam approved: loosen a filter bundle, for the next 15 minutes.", store.device(id)!!.alerts.last().text)

        val third = json.decodeFromString(UnlockStatus.serializer(), ask(app.focusfence.dns.GuardPolicy.Action.TURN_OFF_VPN.key).bodyAsText())
        clock += app.focusfence.dns.UnlockAsk.LAPSE_MS + 1
        assertEquals("lapsed", status()!!.state)
        assertEquals("/p/${sam.token}?unlock=late", http.submitForm("/p/${sam.token}/unlock/${third.id}", Parameters.build { append("d", "approve") }).headers["Location"])
        clock += app.focusfence.dns.UnlockAsk.GRANT_MS
        assertNull(status(), "old requests stop riding on the heartbeat")
    }

    @Test
    fun theOwnerSeesWhenEachPartnerLastOpenedTheReport() = run { http ->
        val id = http.enroll()
        val jo = http.emailPartner(id)
        assertEquals(0L, http.list(id).partners.single().lastViewMs)
        // The page itself (what a mail scanner fetches) and alert checks aren't a look; the report data is.
        http.get("/p/${jo.token}")
        http.get("/p/${jo.token}/alerts")
        assertEquals(0L, http.list(id).partners.single().lastViewMs)
        http.get("/p/${jo.token}/data")
        val first = clock
        assertEquals(first, http.list(id).partners.single().lastViewMs)
        clock += 60_000
        http.get("/p/${jo.token}/data")
        assertEquals(first, http.list(id).partners.single().lastViewMs, "reloading within five minutes doesn't rewrite it")
        clock += Store.VIEW_EVERY_MS
        http.get("/p/${jo.token}/data")
        assertTrue(http.list(id).partners.single().lastViewMs > first)
        assertTrue(http.get("/p/${jo.token}").bodyAsText().contains("can see when you last opened this report"))
    }

    @Test
    fun aPartnersQuietHoursAndDailyMessageFollowTheirOwnClock() = run { http ->
        val id = http.enroll()
        val token = http.emailPartner(id).token
        val email = "jo@example.com"
        val alertsSvc = Alerts(store, notifier, cfg)
        val h = 3_600_000L
        suspend fun sweep() { store.updateDevice(id) { it.lastSeenMs = clock }; alertsSvc.sweep() }
        suspend fun toPartners(lines: List<String>, minor: Set<Int>) = alertsSvc.toPartners(store.device(id)!!, "protection changed", lines, minor)
        // Line the clock up with 2027-01-15 08:00 UTC, 01:00 in Denver: inside their quiet hours.
        clock = 1_800_000_000_000L
        store.updateDevice(id) { it.lastSeenMs = clock }
        assertEquals(HttpStatusCode.Found, http.submitForm("/p/$token/prefs", Parameters.build {
            append("level", "daily"); append("quiet", "on"); append("tz", "America/Denver")
        }).status)
        assertEquals("daily" to "America/Denver", store.device(id)!!.partners.single().let { it.alertLevel to it.zone })
        notifier.emails.clear()
        fun mails() = notifier.emails.filter { it.first == email }

        toPartners(listOf("Filter turned off", "A partner left"), minor = setOf(1))
        assertTrue(mails().isEmpty(), "nothing at night")
        assertEquals(2, store.device(id)!!.partners.single().held.size)
        val feed = json.decodeFromString(AlertFeed.serializer(), http.get("/p/$token/alerts").bodyAsText())
        assertEquals(listOf(true, false), feed.alerts.map { it.important })
        assertEquals("daily" to true, feed.level to feed.quiet)

        clock += 5 * h // 06:00 Denver: still quiet
        sweep()
        assertTrue(mails().isEmpty())
        clock += h // 07:00 Denver: one message with the night's alerts
        sweep()
        assertEquals(1, mails().size)
        assertTrue(mails().single().second.contains("2 alerts held for you"))
        sweep()
        assertEquals(1, mails().size, "once")

        clock += 2 * h // 09:00: important at once, housekeeping waits for tomorrow's message
        toPartners(listOf("Strict mode turned off", "A partner was removed"), minor = setOf(1))
        assertEquals(2, mails().size)
        assertTrue(mails().last().third.contains("Strict mode turned off") && !mails().last().third.contains("A partner was removed"))
        clock += 22 * h // 07:00 next day
        sweep()
        assertEquals(3, mails().size)
        assertTrue(mails().last().third.contains("A partner was removed"))

        // Their own hours: 11 pm to 6 am.
        http.submitForm("/p/$token/prefs", Parameters.build { append("level", "all"); append("quiet", "on"); append("from", "23"); append("to", "6") })
        assertEquals(23 to 6, store.device(id)!!.partners.single().let { it.quietFrom to it.quietTo })
        assertTrue(http.get("/p/$token").bodyAsText().contains("<option value='23' selected>11 pm</option>"))
    }

    @Test
    fun staleDeviceAlertsOnceAndWeeklyReportSends() = run { http ->
        val id = http.enroll()
        http.emailPartner(id)
        notifier.emails.clear()
        val alerts = Alerts(store, notifier, cfg)
        clock += cfg.staleMs + 60_000
        alerts.sweep()
        alerts.sweep()
        assertEquals(1, notifier.emails.count { it.second.contains("silent") })
        clock += cfg.reportEveryMs
        store.updateDevice(id) { it.lastSeenMs = clock }
        alerts.sweep()
        val weekly = notifier.emails.single { it.second.contains("weekly report") }.third
        assertTrue(weekly.contains("end-to-end encrypted") && !weekly.contains("lookups"))
    }

    @Test
    fun batchesChainAlertAndReportGaps() = run { http ->
        val id = http.enroll()
        http.emailPartner(id)
        notifier.emails.clear()
        val raw1 = """{"seq":1,"t":$clock,"type":"dns","host":"a.com","qtype":"A","decision":"allowed"}"""
        val r1 = http.batch(id, 1, 1, "GENESIS", raw1)
        assertEquals(HttpStatusCode.OK, r1.status)
        val next = json.decodeFromString(BatchResponse.serializer(), r1.bodyAsText()).nextHash
        assertTrue(notifier.emails.isEmpty(), "plain DNS records don't alert")
        val raw2 = """{"seq":5,"t":$clock,"type":"integ","key":"a11y_enabled","value":"false","was":"true"}"""
        assertEquals(HttpStatusCode.OK, http.batch(id, 5, 5, next, raw2).status)
        val alert = notifier.emails.single()
        assertTrue(alert.third.contains("a11y_enabled changed") && alert.third.contains("3 activity records never arrived"), alert.third)
        val replay = http.batch(id, 5, 5, next, raw2)
        assertEquals(HttpStatusCode.Conflict, replay.status)
        val cursor = json.decodeFromString(CursorResponse.serializer(), replay.bodyAsText())
        assertEquals(6, cursor.expectedNextSeq)
        val forged = http.postJson("/v1/batches", json.encodeToString(BatchRequest.serializer(), BatchRequest(id, 6, 6, cursor.lastHash, raw1, sign("other".toByteArray()))))
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun staleOrForgedSignedRequestsRejected() = run { http ->
        val id = http.enroll()
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/invites", signed("invite", id, t = clock - cfg.clockSkewMs - 1)).status)
        val sig = sign(Integrity.requestMessage("partners", id, clock))
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/invites", """{"deviceId":"$id","t":$clock,"signature":"$sig"}""").status)
        // Signed for one address, sent for another.
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/invites", signed("invite", id, "a@example.com", more = ""","email":"b@example.com"""")).status)
    }

    @Test
    fun anAddressIsNotEmailedFasterThanTheGap() = run { http ->
        val id = http.enroll()
        http.emailPartner(id)
        notifier.emails.clear()
        val a = """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}"""
        val h = json.decodeFromString(BatchResponse.serializer(), http.batch(id, 1, 1, "GENESIS", a).bodyAsText()).nextHash
        http.batch(id, 2, 2, h, a.replace("\"seq\":1", "\"seq\":2"))
        assertEquals(1, notifier.emails.size, "second alert inside the gap is held back")
    }

    @Test
    fun emailHasWholeServerDailyCaps() = run(Config(publicUrl = "https://ff.test", inviteEmailDailyCap = 2, emailGlobalDailyCap = 1, maxPartnersPerDevice = 10, now = { clock })) { http ->
        val id = http.enroll()
        repeat(4) { assertEquals(it < 2, http.invite(id, "p$it@example.com").emailed) }
        assertEquals(2, notifier.emails.size)
        val secrets = notifier.emails.map { Regex("/j/([A-Za-z0-9_-]+)").find(it.third)!!.groupValues[1] }
        secrets.forEach { http.joined(link = it, kind = "web") }
        http.sealAll(id)
        notifier.emails.clear()
        http.batch(id, 1, 1, "GENESIS", """{"seq":1,"t":$clock,"type":"svc","comp":"vpn","state":"revoked"}""")
        assertEquals(1, notifier.emails.size, "two partners, but the server-wide cap lets one email out")
    }

    @Test
    fun sealedReportsAreStoredAsCiphertextAndOpenOnlyWithTheReportKey() = run { http ->
        val id = http.enroll()
        val token = http.appPartner(id).token
        val plain = """{"kind":"digest","from":1,"to":2,"lookups":42,"sites":"secret-site.example\t3"}"""
        val blob = ReportSeal.seal(reportKey, plain.toByteArray(), id)
        assertEquals(HttpStatusCode.OK, http.postReport(id, blob).status)
        val onDisk = store.dir.walkTopDown().filter { it.isFile }.joinToString("") { it.readText() }
        assertTrue(!onDisk.contains("secret-site") && onDisk.contains(blob))
        val data = json.decodeFromString(ReportData.serializer(), http.get("/p/$token/data").bodyAsText())
        assertEquals(id to "Logan", data.deviceId to data.ownerName)
        assertEquals(plain, String(ReportSeal.open(reportKey, data.reports.single().blob, data.deviceId)!!))
        assertNull(ReportSeal.open(ReportSeal.newKey(), data.reports.single().blob, data.deviceId))
        assertEquals(HttpStatusCode.NotFound, http.get("/p/not-a-real-token-at-all-xx/data").status)
    }

    @Test
    fun alertLinesAreEmailedButBatchesAreNeverStored() = run { http ->
        val id = http.enroll()
        http.emailPartner(id)
        notifier.emails.clear()
        val lines = listOf("Filter turned off")
        val sig = sign(Integrity.requestMessage("alerts", id, clock, Integrity.sha256(lines.joinToString("\n"))))
        assertEquals(HttpStatusCode.OK, http.postJson("/v1/alerts", json.encodeToString(AlertsRequest.serializer(), AlertsRequest(id, clock, lines, sig))).status)
        assertTrue(notifier.emails.single().third.contains("Filter turned off"))
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/alerts", json.encodeToString(AlertsRequest.serializer(), AlertsRequest(id, clock, listOf("forged"), sig))).status)
        http.batch(id, 1, 1, "GENESIS", """{"seq":1,"t":$clock,"type":"dns","host":"secret-site.example","decision":"allowed"}""")
        assertTrue(store.dir.walkTopDown().none { it.name.contains(".activity.jsonl") })
        assertTrue(store.dir.walkTopDown().filter { it.isFile }.none { it.readText().contains("secret-site") })
    }

    @Test
    fun scriptsAreAllowedOnlyOnThePartnerPages() = run { http ->
        for (p in listOf("/p/x", "/j/x", "/c/x")) assertTrue(http.get(p).headers["Content-Security-Policy"]!!.contains("script-src 'self'"), p)
        assertTrue(!http.get("/").headers["Content-Security-Policy"]!!.contains("script-src"))
        val js = http.get("/static/report.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue(!js.bodyAsText().contains("innerHTML ="), "report text is data: built with textContent only")
    }

    // ---- unchanged by plan 13 ---------------------------------------------------------------------------

    @Test
    fun rejectsNonP256Key() = run { http ->
        val r = http.postJson("/v1/enroll", """{"publicKeySpki":"AAAA"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
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
    fun theServerNoLongerServesTheAboutPages() = run { http ->
        // They live on GitHub Pages (2026-11-24); the server has no /about at all.
        assertEquals(HttpStatusCode.NotFound, http.get("/about/privacy.html").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/about").status)
    }

    @Test
    fun reportUploadsAreSignedAndBounded() = run { http ->
        val id = http.enroll()
        val forged = json.encodeToString(ReportRequest.serializer(), ReportRequest(id, clock, 1, 2, "abc", sign("nope".toByteArray())))
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/reports", forged).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, http.postReport(id, "x".repeat(Store.REPORT_MAX_CHARS + 1)).status)
    }

    @Test
    fun legacyReadableRecordsArePurged() {
        val f = java.io.File(store.dir, "devices/abc.activity.jsonl").apply { writeText("{}\n") }
        java.io.File(store.dir, "devices/abc.activity.jsonl.1").writeText("{}\n")
        assertEquals(2, store.purgeLegacyRecords())
        assertTrue(!f.exists())
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
    fun enrollNeedsTheEnrollmentCodeWhenOneIsSet() = run(Config(publicUrl = "https://ff.test", enrollToken = "s3cret", now = { clock })) { http ->
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertEquals(HttpStatusCode.Unauthorized, http.postJson("/v1/enroll", """{"publicKeySpki":"$spki"}""").status)
        val wrong = http.post("/v1/enroll") { header(P, "2"); header("X-FF-Enroll", "nope"); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$spki"}""") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val right = http.post("/v1/enroll") { header(P, "2"); header("X-FF-Enroll", "s3cret"); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$spki"}""") }
        assertEquals(HttpStatusCode.OK, right.status)
    }

    @Test
    fun enrollAcceptsAPersonCodeAndTheBootstrapTokenAndNeverBurnsACodeOnABadKey() = run(Config(publicUrl = "https://ff.test", enrollToken = "boot", now = { clock })) { http ->
        val spki = Base64.getEncoder().encodeToString(keys.public.encoded)
        suspend fun enroll(code: String, key: String = spki) = http.post("/v1/enroll") { header(P, "2"); header("X-FF-Enroll", code); contentType(ContentType.Application.Json); setBody("""{"publicKeySpki":"$key"}""") }.status
        val (plain, _) = EnrollCodes(java.io.File(store.dir, "codes.json")).create("friend", clock, maxUses = 1)
        assertEquals(HttpStatusCode.BadRequest, enroll(plain, key = "garbage"), "bad key is rejected")
        assertEquals(HttpStatusCode.OK, enroll(plain), "the code was not burned by the bad key")
        assertEquals(HttpStatusCode.Unauthorized, enroll(plain), "now used up")
        assertEquals(HttpStatusCode.OK, enroll("boot"), "bootstrap token still works")
        assertEquals(HttpStatusCode.Unauthorized, enroll("zzzz"))
    }
}
