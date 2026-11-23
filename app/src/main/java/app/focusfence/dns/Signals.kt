package app.focusfence.dns

/**
 * Small pure helpers that turn what the phone sees into measurable signals, so
 * detection can be tuned from real exports rather than guessed:
 *  - classifier outputs recorded in full (every label, not just the top one)
 *    and bucketed, so the flag threshold can be set from the score distribution;
 *  - hostnames found in on-screen text and address bars, checked against the
 *    same category bundles DNS uses — a second detection path that still works
 *    when DNS was cached, the site came through another resolver, or a link was
 *    only shown and never opened.
 */
object Signals {

    /** "porn=0.910,neutral=0.050,…", highest first, 3 decimals. Compact enough to log per shot. */
    fun scoresCompact(scores: Map<String, Double>): String =
        scores.entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}=${"%.3f".format(java.util.Locale.ROOT, it.value)}" }

    /** Decile 0..9 of a 0..1 score (1.0 lands in 9). */
    fun bucket(score: Double): Int = (score * 10).toInt().coerceIn(0, 9)

    // A hostname: labels of letters/digits/hyphens, a dot, then a 2–24 letter TLD.
    private val HOST = Regex("(?i)(?<![a-z0-9@.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?![a-z0-9-])")

    /** File extensions and abbreviations that look like TLDs but aren't sites. */
    private val NOT_TLDS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "pdf", "txt", "apk", "zip", "mp4", "mp3", "mov", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx", "kt", "java", "js", "json", "xml", "html", "htm", "css", "py", "md", "csv", "log",
    )

    /** Hostnames mentioned in [text], lower-cased, de-duplicated, at most [max]. */
    fun hostsIn(text: String, max: Int = 20): List<String> {
        val out = LinkedHashSet<String>()
        for (m in HOST.findAll(text)) {
            val h = m.groupValues[1].lowercase().trimEnd('.')
            val tld = h.substringAfterLast('.')
            if (tld in NOT_TLDS) continue
            if (h.count { it == '.' } == 0) continue
            // "e.g", "i.e", version numbers etc. fail the letter-TLD rule already;
            // single-letter first labels like "a.com" are real, keep them.
            out += h
            if (out.size >= max) break
        }
        return out.toList()
    }

    /**
     * Category hits for [hosts] against the loaded bundle [lists]
     * ([Categories.match]): host → categories, only hosts that matched.
     */
    fun categorize(hosts: Collection<String>, lists: Map<String, Set<String>>): Map<String, List<String>> {
        if (lists.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (h in hosts) {
            val cats = Categories.match(h, lists)
            if (cats.isNotEmpty()) out[h] = cats
        }
        return out
    }

    /** "bet365.com:gambling;xyz.com:adult,scam" for a log field. */
    fun encodeHits(hits: Map<String, List<String>>): String =
        hits.entries.joinToString(";") { "${it.key}:${it.value.joinToString(",")}" }

    /** Distinct categories across [hits], sorted. */
    fun categoriesOf(hits: Map<String, List<String>>): List<String> = hits.values.flatten().distinct().sorted()
}
