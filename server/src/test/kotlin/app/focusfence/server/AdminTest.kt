package app.focusfence.server

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminTest {
    private var clock = 1_800_000_000_000L
    private val store = Store(Files.createTempDirectory("ff-admin").toFile())
    private val notifier = FakeNotifier()
    private val cfg = Config(publicUrl = "https://ff.test", adminEmails = listOf("boss@example.com"), now = { clock })

    @Test
    fun totpMatchesTheRfc6238Vector() {
        // RFC 6238 appendix B, SHA-1, T=59 -> 94287082; the last six digits are what a 6-digit app shows.
        val secret = Totp.base32("12345678901234567890".toByteArray())
        assertEquals("287082", Totp.code(secret, 1))
        assertEquals(1L, Totp.verify(secret, "287082", 59_000))
        assertEquals(null, Totp.verify(secret, "000000", 59_000))
    }

    private fun cookieOf(r: HttpResponse, name: String) = r.headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }?.substringBefore(';')

    private suspend fun HttpClient.form(path: String, cookies: String, vararg p: Pair<String, String>) =
        submitForm(path, Parameters.build { p.forEach { append(it.first, it.second) } }) { header(HttpHeaders.Cookie, cookies) }

    private fun run(block: suspend (HttpClient) -> Unit) = testApplication {
        application { module(store, notifier, cfg) }
        block(createClient { followRedirects = false })
    }

    @Test
    fun theWholeLoginSetupAndCodeFlow() = run { http ->
        // A stranger gets the same page and no email.
        val stranger = http.form("/admin/login", "", "email" to "nobody@example.com")
        assertEquals(HttpStatusCode.OK, stranger.status)
        assertEquals(0, notifier.emails.size)

        val login = http.form("/admin/login", "", "email" to "boss@example.com")
        val pcookie = assertNotNull(cookieOf(login, "__Host-ffap"))
        val emailed = Regex("<b>(\\d{6})</b>").find(notifier.emails.last().third)!!.groupValues[1]

        // Wrong email code first: refused.
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/verify", pcookie, "email_code" to "000000", "second" to "").status)

        // First login: no authenticator yet, so setup.
        val verified = http.form("/admin/verify", pcookie, "email_code" to emailed, "second" to "")
        assertEquals(HttpStatusCode.OK, verified.status)
        val session = assertNotNull(cookieOf(verified, "__Host-ffa"))
        val body = verified.bodyAsText()
        val secret = Regex("<code>([A-Z2-7]{32})</code>").find(body)!!.groupValues[1]
        val csrf = Regex("name='csrf' value='([^']+)'").find(body)!!.groupValues[1]
        assertEquals(HttpStatusCode.Forbidden, http.form("/admin/setup", session, "csrf" to "bad", "totp" to "123456").status)
        val done = http.form("/admin/setup", session, "csrf" to csrf, "totp" to Totp.code(secret, clock / 30_000))
        assertEquals(HttpStatusCode.OK, done.status)
        val recovery = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(done.bodyAsText())!!.groupValues[1].split("<br>")
        assertEquals(8, recovery.size)
        assertFalse(store.dir.resolve("admin.json").readText().contains(recovery[0].replace("-", "")), "recovery codes stored hashed")

        // Dashboard, create a code, see it once.
        assertTrue(http.get("/admin") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Overview"))
        assertTrue(http.get("/admin/codes") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Enrollment codes"))
        assertEquals(HttpStatusCode.Forbidden, http.form("/admin/codes/add", session, "csrf" to "nope", "label" to "x").status)
        val made = http.form("/admin/codes/add", session, "csrf" to csrf, "label" to "Sam", "uses" to "1").bodyAsText()
        assertTrue(made.contains("Shown once"))
        val dev = store.createDevice("spki", clock)
        assertTrue(http.form("/admin/devices/label", session, "csrf" to csrf, "id" to dev.id, "label" to "Sam's phone").bodyAsText().contains("Saved"))
        assertEquals("Sam's phone", store.device(dev.id)!!.label)
        assertTrue(http.form("/admin/test-email", session, "csrf" to csrf).bodyAsText().contains("Test email sent"))
        assertTrue(http.get("/admin/devices") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Sam&#39;s phone"), "labels are escaped")
        assertEquals(1, EnrollCodes(store.dir.resolve("codes.json")).list().size)
        assertTrue(store.dir.resolve("admin-audit.jsonl").readText().contains("code_created"))

        // Second login needs email code AND authenticator; a replayed TOTP step is refused.
        clock += 60_000
        val l2 = http.form("/admin/login", "", "email" to "boss@example.com")
        val pc2 = cookieOf(l2, "__Host-ffap")!!
        val e2 = Regex("<b>(\\d{6})</b>").find(notifier.emails.last().third)!!.groupValues[1]
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/verify", pc2, "email_code" to e2, "second" to "").status, "email code alone is not enough")
        val l3 = http.form("/admin/login", "", "email" to "boss@example.com")
        val pc3 = cookieOf(l3, "__Host-ffap")!!
        val e3 = Regex("<b>(\\d{6})</b>").find(notifier.emails.last().third)!!.groupValues[1]
        assertEquals(HttpStatusCode.OK, http.form("/admin/verify", pc3, "email_code" to e3, "second" to Totp.code(secret, clock / 30_000)).status)

        // A recovery code works once. (An hour on: the per-address cap of 3 emails an hour has reset.)
        clock += 3_700_000
        val l4 = http.form("/admin/login", "", "email" to "boss@example.com")
        val pc4 = cookieOf(l4, "__Host-ffap")!!
        val e4 = Regex("<b>(\\d{6})</b>").find(notifier.emails.last().third)!!.groupValues[1]
        assertEquals(HttpStatusCode.OK, http.form("/admin/verify", pc4, "email_code" to e4, "second" to recovery[0]).status)
        val before = notifier.emails.size
        repeat(4) { http.form("/admin/login", "", "email" to "boss@example.com") }
        assertEquals(2, notifier.emails.size - before, "3 an hour per address: one was used by the recovery login")
    }

    @Test
    fun adminPageDoesNotExistWithoutAnAllowList() = testApplication {
        application { module(store, notifier, Config(publicUrl = "https://ff.test", now = { clock })) }
        assertEquals(HttpStatusCode.NotFound, createClient { followRedirects = false }.get("/admin").status)
    }

    @Test
    fun noSessionNoDashboard() = run { http ->
        assertFalse(http.get("/admin").bodyAsText().contains("Overview"))
        assertFalse(http.get("/admin/devices").bodyAsText().contains("Remove device"))
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/codes/add", "", "csrf" to "x", "label" to "x").status)
    }
}
