package app.focusfence.dns

/**
 * Risky words in captured on-screen text, by category — a cheap detection
 * signal that needs no network and no model. Deliberately short, unambiguous
 * lists matched as whole words: everyday words with innocent meanings ("slots",
 * "bet", "hot") are left out, because a noisy signal teaches a partner to ignore
 * it. Hits are recorded on the text record and counted; they never block.
 */
object RiskTerms {

    val TERMS: Map<String, List<String>> = mapOf(
        "adult" to listOf(
            "porn", "porno", "pornhub", "xvideos", "xnxx", "xhamster", "onlyfans", "nsfw",
            "nudes", "hentai", "camgirl", "sexcam", "xxx",
        ),
        "gambling" to listOf(
            "casino", "sportsbook", "blackjack", "roulette", "draftkings", "fanduel", "bet365",
            "parlay", "slot machine", "online casino",
        ),
    )

    private val PATTERNS: Map<String, Regex> = TERMS.mapValues { (_, words) ->
        Regex("(?i)(?<![\\p{L}\\p{N}])(" + words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])")
    }

    /** Category → matched terms (lower-cased, distinct), only categories with a hit. */
    fun find(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for ((cat, re) in PATTERNS) {
            val hits = re.findAll(text).map { it.value.lowercase() }.distinct().take(5).toList()
            if (hits.isNotEmpty()) out[cat] = hits
        }
        return out
    }

    /** "adult:nsfw,porn;gambling:casino" for a log field. */
    fun encode(hits: Map<String, List<String>>): String =
        hits.entries.joinToString(";") { "${it.key}:${it.value.joinToString(",")}" }
}
