package app.focusfence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
 * Resend for email; Twilio or Telnyx for SMS, chosen by env. With no keys set,
 * messages print to stdout so the prototype runs without any account.
 *
 *   RESEND_API_KEY, FF_MAIL_FROM
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

    @Serializable
    private data class ResendRequest(val from: String, val to: List<String>, val subject: String, val html: String)

    @Serializable
    private data class TelnyxRequest(val from: String, val to: String, val text: String)

    override val smsAvailable: Boolean
        get() = smsProvider in setOf("twilio", "telnyx", "dev") && (smsProvider == "dev" || !smsFrom.isNullOrBlank())

    override suspend fun email(to: String, subject: String, html: String): Boolean {
        if (resendKey.isNullOrBlank()) {
            println("[email:dev] to=$to subject=$subject\n$html")
            return true
        }
        return runCatching {
            client.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $resendKey")
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(ResendRequest.serializer(), ResendRequest(mailFrom, listOf(to), subject, html)))
            }.status.isSuccess()
        }.getOrDefault(false)
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
