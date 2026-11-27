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
            "porno", "hentai", "camgirl", "sexcam", "blowjob", "handjob", "creampie", "cumshot", "gangbang",
            "deepthroat", "milf", "nudes", "sex video", "sex tape", "sex chat", "free porn", "porn video", "send nudes",
            "nude pics", "naked pics", "dick pic", "cam girl", "cam show",
        ) + weak(
            // Explicit, but also used as labels, jokes or slang: two of these, or one with a strong word.
            // "porn" itself is here: in a real export every sighting of it was about quitting it, none was porn.
            "porn", "nsfw", "xxx", "boobs", "tits", "pussy", "horny", "nude", "nsfl",
        ),
        "gambling" to strong(
            "casino", "sportsbook", "blackjack", "roulette", "draftkings", "fanduel", "bet365", "parlay",
            "slot machine", "online casino",
        ),
    )

    // ---- The index (plan 17). One pass over the screen's words, however many phrases the lists hold. ----

    /** Every listed word or phrase, as space-joined plain words, and with the spaces dropped ("porn hub" → "pornhub"). */
    private val INDEX: Map<String, Pair<String, Term>> = buildMap {
        for ((cat, terms) in TERMS) for (t in terms) {
            put(t.word, cat to t)
            if (' ' in t.word) putIfAbsent(t.word.replace(" ", ""), cat to t)
        }
    }

    /** The longest phrase, in words. */
    private val MAX_WORDS = TERMS.values.flatten().maxOf { it.word.count { c -> c == ' ' } + 1 }

    /** Plain words that appear in a listed term: a disguised word is turned back into one of these. */
    private val VOCAB: Set<String> = TERMS.values.flatten().flatMap { it.word.split(' ') + it.word.replace(" ", "") }.toSet()

    // Look-alike characters people swap in, to the letter they stand for. '*' stands for any vowel.
    private val LOOKALIKE = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i')
    private const val VOWELS = "aeiou"

    /** A word of the screen: letters, digits and the look-alike symbols; anything else separates words. */
    private val WORD = Regex("[\\p{L}\\p{N}@$*!]+")

    /** Invisible characters, removed before anything else. */
    private val INVISIBLE = Regex("[\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]")
    private val MARKS = Regex("\\p{Mn}+")

    /** Fancy letters to plain ones, accents and invisible characters dropped. */
    fun normalize(text: String): String {
        val compat = Normalizer.normalize(text.replace(INVISIBLE, ""), Normalizer.Form.NFKC)
        return Normalizer.normalize(compat, Normalizer.Form.NFD).replace(MARKS, "")
    }

    /** [w] as a listed word if it is one in disguise (p0rn, p*rn, MILFs), else [w] itself. */
    private fun plain(w: String): String {
        fun known(x: String): String? = when {
            x in VOCAB -> x
            x.length > 4 && x.endsWith("es") && x.dropLast(2) in VOCAB -> x.dropLast(2)
            x.length > 3 && x.endsWith("s") && x.dropLast(1) in VOCAB -> x.dropLast(1)
            else -> null
        }
        known(w)?.let { return it }
        // Only a word mixing letters with digits or symbols is a disguise; "2026" or "365" stays a number.
        if (w.none { it.isLetter() } || w.all { it.isLetterOrDigit() && !it.isDigit() }) return w
        val swapped = w.map { LOOKALIKE[it] ?: it }.joinToString("")
        known(swapped)?.let { return it }
        val stars = swapped.count { it == '*' }
        if (stars in 1..2) {
            var forms = listOf(swapped)
            repeat(stars) { forms = forms.flatMap { f -> VOWELS.map { v -> f.replaceFirst('*', v) } } }
            forms.firstNotNullOfOrNull(::known)?.let { return it }
        }
        return w
    }

    /** The screen's words, plain and lower-case, with their offsets; runs of 3+ single characters joined (p o r n). */
    private fun words(text: String): List<Pair<Int, String>> {
        val raw = WORD.findAll(normalize(text).lowercase()).map { it.range.first to it.value.trim('!', '*') }.filter { it.second.isNotEmpty() }.toList()
        val out = ArrayList<Pair<Int, String>>(raw.size)
        var i = 0
        while (i < raw.size) {
            var j = i
            while (j < raw.size && raw[j].second.length == 1) j++
            if (j - i >= 3) {
                out += raw[i].first to plain(raw.subList(i, j).joinToString("") { it.second })
                i = j
            } else {
                out += raw[i].first to plain(raw[i].second)
                i++
            }
        }
        return out
    }

    /**
     * Category → matched words (as listed, distinct, at most 5, in the order they appear), only categories with a
     * hit. The longest phrase at each place wins, so "online casino" isn't also "casino".
     */
    fun find(text: String): Map<String, List<String>> {
        val w = words(text)
        val found = LinkedHashMap<String, LinkedHashSet<String>>()
        var i = 0
        while (i < w.size) {
            var matched = 0
            for (n in minOf(MAX_WORDS, w.size - i) downTo 1) {
                val parts = w.subList(i, i + n).map { it.second }
                // Words run together ("porn hub" → pornhub) only when one of them is listed itself: "only fans of the
                // show" must not become onlyfans.
                val joined = if (n > 1 && parts.any { it in INDEX }) INDEX[plain(parts.joinToString(""))] else null
                val hit = INDEX[parts.joinToString(" ")] ?: joined ?: continue
                found.getOrPut(hit.first) { LinkedHashSet() } += hit.second.word
                matched = n
                break
            }
            i += maxOf(matched, 1)
        }
        return found.mapValues { it.value.take(5) }.filterValues { it.isNotEmpty() }
    }

    /**
     * Words that mean the screen is about *quitting* or *detecting* porn: recovery apps and their pages, accountability
     * emails, chats about this kind of app. In a real export these were on every screen that named porn. A screen with
     * one is still recorded and still checked by a screenshot, but never alerts. Words porn sites also use ("partner
     * sites", "filter", "ad blocker", "flagged") are left out on purpose, so they can't hide a real hit.
     */
    private val RECOVERY = Regex(
        "(?i)(?<![\\p{L}])(quit(ting)?|accountab\\w*|addict\\w*|recover\\w*|sobriety|relapse\\w*|detectors?|classifiers?|nudenet|" +
            "struggl\\w*|nofap|covenant eyes|focus fence)(?![\\p{L}])"
    )

    fun aboutQuitting(text: String): Boolean = RECOVERY.containsMatchIn(normalize(text))

    /** Partners are alerted for [text]: [strong] words, and the screen isn't about quitting ([aboutQuitting]). */
    fun alertable(text: String, hits: Map<String, List<String>>): Boolean = strong(hits) && !aboutQuitting(text)

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
