package app.focusfence.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sends partner reports and alerts through Resend (https://resend.com). The API
 * key and sender come from env (RESEND_API_KEY, FF_MAIL_FROM); with no key set,
 * email is logged to stdout instead so the prototype runs without an account.
 */
class Email(private val apiKey: String?, private val from: String) {

    private val client = HttpClient(CIO)

    @Serializable
    private data class ResendRequest(val from: String, val to: List<String>, val subject: String, val html: String)

    suspend fun send(to: String, subject: String, html: String) {
        if (apiKey.isNullOrBlank()) {
            println("[email:dev] to=$to subject=$subject\n$html")
            return
        }
        client.post("https://api.resend.com/emails") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ResendRequest.serializer(), ResendRequest(from, listOf(to), subject, html)))
        }
    }
}
