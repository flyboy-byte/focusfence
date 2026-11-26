package app.focusfence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How the server reaches a partner without the app: email (alerts, the weekly pointer, invites). Text messages were
 * removed with plan 13 (a scope note for later, if a paid SMS provider is ever worth it). Tests use a recording fake.
 */
interface Notifier {
    suspend fun email(to: String, subject: String, html: String): Boolean
    /** False when nothing can deliver email (no Resend key, no SMTP): email invites can't be sent. */
    val emailAvailable: Boolean get() = true
}

/**
 * Resend for email (preferred), or plain SMTP when no Resend key is set.
 * With neither email set up, messages print to stdout so the prototype runs without any account.
 *
 *   RESEND_API_KEY, FF_MAIL_FROM
 *   FF_SMTP_HOST, FF_SMTP_PORT (587), FF_SMTP_USER, FF_SMTP_PASSWORD, FF_SMTP_TLS = starttls | ssl | none
 */
class LiveNotifier(
    private val resendKey: String?,
    private val mailFrom: String,
    private val env: (String) -> String?,
) : Notifier {

    private val client = HttpClient(CIO)
    private val log = org.slf4j.LoggerFactory.getLogger("email")

    @Serializable
    private data class ResendRequest(val from: String, val to: List<String>, val subject: String, val html: String)

    override val emailAvailable: Boolean
        get() = !resendKey.isNullOrBlank() || !env("FF_SMTP_HOST").isNullOrBlank()

    override suspend fun email(to: String, subject: String, html: String): Boolean {
        if (resendKey.isNullOrBlank()) {
            if (!env("FF_SMTP_HOST").isNullOrBlank()) {
                return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { smtp(to, subject, html) }
            }
            println("[email:dev] to=$to subject=$subject\n$html")
            return true
        }
        // A failed send must never be silent: a partner who never gets their confirmation can't be told why.
        // Resend's error body is a short message with no credentials in it, so it is safe to log (not the address).
        return try {
            val r = client.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $resendKey")
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(ResendRequest.serializer(), ResendRequest(mailFrom, listOf(to), subject, html)))
            }
            if (!r.status.isSuccess()) {
                log.warn("email send failed: HTTP {} {}", r.status.value, r.bodyAsText().take(200))
            }
            r.status.isSuccess()
        } catch (e: Exception) {
            log.warn("email send failed: {}", e::class.simpleName)
            false
        }
    }

    /** One message over SMTP. Fails loudly in the log, never with the address or the password. */
    private fun smtp(to: String, subject: String, html: String): Boolean = try {
        val host = env("FF_SMTP_HOST")!!
        val port = env("FF_SMTP_PORT")?.toIntOrNull() ?: 587
        val mode = (env("FF_SMTP_TLS") ?: if (port == 465) "ssl" else "starttls").lowercase()
        val user = env("FF_SMTP_USER")?.takeIf { it.isNotBlank() }
        val props = java.util.Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", (user != null).toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            when (mode) {
                "ssl" -> { put("mail.smtp.ssl.enable", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
                // No silent fall back to plain text: STARTTLS is required unless the operator says "none" (a local relay).
                "starttls" -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
            }
        }
        val auth = user?.let { u ->
            object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() = jakarta.mail.PasswordAuthentication(u, env("FF_SMTP_PASSWORD").orEmpty())
            }
        }
        val session = jakarta.mail.Session.getInstance(props, auth)
        val msg = jakarta.mail.internet.MimeMessage(session).apply {
            setFrom(jakarta.mail.internet.InternetAddress(mailFrom))
            setRecipients(jakarta.mail.Message.RecipientType.TO, jakarta.mail.internet.InternetAddress.parse(to, true))
            setSubject(subject, "UTF-8")
            setContent(html, "text/html; charset=UTF-8")
        }
        jakarta.mail.Transport.send(msg)
        true
    } catch (e: Exception) {
        log.warn("email send failed (SMTP): {}", e::class.simpleName)
        false
    }
}
