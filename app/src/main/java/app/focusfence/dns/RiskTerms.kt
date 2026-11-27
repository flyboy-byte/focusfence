package app.focusfence.dns

import java.text.Normalizer

/**
 * Risky words in captured on-screen text, by category: a cheap detection signal that needs no network and no model.
 * Matched as whole words, so everyday words with innocent meanings ("slots", "bet", "hot", "naked eye") stay out:
 * a noisy signal teaches a partner to ignore it.
 *
 * Plan 17: text built to dodge a word filter is matched too. Fancy Unicode letters (𝐩𝐨𝐫𝐧, ｐｏｒｎ), accents (pörn),
 * invisible characters, digit and symbol swaps (p0rn, p*rn, s3x), letters spaced out (p o r n, p.o.r.n) and plurals.
 * A hit is recorded as the plain word, not the disguise.
 *
 * [strong] decides whether a screen's words are clear enough to tell partners right away.
 */
object RiskTerms {

    /** A word or phrase. A [strong] one alone is enough to alert; weak ones need company (see [strong]). */
    data class Term(val word: String, val strong: Boolean)

    private fun strong(vararg w: String) = w.map { Term(it, true) }
    private fun weak(vararg w: String) = w.map { Term(it, false) }

    val TERMS: Map<String, List<Term>> = mapOf(
        "adult" to strong(
            // Sites and brands: nobody names these by accident.
            "pornhub", "xvideos", "xnxx", "xhamster", "onlyfans", "fansly", "spankbang", "redtube", "youporn",
            "brazzers", "chaturbate", "stripchat", "livejasmin", "bongacams", "camsoda", "eporner", "motherless",
            "nhentai", "rule34", "rule 34", "manyvids", "gonewild",
            // Words and phrases with no innocent everyday meaning.
            "porn", "porno", "hentai", "camgirl", "sexcam", "blowjob", "handjob", "creampie", "cumshot", "gangbang",
            "deepthroat", "milf", "nudes", "sex video", "sex tape", "sex chat",
        ) + weak(
            // Explicit, but also used as labels, jokes or slang: two of these, or one with a strong word.
            "nsfw", "xxx", "boobs", "tits", "pussy", "horny", "nude", "nsfl",
        ),
        "gambling" to strong(
            "casino", "sportsbook", "blackjack", "roulette", "draftkings", "fanduel", "bet365", "parlay",
            "slot machine", "online casino",
        ),
    )

    // Look-alike characters people swap in. Vowels may also be a '*'.
    private val LOOKALIKE = mapOf(
        'a' to "a4@", 'e' to "e3", 'i' to "i1!", 'o' to "o0", 's' to "s5$", 't' to "t7", 'l' to "l1",
    )
    private const val VOWELS = "aeiou"

    // Between the letters of a word of 4+ letters: one space, dot, dash, underscore or star ("p o r n", "p.o.r.n").
    private const val SPACED = "[\\s.\\-_*]?"

    private fun letter(c: Char): String {
        val set = (LOOKALIKE[c] ?: c.toString()) + if (c in VOWELS) "*" else ""
        val esc = set.map { if (it.isLetterOrDigit()) "$it" else "\\$it" }.joinToString("")
        return if (set.length == 1) esc else "[$esc]"
    }

    private fun pattern(word: String): Regex {
        val body = word.split(' ').joinToString("[\\s\\-_.]*") { part ->
            part.map(::letter).joinToString(if (word.length >= 4 && ' ' !in word) SPACED else "")
        }
        // Not inside a longer word ("pornography", "xxxl", "milford"), but plurals count ("nudes", "milfs").
        return Regex("(?i)(?<![\\p{L}\\p{N}])$body(?:e?s)?(?![\\p{L}\\p{N}])")
    }

    private val PATTERNS: Map<String, List<Pair<Term, Regex>>> =
        TERMS.mapValues { (_, terms) -> terms.sortedByDescending { it.word.length }.map { it to pattern(it.word) } }

    private val INVISIBLE = Regex("[\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]")
    private val MARKS = Regex("\\p{Mn}+")

    /** Fancy letters to plain ones, accents and invisible characters dropped. */
    fun normalize(text: String): String {
        val compat = Normalizer.normalize(text.replace(INVISIBLE, ""), Normalizer.Form.NFKC)
        return Normalizer.normalize(compat, Normalizer.Form.NFD).replace(MARKS, "")
    }

    /**
     * Category → matched words (as listed, distinct, at most 5, in the order they appear), only categories with a
     * hit. A word found only inside a longer one ("casino" in "online casino", "nude" in "nudes") isn't counted again.
     */
    fun find(text: String): Map<String, List<String>> {
        val plain = normalize(text)
        val out = LinkedHashMap<String, List<String>>()
        for ((cat, patterns) in PATTERNS) {
            val taken = ArrayList<IntRange>()
            val hits = ArrayList<Pair<Int, String>>()
            for ((term, re) in patterns) { // longest first
                val ranges = re.findAll(plain).map { it.range }.filter { r -> taken.none { r.first >= it.first && r.last <= it.last } }.toList()
                if (ranges.isEmpty()) continue
                taken += ranges
                hits += ranges.first().first to term.word
            }
            if (hits.isNotEmpty()) out[cat] = hits.sortedBy { it.first }.map { it.second }.take(5)
        }
        return out
    }

    /** Clear enough to alert partners: a strong adult word, or two different weak ones on one screen. */
    fun strong(hits: Map<String, List<String>>): Boolean {
        val adult = hits["adult"].orEmpty()
        val byWord = TERMS.getValue("adult").associateBy { it.word }
        return adult.any { byWord[it]?.strong == true } || adult.size >= 2
    }

    /** The partner alert's detail: "porn, milf in Chrome", adult words only (the same words the report already shows). */
    fun alertDetail(hits: Map<String, List<String>>, app: String): String =
        hits["adult"].orEmpty().joinToString(", ") + " in " + app

    /** "adult:nsfw,porn;gambling:casino" for a log field. */
    fun encode(hits: Map<String, List<String>>): String =
        hits.entries.joinToString(";") { "${it.key}:${it.value.joinToString(",")}" }
}
