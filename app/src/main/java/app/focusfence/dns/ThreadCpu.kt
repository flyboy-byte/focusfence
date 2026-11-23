package app.focusfence.dns

/**
 * Per-thread CPU accounting from `/proc/self/task/<tid>/stat`, so the whole
 * process's CPU — not just the parts we time by hand — is attributed: UI and
 * main thread, garbage collection, AI runtime threads, binder calls, the log
 * writer. The 2026-11-18 export showed hand-timed parts covering ~14 s of
 * ~115 s screen-on CPU; this closes that gap. Pure: the Android side reads the
 * files and supplies the text.
 */
object ThreadCpu {

    data class Sample(val tid: Int, val name: String, val ticks: Long)

    /**
     * Parses one stat line. The name is in parentheses and may itself contain
     * spaces or parens, so fields are counted from the *last* ')'. utime and
     * stime are fields 14 and 15 (1-based), i.e. 12th/13th after the name.
     */
    fun parseStat(line: String): Sample? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val tid = line.substring(0, open).trim().toIntOrNull() ?: return null
        val name = line.substring(open + 1, close)
        val rest = line.substring(close + 1).trim().split(' ')
        if (rest.size < 13) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return Sample(tid, name, utime + stime)
    }

    private val DIGITS = Regex("\\d+")

    /** Collapses numbered pool threads ("pool-3-thread-12" → "pool-N-thread-N") so they aggregate. */
    fun normalize(name: String): String = DIGITS.replace(name.trim(), "N").ifEmpty { "?" }

    /**
     * CPU ms per normalized thread name between two snapshots. Threads that
     * died between samples lose their final slice (unavoidable with /proc); new
     * threads count from zero.
     */
    fun delta(prev: Map<Int, Sample>, now: Map<Int, Sample>, msPerTick: Double): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((tid, s) in now) {
            val before = prev[tid]?.takeIf { it.name == s.name }?.ticks ?: 0L
            val d = s.ticks - before
            if (d <= 0) continue
            val k = normalize(s.name)
            out[k] = (out[k] ?: 0L) + (d * msPerTick).toLong()
        }
        return out
    }

    /** "name=ms;…" top [n] by ms, plus everything else summed as "other". */
    fun encodeTop(ms: Map<String, Long>, n: Int = 10): String {
        val sorted = ms.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        val top = sorted.take(n).map { "${it.key.replace(';', ' ').replace('=', ' ')}=${it.value}" }
        val other = sorted.drop(n).sumOf { it.value }
        return (if (other > 0) top + "other=$other" else top).joinToString(";")
    }

    fun decodeTop(s: String?): Map<String, Long> =
        s.orEmpty().split(';').filter { '=' in it }.associate { it.substringBeforeLast('=') to (it.substringAfterLast('=').toLongOrNull() ?: 0L) }
}
