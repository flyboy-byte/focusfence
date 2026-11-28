package app.focusfence.dns

/**
 * A restart of the filter (how app exclusions take effect) stops it for under a second. That gap must not reach the
 * integrity trail as "stopped" then "running": partners are alerted on both. A restart that doesn't come back is
 * still recorded, by a check made once [MS] has passed. Times are elapsed-realtime milliseconds.
 */
object RestartGrace {
    const val MS = 5_000L

    /** Whether the filter's state should be left out of this integrity check; [until] is 0 when no restart is under way. */
    fun hidesState(running: Boolean, until: Long, now: Long): Boolean = !running && now < until
}
