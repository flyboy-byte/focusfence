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

    /** Sign-in emails go out in the background (audit A4): wait for the n-th one. */
    private fun emailNo(n: Int): String {
        repeat(200) { if (notifier.emails.size >= n) return notifier.emails[n - 1].third; Thread.sleep(10) }
        error("email $n never arrived")
    }
    private fun lastCode(n: Int) = Regex("<b>(\\d{6})</b>").find(emailNo(n))!!.groupValues[1]

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
        val emailed = lastCode(1)

        // Wrong email code first: refused.
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/verify", pcookie, "email_code" to "000000", "second" to "").status)

        // First login: no authenticator yet, so setup.
        val verified = http.form("/admin/verify", pcookie, "email_code" to emailed, "second" to "")
        assertEquals(HttpStatusCode.OK, verified.status)
        val setupSession = assertNotNull(cookieOf(verified, "__Host-ffa"))
        val body = verified.bodyAsText()
        val secret = Regex("<code>([A-Z2-7]{32})</code>").find(body)!!.groupValues[1]
        val setupCsrf = Regex("name='csrf' value='([^']+)'").find(body)!!.groupValues[1]
        assertEquals(HttpStatusCode.Forbidden, http.form("/admin/setup", setupSession, "csrf" to "bad", "totp" to "123456").status)
        val done = http.form("/admin/setup", setupSession, "csrf" to setupCsrf, "totp" to Totp.code(secret, clock / 30_000))
        assertEquals(HttpStatusCode.OK, done.status)
        val recovery = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(done.bodyAsText())!!.groupValues[1].split("<br>")
        assertEquals(8, recovery.size)
        assertFalse(store.dir.resolve("admin.json").readText().contains(recovery[0].replace("-", "")), "recovery codes stored hashed")

        // Setup retires the partial session (audit A5): from here on use the new cookie and its CSRF token.
        val session = cookieOf(done, "__Host-ffa")!!
        val csrf = Regex("name='csrf' value='([^']+)'").find(http.get("/admin") { header(HttpHeaders.Cookie, session) }.bodyAsText())!!.groupValues[1]
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
        val e2 = lastCode(3) // #2 was the test email
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/verify", pc2, "email_code" to e2, "second" to "").status, "email code alone is not enough")
        val l3 = http.form("/admin/login", "", "email" to "boss@example.com")
        val pc3 = cookieOf(l3, "__Host-ffap")!!
        val e3 = lastCode(4)
        assertEquals(HttpStatusCode.OK, http.form("/admin/verify", pc3, "email_code" to e3, "second" to Totp.code(secret, clock / 30_000)).status)

        // A recovery code works once. (An hour on: the per-address cap of 3 emails an hour has reset.)
        clock += 3_700_000
        val l4 = http.form("/admin/login", "", "email" to "boss@example.com")
        val pc4 = cookieOf(l4, "__Host-ffap")!!
        val e4 = lastCode(5)
        assertEquals(HttpStatusCode.OK, http.form("/admin/verify", pc4, "email_code" to e4, "second" to recovery[0]).status)
        val before = notifier.emails.size
        repeat(4) { http.form("/admin/login", "", "email" to "boss@example.com") }
        emailNo(before + 2); Thread.sleep(300) // background sends
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

    @Test
    fun aCorruptAdminStoreRefusesSignInAndIsNeverOverwritten() = run { http ->
        // Audit A2: fail closed.
        val f = store.dir.resolve("admin.json").apply { writeText("{not json") }
        val login = http.form("/admin/login", "", "email" to "boss@example.com")
        val code = lastCode(1)
        val r = http.form("/admin/verify", cookieOf(login, "__Host-ffap")!!, "email_code" to code, "second" to "")
        assertTrue(r.status != HttpStatusCode.OK, "no setup page, no session")
        assertEquals("{not json", f.readText())
    }

    @Test
    fun theServerCliLinkIsSingleUseAndStillAsksForTheAuthenticator() = run { http ->
        // Audit A1: an owner path no one on the internet can use up.
        val tok = AdminLinks.create(store.dir, "boss@example.com", clock)
        val first = http.get("/admin/link/$tok")
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.bodyAsText().contains("One more code"))
        assertEquals(HttpStatusCode.Unauthorized, http.get("/admin/link/$tok").status, "single use")
        val stranger = AdminLinks.create(store.dir, "nobody@example.com", clock)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/admin/link/$stranger").status, "only allow-listed admins")
        // First login through the link leads to authenticator setup, with a fresh session id (audit A5).
        val setup = http.form("/admin/verify", cookieOf(first, "__Host-ffap")!!, "second" to "")
        val partial = cookieOf(setup, "__Host-ffa")!!
        val body = setup.bodyAsText()
        val secret = Regex("<code>([A-Z2-7]{32})</code>").find(body)!!.groupValues[1]
        val csrf = Regex("name='csrf' value='([^']+)'").find(body)!!.groupValues[1]
        val done = http.form("/admin/setup", partial, "csrf" to csrf, "totp" to Totp.code(secret, clock / 30_000))
        val full = cookieOf(done, "__Host-ffa")!!
        assertTrue(full != partial)
        assertEquals(HttpStatusCode.OK, http.get("/admin") { header(HttpHeaders.Cookie, full) }.status)
        assertTrue(!http.get("/admin") { header(HttpHeaders.Cookie, partial) }.bodyAsText().contains("Overview"), "old id retired")
        assertEquals(0, notifier.emails.size, "no email involved")
    }

    @Test
    fun withoutAnEmailProviderNoCodeIsMadeOrPrinted() = testApplication {
        // Audit A6.
        application { module(store, notifier, Config(publicUrl = "https://ff.test", adminEmails = listOf("boss@example.com"), emailConfigured = false, now = { clock })) }
        val r = createClient { followRedirects = false }.submitForm("/admin/login", Parameters.build { append("email", "boss@example.com") })
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("login-link"))
        Thread.sleep(100)
        assertEquals(0, notifier.emails.size)
    }
}
