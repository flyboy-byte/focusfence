package app.focusfence.dns

/**
 * Strict mode state machine (pure, unit-testable).
 *
 * While strict mode is on, loosening anything — turning blocking off, removing
 * rules, un-blocking apps, or disabling strict mode itself — requires
 * requesting an unlock, waiting [UNLOCK_DELAY_MS], and then making the change
 * inside a [UNLOCK_WINDOW_MS] window (a notification announces when it opens) before the app locks again.
 */
object StrictLock {

    const val UNLOCK_DELAY_MS: Long = 10 * 60_000
    /**
     * How long changes are allowed once the wait is over. 5 minutes proved too
     * short in practice (the wait ends silently, and a multi-step change eats the
     * window); the friction is the 10-minute wait, not a short deadline.
     */
    const val UNLOCK_WINDOW_MS: Long = 15 * 60_000

    sealed class State {
        /** Strict mode disabled: everything editable. */
        data object Off : State()

        /** Strict mode on, no unlock in progress: loosening changes refused. */
        data object Locked : State()

        /** Unlock requested, still waiting. */
        data class Pending(val remainingMs: Long) : State()

        /** Unlock window open: changes allowed for [remainingMs] more. */
        data class Unlocked(val remainingMs: Long) : State()
    }

    /**
     * @param unlockReadyAt epoch millis when a requested unlock opens, or 0 for none.
     */
    fun state(enabled: Boolean, unlockReadyAt: Long, now: Long): State = when {
        !enabled -> State.Off
        unlockReadyAt <= 0 -> State.Locked
        now < unlockReadyAt -> State.Pending(unlockReadyAt - now)
        now < unlockReadyAt + UNLOCK_WINDOW_MS -> State.Unlocked(unlockReadyAt + UNLOCK_WINDOW_MS - now)
        else -> State.Locked
    }

    fun isLocked(state: State): Boolean = state is State.Locked || state is State.Pending
}
