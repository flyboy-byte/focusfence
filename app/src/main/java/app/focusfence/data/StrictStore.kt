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

    /**
     * Every strict-mode change goes in the activity log and on to partners right away: an unlock asked for or used
     * where no partner hears about it is the gap an accountability app exists to close (2026-11-23 export: a request
     * left no trace at all). Urgent, so it is on disk before the sync that carries it.
     */
    fun note(context: Context, state: String, detail: String? = null) {
        app.focusfence.data.EventLog.activity(
            app.focusfence.dns.LogEvents.service(System.currentTimeMillis(), "strict", state, detail), urgent = true,
        )
        app.focusfence.sync.Sync.kick(context)
    }

    /** An urgent `svc` record for another component, synced right away (same path as strict-mode changes). */
    fun noteComponent(context: Context, comp: String, state: String, detail: String? = null) {
        app.focusfence.data.EventLog.activity(
            app.focusfence.dns.LogEvents.service(System.currentTimeMillis(), comp, state, detail), urgent = true,
        )
        app.focusfence.sync.Sync.kick(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled != isEnabled(context)) note(context, if (enabled) "enabled" else "disabled")
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
        note(context, "unlock_requested", "wait_min=${StrictLock.UNLOCK_DELAY_MS / 60_000}")
    }

    /**
     * Opens the unlock window immediately (used by a correct PIN unlock). The
     * timed path still exists as a fallback for a forgotten PIN, so a PIN never
     * permanently locks the user out.
     */
    fun unlockNow(context: Context, how: String, now: Long = System.currentTimeMillis()) {
        app.focusfence.UnlockReadyReceiver.cancel(context)
        BlocklistStore.prefs(context).edit().putLong(KEY_UNLOCK_READY_AT, now).apply()
        note(context, "unlocked", how)
    }

    fun cancelUnlock(context: Context) {
        if (StrictLock.state(isEnabled(context), BlocklistStore.prefs(context).getLong(KEY_UNLOCK_READY_AT, 0L), System.currentTimeMillis()) !is StrictLock.State.Locked) note(context, "relocked")
        app.focusfence.UnlockReadyReceiver.cancel(context)
        BlocklistStore.prefs(context).edit().putLong(KEY_UNLOCK_READY_AT, 0L).apply()
    }
}
