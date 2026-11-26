package app.focusfence.dns

/**
 * Plan 13: everything a person types, scans or opens to become a partner, in one place. Pure, so it is tested on the
 * JVM.
 *
 * - [Join]: a partner invite. The QR code's link (`https://server/c/CODE`), the emailed link (`https://server/j/SECRET`),
 *   or the invite page's "Open in the app" button (`focusfence://join?server=…&code=…` or `&link=…`). Nothing secret
 *   about the report travels in any of them: the report key reaches the partner's device sealed to its own key.
 * - [Server]: the message from the admin page's People tab, with the server address and an enrollment code. It
 *   connects this phone as the one being watched.
 *
 * Lenient on purpose: chat apps wrap lines, add a trailing full stop or bracket, and people paste the whole message.
 */
object InviteLink {
    sealed class Parsed {
        abstract val server: String
        /** Exactly one of [code] (8 characters, as typed) and [link] (an emailed link's secret) is set. */
        data class Join(override val server: String, val code: String = "", val link: String = "") : Parsed()
        data class Server(override val server: String, val code: String) : Parsed()
    }

    private val WEB = Regex("""(https://[A-Za-z0-9.-]+(?::\d+)?)/(c|j)/([A-Za-z0-9_-]{8,64})""")
    private val SERVER = Regex("""(?i)server:\s*(https://[^\s]+)""")
    private val CODE = Regex("""(?i)enrollment code:\s*([A-Za-z0-9-]{4,})""")
    private val ORIGIN = Regex("""https://[A-Za-z0-9.-]+(?::\d+)?""")

    fun parse(text: String): Parsed? {
        fromOpened(text.trim())?.let { return it }
        WEB.find(text)?.let { m ->
            val (server, kind, value) = m.destructured
            return if (kind == "c") normalizeCode(value).takeIf { it.length == 8 }?.let { Parsed.Join(server, code = it) }
            else value.takeIf { it.length >= 20 }?.let { Parsed.Join(server, link = it) }
        }
        val server = SERVER.find(text)?.groupValues?.get(1)?.trimEnd('/', '.', ',', ')') ?: return null
        val code = CODE.find(text)?.groupValues?.get(1) ?: return null
        return Parsed.Server(server, code)
    }

    /** The invite page's button: `focusfence://join?server=https%3A%2F%2F…&code=…` (or `&link=…`). */
    fun fromOpened(url: String): Parsed.Join? {
        if (!url.startsWith("focusfence://join?")) return null
        val q = url.substringAfter('?').split('&').mapNotNull { kv ->
            kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to (runCatching { java.net.URLDecoder.decode(it[1], "UTF-8") }.getOrNull() ?: return@mapNotNull null) }
        }.toMap()
        val server = q["server"]?.trimEnd('/')?.takeIf { ORIGIN.matches(it) } ?: return null
        q["link"]?.takeIf { it.length in 20..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }?.let { return Parsed.Join(server, link = it) }
        return q["code"]?.let(::normalizeCode)?.takeIf { it.length == 8 }?.let { Parsed.Join(server, code = it) }
    }

    /**
     * A code as typed: case, spaces and dashes don't matter, and the letters people mistake for digits are read as
     * those digits (the server's alphabet has no O, I or L). The server normalizes the same way.
     */
    fun normalizeCode(s: String): String =
        s.uppercase().filter { it.isLetterOrDigit() }.replace('O', '0').replace('I', '1').replace('L', '1')

    /** A server address as typed: "ff.example.com" becomes "https://ff.example.com". Null if it can't be one. */
    fun server(typed: String): String? {
        val t = typed.trim().trimEnd('/').let { if (it.startsWith("https://")) it else if (it.contains("://")) return null else "https://$it" }
        return t.takeIf { ORIGIN.matches(it) }
    }

    /** "K7Q4-M2XD": how a code is shown. */
    fun showCode(code: String): String = normalizeCode(code).chunked(4).joinToString("-")
}
