package app.focusfence.dns

/**
 * How readily a screenshot becomes a partner flag (2026-11-27). The body check (D-038) keeps false alarms down, so the
 * owner picks one of three bars for the picture score (with [BodyCheck.FEMALE_BOOST]): at or over it, a picture the
 * body check finds suggestive is a flag. Nudity found by the body check is a flag at any setting. Making it stricter is
 * always allowed; making it less strict is a guarded loosening ([GuardPolicy.Action.LOWER_SENSITIVITY]), so a partner
 * can require their PIN, and partners are told.
 */
enum class Sensitivity(val threshold: Double) {
    STRICT(0.45), STANDARD(0.50), RELAXED(0.70);

    /** Whether going from this to [to] lets more through. */
    fun loosensTo(to: Sensitivity): Boolean = to.threshold > threshold

    companion object {
        val DEFAULT = STANDARD
        fun fromName(s: String?): Sensitivity = values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: DEFAULT

        /** Log detail "to=relaxed,from=standard". */
        fun detail(from: Sensitivity, to: Sensitivity) = "to=${to.name.lowercase()},from=${from.name.lowercase()}"

        /** Whether a [detail] records a loosening. */
        fun loosened(detail: String?): Boolean {
            val d = detail.orEmpty()
            val to = fromName(d.substringAfter("to=", "").substringBefore(','))
            val from = fromName(d.substringAfter("from=", "").substringBefore(','))
            return from.loosensTo(to)
        }
    }
}
