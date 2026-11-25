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

    private fun csrfOf(body: String) = Regex("name='csrf' value='([^']+)'").find(body)!!.groupValues[1]

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
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/verify", pcookie, "email_code" to "000000").status)

        // The emailed code alone signs in (2026-11-25: one way in, not two). No authenticator is forced.
        val verified = http.form("/admin/verify", pcookie, "email_code" to emailed)
        assertEquals(HttpStatusCode.OK, verified.status)
        val session = assertNotNull(cookieOf(verified, "__Host-ffa"))
        assertTrue(verified.bodyAsText().contains("Overview"))
        val csrf = csrfOf(http.get("/admin") { header(HttpHeaders.Cookie, session) }.bodyAsText())

        // Optional authenticator, from Activity.
        assertTrue(http.get("/admin/activity") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Set up authenticator"))
        val start = http.form("/admin/setup/start", session, "csrf" to csrf).bodyAsText()
        assertTrue(start.contains("<svg class='qr'"), "setup shows a QR code")
        val secret = Regex("<code class='key' id='totpKey'>([A-Z2-7]{32})</code>").find(start)!!.groupValues[1]
        assertEquals(HttpStatusCode.Forbidden, http.form("/admin/setup", session, "csrf" to "bad", "totp" to "123456").status)
        val done = http.form("/admin/setup", session, "csrf" to csrf, "totp" to Totp.code(secret, clock / 30_000))
        assertEquals(HttpStatusCode.OK, done.status)
        val recovery = Regex("<pre class='codes' id='recovery'>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).find(done.bodyAsText())!!.groupValues[1].split("\n")
        assertEquals(8, recovery.size)
        assertFalse(store.dir.resolve("admin.json").readText().contains(recovery[0].replace("-", "")), "recovery codes stored hashed")

        // Dashboard, create a code, see it once.
        assertTrue(http.get("/admin/codes") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Enrollment codes"))
        assertEquals(HttpStatusCode.Forbidden, http.form("/admin/codes/add", session, "csrf" to "nope", "label" to "x").status)
        val made = http.form("/admin/codes/add", session, "csrf" to csrf, "label" to "Sam", "uses" to "1").bodyAsText()
        assertTrue(made.contains("shown once"))
        assertTrue(made.contains("Hi Sam, here&#39;s how to join") && made.contains("https://ff.test"), "invite message names the person and the server")
        assertTrue(made.contains("works once"))
        val dev = store.createDevice("spki", clock)
        assertTrue(http.form("/admin/devices/label", session, "csrf" to csrf, "id" to dev.id, "label" to "Sam's phone").bodyAsText().contains("Saved"))
        assertEquals("Sam's phone", store.device(dev.id)!!.label)
        assertTrue(http.form("/admin/test-email", session, "csrf" to csrf).bodyAsText().contains("Test email sent"))
        assertTrue(http.get("/admin/devices") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Sam&#39;s phone"), "labels are escaped")
        assertEquals(1, EnrollCodes(store.dir.resolve("codes.json")).list().size)
        assertTrue(store.dir.resolve("admin-audit.jsonl").readText().contains("code_created"))

        // The authenticator alone signs in too, with no email; a replayed step and a stranger are refused.
        clock += 60_000
        val sent = notifier.emails.size
        val code = Totp.code(secret, clock / 30_000)
        assertEquals(HttpStatusCode.OK, http.form("/admin/totp", "", "email" to "boss@example.com", "code" to code).status)
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/totp", "", "email" to "boss@example.com", "code" to code).status, "replayed step")
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/totp", "", "email" to "nobody@example.com", "code" to code).status)
        assertEquals(sent, notifier.emails.size, "no email involved")

        // A recovery code works once, in the authenticator's place.
        assertEquals(HttpStatusCode.OK, http.form("/admin/totp", "", "email" to "boss@example.com", "code" to recovery[0]).status)
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/totp", "", "email" to "boss@example.com", "code" to recovery[0]).status)

        // 3 emailed codes an hour per address (one already went at the start, and one was the test email).
        clock += 3_700_000
        val before = notifier.emails.size
        repeat(4) { http.form("/admin/login", "", "email" to "boss@example.com") }
        emailNo(before + 3); Thread.sleep(300) // background sends
        assertEquals(3, notifier.emails.size - before)
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
        val r = http.form("/admin/verify", cookieOf(login, "__Host-ffap")!!, "email_code" to code)
        assertTrue(r.status != HttpStatusCode.OK, "no session")
        assertEquals("{not json", f.readText())
    }

    @Test
    fun theServerCliLinkIsSingleUseAndSignsInOnItsOwn() = run { http ->
        // Audit A1: an owner path no one on the internet can use up.
        val tok = AdminLinks.create(store.dir, "boss@example.com", clock)
        // Audit S2: a GET (a chat app's link preview) shows a button and spends nothing.
        repeat(2) { assertTrue(http.get("/admin/link/$tok").bodyAsText().contains("Continue")) }
        val first = http.form("/admin/link/$tok", "")
        assertEquals(HttpStatusCode.OK, first.status)
        val session = assertNotNull(cookieOf(first, "__Host-ffa"))
        assertTrue(http.get("/admin") { header(HttpHeaders.Cookie, session) }.bodyAsText().contains("Overview"))
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/link/$tok", "").status, "single use")
        val stranger = AdminLinks.create(store.dir, "nobody@example.com", clock)
        assertEquals(HttpStatusCode.Unauthorized, http.form("/admin/link/$stranger", "").status, "only allow-listed admins")
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

    @Test
    fun theLoginLinkCliRefusesOutsideTheServiceEnvironment() {
        // Audit S1: an empty directory is not the live store, so no link is written.
        val empty = Files.createTempDirectory("ff-empty").toFile()
        val out = mutableListOf<String>()
        assertTrue(runAdmin(arrayOf("admin", "login-link", "boss@example.com"), empty, clock) { out += it })
        assertTrue(out.single().startsWith("refusing"))
        assertFalse(java.io.File(empty, "admin-links.json").exists())
    }
}
