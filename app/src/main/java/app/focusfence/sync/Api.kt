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

    companion object {
        /** Set once at app start (FocusFenceApp): records the protocol levels of the server that answered. */
        @Volatile var onServerProtocol: ((base: String, level: Int, oldest: Int) -> Unit)? = null
    }

    fun post(path: String, body: JSONObject, headers: Map<String, String> = emptyMap()): Response =
        call("POST", path, body.toString(), headers)

    fun get(path: String, headers: Map<String, String> = emptyMap()): Response = call("GET", path, null, headers)

    private fun call(method: String, path: String, body: String?, headers: Map<String, String>): Response {
        // Counted for the export (diag perf): how many requests sync makes, how long they take, and how they fail. One
        // counter per kind keeps it cheap; nothing about the address or the content is recorded.
        val perf = app.focusfence.monitor.Monitor.perf
        val t0 = android.os.SystemClock.elapsedRealtime()
        perf.count("http_req")
        return try {
            callOnce(method, path, body, headers).also { r ->
                perf.count("http_ms", android.os.SystemClock.elapsedRealtime() - t0)
                if (r.code !in 200..299) perf.count(if (r.code >= 500) "http_5xx" else "http_4xx")
            }
        } catch (e: java.io.IOException) {
            perf.count("http_fail_" + app.focusfence.dns.Faults.netKind(e))
            throw e
        }
    }

    private fun callOnce(method: String, path: String, body: String?, headers: Map<String, String>): Response {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty(app.focusfence.dns.Protocol.HEADER_APP, app.focusfence.dns.Protocol.LEVEL.toString())
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            // D-041: remember which protocol levels the server speaks (absent: a server from before this, level 1).
            val level = conn.getHeaderField(app.focusfence.dns.Protocol.HEADER_SERVER)?.toIntOrNull()
            val oldest = conn.getHeaderField(app.focusfence.dns.Protocol.HEADER_SERVER_OLDEST)?.toIntOrNull()
            onServerProtocol?.invoke(base, level ?: 1, oldest ?: 1)
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
