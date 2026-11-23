package app.focusfence.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny JSON-over-HTTPS client for the accountability server (`server/`).
 * HttpURLConnection + org.json (both built into Android): no new dependency for
 * a handful of calls. The VPN only captures DNS to its own address, so these
 * connections take the normal network path.
 */
class Api(private val base: String) {

    class Response(val code: Int, val body: JSONObject?)

    fun post(path: String, body: JSONObject, headers: Map<String, String> = emptyMap()): Response =
        call("POST", path, body.toString(), headers)

    fun get(path: String, headers: Map<String, String> = emptyMap()): Response = call("GET", path, null, headers)

    private fun call(method: String, path: String, body: String?, headers: Map<String, String>): Response {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = runCatching { if (text.isBlank()) null else JSONObject(text) }.getOrNull()
            return Response(code, json)
        } finally {
            // Unlike the DoH relay (which keeps one connection hot), sync runs a few
            // times an hour; releasing the socket is the right trade here.
            conn.disconnect()
        }
    }
}
