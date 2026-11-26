package app.focusfence.server

import app.focusfence.dns.Digest
import app.focusfence.dns.Ecies
import app.focusfence.dns.InviteLink
import app.focusfence.dns.LogJson
import app.focusfence.dns.ReportSeal
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan 15 item 3: the partner flow end to end, the way the app drives it. Each [Phone] does what `SyncWorker` and
 * `Accountability` do at a sync (literal paths, JSON field names and signature messages, as in CompatLevel2Test), with
 * the app's own pure code for everything that must match across the two halves: [ReportSeal] for the report, [Ecies]
 * with `INFO_KEY` for the sealed report key, [InviteLink] for reading the QR link, [Digest] and [LogJson] for the report
 * body. Only the Android keystore is swapped for a plain key pair (`PartnerKey` wraps exactly such a key).
 *
 * The assertion that matters is always the same: the reader's copy of the key opens the owner's latest report.
 */
class PartnerFlowTest {
    private var clock = 1_800_000_000_000L
    private val store = Store(Files.createTempDirectory("ff-flow").toFile())
    private val cfg = Config(publicUrl = "https://ff.test", enrollToken = "boot", now = { clock })

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun spki(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)
    private fun sign(k: KeyPair, msg: String) = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(msg.toByteArray()); sign() })
    /** `SyncWorker.sha256`: base64url, no padding. */
    private fun sha(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.bool(k: String) = get(k)?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.arr(k: String) = (get(k) as? JsonArray).orEmpty().map { it.jsonObject }

    /** One report this phone reads (`SyncStore.Seen`): its own key pair, and the report key once it has opened it. */
    private class Seen(val owner: String, val token: String, val deviceId: String, val keys: KeyPair) {
        var reportKey = ""
        var keyId = ""
    }

    /** A phone running the app. */
    private inner class Phone(val http: HttpClient, val name: String, val keys: KeyPair, val id: String) {
        var reportKey = ReportSeal.newKey()
        val seen = mutableListOf<Seen>()
        var lastUpload = ""

        fun signed(action: String, extra: String): String {
            clock += 1_000
            return """"deviceId":"$id","t":$clock,"signature":"${sign(keys, "$action|$id|$clock|$extra")}""""
        }

        suspend fun post(path: String, body: String): HttpResponse = http.json(path, body)

        /** `SyncWorker.doWork` in order: heartbeat (rotate, or seal; then group pairs), report, then each report it reads. */
        suspend fun sync() {
            val keyId = ReportSeal.keyId(reportKey)
            val hb = post("/v1/heartbeat", """{${signed("heartbeat", "0")},"lastSeq":0,"noticesAfter":0,"keyId":"$keyId"}""")
            assertEquals(HttpStatusCode.OK, hb.status, hb.bodyAsText())
            val b = obj(hb.bodyAsText())
            if (b.bool("rotateKey")) reportKey = ReportSeal.newKey()
            else for (t in b.arr("seal")) {
                val pub = Ecies.publicKeyFromSpki(Base64.getDecoder().decode(t.str("publicKeySpki")))
                val sealed = Base64.getUrlEncoder().withoutPadding().encodeToString(Ecies.encrypt(reportKey.toByteArray(), pub, Ecies.INFO_KEY))
                val pdev = t.str("deviceId")
                val r = post("/v1/seal", """{${signed("seal", "$pdev|$keyId|${sha(sealed)}")},"partnerDeviceId":"$pdev","keyId":"$keyId","sealed":"$sealed"}""")
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            }
            for (g in b.arr("groupPairs")) {
                val k = keyPair()
                val owner = g.str("ownerDeviceId"); val pid = g.str("partnerId")
                val r = post("/v1/groups/key", """{${signed("group_key", "$owner|$pid|${sha(spki(k))}")},"ownerDeviceId":"$owner","partnerId":"$pid","publicKeySpki":"${spki(k)}","label":"Pixel"}""")
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                seen += Seen(g.str("name"), g.str("token"), obj(r.bodyAsText()).str("deviceId"), k)
            }
            uploadReport()
            for (s in seen.toList()) readKey(s)
        }

        /** `SyncWorker.serverReport`: a [Digest] encoded with [LogJson], sealed with this phone's report key. */
        suspend fun uploadReport() {
            val from = clock - 3_600_000L
            lastUpload = LogJson.encode(Digest(from).build(clock + 1_000))
            val blob = ReportSeal.seal(reportKey, lastUpload.toByteArray(), id)
            clock += 1_000
            val t = clock
            val body = """{"deviceId":"$id","t":$t,"from":$from,"to":$t,"blob":"$blob","signature":"${sign(keys, "report|$id|$t|$from|$t|${sha(blob)}")}"}"""
            assertEquals(HttpStatusCode.OK, post("/v1/reports", body).status)
        }

        /** `SyncWorker.partnerSide`: fetch this device's sealed key and open it when it's a new one. */
        suspend fun readKey(s: Seen) {
            val r = http.get("/p/${s.token}/key?d=${s.deviceId}")
            if (r.status == HttpStatusCode.NotFound) { seen.remove(s); return }
            val k = obj(r.bodyAsText())
            if (k.bool("ready") && k.str("keyId") != s.keyId) {
                s.reportKey = String(Ecies.decrypt(Base64.getUrlDecoder().decode(k.str("sealed")), s.keys.private, Ecies.INFO_KEY))
                s.keyId = k.str("keyId")
            }
        }

        fun seenOf(owner: Phone) = seen.firstOrNull { it.owner == owner.name }
    }

    private suspend fun HttpClient.json(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); header("X-FF-Protocol", "2"); headers.forEach { (k, v) -> header(k, v) }; setBody(body) }

    /** `Accountability.ensureEnrolled`: a new device key, enrolled with whichever credential the phone has. */
    private suspend fun HttpClient.phone(name: String, enrollHeaders: Map<String, String>): Phone {
        val k = keyPair()
        val r = json("/v1/enroll", """{"publicKeySpki":"${spki(k)}"}""", enrollHeaders)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Phone(this, name, k, obj(r.bodyAsText()).str("deviceId"))
    }

    private fun flow(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store, FakeNotifier(), cfg) }
        block(createClient { followRedirects = false })
    }

    /** What the reader's app shows: the owner's latest report, opened with the reader's copy of the key; null if it can't. */
    private suspend fun opens(reader: Phone, owner: Phone): Boolean {
        val s = reader.seenOf(owner) ?: return false
        if (s.reportKey.isEmpty()) return false
        val data = obj(reader.http.get("/p/${s.token}/data").bodyAsText())
        assertEquals(owner.id, data.str("deviceId"))
        val latest = data.getValue("reports").jsonArray.map { it.jsonObject }.maxByOrNull { it.str("t").toLong() } ?: return false
        val plain = ReportSeal.open(s.reportKey, latest.str("blob"), owner.id) ?: return false
        assertEquals(owner.lastUpload, String(plain))
        assertNotNull(LogJson.decode(String(plain)))
        return true
    }

    private suspend fun syncAll(vararg phones: Phone, rounds: Int = 2) = repeat(rounds) { phones.forEach { it.sync() } }

    @Test
    fun anInviteByQrGetsThePartnerTheReportAndRemovingOneLocksOnlyThemOut() = flow { http ->
        val logan = http.phone("Logan", mapOf("X-FF-Enroll" to "boot"))
        // Logan invites; the partner's phone scans the QR, which is the /c/ link.
        val inv = obj(logan.post("/v1/invites", """{${logan.signed("invite", "")},"email":"","from":"Logan"}""").bodyAsText())
        val parsed = InviteLink.parse(inv.str("qrUrl")) as InviteLink.Parsed.Join
        assertEquals("https://ff.test" to InviteLink.normalizeCode(inv.str("code")), parsed.server to parsed.code)

        // Sam's phone isn't on the server yet: it joins with its own key, then enrolls with the partner token (#123).
        val samReadKey = keyPair()
        val j = obj(http.json("/v1/join", """{"code":"${parsed.code}","link":"","name":"Sam","kind":"app","label":"Pixel 8","publicKeySpki":"${spki(samReadKey)}"}""").bodyAsText())
        val sam = http.phone("Sam", mapOf("X-FF-Partner" to j.str("token")))
        sam.seen += Seen(j.str("ownerName"), j.str("token"), j.str("deviceId"), samReadKey)
        assertEquals("Logan", j.str("ownerName"))
        // A second phone can't reuse Sam's token to enroll.
        assertEquals(HttpStatusCode.Unauthorized, http.json("/v1/enroll", """{"publicKeySpki":"${spki(keyPair())}"}""", mapOf("X-FF-Partner" to j.str("token"))).status)

        // Before Logan's phone checks in, Sam has nothing to open; after, the report opens.
        sam.sync()
        assertEquals(false, opens(sam, logan))
        syncAll(logan, sam)
        assertTrue(opens(sam, logan))

        // A second partner, Jo, by code. Both read.
        val code = obj(logan.post("/v1/invites", """{${logan.signed("invite", "")},"email":"","from":"Logan"}""").bodyAsText()).str("code")
        val joKey = keyPair()
        val jj = obj(http.json("/v1/join", """{"code":"${code.replace("-", "").lowercase()}","link":"","name":"Jo","kind":"app","label":"Phone","publicKeySpki":"${spki(joKey)}"}""").bodyAsText())
        val jo = http.phone("Jo", mapOf("X-FF-Partner" to jj.str("token")))
        jo.seen += Seen("Logan", jj.str("token"), jj.str("deviceId"), joKey)
        syncAll(logan, sam, jo)
        assertTrue(opens(sam, logan) && opens(jo, logan))

        // Logan removes Jo: a new report key, sealed again to Sam only. Sam still reads; Jo's link is gone, and Jo's old
        // copy of the key doesn't open the new report.
        val joOldKey = jo.seenOf(logan)!!.reportKey
        val list = obj(logan.post("/v1/partners/list", """{${logan.signed("partners", "")}}""").bodyAsText())
        val joId = list.arr("partners").first { it.str("name") == "Jo" }.str("id")
        assertEquals(HttpStatusCode.OK, logan.post("/v1/partners/unlink", """{${logan.signed("unlink", joId)},"partnerId":"$joId"}""").status)
        syncAll(logan, sam, jo, rounds = 3)
        assertTrue(opens(sam, logan))
        assertNull(jo.seenOf(logan))
        assertTrue(joOldKey != logan.reportKey)
        val latest = obj(sam.http.get("/p/${sam.seenOf(logan)!!.token}/data").bodyAsText()).getValue("reports").jsonArray.map { it.jsonObject }.maxBy { it.str("t").toLong() }
        assertNull(ReportSeal.open(joOldKey, latest.str("blob"), logan.id))
    }

    @Test
    fun aGroupOfThreeAllReadEachOtherAndSomeoneLeavingLocksOnlyThemOut() = flow { http ->
        val logan = http.phone("Logan", mapOf("X-FF-Enroll" to "boot"))
        val g = obj(logan.post("/v1/groups/create", """{${logan.signed("group_create", "Tuesday")},"name":"Tuesday","label":"Logan"}""").bodyAsText())
        val code = g.str("code")
        // The QR is the /c/ link; the app reads the code and server from it.
        val parsed = InviteLink.parse(g.str("qrUrl")) as InviteLink.Parsed.Join
        assertEquals(InviteLink.normalizeCode(code), parsed.code)

        // Sam and Jo aren't on the server: each enrolls with the group code, then joins (`Accountability.joinGroup`).
        suspend fun member(n: String): Phone {
            val p = http.phone(n, mapOf("X-FF-Group" to parsed.code))
            val c = InviteLink.normalizeCode(code)
            val r = p.post("/v1/groups/join", """{${p.signed("group_join", c)},"code":"$c","label":"$n"}""")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            return p
        }
        val sam = member("Sam"); val jo = member("Jo")
        val all = arrayOf(logan, sam, jo)

        // Round 1: each phone sends a key for each report it may read. Round 2: each owner seals to them; readers open.
        syncAll(*all)
        for (reader in all) for (owner in all) if (reader !== owner) assertTrue(opens(reader, owner), "${reader.name} reads ${owner.name}")

        // Jo leaves. Logan and Sam start new keys and still read each other; Jo reads no one, and no one keeps Jo's.
        val gid = g.str("groupId")
        val joOld = jo.seenOf(logan)!!.reportKey
        assertEquals(HttpStatusCode.OK, jo.post("/v1/groups/leave", """{${jo.signed("group_leave", gid)},"groupId":"$gid"}""").status)
        syncAll(*all, rounds = 3)
        assertTrue(opens(logan, sam) && opens(sam, logan))
        assertTrue(jo.seen.isEmpty())
        assertNull(logan.seenOf(jo)); assertNull(sam.seenOf(jo))
        val s = sam.seenOf(logan)!!
        val latest = obj(http.get("/p/${s.token}/data").bodyAsText()).getValue("reports").jsonArray.map { it.jsonObject }.maxBy { it.str("t").toLong() }
        assertNull(ReportSeal.open(joOld, latest.str("blob"), logan.id))
    }
}
