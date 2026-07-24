package app.focusfence.dns

import java.util.Locale

/**
 * Decides whether a browser URL should be blocked under section rules
 * (rules with [BlockRule.allowPaths]). Pure JVM logic for unit-testability;
 * the accessibility service feeds it URLs read from browser address bars.
 */
object UrlFilter {

    /**
     * True if visiting [rawUrl] is forbidden: its host falls under a rule with
     * path exceptions and the path is not under any allowed prefix. Rules
     * without path exceptions return false — the DNS layer enforces those.
     */
    fun shouldBlock(rawUrl: String, rules: Map<String, BlockRule>): Boolean {
        if (rules.isEmpty()) return false
        val url = rawUrl.trim().lowercase(Locale.ROOT)
        if (url.isEmpty() || url.any { it.isWhitespace() }) return false

        val noScheme = url.substringAfter("://")
        val hostEnd = noScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPart = if (hostEnd >= 0) noScheme.substring(0, hostEnd) else noScheme
        val host = hostPart.substringBefore(':').removePrefix("www.").trim('.')
        if (host.isEmpty() || !host.contains('.')) return false
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return false

        val rule = Blocklist.findRule(host, rules) ?: return false
        if (!rule.hasPathExceptions) return false

        val path = if (hostEnd >= 0 && noScheme[hostEnd] == '/') noScheme.substring(hostEnd) else "/"
        return rule.allowPaths.none { prefix ->
            path == prefix || path.startsWith("$prefix/") ||
                path.startsWith("$prefix?") || path.startsWith("$prefix#")
        }
    }
}
