package app.focusfence.dns

import java.util.Locale

/**
 * A user rule for [domain] (and subdomains). Exactly one mode: block always,
 * block during a daily time window (may wrap midnight, e.g. 22:00–06:00), a
 * daily usage budget in minutes after which the domain is blocked for the rest
 * of the day, or — for app rules — a commitment lasting until a fixed instant.
 *
 * The same encoding is reused for app rules, keyed by package name instead of
 * domain, so a bare package name stored by an older version decodes as
 * always-blocked and needs no migration.
 */
data class BlockRule(
    val domain: String,
    val startMinute: Int = ALWAYS,
    val endMinute: Int = ALWAYS,
    val budgetMinutes: Int = 0,
    /**
     * When non-empty, only these URL path prefixes are allowed (e.g. "/marketplace")
     * and the rest of the site is blocked. Enforced by the accessibility URL filter
     * in browsers, not at the DNS layer — DNS sees hostnames only, so the domain
     * itself must stay resolvable for the allowed sections to work.
     */
    val allowPaths: List<String> = emptyList(),
    /**
     * Wall-clock instant (epoch millis) until which this rule blocks, for
     * "unavailable until 6pm" commitments. Absolute rather than a daily window
     * because a commitment is a one-off promise, not a recurring schedule.
     */
    val commitUntilEpochMillis: Long = 0L,
) {
    val isScheduled: Boolean get() = startMinute != ALWAYS
    val hasBudget: Boolean get() = budgetMinutes > 0
    val hasPathExceptions: Boolean get() = allowPaths.isNotEmpty()
    val hasCommitment: Boolean get() = commitUntilEpochMillis > 0L

    /** True if this rule is in effect at [minuteOfDay] (0..1439). */
    fun activeAt(minuteOfDay: Int): Boolean {
        if (!isScheduled) return true
        return if (startMinute <= endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    // Ordering is deliberate: a rule carrying more than one mode encodes as the
    // most specific one, and decode() reproduces exactly that single mode.
    fun encode(): String = when {
        hasPathExceptions -> "$domain|p|${allowPaths.joinToString(",")}"
        hasCommitment -> "$domain|c|$commitUntilEpochMillis"
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
                    if (parts[1] == "p") {
                        val paths = parts[2].split(',').mapNotNull { Blocklist.normalizePath(it) }
                        if (paths.isEmpty()) return null
                        return BlockRule(parts[0], allowPaths = paths)
                    }
                    if (parts[1] == "c") {
                        val until = parts[2].toLongOrNull() ?: return null
                        if (until <= 0L) return null
                        return BlockRule(parts[0], commitUntilEpochMillis = until)
                    }
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

    /**
     * Cleans a user-entered URL path prefix: "marketplace", "/marketplace/" and
     * "facebook.com/marketplace" all become "/marketplace". Returns null if
     * nothing usable remains (a bare "/" would allow the whole site).
     */
    fun normalizePath(input: String): String? {
        var s = input.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return null
        s = s.substringAfter("://")
        if (!s.startsWith("/")) {
            s = if (s.contains('/')) s.substring(s.indexOf('/')) else "/$s"
        }
        s = s.trimEnd('/')
        if (s.isEmpty() || s == "/") return null
        if (s.any { it == '|' || it == ',' || it.isWhitespace() }) return null
        return s
    }

    private val FRONT_DOOR_PREFIXES = listOf("www.", "m.", "web.", "mobile.", "touch.")

    /**
     * True if [qname] is one of [domain]'s user-facing web hostnames. Time
     * budgets accrue and enforce only on these: hidden subdomains like
     * graph.facebook.com carry SDK/API traffic from unrelated apps, which must
     * neither drain a budget nor break when it runs out.
     */
    fun isFrontDoor(qname: String, domain: String): Boolean {
        if (qname == domain) return true
        return FRONT_DOOR_PREFIXES.any { qname == it + domain }
    }

    /** True if [qname] equals a blocked domain or is a subdomain of one. */
    fun matches(qname: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        if (qname in blocked) return true
        return parentDomains(qname).any { it in blocked }
    }

    /**
     * Null if [qname] is allowed; otherwise the stats bucket: the rule's domain, a
     * "_src:&lt;id&gt;" list bucket, or the DoH guard. Pure so the O-003 ordering (D-017)
     * is unit-testable: a user rule that is currently in force is final and cannot be
     * overridden by [allowlist] — only list-sourced blocks (filter lists, DoH bypass
     * guards) can be. [ruleDomain]/[ruleBlockedNow] are supplied by the caller because
     * whether a rule is *currently* in force depends on the clock and accrued usage,
     * which this package has no access to (see [RuleEvaluator]).
     *
     * [listHosts] maps a blocked host to the id of the filter source that listed it
     * (W11 provenance). Matching walks parent domains: the bare-domain lists the app
     * consumes (OISD/HaGeZi wildcard variants) define an entry as covering all its
     * subdomains, so exact-only matching would silently under-enforce them — the
     * worst failure mode this app has (D-012).
     */
    fun blockReason(
        qname: String,
        ruleDomain: String?,
        ruleBlockedNow: Boolean,
        allowlist: Set<String>,
        listHosts: Map<String, String>,
    ): String? {
        if (ruleDomain != null && ruleBlockedNow) return ruleDomain
        if (matches(qname, allowlist)) return null
        findListSource(qname, listHosts)?.let { return StatsCodec.SRC_PREFIX + it }
        if (matches(qname, DOH_BYPASS_GUARDS)) return StatsCodec.KEY_GUARD
        return null
    }

    /** The source id whose list covers [qname] (exact or parent domain), if any. */
    fun findListSource(qname: String, listHosts: Map<String, String>): String? {
        if (listHosts.isEmpty()) return null
        listHosts[qname]?.let { return it }
        for (parent in parentDomains(qname)) {
            listHosts[parent]?.let { return it }
        }
        return null
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
