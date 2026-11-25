package app.focusfence.dns

/**
 * When to take an accountability screenshot. Pure so the timing logic is
 * unit-tested; the Android side supplies "is a watched app in front", "is the
 * screen on", the clock, and a random draw.
 *
 * Sampling is sparse and randomized on purpose: a fixed interval is both
 * predictable (easy to wait out) and wasteful. The interval is drawn uniformly
 * around the level's target so the mean matches the target but no two gaps are
 * the same. Capture never happens with the screen off or outside a watched app,
 * which is what keeps the battery cost bounded (measured via diag like
 * everything else).
 */
object CapturePolicy {

    /** targetMs = mean gap between captures; minMs = hard floor (also the API's own rate limit guard). */
    enum class Level(val targetMs: Long, val minMs: Long) {
        LOW(4 * 60_000, 2 * 60_000),
        STANDARD(90_000, 45_000),
        HIGH(45_000, 20_000),
    }

    /**
     * The next time a capture is due after one at [lastAt]. [rnd] in [0,1) is a
     * random draw. The gap is uniform in [minMs, 2*targetMs - minMs], so its
     * mean is exactly targetMs and it never dips below the floor.
     */
    fun nextDueAt(lastAt: Long, level: Level, rnd: Double, sameStreak: Int = 0): Long {
        val span = 2 * (level.targetMs - level.minMs)
        val interval = level.minMs + (rnd.coerceIn(0.0, 1.0) * span).toLong()
        return lastAt + interval * backoff(sameStreak)
    }

    /**
     * Battery (2026-11-25): each capture copies a full-screen bitmap, and about a quarter of them showed the same
     * screen as the one before. After [sameStreak] unchanged captures in a row the gap doubles, up to 4x; a changed
     * screen, a new window or another app resets it. Video and scrolling change the picture, so they never back off.
     */
    fun backoff(sameStreak: Int): Int = 1 shl sameStreak.coerceIn(0, 2)

    fun shouldCapture(watchedForeground: Boolean, screenOn: Boolean, now: Long, nextDueAt: Long): Boolean =
        watchedForeground && screenOn && now >= nextDueAt
}
