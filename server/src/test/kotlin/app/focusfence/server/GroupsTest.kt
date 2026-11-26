package app.focusfence.server

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan 14: groups are partner pairs made both ways; these check the pairs, not just the group record. */
class GroupsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private var clock = 1_800_000_000_000L
    private val store = Store(Files.createTempDirectory("ff-groups").toFile())
    private val cfg = Config(publicUrl = "https://ff.test", now = { clock })

    private class Phone(val keys: KeyPair, val id: String)

    private fun newKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun spki(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)
    private fun sign(k: KeyPair, msg: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(msg.toByteArray()); sign() })

    private fun run(config: Config = cfg, block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store, FakeNotifier(), config) }
        block(createClient { followRedirects = false })
    }

    private suspend fun HttpClient.postJson(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        header(app.focusfence.dns.Protocol.HEADER_APP, "2")
        headers.forEach { (k, v) -> header(k, v) }
        setBody(body)
    }

    private suspend fun HttpClient.phone(headers: Map<String, String> = emptyMap()): Phone {
        val k = newKeys()
        val r = postJson("/v1/enroll", """{"publicKeySpki":"${spki(k)}"}""", headers)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Phone(k, json.decodeFromString(EnrollResponse.serializer(), r.bodyAsText()).deviceId)
    }

    /** A signed group call: [extra] is what the server signs over for [action]. */
    private suspend fun HttpClient.group(path: String, p: Phone, action: String, extra: String, fields: JsonObject = JsonObject(emptyMap())): HttpResponse {
        val body = buildJsonObject {
            put("deviceId", p.id); put("t", clock); put("signature", sign(p.keys, "$action|${p.id}|$clock|$extra"))
            fields.forEach { (k, v) -> put(k, v) }
        }
        return postJson(path, body.toString())
    }

    private suspend fun HttpClient.create(p: Phone, name: String = "Tuesday group", me: String = "Logan"): GroupCodeResponse {
        val r = group("/v1/groups/create", p, "group_create", name, buildJsonObject { put("name", name); put("label", me) })
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json.decodeFromString(GroupCodeResponse.serializer(), r.bodyAsText())
    }

    private suspend fun HttpClient.join(p: Phone, code: String, me: String): HttpResponse =
        group("/v1/groups/join", p, "group_join", normalizeCode(code), buildJsonObject { put("code", code); put("label", me) })

    private suspend fun HttpClient.leave(p: Phone, gid: String) = group("/v1/groups/leave", p, "group_leave", gid, buildJsonObject { put("groupId", gid) })

    private suspend fun HttpClient.heartbeat(p: Phone, keyId: String = "k1"): HeartbeatResponse {
        val r = postJson("/v1/heartbeat", """{"deviceId":"${p.id}","t":$clock,"lastSeq":0,"signature":"${sign(p.keys, "heartbeat|${p.id}|$clock|0")}","keyId":"$keyId"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        return json.decodeFromString(HeartbeatResponse.serializer(), r.bodyAsText())
    }

    private fun partners(p: Phone) = store.device(p.id)!!.partners

    @Test
    fun joiningPairsEveryoneBothWaysAndKeysTravelAsForAnyPartner() = run { http ->
        val logan = http.phone(); val sam = http.phone(); val jo = http.phone()
        val g = http.create(logan)
        val look = http.postJson("/v1/codes/lookup", """{"code":"${g.code}"}""")
        assertEquals(LookupResponse("group", "Tuesday group", listOf("Logan")), json.decodeFromString(LookupResponse.serializer(), look.bodyAsText()))
        assertEquals(HttpStatusCode.OK, http.join(sam, g.code, "Sam").status)
        // Typed without the dash, in lower case: the same code.
        assertEquals(HttpStatusCode.OK, http.join(jo, g.code.replace("-", "").lowercase(), "Jo").status)
        // Three people: each phone has the other two as partners, made by the group, alerts "important" by default.
        for (p in listOf(logan, sam, jo)) {
            assertEquals(2, partners(p).size)
            assertTrue(partners(p).all { it.groupIds == listOf(g.groupId) && !it.chosen && it.alertLevel == "important" })
        }
        assertEquals(setOf("Sam", "Jo"), partners(logan).map { it.name }.toSet())
        assertTrue(store.device(logan.id)!!.ownerNotices.any { it.text == "Sam joined Tuesday group." && it.kind == "group_joined" })

        // Sam's phone hears it may read Logan's and Jo's reports, and sends a key for Logan's.
        val pairs = http.heartbeat(sam).groupPairs
        assertEquals(setOf("Logan", "Jo"), pairs.map { it.name }.toSet())
        val toLogan = pairs.first { it.name == "Logan" }
        val samKey = newKeys()
        val k = http.group("/v1/groups/key", sam, "group_key", "${toLogan.ownerDeviceId}|${toLogan.partnerId}|${Integrity.sha256(spki(samKey))}",
            buildJsonObject { put("ownerDeviceId", toLogan.ownerDeviceId); put("partnerId", toLogan.partnerId); put("publicKeySpki", spki(samKey)) })
        assertEquals(HttpStatusCode.OK, k.status, k.bodyAsText())
        assertEquals(listOf("Jo"), http.heartbeat(sam).groupPairs.map { it.name })
        // Jo can't add a key to the pair that is Sam's.
        val stolen = http.group("/v1/groups/key", jo, "group_key", "${toLogan.ownerDeviceId}|${toLogan.partnerId}|${Integrity.sha256(spki(samKey))}",
            buildJsonObject { put("ownerDeviceId", toLogan.ownerDeviceId); put("partnerId", toLogan.partnerId); put("publicKeySpki", spki(samKey)) })
        assertEquals(HttpStatusCode.NotFound, stolen.status)
        // Logan's phone is asked to seal to it, worded with the group.
        val seal = http.heartbeat(logan).seal.single()
        assertEquals("Sam", seal.name); assertEquals("Tuesday group", seal.groupName); assertTrue(seal.firstDevice)

        // A group pair isn't removed one-sidedly: leaving the group is how.
        val unlink = http.postJson("/v1/partners/unlink", """{"deviceId":"${logan.id}","t":$clock,"partnerId":"${seal.partnerId}","signature":"${sign(logan.keys, "unlink|${logan.id}|$clock|${seal.partnerId}")}"}""")
        assertEquals(HttpStatusCode.Conflict, unlink.status)

        // The list: Logan sees both, with the link each report is read at.
        val list = json.decodeFromString(GroupsResponse.serializer(), http.group("/v1/groups/list", sam, "groups", "").bodyAsText()).groups.single()
        assertEquals(listOf("Logan", "Sam", "Jo"), list.members.map { it.name })
        assertEquals(toLogan.token, list.members.first { it.name == "Logan" }.theirToken)

        // Jo leaves: Logan and Sam stop seeing Jo and Jo them, and both of theirs start new keys. Both are told.
        assertEquals(HttpStatusCode.OK, http.leave(jo, g.groupId).status)
        assertEquals(listOf("Sam"), partners(logan).map { it.name })
        assertEquals(emptyList(), partners(jo))
        assertTrue(store.device(logan.id)!!.rotateKey && store.device(sam.id)!!.rotateKey)
        assertTrue(store.device(sam.id)!!.ownerNotices.any { it.text == "Jo left Tuesday group." })
    }

    @Test
    fun twoGroupsMakeOnePairKeptUntilBothAreLeft() = run { http ->
        val a = http.phone(); val b = http.phone()
        val g1 = http.create(a, "One"); val g2 = http.create(a, "Two")
        http.join(b, g1.code, "B"); http.join(b, g2.code, "B")
        assertEquals(1, partners(a).size)
        assertEquals(setOf(g1.groupId, g2.groupId), partners(a).single().groupIds.toSet())
        http.leave(b, g1.groupId)
        assertEquals(1, partners(a).size)
        assertEquals(false, store.device(a.id)!!.rotateKey)
        http.leave(b, g2.groupId)
        assertEquals(0, partners(a).size)
        assertTrue(store.device(a.id)!!.rotateKey)
        // The last member leaving deletes the group.
        http.leave(a, g1.groupId)
        assertEquals(null, store.group(g1.groupId))
    }

    @Test
    fun aGroupHoldsSixAndThenItsCodeStops() = run { http ->
        val first = http.phone()
        val g = http.create(first)
        // Six phones on one Wi-Fi, as at a meeting: each enrolls with the group code.
        repeat(5) { assertEquals(HttpStatusCode.OK, http.join(http.phone(mapOf("X-FF-Group" to g.code)), g.code, "M$it").status) }
        assertEquals(HttpStatusCode.NotFound, http.join(http.phone(mapOf("X-FF-Group" to g.code)), g.code, "Late").status)
        assertEquals(6, store.group(g.groupId)!!.members.size)
        assertEquals(5, partners(first).size)
        // Already in it, or a seventh: refused.
        assertEquals(HttpStatusCode.Conflict, http.group("/v1/groups/code", first, "group_code", g.groupId, buildJsonObject { put("groupId", g.groupId) }).status)
    }

    @Test
    fun aGroupCodeEnrollsAPhoneAndItsQrPageOpensTheApp() = run(Config(publicUrl = "https://ff.test", enrollToken = "boot", now = { clock })) { http ->
        val logan = http.phone(mapOf("X-FF-Enroll" to "boot"))
        val g = http.create(logan)
        val sam = http.phone(mapOf("X-FF-Group" to g.code))
        assertEquals(HttpStatusCode.OK, http.join(sam, g.code, "Sam").status)
        val page = http.get("/c/${normalizeCode(g.code)}").bodyAsText()
        assertTrue(page.contains("Join Tuesday group") && page.contains("intent://join?") && page.contains("Logan, Sam"))
        clock += Groups.CODE_TTL_MS + 1
        assertEquals(HttpStatusCode.NotFound, http.join(http.phone(mapOf("X-FF-Enroll" to "boot")), g.code, "Late").status)
    }
}
