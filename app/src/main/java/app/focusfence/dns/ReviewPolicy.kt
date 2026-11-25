package app.focusfence.dns

/**
 * The owner's own look at risky screenshots. Separate from partner evidence:
 *
 *  - Partner evidence ([Classification.retainImage]) is kept only above the
 *    flag threshold, encrypted to the partner's key, and the owner can't read it.
 *  - **Review copies** are kept at a lower, owner-chosen percentage so the owner
 *    can see what the AI is reacting to, and mark it "not a problem" or "real".
 *    They live only on this phone (not in backups), expire after [MAX_AGE_MS],
 *    and never leave the device.
 *
 * Marking a review "not a problem" does not touch partner evidence and does not
 * change what the partner is sent; it only feeds tuning (a `rev` log record).
 * Pure so the keep/prune/filename rules are tested.
 */
object ReviewPolicy {

    const val DEFAULT_PERCENT = 50
    val PERCENT_CHOICES = listOf(10, 20, 30, 40, 50, 60, 70, 80)

    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_COUNT = 40

    fun shouldKeep(enabled: Boolean, score: Double?, percent: Int): Boolean =
        enabled && score != null && score * 100 >= percent

    /** [scores] is the compact all-label string (`neutral=0.944,porn=0.007,…`), empty on older files. */
    data class Entry(val t: Long, val pkg: String, val label: String, val percent: Int, val scores: String = "")

    private const val SEP = "~"

    /** Filename carries the metadata, so listing needs no index file to keep in sync. */
    fun fileName(e: Entry): String =
        (listOf(e.t, e.pkg, e.label, e.percent) + listOfNotNull(e.scores.ifEmpty { null })).joinToString(SEP) + ".jpg"

    fun parse(name: String): Entry? {
        if (!name.endsWith(".jpg")) return null
        val p = name.removeSuffix(".jpg").split(SEP)
        if (p.size != 4 && p.size != 5) return null
        val t = p[0].toLongOrNull() ?: return null
        val pct = p[3].toIntOrNull() ?: return null
        return Entry(t, p[1], p[2], pct, p.getOrElse(4) { "" })
    }

    /** `neutral 94% · hentai 3% · …`, highest first, then the body check (D-038) when it ran. */
    fun breakdown(scores: String): String {
        val all = scores.split(',').mapNotNull { kv ->
            val p = kv.split('=')
            val v = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            p[0] to v
        }
        val (body, labels) = all.partition { it.first.startsWith("body.") }
        val main = labels.sortedByDescending { it.second }.joinToString(" · ") { "${it.first} ${Math.round(it.second * 100)}%" }
        val check = body.firstOrNull()?.let { (k, v) ->
            val part = k.removePrefix("body.")
            if (part == "none") "body check: nothing exposed"
            else "body check: ${part.lowercase().replace('_', ' ')} ${Math.round(v * 100)}%"
        }
        return listOfNotNull(main.ifEmpty { null }, check).joinToString("\n")
    }

    /** Names to delete: anything past [maxAgeMs], then the oldest beyond [maxCount]. Unparseable names are removed too. */
    fun toPrune(names: List<String>, now: Long, maxAgeMs: Long = MAX_AGE_MS, maxCount: Int = MAX_COUNT): List<String> {
        val parsed = names.map { it to parse(it) }
        val junk = parsed.filter { it.second == null }.map { it.first }
        val live = parsed.mapNotNull { (n, e) -> e?.let { n to it } }
        val expired = live.filter { now - it.second.t > maxAgeMs }.map { it.first }
        val rest = live.filter { it.first !in expired }.sortedByDescending { it.second.t }
        val overflow = rest.drop(maxCount).map { it.first }
        return junk + expired + overflow
    }
}
