package app.focusfence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How the server reaches a partner. Email is the default channel (reports and
 * alerts); SMS is opt-in per partner and used for alerts only — a weekly report
 * doesn't fit in a text. Providers sit behind this interface so they can be
 * swapped, and tests use a recording fake.
 */
interface Notifier {
    suspend fun email(to: String, subject: String, html: String): Boolean
    suspend fun sms(to: String, text: String): Boolean
    val smsAvailable: Boolean
}

/**
 * Resend for email (preferred), or plain SMTP when no Resend key is set; Twilio or Telnyx for SMS, chosen by env.
 * With neither email set up, messages print to stdout so the prototype runs without any account.
 *
 *   RESEND_API_KEY, FF_MAIL_FROM
 *   FF_SMTP_HOST, FF_SMTP_PORT (587), FF_SMTP_USER, FF_SMTP_PASSWORD, FF_SMTP_TLS = starttls | ssl | none
 *   FF_SMS_PROVIDER = twilio | telnyx | none (default none)
 *   FF_SMS_FROM, TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TELNYX_API_KEY
 */
class LiveNotifier(
    private val resendKey: String?,
    private val mailFrom: String,
    private val smsProvider: String,
    private val smsFrom: String?,
    private val env: (String) -> String?,
) : Notifier {

    private val client = HttpClient(CIO)
    private val log = org.slf4j.LoggerFactory.getLogger("email")

    @Serializable
    private data class ResendRequest(val from: String, val to: List<String>, val subject: String, val html: String)

    @Serializable
    private data class TelnyxRequest(val from: String, val to: String, val text: String)

    override val smsAvailable: Boolean
        get() = smsProvider in setOf("twilio", "telnyx", "dev") && (smsProvider == "dev" || !smsFrom.isNullOrBlank())

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

    override suspend fun sms(to: String, text: String): Boolean = runCatching {
        when (smsProvider) {
            "twilio" -> {
                val sid = env("TWILIO_ACCOUNT_SID") ?: return false
                val token = env("TWILIO_AUTH_TOKEN") ?: return false
                client.submitForm(
                    "https://api.twilio.com/2010-04-01/Accounts/$sid/Messages.json",
                    Parameters.build {
                        append("To", to)
                        append("From", smsFrom ?: return false)
                        append("Body", text)
                    },
                ) { basicAuth(sid, token) }.status.isSuccess()
            }
            "telnyx" -> {
                val key = env("TELNYX_API_KEY") ?: return false
                client.post("https://api.telnyx.com/v2/messages") {
                    header("Authorization", "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(TelnyxRequest.serializer(), TelnyxRequest(smsFrom ?: return false, to, text)))
                }.status.isSuccess()
            }
            "dev" -> {
                println("[sms:dev] to=$to $text")
                true
            }
            else -> false
        }
    }.getOrDefault(false)
}
