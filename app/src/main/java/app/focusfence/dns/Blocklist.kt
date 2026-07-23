package app.focusfence.dns

import java.util.Locale

/**
 * A user rule for [domain] (and subdomains). Exactly one of three modes:
 * block always, block during a daily time window (may wrap midnight,
 * e.g. 22:00–06:00), or a daily usage budget in minutes after which the
 * domain is blocked for the rest of the day.
 */
data class BlockRule(
    val domain: String,
    val startMinute: Int = ALWAYS,
    val endMinute: Int = ALWAYS,
    val budgetMinutes: Int = 0,
) {
    val isScheduled: Boolean get() = startMinute != ALWAYS
    val hasBudget: Boolean get() = budgetMinutes > 0

    /** True if this rule is in effect at [minuteOfDay] (0..1439). */
    fun activeAt(minuteOfDay: Int): Boolean {
        if (!isScheduled) return true
        return if (startMinute <= endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    fun encode(): String = when {
        hasBudget -> "$domain|b|$budgetMinutes"
        isScheduled -> "$domain|$startMinute|$endMinute"
        else -> domain
    }

    companion object {
        const val ALWAYS = -1

        fun decode(encoded: String): BlockRule? {
            val parts = encoded.split('|')
            return when (parts.size) {
                1 -> parts[0].takeIf { it.isNotEmpty() }?.let { BlockRule(it) }
                3 -> {
                    if (parts[0].isEmpty()) return null
                    if (parts[1] == "b") {
                        val minutes = parts[2].toIntOrNull() ?: return null
                        if (minutes !in 1..1439) return null
                        return BlockRule(parts[0], budgetMinutes = minutes)
                    }
                    val start = parts[1].toIntOrNull() ?: return null
                    val end = parts[2].toIntOrNull() ?: return null
                    if (start !in 0..1439 || end !in 0..1439) return null
                    BlockRule(parts[0], start, end)
                }
                else -> null
            }
        }
    }
}

/** Pure matching/normalization logic, kept free of Android types so it is unit-testable. */
object Blocklist {

    /**
     * DoH bootstrap hosts that are always answered NXDOMAIN while the VPN runs, so
     * browsers cannot silently bypass the filter with their own encrypted DNS.
     * "use-application-dns.net" is the standard canary that tells Firefox to
     * disable its built-in DoH.
     */
    val DOH_BYPASS_GUARDS: Set<String> = setOf(
        "use-application-dns.net",
        "chrome.cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "cloudflare-dns.com",
        "dns.google",
        "dns.quad9.net",
        "doh.opendns.com",
        "dns.nextdns.io",
    )

    /**
     * Cleans up user input into a bare registrable hostname:
     * trims, lowercases, strips scheme/path/port and a leading "www.".
     * Returns null if nothing that looks like a domain remains.
     */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringBefore(':')
        s = s.removePrefix("www.")
        s = s.trim('.')
        if (s.isEmpty() || !s.contains('.')) return null
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        if (s.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return s
    }

    /** True if [qname] equals a blocked domain or is a subdomain of one. */
    fun matches(qname: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        if (qname in blocked) return true
        return parentDomains(qname).any { it in blocked }
    }

    /** Finds the rule covering [qname] (exact domain or nearest parent), if any. */
    fun findRule(qname: String, rules: Map<String, BlockRule>): BlockRule? {
        if (rules.isEmpty()) return null
        rules[qname]?.let { return it }
        for (parent in parentDomains(qname)) {
            rules[parent]?.let { return it }
        }
        return null
    }

    /** Walks parent domains: a.b.example.com -> b.example.com -> example.com */
    private fun parentDomains(qname: String) = sequence {
        var idx = qname.indexOf('.')
        while (idx in 0 until qname.length - 1) {
            yield(qname.substring(idx + 1))
            idx = qname.indexOf('.', idx + 1)
        }
    }
}
