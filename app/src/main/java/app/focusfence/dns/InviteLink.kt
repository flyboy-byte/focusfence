package app.focusfence.dns

/**
 * D-035: everything a person pastes into "Join with an invite", in one place. Pure, so it is tested on the JVM.
 *
 * - [Partner]: a partner invite from someone's phone, `https://server/i/SECRET#k=REPORT_KEY&p=PAIRING_KEY`. Opened in
 *   a browser it is the email partner's join page (the browser never sends the part after '#'); pasted into the app it
 *   enrolls this phone with SECRET and starts watching with the pairing key.
 * - [Server]: the message from the admin page's People tab, with the server address and an enrollment code. It
 *   connects this phone as the one being filtered.
 *
 * Lenient on purpose: chat apps wrap lines, add a trailing full stop or bracket, and people paste the whole message.
 */
object InviteLink {
    sealed class Parsed {
        abstract val server: String
        data class Partner(override val server: String, val secret: String, val reportKey: String?, val pairingKey: String?) : Parsed()
        data class Server(override val server: String, val code: String) : Parsed()
    }

    private val LINK = Regex("""(https://[A-Za-z0-9.-]+(?::\d+)?)/i/([A-Za-z0-9_-]{20,})(#[^\s]*)?""")
    private val SERVER = Regex("""(?i)server:\s*(https://[^\s]+)""")
    private val CODE = Regex("""(?i)enrollment code:\s*([A-Za-z0-9-]{4,})""")

    fun parse(text: String): Parsed? {
        LINK.find(text)?.let { m ->
            val frag = m.groupValues[3].removePrefix("#").trimEnd('.', ',', ')', ']')
            val params = frag.split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return Parsed.Partner(m.groupValues[1], m.groupValues[2], params["k"]?.ifEmpty { null }, params["p"]?.ifEmpty { null })
        }
        val server = SERVER.find(text)?.groupValues?.get(1)?.trimEnd('/', '.', ',', ')') ?: return null
        val code = CODE.find(text)?.groupValues?.get(1) ?: return null
        return Parsed.Server(server, code)
    }

    /**
     * Where an app partner polls for alerts, from their report link (`https://server/p/TOKEN#k=…`): the server origin
     * and the path `/p/TOKEN/alerts`. Null for anything else, so a bad stored link just means no server alerts.
     */
    fun alertsTarget(reportUrl: String): Pair<String, String>? {
        val m = REPORT.matchEntire(reportUrl.substringBefore('#')) ?: return null
        return m.groupValues[1] to "/p/${m.groupValues[2]}/alerts"
    }

    /** Where an app partner says they're leaving (plan 12), so the owner and the other partners are told. */
    fun leaveTarget(reportUrl: String): Pair<String, String>? =
        alertsTarget(reportUrl)?.let { (origin, path) -> origin to path.removeSuffix("/alerts") + "/leave" }

    private val REPORT = Regex("""(https://[A-Za-z0-9.-]+(?::\d+)?)/p/([A-Za-z0-9_-]{20,})/?""")

    /** The partner invite as the inviting phone shares it. */
    fun partnerLink(joinUrl: String, reportKey: String, pairingKey: String): String = "$joinUrl#k=$reportKey&p=$pairingKey"
}
