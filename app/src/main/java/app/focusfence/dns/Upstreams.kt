package app.focusfence.dns

/**
 * Where allowed lookups are forwarded after the filter has decided. Choosing a
 * resolver never weakens filtering: blocking happens on the phone *before*
 * forwarding, so any resolver only answers what the filter already allowed.
 * (A filtering resolver such as Cloudflare Families just adds a second layer.)
 *
 * Modes:
 *  - [Mode.AUTO]: the network's own DNS servers, then Cloudflare/Google DoH.
 *    The default, because every network allows its own resolver (see the
 *    relay history in docs/architecture.md).
 *  - [Mode.PRESET]: a named public resolver ([PRESETS]).
 *  - [Mode.CUSTOM]: the owner's own servers, one per line: `1.2.3.4`,
 *    `1.2.3.4:5353`, `[2001:db8::1]:53`, `dns.example.com`, or a DoH URL
 *    `https://dns.example.com/dns-query`.
 *
 * With [Config.fallback] on (the default), AUTO's servers are tried after the
 * chosen ones fail, so a dead home server degrades to "slower", not "offline".
 * Pure, so parsing and ordering are unit-tested.
 */
object Upstreams {

    enum class Mode { AUTO, PRESET, CUSTOM }

    sealed class Entry(val key: String) {
        /** Plain DNS over UDP. [host] is an IP literal or a hostname (resolved on the real network). */
        data class Udp(val host: String, val port: Int) : Entry("udp:$host:$port")
        /** DNS-over-HTTPS (RFC 8484). */
        data class Doh(val url: String) : Entry("doh:$url")
    }

    data class Preset(val id: String, val label: String, val entries: List<Entry>)

    val PRESETS = listOf(
        Preset("cloudflare", "Cloudflare", listOf(Entry.Doh("https://1.1.1.1/dns-query"), Entry.Doh("https://1.0.0.1/dns-query"))),
        Preset("cloudflare_family", "Cloudflare for Families (malware + adult)", listOf(Entry.Doh("https://family.cloudflare-dns.com/dns-query"))),
        Preset("quad9", "Quad9 (malware blocking)", listOf(Entry.Doh("https://dns.quad9.net/dns-query"))),
        Preset("google", "Google", listOf(Entry.Doh("https://8.8.8.8/dns-query"), Entry.Doh("https://8.8.4.4/dns-query"))),
    )

    /** AUTO's public fallback after the network's own servers. */
    val PUBLIC_FALLBACK: List<Entry> = listOf(Entry.Doh("https://1.1.1.1/dns-query"), Entry.Doh("https://8.8.8.8/dns-query"))

    data class Config(val mode: Mode, val presetId: String?, val custom: List<Entry>, val fallback: Boolean)

    val DEFAULT = Config(Mode.AUTO, null, emptyList(), fallback = true)

    /** One line → entry, or null if it isn't a usable server. */
    fun parseLine(raw: String): Entry? {
        val s = raw.trim()
        if (s.isEmpty() || s.startsWith("#")) return null
        if (s.startsWith("https://", ignoreCase = true)) {
            val rest = s.substring(8)
            val host = rest.substringBefore('/').substringBefore('?')
            return if (host.isNotEmpty() && ' ' !in s) Entry.Doh(s) else null
        }
        if ("://" in s) return null // http://, tls://… not supported
        // [v6]:port or [v6]
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            if (!isIpv6(host)) return null
            val port = s.substring(end + 1).removePrefix(":").ifEmpty { "53" }.toIntOrNull() ?: return null
            return if (port in 1..65535) Entry.Udp(host, port) else null
        }
        if (isIpv6(s)) return Entry.Udp(s, 53)
        val host = s.substringBefore(':')
        val port = if (':' in s) s.substringAfter(':').toIntOrNull() ?: return null else 53
        if (port !in 1..65535) return null
        return if (isIpv4(host) || isHostname(host)) Entry.Udp(host, port) else null
    }

    /** Parses a multi-line box; returns entries and the lines that didn't parse. */
    fun parse(text: String): Pair<List<Entry>, List<String>> {
        val ok = ArrayList<Entry>()
        val bad = ArrayList<String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val e = parseLine(t)
            if (e == null) bad += t else if (ok.none { it.key == e.key }) ok += e
        }
        return ok to bad
    }

    fun render(entries: List<Entry>): String = entries.joinToString("\n") {
        when (it) {
            is Entry.Doh -> it.url
            is Entry.Udp -> when {
                ':' in it.host -> "[${it.host}]" + if (it.port == 53) "" else ":${it.port}"
                it.port == 53 -> it.host
                else -> "${it.host}:${it.port}"
            }
        }
    }

    /**
     * The order to try upstreams for one query. [network] is the real network's
     * own DNS servers (AUTO's first choice). [preferredKey] (the last upstream
     * that answered) goes first *within its group*, so a working fallback never
     * permanently displaces the owner's chosen server.
     */
    fun order(config: Config, network: List<Entry>, preferredKey: String?): List<Entry> {
        val auto = network + PUBLIC_FALLBACK
        val chosen = when (config.mode) {
            Mode.AUTO -> return prefer(auto, preferredKey)
            Mode.PRESET -> PRESETS.firstOrNull { it.id == config.presetId }?.entries.orEmpty()
            Mode.CUSTOM -> config.custom
        }
        // A misconfigured choice (unknown preset, empty custom list) must not mean "no DNS".
        if (chosen.isEmpty()) return prefer(auto, preferredKey)
        val rest = if (config.fallback) auto.filter { a -> chosen.none { it.key == a.key } } else emptyList()
        return prefer(chosen, preferredKey) + prefer(rest, preferredKey)
    }

    private fun prefer(list: List<Entry>, key: String?): List<Entry> =
        if (key == null) list else list.sortedBy { if (it.key == key) 0 else 1 }

    /** Settings round-trip: prefs keep the mode, preset id, custom text and fallback flag. */
    fun fromPrefs(mode: String?, presetId: String?, customText: String?, fallback: Boolean): Config {
        val m = runCatching { Mode.valueOf(mode ?: "") }.getOrDefault(Mode.AUTO)
        return Config(m, presetId, parse(customText.orEmpty()).first, fallback)
    }

    fun describe(config: Config): String = when (config.mode) {
        Mode.AUTO -> "Automatic (network DNS, then Cloudflare/Google)"
        Mode.PRESET -> PRESETS.firstOrNull { it.id == config.presetId }?.label ?: "Automatic"
        Mode.CUSTOM -> if (config.custom.isEmpty()) "Custom (none set — using automatic)"
            else "Custom: " + render(config.custom).replace("\n", ", ")
    }

    private fun isIpv4(s: String): Boolean {
        val p = s.split('.')
        return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() <= 255 }
    }

    private fun isIpv6(s: String): Boolean =
        s.count { it == ':' } >= 2 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    private fun isHostname(s: String): Boolean =
        s.length in 1..253 && '.' in s && !s.all { it.isDigit() || it == '.' } &&
            s.split('.').all { l -> l.isNotEmpty() && l.length <= 63 && l.all { it.isLetterOrDigit() || it == '-' } && !l.startsWith('-') }
}
