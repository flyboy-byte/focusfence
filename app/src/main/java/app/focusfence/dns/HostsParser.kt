package app.focusfence.dns

import java.util.Locale

/**
 * Parses ad-block databases in the two common formats:
 * hosts files ("0.0.0.0 ads.example.com") and plain domain lists.
 */
object HostsParser {

    private val LOCAL_NAMES = setOf(
        "localhost", "localhost.localdomain", "local",
        "broadcasthost", "ip6-localhost", "ip6-loopback",
        "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes",
        "ip6-allrouters", "ip6-allhosts",
    )

    fun parse(lines: Sequence<String>): HashSet<String> {
        val out = HashSet<String>(1 shl 17)
        for (raw in lines) {
            var line = raw
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash)
            line = line.trim()
            if (line.isEmpty()) continue

            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            val hosts = when {
                parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1") ->
                    parts.subList(1, parts.size)
                parts.size == 1 -> parts
                else -> continue
            }
            for (h in hosts) {
                val host = h.lowercase(Locale.ROOT).trim('.')
                if (host in LOCAL_NAMES || !host.contains('.')) continue
                if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) continue
                out.add(host)
            }
        }
        return out
    }
}
