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
        BlocklistStore.prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putLong(KEY_UNLOCK_READY_AT, 0L)
            .apply()
    }

    fun requestUnlock(context: Context, now: Long = System.currentTimeMillis()) {
        BlocklistStore.prefs(context).edit()
            .putLong(KEY_UNLOCK_READY_AT, now + StrictLock.UNLOCK_DELAY_MS)
            .apply()
    }

    fun cancelUnlock(context: Context) {
        BlocklistStore.prefs(context).edit().putLong(KEY_UNLOCK_READY_AT, 0L).apply()
    }
}
