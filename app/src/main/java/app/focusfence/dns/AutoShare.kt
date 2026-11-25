package app.focusfence.dns

/**
 * D-037: a flagged screenshot's blurred thumbnail goes to partners on its own, after [DELAY_MS]. The delay is for the
 * owner's note, not a way out: there is no cancel, and no daily cap (Logan, 2026-11-25). Pure, so it is tested on
 * the JVM.
 */
object AutoShare {
    const val DELAY_MS = 10 * 60_000L

    /** When a share queued at [queuedAt] goes. A manual share (sendAt 0) goes at once. */
    fun sendAt(queuedAt: Long, auto: Boolean): Long = if (auto) queuedAt + DELAY_MS else 0

    fun isDue(sendAt: Long, now: Long): Boolean = now >= sendAt

    /** The partner's alert once it has gone: category and score, never the app's content. */
    fun sentText(label: String, percent: Int): String = "$label $percent%, sent automatically"
}
