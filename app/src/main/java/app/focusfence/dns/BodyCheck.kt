package app.focusfence.dns

/**
 * D-038: the second check on a risky screenshot, NudeNet's body-part detector. The first model (MobileNet) scores the
 * whole picture and calls close-up faces, skin and wood "porn" (2026-11-25: video-call frames at 65–83%); this one
 * looks for exposed body parts, so a face or a family photo has nothing to find. A partner flag, and so an automatic
 * send (D-037), needs this to find one.
 *
 * Pure, so it is tested on the JVM: [best] reads the raw output, [confirm] decides.
 */
object BodyCheck {
    /** NudeNet 320n output order (nudenet 3.4.2). */
    val LABELS = listOf(
        "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED", "BELLY_COVERED",
        "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE", "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED",
    )

    /** What counts. Feet, armpits, bellies and male chests are exposed in ordinary photos. */
    val EXPLICIT = setOf(
        "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED", "ANUS_EXPOSED",
    )

    /**
     * Covered intimate parts: the shapes of a bra, a bikini or lingerie. Not nudity, but what the owner wants captured
     * as "suggestive" (2026-11-25). On their own they also fire on ordinary tops, so they count only when the first
     * model already found the picture risky (the body check runs only then).
     */
    val SUGGESTIVE = setOf("FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED", "FEMALE_GENITALIA_COVERED")

    /** A covered part this sure makes a risky picture "suggestive". Untuned: on the five false alarms it was ≤ 0.01. */
    const val SUGGEST_AT = 0.45

    enum class Kind { NUDITY, SUGGESTIVE, NONE }

    data class Result(val kind: Kind, val part: String? = null, val score: Double = 0.0)

    /** Nudity beats suggestive; either needs its part at or over its bar. */
    fun classify(best: Map<String, Double>): Result {
        confirm(best)?.let { return Result(Kind.NUDITY, it.first, it.second) }
        best.filterKeys { it in SUGGESTIVE }.maxByOrNull { it.value }?.takeIf { it.value >= SUGGEST_AT }
            ?.let { return Result(Kind.SUGGESTIVE, it.key, it.value) }
        return Result(Kind.NONE)
    }

    /** "female breast covered" for people. */
    fun words(part: String): String = part.lowercase().replace('_', ' ')

    /**
     * A detection this sure confirms. NudeNet's own default keeps boxes from 0.2; 0.4 is stricter because a false
     * confirm now sends a picture. On the five false alarms the highest explicit score was 0.11, so 0.40 was lowered
     * to 0.30 on 2026-11-27 (still well clear of them).
     */
    const val CONFIRM = 0.30

    /**
     * The body check runs only when the first model's risky score is at least this. 0.50 until 2026-11-27, then 0.30:
     * very few screenshots score between the two, so the cost is small, and middling pictures (covers, thumbnails) get
     * looked at for nudity too.
     */
    const val CHECK_FROM = 0.30

    /** A woman in the picture: her face or a female body part, at least this sure. */
    val FEMALE = setOf(
        "FACE_FEMALE", "FEMALE_BREAST_EXPOSED", "FEMALE_BREAST_COVERED", "FEMALE_GENITALIA_EXPOSED", "FEMALE_GENITALIA_COVERED",
    )
    const val FEMALE_AT = 0.40

    /** Added to a picture's risk score when a woman is in it (2026-11-27). Never alone a flag. */
    const val FEMALE_BOOST = 0.10

    fun female(best: Map<String, Double>): Boolean = best.any { it.key in FEMALE && it.value >= FEMALE_AT }

    /** [score] raised by [FEMALE_BOOST] when [best] shows a woman, at most 1. */
    fun judged(score: Double, best: Map<String, Double>?): Double =
        if (best != null && female(best)) minOf(1.0, score + FEMALE_BOOST) else score

    /** Best score per label over every anchor. [out] is the model output, laid out [4 + labels][anchors]. */
    fun best(out: FloatArray, anchors: Int): Map<String, Double> {
        require(out.size >= (4 + LABELS.size) * anchors) { "output too small" }
        return LABELS.mapIndexed { c, label ->
            var m = 0f
            val base = (4 + c) * anchors
            for (a in 0 until anchors) if (out[base + a] > m) m = out[base + a]
            label to m.toDouble()
        }.toMap()
    }

    /** The strongest explicit finding at or over [CONFIRM], or null. */
    fun confirm(best: Map<String, Double>): Pair<String, Double>? =
        best.filterKeys { it in EXPLICIT }.maxByOrNull { it.value }?.toPair()?.takeIf { it.second >= CONFIRM }

    /**
     * For the review card's stored scores (they live in a file name, `key=value` joined by commas): `body.none=0` or
     * `body.FEMALE_BREAST_EXPOSED=0.820`. [ReviewPolicy.breakdown] turns it back into words.
     */
    fun compact(best: Map<String, Double>): String {
        val r = classify(best)
        return if (r.part == null) "body.none=0" else "body.${r.part}=${"%.3f".format(java.util.Locale.ROOT, r.score)}"
    }

    /** For logs: "none found" or "FEMALE_BREAST_EXPOSED 82%". */
    fun summary(best: Map<String, Double>): String {
        val r = classify(best)
        return when (r.kind) {
            Kind.NUDITY -> "${r.part} ${Math.round(r.score * 100)}%"
            Kind.SUGGESTIVE -> "suggestive: ${r.part} ${Math.round(r.score * 100)}%"
            Kind.NONE -> "none found"
        }
    }
}
