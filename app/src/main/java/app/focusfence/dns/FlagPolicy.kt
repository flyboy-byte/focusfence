package app.focusfence.dns

/**
 * Decides which classifier hits become a partner flag. A "raw hit" is a risky label at or above the owner's
 * threshold; on its own that is NOT evidence: the only real flag so far (2026-11-20) was a photo with no nudity
 * scored `porn` 0.82 (`docs/research/ai-detection.md`). A raw hit is reported and its image kept for the
 * partner only when one of these holds:
 *
 *  - [STRONG]: the score is very high on its own;
 *  - persistence: the same app had another raw hit within [WINDOW_MS] (a photo scrolled past once is dropped,
 *    sustained viewing is not);
 *  - corroboration: a lower but real score ([CORROBORATED]) while adult words were on screen in the same app
 *    within [WINDOW_MS] ([RiskTerms]).
 *
 * Pure and clock-free (the caller passes `now`), so it is tested on the JVM. Costs nothing at runtime: a
 * couple of map lookups per classified screenshot. The owner's review screen is separate and unchanged.
 * Thresholds here are untuned guesses until the `rev` answers show a real false-alarm rate: don't retune
 * quietly (record why, with the date, in `docs/research/ai-detection.md`).
 */
class FlagPolicy(private val windowMs: Long = WINDOW_MS) {

    enum class Why { STRONG, PERSISTENT, CORROBORATED }

    private val lastHit = HashMap<String, Long>()
    private val lastSignal = HashMap<String, Long>()

    /** Adult words were seen on screen in [pkg] (from text capture). */
    @Synchronized
    fun noteSignal(pkg: String, now: Long) {
        lastSignal[pkg] = now
        if (lastSignal.size > MAX_APPS) prune(lastSignal, now)
    }

    /**
     * A raw hit for [pkg] with [score]; null means "held back, not reported". Always remembers the hit, so a
     * second one soon after is persistent. Callers only pass raw hits (score at or above the owner's threshold).
     */
    @Synchronized
    fun onRawHit(pkg: String, score: Double, now: Long): Why? {
        val prior = lastHit[pkg]
        lastHit[pkg] = now
        if (lastHit.size > MAX_APPS) prune(lastHit, now)
        return when {
            score >= STRONG -> Why.STRONG
            prior != null && now - prior in 0..windowMs -> Why.PERSISTENT
            score >= CORROBORATED && (lastSignal[pkg]?.let { now - it in 0..windowMs } == true) -> Why.CORROBORATED
            else -> null
        }
    }

    private fun prune(m: HashMap<String, Long>, now: Long) {
        m.entries.removeAll { now - it.value > windowMs }
    }

    companion object {
        const val STRONG = 0.90
        const val CORROBORATED = 0.75
        const val WINDOW_MS = 3 * 60_000L
        private const val MAX_APPS = 64
    }
}
