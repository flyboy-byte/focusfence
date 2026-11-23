package app.focusfence.dns

/**
 * Turns a classifier's raw per-label scores into a risk verdict and a retention
 * decision. Pure, so the thresholds and the "what do we keep" rules are tested
 * independently of any model.
 *
 * Retention is deliberately conservative by default ([RetentionMode.FLAGGED_ONLY]):
 * an un-flagged screenshot leaves nothing behind but its score, so routine
 * browsing is not stored as images. Images that are kept are encrypted to the
 * partner key before they ever leave the device (see [Ecies]).
 */
object Classification {

    /** How much of a sampled screenshot is retained. */
    enum class RetentionMode { SCORE_ONLY, FLAGGED_ONLY, ALL }

    /** Labels a NSFW image classifier emits that count as risky (Déchaîner/MobileNet-style). */
    val RISK_LABELS = setOf("porn", "hentai", "sexy")

    const val DEFAULT_THRESHOLD = 0.70

    data class Verdict(val label: String, val score: Double, val flagged: Boolean)

    /** Highest-scoring risky label and its probability; null if none of the risk labels are present. */
    fun riskVerdict(scores: Map<String, Double>, threshold: Double = DEFAULT_THRESHOLD): Verdict? {
        val top = scores.entries
            .filter { it.key in RISK_LABELS }
            .maxByOrNull { it.value } ?: return null
        return Verdict(top.key, top.value, top.value >= threshold)
    }

    /** Whether to retain the image bytes (vs. only the score) under [mode]. */
    fun retainImage(mode: RetentionMode, flagged: Boolean): Boolean = when (mode) {
        RetentionMode.SCORE_ONLY -> false
        RetentionMode.FLAGGED_ONLY -> flagged
        RetentionMode.ALL -> true
    }
}
