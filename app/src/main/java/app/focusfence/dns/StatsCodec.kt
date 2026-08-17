package app.focusfence.dns

/**
 * Serializes a day's block counters ("reddit.com" -> 42, "_ads" -> 310) to a
 * compact string for prefs storage. Pure for unit-testability. Keys are domain
 * names or reserved buckets — none can contain '=' or ';'.
 *
 * List blocks are bucketed per source as "_src:&lt;sourceId&gt;" so the stats
 * screen can say *which* list blocked (W11 provenance). "_ads" is the pre-W11
 * aggregate bucket; it is no longer written but old days still decode to it,
 * so readers must treat it as a list bucket forever.
 */
object StatsCodec {

    const val KEY_ADS = "_ads"
    const val KEY_GUARD = "_guard"
    const val SRC_PREFIX = "_src:"

    /** True for internal buckets — anything that is not a user-rule domain. */
    fun isListBucket(key: String): Boolean =
        key == KEY_ADS || key == KEY_GUARD || key.startsWith(SRC_PREFIX)

    /** The source id inside a "_src:" bucket key, or null for any other key. */
    fun sourceId(key: String): String? =
        if (key.startsWith(SRC_PREFIX)) key.substring(SRC_PREFIX.length) else null

    fun encode(counts: Map<String, Int>): String =
        counts.entries
            .filter { it.key.isNotEmpty() && it.value > 0 }
            .joinToString(";") { "${it.key}=${it.value}" }

    fun decode(encoded: String): MutableMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (encoded.isEmpty()) return out
        for (part in encoded.split(';')) {
            val sep = part.lastIndexOf('=')
            if (sep <= 0) continue
            val count = part.substring(sep + 1).toIntOrNull() ?: continue
            if (count > 0) out[part.substring(0, sep)] = count
        }
        return out
    }

    /** Sum of user-facing blocks, excluding internal buckets when [domainsOnly]. */
    fun total(counts: Map<String, Int>, domainsOnly: Boolean = false): Int =
        counts.entries.sumOf {
            if (domainsOnly && isListBucket(it.key)) 0 else it.value
        }
}
