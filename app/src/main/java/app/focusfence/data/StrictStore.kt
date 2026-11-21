package app.focusfence.data

import android.content.Context
import app.focusfence.dns.StrictLock

/** Persists strict-mode settings and answers lock-state queries. */
object StrictStore {

    const val KEY_ENABLED = "strict_enabled"
    const val KEY_UNLOCK_READY_AT = "strict_unlock_ready_at"

    fun isEnabled(context: Context): Boolean =
        BlocklistStore.prefs(context).getBoolean(KEY_ENABLED, false)

    fun state(context: Context, now: Long = System.currentTimeMillis()): StrictLock.State =
        StrictLock.state(
            isEnabled(context),
            BlocklistStore.prefs(context).getLong(KEY_UNLOCK_READY_AT, 0L),
            now,
        )

    fun isLocked(context: Context): Boolean = StrictLock.isLocked(state(context))

    fun setEnabled(context: Context, enabled: Boolean) {
        app.focusfence.UnlockReadyReceiver.cancel(context)
        BlocklistStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putLong(KEY_UNLOCK_READY_AT, 0L)
            .apply()
    }

    fun requestUnlock(context: Context, now: Long = System.currentTimeMillis()) {
        BlocklistStore.prefs(context).edit()
            .putLong(KEY_UNLOCK_READY_AT, now + StrictLock.UNLOCK_DELAY_MS)
            .apply()
        app.focusfence.UnlockReadyReceiver.schedule(context, now + StrictLock.UNLOCK_DELAY_MS)
    }

    /**
     * Opens the unlock window immediately (used by a correct PIN unlock). The
     * timed path still exists as a fallback for a forgotten PIN, so a PIN never
     * permanently locks the user out.
     */
    fun unlockNow(context: Context, now: Long = System.currentTimeMillis()) {
        app.focusfence.UnlockReadyReceiver.cancel(context)
        BlocklistStore.prefs(context).edit().putLong(KEY_UNLOCK_READY_AT, now).apply()
    }

    fun cancelUnlock(context: Context) {
        app.focusfence.UnlockReadyReceiver.cancel(context)
        BlocklistStore.prefs(context).edit().putLong(KEY_UNLOCK_READY_AT, 0L).apply()
    }
}
