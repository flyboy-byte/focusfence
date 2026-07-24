package app.focusfence.dns

/**
 * Serializes a day's block counters ("reddit.com" -> 42, "_ads" -> 310) to a
 * compact string for prefs storage. Pure for unit-testability. Keys are domain
 * names or the reserved "_ads"/"_guard" buckets — none can contain '=' or ';'.
 */
object StatsCodec {

    const val KEY_ADS = "_ads"
    const val KEY_GUARD = "_guard"

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
            if (domainsOnly && (it.key == KEY_ADS || it.key == KEY_GUARD)) 0 else it.value
        }
}
